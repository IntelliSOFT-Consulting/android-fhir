package com.icl.surveillance.monitor

import android.util.Log
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.logicalId
import com.icl.surveillance.monitor.LocalBundleResponse.LocalEntry
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.MeasureReport
import org.hl7.fhir.r4.model.Meta
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Resource
import java.util.Date

class FhirSyncService(
    private val fhirEngine: FhirEngine,
    private val fhirDataSource: FhirDataSource // Your server API interface
) {

    suspend fun uploadResource(resource: Resource): SyncResult {
        return try {
            Log.d("FhirSync", "Uploading resource: ${resource.resourceType}/${resource.logicalId}")

            when (resource.resourceType.name) {
                "Patient" -> uploadPatient(resource as Patient)
                "Observation" -> uploadObservation(resource as Observation)
                "Encounter" -> uploadEncounter(resource as Encounter)
                "QuestionnaireResponse" -> uploadQuestionnaireResponse(resource as QuestionnaireResponse)
                "MeasureReport" -> uploadMeasureReport(resource as MeasureReport)
                else -> SyncResult.Failure("Unsupported resource type: ${resource.resourceType}")
            }
        } catch (e: Exception) {
            Log.e(
                "FhirSync",
                "Upload failed for ${resource.resourceType}/${resource.logicalId}: ${e.message}"
            )
            SyncResult.Failure(e.message ?: "Unknown error")
        }
    }

    private suspend fun uploadPatient(patient: Patient): SyncResult {
        return try {
            val jsonParser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()
            val json = jsonParser.encodeResourceToString(patient)
            val requestBody = json.toRequestBody("application/json".toMediaType())


            val result = fhirDataSource.createPatient(patient.idElement.idPart, requestBody)

            if (result.isSuccessful) {
                // Update local resource with server metadata
                updateLocalResourceAfterSync(patient)
                SyncResult.Success(patient.logicalId)
            } else {
                val errorMessage = parseOperationOutcome(result.errorBody())
                    ?: "Server returned error: ${result.code()}"
                SyncResult.Failure(errorMessage)
            }
        } catch (e: Exception) {
            SyncResult.Failure("Patient upload failed: ${e.message}")
        }
    }

    private fun parseOperationOutcome(errorBody: ResponseBody?): String? {
        return try {
            if (errorBody == null) return null

            val jsonParser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()
            val operationOutcome = jsonParser.parseResource(errorBody.string()) as? OperationOutcome

            operationOutcome?.let { outcome ->
                val errorMessages = mutableListOf<String>()

                // Extract all issue details from OperationOutcome
                outcome.issue.forEach { issue ->
                    val severity = issue.severity?.name ?: "ERROR"
                    val code = issue.code?.name ?: "processing"
                    val diagnostics = issue.diagnostics ?: "No diagnostic information"

                    // For your specific example, the most useful information is in diagnostics
                    val issueMessage = "$severity: $diagnostics"
                    errorMessages.add(issueMessage)
                }

                if (errorMessages.isNotEmpty()) {
                    errorMessages.joinToString("; ")
                } else {
                    "Unknown server error"
                }
            }
        } catch (e: Exception) {
            // Fallback if parsing OperationOutcome fails
            null
        }
    }

    private suspend fun uploadObservation(observation: Observation): SyncResult {
        return try {
            val jsonParser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()
            val json = jsonParser.encodeResourceToString(observation)
            val requestBody = json.toRequestBody("application/json".toMediaType())

            val result = fhirDataSource.createObservation(observation.logicalId, requestBody)

            if (result.isSuccessful) {
                updateLocalResourceAfterSync(observation)
                SyncResult.Success(observation.logicalId)
            } else {
                val errorMessage = parseOperationOutcome(result.errorBody())
                    ?: "Server returned error: ${result.code()}"
                SyncResult.Failure(errorMessage)
            }
        } catch (e: Exception) {
            SyncResult.Failure("Observation upload failed: ${e.message}")
        }
    }

    private suspend fun uploadEncounter(encounter: Encounter): SyncResult {
        return try {
            val jsonParser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()
            val json = jsonParser.encodeResourceToString(encounter)
            val requestBody = json.toRequestBody("application/json".toMediaType())

            val result = fhirDataSource.createEncounter(encounter.logicalId, requestBody)

            if (result.isSuccessful) {
                updateLocalResourceAfterSync(encounter)
                SyncResult.Success(encounter.logicalId)
            } else {
                val errorMessage = parseOperationOutcome(result.errorBody())
                    ?: "Server returned error: ${result.code()}"
                SyncResult.Failure(errorMessage)
            }
        } catch (e: Exception) {
            SyncResult.Failure("Encounter upload failed: ${e.message}")
        }
    }

    private suspend fun uploadQuestionnaireResponse(response: QuestionnaireResponse): SyncResult {
        return try {
            val jsonParser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()
            val json = jsonParser.encodeResourceToString(response)
            val requestBody = json.toRequestBody("application/json".toMediaType())

            val result = fhirDataSource.createQuestionnaireResponse(response.logicalId, requestBody)

            if (result.isSuccessful) {
                updateLocalResourceAfterSync(response)
                SyncResult.Success(response.logicalId)
            } else {
                val errorMessage = parseOperationOutcome(result.errorBody())
                    ?: "Server returned error: ${result.code()}"
                SyncResult.Failure(errorMessage)
            }
        } catch (e: Exception) {
            SyncResult.Failure("QuestionnaireResponse upload failed: ${e.message}")
        }
    }

    private suspend fun uploadMeasureReport(report: MeasureReport): SyncResult {
        return try {
            val jsonParser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()
            val json = jsonParser.encodeResourceToString(report)
            val requestBody = json.toRequestBody("application/json".toMediaType())

            val result = fhirDataSource.createMeasureReport(report.logicalId, requestBody)

            if (result.isSuccessful) {
                updateLocalResourceAfterSync(report)
                SyncResult.Success(report.logicalId)
            } else {
                val errorMessage = parseOperationOutcome(result.errorBody())
                    ?: "Server returned error: ${result.code()}"
                SyncResult.Failure(errorMessage)
            }
        } catch (e: Exception) {
            SyncResult.Failure("MeasureReport upload failed: ${e.message}")
        }
    }

    suspend fun uploadBundle(bundle: Bundle): BundleUploadResult {
        return try {
            Log.d("FhirSync", "Uploading bundle with ${bundle.entry.size} resources")
            val jsonParser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()
            val json = jsonParser.encodeResourceToString(bundle)
            val requestBody = json.toRequestBody("application/json".toMediaType())

            val response = fhirDataSource.sendBundleToServer(requestBody)

            if (response.isSuccessful) {
                val responseBundle = response.body()
                processBundleResponse(bundle, responseBundle)
            } else {
                // Entire bundle failed - mark all resources as failed
                val failedUploads = bundle.entry.mapNotNull { entry ->
                    val resourceId = entry.resource?.logicalId
                    if (resourceId != null) {
                        FailedUpload(resourceId, "HTTP ${response.code()}: ${response.message()}")
                    } else {
                        null
                    }
                }

                BundleUploadResult(
                    totalResources = bundle.entry.size,
                    successfulUploads = emptyList(),
                    failedUploads = failedUploads
                )
            }
        } catch (e: Exception) {
            Log.e("FhirSync", "Bundle upload failed: ${e.message}")

            // Entire bundle failed due to network error
            val failedUploads = bundle.entry.mapNotNull { entry ->
                val resourceId = entry.resource?.logicalId
                if (resourceId != null) {
                    FailedUpload(resourceId, "Network error: ${e.message}")
                } else {
                    null
                }
            }

            BundleUploadResult(
                totalResources = bundle.entry.size,
                successfulUploads = emptyList(),
                failedUploads = failedUploads
            )
        }
    }

    /**
     * Process the bundle response from the server
     */
    private suspend fun processBundleResponse(
        requestBundle: Bundle,
        responseBundle: LocalBundleResponse?
    ): BundleUploadResult {
        val successfulUploads = mutableListOf<String>()
        val failedUploads = mutableListOf<FailedUpload>()

        responseBundle?.entry?.forEachIndexed { index, responseEntry ->
            val requestEntry = requestBundle.entry.getOrNull(index)
            val resourceId = requestEntry?.resource?.logicalId ?: "unknown-$index"

            // Check if the individual entry was successful
            if (isEntrySuccessful(responseEntry)) {
                successfulUploads.add(resourceId)

                // Update local resource after successful upload
                requestEntry?.resource?.let { resource ->
                    updateLocalResourceAfterSync(resource)
                }
            } else {
                val error = getEntryErrorMessage(entry = responseEntry)
                failedUploads.add(FailedUpload(resourceId, error))
            }
        }

        // If response bundle is null or empty, consider all as failed
        if (responseBundle == null || responseBundle.entry.isEmpty()) {
            requestBundle.entry.forEach { entry ->
                val resourceId = entry.resource?.logicalId ?: "unknown"
                failedUploads.add(FailedUpload(resourceId, "No response from server"))
            }
        }

        return BundleUploadResult(
            totalResources = requestBundle.entry.size,
            successfulUploads = successfulUploads,
            failedUploads = failedUploads
        )
    }

    /**
     * Check if a bundle entry was successfully processed
     */
    private fun isEntrySuccessful(entry: LocalEntry): Boolean {
        // Check HTTP status code - 2xx means success
        val status = entry.response.status
        if (status.startsWith("2")) {
            return true
        }

//        // Check OperationOutcome for errors
//        if (entry.response.outcome.resourceType.contains("OperationOutcome")) {
//            val outcome = entry.response.outcome
//            return !outcome.issue.any { it.severity == OperationOutcome.IssueSeverity.ERROR }
//        }

        return false
    }

    /**
     * Get error message from a bundle entry response
     */
    private fun getEntryErrorMessage(entry: LocalEntry): String {
        val status = entry.response.status ?: "Unknown status"
//
//        // Try to get error details from OperationOutcome
//        if (entry.response?.outcome is LocalOperationOutcome) {
//            val outcome = entry.response.outcome as LocalOperationOutcome
//            val errors = outcome.issue
//                .filter { it.severity == OperationOutcome.IssueSeverity.ERROR }
//                .joinToString { it.details?.text ?: it.diagnostics ?: "Unknown error" }
//
//            if (errors.isNotEmpty()) {
//                return "$status: $errors"
//            }
//        }

        return "Server returned: $status"
    }

    private suspend fun updateLocalResourceAfterSync(resource: Resource) {
        try {
            // Add sync metadata to the resource
            if (resource.meta == null) {
                resource.meta = Meta()
            }
            resource.meta!!.lastUpdated = Date() // Set sync timestamp
            if (resource.meta!!.versionId == null) {
                resource.meta!!.versionId = "1"
            }

            // Update the resource in local database
            when (resource) {
                is Patient -> fhirEngine.update(resource)
                is Observation -> fhirEngine.update(resource)
                is Encounter -> fhirEngine.update(resource)
                is QuestionnaireResponse -> fhirEngine.update(resource)
                is MeasureReport -> fhirEngine.update(resource)
            }

            Log.d(
                "FhirSync",
                "Updated local resource: ${resource.resourceType}/${resource.logicalId}"
            )
        } catch (e: Exception) {
            Log.e("FhirSync", "Failed to update local resource: ${e.message}")
        }
    }

    suspend fun uploadMultipleResources(resources: List<Resource>): BulkSyncResult {
        val results = mutableListOf<SyncResult>()

        resources.forEach { resource ->
            val result = uploadResource(resource)
            results.add(result)
            delay(100) // Small delay between requests to avoid overwhelming server
        }

        val successful = results.count { it is SyncResult.Success }
        val failed = results.count { it is SyncResult.Failure }

        return BulkSyncResult(
            total = resources.size,
            successful = successful,
            failed = failed,
            individualResults = results
        )
    }
}



