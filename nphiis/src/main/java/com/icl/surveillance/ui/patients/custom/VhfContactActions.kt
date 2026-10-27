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
import com.icl.surveillance.fhir.forms.VhfForms
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
        val values = buildMap {
            put(FormFields.Vhf.FOLLOW_UP_DAY, day.toString())
            put(FormFields.Vhf.FOLLOW_UP_DATE, date.toString())
            // Paper form header: the volunteer / CHP (the logged-in user) and where the
            // follow up was done (the contact's residence); both can be corrected.
            putAll(tracerDetails(context))
            putAll(state.residence)
        }
        val scope = lifecycleOwnerOf(context)?.lifecycleScope
        if (scope == null) {
            openChildForm(context, FormFields.Vhf.CONTACT_FOLLOW_UP_FORM, FormFields.Vhf.CONTACT_FOLLOW_UP_TITLE, values)
            return
        }
        scope.launch {
            val locations = withContext(Dispatchers.IO) {
                runCatching { residenceLocations(context) }.getOrDefault(emptyMap())
            }
            openChildForm(
                context,
                FormFields.Vhf.CONTACT_FOLLOW_UP_FORM,
                FormFields.Vhf.CONTACT_FOLLOW_UP_TITLE,
                values + locations,
            )
        }
    }

    /**
     * The contact's residence county, sub county and ward as picked on its registration form
     * (location references), for the follow-up form's location lists.
     */
    private suspend fun residenceLocations(context: Context): Map<String, String> {
        val formatter = FormatterClass()
        val patientId = formatter.getSharedPref("patientIdParent", context)?.takeIf { it.isNotBlank() }
            ?: return emptyMap()
        val encounterId = formatter.getSharedPref("encounterId", context)
        val response = CaseResponse.find(FhirApplication.fhirEngine(context), patientId, encounterId)
            ?: return emptyMap()
        val targets = mapOf(
            FormFields.Residence.COUNTY to FormFields.Vhf.FOLLOW_UP_COUNTY_LOCATION,
            FormFields.Residence.SUB_COUNTY to FormFields.Vhf.FOLLOW_UP_SUB_COUNTY_LOCATION,
            FormFields.Residence.WARD to FormFields.Vhf.FOLLOW_UP_WARD_LOCATION,
        )
        val result = HashMap<String, String>()
        fun visit(items: List<org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemComponent>) {
            items.forEach { item ->
                targets[item.linkId]?.let { target ->
                    val ref = item.answer.firstOrNull()?.value as? org.hl7.fhir.r4.model.Reference
                    val id = ref?.referenceElement?.idPart
                    if (!id.isNullOrBlank()) result[target] = FormPrefill.locationReference(id, ref.display)
                }
                visit(item.item)
                item.answer.forEach { visit(it.item) }
            }
        }
        visit(response.item)
        return result
    }

    /** "Checked by" name and email (the logged-in user) and today's date, for the Official section. */
    private fun officialDetails(context: Context): Map<String, String> {
        val user = tracerDetails(context)
        return buildMap {
            user[FormFields.Vhf.FOLLOW_UP_TRACER_NAME]?.let { put(FormFields.Vhf.CHECKED_BY_NAME, it) }
            user[FormFields.Vhf.FOLLOW_UP_TRACER_EMAIL]?.let { put(FormFields.Vhf.CHECKED_BY_EMAIL, it) }
            put(FormFields.Vhf.DATE_CHECKED, LocalDate.now().toString())
        }
    }

    /** The logged-in user as the volunteer / CHP: name, mobile number and email address. */
    private fun tracerDetails(context: Context): Map<String, String> {
        val formatter = FormatterClass()
        fun pref(key: String) = formatter.getSharedPref(key, context)
            ?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) && it != "N/A" }
        val name = pref("fullNames")
            ?: listOfNotNull(pref("firstName"), pref("lastName")).joinToString(" ").takeIf { it.isNotBlank() }
        // The form accepts a 10-digit mobile number (07xxxxxxxx).
        val phone = pref("phone")?.filter { it.isDigit() }?.let { digits ->
            when {
                digits.length == 10 -> digits
                digits.length == 12 && digits.startsWith("254") -> "0" + digits.drop(3)
                else -> null
            }
        }
        return buildMap {
            name?.let { put(FormFields.Vhf.FOLLOW_UP_TRACER_NAME, it) }
            phone?.let { put(FormFields.Vhf.FOLLOW_UP_TRACER_PHONE, it) }
            pref("email")?.let { put(FormFields.Vhf.FOLLOW_UP_TRACER_EMAIL, it) }
        }
    }

    /** Opens "Contact Monitoring"; after day 21 a contact without symptoms is suggested for release. */
    fun updateMonitoring(context: Context, state: ContactFollowUpState?) {
        val prefill = buildMap {
            if (state != null) {
                put(FormFields.Vhf.MISSED_FOLLOW_UP_DAYS, state.missedDays.size.toString())
                val death = state.deathVisit
                if (death != null) {
                    // Recorded as died at a daily follow up: the outcome is Died, on that date.
                    put(FormFields.Vhf.MONITORING_STATUS, FormFields.Vhf.STATUS_DIED_CODE)
                    (death.deathDate ?: death.date)?.let { put(FormFields.Vhf.FINAL_OUTCOME_DATE, it.toString()) }
                } else if (state.windowComplete && state.firstSymptomaticVisit == null) {
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
     * "Record outcome" of a suspected / confirmed case (e.g. the case died) without editing the
     * case form; [current] is the outcome the case record holds now. Saving copies it onto the
     * case record (VhfCaseOutcome).
     */
    fun recordOutcome(context: Context, current: Map<String, String>) {
        openChildForm(context, FormFields.Vhf.CASE_OUTCOME_FORM, FormFields.Vhf.CASE_OUTCOME_TITLE, current)
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
            val response = withContext(Dispatchers.IO) {
                CaseResponse.find(FhirApplication.fhirEngine(context), patientId, encounterId)
            }
            val responseId = response?.idElement?.idPart.orEmpty()
            if (responseId.isBlank()) {
                Toast.makeText(context, "The case form for this record was not found.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            formatter.saveSharedPref("resourceId", responseId, context)
            // A contact registered with the contact form is edited in it; converting a contact to
            // a case always opens the case form (the contact form's questions are a subset of it).
            val form = if (convertToSuspected) FormFields.Vhf.CASE_FORM else VhfForms.formFor(response)
            formatter.saveSharedPref("questionnaire", form, context)
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

    /**
     * Opens an EPESM form (survivor monitoring, contact release) as a new record of the case,
     * with [prefill]. The Clearance's "Cleared by" is the logged-in user, today.
     */
    fun openEpesmForm(context: Context, questionnaireFile: String, prefill: Map<String, String> = emptyMap()) {
        val title = FormFields.Epesm.TITLES[questionnaireFile] ?: return
        val values = if (questionnaireFile == FormFields.Epesm.CLEARANCE_FORM) {
            val official = officialDetails(context)
            buildMap {
                official[FormFields.Vhf.CHECKED_BY_NAME]?.let { put(FormFields.Epesm.CLEARED_BY_NAME, it) }
                put(FormFields.Epesm.CLEARED_DATE, LocalDate.now().toString())
            } + prefill
        } else prefill
        openChildForm(context, questionnaireFile, title, values)
    }

    private fun openChildForm(
        context: Context,
        questionnaireFile: String,
        title: String,
        prefill: Map<String, String>,
    ) {
        // Contact monitoring, case outcome and the EPESM forms: the Official section is the
        // logged-in user, today.
        val values = if (questionnaireFile == FormFields.Vhf.CONTACT_MONITORING_FORM ||
            questionnaireFile == FormFields.Vhf.CASE_OUTCOME_FORM ||
            (questionnaireFile in FormFields.Epesm.TITLES && questionnaireFile != FormFields.Epesm.CLEARANCE_FORM)
        ) {
            officialDetails(context) + prefill
        } else prefill
        FormPrefill.stage(context, questionnaireFile, values)
        FormatterClass().saveSharedPref("questionnaire", questionnaireFile, context)
        FormatterClass().saveSharedPref("title", title, context)
        context.startActivity(
            Intent(context, AddCaseActivity::class.java)
                .putExtra(QUESTIONNAIRE_FILE_PATH_KEY, questionnaireFile)
        )
    }
}
