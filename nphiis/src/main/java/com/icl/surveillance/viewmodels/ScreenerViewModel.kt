package com.icl.surveillance.viewmodels

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.google.android.fhir.FhirEngine
import com.icl.surveillance.clients.AddClientFragment.Companion.QUESTIONNAIRE_FILE_PATH_KEY
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.models.QuestionnaireAnswer
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.utils.QuestionnaireHelper
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.DecimalType
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Meta
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.StringType
import org.json.JSONObject
import java.time.LocalDate

class ScreenerViewModel(application: Application, private val state: SavedStateHandle) :
    AndroidViewModel(application) {
    val questionnaire: String
        get() = getQuestionnaireJson()

    val isResourcesSaved = MutableLiveData<Boolean>()

    private var questionnaireJson: String? = null
    private val fhirEngine: FhirEngine by lazy {
        FhirApplication.fhirEngine(application.applicationContext)
    }
    private val formatter = FormatterClass()

    /**
     * Saves screener encounter questionnaire response into the application database.
     *
     * @param questionnaireResponse screener encounter questionnaire response
     */

    private fun sourceMetaTag(
        resource: String,
        facility: String,
        facilityName: String?
    ): Coding {
        return Coding().apply {
            system = "http://example.org/fhir/StructureDefinition/$resource-managingLocation"
            code = "Location/$facility"
            facilityName?.let { display = it }
        }
    }

    private fun sourceExtension(
        resource: String,
        facility: String,
        facilityName: String?
    ): Extension {
        return Extension().apply {
            url = "http://example.org/fhir/StructureDefinition/$resource-managingLocation"
            setValue(
                Reference().apply {
                    reference = "Location/$facility"
                    facilityName?.let { display = it }
                })
        }
    }

    fun completeContactAssessment(
        questionnaireResponse: QuestionnaireResponse,
        patientId: String,
        encounter: String,
        questionnaireResponseString: String,
        appContext: Context
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = try {
                val title = "afp-contact-case-information"
                val linkReference = Reference("Patient/$patientId")
                val encounterId = generateUuid()
                val contactId = generateUuid()
                val now = Date()
                val formattedNow = formatter.formatDateTime(now)

                val contact = Patient().apply { id = contactId }

                val identifierSystem0 = Identifier().apply {
                    system = "system-creation"
                    value = formattedNow
                    type = CodeableConcept().apply {
                        coding = arrayListOf(
                            Coding().apply {
                                system = "system-creation"
                                code = "system_creation"
                                display = "System Creation"
                            }
                        )
                        text = formattedNow
                    }
                }

                val identifierSystem = Identifier().apply {
                    system = title
                    value = encounterId
                    type = CodeableConcept().apply {
                        coding = arrayListOf(
                            Coding().apply {
                                system = title
                                code = title
                                display = title
                            }
                        )
                        text = encounterId
                    }
                }

                contact.identifier.add(identifierSystem0)
                contact.identifier.add(identifierSystem)
                contact.linkFirstRep.other = linkReference

                val subjectReference = Reference("Patient/$contactId")
                val extractedAnswers =
                    extractStructuredAnswers(questionnaireResponse, questionnaireResponseString)

                val nameEntry = extractedAnswers.find { it.linkId == "652156781680" }
                val genderEntry = extractedAnswers.find { it.linkId == "952250448507" }

                val subCountyEntry = extractedAnswers.find { it.linkId == "a3-sub-county" }
                val countyEntry = extractedAnswers.find { it.linkId == "a4-county" }

                nameEntry?.answer?.let { fullName ->
                    val parts = fullName.trim().split("\\s+".toRegex())
                    when (parts.size) {
                        1 -> {
                            contact.nameFirstRep.family = parts[0]
                        }

                        2 -> {
                            contact.nameFirstRep.family = parts[0]
                            contact.nameFirstRep.addGiven(parts[1])
                        }

                        else -> {
                            contact.nameFirstRep.family = parts[0]
                            contact.nameFirstRep.addGiven(parts[1])
                            contact.nameFirstRep.addGiven(parts.drop(2).joinToString(" "))
                        }
                    }
                }
                if (genderEntry != null) {
                    val gender = when (genderEntry.answer.lowercase()) {
                        "male" -> Enumerations.AdministrativeGender.MALE
                        "female" -> Enumerations.AdministrativeGender.FEMALE
                        else -> Enumerations.AdministrativeGender.UNKNOWN
                    }
                    contact.gender = gender
                }

                val qh = QuestionnaireHelper()
                val enc = qh.generalEncounter(encounter, encounterId).apply {
                    id = encounterId
                    subject = subjectReference
                    reasonCodeFirstRep.codingFirstRep.code = title
                    addReasonCode(
                        CodeableConcept().apply {
                            codingFirstRep.code = "case-information"
                            codingFirstRep.display = "case-information"
                            codingFirstRep.system = "case-information"
                            text = "case-information"
                        }
                    )
                    identifier.add(identifierSystem0)
                }

                val facility = formatter.getSharedPref("facility", appContext)
                val facilityName = formatter.getSharedPref("facilityName", appContext)
                if (facility != null) {
                    contact.addExtension(sourceExtension("patient", facility, facilityName))
                    enc.addExtension(sourceExtension("encounter", facility, facilityName))
                    contact.meta = Meta().apply {
                        tag = listOf(
                            sourceMetaTag("patient", facility, facilityName)
                        )
                    }
                    enc.meta = Meta().apply {
                        tag = listOf(
                            sourceMetaTag("encounter", facility, facilityName)
                        )
                    }
                    questionnaireResponse.meta = Meta().apply {
                        tag = listOf(
                            sourceMetaTag("questionnaire", facility, facilityName)
                        )
                    }

                    questionnaireResponse.addExtension(
                        sourceExtension(
                            "questionnaire",
                            facility,
                            facilityName
                        )
                    )
                }
                val encounterReference = Reference("Encounter/$encounterId")

                contact.active = true
                fhirEngine.create(contact)
                fhirEngine.create(enc)

                questionnaireResponse.id = generateUuid()
                questionnaireResponse.subject = subjectReference
                questionnaireResponse.encounter = encounterReference
                fhirEngine.create(questionnaireResponse)

                var county = ""
                var subCounty = ""
                val currentYear = LocalDate.now().year

                if (subCountyEntry != null) {
                    subCounty = subCountyEntry.answer
                }
                if (countyEntry != null) {
                    county = countyEntry.answer
                }

                val countyCode = county.padEnd(3, 'X').take(3).uppercase()
                val subCountyCode = subCounty.padEnd(3, 'X').take(3).uppercase()

                val epid = "KEN-$countyCode-$subCountyCode-$currentYear-AFP-C"

                val obs = qh.codingQuestionnaire("EPID", "EPID No", epid)
                createResource(
                    obs,
                    subjectReference,
                    encounterReference,
                    facility,
                    facilityName,
                    null
                )

                extractedAnswers.forEach {
                    val obs = qh.codingQuestionnaire(
                        it.linkId, it.text,
                        it.answer
                    )
                    createResource(
                        obs,
                        subjectReference,
                        encounterReference,
                        facility,
                        facilityName,
                        null
                    )
                }
                true
            } catch (e: Exception) {
                Log.e("ScreenerViewModel", "Failed to complete contact assessment", e)
                false
            }

            isResourcesSaved.postValue(success)
        }
    }

    fun completeLabAssessment(
        questionnaireResponse: QuestionnaireResponse,
        patientId: String,
        encounter: String,
        title: String,
        questionnaireResponseString: String,
        appContext: Context
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = try {
                val formattedNow = formatter.formatDateTime(Date())

                val identifierSystem0 = Identifier().apply {
                    system = "system-creation"
                    value = formattedNow
                    type = CodeableConcept().apply {
                        coding = arrayListOf(
                            Coding().apply {
                                system = "system-creation"
                                code = "system_creation"
                                display = "System Creation"
                            }
                        )
                        text = formattedNow
                    }
                }

                val subjectReference = Reference("Patient/$patientId")
                val extractedAnswers =
                    extractStructuredAnswers(questionnaireResponse, questionnaireResponseString)

                val qh = QuestionnaireHelper()
                val encounterId = generateUuid()
                val enc = qh.generalEncounter(encounter, encounterId).apply {
                    id = encounterId
                    subject = subjectReference
                    reasonCodeFirstRep.codingFirstRep.code = title
                    identifier.add(identifierSystem0)
                }

                val facility = formatter.getSharedPref("facility", appContext)
                val facilityName = formatter.getSharedPref("facilityName", appContext)
                if (facility != null) {
                    questionnaireResponse.meta = Meta().apply {
                        tag = listOf(
                            sourceMetaTag("questionnaire", facility, facilityName)
                        )
                    }
                    enc.meta = Meta().apply {
                        tag = listOf(
                            sourceMetaTag("encounter", facility, facilityName)
                        )
                    }
                    questionnaireResponse.addExtension(
                        sourceExtension(
                            "questionnaire",
                            facility,
                            facilityName
                        )
                    )
                    enc.addExtension(sourceExtension("encounter", facility, facilityName))
                }

                val practitionerId = formatter.getSharedPref("fhirPractitionerId", appContext)
                if (practitionerId != null) {
                    questionnaireResponse.author = Reference("Practitioner/$practitionerId")
                    enc.participantFirstRep.individual =
                        Reference("Practitioner/$practitionerId")
                }

                fhirEngine.create(enc)

                val encounterReference = Reference("Encounter/$encounterId")
                questionnaireResponse.id = generateUuid()
                questionnaireResponse.subject = subjectReference
                questionnaireResponse.encounter = encounterReference
                fhirEngine.create(questionnaireResponse)

                extractedAnswers.forEach {
                    val obs = qh.codingQuestionnaire(
                        it.linkId, it.text,
                        it.answer
                    )
                    createResource(
                        obs,
                        subjectReference,
                        encounterReference,
                        facility,
                        facilityName,
                        practitionerId
                    )
                }
                true
            } catch (e: Exception) {
                Log.e("ScreenerViewModel", "Failed to complete lab assessment", e)
                false
            }

            isResourcesSaved.postValue(success)
        }
    }

    fun extractStructuredAnswersOnlyFromItems(json: JSONObject): List<QuestionnaireAnswer> {
        return formatter.extractStructuredAnswersOnlyFromItems(json)
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
            Log.e("ScreenerViewModel", "Failed to parse questionnaire response JSON", e)
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
                val values = item.answer.mapNotNull { extractAnswerValue(it) }
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


    private suspend fun createResource(
        obs: Observation,
        subjectReference: Reference,
        encounterReference: Reference,
        facility: String?,
        facilityName: String?,
        practitionerId: String?
    ) {
        try {
            obs.id = generateUuid()
            obs.subject = subjectReference
            obs.encounter = encounterReference
            if (practitionerId != null) {
                obs.performerFirstRep.reference = "Practitioner/$practitionerId"
            }
            obs.issued = Date()
            if (facility != null) {
                obs.meta = Meta().apply {
                    tag = listOf(
                        sourceMetaTag("observation", facility, facilityName)
                    )
                }
                obs.addExtension(sourceExtension("observation", facility, facilityName))
            }
            fhirEngine.create(obs)

            println("Observation created: ${obs.id}")
        } catch (e: Exception) {
            Log.e("SavePatient", "Error saving patient", e)
        }
    }


    private fun getQuestionnaireJson(): String {
        questionnaireJson?.let { return it }
        questionnaireJson = readFileFromAssets(state[QUESTIONNAIRE_FILE_PATH_KEY]!!)
        return questionnaireJson ?: ""
    }

    private fun readFileFromAssets(filename: String): String {
        return getApplication<Application>().assets.open(filename).bufferedReader().use {
            it.readText()
        }
    }

    private fun generateUuid(): String {
        return UUID.randomUUID().toString()
    }

    private companion object {
        const val ASTHMA = "161527007"
        const val LUNG_DISEASE = "13645005"
        const val DEPRESSION = "35489007"
        const val DIABETES = "161445009"
        const val HYPER_TENSION = "161501007"
        const val HEART_DISEASE = "56265001"
        const val HIGH_BLOOD_LIPIDS = "161450003"

        const val FEVER = "386661006"
        const val SHORTNESS_BREATH = "13645005"
        const val COUGH = "49727002"
        const val LOSS_OF_SMELL = "44169009"

        const val SPO2 = "59408-5"

        private val comorbidities: Set<String> =
            setOf(
                ASTHMA,
                LUNG_DISEASE,
                DEPRESSION,
                DIABETES,
                HYPER_TENSION,
                HEART_DISEASE,
                HIGH_BLOOD_LIPIDS,
            )
        private val symptoms: Set<String> =
            setOf(FEVER, SHORTNESS_BREATH, COUGH, LOSS_OF_SMELL)
    }
}
