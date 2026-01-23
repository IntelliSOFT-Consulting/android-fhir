package com.icl.surveillance.monitor


import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.icl.surveillance.utils.NetworkUtils

object DialogHelper {

    fun showNoInternetDialog(
        context: Context,
        onRetry: () -> Unit,
        onCancel: (() -> Unit)? = null
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle("No Internet Connection")
            .setMessage("You need to be connected to the internet to sync resources. Please check your connection and try again.")
            .setPositiveButton("Retry") { dialog, _ ->
                if (NetworkUtils.isInternetAvailable(context)) {
                    onRetry()
                } else {
                    showNoInternetDialog(context, onRetry, onCancel) // Recursive call if still no internet
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                onCancel?.invoke()
                dialog.dismiss()
            }
            .setNeutralButton("Settings") { dialog, _ ->
                val intent = android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
                context.startActivity(intent)
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    fun showUploadConfirmationDialog(
        context: Context,
        resourceCount: Int,
        onConfirm: () -> Unit
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Upload Resources")
            .setMessage("You are about to upload $resourceCount resources. Make sure you have a stable internet connection.")
            .setPositiveButton("Upload") { dialog, _ ->
                if (NetworkUtils.isInternetAvailable(context)) {
                    onConfirm()
                } else {
                    showNoInternetDialog(context, onConfirm)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
