package com.icl.surveillance.fhir.forms

import com.google.android.fhir.SearchResult
import com.google.android.fhir.datacapture.extensions.asStringValue
import com.icl.surveillance.models.QuestionnaireAnswer
import org.hl7.fhir.r4.model.Observation

/*
 * Lookups shared by the submission (extraction) path and the case lists. Every extracted answer
 * is stored as an Observation whose code is the question's linkId, so the same [FormFields]
 * constants work on both the submitted answers and the stored observations.
 */

/**
 * An answer value as text, whatever its type: primitives as they are, a Coding or
 * CodeableConcept by its display (else code), a Quantity by its number, a Reference by its
 * display. Records created by the HIE store coded answers (e.g. Type of case) as codes, which
 * asStringValue() reads as "".
 */
fun org.hl7.fhir.r4.model.Type.answerText(): String = when (this) {
    is org.hl7.fhir.r4.model.Coding -> display ?: code ?: ""
    is org.hl7.fhir.r4.model.CodeableConcept ->
        text?.takeIf { it.isNotBlank() } ?: coding.firstNotNullOfOrNull { c -> (c.display ?: c.code)?.takeIf { it.isNotBlank() } } ?: ""
    is org.hl7.fhir.r4.model.Quantity -> value?.toPlainString() ?: ""
    is org.hl7.fhir.r4.model.Reference -> display ?: reference ?: ""
    else -> asStringValue()
}

/** First non-blank answer among [linkIds] (checked in order), or null. */
fun List<QuestionnaireAnswer>.answerOf(vararg linkIds: String): String? =
    answerOf(linkIds.asList())

fun List<QuestionnaireAnswer>.answerOf(linkIds: List<String>): String? =
    linkIds.firstNotNullOfOrNull { id ->
        firstOrNull { it.linkId == id && it.answer.isNotBlank() }?.answer
    }

/** Value of the first stored answer among [codes] (checked in order), or [default]. */
fun List<Observation>.valueOf(vararg codes: String, default: String = ""): String =
    codes.firstNotNullOfOrNull { code ->
        firstOrNull { it.code.codingFirstRep.code == code }
            ?.value?.answerText()?.takeIf { it.isNotBlank() }
    } ?: default

/**
 * The newest stored answer to [code] (by issued / effective / last updated), or [default].
 * For answers that can be changed outside the app's form, e.g. Type of case set to Confirmed by
 * the HIE or the lab as a new or updated Observation without the form response being changed.
 */
fun List<Observation>.latestValueOf(code: String, default: String = ""): String =
    filter { it.code.coding.any { c -> c.code == code } && it.status != Observation.ObservationStatus.ENTEREDINERROR }
        .mapNotNull { obs ->
            obs.value?.answerText()?.takeIf { it.isNotBlank() }?.let { value ->
                val time = listOfNotNull(
                    obs.issued?.time,
                    obs.effectiveDateTimeType?.value?.time.takeIf { obs.hasEffectiveDateTimeType() },
                    obs.meta.lastUpdated?.time,
                ).maxOrNull() ?: 0L
                time to value
            }
        }
        .maxByOrNull { it.first }?.second ?: default

@JvmName("searchResultLatestValueOf")
fun List<SearchResult<Observation>>.latestValueOf(code: String, default: String = ""): String =
    map { it.resource }.latestValueOf(code, default)

/** [valueOf] for search results. */
@JvmName("searchResultValueOf")
fun List<SearchResult<Observation>>.valueOf(vararg codes: String, default: String = ""): String =
    map { it.resource }.valueOf(*codes, default = default)
