package com.icl.surveillance.ui.patients

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum
import ca.uhn.fhir.parser.IParser
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.SearchResult
import com.google.android.fhir.datacapture.extensions.asStringValue
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.search.Order
import com.google.android.fhir.search.StringFilterModifier
import com.google.android.fhir.search.count
import com.google.android.fhir.search.revInclude
import com.google.android.fhir.search.search
import com.icl.surveillance.models.UserRole
import com.icl.surveillance.network.RetrofitCallsAuthentication
import com.icl.surveillance.utils.FormatterClass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.DecimalType
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Enumeration
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.MeasureReport
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Quantity
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.StringType
import org.hl7.fhir.r4.model.TimeType
import org.hl7.fhir.r4.model.UriType
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.String

class PatientListViewModel(
    application: Application, private val fhirEngine: FhirEngine
) : AndroidViewModel(application) {
    val liveSearchedPatients = MutableLiveData<List<PatientItem>>()
    val liveSearchedCases = MutableLiveData<List<PatientItem>>()
    val liveRumorCases = MutableLiveData<List<RumorItem>>()
    val patientCount = MutableLiveData<Long>()
    private val _patients = MutableStateFlow<List<PatientItem>>(emptyList())
    val patients: StateFlow<List<PatientItem>> = _patients

    private var page = 0
    private val pageSize = 50
    private var pageUpload = 0
    private val pageSizeUpload = 50
    private var hasMore = true
    private var isLoading = false
    private var hasMoreUpload = true
    private var isUploadLoading = false

    fun prepareListInBatches(nameQuery: String, context: Context) {
        val isSummary = nameQuery.contains("mpox")
        if (!hasMoreUpload || isUploadLoading) return
        isUploadLoading = true
        page++
        val jsonParser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()

        viewModelScope.launch(Dispatchers.IO) {
            val results = fhirEngine.search<Patient> {
                sort(Patient.GIVEN, Order.ASCENDING)
                count = pageSizeUpload
                from = (pageUpload - 1) * pageSizeUpload
            }

            if (results.isEmpty()) {
                hasMoreUpload = false
            } else {
                val bundle = Bundle()
                bundle.type = Bundle.BundleType.TRANSACTION

                results.forEach { patient ->
                    val patientResource = patient.resource.copy() as Patient
                    patientResource.nameFirstRep.family = "test-bundle-2"
                    //  sendSingleEntry(jsonParser, patientResource)
                    val bundleEntry = Bundle.BundleEntryComponent()
                    bundleEntry.resource = patientResource
                    bundleEntry.fullUrl = "Patient/${patientResource.idElement.idPart}"
                    bundleEntry.request = Bundle.BundleEntryRequestComponent()
                    bundleEntry.request.setMethod(Bundle.HTTPVerb.PUT)
                    bundleEntry.request.url =
                        "Patient/${patientResource.idElement.idPart}"
                    bundle.addEntry(bundleEntry)
                }
                sendBundleToServer(jsonParser, bundle, context)
            }
            isUploadLoading = false
        }
    }

    fun prepareEncountersBatches(nameQuery: String, context: Context) {
        val isSummary = nameQuery.contains("mpox")
        if (!hasMoreUpload || isUploadLoading) return
        isUploadLoading = true
        page++
        val jsonParser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()

        viewModelScope.launch(Dispatchers.IO) {
            val results = fhirEngine.search<Encounter> {
                count = pageSizeUpload
                from = (pageUpload - 1) * pageSizeUpload
            }

            if (results.isEmpty()) {
                hasMoreUpload = false
            } else {
                val bundle = Bundle()
                bundle.type = Bundle.BundleType.TRANSACTION

                results.forEach { patient ->
                    val patientResource = patient.resource.copy() as Encounter
//                    patientResource.nameFirstRep.family = "test-bundle-2"
                    //  sendSingleEntry(jsonParser, patientResource)
                    val bundleEntry = Bundle.BundleEntryComponent()
                    bundleEntry.resource = patientResource
                    bundleEntry.fullUrl = "Encounter/${patientResource.idElement.idPart}"
                    bundleEntry.request = Bundle.BundleEntryRequestComponent()
                    bundleEntry.request.setMethod(Bundle.HTTPVerb.PUT)
                    bundleEntry.request.url =
                        "Encounter/${patientResource.idElement.idPart}"
                    bundle.addEntry(bundleEntry)
                }
                sendBundleToServer(jsonParser, bundle, context)
            }
            isUploadLoading = false
        }
    }

    fun prepareObsBatches(nameQuery: String, context: Context) {
        val isSummary = nameQuery.contains("mpox")
        if (!hasMoreUpload || isUploadLoading) return
        isUploadLoading = true
        page++
        val jsonParser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()

        viewModelScope.launch(Dispatchers.IO) {
            val results = fhirEngine.search<Observation> {
                count = pageSizeUpload
                from = (pageUpload - 1) * pageSizeUpload
            }

            if (results.isEmpty()) {
                hasMoreUpload = false
            } else {
                val bundle = Bundle()
                bundle.type = Bundle.BundleType.TRANSACTION

                results.forEach { patient ->
                    val patientResource = patient.resource.copy() as Observation
//                    patientResource.nameFirstRep.family = "test-bundle-2"
                    //  sendSingleEntry(jsonParser, patientResource)
                    val bundleEntry = Bundle.BundleEntryComponent()
                    bundleEntry.resource = patientResource
                    bundleEntry.fullUrl = "Observation/${patientResource.idElement.idPart}"
                    bundleEntry.request = Bundle.BundleEntryRequestComponent()
                    bundleEntry.request.setMethod(Bundle.HTTPVerb.PUT)
                    bundleEntry.request.url =
                        "Observation/${patientResource.idElement.idPart}"
                    bundle.addEntry(bundleEntry)
                }
                sendBundleToServer(jsonParser, bundle, context)
            }
            isUploadLoading = false
        }
    }

    fun prepareQuestionnaireResponseBatches(nameQuery: String, context: Context) {
        val isSummary = nameQuery.contains("mpox")
        if (!hasMoreUpload || isUploadLoading) return
        isUploadLoading = true
        page++
        val jsonParser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()

        viewModelScope.launch(Dispatchers.IO) {
            val results = fhirEngine.search<QuestionnaireResponse> {
                count = pageSizeUpload
                from = (pageUpload - 1) * pageSizeUpload
            }

            if (results.isEmpty()) {
                hasMoreUpload = false
            } else {
                val bundle = Bundle()
                bundle.type = Bundle.BundleType.TRANSACTION

                results.forEach { patient ->
                    val patientResource = patient.resource.copy() as QuestionnaireResponse
//                    patientResource.nameFirstRep.family = "test-bundle-2"
                    //  sendSingleEntry(jsonParser, patientResource)
                    val bundleEntry = Bundle.BundleEntryComponent()
                    bundleEntry.resource = patientResource
                    bundleEntry.fullUrl =
                        "QuestionnaireResponse/${patientResource.idElement.idPart}"
                    bundleEntry.request = Bundle.BundleEntryRequestComponent()
                    bundleEntry.request.setMethod(Bundle.HTTPVerb.PUT)
                    bundleEntry.request.url =
                        "QuestionnaireResponse/${patientResource.idElement.idPart}"
                    bundle.addEntry(bundleEntry)
                }
                sendBundleToServer(jsonParser, bundle, context)
            }
            isUploadLoading = false
        }
    }

    fun prepareMeasureReportBatches(nameQuery: String, context: Context) {
        val isSummary = nameQuery.contains("mpox")
        if (!hasMoreUpload || isUploadLoading) return
        isUploadLoading = true
        page++
        val jsonParser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()

        viewModelScope.launch(Dispatchers.IO) {
            val results = fhirEngine.search<MeasureReport> {
                count = pageSizeUpload
                from = (pageUpload - 1) * pageSizeUpload
            }

            if (results.isEmpty()) {
                hasMoreUpload = false
            } else {
                val bundle = Bundle()
                bundle.type = Bundle.BundleType.TRANSACTION

                results.forEach { patient ->
                    val patientResource = patient.resource.copy() as QuestionnaireResponse
//                    patientResource.nameFirstRep.family = "test-bundle-2"
                    //  sendSingleEntry(jsonParser, patientResource)
                    val bundleEntry = Bundle.BundleEntryComponent()
                    bundleEntry.resource = patientResource
                    bundleEntry.fullUrl = "MeasureReport/${patientResource.idElement.idPart}"
                    bundleEntry.request = Bundle.BundleEntryRequestComponent()
                    bundleEntry.request.setMethod(Bundle.HTTPVerb.PUT)
                    bundleEntry.request.url =
                        "MeasureReport/${patientResource.idElement.idPart}"
                    bundle.addEntry(bundleEntry)
                }
                sendBundleToServer(jsonParser, bundle, context)
            }
            isUploadLoading = false
        }
    }

    fun loadAllPatients(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val jsonParser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()
            val results = fhirEngine.search<Patient> {
                sort(Patient.GIVEN, Order.ASCENDING)
                count = 50
                from = 0
            }
            if (results.isNotEmpty()) {
                Log.e("Patient  Record ::::", " Count ${results.size}")
                val bundle = Bundle()
                bundle.type = Bundle.BundleType.TRANSACTION

                results.forEach { patient ->

                    val patientResource = patient.resource.copy() as Patient
                    patientResource.nameFirstRep.family = "test-bundle-2"

                    //  sendSingleEntry(jsonParser, patientResource)

                    val bundleEntry = Bundle.BundleEntryComponent()
                    bundleEntry.resource = patientResource
                    bundleEntry.fullUrl = "Patient/${patientResource.idElement.idPart}"
                    bundleEntry.request = Bundle.BundleEntryRequestComponent()
                    bundleEntry.request.setMethod(Bundle.HTTPVerb.PUT)
                    bundleEntry.request.url =
                        "Patient/${patientResource.idElement.idPart}"
                    bundle.addEntry(bundleEntry)

                }
                // handle API call
                val payload = jsonParser.encodeResourceToString(bundle)
                println("API Response:::: Ready to send bundle $payload")
                sendBundleToServer(jsonParser, bundle, context)
            }
        }
    }

    private fun sendSingleEntry(jsonParser: IParser, patientResource: Patient, context: Context) {
        viewModelScope.launch {
            val payload = jsonParser.encodeResourceToString(patientResource)
            val apiCall = RetrofitCallsAuthentication()

            val json = jsonParser.encodeResourceToString(patientResource)
            val requestBody = json.toRequestBody("application/json".toMediaType())
            apiCall.sendPatientToServer(patientResource.idElement.idPart, requestBody, context)
        }
    }

    private fun sendBundleToServer(
        jsonParser: IParser,
        bundle: Bundle,
        context: Context
    ) {
        viewModelScope.launch {
            println("API Response:::: Preparing data")
            val payload = jsonParser.encodeResourceToString(bundle)
            val apiCall = RetrofitCallsAuthentication()
            val json = jsonParser.encodeResourceToString(bundle)
            val requestBody = json.toRequestBody("application/json".toMediaType())
            apiCall.sendBundleToServer(requestBody, context)
        }
    }


    fun simulateScrollUntilEnd(
        slug: String,
        units: List<String>, userRole: UserRole?,
        onFinished: (List<PatientItem>) -> Unit,
    ) {
        viewModelScope.launch {
            while (hasMore) {
                loadMpoxPatientList(slug, units, userRole)
                delay(300L) // optional pause to mimic scrolling
            }
            // When done, send back the full list
            onFinished(_patients.value)
        }
    }

    fun loadMpoxPatientList(nameQuery: String, units: List<String>, userRole: UserRole?) {
        val isSummary = nameQuery.contains("mpox")
        if (!hasMore || isLoading) return
        isLoading = true
        page++

        viewModelScope.launch(Dispatchers.IO) {
            val results = fhirEngine.search<Patient> {
                sort(Patient.GIVEN, Order.ASCENDING)
                count = pageSize
                from = (page - 1) * pageSize
                revInclude<Observation>(Observation.SUBJECT)
            }

            if (results.isEmpty()) {
                hasMore = false
            } else {

                val mapped = results.mapIndexedNotNull { index, wrapper ->
                    val p = wrapper.resource
                    val matchingIdentifier = p.identifier.find {
                        it.system == nameQuery
                    }

                    val tag =
                        wrapper.resource.meta.tag.find { it.system.endsWith("/patient-managingLocation") }?.code

                    if (matchingIdentifier != null) {

                        val obs =
                            wrapper.revIncluded?.get(ResourceType.Observation to Observation.SUBJECT.paramName) as? List<Observation>
                                ?: emptyList()
                        val epid =
                            obs.firstOrNull { it.code.codingFirstRep.code == "EPID" }?.value?.asStringValue()
                                ?: ""

                        val county =
                            obs.firstOrNull { it.code.codingFirstRep.code == "a4-county" }?.value?.asStringValue()
                                ?: ""
                        val subCounty =
                            obs.firstOrNull { it.code.codingFirstRep.code == "a3-sub-county" }?.value?.asStringValue()
                                ?: ""
                        val onset =
                            obs.firstOrNull { it.code.codingFirstRep.code == "728034137219" }?.value?.asStringValue()
                                ?: ""
                        val caseList =
                            obs.firstOrNull { it.code.codingFirstRep.code == "865158268604" }?.value?.asStringValue()
                                ?: "Case"


                        val campaignDay =
                            obs.firstOrNull { it.code.codingFirstRep.code == "campaign_day" }?.value?.asStringValue()
                                ?: ""
                        val teamNumber =
                            obs.firstOrNull { it.code.codingFirstRep.code == "team_no" }?.value?.asStringValue()
                                ?: ""
                        val supervisorName =
                            obs.firstOrNull { it.code.codingFirstRep.code == "supervisor_name" }?.value?.asStringValue()
                                ?: ""
                        var occupation =
                            obs.firstOrNull { it.code.codingFirstRep.code == "occupation" }?.value?.asStringValue()
                                ?: ""
                        val occupationOther =
                            obs.firstOrNull { it.code.codingFirstRep.code == "occupation_other" }?.value?.asStringValue()
                                ?: ""

                        if (occupation == "Other") {
                            occupation = occupationOther
                        }
                        val vaccinationCenter =
                            obs.firstOrNull { it.code.codingFirstRep.code == "vaccination_center" }?.value?.asStringValue()
                                ?: ""
                        val logicalId = matchingIdentifier.value
                        val encounterQuestionnaire = matchingIdentifier.system
                        PatientItem(
                            id = (index + 1).toString(),
                            resourceId = p.logicalId,
                            encounterId = logicalId,
                            name = if (p.hasName()) p.nameFirstRep.nameAsSingleString else "",
                            gender = "",
                            phone = "",
                            city = "",
                            country = "",
                            isActive = true,
                            epid = " $epid",
                            county = " $county",
                            subCounty = " $subCounty",
                            caseOnsetDate = "",
                            lastUpdated = "",
                            encounterQuestionnaire = "$encounterQuestionnaire",
                            isSummary = isSummary,
                            campaignDate = "",
                            teamNumber = " $teamNumber",
                            supervisorName = "",
                            vaccinationCenter = " $vaccinationCenter",
                            occupation = " $occupation",
                            syncStatus = "Pending",
                            sourceTag = "$tag"
                        )
                    } else {
                        null
                    }
                }.filter {
                    when (userRole) {
                        UserRole.ADMINISTRATOR -> {
                            true
                        }

                        else -> {
                            it.sourceTag in units
                        }
                    }


                }

                _patients.update { it + mapped }
            }
            isLoading = false
        }
    }


    init {
        updatePatientListAndPatientCount({ getSearchResults() }, { searchedPatientCount() })
    }

    fun searchPatientsByName(nameQuery: String) {
        updatePatientListAndPatientCount({ getSearchResults(nameQuery) }, { count(nameQuery) })
    }

    fun handleCurrentCaseListing(category: String, units: List<String>, userRole: UserRole?) {
        viewModelScope.launch {
            liveSearchedCases.value = retrieveCasesByDisease(category, units, userRole)

        }
    }

    fun handleCurrentRumorCaseListing(category: String, units: List<String>, userRole: UserRole?) {
        viewModelScope.launch {
            liveRumorCases.value = retrieveRumorCasesByDisease(category, units, userRole)

        }
    }


    private suspend fun loadSupervisorChecklistCases(isSummary: Boolean): List<PatientItem> {
        val responses = fhirEngine.search<QuestionnaireResponse> {
            sort(QuestionnaireResponse.AUTHORED, Order.DESCENDING)
            count = 5000
            from = 0
        }

        return responses.mapIndexed { index, response ->
            mapToSupervisorChecklistItem(index, response.resource, isSummary)
        }.sortedByDescending { it.lastUpdated }
    }

    private suspend fun loadPatientCases(
        nameQuery: String,
        isSummary: Boolean
    ): List<PatientItem> {
        val patients = fhirEngine.search<Patient> {
            sort(Patient.GIVEN, Order.ASCENDING)
            count = 5000
            from = 0
        }

        return patients.mapIndexedNotNull { index, result ->
            mapToPatientItem(index, result.resource, nameQuery, isSummary)
        }.sortedByDescending { it.lastUpdated }
    }

    private fun mapToSupervisorChecklistItem(
        index: Int, response: QuestionnaireResponse, isSummary: Boolean
    ): PatientItem {
        val tag =
            response.meta.tag.find { it.system.endsWith("/questionnaire-managingLocation") }?.code

        val county = getAnswerValueAsString(response.item, "294367770999")
        val subCounty = getAnswerValueAsString(response.item, "819946803642")
        var caseOnsetDate = getAnswerValueAsString(response.item, "728034137219")

        val siteName = getAnswerValueAsString(response.item, "site_name")
        val teamNumber = getAnswerValueAsString(response.item, "site_type")
        val supervisorName = getAnswerValueAsString(response.item, "supervisor_name")

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val authored = try {
            response.authored?.toInstant()?.atZone(ZoneId.systemDefault())?.toLocalDateTime()
                ?.format(formatter) ?: ""
        } catch (e: Exception) {
            ""
        }

        if (caseOnsetDate.isEmpty()) {
            caseOnsetDate = try {
                response.authored?.toInstant()?.atZone(ZoneId.systemDefault())?.toLocalDate()
                    .toString()
            } catch (e: Exception) {
                ""
            }
        }

        return PatientItem(
            id = (index + 1).toString(),
            resourceId = response.logicalId,
            encounterId = response.logicalId,
            name = response.item.firstOrNull()?.item?.firstOrNull { it.linkId == "294367770999" }?.answer?.firstOrNull()?.valueReference?.display
                ?: "",
            gender = "",
            phone = "",
            city = "",
            country = "",
            isActive = false,
            epid = "",
            county = county,
            subCounty = subCounty,
            caseOnsetDate = caseOnsetDate,
            lastUpdated = authored,
            isSummary = isSummary,
            campaignDate = siteName,
            teamNumber = teamNumber,
            supervisorName = supervisorName,
            sourceTag = "$tag"
        )
    }

    private suspend fun mapToPatientItem(
        index: Int, patient: Patient, nameQuery: String, isSummary: Boolean
    ): PatientItem? {
        val matchingIdentifier = when (nameQuery) {
            "rcce" -> patient.identifier.find {
                it.system == "rcce-community-questionnaire" || it.system == "rcce-countysubcounty-interface"
            }

            else -> patient.identifier.find { it.system == nameQuery }
        } ?: return null

        val logicalId = matchingIdentifier.value
        val encounterQuestionnaire = matchingIdentifier.system

        // Load observations linked to encounter
        val obs = fhirEngine.search<Observation> {
            filter(
                Observation.ENCOUNTER, { value = "Encounter/${logicalId}" })
        }.take(500)

        // Build base PatientItem
        var data = patient.toPatientItem(index + 1).copy(
            encounterId = logicalId,
            encounterQuestionnaire = encounterQuestionnaire,
            isSummary = isSummary,
            // map county, subcounty, epid, etc. here like in your code
        )

        // Disease-specific enrichment
        val childEncounter = loadChildEncounter(data.resourceId, logicalId)
        data = when (nameQuery) {
            "vl-case-information" -> enrichLabResultsForVL(childEncounter, data)
            "afp-case-information" -> enrichLabResultsForAFP(childEncounter, data)
            else -> enrichLabResultsForMeasles(childEncounter, data)
        }

        return data
    }

    private suspend fun enrichLabResultsForMeasles(
        childEncounters: List<EncounterItem>, data: PatientItem
    ): PatientItem {
        // Find the child encounter specifically for Measles Final Lab Information
        val childCaseInfoEncounter = childEncounters.firstOrNull {
            it.reasonCode == "Measles Final Lab Information"
        } ?: return data // nothing to enrich

        // Load Observations for this encounter
        val obsList = fhirEngine.search<Observation> {
            filter(
                Observation.ENCOUNTER, { value = "Encounter/${childCaseInfoEncounter.id}" })
        }

        // Extract Measles result with empty fallback
        val measles =
            obsList.firstOrNull { it.resource.code.codingFirstRep.code == "2437874573" }?.resource?.value?.asStringValue()
                ?: ""

        // Extract Rubella result with empty fallback
        val rubella =
            obsList.firstOrNull { it.resource.code.codingFirstRep.code == "2636544254" }?.resource?.value?.asStringValue()
                ?: ""

        // Classification logic
        val status = when {
            measles.equals("Positive", ignoreCase = true) -> "Confirmed by lab"
            rubella.equals("Positive", ignoreCase = true) -> "Confirmed rubella"
            measles.equals("Negative", ignoreCase = true) && rubella.equals(
                "Negative", ignoreCase = true
            ) -> "Discarded"

            else -> "" // no classification
        }

        return data.copy(
            labResults = "Measles: $measles, Rubella: $rubella".trim().trimStart(','),
            status = status
        )
    }

    private suspend fun enrichLabResultsForAFP(
        childEncounters: List<EncounterItem>, data: PatientItem
    ): PatientItem {
        // Find the child encounter specifically for AFP Final Lab Information
        val childCaseInfoEncounter = childEncounters.firstOrNull {
            it.reasonCode == "AFP Final Lab Information"
        } ?: return data // nothing to enrich

        // Load Observations for this encounter
        val obsList = fhirEngine.search<Observation> {
            filter(
                Observation.ENCOUNTER, { value = "Encounter/${childCaseInfoEncounter.id}" })
        }

        // Extract AFP result with empty fallback
        val afp =
            obsList.firstOrNull { it.resource.code.codingFirstRep.code == "329949474707" }?.resource?.value?.asStringValue()
                ?: ""

        // Map AFP result to classification status
        val status = when (afp) {
            "WPV", "cVDPV", "aVDPV", "iVDPV" -> "Confirmed by lab"
            "Discarded" -> "Discarded"
            "Compatible" -> "Compatible"
            else -> "" // no classification
        }

        return data.copy(
            labResults = afp, status = status
        )
    }

    private suspend fun enrichLabResultsForVL(
        childEncounters: List<EncounterItem>, data: PatientItem
    ): PatientItem {
        // Find the child encounter specifically for VL Laboratory Examination
        val childCaseInfoEncounter = childEncounters.firstOrNull {
            it.reasonCode == "VL Laboratory Examination"
        } ?: return data // nothing to enrich

        // Load Observations for this encounter
        val obsList = fhirEngine.search<Observation> {
            filter(
                Observation.ENCOUNTER, { value = "Encounter/${childCaseInfoEncounter.id}" })
        }

        // Extract individual lab results with empty string fallback
        val rapidResults =
            obsList.firstOrNull { it.resource.code.codingFirstRep.code == "286501145394" }?.resource?.value?.asStringValue()
                ?: ""

        val datResult =
            obsList.firstOrNull { it.resource.code.codingFirstRep.code == "839711142610" }?.resource?.value?.asStringValue()
                ?: ""

        val aResult =
            obsList.firstOrNull { it.resource.code.codingFirstRep.code == "108406555539" }?.resource?.value?.asStringValue()
                ?: ""

        val mResult =
            obsList.firstOrNull { it.resource.code.codingFirstRep.code == "320819009291" }?.resource?.value?.asStringValue()
                ?: ""

        var status =
            obsList.firstOrNull { it.resource.code.codingFirstRep.code == "655245793432" }?.resource?.value?.asStringValue()
                ?: ""

        val otherStatus =
            obsList.firstOrNull { it.resource.code.codingFirstRep.code == "843481153132" }?.resource?.value?.asStringValue()
                ?: ""

        if (status == "Other (specify)") {
            status = otherStatus
        }

        // Normalize to lowercase for classification
        val allResults =
            listOf(rapidResults, datResult, aResult, mResult).filter { it.isNotEmpty() }
                .map { it.lowercase() }

        val results = when {
            allResults.any { it == "positive" } -> "Positive"
            allResults.isNotEmpty() && allResults.all { it == "negative" } -> "Negative"
            allResults.isNotEmpty() && allResults.all { it == "not done" } -> "Not Done"
            else -> "" // no conclusive result
        }

        return data.copy(
            labResults = results, status = status
        )
    }

    private suspend fun retrieveCasesByDisease(
        nameQuery: String,
        units: List<String>,
        userRole: UserRole?,
    ): List<PatientItem> {
        val isSummary = nameQuery.contains("mpox")

        when (nameQuery) {
            "mpox-tally-sheet" -> {
                val questionnaireData: MutableList<PatientItem> = mutableListOf()
                fhirEngine.search<MeasureReport> {
                    sort(MeasureReport.DATE, Order.DESCENDING)
                }
                    .mapIndexedNotNull { index, data ->
                        val tag =
                            data.resource.meta.tag.find { it.system.endsWith("/measure-managingLocation") }?.code

                        val identifier = data.resource.identifier.find {
                            it.system == "geo-location-details"
                        }
                        if (identifier != null) {


                            try {
                                val patientId = data.resource.subject.reference.split("/").last()

                                val searchResult = fhirEngine.search<Patient> {
                                    filter(Resource.RES_ID, { value = of(patientId) })
                                    revInclude<Observation>(Observation.SUBJECT)
                                }
                                if (searchResult.isNotEmpty()) {
                                    searchResult.first().let {
                                        val encounterId = if (it.resource.hasIdentifier()) {
                                            val enco =
                                                it.resource.identifier.find { id -> id.system == "mpox-tally-sheet" }
                                            if (enco != null) {
                                                enco.value
                                            } else ""
                                        } else ""
                                        val observations =
                                            it.revIncluded?.get(ResourceType.Observation to Observation.SUBJECT.paramName) as? List<Observation>
                                                ?: emptyList()

                                        // Create team_numberr
                                        val teamNumber =
                                            observations.firstOrNull { it.code.codingFirstRep.code == "team_no" }?.value?.asStringValue()
                                                ?: ""
                                        // supervisor name
                                        val supervisorName =
                                            observations.firstOrNull { it.code.codingFirstRep.code == "supervisor_name" }?.value?.asStringValue()
                                                ?: ""
                                        //County
                                        val county =
                                            observations.firstOrNull { it.code.codingFirstRep.code == "294367770999" }?.value?.asStringValue()
                                                ?: ""
                                        // SubCounty
                                        val subCounty =
                                            observations.firstOrNull { it.code.codingFirstRep.code == "819946803642" }?.value?.asStringValue()
                                                ?: ""
                                        // Campaign Day
                                        val campaignDay =
                                            observations.firstOrNull { it.code.codingFirstRep.code == "campaign_day" }?.value?.asStringValue()
                                                ?: ""

                                        val formatted =
                                            observations.firstOrNull { it.code.codingFirstRep.code == "728034137219" }?.value?.asStringValue()
                                                ?: ""

                                        if (county.isNotEmpty()) {
                                            val resource = PatientItem(
                                                id = (index + 1).toString(),
                                                resourceId = patientId,
                                                encounterId = encounterId,
                                                lastUpdated = "${data.resource.date}",
                                                name = "",
                                                gender = "",
                                                phone = "",
                                                city = "",
                                                country = " $county",
                                                isActive = true,
                                                epid = "",
                                                county = " $county",
                                                subCounty = " $subCounty",
                                                caseOnsetDate = " $formatted",
                                                isSummary = isSummary,
                                                campaignDate = " $campaignDay",
                                                teamNumber = " $teamNumber",
                                                supervisorName = " $supervisorName",
                                                sourceTag = "$tag"
                                            )

                                            resource
                                        } else {
                                            null
                                        }
                                    }
                                } else {
                                    null
                                }
                            } catch (e: Exception) {
                                null
                            }

                        } else {
                            null
                        }
                    }
                    .filter { it.sourceTag in units }
                    .also { questionnaireData.addAll(it) }


                return questionnaireData.sortedByDescending { it.lastUpdated }
            }

            "mpox-register" -> {

                val questionnaireData: MutableList<PatientItem> = mutableListOf()
                fhirEngine.search<Patient> {
                    sort(Patient.GIVEN, Order.ASCENDING)
                    revInclude<Observation>(Observation.SUBJECT)
                }.mapIndexedNotNull { index, fhirPatient ->
                    val tag =
                        fhirPatient.resource.meta.tag.find { it.system.endsWith("/patient-managingLocation") }?.code

                    val matchingIdentifier = fhirPatient.resource.identifier.find {
                        it.system == nameQuery
                    }
                    val epidIdenfifier =
                        fhirPatient.resource.identifier.find { it.type.codingFirstRep.code == "EPID" }

                    if (matchingIdentifier != null) {
                        // Convert the FHIR Patient resource to your PatientItem model
                        var data = fhirPatient.resource.toPatientItem(index + 1)
                        val logicalId = matchingIdentifier.value
                        val encounterQuestionnaire = matchingIdentifier.system
                        data = data.copy(
                            vaccinationCenter = "vaccinationCenter",
                            occupation = "occupation",
                            caseList = "caseList",
                            encounterId = logicalId,
                            epid = "${epidIdenfifier?.value}",
                            county = "county",
                            subCounty = "subCounty",
                            caseOnsetDate = "onset",
                            encounterQuestionnaire = encounterQuestionnaire,
                            isSummary = isSummary,
                            campaignDate = "campaignDay",
                            teamNumber = "teamNumber",
                            supervisorName = "supervisorName",
                            sourceTag = "$tag"
                        )
                        data
                    } else {
                        null // Not a match — exclude
                    }
                }.also {
                    questionnaireData.addAll(it)
                }
                    .filter { it.sourceTag in units }

                return questionnaireData.sortedByDescending { it.lastUpdated }
            }

            "mpox-supervisor-checklist" -> {

                val questionnaireData: MutableList<PatientItem> = mutableListOf()
                fhirEngine.search<QuestionnaireResponse> {
                    sort(QuestionnaireResponse.AUTHORED, Order.DESCENDING)

                }
                    .mapIndexedNotNull { index, fhirPatient ->

                        val tag =
                            fhirPatient.resource.meta.tag.find { it.system.endsWith("/questionnaire-managingLocation") }?.code

                        if (fhirPatient.resource.hasIdentifier()) {
                            val county =
                                getAnswerValueAsString(fhirPatient.resource.item, "294367770999")
                            val subCounty =
                                getAnswerValueAsString(fhirPatient.resource.item, "819946803642")
                            var caseOnsetDate =
                                getAnswerValueAsString(fhirPatient.resource.item, "728034137219")

                            val siteName =
                                getAnswerValueAsString(fhirPatient.resource.item, "site_name")

                            val teamNumber =
                                getAnswerValueAsString(fhirPatient.resource.item, "site_type")

                            val supervisorName =
                                getAnswerValueAsString(fhirPatient.resource.item, "supervisor_name")
                            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

                            val authored = try {
                                val authoredDate: Date = fhirPatient.resource.authored
                                val localDate =
                                    authoredDate.toInstant().atZone(ZoneId.systemDefault())
                                        .toLocalDateTime()
                                localDate.format(formatter)  // format here instead of toString()
                            } catch (e: Exception) {
                                ""
                            }

                            if (caseOnsetDate.isEmpty()) {
                                caseOnsetDate = try {
                                    val authoredDate: Date = fhirPatient.resource.authored
                                    val localDate =
                                        authoredDate.toInstant().atZone(ZoneId.systemDefault())
                                            .toLocalDate()
                                    localDate.toString()
                                } catch (e: Exception) {
                                    ""
                                }

                            }

                            val data = PatientItem(
                                id = (index + 1).toString(),
                                resourceId = fhirPatient.resource.logicalId,
                                encounterId = fhirPatient.resource.logicalId,
                                name = fhirPatient.resource.item.firstOrNull()?.item?.firstOrNull() { it.linkId == "294367770999" }?.answer?.firstOrNull()?.valueReference?.display
                                    ?: "",
                                gender = "",
                                phone = "",
                                city = "",
                                country = "",
                                isActive = false,
                                epid = "",
                                county = " $county",
                                subCounty = " $subCounty",
                                caseOnsetDate = caseOnsetDate,
                                lastUpdated = authored,
                                isSummary = isSummary,
                                campaignDate = siteName,
                                teamNumber = teamNumber,
                                supervisorName = supervisorName,
                                sourceTag = "$tag"
                            )
                            data
                        } else {
                            null
                        }
                    }.also {
                        questionnaireData.addAll(it)
                    }
                    .filter { it.sourceTag in units }

                return questionnaireData.sortedByDescending { it.lastUpdated }
            }

            else -> {
//                val result = fhirEngine.search("Patient?gender=female")
                return fhirEngine.search<Patient> {
                    sort(Patient.GIVEN, Order.ASCENDING)

                }.mapIndexedNotNull { index, fhirPatient ->

                    val tag =
                        fhirPatient.resource.meta.tag.find { it.system.endsWith("/patient-managingLocation") }?.code

                    val matchingIdentifier = when (nameQuery) {
                        "rcce" -> fhirPatient.resource.identifier.find {
                            it.system == "rcce-community-questionnaire" || it.system == "rcce-countysubcounty-interface"
                        }

                        else -> fhirPatient.resource.identifier.find {
                            it.system == nameQuery
                        }
                    }
                    val epidIdenfifier =
                        fhirPatient.resource.identifier.find { it.type.codingFirstRep.code == "EPID" }


                    if (matchingIdentifier != null) {
                        // Convert the FHIR Patient resource to your PatientItem model
                        var data = fhirPatient.resource.toPatientItem(index + 1)
                        val logicalId = matchingIdentifier.value

                        val encounterQuestionnaire = matchingIdentifier.system
                        val obs = fhirEngine.search<Observation> {
                            filter(
                                Observation.ENCOUNTER, { value = "Encounter/${logicalId}" })
                        }.take(500)

                        val epid =
                            if (epidIdenfifier != null) epidIdenfifier.value else obs.firstOrNull { it.resource.code.codingFirstRep.code == "EPID" }?.resource?.value?.asStringValue()
                                ?: ""

                        var county =
                            if (fhirPatient.resource.hasAddress()) if (fhirPatient.resource.addressFirstRep.hasCity()) fhirPatient.resource.addressFirstRep.city else "" else obs.firstOrNull { it.resource.code.codingFirstRep.code == "a4-county" }?.resource?.value?.asStringValue()
                                ?: ""
                        var subCounty =
                            if (fhirPatient.resource.hasAddress()) if (fhirPatient.resource.addressFirstRep.hasState()) fhirPatient.resource.addressFirstRep.state else "" else obs.firstOrNull { it.resource.code.codingFirstRep.code == "a3-sub-county" }?.resource?.value?.asStringValue()
                                ?: ""
                        val onset =
                            obs.firstOrNull { it.resource.code.codingFirstRep.code == "728034137219" }?.resource?.value?.asStringValue()
                                ?: ""
                        val caseList =
                            obs.firstOrNull { it.resource.code.codingFirstRep.code == "865158268604" }?.resource?.value?.asStringValue()
                                ?: "Case"

                        val campaignDay =
                            obs.firstOrNull { it.resource.code.codingFirstRep.code == "campaign_day" }?.resource?.value?.asStringValue()
                                ?: ""
                        val teamNumber =
                            obs.firstOrNull { it.resource.code.codingFirstRep.code == "team_no" }?.resource?.value?.asStringValue()
                                ?: ""
                        val supervisorName =
                            obs.firstOrNull { it.resource.code.codingFirstRep.code == "supervisor_name" }?.resource?.value?.asStringValue()
                                ?: ""
                        var occupation =
                            obs.firstOrNull { it.resource.code.codingFirstRep.code == "occupation" }?.resource?.value?.asStringValue()
                                ?: ""
                        val occupationOther =
                            obs.firstOrNull { it.resource.code.codingFirstRep.code == "occupation_other" }?.resource?.value?.asStringValue()
                                ?: ""

                        if (occupation == "Other") {
                            occupation = occupationOther
                        }
                        val vaccinationCenter =
                            obs.firstOrNull { it.resource.code.codingFirstRep.code == "vaccination_center" }?.resource?.value?.asStringValue()
                                ?: ""


                        val childEncounter = loadChildEncounter(data.resourceId, logicalId)

                        when (nameQuery) {


                            "moh-505-reporting-form" -> {

                                val res = fhirEngine.search<QuestionnaireResponse> {
                                    filter(
                                        QuestionnaireResponse.SUBJECT,
                                        { value = "Patient/${data.resourceId}" })
                                }.take(5)

                                if (res.isNotEmpty()) {
                                    val response = res.first().resource
                                    val data = FhirContext.forR4Cached().newJsonParser()
                                        .encodeResourceToString(response)

                                    val jsonParser =
                                        FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()
                                    val questionnaireResponseString =
                                        jsonParser.encodeResourceToString(response)
                                    val jsonObject = JSONObject(questionnaireResponseString)
                                    val extractedAnswers =
                                        FormatterClass().extractStructuredAnswersOnlyFromItems(
                                            jsonObject
                                        )
                                    val countyLinkIds = listOf(
                                        "294367770999",
                                        "294367770999_sub_county",
                                        "294367770999_county",
                                        "294367770999_national"
                                    ) // check in order
                                    val subCountyLinkIds = listOf(
                                        "819946803642",
                                        "819946803642_sub_county",
                                        "819946803642_county",
                                        "819946803642_national"
                                    )

                                    county = try {
                                        countyLinkIds.firstNotNullOfOrNull { id ->
                                            extractedAnswers.find { it.linkId == id }?.answer
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        ""
                                    }
                                    subCounty = try {
                                        subCountyLinkIds.firstNotNullOfOrNull { id ->
                                            extractedAnswers.find { it.linkId == id }?.answer
                                        }

                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        ""
                                    }
                                }

                            }

                            "vl-case-information" -> {

                                val childCaseInfoEncounter = childEncounter.firstOrNull {
                                    it.reasonCode == "VL Laboratory Examination"
                                }

                                childCaseInfoEncounter?.let { kk ->
                                    val obs1 = fhirEngine.search<Observation> {
                                        filter(
                                            Observation.ENCOUNTER,
                                            { value = "Encounter/${kk.id}" })
                                    }
                                    var results = "Pending Results"
                                    val rapidResults =
                                        obs1.firstOrNull { it.resource.code.codingFirstRep.code == "286501145394" }?.resource?.value?.asStringValue()
                                            ?: "Pending"
                                    val datResult =
                                        obs1.firstOrNull { it.resource.code.codingFirstRep.code == "839711142610" }?.resource?.value?.asStringValue()
                                            ?: "Pending"

                                    val aResult =
                                        obs1.firstOrNull { it.resource.code.codingFirstRep.code == "108406555539" }?.resource?.value?.asStringValue()
                                            ?: "Pending"
                                    val mResult =

                                        obs1.firstOrNull { it.resource.code.codingFirstRep.code == "320819009291" }?.resource?.value?.asStringValue()
                                            ?: "Pending"

                                    var status =
                                        obs1.firstOrNull { it.resource.code.codingFirstRep.code == "655245793432" }?.resource?.value?.asStringValue()
                                            ?: "Pending"
                                    val otherStatus =
                                        obs1.firstOrNull { it.resource.code.codingFirstRep.code == "843481153132" }?.resource?.value?.asStringValue()
                                            ?: "Pending"

                                    if (status == "Other (specify)") {
                                        status = otherStatus
                                    }
                                    // Normalize to lowercase for easier comparison
                                    val allResults = listOf(
                                        rapidResults, datResult, aResult, mResult
                                    ).map { it.lowercase() }

                                    results = when {
                                        allResults.any { it == "positive" } -> "Positive"
                                        allResults.all { it == "negative" } -> "Negative"
                                        allResults.all { it == "not done" } -> "Not Done"
                                        else -> "Pending Results"
                                    }

                                    data = data.copy(
                                        labResults = results, status = status
                                    )
                                }
                            }

                            "afp-case-information" -> {
                                // CLASSIFICATION FOR A AFP CASE
                                val childCaseInfoEncounter = childEncounter.firstOrNull {
                                    it.reasonCode == "AFP Final Lab Information"
                                }

                                childCaseInfoEncounter?.let { kk ->
                                    val obs1 = fhirEngine.search<Observation> {
                                        filter(
                                            Observation.ENCOUNTER,
                                            { value = "Encounter/${kk.id}" })
                                    }
                                    val afp =
                                        obs1.firstOrNull { it.resource.code.codingFirstRep.code == "329949474707" }?.resource?.value?.asStringValue()
                                            ?: "Pending"

                                    data = data.copy(
                                        labResults = afp, status = when (afp) {
                                            "WPV", "cVDPV", "aVDPV", "iVDPV" -> "Confirmed by lab"
                                            "Discarded" -> "Discarded"
                                            "Compatible" -> "Compatible"
                                            else -> "Pending"
                                        }
                                    )
                                }
                            }

                            else -> {
                                var measlesIgm: String
                                var finalClassification: String
                                var maxDays: String
                                val childCaseInfoEncounter = childEncounter.firstOrNull {
                                    it.reasonCode == "Measles Lab Information"
                                }

                                childCaseInfoEncounter?.let { kk ->
                                    val obs1 = fhirEngine.search<Observation> {
                                        filter(
                                            Observation.ENCOUNTER,
                                            { value = "Encounter/${kk.id}" })
                                    }

                                    measlesIgm =
                                        obs1.firstOrNull { it.resource.code.codingFirstRep.code == "measles-igm" }?.resource?.value?.asStringValue()
                                            ?: "Pending"

                                    maxDays =
                                        obs.firstOrNull { it.resource.code.codingFirstRep.code == "308128177300" }?.resource?.value?.asStringValue()
                                            ?: ""


                                    finalClassification = when (measlesIgm.lowercase()) {
                                        "positive" -> {
                                            when (maxDays.lowercase()) {
                                                "yes" -> "Pending"
                                                else -> "Confirmed by lab"
                                            }
                                        }

                                        "negative" -> "Discarded"
                                        "indeterminate" -> "Compatible/Clinical/Probable"
                                        else -> "Pending Results"

                                    }

                                    data = data.copy(
                                        labResults = measlesIgm,
                                        status = finalClassification,
                                    )
                                }
                            }
                        }
                        data = data.copy(
                            vaccinationCenter = vaccinationCenter,
                            occupation = occupation,
                            caseList = caseList,
                            encounterId = logicalId,
                            epid = epid,
                            county = " $county",
                            subCounty = " $subCounty",
                            caseOnsetDate = onset,
                            encounterQuestionnaire = encounterQuestionnaire,
                            isSummary = isSummary,
                            campaignDate = campaignDay,
                            teamNumber = teamNumber,
                            supervisorName = supervisorName,
                            sourceTag = "$tag"
                        )
                        data
                    } else {
                        null // Not a match — exclude
                    }
                }
                    .filter {
                        when (userRole) {
                            UserRole.ADMINISTRATOR -> {
                                true
                            }

                            else -> {
                                it.sourceTag in units
                            }
                        }


                    }
                    .sortedByDescending { it.lastUpdated }
            }
        }
    }


    /**
     * [updatePatientListAndPatientCount] calls the search and count lambda and updates the live data
     * values accordingly. It is initially called when this [ViewModel] is created. Later its called
     * by the client every time search query changes or data-sync is completed.
     */
    private fun updatePatientListAndPatientCount(
        search: suspend () -> List<PatientItem>,
        count: suspend () -> Long,
    ) {
        viewModelScope.launch {
            liveSearchedPatients.value = search()
            patientCount.value = count()
        }
    }

    /**
     * Returns count of all the [Patient] who match the filter criteria unlike [getSearchResults]
     * which only returns a fixed range.
     */
    private suspend fun count(nameQuery: String = ""): Long {
        return fhirEngine.count<Patient> {
            if (nameQuery.isNotEmpty()) {
                filter(
                    Patient.NAME,
                    {
                        modifier = StringFilterModifier.CONTAINS
                        value = nameQuery
                    },
                )
            }
        }
    }

    private suspend fun getSearchResults(
        nameQuery: String = "",
    ): List<PatientItem> {

        val patients: MutableList<PatientItem> = mutableListOf()
        fhirEngine.search<Patient> {
            if (nameQuery.isNotEmpty()) {
                filter(
                    Patient.NAME,
                    {
                        modifier = StringFilterModifier.CONTAINS
                        value = nameQuery
                    },
                )
            }
            sort(Patient.GIVEN, Order.ASCENDING)
            count = 100
            from = 0
        }.mapIndexed { index, fhirPatient ->
            var item = fhirPatient.resource.toPatientItem(index + 1)
            try {

                val encounter = loadEncounter(item.resourceId)
                val caseInfoEncounter = encounter.firstOrNull {
                    it.reasonCodeFirstRep.codingFirstRep.code == "Case Information"
                }

                caseInfoEncounter?.let {

                    val childEncounter = loadChildEncounter(item.resourceId, it.logicalId)
                    val childCaseInfoEncounter = childEncounter.firstOrNull {
                        it.reasonCode == "Measles Lab Information"
                    }

                    childCaseInfoEncounter?.let { kk ->
                        val obs1 = fhirEngine.search<Observation> {
                            filter(
                                Observation.ENCOUNTER, { value = "Encounter/${kk.id}" })
                        }

                        val measlesIgm =
                            obs1.firstOrNull { it.resource.code.codingFirstRep.code == "measles-igm" }?.resource?.value?.asStringValue()
                                ?: ""


                        val finalClassification = when (measlesIgm.lowercase()) {
                            "positive" -> obs1.firstOrNull {
                                it.resource.code.codingFirstRep.code == "final-confirm-classification"
                            }?.resource?.value?.asStringValue() ?: ""

                            "negative" -> obs1.firstOrNull {
                                it.resource.code.codingFirstRep.code == "final-negative-classification"
                            }?.resource?.value?.asStringValue() ?: ""

                            else -> obs1.firstOrNull {
                                it.resource.code.codingFirstRep.code == "final-classification"
                            }?.resource?.value?.asStringValue() ?: ""
                        }

                        item = item.copy(labResults = measlesIgm, status = finalClassification)
                    }

                    // pull all Obs for this Encounter
                    val obs = fhirEngine.search<Observation> {
                        filter(
                            Observation.ENCOUNTER, { value = "Encounter/${it.logicalId}" })
                    }

                    val epid =
                        obs.firstOrNull { it.resource.code.codingFirstRep.code == "EPID" }?.resource?.value?.asStringValue()
                            ?: "still loading"
                    val county =
                        obs.firstOrNull { it.resource.code.codingFirstRep.code == "a4-county" }?.resource?.value?.asStringValue()
                            ?: ""
                    val subCounty =
                        obs.firstOrNull { it.resource.code.codingFirstRep.code == "a3-sub-county" }?.resource?.value?.asStringValue()
                            ?: ""
                    val onset =
                        obs.firstOrNull { it.resource.code.codingFirstRep.code == "728034137219" }?.resource?.value?.asStringValue()
                            ?: ""

                    item = item.copy(
                        encounterId = it.logicalId,
                        epid = epid,
                        subCounty = subCounty,
                        county = county,
                        caseOnsetDate = onset
                    )
                }

                println("Found : None for Now")

            } catch (e: Exception) {
                e.printStackTrace()

                println("Error Loading Page : ${e.message}")
            }
            item
        }.let {
            val sortedCases = it.sortedByDescending { q -> q.lastUpdated }

            patients.addAll(sortedCases)
        }

        return patients
    }

    private suspend fun retrieveRumorCasesByDisease(
        nameQuery: String,
        units: List<String>,
        userRole: UserRole?,
    ): List<RumorItem> {
        return fhirEngine.search<Patient> {
            sort(Patient.GIVEN, Order.ASCENDING)
            count = 500
            from = 0
        }.mapIndexedNotNull { index, fhirPatient ->
            val matchingIdentifier = fhirPatient.resource.identifier.find {
                it.system == nameQuery
            }
            if (matchingIdentifier != null) {
                // Convert the FHIR Patient resource to your PatientItem model
                var data = fhirPatient.resource.toPatientItem(index + 1)
                val tag =
                    fhirPatient.resource.meta.tag.find { it.system.endsWith("/patient-managingLocation") }?.code

                val logicalId = matchingIdentifier.value
                val obs = fhirEngine.search<Observation> {
                    filter(
                        Observation.ENCOUNTER, { value = "Encounter/${logicalId}" })
                }.take(500)
                var mohName =
                    obs.firstOrNull { it.resource.code.codingFirstRep.code == "683805917262" }?.resource?.value?.asStringValue()
                        ?: ""
                val otherCadreName =
                    obs.firstOrNull { it.resource.code.codingFirstRep.code == "223529605110" }?.resource?.value?.asStringValue()
                        ?: ""
                if (mohName.contains("Other")) {
                    mohName = otherCadreName
                }
                var agency =
                    obs.firstOrNull { it.resource.code.codingFirstRep.code == "683805917111" }?.resource?.value?.asStringValue()
                        ?: ""
                var agencyOther =
                    obs.firstOrNull { it.resource.code.codingFirstRep.code == "22311605110" }?.resource?.value?.asStringValue()
                        ?: ""
                if (agency.contains("Other")) {
                    agency = agencyOther
                }

                var response = RumorItem(
                    id = data.id,
                    resourceId = data.resourceId,
                    encounterId = matchingIdentifier.value,
                    mohName = mohName,

                    directorate = agency,
                    division = obs.firstOrNull { it.resource.code.codingFirstRep.code == "686990243396" }?.resource?.value?.asStringValue()
                        ?: "",
                    village = obs.firstOrNull { it.resource.code.codingFirstRep.code == "871818396498" }?.resource?.value?.asStringValue()
                        ?: "",
                    subCounty = obs.firstOrNull { it.resource.code.codingFirstRep.code == "a3-sub-county" }?.resource?.value?.asStringValue()
                        ?: "",
                    county = obs.firstOrNull { it.resource.code.codingFirstRep.code == "a4-county" }?.resource?.value?.asStringValue()
                        ?: "",
                    lastUpdated = data.lastUpdated,
                    sourceTag = "$tag"
                )

                response
            } else {

                null
            }

        }.filter {
            when (userRole) {
                UserRole.ADMINISTRATOR -> {
                    true
                }

                else -> {
                    it.sourceTag in units
                }
            }
        }
            .sortedByDescending { it.lastUpdated }
    }

    fun getAnswerValueAsString(
        item: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>, linkId: String
    ): String {
        val answer = item.flatMap { it.item ?: emptyList() }
            .firstOrNull { it.linkId == linkId }?.answer?.firstOrNull()?.value
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)


        return when (answer) {
            is DateType -> answer.value?.let {
                dateFormat.format(it)
            } ?: "" // returns yyyy-MM-dd
            is DateTimeType -> answer.value?.let { dateFormat.format(it) } ?: ""
            is Reference -> answer.display ?: answer.reference ?: ""
            is StringType -> answer.value ?: ""
            is BooleanType -> answer.value.toString()
            is IntegerType -> answer.value.toString()
            is DecimalType -> answer.value.toString()
            is Coding -> answer.display ?: answer.code ?: ""
            else -> answer?.primitiveValue() ?: ""
        }
    }

    fun extractAnswerValue(answer: QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent?): String {
        return when (val value = answer?.value) {
            is StringType -> value.value ?: ""
            is BooleanType -> value.booleanValue().toString()
            is IntegerType -> value.value?.toString() ?: ""
            is DecimalType -> value.value?.toPlainString() ?: ""
            is DateType -> value.value?.toString() ?: ""
            is DateTimeType -> value.value?.toString() ?: ""
            is Coding -> value.display ?: value.code ?: ""
            is Reference -> value.display ?: value.reference ?: ""
            is UriType -> value.value ?: ""
            is TimeType -> value.value ?: ""
            is Quantity -> "${value.value} ${value.unit}".trim()
            is Enumeration<*> -> value.code ?: ""
            else -> "" // Handle unknown or unsupported types gracefully
        }
    }


    private suspend fun processVlCase(
        fhirEngine: FhirEngine, childEncounters: List<EncounterItem>, data: PatientItem
    ): PatientItem {
        val childCase =
            childEncounters.firstOrNull { it.reasonCode == "VL Laboratory Examination" }
                ?: return data

        val obs1 = fhirEngine.search<Observation> {
            filter(
                Observation.ENCOUNTER, { value = "Encounter/${childCase.id}" })
        }

        val resultsList = listOf(
            obs1.getValue("286501145394"), // rapid
            obs1.getValue("839711142610"), // dat
            obs1.getValue("108406555539"), // aResult
            obs1.getValue("320819009291")  // mResult
        ).map { it.lowercase() }

        val status = obs1.getValue("655245793432").takeUnless { it == "Other (specify)" }
            ?: obs1.getValue("843481153132")

        val results = when {
            resultsList.any { it == "positive" } -> "Positive"
            resultsList.all { it == "negative" } -> "Negative"
            resultsList.all { it == "not done" } -> "Not Done"
            else -> "Pending Results"
        }

        return data.copy(labResults = results, status = status)
    }

    private suspend fun processAfpCase(
        fhirEngine: FhirEngine, childEncounters: List<EncounterItem>, data: PatientItem
    ): PatientItem {
        val childCase =
            childEncounters.firstOrNull { it.reasonCode == "AFP Final Lab Information" }
                ?: return data

        val obs1 = fhirEngine.search<Observation> {
            filter(
                Observation.ENCOUNTER, { value = "Encounter/${childCase.id}" })
        }

        val afp = obs1.getValue("329949474707", "Pending")

        val status = when (afp) {
            "WPV", "cVDPV", "aVDPV", "iVDPV" -> "Confirmed by lab"
            "Discarded" -> "Discarded"
            "Compatible" -> "Compatible"
            else -> "Pending"
        }

        return data.copy(labResults = afp, status = status)
    }

    private suspend fun processMeaslesCase(
        fhirEngine: FhirEngine,
        childEncounters: List<EncounterItem>,
        data: PatientItem,
//        obsMap: Map<String, Observation>
    ): PatientItem {
        val childCase =
            childEncounters.firstOrNull { it.reasonCode == "Measles Lab Information" }
                ?: return data

        val obs1 = fhirEngine.search<Observation> {
            filter(
                Observation.ENCOUNTER, { value = "Encounter/${childCase.id}" })
        }

        val measlesIgm = obs1.getValue("measles-igm", "Pending")
        val maxDays = "yes"// obsMap["308128177300"]?.resource?.value?.asStringValue().orEmpty()

        val classification = when (measlesIgm.lowercase()) {
            "positive" -> if (maxDays.lowercase() == "yes") "Pending" else "Confirmed by lab"
            "negative" -> "Discarded"
            "indeterminate" -> "Compatible/Clinical/Probable"
            else -> "Pending Results"
        }

        return data.copy(labResults = measlesIgm, status = classification)
    }

    /* ----------------------- UTIL ----------------------- */

    private fun List<SearchResult<Observation>>.getValue(
        code: String, default: String = "Pending"
    ): String {
        return this.firstOrNull { it.resource.code.codingFirstRep.code == code }?.resource?.value?.asStringValue()
            ?: default
    }


    private suspend fun batchLoadObservations(encounterIds: Set<String>): Map<String, List<SearchResult<Observation>>> {
        if (encounterIds.isEmpty()) return emptyMap()

        return coroutineScope {
            encounterIds.chunked(50).map { chunk ->
                async {
                    chunk.associateWith { encounterId ->
                        try {
                            fhirEngine.search<Observation> {
                                filter(
                                    Observation.ENCOUNTER,
                                    { value = "Encounter/$encounterId" })
                                count = 100
                            }
                        } catch (e: Exception) {
                            println("Error loading observations for encounter $encounterId: ${e.message}")
                            emptyList()
                        }
                    }
                }
            }.awaitAll().fold(mutableMapOf()) { acc, map ->
                acc.putAll(map)
                acc
            }
        }
    }

    private suspend fun processPatientItem(
        patient: Patient,
        index: Int,
        logicalId: String,
        system: String,
        nameQuery: String,
        isSummary: Boolean,
        observations: List<SearchResult<Observation>>
    ): PatientItem? {
        // Convert patient to PatientItem
        var data = patient.toPatientItem(index)

        // Extract EPID
        val epidIdentifier = patient.identifier.find { it.type.codingFirstRep.code == "EPID" }
        val epid = epidIdentifier?.value ?: findObservationValue(observations, "EPID")

        // Extract location data
        val county = if (patient.hasAddress() && patient.addressFirstRep.hasCity()) {
            patient.addressFirstRep.city
        } else {
            findObservationValue(observations, "a4-county")
        }

        val subCounty = if (patient.hasAddress() && patient.addressFirstRep.hasState()) {
            patient.addressFirstRep.state
        } else {
            findObservationValue(observations, "a3-sub-county")
        }

        // Extract other observation values
        val onset = findObservationValue(observations, "728034137219")
        val caseList = findObservationValue(observations, "865158268604") ?: "Case"
        val campaignDay = findObservationValue(observations, "campaign_day")
        val teamNumber = findObservationValue(observations, "team_no")
        val supervisorName = findObservationValue(observations, "supervisor_name")
        val occupation = findObservationValue(observations, "occupation")
        val vaccinationCenter = findObservationValue(observations, "vaccination_center")

        println("Current Workflow :::: Campaign Day : $campaignDay")

        // Process lab results based on case type
        data = processLabResults(data, nameQuery, logicalId)

        // Update data with all extracted values
        return data.copy(
            vaccinationCenter = vaccinationCenter ?: "",
            occupation = occupation ?: "",
            caseList = caseList,
            encounterId = logicalId,
            epid = epid ?: "",
            county = county ?: "",
            subCounty = subCounty ?: "",
            caseOnsetDate = onset ?: "",
            encounterQuestionnaire = system,
            isSummary = isSummary,
            campaignDate = campaignDay ?: "",
            teamNumber = teamNumber ?: "",
            supervisorName = supervisorName ?: ""
        )
    }

    private suspend fun processLabResults(
        data: PatientItem, nameQuery: String, patientId: String
    ): PatientItem {
        return when (nameQuery) {
            "moh-505-reporting-form" -> {
                // Add specific processing for MOH 505 if needed
                data
            }

            "vl-case-information" -> {
                processVLLabResults(data, patientId)
            }

            "afp-case-information" -> {
                processAFPLabResults(data, patientId)
            }

            else -> {
                processMeaslesLabResults(data, patientId)
            }
        }
    }

    private suspend fun processVLLabResults(data: PatientItem, patientId: String): PatientItem {
        return try {
            val childEncounter = loadChildEncounter(data.resourceId, patientId)
            val vlEncounter = childEncounter.firstOrNull { encounter ->
                // Adapt this based on your actual ChildEncounter structure
                getEncounterReasonCode(encounter) == "VL Laboratory Examination"
            }

            if (vlEncounter != null) {
                val encounterId = getEncounterId(vlEncounter)
                val obs = fhirEngine.search<Observation> {
                    filter(Observation.ENCOUNTER, { value = "Encounter/$encounterId" })
                }

                val rapidResults = findObservationValue(obs, "286501145394") ?: "Pending"
                val datResult = findObservationValue(obs, "839711142610") ?: "Pending"
                val aResult = findObservationValue(obs, "108406555539") ?: "Pending"
                val mResult = findObservationValue(obs, "320819009291") ?: "Pending"
                var status = findObservationValue(obs, "655245793432") ?: "Pending"
                val otherStatus = findObservationValue(obs, "843481153132") ?: "Pending"

                if (status == "Other (specify)") {
                    status = otherStatus
                }

                val allResults =
                    listOf(rapidResults, datResult, aResult, mResult).map { it.lowercase() }
                val results = when {
                    allResults.any { it == "positive" } -> "Positive"
                    allResults.all { it == "negative" } -> "Negative"
                    allResults.all { it == "not done" } -> "Not Done"
                    else -> "Pending Results"
                }

                data.copy(labResults = results, status = status)
            } else {
                data
            }
        } catch (e: Exception) {
            println("Error processing VL lab results: ${e.message}")
            data
        }
    }

    private suspend fun processAFPLabResults(
        data: PatientItem,
        patientId: String
    ): PatientItem {
        return try {
            val childEncounter = loadChildEncounter(data.resourceId, patientId)
            val afpEncounter = childEncounter.firstOrNull { encounter ->
                getEncounterReasonCode(encounter) == "AFP Final Lab Information"
            }

            if (afpEncounter != null) {
                val encounterId = getEncounterId(afpEncounter)
                val obs = fhirEngine.search<Observation> {
                    filter(Observation.ENCOUNTER, { value = "Encounter/$encounterId" })
                }

                val afp = findObservationValue(obs, "329949474707") ?: "Pending"
                val status = when (afp) {
                    "WPV", "cVDPV", "aVDPV", "iVDPV" -> "Confirmed by lab"
                    "Discarded" -> "Discarded"
                    "Compatible" -> "Compatible"
                    else -> "Pending"
                }

                data.copy(labResults = afp, status = status)
            } else {
                data
            }
        } catch (e: Exception) {
            println("Error processing AFP lab results: ${e.message}")
            data
        }
    }

    private suspend fun processMeaslesLabResults(
        data: PatientItem, patientId: String
    ): PatientItem {
        return try {
            val childEncounter = loadChildEncounter(data.resourceId, patientId)
            val measlesEncounter = childEncounter.firstOrNull { encounter ->
                getEncounterReasonCode(encounter) == "Measles Lab Information"
            }

            if (measlesEncounter != null) {
                val encounterId = getEncounterId(measlesEncounter)
                val obs = fhirEngine.search<Observation> {
                    filter(Observation.ENCOUNTER, { value = "Encounter/$encounterId" })
                }

                val measlesIgm = findObservationValue(obs, "measles-igm") ?: "Pending"

                // Get maxDays from main observations (this was from the original outer obs search)
                val maxDays =
                    "" // You may need to pass the main observations here or load separately

                val finalClassification = when (measlesIgm.lowercase()) {
                    "positive" -> if (maxDays.lowercase() == "yes") "Pending" else "Confirmed by lab"
                    "negative" -> "Discarded"
                    "indeterminate" -> "Compatible/Clinical/Probable"
                    else -> "Pending Results"
                }

                data.copy(labResults = measlesIgm, status = finalClassification)
            } else {
                data
            }
        } catch (e: Exception) {
            println("Error processing Measles lab results: ${e.message}")
            data
        }
    }

    // Helper functions to work with your existing loadChildEncounter return type
