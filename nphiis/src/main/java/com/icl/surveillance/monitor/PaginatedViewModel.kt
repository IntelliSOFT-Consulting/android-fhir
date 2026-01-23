package com.icl.surveillance.monitor

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.logicalId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.Bundle
import java.time.Instant

class PaginatedViewModel(private val fhirEngine: FhirEngine) : ViewModel() {

    private val repository = FhirPaginatedRepository(fhirEngine)
    private val syncFailureManager = SyncFailureManager()
    private val fhirSyncService by lazy {
        FhirSyncService(
            fhirEngine,
            NetworkModule().provideFhirDataSource()
        )
    }

    private val _resources = MutableStateFlow<List<ResourceWithSyncStatus>>(emptyList())
    val resources: StateFlow<List<ResourceWithSyncStatus>> = _resources.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _syncInProgress = MutableStateFlow<Set<String>>(emptySet())
    val syncInProgress: StateFlow<Set<String>> = _syncInProgress.asStateFlow()

    private val _syncStats = MutableStateFlow(SyncStats())
    val syncStats: StateFlow<SyncStats> = _syncStats.asStateFlow()

    private var currentResourceType = "Patient"

    // Initialize with Patient resources
    init {
        loadFirstPage("Patient")
    }


    fun loadFirstPage(resourceType: String) {
        currentResourceType = resourceType
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val firstPage = repository.getResourcesPage(resourceType, 0)
                val resourcesWithStatus = firstPage.map { resource ->
                    ResourceWithSyncStatus(
                        resource = resource,
                        syncStatus = SyncStatus.PENDING
                    )
                }
                _resources.value = resourcesWithStatus
                _hasMore.value = repository.hasMore(firstPage)
                updateSyncStats()
            } catch (e: Exception) {
                Log.e("PaginatedVM", "Error loading first page: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadNextPage() {
        if (_isLoading.value || !_hasMore.value) return

        viewModelScope.launch {
            _isLoading.value = true
            try {
                val currentPage =
                    _resources.value.size / repository.getPageSize(currentResourceType)
                val nextPage = repository.getResourcesPage(currentResourceType, currentPage)

                if (nextPage.isNotEmpty()) {
                    val newResourcesWithStatus = nextPage.map { resource ->
                        ResourceWithSyncStatus(
                            resource = resource,
                            syncStatus = SyncStatus.PENDING
                        )
                    }
                    val currentList = _resources.value.toMutableList()
                    currentList.addAll(newResourcesWithStatus)
                    _resources.value = currentList
                    _hasMore.value = repository.hasMore(nextPage)
                    updateSyncStats()
                } else {
                    _hasMore.value = false
                }
            } catch (e: Exception) {
                Log.e("PaginatedVM", "Error loading next page: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun changeResourceType(resourceType: String) {
        if (resourceType != currentResourceType) {
            loadFirstPage(resourceType)
        }
    }

    // NEW: Upload functionality
    fun uploadSingleResource(resourceId: String) {
        viewModelScope.launch {
            val resourceWithStatus = _resources.value.find { it.resource.logicalId == resourceId }
            if (resourceWithStatus != null) {
                updateResourceStatus(resourceId, SyncStatus.SYNCING)
                _syncInProgress.value = _syncInProgress.value + resourceId

                try {
                    val result = fhirSyncService.uploadResource(resourceWithStatus.resource)

                    when (result) {
                        is SyncResult.Success -> {
                            updateResourceStatus(resourceId, SyncStatus.SYNCED)
                            syncFailureManager.clearFailure(resourceId)
                            removeResourceFromList(resourceId)
                            Log.d("Upload", "Successfully uploaded resource: $resourceId")
                        }

                        is SyncResult.Failure -> {
                            updateResourceStatus(resourceId, SyncStatus.FAILED, result.error)
                            syncFailureManager.recordFailure(
                                resourceId,
                                getResourceType(resourceId),
                                result.error
                            )
                            Log.e(
                                "Upload",
                                "Failed to upload resource $resourceId: ${result.error}"
                            )
                        }
                    }
                } catch (e: Exception) {
                    val error = "Upload error: ${e.message}"
                    updateResourceStatus(resourceId, SyncStatus.FAILED, error)
                    syncFailureManager.recordFailure(resourceId, getResourceType(resourceId), error)
                    Log.e("Upload", "Upload error for $resourceId: ${e.message}")
                } finally {
                    _syncInProgress.value = _syncInProgress.value - resourceId
                    updateSyncStats()
                }
            }
        }
    }

    fun retryUpload(resourceId: String) {
        if (!syncFailureManager.shouldRetry(resourceId)) {
            Log.w("ViewModel", "Max retries reached for $resourceId")
            return
        }

        viewModelScope.launch {
            updateResourceStatus(resourceId, SyncStatus.RETRYING)
            _syncInProgress.value = _syncInProgress.value + resourceId

            try {
                val success = performActualUpload(resourceId)

                if (success) {
                    updateResourceStatus(resourceId, SyncStatus.SYNCED)
                    syncFailureManager.clearFailure(resourceId)
                    removeResourceFromList(resourceId)
                    updateSyncStats()
                    Log.d("Sync", "Successfully synced resource: $resourceId")
                } else {
                    val error = "Upload failed on retry attempt"
                    updateResourceStatus(resourceId, SyncStatus.FAILED, error)
                    syncFailureManager.recordFailure(resourceId, getResourceType(resourceId), error)
                    updateSyncStats()
                    Log.e("Sync", "Failed to sync resource $resourceId: $error")
                }
            } catch (e: Exception) {
                val error = "Retry failed: ${e.message}"
                updateResourceStatus(resourceId, SyncStatus.FAILED, error)
                syncFailureManager.recordFailure(resourceId, getResourceType(resourceId), error)
                updateSyncStats()
                Log.e("Sync", "Retry failed for $resourceId: ${e.message}")
            } finally {
                _syncInProgress.value = _syncInProgress.value - resourceId
            }
        }
    }


    fun uploadBundle(bundle: Bundle, bundleDescription: String = "Bundle") {
        viewModelScope.launch {
            val resourceIdsInBundle = bundle.entry.mapNotNull { it.resource?.logicalId }
            val jsonParser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()
            val json = jsonParser.encodeResourceToString(bundle)
            Log.d("Upload :: @", "Bundle Prepared here $json")
            println("Upload :: @  Bundle Prepared here $json")
            if (resourceIdsInBundle.isEmpty()) {
                Log.w("Upload :: @", "Bundle is empty, nothing to upload")
                return@launch
            }

            Log.d(
                "Upload :: @",
                "Starting bundle upload: $bundleDescription with ${resourceIdsInBundle.size} resources"
            )

            try {
                // Step 1: Update all resources to SYNCING
                resourceIdsInBundle.forEach { resourceId ->
                    updateResourceStatus(resourceId, SyncStatus.SYNCING)
                }
                _syncInProgress.value = _syncInProgress.value + resourceIdsInBundle

                // Step 2: Upload the bundle
                val result = fhirSyncService.uploadBundle(bundle)

                // Step 3: Process individual results
                processBundleUploadResult(result, resourceIdsInBundle, bundleDescription)

            } catch (e: Exception) {
                Log.d(
                    "Upload :: @", "Bundle upload failed: ${e.message} for $bundleDescription"
                )
                handleBundleUploadError(e, resourceIdsInBundle, bundleDescription)
            } finally {
                _syncInProgress.value = _syncInProgress.value - resourceIdsInBundle.toSet()
                updateSyncStats()
            }
        }
    }

    private fun processBundleUploadResult(
        result: BundleUploadResult,
        resourceIdsInBundle: List<String>,
        bundleDescription: String
    ) {
        val successfulCount = result.successfulUploads.size
        val failedCount = result.failedUploads.size

        // Process successful uploads
        result.successfulUploads.forEach { resourceId ->
            updateResourceStatus(resourceId, SyncStatus.SYNCED)
            syncFailureManager.clearFailure(resourceId)
            removeResourceFromList(resourceId)
            Log.d("Upload", "✓ Bundle upload successful: $resourceId")
        }

        // Process failed uploads
        result.failedUploads.forEach { failedUpload ->
            updateResourceStatus(failedUpload.resourceId, SyncStatus.FAILED, failedUpload.error)
            syncFailureManager.recordFailure(
                failedUpload.resourceId,
                getResourceType(failedUpload.resourceId),
                failedUpload.error
            )
            Log.e(
                "Upload",
                "✗ Bundle upload failed: ${failedUpload.resourceId} - ${failedUpload.error}"
            )
        }

        // Show summary
        showBundleUploadSummary(
            bundleDescription,
            successfulCount,
            failedCount,
            resourceIdsInBundle.size
        )
    }

    private fun handleBundleUploadError(
        e: Exception,
        resourceIdsInBundle: List<String>,
        bundleDescription: String
    ) {
        val error = "Bundle upload failed: ${e.message}"

        // Mark all resources as failed
        resourceIdsInBundle.forEach { resourceId ->
            updateResourceStatus(resourceId, SyncStatus.FAILED, error)
            syncFailureManager.recordFailure(resourceId, getResourceType(resourceId), error)
        }

        Log.e("Upload", "Bundle upload error for $bundleDescription: ${e.message}")
        showBundleUploadError(bundleDescription, error)
    }

    private fun showBundleUploadSummary(
        bundleDescription: String,
        successfulCount: Int,
        failedCount: Int,
        totalCount: Int
    ) {
        val message = "$bundleDescription: $successfulCount/$totalCount successful"
        if (failedCount > 0) {
            Log.w("Upload", "$message ($failedCount failed)")
            // You could show a Snackbar with the result
//             showSnackbar("$bundleDescription: $successfulCount/$totalCount uploaded ($failedCount failed)")
        } else {
            Log.d("Upload", "$message - All resources uploaded successfully")
//             showSnackbar("$bundleDescription: All $successfulCount resources uploaded successfully")
        }
    }

    private fun showBundleUploadError(bundleDescription: String, error: String) {
        Log.e("Upload", "$bundleDescription failed: $error")
//         showSnackbar("$bundleDescription upload failed")
    }

    private fun updateResourceStatus(
        resourceId: String,
        status: SyncStatus,
        errorMessage: String? = null
    ) {
        val currentList = _resources.value.toMutableList()
        val index = currentList.indexOfFirst { it.resource.logicalId == resourceId }

        if (index != -1) {
            val current = currentList[index]
            currentList[index] = current.copy(
                syncStatus = status,
                errorMessage = errorMessage,
                lastSyncAttempt = Instant.now(),
                retryCount = if (status == SyncStatus.RETRYING) current.retryCount + 1 else current.retryCount
            )
            _resources.value = currentList
        }
    }

    private fun updateSyncStats() {
        val currentResources = _resources.value
        _syncStats.value = SyncStats(
            total = currentResources.size,
            synced = currentResources.count { it.syncStatus == SyncStatus.SYNCED },
            failed = currentResources.count { it.syncStatus == SyncStatus.FAILED },
            pending = currentResources.count { it.syncStatus == SyncStatus.PENDING },
            retrying = currentResources.count { it.syncStatus == SyncStatus.RETRYING }
        )
    }

    private fun getResourceType(resourceId: String): String {
        return _resources.value.find { it.resource.logicalId == resourceId }?.resource?.resourceType?.name
            ?: "Unknown"
    }

    private fun removeResourceFromList(resourceId: String) {
        val currentList = _resources.value.toMutableList()
        currentList.removeAll { it.resource.logicalId == resourceId }
        _resources.value = currentList
    }

    private suspend fun performActualUpload(resourceId: String): Boolean {
        val resourceWithStatus = _resources.value.find { it.resource.logicalId == resourceId }
        return if (resourceWithStatus != null) {
            val result = fhirSyncService.uploadResource(resourceWithStatus.resource)
            result is SyncResult.Success
        } else {
            false
        }
    }


}