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
import com.google.android.fhir.get
import com.google.android.fhir.search.Order
import com.google.android.fhir.search.StringFilterModifier
import com.google.android.fhir.search.count
import com.google.android.fhir.search.filter.ReferenceParamFilterCriterion
import com.google.android.fhir.search.filter.TokenParamFilterCriterion
import com.google.android.fhir.search.revInclude
import com.google.android.fhir.search.search
import com.icl.surveillance.fhir.forms.CaseSlugs
import com.icl.surveillance.fhir.forms.CaseTypes
import com.icl.surveillance.fhir.forms.FhirSystems
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.fhir.forms.toCaseSlug
import com.icl.surveillance.fhir.forms.valueOf
import com.icl.surveillance.models.QuestionnaireAnswer
import com.icl.surveillance.models.UserRole
import com.icl.surveillance.network.RetrofitCallsAuthentication
import com.icl.surveillance.utils.FormatterClass
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.String
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import timber.log.Timber

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
    private var hasMore = true
    private var isLoading = false

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
                        wrapper.resource.meta.tag.find { it.system.endsWith("/patient" + FhirSystems.MANAGING_LOCATION_SUFFIX) }?.code

                    if (matchingIdentifier != null) {

                        val obs =
                            wrapper.revIncluded?.get(ResourceType.Observation to Observation.SUBJECT.paramName) as? List<Observation>
                                ?: emptyList()
                        val epid =
                            obs.valueOf(FhirSystems.EPID)

                        val county =
                            obs.valueOf(FormFields.Residence.COUNTY)
                        val subCounty =
                            obs.valueOf(FormFields.Residence.SUB_COUNTY)
                        val onset =
                            obs.valueOf(FormFields.EVENT_DATE)
                        val caseList =
                            obs.valueOf(FormFields.Measles.CASE_OR_LINE_LIST, default = "Case")


                        val campaignDay =
                            obs.valueOf(FormFields.MpoxTallySheet.CAMPAIGN_DAY)
                        val teamNumber =
                            obs.valueOf(FormFields.MpoxTallySheet.TEAM_NUMBER)
                        val supervisorName =
                            obs.valueOf(FormFields.MpoxTallySheet.SUPERVISOR_NAME)
                        var occupation =
                            obs.valueOf(FormFields.Rcce.OCCUPATION)
                        val occupationOther =
                            obs.valueOf(FormFields.Rcce.OCCUPATION_OTHER)

                        if (occupation == "Other") {
                            occupation = occupationOther
                        }
                        val vaccinationCenter =
                            obs.valueOf(FormFields.MpoxRegister.VACCINATION_CENTER)
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
                    // National roles see everything; others only their facilities ("units")
                    if (userRole?.isNational == true) true else it.sourceTag in units


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

    private var rumorJob: Job? = null

    /**
     * Loads every rumor report, page by page, publishing the growing list after each page so the
     * screen fills in quickly. Replaces the old lookup that only scanned the first 500 Patients of
     * all modules (so rumors beyond that were never listed) and ran one Observation query per row.
     */
    fun handleCurrentRumorCaseListing(category: String, units: List<String>, userRole: UserRole?) {
        rumorJob?.cancel()
        rumorJob = viewModelScope.launch {
            val loaded = loadRumorCasesPaged(units, userRole) { liveRumorCases.value = it }
            if (loaded.isEmpty()) {
                // Records saved before encounters carried the case name: old lookup.
                liveRumorCases.value = retrieveRumorCasesByDisease(category, units, userRole)
            }
        }
    }

    private suspend fun loadRumorCasesPaged(
        units: List<String>,
        userRole: UserRole?,
        onProgress: (List<RumorItem>) -> Unit,
    ): List<RumorItem> {
        val all = ArrayList<RumorItem>()
        var offset = 0
        while (true) {
            val page = withContext(Dispatchers.IO) {
                fhirEngine.search<Encounter> {
                    filter(Encounter.REASON_CODE, { value = of(CaseTypes.RUMOR) })
                    count = RUMOR_PAGE_SIZE
                    from = offset
                }
            }.map { it.resource }
            if (page.isEmpty()) break
            offset += page.size

            val visible = withContext(Dispatchers.IO) {
                page.filter { userRole?.isNational == true || jurisdictionTag(it) in units }
            }
            if (visible.isNotEmpty()) {
                // One query for the listed answers of the whole page (instead of one per row).
                val references = visible.map { "Encounter/${it.logicalId}" }
                val observations = withContext(Dispatchers.IO) {
                    fhirEngine.search<Observation> {
                        filter(
                            Observation.ENCOUNTER,
                            *references.map<String, ReferenceParamFilterCriterion.() -> Unit> { ref ->
                                { value = ref }
                            }.toTypedArray()
                        )
                        filter(
                            Observation.CODE,
                            *FormFields.Rumor.LIST_FIELDS.map<String, TokenParamFilterCriterion.() -> Unit> { code ->
                                { value = of(code) }
                            }.toTypedArray()
                        )
                    }
                }.map { it.resource }.groupBy { it.encounter.referenceElement.idPart }
                visible.forEach { encounter ->
                    all.add(encounter.toRumorItem(observations[encounter.logicalId].orEmpty()))
                }
            }
            onProgress(all.sortedByDescending { it.lastUpdated })
            if (page.size < RUMOR_PAGE_SIZE) break
        }
        return all
    }

    /** Facility the record belongs to; older records may only have it on the Patient. */
    private suspend fun jurisdictionTag(encounter: Encounter): String? {
        encounter.meta.tag.firstOrNull { it.system?.endsWith(FhirSystems.MANAGING_LOCATION_SUFFIX) == true }
            ?.code?.let { return it }
        val patientId = encounter.subject?.referenceElement?.idPart ?: return null
        val patient = runCatching { fhirEngine.get<Patient>(patientId) }.getOrNull() ?: return null
        return patient.meta.tag.firstOrNull { it.system?.endsWith("/patient" + FhirSystems.MANAGING_LOCATION_SUFFIX) == true }?.code
    }

    private fun Encounter.toRumorItem(observations: List<Observation>): RumorItem {
        var cadre = observations.valueOf(FormFields.Rumor.REPORTING_CADRE)
        if (cadre.contains("Other", ignoreCase = true)) {
            cadre = observations.valueOf(FormFields.Rumor.REPORTING_CADRE_OTHER).ifBlank { cadre }
        }
        var agency = observations.valueOf(FormFields.Rumor.AGENCY)
        if (agency.contains("Other", ignoreCase = true)) {
            agency = observations.valueOf(FormFields.Rumor.AGENCY_OTHER).ifBlank { agency }
        }
        val created = identifier.firstOrNull { it.system == FhirSystems.SYSTEM_CREATION }?.value ?: ""

        return RumorItem(
            id = logicalId,
            resourceId = subject?.referenceElement?.idPart ?: "",
            encounterId = logicalId,
            mohName = cadre,
            directorate = agency,
            // Shown as "Date Reported" (the form has no division question).
            division = created.substringBefore(" "),
            village = observations.valueOf(FormFields.Rumor.VILLAGE),
            subCounty = observations.valueOf(*FormFields.ReportingSite.SUB_COUNTY_VARIANTS.toTypedArray()),
            county = observations.valueOf(*FormFields.ReportingSite.COUNTY_VARIANTS.toTypedArray()),
            lastUpdated = created,
            sourceTag = jurisdictionTagOrEmpty(),
        )
    }

    private fun Encounter.jurisdictionTagOrEmpty() =
        meta.tag.firstOrNull { it.system?.endsWith(FhirSystems.MANAGING_LOCATION_SUFFIX) == true }?.code ?: ""


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

        val county = getAnswerValueAsString(response.item, FormFields.ReportingSite.COUNTY)
        val subCounty = getAnswerValueAsString(response.item, FormFields.ReportingSite.SUB_COUNTY)
        var caseOnsetDate = getAnswerValueAsString(response.item, FormFields.EVENT_DATE)

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
            name = response.item.firstOrNull()?.item?.firstOrNull { it.linkId == FormFields.ReportingSite.COUNTY }?.answer?.firstOrNull()?.valueReference?.display
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
            CaseSlugs.RCCE -> patient.identifier.find {
                it.system == CaseSlugs.RCCE_COMMUNITY || it.system == CaseSlugs.RCCE_COUNTY
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
            CaseSlugs.VL -> enrichLabResultsForVL(childEncounter, data)
            CaseSlugs.AFP -> enrichLabResultsForAFP(childEncounter, data)
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
            obsList.valueOf("2437874573")

        // Extract Rubella result with empty fallback
        val rubella =
            obsList.valueOf("2636544254")

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
        return processAfpCase(fhirEngine, childEncounters, data)
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
            obsList.valueOf(FormFields.VlLab.RAPID_TEST_RESULT)

        val datResult =
            obsList.valueOf(FormFields.VlLab.DAT_RESULT)

        val aResult =
            obsList.valueOf(FormFields.VlLab.ASPIRATE_RESULT)

        val mResult =
            obsList.valueOf(FormFields.VlLab.MICROSCOPY_RESULT)

        var status =
            obsList.valueOf(FormFields.VlLab.FINAL_DIAGNOSIS)

        val otherStatus =
            obsList.valueOf(FormFields.VlLab.FINAL_DIAGNOSIS_OTHER)

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
            CaseSlugs.MPOX_TALLY_SHEET -> {
                val questionnaireData: MutableList<PatientItem> = mutableListOf()
                fhirEngine.search<MeasureReport> {

                    sort(MeasureReport.DATE, Order.DESCENDING)
                }
                    .mapIndexedNotNull { index, data ->
                        val tag =
                            data.resource.meta.tag.find { it.system.endsWith("/measure" + FhirSystems.MANAGING_LOCATION_SUFFIX) }?.code

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
                                                it.resource.identifier.find { id -> id.system == CaseSlugs.MPOX_TALLY_SHEET }
                                            if (enco != null) {
                                                enco.value
                                            } else ""
                                        } else ""
                                        val observations =
                                            it.revIncluded?.get(ResourceType.Observation to Observation.SUBJECT.paramName) as? List<Observation>
                                                ?: emptyList()

                                        // Create team_numberr
                                        val teamNumber =
                                            observations.valueOf(FormFields.MpoxTallySheet.TEAM_NUMBER)
                                        // supervisor name
                                        val supervisorName =
                                            observations.valueOf(FormFields.MpoxTallySheet.SUPERVISOR_NAME)
                                        //County
                                        val county =
                                            observations.valueOf(FormFields.ReportingSite.COUNTY)
                                        // SubCounty
                                        val subCounty =
                                            observations.valueOf(FormFields.ReportingSite.SUB_COUNTY)
                                        // Campaign Day
                                        val campaignDay =
                                            observations.valueOf(FormFields.MpoxTallySheet.CAMPAIGN_DAY)

                                        val formatted =
                                            observations.valueOf(FormFields.EVENT_DATE)

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

            CaseSlugs.MPOX_REGISTER -> {

                val questionnaireData: MutableList<PatientItem> = mutableListOf()
                fhirEngine.search<Patient> {
                    filter(Patient.ACTIVE, { value = of(true) })
                    sort(Patient.GIVEN, Order.ASCENDING)
                    revInclude<Observation>(Observation.SUBJECT)
                }.mapIndexedNotNull { index, fhirPatient ->
                    val tag =
                        fhirPatient.resource.meta.tag.find { it.system.endsWith("/patient" + FhirSystems.MANAGING_LOCATION_SUFFIX) }?.code

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

            CaseSlugs.MPOX_SUPERVISOR_CHECKLIST -> {
                var county = ""
                var subCounty = ""
                val questionnaireData: MutableList<PatientItem> = mutableListOf()
                fhirEngine.search<QuestionnaireResponse> {
                    filter(
                        QuestionnaireResponse.STATUS,
                        { value = of(QuestionnaireResponse.QuestionnaireResponseStatus.INPROGRESS.toCode()) }
                    )
                    sort(QuestionnaireResponse.AUTHORED, Order.DESCENDING)

                }
                    .mapIndexedNotNull { index, fhirPatient ->

                        val tag =
                            fhirPatient.resource.meta.tag.find { it.system.endsWith("/questionnaire-managingLocation") }?.code

                        if (fhirPatient.resource.hasIdentifier()) {

                            val countyLinkIds = FormFields.ReportingSite.COUNTY_VARIANTS
                            val subCountyLinkIds = FormFields.ReportingSite.SUB_COUNTY_VARIANTS
                            val extractedAnswers = extractStructuredAnswers(fhirPatient.resource)

                            county = findFirstAnswer(
                                extractedAnswers,
                                countyLinkIds
                            ).ifBlank { county }
                            subCounty = findFirstAnswer(
                                extractedAnswers,
                                subCountyLinkIds
                            ).ifBlank { subCounty }

                            var caseOnsetDate =
                                getAnswerValueAsString(fhirPatient.resource.item, FormFields.EVENT_DATE)

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
                                name = fhirPatient.resource.item.firstOrNull()?.item?.firstOrNull() { it.linkId == FormFields.ReportingSite.COUNTY }?.answer?.firstOrNull()?.valueReference?.display
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
                // Fast path: only this module's patients, with their answers loaded in batches.
                // Falls back to scanning every patient when the case type is not known.
                val batches = caseBatchLoader(nameQuery)
                val patients = batches?.patients ?: fhirEngine.search<Patient> {
                    filter(Patient.ACTIVE, { value = of(true) })
                    sort(Patient.GIVEN, Order.ASCENDING)
                }
                return patients.mapIndexedNotNull { index, fhirPatient ->
                    batches?.ensureLoaded(index)

                    val tag =
                        fhirPatient.resource.meta.tag.find { it.system.endsWith("/patient" + FhirSystems.MANAGING_LOCATION_SUFFIX) }?.code

                    val matchingIdentifier = when (nameQuery) {
                        CaseSlugs.RCCE -> fhirPatient.resource.identifier.find {
                            it.system == CaseSlugs.RCCE_COMMUNITY || it.system == CaseSlugs.RCCE_COUNTY
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
                        val obs = batches?.observationsFor(logicalId)?.take(500)
                            ?: fhirEngine.search<Observation> {
                                filter(
                                    Observation.ENCOUNTER, { value = "Encounter/${logicalId}" })
                            }.take(500)

                        val epid =
                            if (epidIdenfifier != null) epidIdenfifier.value else obs.valueOf(FhirSystems.EPID)

                        var county =
                            if (fhirPatient.resource.hasAddress()) if (fhirPatient.resource.addressFirstRep.hasCity()) fhirPatient.resource.addressFirstRep.city else "" else obs.valueOf(FormFields.Residence.COUNTY)
                        var subCounty =
                            if (fhirPatient.resource.hasAddress()) if (fhirPatient.resource.addressFirstRep.hasState()) fhirPatient.resource.addressFirstRep.state else "" else obs.valueOf(FormFields.Residence.SUB_COUNTY)
                        val onset =
                            obs.valueOf(FormFields.EVENT_DATE)
                        val caseList =
                            obs.valueOf(FormFields.Measles.CASE_OR_LINE_LIST, default = "Case")

                        val campaignDay =
                            obs.valueOf(FormFields.MpoxTallySheet.CAMPAIGN_DAY)
                        val teamNumber =
                            obs.valueOf(FormFields.MpoxTallySheet.TEAM_NUMBER)
                        val supervisorName =
                            obs.valueOf(FormFields.MpoxTallySheet.SUPERVISOR_NAME)
                        var occupation =
                            obs.valueOf(FormFields.Rcce.OCCUPATION)
                        val occupationOther =
                            obs.valueOf(FormFields.Rcce.OCCUPATION_OTHER)

                        if (occupation == "Other") {
                            occupation = occupationOther
                        }
                        val vaccinationCenter =
                            obs.valueOf(FormFields.MpoxRegister.VACCINATION_CENTER)


                        val childEncounter = batches?.childEncountersFor(logicalId)
                            ?: loadChildEncounter(data.resourceId, logicalId)

                        when (nameQuery) {
                            CaseSlugs.RCCE -> {
                                val res = batches?.responsesFor(data.resourceId)?.take(5)
                                    ?: fhirEngine.search<QuestionnaireResponse> {
                                        filter(
                                            QuestionnaireResponse.SUBJECT,
                                            { value = "Patient/${data.resourceId}" })
                                    }.take(5)

                                if (res.isNotEmpty()) {
                                    val response = res.first().resource
                                    val extractedAnswers = extractStructuredAnswers(response)
                                    val countyLinkIds = FormFields.ReportingSite.COUNTY_VARIANTS
                                    val subCountyLinkIds = FormFields.ReportingSite.SUB_COUNTY_VARIANTS
                                    val wardLinkIds = FormFields.ReportingSite.WARD_VARIANTS
                                    val reportingSiteLinkIds = FormFields.ReportingSite.FACILITY_VARIANTS
                                    val facilityTypeLinkIds = FormFields.ReportingSite.FACILITY_TYPE_VARIANTS

                                    county = findFirstAnswer(
                                        extractedAnswers,
                                        countyLinkIds
                                    ).ifBlank { county }
                                    subCounty = findFirstAnswer(
                                        extractedAnswers,
                                        subCountyLinkIds
                                    ).ifBlank { subCounty }

                                    var socialOccupation =
                                        findFirstAnswer(extractedAnswers, listOf("occupation"))
                                            .ifBlank { occupation }
                                    if (socialOccupation.equals("Other", ignoreCase = true) &&
                                        occupation.isNotBlank()
                                    ) {
                                        socialOccupation = occupation
                                    }

                                    val respondentSex =
                                        findFirstAnswer(extractedAnswers, listOf(FormFields.Person.SEX))
                                    val respondentAge =
                                        findFirstAnswer(extractedAnswers, listOf("age"))
                                    val village =
                                        findFirstAnswer(extractedAnswers, listOf("village"))
                                    val ward =
                                        findFirstAnswer(extractedAnswers, wardLinkIds)
                                    val reportingSite =
                                        findFirstAnswer(extractedAnswers, reportingSiteLinkIds)
                                    val facilityType =
                                        findFirstAnswer(extractedAnswers, facilityTypeLinkIds)

                                    val authoredFormatter =
                                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                                    val authoredDateTime =
                                        formatAuthoredDate(response.authored, authoredFormatter)
                                    val authoredDate =
                                        formatAuthoredDateAsDate(response.authored)

                                    data = data.copy(
                                        gender = respondentSex.ifBlank { data.gender },
                                        occupation = socialOccupation,
                                        respondentAge = respondentAge,
                                        village = village,
                                        ward = ward,
                                        reportingSite = reportingSite,
                                        facilityType = facilityType,
                                        lastUpdated = authoredDateTime.ifBlank { data.lastUpdated },
                                        caseOnsetDate = authoredDate.ifBlank { data.caseOnsetDate }
                                    )
                                }
                            }

                            CaseSlugs.MOH_505 -> {

                                val res = batches?.responsesFor(data.resourceId)?.take(5)
                                    ?: fhirEngine.search<QuestionnaireResponse> {
                                        filter(
                                            QuestionnaireResponse.SUBJECT,
                                            { value = "Patient/${data.resourceId}" })
                                    }.take(5)

                                if (res.isNotEmpty()) {
                                    val response = res.first().resource
                                    val extractedAnswers = extractStructuredAnswers(response)
                                    val countyLinkIds = FormFields.ReportingSite.COUNTY_VARIANTS // check in order
                                    val subCountyLinkIds = FormFields.ReportingSite.SUB_COUNTY_VARIANTS

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

                            CaseSlugs.VL -> {

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
                                        obs1.valueOf(FormFields.VlLab.RAPID_TEST_RESULT, default = "Pending")
                                    val datResult =
                                        obs1.valueOf(FormFields.VlLab.DAT_RESULT, default = "Pending")

                                    val aResult =
                                        obs1.valueOf(FormFields.VlLab.ASPIRATE_RESULT, default = "Pending")
                                    val mResult =

                                        obs1.valueOf(FormFields.VlLab.MICROSCOPY_RESULT, default = "Pending")

                                    var status =
                                        obs1.valueOf(FormFields.VlLab.FINAL_DIAGNOSIS, default = "Pending")
                                    val otherStatus =
                                        obs1.valueOf(FormFields.VlLab.FINAL_DIAGNOSIS_OTHER, default = "Pending")

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

                            CaseSlugs.AFP -> {
                                data = processAfpCase(fhirEngine, childEncounter, data)
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
                                        obs1.valueOf(FormFields.MeaslesLab.IGM_RESULT, default = "Pending")

                                    maxDays =
                                        obs.valueOf(FormFields.Measles.MR_VACCINE_LAST_30_DAYS)


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
//                    .filter {
//                        when (userRole) {
//                            UserRole.ADMINISTRATOR -> {
//                                true
//                            }
//
//                            else -> {
//                                it.sourceTag in units
//                            }
//                        }
//
//
//                    }
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
                    it.isCaseInformationEncounter() && !it.hasPartOf()
                }

                caseInfoEncounter?.let {

                    val childEncounter = loadChildEncounter(item.resourceId, it.logicalId)
                    val hasAfpLabData = childEncounter.any { child ->
                        child.reasonCode == "AFP Stool Lab Information" ||
                                child.reasonCode == "AFP Final Lab Information"
                    }
                    val childCaseInfoEncounter = childEncounter.firstOrNull {
                        it.reasonCode == "Measles Lab Information"
                    }

                    when {
                        hasAfpLabData -> {
                            item = processAfpCase(fhirEngine, childEncounter, item)
                        }

                        childCaseInfoEncounter != null -> {
                            val obs1 = fhirEngine.search<Observation> {
                                filter(
                                    Observation.ENCOUNTER,
                                    { value = "Encounter/${childCaseInfoEncounter.id}" })
                            }

                            val measlesIgm =
                                obs1.valueOf(FormFields.MeaslesLab.IGM_RESULT)


                            val finalClassification = when (measlesIgm.lowercase()) {
                                "positive" -> obs1.valueOf("final-confirm-classification")

                                "negative" -> obs1.valueOf("final-negative-classification")

                                else -> obs1.valueOf(FormFields.MeaslesLab.FINAL_CLASSIFICATION)
                            }

                            item = item.copy(labResults = measlesIgm, status = finalClassification)
                        }
                    }

                    // pull all Obs for this Encounter
                    val obs = fhirEngine.search<Observation> {
                        filter(
                            Observation.ENCOUNTER, { value = "Encounter/${it.logicalId}" })
                    }

                    val epid =
                        obs.valueOf(FhirSystems.EPID)
                    val county =
                        obs.valueOf(FormFields.Residence.COUNTY)
                    val subCounty =
                        obs.valueOf(FormFields.Residence.SUB_COUNTY)
                    val onset =
                        obs.valueOf(FormFields.EVENT_DATE)

                    item = item.copy(
                        encounterId = it.logicalId,
                        epid = epid,
                        subCounty = subCounty,
                        county = county,
                        caseOnsetDate = onset
                    )
                }

            } catch (e: Exception) {
                e.printStackTrace()
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
                    fhirPatient.resource.meta.tag.find { it.system.endsWith("/patient" + FhirSystems.MANAGING_LOCATION_SUFFIX) }?.code

                val logicalId = matchingIdentifier.value
                val obs = fhirEngine.search<Observation> {
                    filter(
                        Observation.ENCOUNTER, { value = "Encounter/${logicalId}" })
                }.take(500)
                var mohName =
                    obs.valueOf(FormFields.Rumor.REPORTING_CADRE)
                val otherCadreName =
                    obs.valueOf(FormFields.Rumor.REPORTING_CADRE_OTHER)
                if (mohName.contains("Other")) {
                    mohName = otherCadreName
                }
                var agency =
                    obs.valueOf(FormFields.Rumor.AGENCY)
                var agencyOther =
                    obs.valueOf(FormFields.Rumor.AGENCY_OTHER)
                if (agency.contains("Other")) {
                    agency = agencyOther
                }

                val countyLinkIds = FormFields.ReportingSite.COUNTY_VARIANTS // check in order
                val subCountyLinkIds = FormFields.ReportingSite.SUB_COUNTY_VARIANTS

                val county = try {
                    countyLinkIds.firstNotNullOfOrNull { id ->
                        obs.find { it.resource.code.codingFirstRep.code == id }?.resource?.value?.asStringValue()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    ""
                }
                val subCounty = try {
                    subCountyLinkIds.firstNotNullOfOrNull { id ->
                        obs.find { it.resource.code.codingFirstRep.code == id }?.resource?.value?.asStringValue()
                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                    ""
                }
                val response = RumorItem(
                    id = data.id,
                    resourceId = data.resourceId,
                    encounterId = matchingIdentifier.value,
                    mohName = mohName,

                    directorate = agency,
                    division = obs.valueOf("686990243396"),
                    village = obs.valueOf(FormFields.Rumor.VILLAGE),
                    subCounty = subCounty ?: "",
                    county = county ?: "",
                    lastUpdated = data.lastUpdated,
                    sourceTag = "$tag"
                )

                response
            } else {

                null
            }

        }.filter {
            // National roles see everything; others only their facilities ("units")
            if (userRole?.isNational == true) true else it.sourceTag in units
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
            obs1.getValue(FormFields.VlLab.RAPID_TEST_RESULT), // rapid
            obs1.getValue(FormFields.VlLab.DAT_RESULT), // dat
            obs1.getValue(FormFields.VlLab.ASPIRATE_RESULT), // aResult
            obs1.getValue(FormFields.VlLab.MICROSCOPY_RESULT)  // mResult
        ).map { it.lowercase() }

        val status = obs1.getValue(FormFields.VlLab.FINAL_DIAGNOSIS).takeUnless { it == "Other (specify)" }
            ?: obs1.getValue(FormFields.VlLab.FINAL_DIAGNOSIS_OTHER)

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
        val stoolLabEncounter = childEncounters.firstOrNull {
            it.reasonCode == "AFP Stool Lab Information"
        }
        val finalLabEncounter = childEncounters.firstOrNull {
            it.reasonCode == "AFP Final Lab Information"
        }

        if (stoolLabEncounter == null && finalLabEncounter == null) {
            return data
        }

        val afpResult = stoolLabEncounter?.let { encounter ->
            val observations = fhirEngine.search<Observation> {
                filter(
                    Observation.ENCOUNTER,
                    { value = "Encounter/${encounter.id}" })
            }
            observations.getValue("314664353334", "")
        }.orEmpty()

        val afpClassification = when (afpResult.lowercase()) {
            "positive" -> "Confirmed by lab"
            "negative" -> "Discarded"
            else -> "Pending Results"

        }

        return data.copy(
            labResults = afpResult.ifBlank { "Pending" },
            status = afpClassification.ifBlank { "Pending Results" }
        )
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
            findObservationValue(observations, FormFields.Residence.COUNTY)
        }

        val subCounty = if (patient.hasAddress() && patient.addressFirstRep.hasState()) {
            patient.addressFirstRep.state
        } else {
            findObservationValue(observations, FormFields.Residence.SUB_COUNTY)
        }

        // Extract other observation values
        val onset = findObservationValue(observations, FormFields.EVENT_DATE)
        val caseList = findObservationValue(observations, FormFields.Measles.CASE_OR_LINE_LIST) ?: "Case"
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
            CaseSlugs.MOH_505 -> {
                // Add specific processing for MOH 505 if needed
                data
            }

            CaseSlugs.VL -> {
                processVLLabResults(data, patientId)
            }

            CaseSlugs.AFP -> {
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

                val rapidResults = findObservationValue(obs, FormFields.VlLab.RAPID_TEST_RESULT) ?: "Pending"
                val datResult = findObservationValue(obs, FormFields.VlLab.DAT_RESULT) ?: "Pending"
                val aResult = findObservationValue(obs, FormFields.VlLab.ASPIRATE_RESULT) ?: "Pending"
                val mResult = findObservationValue(obs, FormFields.VlLab.MICROSCOPY_RESULT) ?: "Pending"
                var status = findObservationValue(obs, FormFields.VlLab.FINAL_DIAGNOSIS) ?: "Pending"
                val otherStatus = findObservationValue(obs, FormFields.VlLab.FINAL_DIAGNOSIS_OTHER) ?: "Pending"

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
            processAfpCase(fhirEngine, childEncounter, data)
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

    private fun Encounter.isCaseInformationEncounter(): Boolean {
        return reasonCode.any { concept ->
            concept.coding.any { coding ->
                val code = coding.code
                val display = coding.display
                code.equals("case-information", ignoreCase = true) ||
                        code.equals("Case Information", ignoreCase = true) ||
                        display.equals("case-information", ignoreCase = true) ||
                        display.equals("Case Information", ignoreCase = true) ||
                        code?.endsWith("Case Information", ignoreCase = true) == true
            } || concept.text?.equals("case-information", ignoreCase = true) == true
        } || reasonCodeFirstRep.codingFirstRep.code?.equals(
            "Case Information",
            ignoreCase = true
        ) == true
    }

    private fun findMatchingIdentifier(patient: Patient, nameQuery: String): Identifier? {
        return when (nameQuery) {
            CaseSlugs.RCCE -> patient.identifier.find {
                it.system == CaseSlugs.RCCE_COMMUNITY || it.system == CaseSlugs.RCCE_COUNTY
            }

            else -> patient.identifier.find { it.system == nameQuery }
        }
    }

    private fun findObservationValue(
        observations: List<SearchResult<Observation>>, code: String
    ): String? {
        return observations.firstOrNull { it.resource.code.codingFirstRep.code == code }?.resource?.value?.asStringValue()
    }

    private fun findFirstAnswer(
        answers: List<QuestionnaireAnswer>,
        linkIds: List<String>
    ): String {
        return linkIds.firstNotNullOfOrNull { linkId ->
            answers.firstOrNull { it.linkId == linkId }?.answer?.takeIf { it.isNotBlank() }
        }.orEmpty()
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

    companion object {
        private const val CASE_BATCH_SIZE = 100
        private const val RUMOR_PAGE_SIZE = 200

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
        val sourceTag: String,
        val ward: String = "",
        val reportingSite: String = "",
        val facilityType: String = "",
        val village: String = "",
        val respondentAge: String = "",
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

    /** `currentCase` names (Encounter.reasonCode) behind a case-list slug, e.g. CaseSlugs.RCCE. */
    private fun caseNamesFor(nameQuery: String): List<String> =
        CaseTypes.CASE_LISTED.filter { name ->
            val slug = name.toCaseSlug()
            slug == nameQuery || (nameQuery == CaseSlugs.RCCE && slug in CaseSlugs.RCCE_FORMS)
        }

    /**
     * Number of records of a case type, counted in the database (no records loaded).
     * Null when the case type is unknown and the caller must count the loaded list instead.
     */
    suspend fun countCaseRecords(nameQuery: String): Int? {
        val names = caseNamesFor(nameQuery).ifEmpty { return null }
        return withContext(Dispatchers.IO) {
            fhirEngine.count<Encounter> {
                filter(
                    Encounter.REASON_CODE,
                    *names.map<String, TokenParamFilterCriterion.() -> Unit> { name ->
                        { value = of(name) }
                    }.toTypedArray()
                )
            }.toInt()
        }.takeIf { it > 0 } // 0 may mean older records without the case name: count the list
    }

    /**
     * Finds the patients of a case type through their case Encounters (reasonCode = case name)
     * instead of reading every patient on the device. Returns null for unknown case types or when
     * no encounter carries the case name (older data), so the caller keeps the full scan.
     */
    private suspend fun caseBatchLoader(nameQuery: String): CaseBatchLoader? {
        val names = caseNamesFor(nameQuery).ifEmpty { return null }
        val encounters = fhirEngine.search<Encounter> {
            filter(
                Encounter.REASON_CODE,
                *names.map<String, TokenParamFilterCriterion.() -> Unit> { name ->
                    { value = of(name) }
                }.toTypedArray()
            )
        }.map { it.resource }.filter { !it.hasPartOf() }
        if (encounters.isEmpty()) return null

        val encountersByPatient = encounters
            .groupBy({ it.subject.referenceElement.idPart ?: "" }, { it.logicalId })
        val patients = encountersByPatient.keys.filter { it.isNotEmpty() }
            .chunked(CASE_BATCH_SIZE)
            .flatMap { ids ->
                fhirEngine.search<Patient> {
                    filter(
                        Resource.RES_ID,
                        *ids.map<String, TokenParamFilterCriterion.() -> Unit> { id ->
                            { value = of(id) }
                        }.toTypedArray()
                    )
                    filter(Patient.ACTIVE, { value = of(true) })
                }
            }
            .sortedBy { it.resource.nameFirstRep.givenAsSingleString.orEmpty() }
        return CaseBatchLoader(patients, encountersByPatient)
    }

    /**
     * Loads observations, child encounters and questionnaire responses for [CASE_BATCH_SIZE]
     * patients at a time (one query each) instead of three queries per patient. Only one batch is
     * held in memory.
     */
    private inner class CaseBatchLoader(
        val patients: List<SearchResult<Patient>>,
        private val encountersByPatient: Map<String, List<String>>,
    ) {
        private var loadedBatch = -1
        private var observations: Map<String, List<SearchResult<Observation>>> = emptyMap()
        private var children: Map<String, List<EncounterItem>> = emptyMap()
        private var responses: Map<String, List<SearchResult<QuestionnaireResponse>>> = emptyMap()

        suspend fun ensureLoaded(index: Int) {
            val batch = index / CASE_BATCH_SIZE
            if (batch == loadedBatch) return
            loadedBatch = batch
            val slice = patients.subList(
                batch * CASE_BATCH_SIZE,
                minOf(patients.size, (batch + 1) * CASE_BATCH_SIZE)
            )
            val patientIds = slice.map { it.resource.logicalId }
            val encounterRefs = patientIds.flatMap { id ->
                encountersByPatient[id].orEmpty().map { "Encounter/$it" }
            }
            val patientRefs = patientIds.map { "Patient/$it" }

            observations = if (encounterRefs.isEmpty()) emptyMap() else
                fhirEngine.search<Observation> {
                    filter(Observation.ENCOUNTER, *references(encounterRefs))
                }.groupBy { it.resource.encounter.referenceElement.idPart ?: "" }

            children = if (encounterRefs.isEmpty()) emptyMap() else
                fhirEngine.search<Encounter> {
                    filter(Encounter.PART_OF, *references(encounterRefs))
                }.map { it.resource }
                    .groupBy { it.partOf.referenceElement.idPart ?: "" }
                    .mapValues { (_, list) ->
                        list.map { encounter ->
                            EncounterItem(
                                id = encounter.logicalId,
                                reasonCode = encounter.reasonCodeFirstRep.codingFirstRep.code ?: "",
                                lastUpdated = encounter.identifier
                                    .find { it.system == FhirSystems.SYSTEM_CREATION }?.value ?: ""
                            )
                        }.sortedByDescending { it.lastUpdated }
                    }

            responses = fhirEngine.search<QuestionnaireResponse> {
                filter(QuestionnaireResponse.SUBJECT, *references(patientRefs))
            }.groupBy { it.resource.subject.referenceElement.idPart ?: "" }
        }

        fun observationsFor(encounterId: String) = observations[encounterId].orEmpty()
        fun childEncountersFor(encounterId: String) = children[encounterId].orEmpty()
        fun responsesFor(patientId: String) = responses[patientId].orEmpty()

        private fun references(values: List<String>) =
            values.map<String, ReferenceParamFilterCriterion.() -> Unit> { ref -> { value = ref } }
                .toTypedArray()
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
                    val id = it.resource.identifier.find { it.system == FhirSystems.SYSTEM_CREATION }
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

    private fun extractStructuredAnswers(
        response: QuestionnaireResponse
    ): List<QuestionnaireAnswer> {
        val fromModel = extractStructuredAnswersFromItems(response.item)
        if (fromModel.isNotEmpty()) {
            return fromModel
        }

        return try {
            val jsonParser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()
            val questionnaireResponseString = jsonParser.encodeResourceToString(response)
            FormatterClass().extractStructuredAnswersOnlyFromItems(
                JSONObject(questionnaireResponseString)
            )
        } catch (e: Exception) {
            Timber.tag("PatientListViewModel").e(e, "Failed to parse questionnaire response JSON")
            emptyList()
        }
    }

    private fun extractStructuredAnswersFromItems(
        items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>
    ): List<QuestionnaireAnswer> {
        val results = mutableListOf<QuestionnaireAnswer>()

        fun processItem(item: QuestionnaireResponse.QuestionnaireResponseItemComponent) {
            val linkId = item.linkId ?: ""
            val text = item.text ?: ""

            if (item.answer.isNotEmpty()) {
                val values = item.answer.mapNotNull { answer ->
                    extractAnswerValue(answer).takeIf { it.isNotBlank() }
                }
                if (values.isNotEmpty()) {
                    results.add(QuestionnaireAnswer(linkId, text, values.joinToString(", ")))
                }
            }

            if (item.item.isNotEmpty()) {
                item.item.forEach { child -> processItem(child) }
            }
        }

        items.forEach { processItem(it) }
        return results
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
        if (hasMeta()) if (meta.hasTag()) meta.tag.find { it.system.endsWith("/patient" + FhirSystems.MANAGING_LOCATION_SUFFIX) }?.code else "" else ""
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
        val id = identifier.find { it.system == FhirSystems.SYSTEM_CREATION }
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
