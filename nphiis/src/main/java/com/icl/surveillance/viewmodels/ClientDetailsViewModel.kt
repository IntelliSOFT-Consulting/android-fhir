package com.icl.surveillance.viewmodels

import android.app.Application
import android.content.res.Resources
import android.icu.text.DateFormat
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.SearchResult
import com.google.android.fhir.datacapture.extensions.asStringValue
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.search.Order
import com.google.android.fhir.search.revInclude
import com.google.android.fhir.search.search
import com.icl.surveillance.R
import com.icl.surveillance.ui.patients.PatientListViewModel
import com.icl.surveillance.ui.patients.PatientListViewModel.ClinicalData
import com.icl.surveillance.ui.patients.PatientListViewModel.ContactResults
import com.icl.surveillance.ui.patients.PatientListViewModel.PersonDetails
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Condition
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType
import kotlin.String

class ClientDetailsViewModel(
    application: Application,
    private val fhirEngine: FhirEngine,
    private val patientId: String,
) : AndroidViewModel(application) {
    val livePatientData = MutableLiveData<List<PatientListViewModel.PatientDetailData>>()
    val livecaseData = MutableLiveData<PatientListViewModel.CaseDetailData>()
    val liveIdentificationData = MutableLiveData<PersonDetails>()
    val liveSummaryData = MutableLiveData<PatientListViewModel.CaseDetailSummaryData>()
    val liveCaseData = MutableLiveData<PatientListViewModel.CaseId>()
    val liveDiseaseData = MutableLiveData<List<PatientListViewModel.CaseDiseaseData>>()
    val liveLabData = MutableLiveData<List<PatientListViewModel.CaseLabResultsData>>()
    val currentLiveLabData = MutableLiveData<List<PatientListViewModel.LabResults>>()
    val liveLinkedData = MutableLiveData<List<ContactResults>>()
    val liveClinicalData = MutableLiveData<ClinicalData>()
    var hasQuestionnaireResponse: Boolean = false


    /** Emits list of [PatientDetailData]. */
    fun getPatientDetailData(category: String, parent: String?) {
        viewModelScope.launch {
            livePatientData.value = getPatientDetailDataModel(category, parent)
        }
    }

    private suspend fun getPatientDetailDataModel(
        category: String,
        parent: String?
    ): List<PatientListViewModel.PatientDetailData> {
        val searchResult =
            fhirEngine.search<Patient> {
                filter(Resource.RES_ID, { value = of(patientId) })
                revInclude<Observation>(Observation.SUBJECT)
                revInclude<Condition>(Condition.SUBJECT)
                revInclude<Encounter>(Encounter.SUBJECT)
            }
        val data = mutableListOf<PatientListViewModel.PatientDetailData>()

        searchResult.first().let {
            it.revIncluded?.get(ResourceType.Encounter to Encounter.SUBJECT.paramName)?.let {
            }
        }

        return data
    }

    private fun MutableList<PatientListViewModel.EncounterItem>.addEncounterData(
        datas: List<Encounter>,
        category: String,
        parent: String?
    ) {
        if (datas.isNotEmpty()) {

            datas
                .filter { encounter ->
                    if (parent != null) {
                        encounter.partOf.display == parent
                    }
                    encounter.reasonCode.any { reason -> reason.coding.any { it.code == category } }
                }
                .take(100)
                .map { createEncounterItem(it, getApplication<Application>().resources) }
                .mapIndexed { index, data ->
                    PatientListViewModel.EncounterItem(
                        id = data.id, reasonCode = data.reasonCode, status = data.status
                    )
                }
                .let { addAll(it) }
        }
    }

    private val LocalDate.localizedString: String
        get() {
            val date = Date.from(atStartOfDay(ZoneId.systemDefault())?.toInstant())
            return if (isAndroidIcuSupported()) {
                DateFormat.getDateInstance(DateFormat.DEFAULT).format(date)
            } else {
                SimpleDateFormat.getDateInstance(DateFormat.DEFAULT, Locale.getDefault())
                    .format(date)
            }
        }

    // Android ICU is supported API level 24 onwards.
    private fun isAndroidIcuSupported() = true

    private fun getString(resId: Int) = getApplication<Application>().resources.getString(resId)

    private suspend fun loadEncounter(patientId: String): List<Encounter> {
        return fhirEngine
            .search<Encounter> { filter(Encounter.SUBJECT, { value = "Patient/$patientId" }) }
            .map { it.resource }
    }

    suspend fun checkIfResourceHasQuestionnaireResponse(patientId: String): String =
        withContext(Dispatchers.IO) {
            val searchResult = fhirEngine.search<QuestionnaireResponse> {
                filter(QuestionnaireResponse.SUBJECT, { value = "Patient/$patientId" })
            }
            val logicalId = searchResult.firstOrNull()?.resource?.logicalId ?: ""
            println("Logical Id $logicalId")
            logicalId
        }

    private suspend fun epidSummary(slug: String): PatientListViewModel.CaseId {
        var logicalId = ""
        var epid = ""

        val searchResult =
            fhirEngine.search<Patient> { filter(Resource.RES_ID, { value = of(patientId) }) }
        searchResult.first().let { data ->
            val matchingIdentifier = data.resource.identifier.find {
                it.system == slug
            }
            val epidIdenfifier =
                data.resource.identifier.find { it.type.codingFirstRep.code == "EPID" }

            if (epidIdenfifier != null) {
                epid = epidIdenfifier.value
            }
            if (matchingIdentifier != null) {
                val logicalId = matchingIdentifier.value
                val obs =
                    fhirEngine.search<Observation> {
                        filter(
                            Observation.ENCOUNTER,
                            { value = "Encounter/${logicalId}" })
                    }.take(500)


                if (epid.isEmpty()) {
                    epid = obs.firstOrNull { it.resource.code.codingFirstRep.code == "EPID" }
                        ?.resource
                        ?.value
                        ?.asStringValue() ?: ""
                }
            }
        }
        return PatientListViewModel.CaseId(
            patientId = logicalId,
            eNo = epid,
        )
    }

    private suspend fun getPatientInfoSummary(slug: String): PatientListViewModel.CaseDetailSummaryData {
        var logicalId = ""
        var name = ""
        var sex = ""
        var dob = ""
        var encounterId = ""
        var epidNo = ""
        var observations = mutableListOf<PatientListViewModel.ObservationItem>()

        println("Dealing with the current Slug View Model $slug")
        val searchResult =
            fhirEngine.search<Patient> { filter(Resource.RES_ID, { value = of(patientId) }) }
        if (searchResult.isNotEmpty()) {
            searchResult.first().let {
                logicalId = it.resource.logicalId
                name =
                    if (it.resource.hasName()) {
                        "${it.resource.name[0].givenAsSingleString} ${it.resource.name[0].family} "
                    } else ""
                sex = if (it.resource.hasGenderElement()) it.resource.gender.display else ""
                dob =
                    if (it.resource.hasBirthDateElement())
                        if (it.resource.birthDateElement.hasValue())
                            it.resource.birthDateElement.valueAsString
                        else ""
                    else ""


                val matchingIdentifier = it.resource.identifier.find {
                    it.system == slug
                }


                val epidIdenfifier =
                    it.resource.identifier.find { it.type.codingFirstRep.code == "EPID" }

                if (epidIdenfifier != null) {
                    epidNo = epidIdenfifier.value
                }
                if (matchingIdentifier != null) {
                    encounterId = matchingIdentifier.value


                    fhirEngine.search<Observation> {
                        filter(
                            Observation.ENCOUNTER,
                            { value = "Encounter/${matchingIdentifier.value}" })
                    }
                        .map { ob ->


                            val value =
                                if (ob.resource.hasValueQuantity()) {
                                    ob.resource.valueQuantity.value.toString()
                                } else if (ob.resource.hasValueCodeableConcept()) {
                                    ob.resource.valueCodeableConcept.coding.firstOrNull()?.display
                                        ?: ""
                                } else if (ob.resource.hasValueStringType()) {
                                    ob.resource.valueStringType.valueAsString
                                } else {
                                    ""
                                }

                            val created =
                                if (ob.resource.hasIssued()) ob.resource.issuedElement.value.toString() else ""
                            val item = PatientListViewModel.ObservationItem(
                                id = ob.resource.logicalId,
                                code = ob.resource.code.codingFirstRep.code,
                                value = value,
                                created = created
                            )
                            observations.add(item)
                        }
                    val obs =
                        fhirEngine.search<Observation> {
                            filter(
                                Observation.ENCOUNTER,
                                { value = "Encounter/${matchingIdentifier.value}" })
                        }.take(500)

                    if (epidNo.isEmpty()) {
                        epidNo = obs.firstOrNull { it.resource.code.codingFirstRep.code == "EPID" }
                            ?.resource
                            ?.value
                            ?.asStringValue() ?: ""
                    }
                }

            }
        }
        return PatientListViewModel.CaseDetailSummaryData(
            logicalId = logicalId,
            encounterId = encounterId,
            name = name,
            dob = dob,
            sex = sex,
            observations = observations,
            epidNo = epidNo
        )
    }

    private suspend fun getClinicalInfoCard(slug: String): ClinicalData {
        val searchResult =
            fhirEngine.search<Patient> { filter(Resource.RES_ID, { value = of(patientId) }) }
        var logicalId = ""
        var onset = ""
        var symptoms = mutableListOf<String>()
        var rashDate = ""
        var rashType = ""
        var otherType = ""
        var vaccinated = ""
        var doses = ""
        var thirtyDays = ""
        var lastVaccination = ""
        var homeVisit = ""
        var homeDateVisit = ""
        var caseEpilinked = ""
        var epiName = ""
        var epiEPID = ""
        searchResult.first().let {
            logicalId = it.resource.logicalId
            val matchingIdentifier = it.resource.identifier.find {
                it.system == slug
            }
            if (matchingIdentifier != null) {
                val obs =
                    fhirEngine.search<Observation> {
                        filter(
                            Observation.ENCOUNTER,
                            { value = "Encounter/${matchingIdentifier.value}" })
                    }.take(500)

                obs.forEach {
                    println("Obs Retrieved: **** ${it.resource.code.codingFirstRep.code} Value ${it.resource.valueStringType}")
                }
                vaccinated = generateResponse(obs, "517772812375")
                doses = generateResponse(obs, "886125589225")
                thirtyDays = generateResponse(obs, "308128177300")
                lastVaccination = generateResponse(obs, "544290619304")
                homeVisit = generateResponse(obs, "207408507040")
                homeDateVisit = generateResponse(obs, "566661890668")
                caseEpilinked = generateResponse(obs, "865158268604")
                epiName = generateResponse(obs, "714692748467")
                epiEPID = generateResponse(obs, "512392582851")
                onset =
                    obs.firstOrNull { it.resource.code.codingFirstRep.code == "728034137219" }
                        ?.resource
                        ?.value
                        ?.asStringValue() ?: ""


                rashType = generateResponse(obs, "704922081985")
                otherType = generateResponse(obs, "679340979918")
                rashDate =
                    obs.firstOrNull { it.resource.code.codingFirstRep.code == "576528567552" }
                        ?.resource
                        ?.value
                        ?.asStringValue() ?: ""
                val par =
                    obs.firstOrNull { it.resource.code.codingFirstRep.code == "745196148424" }
                        ?.resource
                        ?.value
                        ?.asStringValue() ?: ""
                if (par.isNotEmpty()) {
                    symptoms.add(par)
                }
                val conju =
                    obs.firstOrNull { it.resource.code.codingFirstRep.code == "178038943620" }
                        ?.resource
                        ?.value
                        ?.asStringValue() ?: ""
                if (conju.isNotEmpty()) {
                    symptoms.add(conju)
                }
                val cory =
                    obs.firstOrNull { it.resource.code.codingFirstRep.code == "317122026276" }
                        ?.resource
                        ?.value
                        ?.asStringValue() ?: ""
                if (cory.isNotEmpty()) {
                    symptoms.add(cory)
                }
                val cough =
                    obs.firstOrNull { it.resource.code.codingFirstRep.code == "203174333568" }
                        ?.resource
                        ?.value
                        ?.asStringValue() ?: ""
                if (cough.isNotEmpty()) {
                    symptoms.add(cough)
                }
                val rash =
                    obs.firstOrNull { it.resource.code.codingFirstRep.code == "547137374562" }
                        ?.resource
                        ?.value
                        ?.asStringValue() ?: ""
                if (rash.isNotEmpty()) {
                    symptoms.add(rash)
                }
                val fever =
                    obs.firstOrNull { it.resource.code.codingFirstRep.code == "848847022926" }
                        ?.resource
                        ?.value
                        ?.asStringValue() ?: ""
                if (fever.isNotEmpty()) {
                    symptoms.add(fever)
                }
            }
        }
        return ClinicalData(
            onset = onset,
            symptoms = symptoms,
            rashDate = rashDate,
            rashType = rashType,
            otherType = otherType,
            vaccinated = vaccinated,
            doses = doses,
            thirtyDays = thirtyDays,
            lastVaccination = lastVaccination,
            homeVisit = homeVisit,
            homeDateVisit = homeDateVisit,
            caseEpilinked = caseEpilinked,
            epiName = epiName,
            epiEPID = epiEPID,
        )
    }

    private suspend fun getPatientIdentificationCard(slug: String): PersonDetails {
        val searchResult =
            fhirEngine.search<Patient> { filter(Resource.RES_ID, { value = of(patientId) }) }
        var logicalId = ""
        var name = ""
        var sex = ""
        var dob = ""
        var subCounty = ""
        var county = ""
        var residence = ""
        var parent = ""
        var houseNo = ""
        var neighbour = ""
        var street = ""
        var town = ""
        var parentPhone = ""
        searchResult.first().let {
            logicalId = it.resource.logicalId
            name =
                if (it.resource.hasName()) {
                    "${it.resource.name[0].givenAsSingleString} ${it.resource.name[0].family} "
                } else ""

            sex = if (it.resource.hasGenderElement()) it.resource.gender.display else ""
            dob =
                if (it.resource.hasBirthDateElement())
                    if (it.resource.birthDateElement.hasValue())
                        it.resource.birthDateElement.valueAsString
                    else ""
                else ""
            parentPhone =
                if (it.resource.hasContact())
                    if (it.resource.contactFirstRep.hasTelecom())
                        if (it.resource.contactFirstRep.telecomFirstRep.hasValue())
                            it.resource.contactFirstRep.telecomFirstRep.value
                        else ""
                    else ""
                else ""

            val cCounty =
                if (it.resource.hasAddress()) if (it.resource.addressFirstRep.hasCity()) it.resource.addressFirstRep.city else "" else ""
            val cSubCounty =
                if (it.resource.hasAddress()) if (it.resource.addressFirstRep.hasState()) it.resource.addressFirstRep.state else "" else ""

            val addressLines = if (
                it.resource.hasContact() &&
                it.resource.contactFirstRep.hasAddress() &&
                it.resource.contactFirstRep.address.hasLine()
            ) {
                it.resource.contactFirstRep.address.line
            } else {
                emptyList()
            }

            houseNo = if (addressLines.isNotEmpty()) addressLines[0].value else ""
            neighbour = if (addressLines.size > 1) addressLines[1].value else ""
            street = if (addressLines.size > 2) addressLines[2].value else ""
            town = if (addressLines.size > 3) addressLines[3].value else ""


            val matchingIdentifier = it.resource.identifier.find {
                it.system == slug
            }
            if (matchingIdentifier != null) {
                val obs =
                    fhirEngine.search<Observation> {
                        filter(
                            Observation.ENCOUNTER,
                            { value = "Encounter/${matchingIdentifier.value}" })
                    }.take(500)


                residence =
                    obs.firstOrNull { it.resource.code.codingFirstRep.code == "407548372315" }
                        ?.resource
                        ?.value
                        ?.asStringValue() ?: ""
                parent =
                    obs.firstOrNull { it.resource.code.codingFirstRep.code == "856448027666" }
                        ?.resource
                        ?.value
                        ?.asStringValue() ?: ""
                houseNo =
                    obs.firstOrNull { it.resource.code.codingFirstRep.code == "242811643559" }
                        ?.resource
                        ?.value
                        ?.asStringValue() ?: ""


                if (cCounty.isEmpty()) {
                    county =
                        obs.firstOrNull { it.resource.code.codingFirstRep.code == "a4-county" }
                            ?.resource
                            ?.value
                            ?.asStringValue() ?: ""
                } else county = cCounty
                if (cSubCounty.isEmpty()) {
                    subCounty =
                        obs.firstOrNull { it.resource.code.codingFirstRep.code == "a3-sub-county" }
                            ?.resource
                            ?.value
                            ?.asStringValue() ?: ""
                } else subCounty = cSubCounty
                if (parentPhone.isEmpty()) {
                    obs.firstOrNull { it.resource.code.codingFirstRep.code == "754217593839" }
                        ?.resource
                        ?.value
                        ?.asStringValue() ?: ""
                }
            }
        }
        return PersonDetails(
            name = name,
            sex = sex,
            dob = dob,
            residence = residence,
            parent = parent,
            houseNo = houseNo,
            neighbour = neighbour,
            street = street,
            town = town,
            subCountyName = subCounty,
            countyName = county,
            parentPhone = parentPhone
        )
    }

    private suspend fun getPatientInfoCard(slug: String): PatientListViewModel.CaseDetailData {
        val searchResult =
            fhirEngine.search<Patient> { filter(Resource.RES_ID, { value = of(patientId) }) }
        var logicalId = ""

        var name = ""
        var sex = ""
        var dob = ""
        var epid = ""
        var county = ""
        var subCounty = ""
        var onset = ""
        var facility = ""
        var type = ""
        var disease = ""
        var dateFirstSeen = ""
        var dateSubCountyNotified = ""
        var hospitalized = ""
        var ipNo = ""
        var diagnosis = ""
        var diagnosisMeans = ""
        var diagnosisMeansOther = ""
        var wasPatientVaccinated = ""
        var noOfDoses = ""
        var twoMonthsVaccination = ""
        var patientStatus = ""
        var admissionDate = ""
        var vaccineDate = ""
        var residence = ""
        var parent = ""
        var houseno = ""
        var parentPhone = ""
        var countyName = ""
        var subCountyName = ""
        var neighbour = ""
        var street = ""
        var town = ""

        var clinicalSymptoms = ""
        var rashDate = ""
        var rashType = ""
        var patientVaccinated = ""
        var patientDoses = ""
        var vaccineDateThirtyDays = ""
        var lastDoseDate = ""
        var homeVisited = ""
        var homeVisitedDate = ""
        var epiLinked = ""
        var specimen = ""
        var noWhy = ""
        var collectionDate = ""
        var specimenType = ""
        var specimenTypeOther = ""
        var dateSent = ""
        var labName = ""
        var patientOutcome = ""
        var sampleCollected = ""
        var inPatientOutPatient = ""
        var epiLinkedName = ""
        var epiLinkedNumber = ""

        var bloodSpecimenCollected = ""
        var noWhyBlood = ""
        var dateBloodSpecimen = ""
        var urineSpecimenCollected = ""
        var noWhyUrine = ""
        var dateUrineSpecimen = ""
        var respiratorySampleCollected = ""
        var dateRespiratorySample = ""
        var noWhyRespiratory = ""
        var otherSpecimenCollected = ""
        var specifyOtherSpecimen = ""
        var dateOtherSpecimen = ""
        var dateSpecimenSentToLab = ""

        var country = ""
        var yearOfReporting = ""
        var healthFacility = ""
        var typeOfHealthFacility = ""
        var subcountyOfFacility = ""
        var countyOfFacility = ""

        searchResult.first().let {
            logicalId = it.resource.logicalId
            name =
                if (it.resource.hasName()) {
                    // display name in order as fname, then others
                    "${it.resource.name[0].givenAsSingleString} ${it.resource.name[0].family} "
                } else ""
            sex = if (it.resource.hasGenderElement()) it.resource.gender.display else ""
            dob =
                if (it.resource.hasBirthDateElement())
                    if (it.resource.birthDateElement.hasValue())
                        it.resource.birthDateElement.valueAsString
                    else ""
                else ""
            val matchingIdentifier = it.resource.identifier.find {
                it.system == slug
            }
            if (matchingIdentifier != null) {
                val obs =
                    fhirEngine.search<Observation> {
                        filter(
                            Observation.ENCOUNTER,
                            { value = "Encounter/${matchingIdentifier.value}" })
                    }

                // SECTION SITE REPORTING

                subCounty = generateResponse(obs, "a3-sub-county")
                county = generateResponse(obs, "a4-county")
                country = generateResponse(obs, "602440958701")
                yearOfReporting = generateResponse(obs, "596681097855")
                healthFacility = generateResponse(obs, "185989158723")
                typeOfHealthFacility = generateResponse(obs, "438862163919")
                subcountyOfFacility = generateResponse(obs, "819946803642")
                countyOfFacility = generateResponse(obs, "294367770999")

                residence = generateResponse(obs, "407548372315")
                epid = generateResponse(obs, "EPID")
                facility = generateResponse(obs, "185989158723")
                type = generateResponse(obs, "438862163919")
                disease = generateResponse(obs, "970724948648")


                // Lab Information
                specimen = generateResponse(obs, "918495737998")
                noWhy = generateResponse(obs, "752178052107")
                collectionDate = generateResponse(obs, "896246858334")
                specimenType = generateResponse(obs, "354119451212")
                specimenTypeOther = generateResponse(obs, "834246950025")
                dateSent = generateResponse(obs, "718251724172")
                labName = generateResponse(obs, "868259191903")

                bloodSpecimenCollected = generateResponse(obs, "918495737998")
                noWhyBlood = generateResponse(obs, "752178052107")
                dateBloodSpecimen = generateResponse(obs, "8962468583341")
                urineSpecimenCollected = generateResponse(obs, "433195098993")
                noWhyUrine = generateResponse(obs, "329542687751")
                dateUrineSpecimen = generateResponse(obs, "915783129731")
                respiratorySampleCollected = generateResponse(obs, "270749570400")
                dateRespiratorySample = generateResponse(obs, "183705125522")
                noWhyRespiratory = generateResponse(obs, "427973727975")
                otherSpecimenCollected = generateResponse(obs, "258912872921")
                specifyOtherSpecimen = generateResponse(obs, "340507649387")
                dateOtherSpecimen = generateResponse(obs, "699353598445")
                dateSpecimenSentToLab = generateResponse(obs, "718251724172")


                // Case Details

                onset = generateResponse(obs, "728034137219")
                clinicalSymptoms += generateResponse(obs, "848847022926") + ", "
                clinicalSymptoms += generateResponse(obs, "547137374562") + ", "
                clinicalSymptoms += generateResponse(obs, "203174333568") + ", "
                clinicalSymptoms += generateResponse(obs, "317122026276") + ", "
                clinicalSymptoms += generateResponse(obs, "178038943620") + ", "
                rashDate = generateResponse(obs, "576528567552")
                rashType = generateResponse(obs, "704922081985")
                patientVaccinated = generateResponse(obs, "517772812375")
                patientDoses = generateResponse(obs, "886125589225")
                vaccineDateThirtyDays = generateResponse(obs, "308128177300")
                lastDoseDate = generateResponse(obs, "544290619304")
                homeVisited = generateResponse(obs, "207408507040")
                homeVisitedDate = generateResponse(obs, "566661890668")
                epiLinked = generateResponse(obs, "865158268604")

                epiLinkedName = generateResponse(obs, "714692748467")
                epiLinkedNumber = generateResponse(obs, "512392582851")

                //        End of Case details

                /** Section C* */
                dateFirstSeen = generateResponse(obs, "554231819382")
                dateSubCountyNotified = generateResponse(obs, "554510652480")
                hospitalized = generateResponse(obs, "c4-hospitalized")
                patientStatus = generateResponse(obs, "999822503482")
                admissionDate = generateResponse(obs, "340908984116")
                ipNo = generateResponse(obs, "755731625544")
                patientOutcome = generateResponse(obs, "508745697175")
                sampleCollected = generateResponse(obs, "602355761864")
                diagnosis = generateResponse(obs, "c6-diagnosis")
                diagnosisMeans = generateResponse(obs, "c7-means-of-diagnosis")
                diagnosisMeansOther = generateResponse(obs, "c7-other-specify")
                wasPatientVaccinated = generateResponse(obs, "c8a-vaccinated")
                noOfDoses = generateResponse(obs, "c8a-no-of-doses")
                twoMonthsVaccination = generateResponse(obs, "c8b-recent-vaccine")
                vaccineDate = generateResponse(obs, "c8b-date-of-vaccine")
                inPatientOutPatient = generateResponse(obs, "483042281962")
            }

            parent =
                if (it.resource.hasContact())
                    if (it.resource.contactFirstRep.hasName())
                        it.resource.contactFirstRep.name.givenAsSingleString
                    else ""
                else ""

            houseno =
                if (it.resource.hasContact())
                    if (it.resource.contactFirstRep.hasAddress())
                        it.resource.contactFirstRep.address.line[0].value.toString()
                    else ""
                else ""
            neighbour =
                if (it.resource.hasContact())
                    if (it.resource.contactFirstRep.hasAddress())
                        if (it.resource.contactFirstRep.address.line.size > 4) {
                            it.resource.contactFirstRep.address.line[1].value.toString()
                        } else {
                            ""
                        }
                    else ""
                else ""
            street =
                if (it.resource.hasContact())
                    if (it.resource.contactFirstRep.hasAddress())
                        if (it.resource.contactFirstRep.address.line.size > 2) {
                            it.resource.contactFirstRep.address.line[2].value.toString()
                        } else {
                            ""
                        }
                    else ""
                else ""

            town =
                if (it.resource.hasContact())
                    if (it.resource.contactFirstRep.hasAddress())
                        if (it.resource.contactFirstRep.address.line.size > 3) {
                            it.resource.contactFirstRep.address.line[3].value.toString()
                        } else {
                            ""
                        }
                    else ""
                else ""
            subCountyName =
                if (it.resource.hasContact())
                    if (it.resource.contactFirstRep.hasAddress())
                        if (it.resource.contactFirstRep.address.line.size > 4) {
                            it.resource.contactFirstRep.address.line[4].value.toString()
                        } else {
                            ""
                        }
                    else ""
                else ""
            countyName =
                if (it.resource.hasContact())
                    if (it.resource.contactFirstRep.hasAddress())
                        if (it.resource.contactFirstRep.address.line.size > 5) {
                            it.resource.contactFirstRep.address.line[5].value.toString()
                        } else {
                            ""
                        }
                    else ""
                else ""

            parentPhone =
                if (it.resource.hasContact())
                    if (it.resource.contactFirstRep.hasTelecom())
                        it.resource.contactFirstRep.telecomFirstRep.value.toString()
                    else ""
                else ""
        }

        return PatientListViewModel.CaseDetailData(
            epid = epid,
            onset = onset,
            logicalId = logicalId,

            //      Case Information

            clinicalSymptoms = clinicalSymptoms,
            rashDate = rashDate,
            rashType = rashType,
            patientVaccinated = patientVaccinated,
            patientDoses = patientDoses,
            vaccineDateThirtyDays = vaccineDateThirtyDays,
            lastDoseDate = lastDoseDate,
            homeVisited = homeVisited,
            homeVisitedDate = homeVisitedDate,
            epiLinked = epiLinked,

            //      End of Case

            //      SECTION A
            facility = facility,
            disease = disease,
            type = type,
            subCounty = subCounty,
            county = county,
            country = country,
            yearOfReporting = yearOfReporting,
            healthFacility = healthFacility,
            typeOfHealthFacility = typeOfHealthFacility,
            subcountyOfFacility = subcountyOfFacility,
            countyOfFacility = countyOfFacility,

            // SECTION B

            name = name,
            sex = sex,
            dob = dob,
            residence = residence,
            parent = parent,
            houseNo = houseno,
            neighbour = neighbour,
            street = street,
            town = town,
            subCountyName = subCountyName,
            countyName = countyName,
            parentPhone = parentPhone,

            //      SECTION C

            dateFirstSeen = dateFirstSeen,
            dateSubCountyNotified = dateSubCountyNotified,
            hospitalized = hospitalized,
            admissionDate = admissionDate,
            ipNo = ipNo,
            diagnosis = diagnosis,
            diagnosisMeans = diagnosisMeans,
            diagnosisMeansOther = diagnosisMeansOther,
            targetDisease = disease,
            wasPatientVaccinated = wasPatientVaccinated,
            noOfDoses = noOfDoses,
            twoMonthsVaccination = twoMonthsVaccination,
            vaccineDate = vaccineDate,
            patientStatus = patientStatus,
            patientOutcome = patientOutcome,
            sampleCollected = sampleCollected,
            inPatientOutPatient = inPatientOutPatient,

            // Lab Information
            specimen = specimen,
            noWhy = noWhy,
            collectionDate = collectionDate,
            specimenType = specimenType,
            specimenTypeOther = specimenTypeOther,
            dateSent = dateSent,
            labName = labName,

            bloodSpecimenCollected = bloodSpecimenCollected,
            noWhyBlood = noWhyBlood,
            dateBloodSpecimen = dateBloodSpecimen,
            urineSpecimenCollected = urineSpecimenCollected,
            noWhyUrine = noWhyUrine,
            dateUrineSpecimen = dateUrineSpecimen,
            respiratorySampleCollected = respiratorySampleCollected,
            dateRespiratorySample = dateRespiratorySample,
            noWhyRespiratory = noWhyRespiratory,
            otherSpecimenCollected = otherSpecimenCollected,
            specifyOtherSpecimen = specifyOtherSpecimen,
            dateOtherSpecimen = dateOtherSpecimen,
            dateSpecimenSentToLab = dateSpecimenSentToLab


        )
    }

    private fun generateResponse(obs: List<SearchResult<Observation>>, s: String): String {

        return obs.firstOrNull { it.resource.code.codingFirstRep.code == s }
            ?.resource
            ?.value
            ?.asStringValue() ?: ""
    }

    fun getClinicalInfo(slug: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val patientData = getClinicalInfoCard(slug)
            withContext(Dispatchers.Main) { liveClinicalData.value = patientData }
        }
    }

    fun getPatientInfo(slug: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val patientData = getPatientInfoCard(slug)
            withContext(Dispatchers.Main) { livecaseData.value = patientData }
        }
    }

    fun getPatientIdentification(slug: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val patientData = getPatientIdentificationCard(slug)
            withContext(Dispatchers.Main) { liveIdentificationData.value = patientData }
        }
    }

    fun getPatientInfoSummaryData(slug: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val patientData = getPatientInfoSummary(slug)
            withContext(Dispatchers.Main) { liveSummaryData.value = patientData }
        }
    }

    fun getEPIDNo(slug: String) {

        CoroutineScope(Dispatchers.IO).launch {
            val patientData = epidSummary(slug)
            withContext(Dispatchers.Main) { liveCaseData.value = patientData }
        }
    }

    fun getPatientDiseaseData(reason: String, parent: String, isCase: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            if (isCase) {
                val patientData = getPatientDiseaseDataInformation(reason, parent)
                withContext(Dispatchers.Main) { liveDiseaseData.value = patientData }
            } else {
                val patientData = getPatientLabDataInformation(reason, parent)
                withContext(Dispatchers.Main) { liveLabData.value = patientData }
            }
        }
    }

    fun getLinkedContacts(patientId: String) {
        CoroutineScope(Dispatchers.IO).launch {

            val patientData = getLinkedContactInformation(patientId)
            withContext(Dispatchers.Main) { liveLinkedData.value = patientData }

        }
    }

    fun getPatientResultsDiseaseData(reason: String, parent: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val patientData = getPatientLabDataDataInformation(reason, parent)
            withContext(Dispatchers.Main) { currentLiveLabData.value = patientData }
        }
    }

    private suspend fun getLinkedContactInformation(
        patientId: String
    ): List<ContactResults> {

        println("Dealing with Patient With ID $patientId")
        val patients: MutableList<ContactResults> = mutableListOf()
        fhirEngine
            .search<Patient> {
                filter(Patient.LINK, { value = "Patient/$patientId" })
            }.map { patient ->
                var data = ContactResults(
                    parentIdId = patientId,
                    childId = patient.resource.logicalId,
                    observations = emptyList(),
                    name = if (patient.resource.hasName()) patient.resource.nameFirstRep.nameAsSingleString else "N/A",
                    epid = if (patient.resource.hasIdentifier()) {
                        val identifier =
                            patient.resource.identifier.find { it.type.codingFirstRep.code == "EPID" }
                        if (identifier != null) {
                            identifier.value
                        } else {
                            ""
                        }
                    } else ""

                )
                fhirEngine
                    .search<Encounter> {
                        filter(
                            Encounter.SUBJECT,
                            { value = "Patient/${patient.resource.logicalId}" })
                        filter(
                            Encounter.REASON_CODE,
                            {
                                value = of(Coding().apply {
                                    code = "afp-contact-case-information"
                                })
                            })
                    }.map { enc ->

                        val observations: MutableList<PatientListViewModel.ObservationItem> =
                            mutableListOf()
                        fhirEngine.search<Observation> {
                            filter(
                                Observation.ENCOUNTER,
                                { value = "Encounter/${enc.resource.logicalId}" })
                        }.take(500)
                            .map { ob ->
                                val value =
                                    if (ob.resource.hasValueQuantity()) {
                                        ob.resource.valueQuantity.value.toString()
                                    } else if (ob.resource.hasValueCodeableConcept()) {
                                        ob.resource.valueCodeableConcept.coding.firstOrNull()?.display
                                            ?: ""
                                    } else if (ob.resource.hasValueStringType()) {
                                        ob.resource.valueStringType.valueAsString
                                    } else {
                                        ""
                                    }

                                val created =
                                    if (ob.resource.hasIssued()) ob.resource.issuedElement.value.toString() else ""
                                val item = PatientListViewModel.ObservationItem(
                                    id = ob.resource.logicalId,
                                    code = ob.resource.code.codingFirstRep.code,
                                    value = value,
                                    created = created
                                )
                                observations.add(item)
                            }
                        data = data.copy(observations = observations)

                    }
                data

            }.let {
                patients.addAll(it)
            }

        return patients
    }

    private suspend fun getPatientLabDataInformation(
        reason: String,
        parent: String
    ): List<PatientListViewModel.CaseLabResultsData> {
        val patients: MutableList<PatientListViewModel.CaseLabResultsData> = mutableListOf()
        fhirEngine
            .search<Encounter> {
                filter(Encounter.SUBJECT, { value = "Patient/$patientId" })
                filter(Encounter.PART_OF, { value = "Encounter/$parent" })
            }
            .mapIndexedNotNull() { index, data ->
                val code = data.resource.reasonCodeFirstRep.codingFirstRep.code
                if (code == reason) {
                    var loop = createEncounterItemLabData(data.resource)

                    val obs =
                        fhirEngine.search<Observation> {
                            filter(
                                Observation.ENCOUNTER,
                                { value = "Encounter/${loop.logicalId}" })
                        }

                    val dateSpecimenReceived = generateResponse(obs, "date-specimen-received")
                    val specimenCondition = generateResponse(obs, "specimen-condition")
                    val measlesIgM = generateResponse(obs, "measles-igm")
                    val rubellaIgM = generateResponse(obs, "rubella-igm")
                    val dateLabSentResults = generateResponse(obs, "date-lab-sent-results")
                    val finalClassification = generateResponse(obs, "final-classification")
                    val finalNClassification =
                        generateResponse(obs, "final-negative-classification")
                    val finalPClassification =
                        generateResponse(obs, "final-confirm-classification")

                    val subcountyName = generateResponse(obs, "contact-name")
                    val subcountyDesignation = generateResponse(obs, "contact-designation")
                    val subcountyPhone = generateResponse(obs, "contact-phone")
                    val subcountyEmail = generateResponse(obs, "contact-email")
                    val formCompletedBy = generateResponse(obs, "completer-name")
                    val nameOfPersonCompletingForm = generateResponse(obs, "completer-name")
                    val designation = generateResponse(obs, "completer-designation")
                    val sign = generateResponse(obs, "completer-sign")

//                    val finalClassification = when (measlesIgM.lowercase()) {
//                        "positive" -> finalPClassification
//                        "negative" -> finalNClassification
//                        else -> finalDClassification
//
//                    }

                    loop =
                        loop.copy(
                            dateSpecimenReceived = dateSpecimenReceived,
                            specimenCondition = specimenCondition,
                            measlesIgM = measlesIgM,
                            rubellaIgM = rubellaIgM,
                            dateLabSentResults = dateLabSentResults,
                            finalClassification = finalClassification,
                            subcountyName = subcountyName,
                            subcountyDesignation = subcountyDesignation,
                            subcountyPhone = subcountyPhone,
                            subcountyEmail = subcountyEmail,
                            formCompletedBy = formCompletedBy,
                            nameOfPersonCompletingForm = nameOfPersonCompletingForm,
                            designation = designation,
                            sign = sign
                        )

                    loop
                } else {
                    null
                }
            }
            .let { patients.addAll(it) }

        return patients
    }

    private suspend fun getPatientLabDataDataInformation(
        reason: String,
        parent: String
    ): List<PatientListViewModel.LabResults> {
        val encounters: MutableList<PatientListViewModel.LabResults> = mutableListOf()
        println("Dealing with case:::: starter patient $patientId")
        fhirEngine
            .search<Encounter> {
                filter(Encounter.SUBJECT, { value = "Patient/$patientId" })
                filter(Encounter.PART_OF, { value = "Encounter/$parent" })
                filter(
                    Encounter.REASON_CODE,
                    {
                        value = of(Coding().apply {
                            code = reason
                        })
                    })
                sort(Encounter.DATE, Order.ASCENDING)
            }
            .take(500)
            .map { enc ->

                Log.e(
                    "Lab Results: ",
                    "Lab results coming here Parent ${enc.resource.logicalId}"
                )
                val observations: MutableList<PatientListViewModel.ObservationItem> =
                    mutableListOf()
                fhirEngine.search<Observation> {
                    filter(
                        Observation.ENCOUNTER,
                        { value = "Encounter/${enc.resource.logicalId}" })
                }.map { ob ->
                    val value =
                        if (ob.resource.hasValueQuantity()) {
                            ob.resource.valueQuantity.value.toString()
                        } else if (ob.resource.hasValueCodeableConcept()) {
                            ob.resource.valueCodeableConcept.coding.firstOrNull()?.display ?: ""
                        } else if (ob.resource.hasValueStringType()) {
                            ob.resource.valueStringType.valueAsString
                        } else {
                            ""
                        }
                    val created =
                        if (ob.resource.hasIssued()) ob.resource.issuedElement.value.toString() else ""
                    val item = PatientListViewModel.ObservationItem(
                        id = ob.resource.logicalId,
                        code = ob.resource.code.codingFirstRep.code,
                        value = value,
                        created = created
                    )
                    observations.add(item)
                }


                val lab = PatientListViewModel.LabResults(
                    encounterId = enc.resource.logicalId,
                    observations = observations

                )
                encounters.add(lab)
            }
        return encounters
    }

    private suspend fun getPatientDiseaseDataInformation(
        reason: String,
        parent: String
    ): List<PatientListViewModel.CaseDiseaseData> {
        val patients: MutableList<PatientListViewModel.CaseDiseaseData> = mutableListOf()

        println("Parent Encounter  Details $parent and respective Patient $patientId")
        fhirEngine
            .search<Encounter> {
                filter(Encounter.SUBJECT, { value = "Patient/$patientId" })
                filter(Encounter.PART_OF, { value = "Encounter/$parent" })
            }
            .mapIndexedNotNull() { index, data ->
                val code = data.resource.reasonCodeFirstRep.codingFirstRep.code
                if (code == reason) {

                    println("Parent Encounter  Retrieved ${data.resource.logicalId} and respective Patient $patientId")
                    var loop = createEncounterItemLab(data.resource)

                    val obs =
                        fhirEngine.search<Observation> {
                            filter(
                                Observation.ENCOUNTER,
                                { value = "Encounter/${loop.logicalId}" })
                        }

                    val fever = generateResponse(obs, "f1")
                    val rash = generateResponse(obs, "f2")

                    loop = loop.copy(fever = fever, rash = rash)

                    loop
                } else {
                    null
                }
            }
            .let { patients.addAll(it) }

        return patients
    }

    companion object {

        private fun createEncounterItemLabData(
            encounter: Encounter,
        ): PatientListViewModel.CaseLabResultsData {
            val reasonCode = encounter.reasonCodeFirstRep.codingFirstRep.code

            return PatientListViewModel.CaseLabResultsData(
                logicalId = encounter.logicalId,
                reasonCode = reasonCode,
            )
        }

        private fun createEncounterItemLab(
            encounter: Encounter,
        ): PatientListViewModel.CaseDiseaseData {
            val reasonCode = encounter.reasonCodeFirstRep.codingFirstRep.code

            return PatientListViewModel.CaseDiseaseData(
                logicalId = encounter.logicalId,
                name = reasonCode,
            )
        }

        private fun createEncounterItem(
            encounter: Encounter,
            resources: Resources,
        ): PatientListViewModel.EncounterItem {
            val reasonCode = encounter.reasonCodeFirstRep.codingFirstRep.code
            val status = encounter.status.display
            return PatientListViewModel.EncounterItem(
                id = encounter.logicalId,
                reasonCode = reasonCode,
                status = status,
            )
        }

        /**
         * Creates ObservationItem objects with displayable values from the Fhir Observation objects.
         */
        private fun createObservationItem(
            observation: Observation,
        ): PatientListViewModel.ObservationItem {
            val observationCode =
                observation.code.text ?: observation.code.codingFirstRep.display

            // Show nothing if no values available for datetime and value quantity.
            val dateTimeString =
                if (observation.hasEffectiveDateTimeType()) {
                    observation.effectiveDateTimeType.asStringValue()
                } else {
                    ""
                }
            val value =
                if (observation.hasValueQuantity()) {
                    observation.valueQuantity.value.toString()
                } else if (observation.hasValueCodeableConcept()) {
                    observation.valueCodeableConcept.coding.firstOrNull()?.display ?: ""
                } else if (observation.hasValueStringType()) {
                    observation.valueStringType.valueAsString
                } else {
                    ""
                }
            val valueUnit =
                if (observation.hasValueQuantity()) {
                    observation.valueQuantity.unit ?: observation.valueQuantity.code
                } else {
                    ""
                }
            val valueString = "$value $valueUnit"
            val created = ""

            return PatientListViewModel.ObservationItem(
                observation.logicalId,
                observationCode,
                valueString,
                created = created
            )
        }

        /** Creates ConditionItem objects with displayable values from the Fhir Condition objects. */
        private fun createConditionItem(
            condition: Condition,
            resources: Resources,
        ): PatientListViewModel.ConditionItem {
            val observationCode =
                condition.code.text ?: condition.code.codingFirstRep.display ?: ""

            // Show nothing if no values available for datetime and value quantity.
            val dateTimeString =
                if (condition.hasOnsetDateTimeType()) {
                    condition.onsetDateTimeType.asStringValue()
                } else {
                    resources.getText(R.string.message_no_datetime).toString()
                }
            val value =
                if (condition.hasVerificationStatus()) {
                    condition.verificationStatus.codingFirstRep.code
                } else {
                    ""
                }
            return PatientListViewModel.ConditionItem(
                condition.logicalId,
                observationCode,
                dateTimeString,
                value,
            )
        }
    }
}

