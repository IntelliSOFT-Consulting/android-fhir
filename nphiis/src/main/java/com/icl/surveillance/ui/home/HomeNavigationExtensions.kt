package com.icl.surveillance.ui.home

import android.os.Bundle
import androidx.annotation.IdRes
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import timber.log.Timber

internal fun Fragment.navigateIfActionAvailable(
    @IdRes expectedDestinationId: Int,
    @IdRes actionId: Int,
    args: Bundle? = null,
): Boolean {
    val navController = findNavController()
    val currentDestination = navController.currentDestination ?: return false
    if (currentDestination.id != expectedDestinationId) {
        return false
    }

    val action = currentDestination.getAction(actionId) ?: navController.graph.getAction(actionId)
    if (action == null) {
        return false
    }

    return runCatching {
        navController.navigate(actionId, args)
        true
    }.getOrElse { error ->
        Timber.w(
            error,
            "Skipped navigation action=%s from destination=%s",
            actionId,
            currentDestination.id,
        )
        false
    }
}
