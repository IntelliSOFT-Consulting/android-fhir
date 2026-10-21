package com.icl.surveillance.fhir.forms

import android.content.Context
import com.icl.surveillance.utils.FormatterClass

/**
 * VHF module lists: "Suspected Cases" (suspected / probable / confirmed) and "Contact List" are
 * listed separately so contacts are not mixed in with cases. Set when a list is opened from the
 * VHF menu; VHF Training lists show everything (no split).
 */
object VhfListMode {
    const val CASES = "cases"
    const val CONTACTS = "contacts"
    const val NONE = ""
    private const val PREF = "vhfListMode"

    fun set(context: Context, mode: String) = FormatterClass().saveSharedPref(PREF, mode, context)

    fun get(context: Context): String = FormatterClass().getSharedPref(PREF, context).orEmpty()

    fun isContact(caseType: String?): Boolean =
        caseType?.trim().equals(FormFields.Vhf.CASE_TYPE_CONTACT, ignoreCase = true)

    /** Whether a row of "Type of case" [caseType] belongs in the list [mode]. */
    fun matches(mode: String, caseType: String?): Boolean = when (mode) {
        CASES -> !isContact(caseType)
        CONTACTS -> isContact(caseType)
        else -> true
    }
}
