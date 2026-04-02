package com.icl.surveillance.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.icl.surveillance.utils.Constants.NOTIFICATION_CODE

class MarkReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Toast.makeText(context, "Marked as read", Toast.LENGTH_SHORT).show()
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_CODE)
    }
}
