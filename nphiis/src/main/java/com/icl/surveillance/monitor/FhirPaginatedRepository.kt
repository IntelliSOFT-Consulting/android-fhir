package com.icl.surveillance.monitor

import android.util.Log
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.search.search
import kotlinx.coroutines.flow.MutableStateFlow
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.MeasureReport
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.Specimen

class FhirPaginatedRepository(private val fhirEngine: FhirEngine) {

    private val currentPage = MutableStateFlow(0)
    private val pageSize = 50
    private val defaultPageSize = 50

    suspend fun getResourcesPage(
        resourceType: String,
        page: Int = 0
    ): List<Resource> {
        return try {
            when (resourceType) {
                "Patient" -> {
                    fhirEngine.search<Patient> {
                        count = pageSize
                        from = page * pageSize
                    }.map { it.resource }
                        .filter {
                            it.meta?.lastUpdated == null
                        }
                }

                "QuestionnaireResponse" -> {
                    fhirEngine.search<QuestionnaireResponse> {
                        count = pageSize
                        from = page * pageSize
                    }.map { it.resource }.filter { it.meta?.lastUpdated == null }
                }

                "MeasureReport" -> {
                    fhirEngine.search<MeasureReport> {
                        count = pageSize
                        from = page * pageSize
                    }.map { it.resource }.filter { it.meta?.lastUpdated == null }
                }

                "Encounter" -> {
                    fhirEngine.search<Encounter> {
                        count = 500 // Specific count for Encounter
                        from = page * 500 // Adjust for different page size
                    }.map { it.resource }.filter { it.meta?.lastUpdated == null }
                }

                "Observation" -> {
                    fhirEngine.search<Observation> {
                        count = 500 // Specific count for Observation
                        from = page * 500 // Adjust for different page size
                    }.map { it.resource }.filter { it.meta?.lastUpdated == null }
                }

                else -> {
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e("Pagination", "Error loading page $page for $resourceType: ${e.message}")
            emptyList()
        }
    }

    suspend fun getNextPage(resourceType: String): List<Resource> {
        val nextPage = currentPage.value + 1
        val resources = getResourcesPage(resourceType, nextPage)

        if (resources.isNotEmpty()) {
            currentPage.value = nextPage
        }

        return resources
    }

    suspend fun create(rr: Resource): Bundle.BundleEntryComponent {
        return Bundle.BundleEntryComponent().apply {
            fullUrl = "${resource.resourceType}/${resource.logicalId}"
            val requestPayload = Bundle.BundleEntryRequestComponent().apply {
                method = Bundle.HTTPVerb.PUT
                url = "${resource.resourceType}/${resource.logicalId}"
            }
            request = requestPayload
            resource = rr
        }

    }

    suspend fun fetchPatientRelatedResources(
        fhirEngine: FhirEngine,
        patientId: String,
        resourceTypes: List<ResourceType>,
        pageSize: Int = 200,
        onlyMissingLastUpdated: Boolean = true
    ): List<Resource> {
        val patientRef = "Patient/$patientId"
        val out = mutableListOf<Resource>()

        suspend fun <T : Resource> fetchPaged(
            searchBlock: suspend (from: Int) -> List<T>
        ) {
            var from = 0
            while (true) {
                val batch = searchBlock(from)
                if (batch.isEmpty()) break

                val filtered = if (onlyMissingLastUpdated) {
                    batch.filter { it.meta?.lastUpdated == null }
                } else batch

                out.addAll(filtered)
                if (batch.size < pageSize) break
                from += pageSize
            }
        }

        for (type in resourceTypes) {
            when (type) {
                ResourceType.Encounter -> fetchPaged { from ->
                    fhirEngine.search<Encounter> {
                        filter(Encounter.SUBJECT, { value = patientRef })
                        count = pageSize
                        this.from = from
                    }.map { it.resource }
                }

                ResourceType.Observation -> fetchPaged { from ->
                    fhirEngine.search<Observation> {
                        filter(Observation.SUBJECT, { value = patientRef })
                        count = pageSize
                        this.from = from
                    }.map { it.resource }
                }

                ResourceType.QuestionnaireResponse -> fetchPaged { from ->
                    fhirEngine.search<QuestionnaireResponse> {
                        filter(QuestionnaireResponse.SUBJECT, { value = patientRef })
                        count = pageSize
                        this.from = from
                    }.map { it.resource }
                }

                ResourceType.Specimen -> fetchPaged { from ->
                    fhirEngine.search<Specimen> {
                        filter(Specimen.SUBJECT, { value = patientRef })
                        count = pageSize
                        this.from = from
                    }.map { it.resource }
                }

                else -> emptyList<Resource>()
            }
        }

        return out
    }


    suspend fun getFirstPage(resourceType: String): List<Resource> {
        currentPage.value = 0
        return getResourcesPage(resourceType, 0)
    }

    fun hasMore(resources: List<Resource>): Boolean {
        return resources.size == pageSize
    }

    fun getPageSize(resourceType: String): Int {
        return when (resourceType) {
            "Encounter", "Observation" -> 500
            else -> defaultPageSize
        }
    }

    fun resetPagination() {
        currentPage.value = 0
    }
}