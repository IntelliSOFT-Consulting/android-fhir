package com.icl.surveillance.viewmodels

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
import com.google.android.fhir.datacapture.mapping.ResourceMapper
import com.icl.surveillance.clients.AddClientFragment.Companion.QUESTIONNAIRE_FILE_PATH_KEY
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.models.QuestionnaireAnswer
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.utils.QuestionnaireHelper
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.Meta
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

class ScreenerViewModel(application: Application, private val state: SavedStateHandle) :
    AndroidViewModel(application) {
    val questionnaire: String
        get() = getQuestionnaireJson()

    val isResourcesSaved = MutableLiveData<Boolean>()

    private val questionnaireResource: Questionnaire
        get() =
            FhirContext.forCached(FhirVersionEnum.R4).newJsonParser().parseResource(questionnaire)
                    as Questionnaire

    private var questionnaireJson: String? = null
    private var fhirEngine: FhirEngine = FhirApplication.fhirEngine(application.applicationContext)

    /**
     * Saves screener encounter questionnaire response into the application database.
     *
     * @param questionnaireResponse screener encounter questionnaire response
     */

    private fun sourceMetaTag(
        resource: String,
        facility: String,
        context: Context
    ): Coding {
        return Coding().apply {
            system = "http://example.org/fhir/StructureDefinition/$resource-managingLocation"
            code = "Location/$facility"
            display = FormatterClass().getSharedPref("facilityName", context)
        }
    }

    private fun sourceExtension(resource: String, facility: String, context: Context): Extension {
        return Extension().apply {
            url = "http://example.org/fhir/StructureDefinition/$resource-managingLocation"
            setValue(
                Reference().apply {
                    reference = "Location/$facility"
                    display = FormatterClass().getSharedPref("facilityName", context)
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
        viewModelScope.launch {
            val bundle =
                ResourceMapper.extract(questionnaireResource, questionnaireResponse)
            val context = FhirContext.forR4()
            val questionnaire =
                context.newJsonParser().encodeResourceToString(questionnaireResponse)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val title = "afp-contact-case-information"
                    val linkReference = Reference("Patient/$patientId")
                    val encounterId = generateUuid()
                    val contactId = generateUuid()

                    val contact = Patient()
                    contact.id = contactId


                    val identifierSystem0 = Identifier()
                    val typeCodeableConcept0 = CodeableConcept()
                    val codingList0 = ArrayList<Coding>()
                    val coding0 = Coding()
                    coding0.system = "system-creation"
                    coding0.code = "system_creation"
                    coding0.display = "System Creation"
                    codingList0.add(coding0)
                    typeCodeableConcept0.coding = codingList0
                    typeCodeableConcept0.text = FormatterClass().formatDateTime(Date())

                    identifierSystem0.value = FormatterClass().formatDateTime(Date())
                    identifierSystem0.system = "system-creation"
                    identifierSystem0.type = typeCodeableConcept0


                    val identifierSystem = Identifier()
                    val typeCodeableConcept = CodeableConcept()
                    val codingList = ArrayList<Coding>()
                    val coding = Coding()
                    coding.system = title
                    coding.code = title
                    coding.display = title
                    codingList.add(coding)
                    typeCodeableConcept.coding = codingList
                    typeCodeableConcept.text = encounterId

                    identifierSystem.value = encounterId
                    identifierSystem.system = title
                    identifierSystem.type = typeCodeableConcept


                    contact.identifier.add(identifierSystem0)
                    contact.identifier.add(identifierSystem)
                    contact.linkFirstRep.other = linkReference

                    val subjectReference = Reference("Patient/$contactId")
                    val jsonObject = JSONObject(questionnaireResponseString)
                    val extractedAnswers = extractStructuredAnswersOnlyFromItems(jsonObject)

                    val nameEntry = extractedAnswers.find { it.linkId == "652156781680" }
                    val dobEntry = extractedAnswers.find { it.linkId == "833589441171" }
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
                    val enc = qh.generalEncounter(encounter, encounterId)
                    enc.id = encounterId
                    enc.subject = subjectReference
                    enc.reasonCodeFirstRep.codingFirstRep.code = title

                    val codeableConcept = CodeableConcept()
                    codeableConcept.codingFirstRep.code = "case-information"
                    codeableConcept.codingFirstRep.display = "case-information"
                    codeableConcept.codingFirstRep.system = "case-information"
                    codeableConcept.text = "case-information"
                    enc.addReasonCode(codeableConcept)
                    enc.identifier.add(identifierSystem0)


                    val facility = FormatterClass().getSharedPref("facility", appContext)
                    if (facility != null) {
                        contact.addExtension(sourceExtension("patient", facility, appContext))
                        enc.addExtension(sourceExtension("encounter", facility, appContext))
                        contact.meta = Meta().apply {
                            tag = listOf(
                                sourceMetaTag("patient", facility, appContext)
                            )
                        }
                        enc.meta = Meta().apply {
                            tag = listOf(
                                sourceMetaTag("encounter", facility, appContext)
                            )
                        }
                        questionnaireResponse.meta = Meta().apply {
                            tag = listOf(
                                sourceMetaTag("questionnaire", facility, appContext)
                            )
                        }

                        questionnaireResponse.addExtension(
                            sourceExtension(
                                "questionnaire",
                                facility,
                                appContext
                            )
                        )

                    }
                    val encounterReference = Reference("Encounter/$encounterId")

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
                    createResource(obs, subjectReference, encounterReference, appContext)

                    extractedAnswers.forEach {

                        val obs = qh.codingQuestionnaire(
                            it.linkId, it.text,
                            it.answer
                        )
                        createResource(obs, subjectReference, encounterReference, appContext)
                        println("Data Found LinkId: ${it.linkId}, Text: ${it.text}, Answer: ${it.answer}")
                    }

                    CoroutineScope(Dispatchers.Main).launch { isResourcesSaved.value = true }
                } catch (e: Exception) {

                    CoroutineScope(Dispatchers.Main).launch { isResourcesSaved.value = false }
                }
            }
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
        viewModelScope.launch {

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val identifierSystem0 = Identifier()
                    val typeCodeableConcept0 = CodeableConcept()
                    val codingList0 = ArrayList<Coding>()
                    val coding0 = Coding()
                    coding0.system = "system-creation"
                    coding0.code = "system_creation"
                    coding0.display = "System Creation"
                    codingList0.add(coding0)
                    typeCodeableConcept0.coding = codingList0
                    typeCodeableConcept0.text = FormatterClass().formatDateTime(Date())

                    identifierSystem0.value = FormatterClass().formatDateTime(Date())
                    identifierSystem0.system = "system-creation"
                    identifierSystem0.type = typeCodeableConcept0

                    val subjectReference = Reference("Patient/$patientId")
                    val jsonObject = JSONObject(questionnaireResponseString)
                    val extractedAnswers = extractStructuredAnswersOnlyFromItems(jsonObject)

                    val qh = QuestionnaireHelper()
                    val encounterId = generateUuid()
                    val enc = qh.generalEncounter(encounter, encounterId)
                    enc.id = encounterId
                    enc.subject = subjectReference
                    enc.reasonCodeFirstRep.codingFirstRep.code = title
                    enc.identifier.add(identifierSystem0)
                    val facility = FormatterClass().getSharedPref("facility", appContext)
                    if (facility != null) {
                        questionnaireResponse.meta = Meta().apply {
                            tag = listOf(
                                sourceMetaTag("questionnaire", facility, appContext)
                            )
                        }
                        enc.meta = Meta().apply {
                            tag = listOf(
                                sourceMetaTag("encounter", facility, appContext)
                            )
                        }
                        questionnaireResponse.addExtension(
                            sourceExtension(
                                "questionnaire",
                                facility,
                                appContext
                            )
                        )
                        enc.addExtension(sourceExtension("encounter", facility, appContext))
                    }

                    val practitionerId =
                        FormatterClass().getSharedPref("fhirPractitionerId", appContext)
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
                        createResource(obs, subjectReference, encounterReference, appContext)
                    }

                    CoroutineScope(Dispatchers.Main).launch { isResourcesSaved.value = true }
                } catch (e: Exception) {
                    e.printStackTrace()
                    CoroutineScope(Dispatchers.Main).launch { isResourcesSaved.value = false }
                }
            }
        }
    }

    fun extractStructuredAnswersOnlyFromItems(json: JSONObject): List<QuestionnaireAnswer> {
        val results = mutableListOf<QuestionnaireAnswer>()

        fun processItems(items: JSONArray) {
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val linkId = item.optString("linkId", "")
                val text = item.optString("text", "")

                if (item.has("answer")) {
                    val answers = item.getJSONArray("answer")
                    val valueList = mutableListOf<String>()

                    for (j in 0 until answers.length()) {
                        val answerObj = answers.getJSONObject(j)

                        val value = when {
                            answerObj.has("valueString") -> answerObj.getString("valueString")
                            answerObj.has("valueInteger") -> answerObj.optString("valueInteger", "")
                            answerObj.has("valueDate") -> answerObj.optString("valueDate", "")
                            answerObj.has("valueDateTime") -> answerObj.optString(
                                "valueDateTime",
                                ""
                            )

                            answerObj.has("valueBoolean") -> answerObj.optString("valueBoolean", "")
                            answerObj.has("valueDecimal") -> answerObj.optString("valueDecimal", "")
                            answerObj.has("valueCoding") -> {
                                val coding = answerObj.getJSONObject("valueCoding")
                                coding.optString("display", coding.optString("code", ""))
                            }

                            answerObj.has("valueReference") -> {
                                val ref = answerObj.getJSONObject("valueReference")
                                ref.optString("display", ref.optString("reference", ""))
                            }

                            else -> null
                        }

                        if (!value.isNullOrBlank()) {
                            valueList.add(value)
                        }
                    }

                    if (valueList.isNotEmpty()) {
                        // Join multiple values with comma
                        results.add(QuestionnaireAnswer(linkId, text, valueList.joinToString(", ")))
                    }
                }

                if (item.has("item")) {
                    processItems(item.getJSONArray("item"))
                }
            }
        }

        if (json.has("item")) {
            processItems(json.getJSONArray("item"))
        }

        return results
    }


    private suspend fun createResource(
        obs: Observation,
        subjectReference: Reference,
        encounterReference: Reference,
        context: Context
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
            if (facility != null) {
                obs.meta = Meta().apply {
                    tag = listOf(
                        sourceMetaTag("observation", facility, context)
                    )
                }
                obs.addExtension(sourceExtension("observation", facility, context))
            }
            fhirEngine.create(obs)

            println("Observation created: ${obs.id}")
        } catch (e: Exception) {
            Log.e("SavePatient", "Error saving patient", e)
        }
    }


    private fun getQuestionnaireJson(): String {
        questionnaireJson?.let {
            return it!!
        }
        questionnaireJson = readFileFromAssets(state[QUESTIONNAIRE_FILE_PATH_KEY]!!)
        return questionnaireJson!!
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