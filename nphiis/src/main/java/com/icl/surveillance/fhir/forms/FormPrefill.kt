package com.icl.surveillance.fhir.forms

import android.content.Context
import com.icl.surveillance.utils.FormatterClass
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.StringType
import org.hl7.fhir.r4.model.Type
import org.json.JSONArray
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
    private const val PREF_LOCKED = "formPrefillLocked"

    /** Separates the answers of a repeating question in one staged value, e.g. several symptoms. */
    const val MULTI = "\u001F"

    /** One staged value holding several answers (see [MULTI]). */
    fun multi(values: List<String>): String = values.filter { it.isNotBlank() }.joinToString(MULTI)

    /** Answers staged for a form, and which of them the user may not change. */
    data class Staged(val values: Map<String, String>, val locked: Set<String>)

    /**
     * Stages [values] for [questionnaireFile]. linkIds in [locked] (e.g. details verified in IPRS)
     * become read-only on the form once their answer is filled in.
     */
    fun stage(
        context: Context,
        questionnaireFile: String,
        values: Map<String, String>,
        locked: Set<String> = emptySet(),
    ) {
        val formatter = FormatterClass()
        formatter.saveSharedPref(PREF_FORM, questionnaireFile, context)
        formatter.saveSharedPref(PREF_VALUES, JSONObject(values).toString(), context)
        formatter.saveSharedPref(PREF_LOCKED, JSONArray(locked.toList()).toString(), context)
    }

    /** Returns (and clears) the values staged for [questionnaireFile]; empty when none. */
    fun take(context: Context, questionnaireFile: String?): Map<String, String> =
        takeStaged(context, questionnaireFile).values

    /** Returns (and clears) the values and locked linkIds staged for [questionnaireFile]. */
    fun takeStaged(context: Context, questionnaireFile: String?): Staged =
        peek(context, questionnaireFile).also { clear(context) }

    /** The values and locked linkIds staged for [questionnaireFile], left staged. */
    fun peek(context: Context, questionnaireFile: String?): Staged {
        val formatter = FormatterClass()
        val stagedFor = formatter.getSharedPref(PREF_FORM, context)
        val json = formatter.getSharedPref(PREF_VALUES, context)
        val lockedJson = formatter.getSharedPref(PREF_LOCKED, context)
        if (questionnaireFile == null || stagedFor != questionnaireFile || json.isNullOrBlank()) {
            return Staged(emptyMap(), emptySet())
        }
        val values = try {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.optString(it) }.filterValues { it.isNotBlank() }
        } catch (e: Exception) {
            emptyMap()
        }
        val locked = try {
            val array = JSONArray(lockedJson ?: "[]")
            (0 until array.length()).map { array.getString(it) }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
        return Staged(values, locked)
    }

    /** linkIds that hold an answer in [response], at any depth. */
    fun answeredLinkIds(response: QuestionnaireResponse): Set<String> = answered(response.item).toSet()

    private fun answered(items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>): List<String> =
        items.flatMap { item ->
            listOfNotNull(item.linkId.takeIf { item.hasAnswer() }) +
                answered(item.answer.flatMap { it.item }) + answered(item.item)
        }

    /** Returns [questionnaireJson] with the items in [linkIds] marked read-only. */
    fun withReadOnly(questionnaireJson: String, linkIds: Set<String>): String {
        if (linkIds.isEmpty()) return questionnaireJson
        val root = JSONObject(questionnaireJson)
        fun visit(items: JSONArray?) {
            if (items == null) return
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                if (item.optString("linkId") in linkIds) item.put("readOnly", true)
                visit(item.optJSONArray("item"))
            }
        }
        visit(root.optJSONArray("item"))
        return root.toString()
    }

    /** Staged value for a Location reference question; see [answerFor]. */
    fun locationReference(id: String, display: String?): String =
        "Location/$id" + (display?.takeIf { it.isNotBlank() }?.let { "|$it" } ?: "")

    fun clear(context: Context) {
        val formatter = FormatterClass()
        formatter.deleteSharedPref(PREF_FORM, context)
        formatter.deleteSharedPref(PREF_VALUES, context)
        formatter.deleteSharedPref(PREF_LOCKED, context)
    }

    /**
     * Merges [values] into [response] in questionnaire order. Existing answers are kept unless
     * [overwrite] is true (used to change one answer of a saved record, e.g. Type of case).
     */
    fun applyTo(
        questionnaire: Questionnaire,
        response: QuestionnaireResponse,
        values: Map<String, String>,
        overwrite: Boolean = false,
    ) {
        if (values.isEmpty()) return
        response.item = merge(questionnaire.item, response.item, values, overwrite)
    }

    private fun merge(
        questions: List<Questionnaire.QuestionnaireItemComponent>,
        existing: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>,
        values: Map<String, String>,
        overwrite: Boolean,
    ): MutableList<QuestionnaireResponse.QuestionnaireResponseItemComponent> {
        val result = mutableListOf<QuestionnaireResponse.QuestionnaireResponseItemComponent>()
        for (question in questions) {
            val current = existing.firstOrNull { it.linkId == question.linkId }
            if (question.type == Questionnaire.QuestionnaireItemType.GROUP) {
                val children = merge(question.item, current?.item.orEmpty(), values, overwrite)
                if (current != null || children.isNotEmpty()) {
                    val group = current ?: newItem(question)
                    group.item = children
                    result.add(group)
                }
                continue
            }
            val answers = values[question.linkId]?.let { answersFor(question, it) }.orEmpty()
            when {
                current != null && answers.isNotEmpty() && overwrite -> result.add(
                    current.apply {
                        this.answer = mutableListOf()
                        answers.forEach { addAnswer().value = it }
                    }
                )
                current != null && (current.hasAnswer() || answers.isEmpty()) -> result.add(current)
                answers.isNotEmpty() -> result.add(
                    (current ?: newItem(question)).apply { answers.forEach { addAnswer().value = it } }
                )
            }
        }
        // Anything not in the questionnaire is kept as it was.
        result.addAll(existing.filter { item -> questions.none { it.linkId == item.linkId } })
        return result
    }

    private fun isOpenChoice(question: Questionnaire.QuestionnaireItemComponent) =
        question.extension.any { extension ->
            extension.url == "http://hl7.org/fhir/StructureDefinition/questionnaire-itemControl" &&
                (extension.value as? org.hl7.fhir.r4.model.CodeableConcept)
                    ?.coding?.any { it.code == "open-choice" } == true
        }

    private fun newItem(question: Questionnaire.QuestionnaireItemComponent) =
        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
            linkId = question.linkId
            text = question.text
        }

    /**
     * Saved answers whose question has since become a date-and-time question (e.g. VHF sample
     * collection and sending, 12 Oct 2026) still hold a plain date. The form screen rejects an
     * answer of the wrong type, so such a date is carried over as a date-time with day precision.
     */
    fun upgradeDateAnswers(q: Questionnaire, r: QuestionnaireResponse) {
        val types = mutableMapOf<String, Questionnaire.QuestionnaireItemType>()
        fun index(items: List<Questionnaire.QuestionnaireItemComponent>) {
            items.forEach { types[it.linkId] = it.type; index(it.item) }
        }
        index(q.item)
        fun visit(items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>) {
            items.forEach { item ->
                if (types[item.linkId] == Questionnaire.QuestionnaireItemType.DATETIME) {
                    item.answer.forEach { answer ->
                        val v = answer.value
                        if (v is DateType && v.valueAsString != null) answer.value = DateTimeType(v.valueAsString)
                    }
                }
                item.answer.forEach { visit(it.item) }
                visit(item.item)
            }
        }
        visit(r.item)
    }

    /** Answers for a staged value: several for a repeating question ([MULTI]), else one. */
    private fun answersFor(question: Questionnaire.QuestionnaireItemComponent, raw: String): List<Type> {
        val parts = if (question.repeats) raw.split(MULTI) else listOf(raw.substringBefore(MULTI))
        return parts.filter { it.isNotBlank() }.mapNotNull { answerFor(question, it) }
    }

    /**
     * Saved answers whose question has since become a choice (e.g. VHF travel origin country,
     * 14 Oct 2026) still hold the typed text. The form screen rejects an answer of the wrong type,
     * so the text becomes the option with that display or code (ignoring case); text that matches
     * no option is dropped, to be answered again. Open-choice questions keep typed text.
     */
    fun upgradeTextToChoiceAnswers(q: Questionnaire, r: QuestionnaireResponse) {
        val choices = mutableMapOf<String, Questionnaire.QuestionnaireItemComponent>()
        fun index(items: List<Questionnaire.QuestionnaireItemComponent>) {
            items.forEach {
                if (it.type == Questionnaire.QuestionnaireItemType.CHOICE && !isOpenChoice(it)) {
                    choices[it.linkId] = it
                }
                index(it.item)
            }
        }
        index(q.item)
        fun visit(items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>) {
            items.forEach { item ->
                val question = choices[item.linkId]
                if (question != null && item.answer.any { it.value is StringType }) {
                    item.answer = item.answer.mapNotNullTo(mutableListOf()) { answer ->
                        val text = (answer.value as? StringType)?.value?.trim()
                            ?: return@mapNotNullTo answer
                        question.answerOption
                            .firstOrNull { option ->
                                option.hasValueCoding() &&
                                    (option.valueCoding.display.equals(text, ignoreCase = true) ||
                                        option.valueCoding.code.equals(text, ignoreCase = true))
                            }
                            ?.let { answer.apply { value = it.valueCoding.copy() } }
                    }
                }
                item.answer.forEach { visit(it.item) }
                visit(item.item)
            }
        }
        visit(r.item)
    }

    private fun answerFor(question: Questionnaire.QuestionnaireItemComponent, raw: String): Type? =
        when (question.type) {
            Questionnaire.QuestionnaireItemType.CHOICE -> question.answerOption
                .firstOrNull { option ->
                    option.hasValueCoding() &&
                        (option.valueCoding.code == raw || option.valueCoding.display == raw)
                }?.valueCoding?.copy()
                // An open-choice question also takes a typed answer.
                ?: StringType(raw).takeIf { isOpenChoice(question) }

            Questionnaire.QuestionnaireItemType.DATE -> runCatching { DateType(raw) }.getOrNull()
            Questionnaire.QuestionnaireItemType.DATETIME -> runCatching { DateTimeType(raw) }.getOrNull()
            Questionnaire.QuestionnaireItemType.INTEGER -> raw.toIntOrNull()?.let { IntegerType(it) }
            Questionnaire.QuestionnaireItemType.STRING,
            Questionnaire.QuestionnaireItemType.TEXT -> StringType(raw)

            // "Location/<id>|<display>", as built by locationReference.
            Questionnaire.QuestionnaireItemType.REFERENCE -> raw.split("|", limit = 2).let { parts ->
                Reference(parts[0]).apply { if (parts.size > 1) display = parts[1] }
            }

            else -> null
        }
}
