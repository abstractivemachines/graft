@file:Suppress("FunctionName", "unused")

package com.abstractivemachines.graft

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.annotations.McpToolHintValue
import com.intellij.mcpserver.annotations.McpToolHints
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.ModuleOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.impl.ModifiableModelCommitter
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.projectImport.ProjectAttachProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.isDirectory

/**
 * MCP tools that do what File | Open | Attach and "Remove from Project View" do in the UI,
 * so an agent can bring a git worktree into the current IDE window and take it out again.
 */
class GraftToolset : McpToolset {

    private val log = logger<GraftToolset>()

    override fun isExperimental(): Boolean = false

    @McpTool
    @McpToolHints(readOnlyHint = McpToolHintValue.TRUE, idempotentHint = McpToolHintValue.TRUE)
    @McpDescription(
        """
        |Lists the modules attached to the IDE project window: the primary project plus every attached directory.
        |Each entry says whether it is a linked git worktree and which branch it has checked out.
        |Call this before attach_worktree or detach_worktree to see what is already attached.
        """,
    )
    suspend fun list_attached_worktrees(): AttachedModules {
        val project = currentCoroutineContext().project
        return readAction { snapshot(project) }
    }

    @McpTool
    @McpToolHints(destructiveHint = McpToolHintValue.FALSE, idempotentHint = McpToolHintValue.TRUE)
    @McpDescription(
        """
        |Attaches a directory, typically a git worktree, to the IDE project window as an additional module.
        |This is the same as File | Open | Attach: the directory appears in the Project view beside the primary project
        |and shares the window, run configurations, and terminal. If the directory has no .idea folder the IDE creates
        |one with a module named after the directory. Returns status already_attached, with no changes, when the
        |directory is already a module of this project. Fails with an explanation instead of showing a dialog when the
        |directory carries a copied .idea whose module name collides with an existing module.
        """,
    )
    suspend fun attach_worktree(
        @McpDescription("Absolute path of the directory to attach. A leading ~ is expanded to the home directory.")
        path: String,
    ): AttachResult {
        val project = currentCoroutineContext().project
        val dir = resolveDirectory(path)

        val existing = readAction { findModuleByPath(project, dir) }
        if (existing != null) {
            return AttachResult("already_attached", existing.name, dir.toString(), readAction { snapshot(project).modules })
        }

        if (!ProjectAttachProcessor.canAttachToProject()) {
            mcpFail("This IDE does not support attaching directories to an open project.")
        }

        val moduleNames = readAction { ModuleManager.getInstance(project).modules.map { it.name }.toSet() }
        WorktreePaths.attachBlocker(dir, moduleNames)?.let { mcpFail(it) }

        // The Open dialog would ask whether to trust an unknown location. A tool must not block on a modal dialog,
        // so refuse with instructions instead.
        if (!TrustedProjects.isProjectTrusted(dir, project)) {
            mcpFail(
                "$dir is not in a trusted location, so the IDE would ask before attaching it. Add its parent under " +
                    "Settings | Build, Execution, Deployment | Trusted Locations (or open the directory once and choose Trust), then retry.",
            )
        }

        // Same processor selection as the Open dialog: ModuleAttachProcessor for a directory that already has .idea,
        // ImportNewProjectAttachProcessor for one that does not.
        val processor = ProjectAttachProcessor.getProcessor(project, dir, null)
            ?: mcpFail("No attach processor accepts $dir. Check that it is a plain directory and see idea.log for details.")

        log.info("attach_worktree: attaching $dir to ${project.name} via ${processor.javaClass.simpleName}")
        val attached = try {
            processor.attachToProjectAsync(project, dir, null, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("attach_worktree: attaching $dir failed", e)
            mcpFail("Attaching $dir failed: ${e.message ?: e.javaClass.simpleName}. See idea.log for the stack trace.")
        }
        if (!attached) {
            mcpFail("The IDE declined to attach $dir. See idea.log for the reason.")
        }

        val module = readAction { findModuleByPath(project, dir) }
            ?: mcpFail("Attach reported success but no module has $dir as a content root.")
        return AttachResult("attached", module.name, dir.toString(), readAction { snapshot(project).modules })
    }

    @McpTool
    @McpToolHints(destructiveHint = McpToolHintValue.TRUE, idempotentHint = McpToolHintValue.TRUE)
    @McpDescription(
        """
        |Detaches a previously attached directory from the IDE project window, the same as Remove from Project View.
        |Also removes the module dependency the IDE added to the primary module when it attached. Files on disk are not
        |touched unless deleteIdeaDirectory is true, which removes the worktree's own .idea folder after detaching so a
        |later worktree with the same directory name cannot collide with a stale module file. The primary module of the
        |window cannot be detached.
        """,
    )
    suspend fun detach_worktree(
        @McpDescription("Absolute path of the attached directory, or the exact module name shown by list_attached_worktrees.")
        path: String,
        @McpDescription("Also delete the detached directory's .idea folder. Defaults to false.")
        deleteIdeaDirectory: Boolean = false,
    ): DetachResult {
        val project = currentCoroutineContext().project

        val module = readAction { findModuleByPathOrName(project, path) }
            ?: mcpFail("No attached module matches '$path'. Call list_attached_worktrees to see what is attached.")
        val moduleName = module.name
        val moduleDir = readAction { moduleRoots(module).firstOrNull() }

        if (readAction { isPrimary(project, module) }) {
            mcpFail("'$moduleName' is the primary module of this window and cannot be detached.")
        }

        log.info("detach_worktree: detaching module $moduleName ($moduleDir) from ${project.name}")
        writeCommandAction(project, GraftBundle.message("command.detach")) {
            detach(project, module)
        }

        var deleted = false
        if (deleteIdeaDirectory && moduleDir != null) {
            deleted = withContext(Dispatchers.IO) { WorktreePaths.deleteIdeaDirectory(moduleDir) }
            if (deleted) {
                LocalFileSystem.getInstance().refreshNioFiles(listOf(moduleDir), true, true, null)
            }
        }

        return DetachResult("detached", moduleName, moduleDir?.toString(), deleted, readAction { snapshot(project).modules })
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private fun resolveDirectory(input: String): Path {
        val dir = try {
            WorktreePaths.resolve(input)
        } catch (e: IllegalArgumentException) {
            mcpFail(e.message ?: "Invalid path '$input'.")
        }
        if (!dir.isDirectory()) mcpFail("Not a directory: $dir")
        return dir
    }

    /** The module's content roots as paths. An attached directory is always the single content root of its module. */
    private fun moduleRoots(module: Module): List<Path> =
        ModuleRootManager.getInstance(module).contentRoots.mapNotNull { root ->
            runCatching { root.toNioPath() }.getOrNull()
        }

    private fun findModuleByPath(project: Project, dir: Path): Module? =
        ModuleManager.getInstance(project).modules.firstOrNull { module ->
            moduleRoots(module).any { WorktreePaths.samePath(it, dir) }
        }

    private fun findModuleByPathOrName(project: Project, input: String): Module? {
        val modules = ModuleManager.getInstance(project).modules
        modules.firstOrNull { it.name == input.trim() }?.let { return it }
        val dir = runCatching { WorktreePaths.resolve(input) }.getOrNull() ?: return null
        return findModuleByPath(project, dir)
    }

    private fun isPrimary(project: Project, module: Module): Boolean {
        val base = project.basePath?.let(Path::of) ?: return false
        return moduleRoots(module).any { WorktreePaths.samePath(it, base) }
    }

    /**
     * Mirrors what the platform's "Remove from Project View" does: drop the order entries other modules hold on this
     * module, notify attach processors so VCS mappings and similar are cleaned up, dispose the module, and commit
     * everything in one go.
     */
    private fun detach(project: Project, module: Module) {
        val moduleManager = ModuleManager.getInstance(project)
        val moduleModel = moduleManager.getModifiableModel()

        val changedRootModels = mutableListOf<ModifiableRootModel>()
        for (other in moduleManager.modules) {
            if (other == module) continue
            val rootModel = ModuleRootManager.getInstance(other).modifiableModel
            val dependencies = rootModel.orderEntries
                .filterIsInstance<ModuleOrderEntry>()
                .filter { it.moduleName == module.name }
            if (dependencies.isEmpty()) {
                rootModel.dispose()
                continue
            }
            dependencies.forEach(rootModel::removeOrderEntry)
            changedRootModels += rootModel
        }

        for (processor in ProjectAttachProcessor.EP_NAME.extensionList) {
            processor.beforeDetach(module)
        }

        moduleModel.disposeModule(module)
        ModifiableModelCommitter.multiCommit(changedRootModels, moduleModel)
    }

    private fun snapshot(project: Project): AttachedModules {
        val base = project.basePath?.let(Path::of)
        val modules = ModuleManager.getInstance(project).modules.map { module ->
            val root = moduleRoots(module).firstOrNull()
            val worktree = root?.let(WorktreePaths::linkedWorktree)
            ModuleInfo(
                name = module.name,
                path = root?.toString(),
                primary = base != null && root != null && WorktreePaths.samePath(root, base),
                linkedWorktree = worktree != null,
                branch = worktree?.branch,
                mainRepository = worktree?.mainRepository?.toString(),
            )
        }.sortedWith(compareByDescending<ModuleInfo> { it.primary }.thenBy { it.name })
        return AttachedModules(projectPath = base?.toString() ?: "", modules = modules)
    }
}
