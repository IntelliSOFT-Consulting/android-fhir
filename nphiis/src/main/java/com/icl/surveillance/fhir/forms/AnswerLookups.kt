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
            ?.value?.asStringValue()?.takeIf { it.isNotBlank() }
    } ?: default

/** [valueOf] for search results. */
@JvmName("searchResultValueOf")
fun List<SearchResult<Observation>>.valueOf(vararg codes: String, default: String = ""): String =
    map { it.resource }.valueOf(*codes, default = default)
