package com.abstractivemachines.worktreeattach

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.WorktreeAttachBundle"

internal object WorktreeAttachBundle {
    private val instance = DynamicBundle(WorktreeAttachBundle::class.java, BUNDLE)

    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): @Nls String =
        instance.getMessage(key, *params)
}
