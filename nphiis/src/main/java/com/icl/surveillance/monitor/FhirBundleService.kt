// FhirBundleService.kt
package com.icl.surveillance.monitor

import android.util.Log
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.search.search
import org.hl7.fhir.r4.model.*
import java.util.*

class FhirBundleService(private val fhirEngine: FhirEngine) {

    suspend fun createUploadBundle(resourceType: String): Bundle {
        val bundle = Bundle().apply {
            id = "upload-bundle-${System.currentTimeMillis()}"
            type = Bundle.BundleType.TRANSACTION
            timestamp = Date()
        }

        val allResources = getAllResourcesWithoutLastUpdated(resourceType)

        allResources.forEach { response ->
            val entry = Bundle.BundleEntryComponent().apply {
                fullUrl = "$resourceType/${response.logicalId}"
                val requestPayload = Bundle.BundleEntryRequestComponent().apply {
                    method = Bundle.HTTPVerb.PUT
                    url = "$resourceType/${response.logicalId}"
                }
                request = requestPayload
                resource = response
            }
            bundle.addEntry(entry)
        }

        Log.d("BundleService", "Created bundle with ${allResources.size} $resourceType resources")
        return bundle
    }

    suspend fun createUploadBundleForAllTypes(): Bundle {
        val bundle = Bundle().apply {
            id = "upload-bundle-all-${System.currentTimeMillis()}"
            type = Bundle.BundleType.TRANSACTION
            timestamp = Date()
        }

        val resourceTypes = listOf(
            "Patient", "QuestionnaireResponse", "MeasureReport", "Encounter", "Observation"
        )

        resourceTypes.forEach { resourceType ->
            val resources = getAllResourcesWithoutLastUpdated(resourceType)
            resources.forEach { resource ->
                val entry = Bundle.BundleEntryComponent().apply {
                    fullUrl = "$resourceType/${resource.logicalId}"
                    this.resource = resource

                    val request = Bundle.BundleEntryRequestComponent().apply {
                        method = Bundle.HTTPVerb.PUT
                        url = "${resource.resourceType.name}/${resource.id}"
                    }
                    this.request = request
                }
                bundle.addEntry(entry)
            }
        }

        Log.d("BundleService", "Created bundle with ${bundle.entry.size} total resources")
        return bundle
    }

    private suspend fun getAllResourcesWithoutLastUpdated(resourceType: String): List<Resource> {
        return when (resourceType) {
            "Patient" -> {
                fhirEngine.search<Patient> {
                    // No filter needed since we're getting all without lastUpdated
                }.map { it.resource }.filter { it.meta?.lastUpdated == null }
            }

            "QuestionnaireResponse" -> {
                fhirEngine.search<QuestionnaireResponse> {
                    // No filter needed since we're getting all without lastUpdated
                }.map { it.resource }.filter { it.meta?.lastUpdated == null }
            }

            "MeasureReport" -> {
                fhirEngine.search<MeasureReport> {
                    // No filter needed since we're getting all without lastUpdated
                }.map { it.resource }.filter { it.meta?.lastUpdated == null }
            }

            "Encounter" -> {
                fhirEngine.search<Encounter> {
                    // No filter needed since we're getting all without lastUpdated
                }.map { it.resource }.filter { it.meta?.lastUpdated == null }
            }

            "Observation" -> {
                fhirEngine.search<Observation> {
                    // No filter needed since we're getting all without lastUpdated
                }.map {
                    it.resource
                }.filter { it.meta?.lastUpdated == null }
            }

            else -> emptyList()
        }
    }
}