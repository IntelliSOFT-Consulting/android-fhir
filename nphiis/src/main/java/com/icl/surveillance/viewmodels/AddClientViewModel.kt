package com.icl.surveillance.viewmodels

import com.icl.surveillance.fhir.FhirConformance
import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.datacapture.validation.Invalid
import com.google.android.fhir.datacapture.validation.QuestionnaireResponseValidator
import com.google.android.fhir.get
import com.google.android.fhir.search.StringFilterModifier
import com.google.android.fhir.search.revInclude
import com.google.android.fhir.search.search
import com.ibm.icu.text.SimpleDateFormat
import com.icl.surveillance.clients.AddClientFragment.Companion.QUESTIONNAIRE_FILE_PATH_KEY
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.fhir.forms.CaseSlugs
import com.icl.surveillance.fhir.forms.CaseTypes
import com.icl.surveillance.fhir.forms.EpidNumber
import com.icl.surveillance.fhir.forms.FhirSystems
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.fhir.forms.LocalWins
import com.icl.surveillance.fhir.forms.PatientMapper
import com.icl.surveillance.fhir.forms.ResidenceLocation
import com.icl.surveillance.fhir.forms.VhfContactTracker
import com.icl.surveillance.fhir.forms.VhfSourceCases
import com.icl.surveillance.fhir.forms.StandardSpecimen
import com.icl.surveillance.fhir.forms.VhfScreened
import com.icl.surveillance.fhir.forms.VhfTraining
import com.icl.surveillance.ui.patients.CaseIdentifierIndex
import com.icl.surveillance.fhir.forms.answerOf
import com.icl.surveillance.fhir.forms.toCaseSlug
import com.icl.surveillance.models.FacilityInfo
import com.icl.surveillance.models.LocationLevel
import com.icl.surveillance.models.QuestionnaireAnswer
import com.icl.surveillance.models.UserRole
import com.icl.surveillance.utils.Constants.ALL_LINK_IDS
import com.icl.surveillance.utils.Constants.ALL_MPOX_LINK_IDS
import com.icl.surveillance.utils.Constants.WEEK_ENDING_DATE
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.utils.QuestionnaireHelper
import java.util.Calendar
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.DecimalType
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Location
import org.hl7.fhir.r4.model.MeasureReport
import org.hl7.fhir.r4.model.MeasureReport.MeasureReportGroupPopulationComponent
import org.hl7.fhir.r4.model.Meta
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Practitioner
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.Specimen
import org.hl7.fhir.r4.model.StringType
import org.json.JSONObject
import timber.log.Timber

