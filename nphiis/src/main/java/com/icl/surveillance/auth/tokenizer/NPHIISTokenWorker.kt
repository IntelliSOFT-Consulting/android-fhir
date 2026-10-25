package com.icl.surveillance.auth.tokenizer

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.icl.surveillance.network.TokenRefresher

/**
 * No longer scheduled (MainActivity cancels it; TokenRefreshWorker replaced it). Kept so a run
 * already queued on a device still has a class to start, and refreshes the same way.
 */
class NPHIISTokenWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result =
        if (TokenRefresher.ensureFresh(applicationContext, force = true)) Result.success()
        else Result.retry()
}
