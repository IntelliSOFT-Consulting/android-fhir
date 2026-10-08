package com.icl.surveillance.ui.patients.custom

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.icl.surveillance.R
import com.icl.surveillance.clients.AddClientFragment.Companion.QUESTIONNAIRE_FILE_PATH_KEY
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.fhir.forms.CaseResponse
import com.icl.surveillance.fhir.forms.ContactFollowUpState
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.fhir.forms.FormPrefill
import com.icl.surveillance.fhir.forms.VhfDemoMode
import com.icl.surveillance.ui.patients.AddCaseActivity
import com.icl.surveillance.ui.patients.responses.EditChecklistActivity
import com.icl.surveillance.utils.FormatterClass
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /** Set while "Convert to case" waits for its monitoring outcome to be saved (AddCaseActivity). */
    const val PREF_CONVERT_AFTER_MONITORING = "vhfConvertAfterMonitoring"

    /** Set when the case form is opened to convert a contact: Type of case starts as Suspected. */
    const val PREF_CONVERT_TO_SUSPECTED = "vhfConvertToSuspected"

    /**
     * "Convert to case" (data dictionary action), in two steps:
     * 1. the Contact Monitoring form opens with the outcome filled in (Became a suspected case, the
     *    date of the first symptomatic follow up and the days missed before it), for sign-off;
     * 2. once it is saved, the contact's case form opens with Type of case set to Suspected, so
     *    Clinical Information, Clinical Care and Laboratory can be completed.
     * When the outcome is already recorded, it goes straight to step 2.
     */
    fun convertToCase(context: Context, state: ContactFollowUpState?) {
        val outcomeRecorded = state?.monitoringStatus
            .equals(FormFields.Vhf.STATUS_BECAME_CASE, ignoreCase = true)
        val message = if (outcomeRecorded) {
            "The record will open with Type of case set to Suspected. Complete Clinical " +
                "Information, Clinical Care and Laboratory (specimen collection), then save."
        } else {
            "Step 1: confirm the monitoring outcome (Became a suspected case) and sign it off.\n" +
                "Step 2: the record opens with Type of case set to Suspected. Complete Clinical " +
                "Information, Clinical Care and Laboratory (specimen collection), then save."
        }
        MaterialAlertDialogBuilder(context)
            .setIcon(R.drawable.ic_vhf_warning)
            .setTitle("Convert to suspected case?")
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                if (outcomeRecorded || state == null) {
                    editRecord(context, convertToSuspected = true)
                } else {
                    recordBecameCase(context, state)
                }
            }
            .show()
    }

    /** Step 1 of "Convert to case": the monitoring form with the outcome pre-filled. */
    private fun recordBecameCase(context: Context, state: ContactFollowUpState) {
        val visit = state.firstSymptomaticVisit
        val day = visit?.day
        val outcomeDate = visit?.date ?: day?.let { state.dateOfDay(it) } ?: LocalDate.now()
        val missed = if (day != null) state.missedDays.count { it < day } else state.missedDays.size
        FormatterClass().saveSharedPref(PREF_CONVERT_AFTER_MONITORING, "true", context)
        openChildForm(
            context,
            FormFields.Vhf.CONTACT_MONITORING_FORM,
            FormFields.Vhf.CONTACT_MONITORING_TITLE,
            mapOf(
                FormFields.Vhf.MONITORING_STATUS to FormFields.Vhf.STATUS_BECAME_CASE_CODE,
                FormFields.Vhf.FINAL_OUTCOME_DATE to outcomeDate.toString(),
                FormFields.Vhf.MISSED_FOLLOW_UP_DAYS to missed.toString(),
            )
        )
    }

    /**
     * Opens the record's VHF case form for editing, pre-filled with its saved answers. The case
     * response is looked up here (not taken from "resourceId", which may still hold the patient
     * id or point at a follow-up response).
     */
    fun editRecord(
        context: Context,
        convertToSuspected: Boolean = false,
        onOpened: () -> Unit = {},
    ) {
        val formatter = FormatterClass()
        val patientId = formatter.getSharedPref("patientIdParent", context)
        val encounterId = formatter.getSharedPref("encounterId", context)
        val scope = lifecycleOwnerOf(context)?.lifecycleScope ?: return
        if (patientId.isNullOrBlank()) {
            Toast.makeText(context, "Unable to open this record. Please reopen it.", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val responseId = withContext(Dispatchers.IO) {
                CaseResponse.findId(FhirApplication.fhirEngine(context), patientId, encounterId)
            }
            if (responseId.isBlank()) {
                Toast.makeText(context, "The case form for this record was not found.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            formatter.saveSharedPref("resourceId", responseId, context)
            formatter.saveSharedPref("questionnaire", FormFields.Vhf.CASE_FORM, context)
            if (convertToSuspected) {
                formatter.saveSharedPref(PREF_CONVERT_TO_SUSPECTED, "true", context)
            } else {
                formatter.deleteSharedPref(PREF_CONVERT_TO_SUSPECTED, context)
            }
            context.startActivity(Intent(context, EditChecklistActivity::class.java))
            onOpened()
        }
    }

    private fun lifecycleOwnerOf(context: Context): LifecycleOwner? {
        var current: Context? = context
        while (current != null) {
            if (current is LifecycleOwner) return current
            current = (current as? ContextWrapper)?.baseContext
        }
        return null
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
