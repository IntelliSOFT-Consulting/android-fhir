package com.icl.surveillance.utils

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import com.icl.surveillance.R

object ProgressDialogManager {
  private var dialog: AlertDialog? = null

  fun show(context: Context, message: String = "Loading...") {
    if (dialog?.isShowing == true) return // Avoid multiple dialogs

    val view = LayoutInflater.from(context).inflate(R.layout.progress_dialog, null)
    view.findViewById<TextView>(R.id.progress_message).text = message

    dialog = AlertDialog.Builder(context).setView(view).setCancelable(false).create()

    dialog?.show()
  }

  fun dismiss() {
    dialog?.dismiss()
    dialog = null
  }

  fun isShowing(): Boolean {
    return dialog?.isShowing == true
  }
}