interface PatientDetailData {
    val firstInGroup: Boolean
    val lastInGroup: Boolean
}

data class PatientDetailHeader(
    val header: String,
    override val firstInGroup: Boolean = false,
    override val lastInGroup: Boolean = false,
) : PatientDetailData

data class PatientDetailProperty(
    val patientProperty: PatientProperty,
    override val firstInGroup: Boolean = false,
    override val lastInGroup: Boolean = false,
) : PatientDetailData

data class PatientDetailOverview(
    val patient: PatientProperty,
    override val firstInGroup: Boolean = false,
    override val lastInGroup: Boolean = false,
) : PatientDetailData

data class PatientDetailObservation(
    val observation: PatientListViewModel.ObservationItem,
    override val firstInGroup: Boolean = false,
    override val lastInGroup: Boolean = false,
) : PatientDetailData

data class PatientDetailEncounter(
    val data: PatientListViewModel.EncounterItem,
    override val firstInGroup: Boolean = false,
    override val lastInGroup: Boolean = false,
) : PatientDetailData

data class PatientDetailCondition(
    val condition: PatientListViewModel.ConditionItem,
    override val firstInGroup: Boolean = false,
    override val lastInGroup: Boolean = false,
) : PatientDetailData

data class PatientProperty(val header: String, val value: String)

data class RiskAssessmentItem(
    var riskStatusColor: Int,
    var riskStatus: String,
    var lastContacted: String,
    var patientCardColor: Int,
)

/**
 * The logical (unqualified) part of the ID. For example, if the ID is
 * "http://example.com/fhir/Patient/123/_history/456", then this value would be "123".
 */
private val Resource.logicalId: String
    get() {
        return this.idElement?.idPart.orEmpty()
    }