package com.icl.surveillance.utils

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

fun Context.readFileFromAssets(fileName: String): String =
    assets.open(fileName).bufferedReader().use { it.readText() }

fun String.toSlug(): String {
    return this
        .trim()
        .lowercase()
        .replace("[^a-z0-9\\s-]".toRegex(), "")
        .replace("\\s+".toRegex(), "-")
        .replace("-+".toRegex(), "-")
}

fun ComponentActivity.launchAndRepeatStarted(
    vararg launchBlock: suspend () -> Unit,
    doAfterLaunch: (() -> Unit)? = null,
) {
    lifecycleScope.launch {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launchBlock.forEach { launch { it.invoke() } }
            doAfterLaunch?.invoke()
        }
    }
}
