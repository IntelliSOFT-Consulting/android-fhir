package com.icl.surveillance.ui.patients.custom

import android.content.Context
import android.content.Intent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.icl.surveillance.R
import com.icl.surveillance.clients.AddClientFragment.Companion.QUESTIONNAIRE_FILE_PATH_KEY
import com.icl.surveillance.fhir.forms.ContactFollowUpState
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.fhir.forms.FormPrefill
import com.icl.surveillance.fhir.forms.VhfDemoMode
import com.icl.surveillance.ui.patients.AddCaseActivity
import com.icl.surveillance.ui.patients.responses.EditChecklistActivity
import com.icl.surveillance.utils.FormatterClass
import java.time.LocalDate

/**
 * Actions on a VHF contact opened from its case summary (prefs patientIdParent / encounterId /
 * resourceId already point at the contact). Answers worked out from the record — the follow-up
 * day, the missed days — are pre-filled; the officer can still correct them.
 */
object VhfContactActions {

    /**
     * Opens the daily "Contact Follow Up" form for [day], pre-filled with that day's number and
     * date (day N is N days after the date of contact).
     */
    fun recordFollowUp(context: Context, state: ContactFollowUpState, day: Int) {
        // Demo mode dates every follow up today (the form does not accept future dates).
        val date = if (day == state.daysSinceExposure || VhfDemoMode.enabled) LocalDate.now()
        else state.dateOfDay(day)
        openChildForm(
            context,
            FormFields.Vhf.CONTACT_FOLLOW_UP_FORM,
            FormFields.Vhf.CONTACT_FOLLOW_UP_TITLE,
            mapOf(
                FormFields.Vhf.FOLLOW_UP_DAY to day.toString(),
                FormFields.Vhf.FOLLOW_UP_DATE to date.toString(),
            )
        )
    }

    /** Opens "Contact Monitoring"; after day 21 a contact without symptoms is suggested for release. */
    fun updateMonitoring(context: Context, state: ContactFollowUpState?) {
        val prefill = buildMap {
            if (state != null) {
                put(FormFields.Vhf.MISSED_FOLLOW_UP_DAYS, state.missedDays.size.toString())
                if (state.windowComplete && state.firstSymptomaticVisit == null) {
                    put(FormFields.Vhf.MONITORING_STATUS, FormFields.Vhf.STATUS_COMPLETED_CODE)
                }
            }
        }
        openChildForm(
            context,
            FormFields.Vhf.CONTACT_MONITORING_FORM,
            FormFields.Vhf.CONTACT_MONITORING_TITLE,
            prefill
        )
    }

    /**
     * "Convert to case" (data dictionary action): after confirmation, opens the contact's own case
     * form so the officer changes Type of case to Suspected and completes Clinical and Laboratory.
     */
    fun convertToCase(context: Context) {
        MaterialAlertDialogBuilder(context)
            .setIcon(R.drawable.ic_vhf_warning)
            .setTitle("Convert to suspected case?")
            .setMessage(
                "The contact's record will open for editing. Change Type of case to Suspected, " +
                    "then complete Clinical Information and Laboratory (specimen collection)."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ -> editRecord(context) }
            .show()
    }

    /** Opens the contact's VHF case form for editing. */
    fun editRecord(context: Context) {
        FormatterClass().saveSharedPref("questionnaire", FormFields.Vhf.CASE_FORM, context)
        context.startActivity(Intent(context, EditChecklistActivity::class.java))
    }

    private fun openChildForm(
        context: Context,
        questionnaireFile: String,
        title: String,
        prefill: Map<String, String>,
    ) {
        FormPrefill.stage(context, questionnaireFile, prefill)
        FormatterClass().saveSharedPref("questionnaire", questionnaireFile, context)
        FormatterClass().saveSharedPref("title", title, context)
        context.startActivity(
            Intent(context, AddCaseActivity::class.java)
                .putExtra(QUESTIONNAIRE_FILE_PATH_KEY, questionnaireFile)
        )
    }
}
