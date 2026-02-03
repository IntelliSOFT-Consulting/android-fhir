package com.icl.surveillance.network

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class TokenRefreshWorker(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val authRepository = AuthRepository(applicationContext)

            val refreshed = authRepository.refreshToken()

            if (refreshed) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }
}