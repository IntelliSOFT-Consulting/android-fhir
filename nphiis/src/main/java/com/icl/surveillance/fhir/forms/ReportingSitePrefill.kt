package com.icl.surveillance.fhir.forms

import android.content.Context
import com.icl.surveillance.models.LocationLevel
import com.icl.surveillance.models.UserRole
import com.icl.surveillance.utils.FormatterClass

/**
 * The hidden Reporting Site answers of the case forms for the signed-in user: the role (which
 * section of the form shows) and the user's assigned area, from the profile. Shared by the
 * synthetic data generator and the bulk upload, so both fill the reporting site the same way.
 */
object ReportingSitePrefill {

    /** linkId -> answer, in form order. */
    fun hiddenAnswers(context: Context): List<Pair<String, String>> {
        val formatter = FormatterClass()
        fun pref(key: String) = formatter.getSharedPref(key, context).orEmpty()
        val role = UserRole.fromAny(pref("practitionerRole"))
        return when (role?.scope) {
            LocationLevel.COUNTY -> listOf(
                FormFields.ReportingSite.USER_ROLE to "COUNTY_DISEASE_SURVEILLANCE_OFFICER",
                FormFields.ReportingSite.USER_COUNTY to pref("county"),
            )

            LocationLevel.SUB_COUNTY, LocationLevel.WARD -> listOf(
                FormFields.ReportingSite.USER_ROLE to "SUBCOUNTY_DISEASE_SURVEILLANCE_OFFICER",
                FormFields.ReportingSite.USER_COUNTY to pref("county"),
                FormFields.ReportingSite.USER_SUB_COUNTY to pref("subCounty"),
            )

            LocationLevel.FACILITY -> listOf(
                FormFields.ReportingSite.USER_ROLE to "VACCINATOR",
                FormFields.ReportingSite.USER_FACILITY to pref("facility"),
                FormFields.ReportingSite.USER_WARD to pref("ward"),
                FormFields.ReportingSite.USER_SUB_COUNTY to pref("subCounty"),
                FormFields.ReportingSite.USER_COUNTY to pref("county"),
            )

            else -> listOf(FormFields.ReportingSite.USER_ROLE to "ADMINISTRATOR")
        }
    }
}
