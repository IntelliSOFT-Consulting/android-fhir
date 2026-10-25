package com.icl.surveillance.network

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Periodic refresh while online (scheduled by MainActivity), which also keeps the server
 * session from going idle between syncs.
 */
class TokenRefreshWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            if (TokenRefresher.ensureFresh(applicationContext, force = true)) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
