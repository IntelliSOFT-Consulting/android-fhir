package com.icl.surveillance.viewmodels

import android.app.Application
import android.text.format.DateFormat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.android.fhir.sync.CurrentSyncJobStatus
import com.google.android.fhir.sync.Sync
import com.icl.surveillance.fhir.AppFhirSyncWorker
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter


class SyncFragmentViewModel(application: Application) : AndroidViewModel(application) {
    private val _lastSyncTimestampLiveData = MutableLiveData<String>()
    val lastSyncTimestampLiveData: LiveData<String>
        get() = _lastSyncTimestampLiveData

    // Change this to a simple MutableLiveData to signal UI state (e.g., show a spinner)
    private val _syncState = MutableLiveData<SyncState>()
    val syncState: LiveData<SyncState>
        get() = _syncState

    // A SharedFlow to emit the results of a one-time sync
    private val _oneTimeSyncResult = MutableLiveData<CurrentSyncJobStatus>()
    val oneTimeSyncResult: LiveData<CurrentSyncJobStatus>
        get() = _oneTimeSyncResult

    // This function is now the single point of action to start a sync
    fun triggerOneTimeSync() {
        viewModelScope.launch {
            // Set initial state for UI
            _syncState.value = SyncState.Running

            Sync.oneTimeSync<AppFhirSyncWorker>(getApplication())
                .catch { exception ->
                    // Catch fatal exceptions from the flow itself
                    _syncState.value = SyncState.Error(exception)
                }
                .collect { status ->
                    // Update the result LiveData as status changes
                    _oneTimeSyncResult.value = status

                    // Update the overall state when the sync is finished
                    if (status is CurrentSyncJobStatus.Succeeded || status is CurrentSyncJobStatus.Failed) {
                        updateLastSyncTimestamp()
                        _syncState.value = SyncState.Finished
                    }
                }
        }
    }

    // This function is not the issue, but it's good to have
    fun cancelOneTimeSyncWork() {
        viewModelScope.launch { Sync.cancelOneTimeSync<AppFhirSyncWorker>(getApplication()) }
    }

    /** Emits last sync time. */
    fun updateLastSyncTimestamp(lastSync: OffsetDateTime? = null) {
        val formatter =
            DateTimeFormatter.ofPattern(
                if (DateFormat.is24HourFormat(getApplication())) formatString24 else formatString12,
            )
        _lastSyncTimestampLiveData.value =
            lastSync?.let { it.toLocalDateTime()?.format(formatter) ?: "" }
                ?: Sync.getLastSyncTimestamp(getApplication())?.toLocalDateTime()?.format(formatter)
                        ?: ""
    }

    companion object {
        private const val formatString24 = "yyyy-MM-dd HH:mm:ss"
        private const val formatString12 = "yyyy-MM-dd hh:mm:ss a"
    }
}

// A helper sealed class to manage UI state more cleanly
sealed class SyncState {
    object Idle : SyncState()
    object Running : SyncState()
    object Finished : SyncState()
    data class Error(val exception: Throwable) : SyncState()
}
