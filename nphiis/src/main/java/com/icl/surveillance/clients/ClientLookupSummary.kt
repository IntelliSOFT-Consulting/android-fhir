package com.icl.surveillance.clients

import com.icl.surveillance.R
import com.icl.surveillance.models.ClientDemographics
import java.util.Locale

internal object ClientLookupSummary {
    fun rows(person: ClientDemographics): List<Pair<Int, String>> = listOf(
        R.string.client_lookup_gender to person.sex?.trim()?.lowercase(Locale.ROOT)
            ?.replaceFirstChar { it.titlecase(Locale.ROOT) },
        R.string.client_lookup_phone to person.phone,
        R.string.client_lookup_id_number to person.identificationNumber,
        R.string.client_lookup_dob to person.dateOfBirth,
    ).mapNotNull { (label, value) ->
        value?.trim()?.takeIf { it.isNotEmpty() }?.let { label to it }
    }
}
