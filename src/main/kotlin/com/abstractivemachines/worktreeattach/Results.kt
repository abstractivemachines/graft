package com.abstractivemachines.worktreeattach

import kotlinx.serialization.Serializable

@Serializable
data class ModuleInfo(
    val name: String,
    val path: String?,
    val primary: Boolean,
    val linkedWorktree: Boolean,
    val branch: String?,
    val mainRepository: String?,
)

@Serializable
data class AttachedModules(
    val projectPath: String,
    val modules: List<ModuleInfo>,
)

@Serializable
data class AttachResult(
    /** `attached` or `already_attached`. */
    val status: String,
    val moduleName: String,
    val path: String,
    val modules: List<ModuleInfo>,
)

@Serializable
data class DetachResult(
    /** Always `detached`; failures are reported as MCP errors instead. */
    val status: String,
    val moduleName: String,
    val path: String?,
    val deletedIdeaDirectory: Boolean,
    val modules: List<ModuleInfo>,
)
