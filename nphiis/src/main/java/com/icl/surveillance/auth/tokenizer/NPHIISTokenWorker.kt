package com.icl.surveillance.auth.tokenizer

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.icl.surveillance.models.RefreshToken
import com.icl.surveillance.network.Interface
import com.icl.surveillance.network.RetrofitBuilder
import com.icl.surveillance.network.SessionExpiryHandler
import com.icl.surveillance.network.TokenStore
import com.icl.surveillance.utils.Constants.BASE_AUTH_URL
import com.icl.surveillance.utils.FormatterClass

class NPHIISTokenWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            refreshToken()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private suspend fun refreshToken() {
        val context = applicationContext
        val formatter = FormatterClass()

        val refreshToken = formatter.getSharedPref("refresh_token", context)

        if (refreshToken.isNullOrEmpty()) {
            return // Nothing to refresh
        }

        val apiService = RetrofitBuilder
            .getRetrofit(BASE_AUTH_URL)
            .create(Interface::class.java)

        val response = apiService.refreshToken(data = RefreshToken(refresh_token = refreshToken))

        if (response.isSuccessful) {
            val body = response.body()

            if (body != null) {
                val accessToken = body.access_token
                val newRefreshToken = body.refresh_token

                // Save tokens
                TokenStore.saveTokens(
                    context,
                    accessToken = accessToken,
                    refreshToken = newRefreshToken
                )

                formatter.saveSharedPrefSync("access_token", accessToken, context)
                formatter.saveSharedPrefSync("refresh_token", newRefreshToken, context)
                formatter.saveSharedPrefSync("expires_in", "${body.expires_in}", context)
                formatter.saveSharedPrefSync("refresh_expires_in", "${body.refresh_expires_in}", context)

                formatter.saveSharedPrefSync("isLoggedIn", "true", context)
                SessionExpiryHandler.markSessionActive()
            } else {
                throw Exception("Empty response body")
            }
        } else {
            // Token invalid → logout scenario
            formatter.saveSharedPrefSync("isLoggedIn", "false", context)
            throw Exception("Token refresh failed")
        }
    }
}