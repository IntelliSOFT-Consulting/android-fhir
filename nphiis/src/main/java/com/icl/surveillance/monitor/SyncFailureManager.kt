package com.icl.surveillance.monitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant


class SyncFailureManager {
    private val _syncFailures = MutableStateFlow<Map<String, SyncFailure>>(emptyMap())
    val syncFailures: StateFlow<Map<String, SyncFailure>> = _syncFailures.asStateFlow()

    fun recordFailure(resourceId: String, resourceType: String, error: String) {
        val currentFailures = _syncFailures.value.toMutableMap()
        val existingFailure = currentFailures[resourceId]

        currentFailures[resourceId] = SyncFailure(
            resourceId = resourceId,
            resourceType = resourceType,
            errorMessage = error,
            retryCount = existingFailure?.retryCount?.plus(1) ?: 1,
            timestamp = Instant.now()
        )

        _syncFailures.value = currentFailures
    }

    fun clearFailure(resourceId: String) {
        val currentFailures = _syncFailures.value.toMutableMap()
        currentFailures.remove(resourceId)
        _syncFailures.value = currentFailures
    }

    fun getFailure(resourceId: String): SyncFailure? {
        return _syncFailures.value[resourceId]
    }

    fun shouldRetry(resourceId: String): Boolean {
        val failure = _syncFailures.value[resourceId]
        return failure?.retryCount ?: 0 < 3 // Max 3 retries
    }
}