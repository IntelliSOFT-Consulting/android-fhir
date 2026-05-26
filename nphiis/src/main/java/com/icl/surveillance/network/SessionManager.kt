package com.icl.surveillance.network

import android.content.Context
import androidx.work.WorkManager
import com.google.android.fhir.sync.Sync
import com.icl.surveillance.fhir.AppFhirSyncWorker
import com.icl.surveillance.utils.FormatterClass

object SessionManager {
    private const val LEGACY_TOKEN_REFRESH_WORK_NAME = "NPHIISTokenWorker"
    private const val TOKEN_REFRESH_WORK_NAME = "token_refresh_work"

    suspend fun clearAuthenticatedSession(context: Context) {
        val appContext = context.applicationContext

        FormatterClass().clearSessionState(appContext)
        TokenStore.clear(appContext)
        runCatching { Sync.cancelOneTimeSync<AppFhirSyncWorker>(appContext) }
        runCatching { Sync.cancelPeriodicSync<AppFhirSyncWorker>(appContext) }

        WorkManager.getInstance(appContext).cancelUniqueWork(LEGACY_TOKEN_REFRESH_WORK_NAME)
        WorkManager.getInstance(appContext).cancelUniqueWork(TOKEN_REFRESH_WORK_NAME)
    }
}