// You'll need to implement these based on your actual ChildEncounter structure
    private fun getEncounterReasonCode(encounter: Any): String {
        // Implement based on your actual encounter object structure
        // For example, if it's a map: (encounter as? Map<*, *>)?.get("reasonCode")?.toString() ?: ""
        // Or if it's a data class: encounter.reasonCode
        return when (encounter) {
            is Map<*, *> -> encounter["reasonCode"]?.toString() ?: ""
            // Add other cases based on your actual type
            else -> ""
        }
    }

    private fun getEncounterId(encounter: Any): String {
        // Implement based on your actual encounter object structure
        return when (encounter) {
            is Map<*, *> -> encounter["id"]?.toString() ?: ""
            // Add other cases based on your actual type
            else -> ""
        }
    }

    private fun findMatchingIdentifier(patient: Patient, nameQuery: String): Identifier? {
        return when (nameQuery) {
            "rcce" -> patient.identifier.find {
                it.system == "rcce-community-questionnaire" || it.system == "rcce-countysubcounty-interface"
            }

            else -> patient.identifier.find { it.system == nameQuery }
        }
    }

    private fun findObservationValue(
        observations: List<SearchResult<Observation>>, code: String
    ): String? {
        return observations.firstOrNull { it.resource.code.codingFirstRep.code == code }?.resource?.value?.asStringValue()
    }

    private fun formatAuthoredDate(authored: Date?, formatter: DateTimeFormatter): String {
        return try {
            authored?.let {
                val localDate = it.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime()
                localDate.format(formatter)
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun formatAuthoredDateAsDate(authored: Date?): String {
        return try {
            authored?.let {
                val localDate = it.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
                localDate.toString()
            } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    data class RumorItem(
        val id: String,
        val resourceId: String,
        val encounterId: String,
        val mohName: String,
        val directorate: String,
        val division: String,
        val village: String,
        val subCounty: String,
        val county: String,
        val lastUpdated: String,
        val sourceTag: String
    )

    /** The Patient's details for display purposes. */
    data class PatientItem(
        val id: String,
        val resourceId: String,
        val encounterId: String,
        val name: String,
        val gender: String,
        val dob: LocalDate? = null,
        val phone: String,
        val city: String,
        val country: String,
        val isActive: Boolean,
        val epid: String,
        val county: String,
        val subCounty: String,
        val caseOnsetDate: String,
        val status: String = "Pending Results",
        val labResults: String = "Pending",
        val lastUpdated: String,
        val caseList: String = "Case",
        val vaccinated: String = "No",
        val encounterQuestionnaire: String = "",
        val isSummary: Boolean = false,
        val campaignDate: String = "",
        val teamNumber: String = "",
        val supervisorName: String = "",
        val vaccinationCenter: String = "",
        val occupation: String = "",
        val syncStatus: String = "Pending",
        val sourceTag: String
    ) {
        override fun toString(): String = name
    }

    /** The Observation's details for display purposes. */
    data class ObservationItem(
        val id: String, val code: String, val value: String, val created: String
    ) {
        override fun toString(): String = code
    }

    data class CaseDiseaseData(
        val logicalId: String, val name: String, val fever: String = "", val rash: String = ""
    )

    data class LabResults(
        val encounterId: String,
        val observations: List<ObservationItem> = emptyList<ObservationItem>()
    )

    data class ContactResults(
        val parentIdId: String,
        val childId: String,
        val name: String,
        var epid: String,
        val observations: List<ObservationItem> = emptyList<ObservationItem>()
    )

    data class CaseLabResultsData(
        val logicalId: String,
        val reasonCode: String,
        val dateSpecimenReceived: String = "",
        val specimenCondition: String = "",
        val measlesIgM: String = "",
        val rubellaIgM: String = "",
        val dateLabSentResults: String = "",
        val finalClassification: String = "",
        val subcountyName: String = "",
        val subcountyDesignation: String = "",
        val subcountyPhone: String = "",
        val subcountyEmail: String = "",
        val formCompletedBy: String = "",
        val nameOfPersonCompletingForm: String = "",
        val designation: String = "",
        val sign: String = ""
    )

    interface PatientDetailData {
        val firstInGroup: Boolean
        val lastInGroup: Boolean
    }

    data class CaseId(
        val patientId: String,
        val eNo: String,
    )

    data class CaseDetailSummaryData(
        val name: String,
        val sex: String,
        val dob: String,
        val logicalId: String,
        val encounterId: String,
        val observations: List<ObservationItem> = emptyList<ObservationItem>(),
        val epidNo: String
    )

    data class ClinicalData(
        val onset: String,
        val symptoms: List<String> = emptyList<String>(),
        val rashDate: String,
        val rashType: String,
        val otherType: String,
        val vaccinated: String,
        val doses: String,
        val thirtyDays: String,
        val lastVaccination: String,
        val homeVisit: String,
        val homeDateVisit: String,
        val caseEpilinked: String,
        val epiName: String,
        val epiEPID: String,
    )

    data class PersonDetails(
        val name: String,
        val sex: String,
        val dob: String,
        val residence: String,
        val parent: String,
        val houseNo: String,
        val neighbour: String,
        val street: String,
        val town: String,
        val subCountyName: String,
        val countyName: String,
        val parentPhone: String
    )


    data class CaseDetailData(
        val logicalId: String,
        val name: String,
        val sex: String,
        val dob: String,
        val epid: String,
        val subCounty: String,
        val county: String,
        val country: String,
        val yearOfReporting: String,
        val healthFacility: String,
        val typeOfHealthFacility: String,
        val subcountyOfFacility: String,
        val countyOfFacility: String,

        val onset: String,
        val residence: String,
        val facility: String,
        val type: String,
        val disease: String,
        val parent: String,
        val houseNo: String,
        val neighbour: String,
        val street: String,
        val town: String,
        val subCountyName: String,
        val countyName: String,
        val parentPhone: String,
        val dateFirstSeen: String,
        val dateSubCountyNotified: String,
        val hospitalized: String,
        val admissionDate: String,
        val ipNo: String,
        val diagnosis: String,
        val diagnosisMeans: String,
        val diagnosisMeansOther: String,
        val targetDisease: String,
        val wasPatientVaccinated: String,
        val noOfDoses: String,
        val twoMonthsVaccination: String,
        val patientStatus: String,
        val vaccineDate: String,
        // Case Details
        val clinicalSymptoms: String,
        val rashDate: String,
        val rashType: String,
        val patientVaccinated: String,
        val patientDoses: String,
        val vaccineDateThirtyDays: String,
        val lastDoseDate: String,
        val homeVisited: String,
        val homeVisitedDate: String,
        val epiLinked: String,

        // Clinical

        val patientOutcome: String,
        val sampleCollected: String,
        val inPatientOutPatient: String,

        //    Lab Information
        val specimen: String,
        val noWhy: String,
        val collectionDate: String,
        val specimenType: String,
        val specimenTypeOther: String,
        val dateSent: String,
        val labName: String,
        val bloodSpecimenCollected: String,
        val noWhyBlood: String,
        val dateBloodSpecimen: String,
        val urineSpecimenCollected: String,
        val noWhyUrine: String,
        val dateUrineSpecimen: String,
        val respiratorySampleCollected: String,
        val dateRespiratorySample: String,
        val noWhyRespiratory: String,
        val otherSpecimenCollected: String,
        val specifyOtherSpecimen: String,
        val dateOtherSpecimen: String,
        val dateSpecimenSentToLab: String

    )

    data class PatientDetailOverview(
        val patient: PatientItem,
        override val firstInGroup: Boolean = false,
        override val lastInGroup: Boolean = false,
    ) : PatientDetailData

    data class EncounterItem(
        val id: String,
        val reasonCode: String,
        val status: String = "",
        val lastUpdated: String = "",
    ) {
        override fun toString(): String = reasonCode
    }

    data class ConditionItem(
        val id: String,
        val code: String,
        val effective: String,
        val value: String,
    ) {
        override fun toString(): String = code
    }

    class PatientListViewModelFactory(
        private val application: Application,
        private val fhirEngine: FhirEngine,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PatientListViewModel::class.java)) {
                return PatientListViewModel(application, fhirEngine) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }

    private var patientGivenName: String? = null
    private var patientFamilyName: String? = null

    fun setPatientGivenName(givenName: String) {
        patientGivenName = givenName
        searchPatientsByParameter()
    }

    fun setPatientFamilyName(familyName: String) {
        patientFamilyName = familyName
        searchPatientsByParameter()
    }

    private fun searchPatientsByParameter() {
        viewModelScope.launch {
            liveSearchedPatients.value = searchPatients()
            patientCount.value = searchedPatientCount()
        }
    }

    private suspend fun searchPatients(): List<PatientItem> {
        val patients = fhirEngine.search<Patient> {
            filter(
                Patient.GIVEN,
                {
                    modifier = StringFilterModifier.CONTAINS
                    this.value = patientGivenName ?: ""
                },
            )
            filter(
                Patient.FAMILY,
                {
                    modifier = StringFilterModifier.CONTAINS
                    this.value = patientFamilyName ?: ""
                },
            )
            sort(Patient.GIVEN, Order.ASCENDING)
            count = 100
            from = 0
        }.mapIndexed { index, fhirPatient ->

            val item = fhirPatient.resource.toPatientItem(index + 1)
            try {
                val encounter = loadEncounter(item.resourceId)

            } catch (e: Exception) {
                e.printStackTrace()
                println("Error Loading Patient data ${e.message}")
            }
            item
        }.toMutableList()

        return patients
    }

    private suspend fun loadEncounter(patientId: String): List<Encounter> {
        return fhirEngine.search<Encounter> {
            filter(
                Encounter.SUBJECT, { value = "Patient/$patientId" })
        }.map { it.resource }
    }

    private suspend fun loadChildEncounter(
        patientId: String, encounterId: String
    ): List<EncounterItem> {

        val patients: MutableList<EncounterItem> = mutableListOf()
        fhirEngine.search<Encounter> {
            filter(Encounter.SUBJECT, { value = "Patient/$patientId" })
            filter(Encounter.PART_OF, { value = "Encounter/$encounterId" })

        }.map {
            var data = EncounterItem(
                id = it.resource.logicalId,
                reasonCode = it.resource.reasonCodeFirstRep.codingFirstRep.code
            )
            var lastUpdated = ""
            try {
                if (it.resource.hasIdentifier()) {
                    val id = it.resource.identifier.find { it.system == "system-creation" }
                    if (id != null) {
                        lastUpdated = id.value
                    }
                } else {
                    lastUpdated = ""
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            data = data.copy(
                lastUpdated = lastUpdated
            )
            data

        }.let {
            val sortedCases = it.sortedByDescending { q -> q.lastUpdated }

            patients.addAll(sortedCases)
        }

        return patients

    }

    private suspend fun searchedPatientCount(): Long {
        return fhirEngine.count<Patient> {
            filter(
                Patient.GIVEN,
                {
                    modifier = StringFilterModifier.CONTAINS
                    this.value = patientGivenName ?: ""
                },
            )
            filter(
                Patient.FAMILY,
                {
                    modifier = StringFilterModifier.CONTAINS
                    this.value = patientFamilyName ?: ""
                },
            )
        }
    }
}

internal fun Patient.toPatientItem(
    position: Int,
): PatientListViewModel.PatientItem {
    // Show nothing if no values available for gender and date of birth.

    val tag =
        if (hasMeta()) if (meta.hasTag()) meta.tag.find { it.system.endsWith("/patient-managingLocation") }?.code else "" else ""
    val patientId = if (hasIdElement()) idElement.idPart else ""
    val name = if (hasName()) name[0].nameAsSingleString else ""
    val gender = if (hasGenderElement()) genderElement.valueAsString else ""
    val dob = if (hasBirthDateElement()) {
        birthDateElement.value.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
    } else {
        null
    }
    val phone = if (hasTelecom()) telecom[0].value else ""
    val city = if (hasAddress()) address[0].city else ""
    val country = if (hasAddress()) address[0].country else ""
    val isActive = active
    var epid = ""
    var county = ""
    var subCounty = ""
    var caseOnsetDate = ""


    var lastUpdated = ""
    if (hasIdentifier()) {
        val id = identifier.find { it.system == "system-creation" }
        if (id != null) {
            lastUpdated = id.value
        }
    } else {
        lastUpdated = ""
    }


    return PatientListViewModel.PatientItem(
        id = position.toString(),
        encounterId = "encounterId",
        resourceId = patientId,
        name = " $name",
        gender = gender ?: "",
        dob = dob,
        phone = phone ?: "",
        city = city ?: "",
        country = country ?: "",
        isActive = isActive,
        epid = epid,
        county = county,
        subCounty = subCounty,
        caseOnsetDate = caseOnsetDate,
        lastUpdated = lastUpdated,
        sourceTag = "$tag"
    )
}