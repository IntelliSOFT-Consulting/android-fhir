package com.icl.surveillance.fhir.forms

import android.content.Context
import com.icl.surveillance.utils.FormatterClass
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.StringType
import org.hl7.fhir.r4.model.Type
import org.json.JSONObject

/**
 * Starting answers for a form, keyed by linkId. A screen stages them before opening a form
 * ([stage]); the form screen takes them ([take]) and merges them into its starting response
 * ([applyTo]), nested under the right groups. Values are plain strings: an answer-option code or
 * display for choices, `yyyy-MM-dd` for dates, digits for integers.
 *
 * Only existing questions are pre-filled; the person filling the form can still change them.
 */
object FormPrefill {
    private const val PREF_FORM = "formPrefillFor"
    private const val PREF_VALUES = "formPrefillValues"

    fun stage(context: Context, questionnaireFile: String, values: Map<String, String>) {
        val formatter = FormatterClass()
        formatter.saveSharedPref(PREF_FORM, questionnaireFile, context)
        formatter.saveSharedPref(PREF_VALUES, JSONObject(values).toString(), context)
    }

    /** Returns (and clears) the values staged for [questionnaireFile]; empty when none. */
    fun take(context: Context, questionnaireFile: String?): Map<String, String> {
        val formatter = FormatterClass()
        val stagedFor = formatter.getSharedPref(PREF_FORM, context)
        val json = formatter.getSharedPref(PREF_VALUES, context)
        clear(context)
        if (questionnaireFile == null || stagedFor != questionnaireFile || json.isNullOrBlank()) {
            return emptyMap()
        }
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.optString(it) }.filterValues { it.isNotBlank() }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun clear(context: Context) {
        val formatter = FormatterClass()
        formatter.deleteSharedPref(PREF_FORM, context)
        formatter.deleteSharedPref(PREF_VALUES, context)
    }

    /** Merges [values] into [response], keeping its existing answers and questionnaire order. */
    fun applyTo(
        questionnaire: Questionnaire,
        response: QuestionnaireResponse,
        values: Map<String, String>
    ) {
        if (values.isEmpty()) return
        response.item = merge(questionnaire.item, response.item, values)
    }

    private fun merge(
        questions: List<Questionnaire.QuestionnaireItemComponent>,
        existing: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>,
        values: Map<String, String>
    ): MutableList<QuestionnaireResponse.QuestionnaireResponseItemComponent> {
        val result = mutableListOf<QuestionnaireResponse.QuestionnaireResponseItemComponent>()
        for (question in questions) {
            val current = existing.firstOrNull { it.linkId == question.linkId }
            if (question.type == Questionnaire.QuestionnaireItemType.GROUP) {
                val children = merge(question.item, current?.item.orEmpty(), values)
                if (current != null || children.isNotEmpty()) {
                    val group = current ?: newItem(question)
                    group.item = children
                    result.add(group)
                }
                continue
            }
            val answer = values[question.linkId]?.let { answerFor(question, it) }
            when {
                current != null && (current.hasAnswer() || answer == null) -> result.add(current)
                answer != null -> result.add(
                    (current ?: newItem(question)).apply { addAnswer().value = answer }
                )
            }
        }
        // Anything not in the questionnaire is kept as it was.
        result.addAll(existing.filter { item -> questions.none { it.linkId == item.linkId } })
        return result
    }

    private fun newItem(question: Questionnaire.QuestionnaireItemComponent) =
        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
            linkId = question.linkId
            text = question.text
        }

    private fun answerFor(question: Questionnaire.QuestionnaireItemComponent, raw: String): Type? =
        when (question.type) {
            Questionnaire.QuestionnaireItemType.CHOICE -> question.answerOption
                .firstOrNull { option ->
                    option.hasValueCoding() &&
                        (option.valueCoding.code == raw || option.valueCoding.display == raw)
                }?.valueCoding?.copy()

            Questionnaire.QuestionnaireItemType.DATE -> runCatching { DateType(raw) }.getOrNull()
            Questionnaire.QuestionnaireItemType.INTEGER -> raw.toIntOrNull()?.let { IntegerType(it) }
            Questionnaire.QuestionnaireItemType.STRING,
            Questionnaire.QuestionnaireItemType.TEXT -> StringType(raw)

            else -> null
        }
}
