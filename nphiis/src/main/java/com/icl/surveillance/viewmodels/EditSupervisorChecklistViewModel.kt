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
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.fhir.forms.CaseResponse
import com.icl.surveillance.fhir.forms.FormPrefill
import com.icl.surveillance.ui.patients.custom.VhfContactActions.PREF_CONVERT_TO_SUSPECTED
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.fhir.forms.PatientMapper
import com.icl.surveillance.fhir.forms.answerOf
import com.icl.surveillance.models.QuestionnaireAnswer
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.utils.QuestionnaireHelper
import com.icl.surveillance.utils.readFileFromAssets
import java.util.Date
import java.util.UUID
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
import org.hl7.fhir.r4.model.HumanName
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.StringType
import org.json.JSONObject
import timber.log.Timber


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

    /** The response being edited ("resourceId" may still hold the patient id; see [resolveResponse]). */
    private var responseId: String = questionnaireId

    private suspend fun prepareEditRecord(): Pair<String, String> {
        val questionnaireResponse = resolveResponse()

        // Read the original Questionnaire from assets
        val questionnaireJson =
            getApplication<Application>()
                .readFileFromAssets(questionnaire)
                .trimIndent()

        // Parse the Questionnaire
        val parser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()
        val questionnaire =
            parser.parseResource(Questionnaire::class.java, questionnaireJson) as Questionnaire

        // "Convert to case": the contact's record opens with Type of case = Suspected.
        val app = getApplication<Application>()
        if (formatter.getSharedPref(PREF_CONVERT_TO_SUSPECTED, app) == "true") {
            formatter.deleteSharedPref(PREF_CONVERT_TO_SUSPECTED, app)
            if (this.questionnaire == FormFields.Vhf.CASE_FORM) {
                FormPrefill.applyTo(
                    questionnaire, questionnaireResponse,
                    mapOf(FormFields.Vhf.CASE_TYPE to FormFields.Vhf.CASE_TYPE_SUSPECTED_CODE),
                    overwrite = true,
                )
            }
        }

        // Convert the existing QuestionnaireResponse to JSON string
        val questionnaireResponseJson = parser.encodeResourceToString(questionnaireResponse)


        return questionnaireJson to questionnaireResponseJson
    }


    /**
     * The response to edit: "resourceId" normally holds the case's QuestionnaireResponse id, but
     * when the screen is opened before the summary has looked it up it still holds the patient id;
     * then the patient's case response is used. A blank response is returned if neither exists.
     */
    private suspend fun resolveResponse(): QuestionnaireResponse {
        runCatching { fhirEngine.get<QuestionnaireResponse>(questionnaireId) }.getOrNull()
            ?.let { return it }
        val encounterId = formatter.getSharedPref("encounterId", getApplication())
        val caseResponse = CaseResponse.find(fhirEngine, questionnaireId, encounterId)
        if (caseResponse != null) {
            responseId = caseResponse.idElement.idPart
            return caseResponse
        }
        Timber.w("No QuestionnaireResponse found for %s", questionnaireId)
        return QuestionnaireResponse()
    }

    val isResourcesSaved = MutableLiveData<Boolean>()

    /**
     * Saves the edited response over the original, keeping the original's links (patient,
     * encounter, facility tags, author), then refreshes the case extracted from it.
     */
    fun updatePatient(
        context: Context,
        questionnaireResponse: QuestionnaireResponse,
        questionnaire: String?,
        questionnaireResponseString: String
    ) {
        viewModelScope.launch {
            val original = runCatching { fhirEngine.get<QuestionnaireResponse>(responseId) }.getOrNull()
            if (original == null) {
                // Nothing to update (the record could not be found); never create a stray response.
                isResourcesSaved.value = false
                return@launch
            }
            questionnaireResponse.id = responseId
            keepRecordLinks(from = original, to = questionnaireResponse)
            fhirEngine.update(questionnaireResponse)
            isResourcesSaved.value = true

            // Case forms (response linked to a patient): update the patient and stored answers.
            val isCaseForm = original.subject?.referenceElement?.resourceType == "Patient"
            if (isCaseForm && questionnaire != null) {
                startBackgroundProcessing(original, questionnaireResponse, questionnaireResponseString, questionnaire)
            }
        }
    }

    /** The questionnaire screen returns a bare response: carry over what links it to the case. */
    private fun keepRecordLinks(from: QuestionnaireResponse, to: QuestionnaireResponse) {
        to.subject = from.subject
        to.encounter = from.encounter
        to.author = from.author
        if (from.hasAuthored()) to.authored = from.authored
        if (from.hasQuestionnaire()) to.questionnaire = from.questionnaire
        if (!to.hasIdentifier() && from.hasIdentifier()) to.identifier = from.identifier
        to.status = from.status
        to.meta.tag = from.meta.tag.mapTo(ArrayList()) { it.copy() }
        to.extension = from.extension.mapTo(ArrayList()) { it.copy() }
    }

    private fun generateUuid(): String {
        return UUID.randomUUID().toString()
    }

    private fun startBackgroundProcessing(
        original: QuestionnaireResponse,
        edited: QuestionnaireResponse,
        editedJson: String,
        questionnaire: String,
    ) {
        backgroundProcessingScope.launch {
            try {
                val patientId = original.subject.referenceElement.idPart ?: return@launch
                val answers = extractStructuredAnswers(edited, editedJson)
                updatePatientDetails(patientId, answers, questionnaire)
                updateAnswerObservations(original, patientId, answers, questionnaire)
            } catch (e: Exception) {
                Timber.e(e, "Failed to update the case after editing %s", questionnaire)
            }
        }
    }

    /** Name / sex / date of birth, using each form's own questions and name order. */
    private suspend fun updatePatientDetails(
        patientId: String,
        answers: List<QuestionnaireAnswer>,
        questionnaire: String,
    ) {
        val fields = PATIENT_FIELDS[questionnaire] ?: return
        val patient = fhirEngine.get<Patient>(patientId)
        val first = answers.answerOf(fields.firstName)
        val middle = fields.middleName?.let { answers.answerOf(it) }
        val surname = answers.answerOf(fields.surname)
        if (first != null || surname != null) {
            patient.name = mutableListOf(HumanName())
            PatientMapper.applyName(patient, first, middle, surname, fields.nameOrder)
        }
        PatientMapper.applySex(patient, answers.answerOf(fields.sex))
        PatientMapper.applyBirthDate(patient, answers.answerOf(FormFields.Person.DATE_OF_BIRTH))
        fhirEngine.update(patient)
    }

    /**
     * Brings the case's answer Observations in line with the edited response: changed answers
     * are updated, new answers created (with the case's encounter and facility tags) and answers
     * to questions that are now empty or hidden are deleted.
     */
    private suspend fun updateAnswerObservations(
        original: QuestionnaireResponse,
        patientId: String,
        answers: List<QuestionnaireAnswer>,
        questionnaire: String,
    ) {
        val encounterId = original.encounter?.referenceElement?.idPart
        val existing = fhirEngine.search<Observation> {
            if (encounterId != null) {
                filter(Observation.ENCOUNTER, { value = "Encounter/$encounterId" })
            } else {
                filter(Observation.SUBJECT, { value = "Patient/$patientId" })
            }
        }.map { it.resource }
        val answerByCode = answers.associateBy { it.linkId }
        val questionIds = questionLinkIds(questionnaire)

        existing.forEach { observation ->
            val code = observation.code.codingFirstRep.code ?: return@forEach
            val answer = answerByCode[code]
            when {
                answer != null && observation.value?.primitiveValue() != answer.answer -> {
                    observation.value = StringType(answer.answer)
                    observation.code.text = answer.answer
                    fhirEngine.update(observation)
                }

                // Only answers of this form are removed (never EPID or follow-up form data).
                answer == null && encounterId != null && code in questionIds ->
                    fhirEngine.delete(ResourceType.Observation, observation.idElement.idPart)
            }
        }

        val existingCodes = existing.mapNotNull { it.code.codingFirstRep.code }.toSet()
        val template = existing.firstOrNull()
        answers.filter { it.linkId !in existingCodes }.forEach { answer ->
            val observation = QuestionnaireHelper().codingQuestionnaire(
                code = answer.linkId,
                display = answer.text,
                text = answer.answer
            )
            observation.id = generateUuid()
            observation.subject = Reference("Patient/$patientId")
            encounterId?.let { observation.encounter = Reference("Encounter/$it") }
            observation.issued = Date()
            template?.let { copyFrom ->
                observation.meta.tag = copyFrom.meta.tag.mapTo(ArrayList()) { it.copy() }
                observation.extension = copyFrom.extension.mapTo(ArrayList()) { it.copy() }
                observation.performer = copyFrom.performer.mapTo(ArrayList()) { it.copy() }
            }
            fhirEngine.create(observation)
        }
    }

    private fun questionLinkIds(questionnaireFile: String): Set<String> {
        val json = getApplication<Application>().readFileFromAssets(questionnaireFile)
        val parsed = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()
            .parseResource(Questionnaire::class.java, json)
        val ids = HashSet<String>()
        fun collect(items: List<Questionnaire.QuestionnaireItemComponent>) {
            items.forEach { ids.add(it.linkId); collect(it.item) }
        }
        collect(parsed.item)
        return ids
    }

    /** Patient questions per form (forms without a patient name are not listed). */
    private data class PatientFields(
        val firstName: String,
        val middleName: String?,
        val surname: String,
        val sex: String,
        val nameOrder: PatientMapper.NameOrder,
    )

    private companion object {
        private val PERSON = PatientFields(
            FormFields.Person.FIRST_NAME, FormFields.Person.MIDDLE_NAME, FormFields.Person.SURNAME,
            FormFields.Person.SEX, PatientMapper.NameOrder.SURNAME_AS_FAMILY,
        )

        val PATIENT_FIELDS = mapOf(
            "add-case.json" to PERSON,
            "mpox-register.json" to PERSON,
            "vhf-case.json" to PERSON.copy(middleName = null),
            "afp-case.json" to PERSON.copy(nameOrder = PatientMapper.NameOrder.FIRST_AS_FAMILY),
            "vl-case.json" to PatientFields(
                FormFields.Vl.FIRST_NAME, FormFields.Vl.MIDDLE_NAME, FormFields.Vl.SURNAME,
                FormFields.Vl.SEX, PatientMapper.NameOrder.FIRST_AS_FAMILY,
            ),
        )
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