class AddClientViewModel(application: Application, private val state: SavedStateHandle) :
    AndroidViewModel(application) {

    private var _questionnaireJson: String? = null
    val questionnaireJson: String
        get() = fetchQuestionnaireJson()

    val isPatientSaved = MutableLiveData<Boolean>()

    /** Patient and encounter ids of the case last saved by [persistPatientData]. */
    var lastSavedCase: Pair<String, String>? = null
        private set

    // Parsed once: re-parsing the form JSON on every save was a large source of allocations.
    private val questionnaire: Questionnaire by lazy {
        FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()
            .parseResource(questionnaireJson) as Questionnaire
    }

    private val fhirEngine: FhirEngine by lazy {
        FhirApplication.fhirEngine(application.applicationContext)
    }
    private val formatter = FormatterClass()

    /**
     * Saves patient registration questionnaire response into the application database.
     *
     * @param questionnaireResponse patient registration questionnaire response
     */
    private suspend fun getFacilitiesByLevelSuspend(
        applicationContext: Context,
        fhirEngine: FhirEngine,
        startId: String,
        level: LocationLevel
    ): List<String> = withContext(Dispatchers.IO) {
        val facilityIds = mutableListOf<String>()
        val locationIdsToProcess = mutableListOf(startId)

        when (level) {
            LocationLevel.FACILITY -> facilityIds.add("Location/$startId")

            LocationLevel.WARD -> {
                // Same "Location/<id>" format as the other levels (compared against sourceTag)
                val facilities = fhirEngine.search<Location> {
                    filter(Location.PARTOF, { value = "Location/$startId" })
                }
                facilityIds.addAll(facilities.map { "Location/${it.resource.logicalId}" })
            }

            LocationLevel.SUB_COUNTY -> {
                val cachedFacilities =
                    FormatterClass().getFacilityIdsForWard(applicationContext, startId)
//                if (!cachedFacilities.isNullOrEmpty()) {
//                    facilityIds.addAll(cachedFacilities)
//                } else {
                val wards = fhirEngine.search<Location> {
                    filter(Location.PARTOF, { value = "Location/$startId" })
                    revInclude<Location>(Location.PARTOF)
                }

                val allFacilityIds = wards.flatMap { ward ->
                    ward.revIncluded?.get(ResourceType.Location to Location.PARTOF.paramName)
                        ?.map { "Location/${it.logicalId}" } ?: emptyList()
                }

                FormatterClass().saveFacilityIdsForWard(
                    applicationContext,
                    startId,
                    allFacilityIds
                )
                facilityIds.addAll(allFacilityIds)
//                }
            }

            LocationLevel.COUNTY -> {
                val cachedFacilities =
                    FormatterClass().getFacilityIdsForWard(applicationContext, startId)
//                if (!cachedFacilities.isNullOrEmpty()) {
//                    facilityIds.addAll(cachedFacilities)
//                } else {

                // 1. County → SubCounties
                val subCounties = fhirEngine.search<Location> {
                    filter(Location.PARTOF, { value = "Location/$startId" })
                }

                val allFacilityIds = mutableListOf<String>()

                // 2. SubCounty → Wards
                for (subCounty in subCounties) {
                    val wards = fhirEngine.search<Location> {
                        filter(
                            Location.PARTOF,
                            { value = "Location/${subCounty.resource.logicalId}" })
                    }

                    // 3. Ward → Facilities
                    for (ward in wards) {
                        val facilities = fhirEngine.search<Location> {
                            filter(
                                Location.PARTOF,
                                { value = "Location/${ward.resource.logicalId}" })
                        }

                        allFacilityIds.addAll(
                            facilities.map { "Location/${it.resource.logicalId}" }
                        )
                    }
                }
                FormatterClass().saveFacilityIdsForWard(
                    applicationContext,
                    startId,
                    allFacilityIds
                )

                facilityIds.addAll(allFacilityIds)
//                }
            }

            LocationLevel.NATIONAL -> {
                val counties = fhirEngine.search<Location> { }
                val countyIds = counties.map { it.resource.logicalId }

            }
        }

        facilityIds
    }

    fun generateAreaOfJurisdiction(
        context: Context,
        engine: FhirEngine
    ): LinkedList<String> {
        val formatter = FormatterClass()
        val storedRole = formatter.getSharedPref("practitionerRole", context)
        val userRole = UserRole.fromAny(storedRole ?: "")
        val urls = mutableListOf<String>()


        when (userRole?.scope) {
            LocationLevel.FACILITY -> {
                val facilityId = formatter.getSharedPref("facility", context)
                if (!facilityId.isNullOrEmpty()) {
                    urls.add("Location/$facilityId")
                }
            }

            LocationLevel.WARD -> {
                val ward = formatter.getSharedPref("ward", context)
                if (!ward.isNullOrEmpty()) {
                    val facilities = runBlocking {
                        getFacilitiesByLevelSuspend(context, engine, ward, LocationLevel.WARD)
                    }
                    urls.addAll(facilities)
                }
            }

            LocationLevel.SUB_COUNTY -> {
                val subCounty = formatter.getSharedPref("subCounty", context)
                if (!subCounty.isNullOrEmpty()) {
                    val facilities =
                        runBlocking {
                            getFacilitiesByLevelSuspend(
                                context,
                                engine,
                                subCounty,
                                LocationLevel.SUB_COUNTY
                            )
                        }
                    urls.addAll(facilities)
                }
            }

            LocationLevel.COUNTY -> {
                val county = formatter.getSharedPref("county", context)
                if (!county.isNullOrEmpty()) {
                    val facilities = runBlocking {
                        getFacilitiesByLevelSuspend(
                            context,
                            engine,
                            county,
                            LocationLevel.COUNTY
                        )
                    }
                    urls.addAll(facilities)
                }
            }

            else -> {}
        }

        return LinkedList(urls)
    }

    suspend fun retrieveCaseEncounters(reasonCode: String): List<Encounter> {
        return fhirEngine
            .search<Encounter> {
            }
            .map { it.resource }
            .filter { encounter ->
                encounter.reasonCode.any { codeableConcept ->
                    codeableConcept.coding.any { coding ->
                        coding.code == reasonCode
                    }
                }
            }
    }

    suspend fun retrieveResponses(encounterId: String): List<QuestionnaireResponse> {
        return fhirEngine
            .search<QuestionnaireResponse> {
                filter(QuestionnaireResponse.ENCOUNTER, { value = "Encounter/$encounterId" })
            }
            .map { it.resource }

    }

    suspend fun updateObservationsTag(
        encounter: Encounter,
        res: QuestionnaireResponse,
        facility: String,
        encounterId: String
    ) {
        val observations = fhirEngine
            .search<Observation> {
                filter(Observation.ENCOUNTER, { value = "Encounter/$encounterId" })
            }
            .map { it.resource }
            .filter { obs ->
                obs.meta?.tag?.none { coding ->
                    coding.code?.startsWith("Location/") == true
                } ?: true // true if meta or tag is null
            }

        val noEncounterTag = encounter.meta?.tag?.none { coding ->
            coding.code?.startsWith("Location/") == true
        }
        val noResponseTag = res.meta?.tag?.none { coding ->
            coding.code?.startsWith("Location/") == true
        }
        if (noEncounterTag == true) {
            val newEncounter = encounter.copy()
            newEncounter.id = encounter.id
            newEncounter.meta = Meta().apply {
                tag = listOf(
                    Coding().apply {
                        system =
                            FhirSystems.managingLocation("encounter")
                        code = facility
                        display = facility
                    }
                )
            }
            LocalWins.update(fhirEngine, newEncounter)
        }
        if (noResponseTag == true) {
            val newResponse = res.copy()
            newResponse.id = res.id
            newResponse.meta = Meta().apply {
                tag = listOf(
                    Coding().apply {
                        system =
                            FhirSystems.managingLocation("encounter")
                        code = facility
                        display = facility
                    }
                )
            }
            LocalWins.update(fhirEngine, newResponse)
        }
        observations.forEach { obs ->
            val jsonParser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()
            val responseString =
                jsonParser.encodeResourceToString(obs)

            val newObs = obs.copy()
            newObs.id = obs.id
            newObs.meta = Meta().apply {
                tag = listOf(
                    Coding().apply {
                        system =
                            FhirSystems.managingLocation("observation")
                        code = facility
                        display = facility
                    }
                )
            }
            LocalWins.update(fhirEngine, newObs)
        }
    }

    fun saveUserResponse(
        questionnaireResponse: QuestionnaireResponse,
        questionnaireResponseString: String,
        context: Context
    ) {
        viewModelScope.launch {
            val result =
                persistUserResponse(questionnaireResponse, questionnaireResponseString, context)
            isPatientSaved.value = result != PersistResult.INVALID
        }
    }

    /** Validates and stores a standalone response (e.g. Mpox supervisor checklist). */
    suspend fun persistUserResponse(
        questionnaireResponse: QuestionnaireResponse,
        questionnaireResponseString: String,
        context: Context,
        extraTags: List<Coding> = emptyList(),
    ): PersistResult {
        run {
            if (hasValidationErrors(questionnaireResponse)) {
                return PersistResult.INVALID
            }
            val practitionerId = formatter.getSharedPref("fhirPractitionerId", context)
            val fullName = formatter.getSharedPref("fullNames", context)

            val extractedAnswers =
                extractStructuredAnswers(questionnaireResponse, questionnaireResponseString)


            return withContext(Dispatchers.IO) {

                val latitude = extractedAnswers.answerOf(FormFields.Geo.LATITUDE)
                val longitude = extractedAnswers.answerOf(FormFields.Geo.LONGITUDE)

                val locationIdentifier = QuestionnaireHelper().createFullFhirIdentifier(
                    codeData = FhirSystems.GEO_LOCATION,
                    valueData = "lat:${latitude},lon:${longitude}",
                    systemData = FhirSystems.GEO_LOCATION_DETAILS,
                    displayData = "Latitude: $latitude, Longitude: $longitude"
                )

                val responseType = QuestionnaireHelper().createFullFhirIdentifier(
                    codeData = FhirSystems.SUPERVISOR_CHECKLIST,
                    valueData = FhirSystems.SUPERVISOR_CHECKLIST,
                    systemData = FhirSystems.SUPERVISOR_CHECKLIST,
                    displayData = "Supervisor Checklist"
                )

                questionnaireResponse.identifier = locationIdentifier
                val extension = Extension().apply {
                    url = FhirSystems.SUPERVISOR_CHECKLIST
                    setValue(responseType)
                }
                questionnaireResponse.addExtension(extension)
                questionnaireResponse.status =
                    QuestionnaireResponse.QuestionnaireResponseStatus.INPROGRESS
                if (practitionerId != null) {
                    questionnaireResponse.author = Reference().apply {
                        reference = "Practitioner/$practitionerId"
                        display = fullName
                    }
                }
                if (extraTags.isNotEmpty()) {
                    questionnaireResponse.meta.tag =
                        questionnaireResponse.meta.tag + extraTags.map { it.copy() }
                }
                try {
                    fhirEngine.create(questionnaireResponse)
                    PersistResult.SAVED
                } catch (e: Exception) {
                    Timber.tag("AddClientViewModel").e(e, "Failed to save response")
                    PersistResult.FAILED
                }
            }
        }
    }


    fun updatePatientData(
        questionnaireResponse: QuestionnaireResponse, context: Context, measureReport: MeasureReport
    ) {
        viewModelScope.launch {
            val qh = QuestionnaireHelper()
            val formatter = FormatterClass()
            val facility = formatter.getSharedPref("facility", context)

            if (QuestionnaireResponseValidator.validateQuestionnaireResponse(
                    questionnaire,
                    questionnaireResponse,
                    getApplication(),
                ).values.flatten().any { it is Invalid }
            ) {
                isPatientSaved.value = false
                return@launch
            }
            val extractedAnswers = extractStructuredAnswers(questionnaireResponse, "")

            viewModelScope.launch {
                questionnaireResponse.addExtension(
                    sourceExtension(
                        "questionnaire", context, extractedAnswers
                    )
                )
                questionnaireResponse.meta = Meta().apply {
                    tag = listOf(
                        sourceMetaTag("questionnaire", facility, context, extractedAnswers)
                    )
                }
            }

            val idPart = FormatterClass().getSharedPref(
                "activeResponse", context
            )
            questionnaireResponse.id = idPart
            val practitionerId = formatter.getSharedPref("fhirPractitionerId", context)
            if (practitionerId != null) {
                questionnaireResponse.author = Reference("Practitioner/$practitionerId")
            }

            // Extract Patient & Encounter Ids
            val patientId = questionnaireResponse.subject.reference.split("/")[1]
            val encounterId = questionnaireResponse.encounter.reference.split("/")[1]

            val subjectReference = Reference("Patient/$patientId")
            val encounterReference = Reference("Encounter/$encounterId")

            withContext(Dispatchers.IO) {
                try {
                    val observations = fhirEngine.search<Observation> {
                        filter(
                            Observation.SUBJECT, { value = "Patient/$patientId" })
                        filter(
                            Observation.ENCOUNTER, { value = "Encounter/$encounterId" })

                    }.take(500)



                    extractedAnswers.forEach {
                        val measureCodeableConcept = CodeableConcept()
                        measureCodeableConcept.codingFirstRep.code = it.linkId
                        measureCodeableConcept.codingFirstRep.display = it.text
                        measureCodeableConcept.codingFirstRep.system = FhirSystems.QUESTIONNAIRE_ANSWERS
                        measureCodeableConcept.text = it.text

                        val compo = MeasureReportGroupPopulationComponent()
                        compo.code = measureCodeableConcept

                        if (it.linkId == WEEK_ENDING_DATE) {
                            val date = SimpleDateFormat(
                                "yyyy-MM-dd", Locale.getDefault()
                            ).parse(it.answer)
                            if (date != null) {
                                measureReport.date = date
                            }

                        }
                        compo.id = it.linkId

                        try {
                            compo.count = it.answer.toInt()
                        } catch (e: Exception) {
                            compo.count = 0
                        }
                        if (!ALL_LINK_IDS.contains(it.linkId)) {
                            measureReport.groupFirstRep.addPopulation(compo)
                        }
                        // Create new Observations
                        val obs = qh.codingQuestionnaire(
                            it.linkId, it.text, it.answer
                        )
                        createResource(
                            obs, subjectReference, encounterReference, context, extractedAnswers
                        )

                    }

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            LocalWins.update(fhirEngine, questionnaireResponse, measureReport)

            withContext(Dispatchers.Main) { isPatientSaved.value = true }
        }


    }

    fun savePatientData(
        questionnaireResponse: QuestionnaireResponse, context: Context
    ) {
        viewModelScope.launch {
            val result = persistPatientData(questionnaireResponse, context)
            // Same UI contract as before: only a validation failure reports "not saved".
            isPatientSaved.value = result != PersistResult.INVALID
        }
    }

    /** Outcome of a persist call, used by the UI and by the synthetic data generator. */
    enum class PersistResult { SAVED, INVALID, FAILED }

    /** Logcat tag of the screened -> VHF case transition. */
    private val SCREENED_LOG = "SCREENED_SAVE"

    /**
     * The screened person a VHF case investigation was started from (Screened Cases), or null.
     * Only for the interactive VHF form: synthetic data passes [caseOverride].
     */
    private suspend fun screenedSource(context: Context, caseOverride: String?): Patient? {
        if (caseOverride != null) return null
        val formatter = FormatterClass()
        if (formatter.getSharedPref("currentCase", context) != CaseTypes.VHF) return null
        val id = formatter.getSharedPref(VhfScreened.PREF_SOURCE, context)
            ?.takeIf { it.isNotBlank() } ?: return null
        val patient = runCatching { fhirEngine.get(ResourceType.Patient, id) as Patient }
            .onFailure { Timber.tag(SCREENED_LOG).w(it, "Screened patient %s not found", id) }
            .getOrNull()
        Timber.tag(SCREENED_LOG).d(
            "Investigating screened %s: found=%s screened=%s", id, patient != null,
            patient?.let { VhfScreened.isScreened(it) },
        )
        return patient?.takeIf { VhfScreened.isScreened(it) }
    }

    /**
     * The screened person's identifiers plus the new case's. The screened marker is renamed to
     * the screening number, so the person leaves the screened list; duplicates are dropped.
     */
    private fun mergeIdentifiers(old: List<Identifier>, new: List<Identifier>): MutableList<Identifier> =
        (old.map { identifier ->
            if (identifier.system in FormFields.Vhf.SCREENED_SYSTEMS) {
                identifier.copy().setSystem(FormFields.Vhf.SCREENING_NUMBER_SYSTEM)
            } else identifier
        }.filterNot { it.system == FhirSystems.SYSTEM_CREATION } + new)
            .distinctBy { "${it.system}|${it.value}" }
            .toMutableList()

    /**
     * Validates [questionnaireResponse] and runs the full case-extraction workflow
     * (Patient, Encounter, QuestionnaireResponse, EPID + per-answer Observations, Specimens,
     * MeasureReport). Suspends until everything is written.
     *
     * @param caseOverride case name to use instead of the `currentCase` shared preference.
     * @param extraTags meta tags added to every resource written by this call
     *   (used to mark synthetic test data).
     */
    suspend fun persistPatientData(
        questionnaireResponse: QuestionnaireResponse,
        context: Context,
        caseOverride: String? = null,
        extraTags: List<Coding> = emptyList(),
    ): PersistResult {
        facilityInfoCache.clear()
        activeExtraTags = extraTags
        try {
            if (hasValidationErrors(questionnaireResponse)) {
                return PersistResult.INVALID
            }
            val identifierSystem0 = Identifier()
            val typeCodeableConcept0 = CodeableConcept()
            val codingList0 = ArrayList<Coding>()
            val coding0 = Coding()
            coding0.system = FhirSystems.SYSTEM_CREATION
            coding0.code = FhirSystems.SYSTEM_CREATION_CODE
            coding0.display = FhirSystems.SYSTEM_CREATION_DISPLAY
            codingList0.add(coding0)
            typeCodeableConcept0.coding = codingList0
            typeCodeableConcept0.text = FormatterClass().formatDateTime(Date())

            identifierSystem0.value = FormatterClass().formatDateTime(Date())
            identifierSystem0.system = FhirSystems.SYSTEM_CREATION
            identifierSystem0.type = typeCodeableConcept0

            // A case investigation started from a screened person (Screened Cases) continues that
            // person's Patient and screening response instead of creating new ones.
            val screened: Patient? = screenedSource(context, caseOverride)
            val screeningResponse: QuestionnaireResponse? =
                screened?.let { VhfScreened.screeningResponse(fhirEngine, it.logicalId) }
            val patientId = screened?.logicalId ?: generateUuid()
            val subjectReference = Reference("Patient/$patientId")
            val formatter = FormatterClass()

            val facility = formatter.getSharedPref("facility", context)

            // Residence picked on the map before the form (contacts). Its name / lat / long are
            // already answers of the form (hidden residence-map-* questions); here it is also
            // added to the Patient's address (geolocation) and the response (extension).
            val residence = if (caseOverride == null) ResidenceLocation.take(context) else null
            val extractedAnswers = extractStructuredAnswers(questionnaireResponse, "")


            val reasonCode = caseOverride ?: FormatterClass().getSharedPref(
                "currentCase", context
            )
            val patient = Patient()
            patient.id = patientId


            val qh = QuestionnaireHelper()
            val encounterId = generateUuid()
            val enc = qh.generalEncounter(null, encounterId)
            enc.id = encounterId
            enc.subject = subjectReference

            enc.reasonCodeFirstRep.codingFirstRep.code = "$reasonCode"
            enc.identifier.add(identifierSystem0)


            var case = "case-info"
            if (reasonCode != null) {
                case = reasonCode.toCaseSlug()

            }


            val codeableConcept = CodeableConcept()
            codeableConcept.codingFirstRep.code = FhirSystems.CASE_INFORMATION
            codeableConcept.codingFirstRep.display = FhirSystems.CASE_INFORMATION
            codeableConcept.codingFirstRep.system = FhirSystems.CASE_INFORMATION
            codeableConcept.text = FhirSystems.CASE_INFORMATION
            enc.addReasonCode(codeableConcept)


            val encounterReference = Reference("Encounter/$encounterId")
            val measure = MeasureReport()
            run {
                patient.addExtension(
                    sourceExtension("patient", context, extractedAnswers)
                )
                patient.meta = Meta().apply {
                    tag = listOf(
                        sourceMetaTag("patient", facility, context, extractedAnswers)
                    )
                }
                enc.addExtension(sourceExtension("encounter", context, extractedAnswers))
                enc.meta = Meta().apply {
                    tag = listOf(
                        sourceMetaTag(
                            resource = "encounter",
                            facility = "$facility",
                            context = context,
                            extractedAnswers
                        )
                    )
                }
                measure.addExtension(
                    sourceExtension(
                        "measure", context, extractedAnswers
                    )
                )
                measure.meta = Meta().apply {
                    tag = listOf(
                        sourceMetaTag("measure", facility, context, extractedAnswers)
                    )
                }
                questionnaireResponse.addExtension(
                    sourceExtension(
                        "questionnaire", context, extractedAnswers
                    )
                )
                questionnaireResponse.meta = Meta().apply {
                    tag = listOf(
                        sourceMetaTag("questionnaire", facility, context, extractedAnswers)
                    )
                }
            }
            val practitionerId = formatter.getSharedPref("fhirPractitionerId", context)
            if (practitionerId != null) {
                questionnaireResponse.author = Reference("Practitioner/$practitionerId")
                measure.reporter = Reference("Practitioner/$practitionerId")
                patient.generalPractitionerFirstRep.reference = "Practitioner/$practitionerId"
                enc.participantFirstRep.individual = Reference("Practitioner/$practitionerId")
            }

            // Module-specific mapping of the answers onto the Patient (and MeasureReport),
            // followed by the module's EPID number.
            val answers = extractedAnswers

            suspend fun saveEpid(epid: String) = createResource(
                qh.codingQuestionnaire(FhirSystems.EPID, FhirSystems.EPID_DISPLAY, epid),
                subjectReference, encounterReference, context, answers
            )

            suspend fun saveSpecimen(
                linkId: String,
                date: String?,
                type: String,
                notes: List<String> = emptyList(),
            ) = createSpecimenResource(
                linkId, date, type, subjectReference, encounterReference, notes, context, answers
            )

            fun startSummaryMeasure(label: String) {
                patient.nameFirstRep.family = label
                patient.nameFirstRep.addGiven(label)
                measure.id = generateUuid()
                measure.subject = subjectReference
                measure.status = MeasureReport.MeasureReportStatus.COMPLETE
                measure.type = MeasureReport.MeasureReportType.SUMMARY
            }

            when (case) {
                CaseSlugs.MPOX_REGISTER -> {
                    PatientMapper.applyName(
                        patient,
                        answers.answerOf(FormFields.Person.FIRST_NAME),
                        answers.answerOf(FormFields.Person.MIDDLE_NAME),
                        answers.answerOf(FormFields.Person.SURNAME),
                        PatientMapper.NameOrder.SURNAME_AS_FAMILY
                    )
                    PatientMapper.applyBirthDate(patient, answers.answerOf(FormFields.Person.DATE_OF_BIRTH))
                    PatientMapper.applySex(patient, answers.answerOf(FormFields.Person.SEX))
                    answers.answerOf(FormFields.Person.PHONE)
                        ?.let { patient.addTelecom(PatientMapper.mobile(it)) }
                    val (county, subCounty) = applyResidence(patient, answers, context)

                    val country = answers.answerOf(FormFields.MpoxRegister.COUNTRY_OF_ORIGIN)
                    val center = answers.answerOf(FormFields.MpoxRegister.VACCINATION_CENTER).orEmpty()
                    saveEpid(
                        if (country != null) EpidNumber.foreign(country, center, EpidNumber.MPOX_VACCINATION)
                        else EpidNumber.kenyan(county, subCounty, EpidNumber.MPOX_VACCINATION)
                    )
                }

                CaseSlugs.RUMOR -> {
                    val (county, subCounty) = applyResidence(patient, answers, context)
                    saveEpid(EpidNumber.kenyan(county, subCounty, EpidNumber.RUMOR))
                }

                CaseSlugs.MEASLES -> {
                    PatientMapper.applyName(
                        patient,
                        answers.answerOf(FormFields.Person.FIRST_NAME),
                        answers.answerOf(FormFields.Person.MIDDLE_NAME),
                        answers.answerOf(FormFields.Person.SURNAME),
                        PatientMapper.NameOrder.SURNAME_AS_FAMILY
                    )
                    PatientMapper.applySex(patient, answers.answerOf(FormFields.Person.SEX))
                    PatientMapper.applyBirthDate(patient, answers.answerOf(FormFields.Person.DATE_OF_BIRTH))
                    PatientMapper.applyContact(
                        patient,
                        fullName = answers.answerOf(FormFields.Measles.PARENT_NAME),
                        phone = answers.answerOf(FormFields.Person.PHONE),
                        addressLines = FormFields.Measles.PARENT_ADDRESS_LINES.mapNotNull { answers.answerOf(it) },
                    )
                    val (county, subCounty) = applyResidence(patient, answers, context)
                    val isLineList =
                        answers.answerOf(FormFields.Measles.CASE_OR_LINE_LIST).equals("yes", ignoreCase = true)
                    saveEpid(
                        EpidNumber.kenyan(
                            county, subCounty,
                            if (isLineList) EpidNumber.MEASLES_LINE_LIST else EpidNumber.MEASLES
                        )
                    )

                    if (answers.answerOf(FormFields.Measles.OTHER_SPECIMEN_COLLECTED).equals("yes", ignoreCase = true)) {
                        val otherType = answers.answerOf(FormFields.Measles.OTHER_SPECIMEN_TYPE)
                        val otherDate = answers.answerOf(FormFields.Measles.OTHER_SPECIMEN_DATE)
                        if (otherType != null && otherDate != null) {
                            saveSpecimen(FormFields.Measles.OTHER_SPECIMEN_TYPE, otherDate, otherType)
                        }
                    }
                    FormFields.Measles.SPECIMENS.forEach { specimen ->
                        val collected = answers.answerOf(specimen.collectedLinkId)
                        val date = answers.answerOf(specimen.dateLinkId)
                        if (collected.equals("yes", ignoreCase = true) && date != null) {
                            saveSpecimen(specimen.collectedLinkId, date, specimen.type)
                        }
                    }
                }

                CaseSlugs.AFP -> {
                    PatientMapper.applyName(
                        patient,
                        answers.answerOf(FormFields.Person.FIRST_NAME),
                        answers.answerOf(FormFields.Person.MIDDLE_NAME),
                        answers.answerOf(FormFields.Person.SURNAME),
                        PatientMapper.NameOrder.FIRST_AS_FAMILY
                    )
                    PatientMapper.applySex(patient, answers.answerOf(FormFields.Person.SEX))
                    PatientMapper.applyBirthDate(patient, answers.answerOf(FormFields.Person.DATE_OF_BIRTH))
                    PatientMapper.applyContact(
                        patient,
                        fullName = answers.answerOf(FormFields.Afp.GUARDIAN_NAME),
                        phone = answers.answerOf(FormFields.Afp.GUARDIAN_PHONE),
                    )
                    val (county, subCounty) = applyResidence(patient, answers, context)
                    saveEpid(EpidNumber.kenyan(county, subCounty, EpidNumber.AFP))

                    answers.answerOf(FormFields.Afp.STOOL_SPECIMEN_DATE)?.let { date ->
                        saveSpecimen(FormFields.Afp.STOOL_SPECIMEN_DATE, date, "Stool")
                    }
                }

                CaseSlugs.VL -> {
                    PatientMapper.applyName(
                        patient,
                        answers.answerOf(FormFields.Vl.FIRST_NAME),
                        answers.answerOf(FormFields.Vl.MIDDLE_NAME),
                        answers.answerOf(FormFields.Vl.SURNAME),
                        PatientMapper.NameOrder.FIRST_AS_FAMILY
                    )
                    PatientMapper.applySex(patient, answers.answerOf(FormFields.Vl.SEX))
                    PatientMapper.applyBirthDate(patient, answers.answerOf(FormFields.Person.DATE_OF_BIRTH))
                    answers.answerOf(FormFields.Vl.PHONE)
                        ?.let { patient.addTelecom(PatientMapper.mobile(it)) }
                    PatientMapper.applyContact(
                        patient,
                        fullName = answers.answerOf(FormFields.Vl.CONTACT_NAME),
                        phone = answers.answerOf(FormFields.Vl.CONTACT_PHONE),
                    )
                    val (county, subCounty) = applyResidence(patient, answers, context)
                    saveEpid(EpidNumber.kenyan(county, subCounty, EpidNumber.VL))
                }

                CaseSlugs.VHF -> {
                    PatientMapper.applyName(
                        patient,
                        first = answers.answerOf(FormFields.Person.FIRST_NAME), // "Given names"
                        middle = null,
                        surname = answers.answerOf(FormFields.Person.SURNAME),
                        order = PatientMapper.NameOrder.SURNAME_AS_FAMILY
                    )
                    PatientMapper.applySex(patient, answers.answerOf(FormFields.Person.SEX))
                    PatientMapper.applyBirthDate(patient, answers.answerOf(FormFields.Person.DATE_OF_BIRTH))
                    answers.answerOf(FormFields.Vhf.PHONE)
                        ?.let { patient.addTelecom(PatientMapper.mobile(it)) }
                    answers.answerOf(FormFields.Vhf.NATIONAL_ID)?.let { idNumber ->
                        // Type of identification (meeting 8 Oct 2026); older records were national IDs.
                        val idType = (answers.answerOf(FormFields.Vhf.ID_TYPE)
                            ?: answers.answerOf(FormFields.Vhf.ID_TYPE_MINOR))
                            ?.trim()?.lowercase()?.replace(' ', '-')
                        patient.addIdentifier().apply {
                            system = idType?.takeIf { it != "none" } ?: "national-id"
                            value = idNumber
                        }
                    }
                    PatientMapper.applyContact(
                        patient,
                        fullName = answers.answerOf(FormFields.Vhf.NEXT_OF_KIN),
                        phone = answers.answerOf(FormFields.Vhf.NEXT_OF_KIN_PHONE),
                    )
                    // Link a contact to its source case: the case it was registered from (Contacts
                    // tab), else the case named by "Contact Link to Case" if it is on the device.
                    (FormatterClass().getSharedPref(VhfContactTracker.PREF_SOURCE_PATIENT, context)
                        ?.takeIf { it.isNotBlank() }
                        ?: VhfSourceCases.epidFrom(answers.answerOf(FormFields.Vhf.SOURCE_CASE_EPID))
                            ?.let { VhfSourceCases.findByEpid(fhirEngine, it)?.logicalId })
                        ?.let { sourcePatientId ->
                            patient.addLink()
                                .setOther(Reference("Patient/$sourcePatientId"))
                                .setType(Patient.LinkType.SEEALSO)
                        }
                    // Trainings: identifier test_xxxx so practice records stay separate.
                    if (VhfTraining.isOn(context)) VhfTraining.mark(patient)
                    val (county, subCounty) = applyResidence(patient, answers, context)
                    saveEpid(EpidNumber.kenyan(county, subCounty, EpidNumber.VHF))

                    // Laboratory (specimen collection): one standard Specimen per collection.
                    if (answers.answerOf(FormFields.Vhf.SAMPLES_COLLECTED).equals("yes", ignoreCase = true)) {
                        val answeredType = answers.answerOf(FormFields.Vhf.SAMPLE_TYPE)
                        val type = when {
                            answeredType == null -> "Unspecified"
                            answeredType.startsWith("Other", ignoreCase = true) ->
                                answers.answerOf(FormFields.Vhf.SAMPLE_TYPE_OTHER) ?: answeredType
                            else -> answeredType
                        }
                        val transport = answers.answerOf(FormFields.Vhf.SAMPLE_TRANSPORT)?.let { mode ->
                            when {
                                mode.startsWith("Other", ignoreCase = true) ->
                                    answers.answerOf(FormFields.Vhf.SAMPLE_TRANSPORT_OTHER) ?: mode
                                mode.equals("Road", ignoreCase = true) ->
                                    answers.answerOf(FormFields.Vhf.SAMPLE_TRANSPORT_ROAD)
                                        ?.let { "Road ($it)" } ?: mode
                                else -> mode
                            }
                        }
                        saveSpecimen(
                            FormFields.Vhf.SAMPLE_TYPE,
                            answers.answerOf(FormFields.Vhf.COLLECTION_DATE),
                            type,
                            listOfNotNull(
                                answers.answerOf(FormFields.Vhf.SAMPLE_TISSUE_TYPE)
                                    ?.takeIf { type == "Tissue" }?.let { "Tissue type: $it" },
                                transport?.let { "Transport: $it" },
                                answers.answerOf(FormFields.Vhf.SENT_DATE)?.let { "Sent to the laboratory: $it" },
                            ),
                        )
                    }
                }

                CaseSlugs.ALERT_LOG -> {
                    // The person affected, when known (name is optional on an alert).
                    PatientMapper.applyName(
                        patient,
                        first = answers.answerOf(FormFields.Person.FIRST_NAME),
                        middle = null,
                        surname = answers.answerOf(FormFields.Person.SURNAME),
                        order = PatientMapper.NameOrder.SURNAME_AS_FAMILY
                    )
                    if (!patient.nameFirstRep.hasFamily() && !patient.nameFirstRep.hasGiven()) {
                        patient.nameFirstRep.family = "Alert"
                    }
                    PatientMapper.applySex(patient, answers.answerOf(FormFields.Person.SEX))
                    answers.answerOf(FormFields.AlertLog.MOBILE)
                        ?.let { patient.addTelecom(PatientMapper.mobile(it)) }
                    val (county, subCounty) = applyResidence(patient, answers, context)
                    saveEpid(EpidNumber.kenyan(county, subCounty, EpidNumber.ALERT_LOG))
                }

                CaseSlugs.MOH_505 -> {
                    startSummaryMeasure("MOH-505")
                    val (county, subCounty) = applyResidence(patient, answers, context)
                    saveEpid(EpidNumber.kenyan(county, subCounty, EpidNumber.MOH_505))
                }

                CaseSlugs.MPOX_TALLY_SHEET -> {
                    startSummaryMeasure("Mpox-Tally")
                    val (county, subCounty) = applyResidence(patient, answers, context)
                    saveEpid(EpidNumber.kenyan(county, subCounty, EpidNumber.MPOX))
                }
            }
            residence?.let {
                // After the module mapping, so the county address stays the first address.
                it.appendTo(questionnaireResponse, patient)
                Timber.tag("RESIDENCE_PICKER").d("Appended residence to the submission: %s", it.label)
            }
            withMetaTags(patient)
            withMetaTags(enc)
            withMetaTags(measure)
            withMetaTags(questionnaireResponse)
            return withContext(Dispatchers.IO) {
                try {
                    val identifierSystem = Identifier()
                    val typeCodeableConcept = CodeableConcept()
                    val codingList = ArrayList<Coding>()
                    val coding = Coding()
                    coding.system = case
                    coding.code = case
                    coding.display = case
                    codingList.add(coding)
                    typeCodeableConcept.coding = codingList
                    typeCodeableConcept.text = encounterId

                    identifierSystem.value = encounterId
                    identifierSystem.system = case
                    identifierSystem.type = typeCodeableConcept


                    patient.identifier.add(identifierSystem0)
                    patient.identifier.add(identifierSystem)
                    patient.active = true
                    if (screened != null) {
                        // Keep the screened person's identity: same id, earlier identifiers kept
                        // (the screened marker becomes the screening number) and links merged.
                        // The Patient is updated last, once the case itself is saved.
                        patient.identifier = mergeIdentifiers(screened.identifier, patient.identifier)
                        patient.link = (screened.link + patient.link)
                            .distinctBy { it.other?.reference }.toMutableList()
                    } else {
                        fhirEngine.create(patient)
                        CaseIdentifierIndex.put(patient)
                    }
                    fhirEngine.create(FhirConformance.ensureRequired(enc))
                    questionnaireResponse.subject = subjectReference
                    questionnaireResponse.encounter = encounterReference
                    if (screeningResponse != null) {
                        // The screening response becomes the case's response.
                        questionnaireResponse.id = screeningResponse.logicalId
                        fhirEngine.update(FhirConformance.ensureRequired(questionnaireResponse))
                    } else {
                        questionnaireResponse.id = generateUuid()
                        fhirEngine.create(FhirConformance.ensureRequired(questionnaireResponse))
                    }

                    extractedAnswers.forEach {

                        val measureCodeableConcept = CodeableConcept()
                        measureCodeableConcept.codingFirstRep.code = it.linkId
                        measureCodeableConcept.codingFirstRep.display = it.text
                        measureCodeableConcept.codingFirstRep.system = FhirSystems.QUESTIONNAIRE_ANSWERS
                        measureCodeableConcept.text = it.text

                        val compo = MeasureReportGroupPopulationComponent()
                        compo.code = measureCodeableConcept
                        try {
                            compo.count = it.answer.toInt()
                        } catch (e: Exception) {
                            compo.count = 0
                        }

                        if (it.linkId == WEEK_ENDING_DATE) {
                            val date = SimpleDateFormat(
                                "yyyy-MM-dd", Locale.getDefault()
                            ).parse(it.answer)
                            if (date != null) {
                                measure.date = date
                            }

                        }
                        compo.id = it.linkId
                        // check if the linkId is not in the excluded list and add to measure

                        when (case) {
                            CaseSlugs.MPOX_TALLY_SHEET -> {
                                if (!ALL_MPOX_LINK_IDS.contains(it.linkId)) {
                                    measure.groupFirstRep.addPopulation(compo)
                                }
                            }

                            else -> {
                                if (!ALL_LINK_IDS.contains(it.linkId)) {
                                    measure.groupFirstRep.addPopulation(compo)
                                }
                            }
                        }
                        val obs = qh.codingQuestionnaire(
                            it.linkId, it.text, it.answer
                        )

                        createResource(
                            obs, subjectReference, encounterReference, context, extractedAnswers
                        )
                    }
                    when (case) {
                        CaseSlugs.MOH_505 -> {
                            fhirEngine.create(measure)
                        }

                        CaseSlugs.MPOX_TALLY_SHEET -> {
                            val latitude = FormatterClass().getSharedPref("latitude", context)
                            val longitude = FormatterClass().getSharedPref("longitude", context)

                            measure.addIdentifier(
                                QuestionnaireHelper().createFullFhirIdentifier(
                                    codeData = FhirSystems.GEO_LOCATION,
                                    valueData = "lat:${latitude},lon:${longitude}",
                                    systemData = FhirSystems.GEO_LOCATION_DETAILS,
                                    displayData = "Latitude: $latitude, Longitude: $longitude"
                                )
                            )
                            fhirEngine.create(measure)
                        }
                    }
                    if (screened != null) {
                        // Screened person -> VHF case: switch identity now that the case is saved.
                        fhirEngine.update(patient)
                        CaseIdentifierIndex.put(patient)
                        FormatterClass().deleteSharedPref(VhfScreened.PREF_SOURCE, context)
                        Timber.tag(SCREENED_LOG).d(
                            "Patient %s is now a VHF case: encounter=%s identifiers=%s county=%s active=%s",
                            patient.logicalId, encounterId,
                            patient.identifier.map { "${it.system}=${it.value}" },
                            patient.addressFirstRep.city, patient.active,
                        )
                    }
                    lastSavedCase = patientId to encounterId
                    PersistResult.SAVED
                } catch (e: Exception) {
                    Timber.tag("TAG").e(e, "Error experienced ${e.message}")
                    if (screened != null) {
                        Timber.tag(SCREENED_LOG).e(e, "Saving the case of screened %s failed", screened.logicalId)
                    }
                    PersistResult.FAILED
                }
            }
        } finally {
            activeExtraTags = emptyList()
        }
    }

    /** Validates against the form and logs which questions failed (linkId: message). */
    private suspend fun hasValidationErrors(questionnaireResponse: QuestionnaireResponse): Boolean {
        val failures = QuestionnaireResponseValidator.validateQuestionnaireResponse(
            questionnaire,
            questionnaireResponse,
            getApplication(),
        ).mapNotNull { (linkId, results) ->
            results.filterIsInstance<Invalid>().takeIf { it.isNotEmpty() }
                ?.let { "$linkId: ${it.joinToString(" | ") { invalid -> invalid.getSingleStringValidationMessage() }}" }
        }
        if (failures.isNotEmpty()) {
            Timber.tag("SyntheticData").w("Validation failed: ${failures.joinToString("; ")}")
        }
        return failures.isNotEmpty()
    }

    /** Tags added to every resource of the persist call in progress (synthetic data marker). */
    private var activeExtraTags: List<Coding> = emptyList()

    /** Per-save cache: resolveFacilityInfo was otherwise re-run (a LIKE search) for every resource. */
    private val facilityInfoCache = HashMap<String, FacilityInfo?>()

    private fun withMetaTags(resource: org.hl7.fhir.r4.model.Resource) {
        if (activeExtraTags.isEmpty()) return
        // Meta.tag may be an immutable listOf(...) — always rebuild the list.
        resource.meta.tag = resource.meta.tag + activeExtraTags.map { it.copy() }
    }

    private suspend fun sourceExtension(
        resource: String,
        context: Context,
        extractedAnswers: List<QuestionnaireAnswer>
    ): Extension {
        // Resolve facility info asynchronously
        val info = resolveFacilityInfo(context, extractedAnswers)
            ?: FacilityInfo(
                name = "Unknown Facility",
                code = "unknown"
            )

        // Build and return Extension with correct data
        return Extension().apply {
            url = FhirSystems.managingLocation(resource)
            setValue(
                Reference().apply {
                    reference = "Location/${info.code}"
                    display = info.name
                }
            )
        }
    }


    suspend fun resolveFacilityInfo(
        context: Context,
        extractedAnswers: List<QuestionnaireAnswer>,

        ): FacilityInfo? {

        val formatter = FormatterClass()
        val storedRole = formatter.getSharedPref("practitionerRole", context)
        val userRole = UserRole.fromAny(storedRole ?: "")

        // Must match the facility linkId of the form variant chosen via UserRole.formRole
        val facility = FormFields.ReportingSite.FACILITY
        val facilityLink = when (userRole?.scope) {
            LocationLevel.COUNTY -> facility + FormFields.ReportingSite.SUFFIX_COUNTY
            LocationLevel.SUB_COUNTY, LocationLevel.WARD -> facility + FormFields.ReportingSite.SUFFIX_SUB_COUNTY
            LocationLevel.NATIONAL -> facility + FormFields.ReportingSite.SUFFIX_NATIONAL
            LocationLevel.FACILITY, null -> facility
        }

        val facilityEntry = extractedAnswers.find { it.linkId == facilityLink }
            ?: return null

        val cacheKey = "$facilityLink|${facilityEntry.answer}"
        if (facilityInfoCache.containsKey(cacheKey)) return facilityInfoCache[cacheKey]
        val info = lookupFacilityInfo(facilityEntry)
        facilityInfoCache[cacheKey] = info
        return info
    }

    private suspend fun lookupFacilityInfo(facilityEntry: QuestionnaireAnswer): FacilityInfo? {
        val results = fhirEngine.search<Location> {
            filter(Location.NAME, {
                modifier = StringFilterModifier.CONTAINS
                value = facilityEntry.answer
            })
        }

        if (results.isEmpty()) return null

        return FacilityInfo(
            name = facilityEntry.answer,
            code = results.first().resource.idPart
        )
    }

    private suspend fun sourceMetaTag(
        resource: String,
        facility: String?,
        context: Context,
        extractedAnswers: List<QuestionnaireAnswer>,

        ): Coding {
        val info = resolveFacilityInfo(context, extractedAnswers)
            ?: FacilityInfo(
                name = "Unknown Facility",
                code = "unknown"
            )
        return Coding().apply {
            system = FhirSystems.managingLocation(resource)
            code = "Location/${info.code}"
            display = info.name
        }
    }

    /** A standard FHIR R4 Specimen (see [StandardSpecimen]) linked to the case Encounter. */
    private suspend fun createSpecimenResource(
        linkId: String,
        dateAnswer: String?,
        type: String,
        subjectReference: Reference,
        encounterReference: Reference,
        notes: List<String>,
        context: Context,
        extractedAnswers: List<QuestionnaireAnswer>
    ) {
        try {
            val specimen = StandardSpecimen.build(
                sourceLinkId = linkId,
                specimenType = type,
                collected = dateAnswer,
                subjectRef = subjectReference,
                encounterRef = encounterReference,
                notes = notes,
            )
            specimen.id = generateUuid()
            val facility = FormatterClass().getSharedPref("facility", context)
            specimen.meta = Meta().apply {
                tag = listOf(
                    sourceMetaTag("specimen", facility, context, extractedAnswers)
                )
            }
            specimen.addExtension(sourceExtension("specimen", context, extractedAnswers))
            withMetaTags(specimen)
            fhirEngine.create(specimen)
        } catch (e: Exception) {
            Timber.tag("SPECIMEN").e(e, "Could not save the %s specimen", type)
        }
    }


    private suspend fun createResource(
        obs: Observation,
        subjectReference: Reference,
        encounterReference: Reference,
        context: Context,
        extractedAnswers: List<QuestionnaireAnswer>
    ) {
        try {
            val practitioner = FormatterClass().getSharedPref("fhirPractitionerId", context)
            obs.id = generateUuid()
            obs.subject = subjectReference
            obs.encounter = encounterReference
            if (practitioner != null) {
                obs.performerFirstRep.reference = "Practitioner/$practitioner"
            }
            obs.issued = Date()
            val facility = FormatterClass().getSharedPref("facility", context)
            obs.meta = Meta().apply {
                tag = listOf(
                    sourceMetaTag("observation", facility, context, extractedAnswers)
                )
            }
            obs.addExtension(
                sourceExtension(
                    "observation",
                    context,
                    extractedAnswers
                )
            )
            withMetaTags(obs)
            fhirEngine.create(FhirConformance.ensureRequired(obs))

        } catch (e: Exception) {
            Timber.tag("SavePatient").e(e, "Error saving patient")
        }
    }

    /** Resolves the case's county / sub-county and writes them as the patient's address. */
    private suspend fun applyResidence(
        patient: Patient,
        answers: List<QuestionnaireAnswer>,
        context: Context,
    ): Pair<String, String> {
        val county = resolveLocationEntry(answers, isCounty = true, context = context)?.answer.orEmpty()
        val subCounty = resolveLocationEntry(answers, isCounty = false, context = context)?.answer.orEmpty()
        PatientMapper.applyResidence(patient, county, subCounty)
        return county to subCounty
    }

    /**
     * Finds the county / sub-county answer used for EPID numbers and patient address.
     *
     * Forms store these under different linkIds: add-case style forms use `a4-county` /
     * `a3-sub-county`, while location-widget forms (rumor tracking, MOH 505, tally sheet, ...)
     * use `294367770999` / `819946803642` with a `_county`, `_sub_county` or `_national` suffix
     * depending on the user's role. Falls back to the logged-in user's assigned location so the
     * EPID never ends up as KEN-XXX-XXX.
     */
    private suspend fun resolveLocationEntry(
        answers: List<QuestionnaireAnswer>,
        isCounty: Boolean,
        context: Context
    ): QuestionnaireAnswer? {
        val candidates = if (isCounty) {
            listOf(FormFields.Residence.COUNTY) + FormFields.ReportingSite.COUNTY_VARIANTS
        } else {
            listOf(FormFields.Residence.SUB_COUNTY) + FormFields.ReportingSite.SUB_COUNTY_VARIANTS
        }
        val base = candidates[1]
        val entry = candidates.firstNotNullOfOrNull { id ->
            answers.firstOrNull { it.linkId == id && it.answer.isNotBlank() }
        }

        if (entry != null) {
            // Reference answers without a display come through as "Location/<id>"
            if (entry.answer.startsWith("Location/")) {
                val name = try {
                    fhirEngine.get<Location>(entry.answer.removePrefix("Location/")).name
                } catch (e: Exception) {
                    null
                }
                if (!name.isNullOrBlank()) return entry.copy(answer = name)
            } else {
                return entry
            }
        }

        val assigned = formatter.getSharedPref(if (isCounty) "countyName" else "subCountyName", context)
        return if (!assigned.isNullOrBlank()) QuestionnaireAnswer(base, "", assigned) else entry
    }

    private fun extractStructuredAnswers(
        questionnaireResponse: QuestionnaireResponse,
        questionnaireResponseString: String
    ): List<QuestionnaireAnswer> {
        val fromModel = extractStructuredAnswersFromItems(questionnaireResponse.item)
        if (fromModel.isNotEmpty()) {
            return fromModel
        }

        if (questionnaireResponseString.isBlank()) {
            return emptyList()
        }

        return try {
            formatter.extractStructuredAnswersOnlyFromItems(JSONObject(questionnaireResponseString))
        } catch (e: Exception) {
            Timber.tag("AddClientViewModel").e(e, "Failed to parse questionnaire response JSON")
            emptyList()
        }
    }

    private fun extractStructuredAnswersFromItems(
        items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>
    ): List<QuestionnaireAnswer> =
        items.flatMap { item ->
            val values = item.answer.mapNotNull { extractAnswerValue(it) }
            val own = if (values.isNotEmpty()) {
                listOf(QuestionnaireAnswer(item.linkId ?: "", item.text ?: "", values.joinToString(", ")))
            } else emptyList()
            own + extractStructuredAnswersFromItems(item.item)
        }

    private fun extractAnswerValue(
        answer: QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent
    ): String? {
        val value = answer.value ?: return null
        return when (value) {
            is StringType -> value.value
            is IntegerType -> value.value?.toString()
            is DateType -> value.valueAsString
            is DateTimeType -> value.valueAsString
            is BooleanType -> value.booleanValue()?.toString()
            is DecimalType -> value.value?.toString()
            is Coding -> value.display ?: value.code
            is Reference -> value.display ?: value.reference
            else -> null
        }?.takeIf { it.isNotBlank() }
    }


    /** Replaces the form (e.g. with choices filled in at runtime) before it is shown. */
    fun useQuestionnaireJson(json: String) {
        _questionnaireJson = json
    }

    private fun fetchQuestionnaireJson(): String {
        _questionnaireJson?.let {
            return it
        }
        _questionnaireJson =
            getApplication<Application>().assets.open(state[QUESTIONNAIRE_FILE_PATH_KEY]!!)
                .bufferedReader().use { it.readText() }
        return _questionnaireJson!!
    }

    private fun generateUuid(): String {
        return UUID.randomUUID().toString()
    }
}
