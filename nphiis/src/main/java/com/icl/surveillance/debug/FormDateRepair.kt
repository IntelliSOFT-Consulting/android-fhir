package com.icl.surveillance.debug

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.Expression
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Type

/**
 * Fixes date answers that fail their question's rules, for the bulk workflows: each failing
 * date is set to the earliest date its question accepts. The bounds come from the form:
 *
 * - minValue / maxValue, a literal date or `today()` / `now()` plus or minus N days;
 * - questionnaire-constraint "not before another date" (`%context.answer.value >=
 *   %resource.descendants().where(linkId='X').answer.value`): no earlier than X's answer.
 *
 * The earliest accepted date is the latest of the lower bounds; with no lower bound, or one
 * after the upper bound, the upper bound is used (e.g. a future date becomes today).
 */
object FormDateRepair {

    /** A changed answer: the question, its old value and the date it was set to. */
    data class Change(val linkId: String, val from: String?, val to: String)

    /**
     * Sets every date / dateTime answer in [linkIds] (the questions that failed validation) to
     * the earliest date [questionnaire] accepts for it. Returns what changed; questions that are
     * not dates, or have no bound to go by, are left as they are.
     */
    fun repair(
        questionnaire: Questionnaire,
        response: QuestionnaireResponse,
        linkIds: Collection<String>,
        today: LocalDate = LocalDate.now(),
    ): List<Change> = linkIds.distinct().mapNotNull { linkId ->
        val question = findQuestion(questionnaire.item, linkId) ?: return@mapNotNull null
        val dateTime = when (question.type) {
            Questionnaire.QuestionnaireItemType.DATE -> false
            Questionnaire.QuestionnaireItemType.DATETIME -> true
            else -> return@mapNotNull null
        }
        val target = earliestAccepted(question, response, today) ?: return@mapNotNull null
        val answers = findAnswers(response.item, linkId)
        if (answers.isEmpty()) return@mapNotNull null
        val from = answers.firstOrNull()?.answerFirstRep?.value?.primitiveValue()
        val value: Type = if (dateTime) {
            DateTimeType(Date.from(target.atTime(LocalTime.MIDNIGHT).atZone(ZoneId.systemDefault()).toInstant()))
        } else {
            DateType(target.toString())
        }
        answers.forEach { it.answer = mutableListOf(QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().setValue(value.copy())) }
        Change(linkId, from, target.toString())
    }

    /** The earliest date [question] accepts, given the other answers in [response]. */
    fun earliestAccepted(
        question: Questionnaire.QuestionnaireItemComponent,
        response: QuestionnaireResponse,
        today: LocalDate,
    ): LocalDate? {
        val lower = buildList {
            bound(question, MIN_VALUE, today)?.let { add(it) }
            question.extension.filter { it.url == CONSTRAINT }.forEach { constraint ->
                val expression = constraint.getExtensionByUrl("expression")?.value?.primitiveValue() ?: return@forEach
                NOT_BEFORE.findAll(expression).forEach { match ->
                    findAnswers(response.item, match.groupValues[1]).firstOrNull()
                        ?.answerFirstRep?.value?.primitiveValue()?.let { dateOf(it) }?.let { add(it) }
                }
            }
        }.maxOrNull()
        val upper = bound(question, MAX_VALUE, today)
        return when {
            lower != null && (upper == null || !lower.isAfter(upper)) -> lower
            else -> upper
        }
    }

    /** A minValue / maxValue as a date: literal, or a calculated `today()` / `now()` ± N days. */
    private fun bound(question: Questionnaire.QuestionnaireItemComponent, url: String, today: LocalDate): LocalDate? {
        val value = question.getExtensionByUrl(url)?.value ?: return null
        val calculated = value.getExtensionByUrl(CALCULATED)?.value as? Expression
        calculated?.expression?.let { return relativeTo(it, today) }
        return value.primitiveValue()?.let { dateOf(it) }
    }

    /** `today()`, `now()`, `today() - 21 days`, `today() + 1 day`; null for anything else. */
    fun relativeTo(expression: String, today: LocalDate): LocalDate? {
        val match = RELATIVE.matchEntire(expression.trim()) ?: return null
        val days = match.groupValues[3].toLongOrNull() ?: return today
        return if (match.groupValues[2] == "-") today.minusDays(days) else today.plusDays(days)
    }

    private fun dateOf(value: String): LocalDate? = runCatching { LocalDate.parse(value.take(10)) }.getOrNull()

    private fun findQuestion(
        items: List<Questionnaire.QuestionnaireItemComponent>,
        linkId: String,
    ): Questionnaire.QuestionnaireItemComponent? =
        items.firstNotNullOfOrNull { if (it.linkId == linkId) it else findQuestion(it.item, linkId) }

    /** Every response item for [linkId], including those nested under answers. */
    private fun findAnswers(
        items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>,
        linkId: String,
    ): List<QuestionnaireResponse.QuestionnaireResponseItemComponent> =
        items.flatMap { item ->
            listOfNotNull(item.takeIf { it.linkId == linkId && it.hasAnswer() }) +
                findAnswers(item.item, linkId) + item.answer.flatMap { findAnswers(it.item, linkId) }
        }

    private const val MIN_VALUE = "http://hl7.org/fhir/StructureDefinition/minValue"
    private const val MAX_VALUE = "http://hl7.org/fhir/StructureDefinition/maxValue"
    private const val CALCULATED = "http://hl7.org/fhir/StructureDefinition/cqf-calculatedValue"
    private const val CONSTRAINT = "http://hl7.org/fhir/StructureDefinition/questionnaire-constraint"
    private val RELATIVE = Regex("""(today|now)\(\)(?:\s*([+-])\s*(\d+)\s*days?)?""")
    private val NOT_BEFORE = Regex(""">=\s*%resource\.descendants\(\)\.where\(linkId='([^']+)'\)\.answer\.value""")
}
