package com.icl.surveillance.viewmodels

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import com.google.android.fhir.sync.CurrentSyncJobStatus
import com.google.android.fhir.sync.LastSyncJobStatus
import com.google.android.fhir.sync.PeriodicSyncConfiguration
import com.google.android.fhir.sync.PeriodicSyncJobStatus
import com.google.android.fhir.sync.RepeatInterval
import com.google.android.fhir.sync.Sync
import com.google.android.fhir.sync.SyncJobStatus
import com.icl.surveillance.R
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.fhir.AppFhirSyncWorker
import com.icl.surveillance.fhir.ProgressHelper
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import android.text.format.DateFormat
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

class PeriodicSyncViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiStateFlow = MutableStateFlow(PeriodicSyncUiState())
    val uiStateFlow: StateFlow<PeriodicSyncUiState> = _uiStateFlow

    private val _pollPeriodicSyncJobStatus = MutableSharedFlow<PeriodicSyncJobStatus>(replay = 10)

    init {
        viewModelScope.launch { initializePeriodicSync() }
    }

    suspend fun initializePeriodicSync() {

        val periodicSyncJobStatusFlow =
            Sync.periodicSync<AppFhirSyncWorker>(
                context = getApplication<Application>().applicationContext,
                periodicSyncConfiguration =
                    PeriodicSyncConfiguration(
                        syncConstraints = Constraints.Builder().build(),
                        repeat = RepeatInterval(interval = 10, timeUnit = TimeUnit.MINUTES),
                    ),
            )

        periodicSyncJobStatusFlow.collect { status -> _pollPeriodicSyncJobStatus.emit(status) }

    }

    fun collectPeriodicSyncJobStatus() {
        viewModelScope.launch {
            _pollPeriodicSyncJobStatus.collect { periodicSyncJobStatus ->

                val lastSyncStatus = getLastSyncStatus(periodicSyncJobStatus.lastSyncJobStatus)
                val lastSyncTime = getLastSyncTime(periodicSyncJobStatus.lastSyncJobStatus)
                val currentSyncStatus =
                    getApplication<FhirApplication>()
                        .getString(
                            R.string.current_status,
                            periodicSyncJobStatus.currentSyncJobStatus::class.java.simpleName,
                        )
                val progress = getProgress(periodicSyncJobStatus.currentSyncJobStatus)

                // Update the UI state
                _uiStateFlow.value =
                    _uiStateFlow.value.copy(
                        lastSyncStatus = lastSyncStatus,
                        lastSyncTime = lastSyncTime,
                        currentSyncStatus = currentSyncStatus,
                        progress = progress,
                    )
            }
        }
    }

    fun cancelPeriodicSyncJob() {
        viewModelScope.launch {
            Sync.cancelPeriodicSync<AppFhirSyncWorker>(
                getApplication<FhirApplication>().applicationContext,
            )
        }
    }

    private fun getLastSyncStatus(lastSyncJobStatus: LastSyncJobStatus?): String? {
        return when (lastSyncJobStatus) {

            is LastSyncJobStatus.Succeeded ->
                getApplication<FhirApplication>()
                    .getString(
                        R.string.last_sync_status,
                        LastSyncJobStatus.Succeeded::class.java.simpleName,
                    )

            is LastSyncJobStatus.Failed -> {
                println("Failed last sync status: ${lastSyncJobStatus.timestamp} $lastSyncJobStatus")
                getApplication<FhirApplication>()
                    .getString(
                        R.string.last_sync_status,
                        LastSyncJobStatus.Failed::class.java.simpleName
                    )
            }

            else -> getApplication<FhirApplication>().getString(R.string.last_sync_status_na)
        }
    }

    private fun getLastSyncTime(lastSyncJobStatus: LastSyncJobStatus?): String {
        val applicationContext = getApplication<FhirApplication>()
        return lastSyncJobStatus?.let { status ->
            applicationContext.getString(
                R.string.last_sync_timestamp,
                status.timestamp.formatSyncTimestamp(applicationContext),
            )
        }
            ?: applicationContext.getString(R.string.last_sync_status_na)
    }

    private fun getProgress(currentSyncJobStatus: CurrentSyncJobStatus): Int? {
        val inProgressSyncJob =
            (currentSyncJobStatus as? CurrentSyncJobStatus.Running)?.inProgressSyncJob
        return (inProgressSyncJob as? SyncJobStatus.InProgress)?.let {
            ProgressHelper.calculateProgressPercentage(it.total, it.completed)
        }
    }
}

fun OffsetDateTime.formatSyncTimestamp(context: Context): String {
    val formatter = getDateTimeFormatter(context)
    return toLocalDateTime().format(formatter)
}

private fun getDateTimeFormatter(context: Context): DateTimeFormatter {
    return DateTimeFormatter.ofPattern(
        if (DateFormat.is24HourFormat(context)) {
            "yyyy-MM-dd HH:mm:ss"
        } else {
            "yyyy-MM-dd hh:mm:ss a"
        },
    )
}

data class PeriodicSyncUiState(
    val lastSyncStatus: String? = null,
    val lastSyncTime: String? = null,
    val currentSyncStatus: String? = null,
    val progress: Int? = null,
)
