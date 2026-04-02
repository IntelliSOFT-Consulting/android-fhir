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
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.DecimalType
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.StringType
import org.json.JSONObject
import java.util.Date
import java.util.UUID


class EditSupervisorChecklistViewModel(
    application: Application,
    private val questionnaireId: String,
    private val questionnaire: String
) :
    AndroidViewModel(application) {
    private val fhirEngine: FhirEngine by lazy {
        FhirApplication.fhirEngine(application.applicationContext)
    }
    private val formatter = FormatterClass()
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
                        questionnaireResponse,
                        questionnaireResponseString,
                        questionnaire
                    )
                }
                "mpox-register.json" -> startBackgroundProcessing(
                    context,
                    questionnaireResponse,
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
        questionnaireResponse: QuestionnaireResponse?,
        questionnaireResponseString: String,
        questionnaire: String?
    ) {

        backgroundProcessingScope.launch {
            try {
                val patientId = formatter.getSharedPref("patientId", context)
               println("Started working on patient $patientId")
                val extractedAnswers =
                    extractStructuredAnswers(questionnaireResponse, questionnaireResponseString)

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
        return formatter.extractStructuredAnswersOnlyFromItems(json)
    }

    private fun extractStructuredAnswers(
        questionnaireResponse: QuestionnaireResponse?,
        questionnaireResponseString: String
    ): List<QuestionnaireAnswer> {
        if (questionnaireResponse != null) {
            val fromModel = extractStructuredAnswersFromItems(questionnaireResponse.item)
            if (fromModel.isNotEmpty()) {
                return fromModel
            }
        }

        if (questionnaireResponseString.isBlank()) {
            return emptyList()
        }

        return try {
            formatter.extractStructuredAnswersOnlyFromItems(JSONObject(questionnaireResponseString))
        } catch (e: Exception) {
            e.printStackTrace()
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

}
