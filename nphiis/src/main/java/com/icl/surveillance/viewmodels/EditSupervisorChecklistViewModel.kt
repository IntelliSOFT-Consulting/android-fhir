package com.icl.surveillance.viewmodels

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.get
import com.google.android.fhir.search.search
import com.ibm.icu.text.SimpleDateFormat
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.models.QuestionnaireAnswer
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.utils.QuestionnaireHelper
import com.icl.surveillance.utils.readFileFromAssets
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.UUID


class EditSupervisorChecklistViewModel(
    application: Application,
    private val questionnaireId: String,
    private val questionnaire: String
) :
    AndroidViewModel(application) {
    private val fhirEngine: FhirEngine = FhirApplication.fhirEngine(application.applicationContext)
    private val backgroundProcessingScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("BackgroundProcessing")
    )
    val liveEditData = liveData { emit(prepareEditRecord()) }

    private suspend fun prepareEditRecord(): Pair<String, String> {
        // This is actually a QuestionnaireResponse, not a Patient
        val questionnaireResponse = fhirEngine.get<QuestionnaireResponse>(questionnaireId)

        // Read the original Questionnaire from assets
        val questionnaireJson =
            getApplication<Application>()
                .readFileFromAssets(questionnaire)
                .trimIndent()

        // Parse the Questionnaire
        val parser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()
        val questionnaire =
            parser.parseResource(Questionnaire::class.java, questionnaireJson) as Questionnaire

        // Convert the existing QuestionnaireResponse to JSON string
        val questionnaireResponseJson = parser.encodeResourceToString(questionnaireResponse)


        return questionnaireJson to questionnaireResponseJson
    }


    val isResourcesSaved = MutableLiveData<Boolean>()

    /**
     * Update patient registration questionnaire response into the application database.
     *
     * @param questionnaireResponse patient registration questionnaire response
     */
    fun updatePatient(
        context: Context,
        questionnaireResponse: QuestionnaireResponse,
        questionnaire: String?,
        questionnaireResponseString: String
    ) {
        viewModelScope.launch {
            questionnaireResponse.id = questionnaireId
            fhirEngine.update(questionnaireResponse)

            isResourcesSaved.value = true

            // Let's use a supervisor job to further process changes
            when (questionnaire) {
                "add-case.json"->{
                    startBackgroundProcessing(
                        context,
                        questionnaireResponseString,
                        questionnaire
                    )
                }
                "mpox-register.json" -> startBackgroundProcessing(
                    context,
                    questionnaireResponseString,
                    questionnaire
                )
            }
            return@launch
        }
    }

    private fun generateUuid(): String {
        return UUID.randomUUID().toString()
    }

    private fun startBackgroundProcessing(
        context: Context,
        questionnaireResponseString: String,
        questionnaire: String?
    ) {

        backgroundProcessingScope.launch {
            try {
                val patientId = FormatterClass().getSharedPref("patientId", context)
               println("Started working on patient $patientId")
                val jsonObject = JSONObject(questionnaireResponseString)
                val extractedAnswers = extractStructuredAnswersOnlyFromItems(jsonObject)

                if (patientId != null) {
                    val patient = fhirEngine.get<Patient>(patientId)
                    val updatedPatient = patient.copy()
                    updatedPatient.id = patientId
                    val patientFNameEntry = extractedAnswers.find { it.linkId == "873240407472" }
                    val patientMNameEntry = extractedAnswers.find { it.linkId == "246751846436" }
                    val patientLNameEntry = extractedAnswers.find { it.linkId == "486402457213" }
                    val dobEntry = extractedAnswers.find { it.linkId == "257830485990" }
                    val genderEntry = extractedAnswers.find { it.linkId == "929966324957" }
                    if (patientLNameEntry != null) {
                        updatedPatient.nameFirstRep.family = patientLNameEntry.answer
                    }

                    if (patientFNameEntry != null) {
                        updatedPatient.nameFirstRep.given.clear()
                        updatedPatient.nameFirstRep.addGiven(patientFNameEntry.answer)
                    }

                    if (patientMNameEntry != null) {
                        updatedPatient.nameFirstRep.addGiven(patientMNameEntry.answer)
                    }
                    if (dobEntry != null) {
                        try {
                            updatedPatient.birthDate =
                                SimpleDateFormat("yyyy-MM-dd").parse(dobEntry.answer)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    if (genderEntry != null) {
                        val gender = when (genderEntry.answer.lowercase()) {
                            "male" -> Enumerations.AdministrativeGender.MALE
                            "female" -> Enumerations.AdministrativeGender.FEMALE
                            else -> Enumerations.AdministrativeGender.UNKNOWN
                        }
                        updatedPatient.gender = gender
                    }
                    fhirEngine.update(updatedPatient)

                    // Update all observations as well

                    val patientObs =
                        fhirEngine.search<Observation> {
                            filter(
                                Observation.SUBJECT,
                                { value = "Patient/${patientId}" })
                        }.take(500)

                    val firstObs = patientObs.firstOrNull()

                    if (firstObs != null) {
                        val encounter = firstObs.resource.encounter
                        println("Encounter ID: ${encounter?.id}")
                    }

                    val existingLinkIds = patientObs
                        .mapNotNull { it.resource.code?.codingFirstRep?.code } // or however you map your code
                        .toSet()

                    // Now filter extractedAnswers to find the "new" ones not already in patientObs
                    val newAnswers = extractedAnswers.filter { answer ->
                        answer.linkId !in existingLinkIds
                    }
//                    val patientId = generateUuid()
                    val subjectReference = Reference("Patient/$patientId")
                    val encounterReference = Reference("Patient/$patientId")

                    val qh = QuestionnaireHelper()
                    // create the newly added obs
                    newAnswers.forEach { answer ->
                        val obs = qh.codingQuestionnaire(
                            code = answer.linkId,
                            display = answer.text,
                            text = answer.answer
                        )
                        obs.id = generateUuid()
                        obs.subject = subjectReference
                        obs.encounter = encounterReference
                        obs.issued = Date()
                        fhirEngine.create(obs)
                    }
                    patientObs.forEach { resource ->
                        val updatedObservation = resource.resource.copy()
                        updatedObservation.id = resource.resource.id
                        val code = resource.resource.code.codingFirstRep.code
                        val latestAnswer = extractedAnswers.find { it.linkId == code }
                        if (latestAnswer != null) {
                            updatedObservation.valueStringType.value = latestAnswer.answer
                        }
                        fhirEngine.update(updatedObservation)
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                println("Started working on patient  error: ${e.message}")
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


}