package com.icl.surveillance.fhir.forms

import android.content.Context
import ca.uhn.fhir.context.FhirContext
import com.google.android.fhir.datacapture.validation.QuestionnaireResponseValidator
import com.icl.surveillance.utils.FormatterClass
import java.io.File
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.json.JSONObject
import timber.log.Timber

/**
 * Unsaved answers of a new-case form, kept on the device so that leaving the form (Back, the app
 * closing or being killed) does not lose them. One draft per form, per user and, for a contact or
 * a screened person's investigation, per source record. Drafts never sync.
 *
 * Stored as JSON files in the app's private files (like the local database, not encrypted).
 */
object FormDrafts {
    private const val TAG = "FormDrafts"
    private const val DIR = "form-drafts"

    data class Draft(val savedAt: Long, val responseJson: String)

    /**
     * The draft key for [questionnaireFile] in the current context: who is entering it, training
     * mode, and the record a contact / case investigation starts from.
     */
    fun keyFor(context: Context, questionnaireFile: String): String {
        val prefs = FormatterClass()
        val parts = listOf(
            prefs.getSharedPref("fhirPractitionerId", context).orEmpty(),
            questionnaireFile.removeSuffix(".json"),
            if (VhfTraining.isOn(context)) "training" else "",
            prefs.getSharedPref(VhfContactTracker.PREF_SOURCE_PATIENT, context)?.let { "contact-$it" }.orEmpty(),
            prefs.getSharedPref(VhfScreened.PREF_SOURCE, context)?.let { "screened-$it" }.orEmpty(),
        )
        return parts.filter { it.isNotBlank() }.joinToString("_")
            .replace(Regex("[^A-Za-z0-9_.-]"), "-")
    }

    fun save(context: Context, key: String, responseJson: String) {
        runCatching {
            file(context, key).writeText(
                JSONObject()
                    .put("savedAt", System.currentTimeMillis())
                    .put("response", responseJson)
                    .toString()
            )
        }.onFailure { Timber.tag(TAG).e(it, "Could not save draft %s", key) }
    }

    fun load(context: Context, key: String): Draft? {
        val file = file(context, key)
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText())
            Draft(json.getLong("savedAt"), json.getString("response"))
        }.onFailure { Timber.tag(TAG).w(it, "Unreadable draft %s, discarding", key); file.delete() }
            .getOrNull()
    }

    fun delete(context: Context, key: String) {
        file(context, key).delete()
    }

    /**
     * The draft's response made to fit [questionnaire] as it is now: answers to questions that no
     * longer exist are dropped, and answers whose question changed type are carried over where
     * possible. Null when it still does not fit (the form screen would reject it).
     */
    fun fitTo(questionnaire: Questionnaire, draftJson: String): String? = runCatching {
        val parser = FhirContext.forR4Cached().newJsonParser()
        val response = parser.parseResource(QuestionnaireResponse::class.java, draftJson)
        response.item = keepKnown(questionnaire.item, response.item)
        FormPrefill.upgradeDateAnswers(questionnaire, response)
        FormPrefill.upgradeTextToChoiceAnswers(questionnaire, response)
        QuestionnaireResponseValidator.checkQuestionnaireResponse(questionnaire, response)
        parser.encodeResourceToString(response)
    }.onFailure { Timber.tag(TAG).w(it, "Draft no longer fits the form") }.getOrNull()

    /**
     * What the user has answered, to tell a draft worth keeping from a form only holding its
     * starting answers.
     */
    fun answersOf(response: QuestionnaireResponse): Map<String, List<String>> {
        val answers = mutableMapOf<String, List<String>>()
        fun visit(items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>) {
            items.forEach { item ->
                if (item.hasAnswer()) {
                    answers[item.linkId] = item.answer.map { answer ->
                        answer.value?.let { value ->
                            (value as? org.hl7.fhir.r4.model.Coding)?.code ?: value.primitiveValue()
                        }.orEmpty()
                    }
                }
                item.answer.forEach { visit(it.item) }
                visit(item.item)
            }
        }
        visit(response.item)
        return answers
    }

    /** Response items (at any depth) whose question is still in the form, in the same nesting. */
    private fun keepKnown(
        questions: List<Questionnaire.QuestionnaireItemComponent>,
        items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>,
    ): MutableList<QuestionnaireResponse.QuestionnaireResponseItemComponent> =
        items.mapNotNullTo(mutableListOf()) { item ->
            val question = questions.firstOrNull { it.linkId == item.linkId } ?: return@mapNotNullTo null
            item.item = keepKnown(question.item, item.item)
            item.answer.forEach { answer -> answer.item = keepKnown(question.item, answer.item) }
            item
        }

    private fun file(context: Context, key: String): File =
        File(context.filesDir, DIR).apply { mkdirs() }.resolve("$key.json")
}
