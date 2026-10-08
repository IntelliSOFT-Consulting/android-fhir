package com.icl.surveillance.utils


import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object DialogHelper {

    fun showSyncRequiresInternetDialog(context: Context) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Internet Connection Required")
            .setMessage("Please ensure internet access is available before syncing.")
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .show()
    }

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
}
