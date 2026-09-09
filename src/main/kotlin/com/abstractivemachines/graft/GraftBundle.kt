package com.abstractivemachines.graft

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.GraftBundle"

internal object GraftBundle {
    private val instance = DynamicBundle(GraftBundle::class.java, BUNDLE)

    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): @Nls String =
        instance.getMessage(key, *params)
}
