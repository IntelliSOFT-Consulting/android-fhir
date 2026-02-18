package com.icl.surveillance.fhir

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.icl.surveillance.models.NPHIISSyncProgress
import com.icl.surveillance.models.NPHIISSyncStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.syncDataStore by preferencesDataStore(name = "sync_progress")

class NPHIISSyncProgressStore(private val context: Context) {

    private object Keys {
        val RUN_ID = stringPreferencesKey("run_id")
        val STATUS = stringPreferencesKey("status")
        val CURRENT_TYPE = stringPreferencesKey("current_type")
        val LOCATION_DOWNLOADED = intPreferencesKey("location_downloaded")
        val LOCATION_TARGET = intPreferencesKey("location_target")
        val LAST_MESSAGE = stringPreferencesKey("last_message")
        val UPDATED_AT = longPreferencesKey("updated_at")
        val LOCATION_DONE = booleanPreferencesKey("location_done")
    }

    val progress: Flow<NPHIISSyncProgress> =
        context.syncDataStore.data.map { p ->
            NPHIISSyncProgress(
                runId = p[Keys.RUN_ID] ?: "",
                status = NPHIISSyncStatus.valueOf(p[Keys.STATUS] ?: NPHIISSyncStatus.IDLE.name),
                currentType = p[Keys.CURRENT_TYPE] ?: "",
                locationDownloaded = p[Keys.LOCATION_DOWNLOADED] ?: 0,
                locationTarget = p[Keys.LOCATION_TARGET] ?: 15_000,
                lastMessage = p[Keys.LAST_MESSAGE] ?: "",
                updatedAtEpochMs = p[Keys.UPDATED_AT] ?: 0L,
            )
        }

    suspend fun startNewRun(runId: String, locationTarget: Int, message: String = "Sync started") {
        context.syncDataStore.edit { p ->
            p[Keys.RUN_ID] = runId
            p[Keys.STATUS] = NPHIISSyncStatus.RUNNING.name
            p[Keys.CURRENT_TYPE] = ""
            p[Keys.LOCATION_DOWNLOADED] = 0
            p[Keys.LOCATION_TARGET] = locationTarget
            p[Keys.LAST_MESSAGE] = message
            p[Keys.LOCATION_DONE] = false
            p[Keys.UPDATED_AT] = System.currentTimeMillis()
        }
    }

    suspend fun setRun(runId: String) {
        context.syncDataStore.edit { p ->
            p[Keys.RUN_ID] = runId
            p[Keys.UPDATED_AT] = System.currentTimeMillis()
        }
    }

    suspend fun setStatus(status: NPHIISSyncStatus, message: String = "") {
        context.syncDataStore.edit { p ->
            p[Keys.STATUS] = status.name
            if (message.isNotBlank()) p[Keys.LAST_MESSAGE] = message
            p[Keys.UPDATED_AT] = System.currentTimeMillis()
        }
    }

    suspend fun setCurrentType(type: String, message: String = "") {
        context.syncDataStore.edit { p ->
            p[Keys.CURRENT_TYPE] = type
            if (message.isNotBlank()) p[Keys.LAST_MESSAGE] = message
            p[Keys.UPDATED_AT] = System.currentTimeMillis()
        }
    }

    suspend fun setLocationTarget(target: Int) {
        context.syncDataStore.edit { p ->
            p[Keys.LOCATION_TARGET] = target
            p[Keys.UPDATED_AT] = System.currentTimeMillis()
        }
    }

    suspend fun addLocationDownloaded(delta: Int, message: String = ""): Int {
        var newCount = 0
        context.syncDataStore.edit { p ->
            val cur = p[Keys.LOCATION_DOWNLOADED] ?: 0
            newCount = cur + delta
            p[Keys.LOCATION_DOWNLOADED] = newCount
            if (message.isNotBlank()) p[Keys.LAST_MESSAGE] = message
            p[Keys.UPDATED_AT] = System.currentTimeMillis()
        }
        return newCount
    }

    suspend fun setLocationDone(done: Boolean) {
        context.syncDataStore.edit { p ->
            p[Keys.LOCATION_DONE] = done
            p[Keys.UPDATED_AT] = System.currentTimeMillis()
        }
    }

    suspend fun isLocationDone(): Boolean {
        val snapshot = context.syncDataStore.data.map { it[Keys.LOCATION_DONE] ?: false }
        // callers typically want a one-shot read; use first() at call site
        throw IllegalStateException("Call store.progress.first() or implement a one-shot read using first()")
    }

    suspend fun reset() {
        context.syncDataStore.edit { it.clear() }
    }
}
