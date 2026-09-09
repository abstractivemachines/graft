package com.abstractivemachines.worktreeattach

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Pure filesystem helpers with no IDE dependencies, so they can be unit tested without a platform sandbox.
 */
internal object WorktreePaths {

    /** Name of the IDE's per-project settings folder. */
    const val IDEA_DIR: String = ".idea"

    /** What a linked git worktree points at. */
    data class LinkedWorktree(
        /** The private git dir, normally `<main>/.git/worktrees/<name>`. */
        val gitDir: Path,
        /** Checked-out branch, or null when HEAD is detached. */
        val branch: String?,
        /** Root of the main checkout that owns this worktree, when it can be derived from [gitDir]. */
        val mainRepository: Path?,
    )

    /**
     * Turns user input into an absolute, normalized path. A leading `~` is expanded against [home].
     * @throws IllegalArgumentException when the input is blank or relative.
     */
    fun resolve(input: String, home: Path = Path.of(System.getProperty("user.home"))): Path {
        val trimmed = input.trim()
        require(trimmed.isNotEmpty()) { "Path is empty." }
        val expanded = when {
            trimmed == "~" -> home
            trimmed.startsWith("~/") -> home.resolve(trimmed.substring(2))
            else -> Path.of(trimmed)
        }
        require(expanded.isAbsolute) { "Path must be absolute, got '$input'." }
        return expanded.normalize()
    }

    /** True when both paths name the same directory, following symlinks when the paths exist. */
    fun samePath(a: Path, b: Path): Boolean {
        val realA = realOrNormalized(a)
        val realB = realOrNormalized(b)
        return realA == realB
    }

    private fun realOrNormalized(p: Path): Path = try {
        p.toRealPath()
    } catch (_: IOException) {
        p.toAbsolutePath().normalize()
    }

    /**
     * Reads `<dir>/.git` and, when it is a linked-worktree pointer file, returns where it points and which branch is checked out.
     * Returns null for a main checkout (`.git` is a directory) or a directory that is not a git checkout at all.
     */
    fun linkedWorktree(dir: Path): LinkedWorktree? {
        val dotGit = dir.resolve(".git")
        if (!dotGit.isRegularFile()) return null
        val pointer = dotGit.readText().lineSequence().firstOrNull { it.startsWith("gitdir:") } ?: return null
        val gitDir = dir.resolve(pointer.removePrefix("gitdir:").trim()).normalize()

        val head = gitDir.resolve("HEAD").takeIf { it.isRegularFile() }?.readText()?.trim()
        val branch = head?.takeIf { it.startsWith("ref: refs/heads/") }?.removePrefix("ref: refs/heads/")

        // <main>/.git/worktrees/<name> -> <main>
        val mainRepository = gitDir.parent
            ?.takeIf { it.fileName?.toString() == "worktrees" }
            ?.parent
            ?.takeIf { it.fileName?.toString() == ".git" }
            ?.parent

        return LinkedWorktree(gitDir, branch, mainRepository)
    }

    /**
     * Returns a human-readable reason why attaching [dir] would fail or pop a modal dialog, or null when it is safe.
     *
     * The IDE attaches by loading a module file it finds under `<dir>/.idea` or `<dir>`. Two situations break that:
     * a `.idea` with no module file (the IDE asks about a non-standard layout), and a module file whose name is already
     * taken in the project (the IDE refuses with "Module name already exists"). The second one is what happens when
     * `.idea` is copied from the main checkout into a worktree.
     */
    fun attachBlocker(dir: Path, existingModuleNames: Set<String>): String? {
        val ideaDir = dir.resolve(IDEA_DIR)
        if (!ideaDir.isDirectory()) return null

        val moduleFiles = listModuleFiles(ideaDir) + listModuleFiles(dir)
        if (moduleFiles.isEmpty()) {
            return "$dir has a $IDEA_DIR folder but no module (.iml) file, so the IDE would stop on a non-standard layout dialog. " +
                "Delete $ideaDir and retry; the IDE will create a module named after the directory."
        }

        val clash = moduleFiles
            .map { it.fileName.toString().removeSuffix(".iml") }
            .firstOrNull { it in existingModuleNames }
        if (clash != null) {
            return "$dir contains module file '$clash.iml' but the project already has a module named '$clash', so Attach would fail " +
                "with 'Module name already exists'. This usually means $IDEA_DIR was copied from the main checkout. " +
                "Delete $ideaDir and retry; the IDE will create a module named after the directory."
        }
        return null
    }

    private fun listModuleFiles(dir: Path): List<Path> {
        if (!dir.isDirectory()) return emptyList()
        return Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".iml") }.toList()
        }
    }

    /** Deletes `<dir>/.idea` recursively. Returns false when there was nothing to delete. */
    fun deleteIdeaDirectory(dir: Path): Boolean {
        val ideaDir = dir.resolve(IDEA_DIR)
        if (!ideaDir.isDirectory()) return false
        Files.walk(ideaDir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
        return true
    }
}
