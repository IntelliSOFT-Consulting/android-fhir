package com.icl.surveillance.fhir

import com.icl.surveillance.models.NPHIISSyncStatus
import kotlinx.coroutines.flow.first
import java.util.UUID


class NPHIISSyncTracker(
    private val store: NPHIISSyncProgressStore,
    private val locationTarget: Int = 15_000
) {
    suspend fun startRun(): String {
        val runId = UUID.randomUUID().toString()
        store.startNewRun(runId = runId, locationTarget = locationTarget, message = "Sync started")
        return runId
    }

    suspend fun onTypeStart(type: String) {
        store.setCurrentType(type, "Downloading $type ...")
    }

    /**
     * Returns current total Location downloaded.
     */
    suspend fun onLocationPageDownloaded(countInPage: Int): Int {
        val p = store.progress.first()
        val newTotal = store.addLocationDownloaded(
            delta = countInPage,
            message = "Location downloaded: ${p.locationDownloaded + countInPage} / ${p.locationTarget}"
        )
        if (newTotal >= p.locationTarget) {
            store.setLocationDone(true)
        }
        return newTotal
    }

    suspend fun isLocationThresholdReached(): Boolean {
        val p = store.progress.first()
        return p.locationDownloaded >= p.locationTarget
    }

    suspend fun done() {
        store.setStatus(NPHIISSyncStatus.SUCCESS, "Sync complete")
    }

    suspend fun failed(msg: String) {
        store.setStatus(NPHIISSyncStatus.FAILED, msg)
    }
}
