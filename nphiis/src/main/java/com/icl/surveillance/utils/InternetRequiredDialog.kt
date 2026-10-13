package com.icl.surveillance.utils

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.icl.surveillance.R

/**
 * "Internet required" alert. "Turn on internet" opens the system internet panel (Wi-Fi and mobile
 * data together, Android 10+) or the wireless settings on older phones.
 */
object InternetRequiredDialog {

    fun show(
        activity: Activity,
        message: CharSequence = activity.getString(R.string.internet_required_message),
        /** Shown as a third button when not null, e.g. to continue without the network. */
        manualLabel: CharSequence? = null,
        onManual: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
        onCancel: () -> Unit = {},
    ): AlertDialog {
        val builder = MaterialAlertDialogBuilder(activity)
            .setIcon(R.drawable.baseline_error_24)
            .setTitle(R.string.internet_required_title)
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton(R.string.internet_required_turn_on) { _, _ ->
                onOpenSettings()
                openInternetSettings(activity)
            }
            .setNegativeButton(R.string.internet_required_cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
        if (manualLabel != null) builder.setNeutralButton(manualLabel) { _, _ -> onManual() }
        return builder.show()
    }

    fun openInternetSettings(activity: Activity) {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) add(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            add(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            add(Intent(Settings.ACTION_SETTINGS))
        }
        for (intent in intents) {
            try {
                activity.startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // try the next one
            }
        }
    }
}
