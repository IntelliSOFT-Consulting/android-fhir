package com.icl.surveillance.fhir.forms

import com.google.android.fhir.datacapture.validation.QuestionnaireResponseValidator
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.DecimalType
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemType
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.StringType
import org.hl7.fhir.r4.model.Type
import timber.log.Timber

/**
 * Makes a saved or pre-filled response acceptable to the form before it is shown. The form
 * screen refuses (crashes on) a response whose items are out of the form's order, name
 * questions the form no longer has, give several answers to a single-answer question, or give
 * an answer of another type (e.g. text for a date). Such responses come from older versions of
 * a form, from another form (a screening record investigated as a case) or from imports.
 *
 * A response the form accepts is left untouched. Otherwise it is rebuilt in the form's order
 * with only the answers that fit; what was dropped is logged (tag FORM_CHECK).
 */
object FormResponseFit {
    const val TAG = "FORM_CHECK"

    /** Fits [response] to [questionnaire] in place. Returns true when it had to be changed. */
    fun fit(questionnaire: Questionnaire, response: QuestionnaireResponse, label: String = ""): Boolean {
        val problem = check(questionnaire, response) ?: return false
        Timber.tag(TAG).w("%s: the form refuses the saved answers (%s); fitting them to the form", label, problem)
        runCatching { FormPrefill.upgradeDateAnswers(questionnaire, response) }
        runCatching { FormPrefill.upgradeTextToChoiceAnswers(questionnaire, response) }

        val saved = HashMap<String, QuestionnaireResponse.QuestionnaireResponseItemComponent>()
        fun collect(items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>) {
            items.forEach { item ->
                if (item.hasAnswer()) saved.putIfAbsent(item.linkId, item)
                collect(item.item)
                item.answer.forEach { collect(it.item) }
            }
        }
        collect(response.item)
        val dropped = ArrayList<String>()

        fun build(questions: List<Questionnaire.QuestionnaireItemComponent>):
            MutableList<QuestionnaireResponse.QuestionnaireResponseItemComponent> =
            questions.mapNotNullTo(mutableListOf()) { q ->
                when (q.type) {
                    QuestionnaireItemType.GROUP -> {
                        val children = build(q.item)
                        if (children.isEmpty()) null
                        else QuestionnaireResponse.QuestionnaireResponseItemComponent()
                            .setLinkId(q.linkId).setText(q.text).also { it.item = children }
                    }
                    QuestionnaireItemType.DISPLAY -> null
                    else -> {
                        val item = saved[q.linkId] ?: return@mapNotNullTo null
                        var values = item.answer.mapNotNull { a -> a.value?.let { convert(q.type, it) } }
                        if (values.size < item.answer.size) dropped += "${q.linkId} (answer type)"
                        if (!q.repeats && values.size > 1) {
                            dropped += "${q.linkId} (${values.size - 1} extra answers)"
                            values = values.take(1)
                        }
                        if (values.isEmpty()) return@mapNotNullTo null
                        QuestionnaireResponse.QuestionnaireResponseItemComponent()
                            .setLinkId(q.linkId).setText(q.text).also { out ->
                                values.forEach { out.addAnswer().value = it }
                                // Questions nested under a question go under its answer.
                                val nested = build(q.item)
                                if (nested.isNotEmpty()) out.answer.first().item = nested
                            }
                    }
                }
            }

        val known = HashSet<String>()
        fun walk(items: List<Questionnaire.QuestionnaireItemComponent>) {
            items.forEach { known += it.linkId; walk(it.item) }
        }
        walk(questionnaire.item)
        saved.keys.filter { it !in known }.forEach { dropped += "$it (not in the form)" }

        response.item = build(questionnaire.item)
        if (dropped.isNotEmpty()) Timber.tag(TAG).w("%s: left out %s", label, dropped)
        check(questionnaire, response)?.let { still ->
            // Last resort: open the form empty rather than crash.
            Timber.tag(TAG).e("%s: still refused after fitting (%s); opening without saved answers", label, still)
            response.item = mutableListOf()
        }
        return true
    }

    /** Why the form would refuse [response], or null if it accepts it. */
    fun check(questionnaire: Questionnaire, response: QuestionnaireResponse): String? =
        runCatching { QuestionnaireResponseValidator.checkQuestionnaireResponse(questionnaire, response) }
            .exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }

    /** [value] as the answer type [type] takes, or null when it cannot be. */
    private fun convert(type: QuestionnaireItemType, value: Type): Type? {
        val text = value.primitiveValue()?.trim()
        return when (type) {
            QuestionnaireItemType.CHOICE, QuestionnaireItemType.OPENCHOICE ->
                value.takeIf { it is Coding || it is StringType }
            QuestionnaireItemType.DATE -> when (value) {
                is DateType -> value
                is DateTimeType -> DateType(value.valueAsString.take(10))
                else -> text?.takeIf { it.length >= 10 }?.let { runCatching { DateType(it.take(10)) }.getOrNull() }
            }
            QuestionnaireItemType.DATETIME -> when (value) {
                is DateTimeType -> value
                is DateType -> DateTimeType(value.valueAsString)
                else -> text?.let { runCatching { DateTimeType(it) }.getOrNull() }
            }
            QuestionnaireItemType.INTEGER -> when (value) {
                is IntegerType -> value
                else -> text?.toBigDecimalOrNull()?.toInt()?.let { IntegerType(it) }
            }
            QuestionnaireItemType.DECIMAL -> when (value) {
                is DecimalType -> value
                else -> text?.toBigDecimalOrNull()?.let { DecimalType(it) }
            }
            QuestionnaireItemType.STRING, QuestionnaireItemType.TEXT -> when (value) {
                is StringType -> value
                is Coding -> (value.display ?: value.code)?.let { StringType(it) }
                else -> text?.let { StringType(it) }
            }
            else -> value.takeIf { expectedType(type) == null || it.fhirType() == expectedType(type) }
        }
    }

    private fun expectedType(type: QuestionnaireItemType): String? = when (type) {
        QuestionnaireItemType.BOOLEAN -> "boolean"
        QuestionnaireItemType.TIME -> "time"
        QuestionnaireItemType.URL -> "url"
        QuestionnaireItemType.ATTACHMENT -> "Attachment"
        QuestionnaireItemType.REFERENCE -> "Reference"
        QuestionnaireItemType.QUANTITY -> "Quantity"
        else -> null
    }
}
