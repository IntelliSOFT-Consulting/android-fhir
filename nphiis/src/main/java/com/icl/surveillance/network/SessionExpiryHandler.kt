package com.icl.surveillance.network

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.icl.surveillance.auth.LoginActivity
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import timber.log.Timber

object SessionExpiryHandler {
    private val redirectInProgress = AtomicBoolean(false)

    fun handleUnauthorized(context: Context) {
        val appContext = context.applicationContext
        if (!redirectInProgress.compareAndSet(false, true)) {
            return
        }

        runBlocking {
            SessionManager.clearAuthenticatedSession(appContext)
        }

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, "Session expired. Please log in again.", Toast.LENGTH_LONG)
                .show()
            try {
                val intent = Intent(appContext, LoginActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                appContext.startActivity(intent)
            } catch (error: Exception) {
                Timber.tag("SessionExpiryHandler").e(error, "Unable to launch login screen after 401")
            }
        }
    }

    fun markSessionActive() {
        redirectInProgress.set(false)
    }
}
