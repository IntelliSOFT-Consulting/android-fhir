package com.icl.surveillance.ui.patients.custom

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.fhir.get
import com.google.android.fhir.search.search
import com.icl.surveillance.R
import com.icl.surveillance.databinding.FragmentVlLabBinding
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.fhir.forms.ContactFollowUpState
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.fhir.forms.VhfContactTracker
import com.icl.surveillance.fhir.forms.VhfSurvivorMonitoring
import com.icl.surveillance.fhir.forms.VhfSurvivorMonitoring.Record
import com.icl.surveillance.ui.patients.responses.EditChecklistActivity
import com.icl.surveillance.utils.FormatterClass
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/**
 * Ebola Post-Exposure & Survivor Monitoring (EPESM) tab of a VHF record ([FormFields.Epesm]).
 * A contact gets its day-22 release; a confirmed case its survivor monitoring: discharge
 * assessment, follow-up visits, semen / breast milk testing, relapse testing and clearance.
 *
 * Create with [newInstance].
 */
class VhfSurvivorFragment : Fragment() {

    private var _binding: FragmentVlLabBinding? = null
    private val binding get() = _binding!!
    private val fhirEngine by lazy { FhirApplication.fhirEngine(requireContext()) }
    private val forContact: Boolean get() = requireArguments().getBoolean(ARG_CONTACT)
    private val caseDied: Boolean get() = requireArguments().getBoolean(ARG_DIED)

    /** Records the user has opened, kept open when the tab reloads (e.g. after an edit). */
    private val expanded = mutableSetOf<String>()

    /** Question texts per form, in form order (for showing a record's answers). */
    private val questionTexts = mutableMapOf<String, List<Pair<String, String>>>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentVlLabBinding.inflate(inflater, container, false)
        binding.fab.visibility = View.GONE
        binding.getStartedButton.visibility = View.GONE
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun load() {
        val formatter = FormatterClass()
        val patientId = formatter.getSharedPref("patientIdParent", requireContext()) ?: return
        val encounterId = formatter.getSharedPref("encounterId", requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val status = VhfSurvivorMonitoring.load(fhirEngine, patientId, encounterId)
                    val contactState = if (forContact && encounterId != null) {
                        VhfContactTracker.load(fhirEngine, patientId, encounterId)
                    } else null
                    val gender = runCatching { fhirEngine.get<Patient>(patientId).gender?.toCode() }.getOrNull()
                    Triple(status, contactState, gender)
                }
            }
            if (_binding == null) return@launch
            loaded.onSuccess { (status, contactState, gender) ->
                if (forContact) renderContact(status, contactState) else renderSurvivor(status, gender)
            }.onFailure {
                Timber.tag(TAG).e(it, "Could not load EPESM records for Patient/%s", patientId)
                binding.lnParent.removeAllViews()
                binding.tvEmptyMessage.text = "Unable to load these records. Please try again."
                binding.lnEmpty.visibility = View.VISIBLE
            }
        }
    }

    // ---- Part 1: contact release on day 22

    private fun renderContact(status: VhfSurvivorMonitoring.Status, state: ContactFollowUpState?) {
        val parent = startRender()
        val release = status.release
        val openRelease = {
            VhfContactActions.openEpesmForm(
                requireContext(),
                FormFields.Epesm.CONTACT_RELEASE_FORM,
                state?.let(VhfSurvivorMonitoring::releasePrefill).orEmpty()
            )
        }
        when {
            release != null -> parent.addView(
                VhfCards.action(
                    parent, CardTone.SUCCESS, R.drawable.ic_vhf_check_circle,
                    "Released",
                    listOf(
                        release.value(FormFields.Epesm.RELEASE_TYPE),
                        release.value(FormFields.Vhf.DATE_CHECKED).let { if (it.isBlank()) "" else "on ${shown(it)}" },
                    ).filter { it.isNotBlank() }.joinToString(" "),
                )
            )

            state?.windowComplete == true -> parent.addView(
                VhfCards.action(
                    parent, CardTone.INFO, R.drawable.ic_vhf_assignment_done,
                    "Ready for release",
                    "The 21-day monitoring period is over. Confirm the follow-up checks and the release type.",
                    actionText = "Record release", actionIcon = R.drawable.ic_vhf_edit_calendar,
                    onAction = openRelease,
                )
            )

            else -> parent.addView(
                VhfCards.action(
                    parent, CardTone.NEUTRAL, R.drawable.ic_vhf_info,
                    "Release on day 22",
                    state?.let { "Monitoring ends on ${ContactFollowUpState.DISPLAY.format(it.lastDay)}; release is recorded from the next day. Early release needs two negative PCRs." }
                        ?: "Release is recorded on day 22, after the 21-day monitoring period.",
                    actionText = "Record release", actionIcon = R.drawable.ic_vhf_edit_calendar,
                    onAction = openRelease,
                )
            )
        }
        section(parent, "Release", FormFields.Epesm.CONTACT_RELEASE_FORM, listOfNotNull(release), single = true,
            emptyText = "No release recorded yet", onAdd = openRelease) {
            it.value(FormFields.Epesm.RELEASE_TYPE)
        }
    }

    // ---- Parts 2-5 and clearance: confirmed survivor

    private fun renderSurvivor(status: VhfSurvivorMonitoring.Status, gender: String?) {
        val parent = startRender()
        val context = requireContext()
        val clearance = status.clearance
        val relapse = status.relapseAlert
        when {
            caseDied -> parent.addView(
                VhfCards.action(
                    parent, CardTone.NEUTRAL, R.drawable.ic_vhf_info, "Not applicable",
                    "The case outcome is Dead: survivor monitoring does not apply.",
                )
            )

            clearance != null -> parent.addView(
                VhfCards.action(
                    parent, CardTone.SUCCESS, R.drawable.ic_vhf_check_circle, "Cleared",
                    listOf(
                        clearance.value(FormFields.Epesm.CLEARANCE_DATE).let { if (it.isBlank()) "" else "Discharged ${shown(it)}" },
                        clearance.value(FormFields.Epesm.CLEARED_BY_NAME).let { if (it.isBlank()) "" else "by $it" },
                    ).filter { it.isNotBlank() }.joinToString(" "),
                )
            )

            relapse != null -> parent.addView(
                VhfCards.action(
                    parent, CardTone.ALERT, R.drawable.ic_vhf_warning, "Relapse alert",
                    "Previous symptoms returned or worsened at the follow up on " +
                        "${shown(relapse.value(FormFields.Epesm.FOLLOW_UP_DATE))}. Collect a relapse confirmation PCR sample.",
                    actionText = "Record relapse", actionIcon = R.drawable.ic_vhf_edit_calendar,
                    onAction = { VhfContactActions.openEpesmForm(context, FormFields.Epesm.RELAPSE_FORM, relapsePrefill()) },
                )
            )

            else -> parent.addView(
                VhfCards.action(
                    parent, CardTone.INFO, R.drawable.ic_vhf_event_available, "Under survivor monitoring",
                    followUpMessage(status),
                )
            )
        }

        val semen = status.semen
        val milk = status.breastMilk
        parent.addView(
            VhfCards.stats(
                parent,
                listOfNotNull(
                    Triple("Follow ups", status.followUps.size.toString(), CardTone.INFO),
                    Triple("Semen negatives", "${semen.consecutiveNegatives}/${FormFields.Epesm.NEGATIVES_TO_CLEAR}",
                        if (semen.cleared) CardTone.SUCCESS else CardTone.NEUTRAL)
                        .takeIf { gender != "female" || semen.results.isNotEmpty() },
                    Triple("Breast milk negatives", "${milk.consecutiveNegatives}/${FormFields.Epesm.NEGATIVES_TO_CLEAR}",
                        if (milk.cleared) CardTone.SUCCESS else CardTone.NEUTRAL)
                        .takeIf { gender == "female" || milk.results.isNotEmpty() },
                    Triple("Relapses", status.relapses.size.toString(),
                        if (relapse != null) CardTone.ALERT else CardTone.NEUTRAL),
                )
            )
        )

        section(parent, "Discharge assessment", FormFields.Epesm.DISCHARGE_FORM, listOfNotNull(status.discharge),
            single = true, emptyText = "Not recorded yet") {
            "Meets discharge criteria: ${it.value(FormFields.Epesm.MEETS_DISCHARGE_CRITERIA).ifBlank { "-" }}"
        }
        section(parent, "Follow-up visits", FormFields.Epesm.FOLLOW_UP_FORM, status.followUps,
            emptyText = "No follow-up visits yet", dateOf = { it.value(FormFields.Epesm.FOLLOW_UP_DATE) },
            toneOf = { if (it.answered(FormFields.Epesm.SYMPTOMS_RETURNED, "yes")) CardTone.ALERT else CardTone.NEUTRAL }) {
            val symptoms = if (it.answered(FormFields.Epesm.SYMPTOMS_RETURNED, "yes")) "Symptoms returned" else "No symptoms returned"
            status.dayAfterDischarge(it)?.let { day -> "Day $day after discharge · $symptoms" } ?: symptoms
        }
        section(parent, "Semen / breast milk testing", FormFields.Epesm.PERSISTENCE_FORM, status.tests,
            emptyText = "No samples tested yet",
            prefill = { VhfSurvivorMonitoring.persistencePrefill(status, gender) },
            dateOf = { it.value(FormFields.Epesm.SEMEN_DATE).ifBlank { it.value(FormFields.Epesm.BREAST_MILK_DATE) } }) {
            val sample = it.value(FormFields.Epesm.SAMPLE_TYPE)
            val result = it.value(FormFields.Epesm.SEMEN_RESULT).ifBlank { it.value(FormFields.Epesm.BREAST_MILK_RESULT) }
            "$sample: ${result.ifBlank { "-" }}"
        }
        listOf("Semen" to semen, "Breast milk" to milk).forEach { (name, series) ->
            if (series.cleared) {
                parent.addView(SummaryRows.note(context,
                    "$name cleared: two consecutive negative results (${series.clearedOn?.let { shown(it.toString()) } ?: "date not recorded"}).",
                    R.color.selection_sheet_secondary_icon_tint))
            } else if (series.results.isNotEmpty()) {
                parent.addView(SummaryRows.note(context,
                    "$name: ${series.consecutiveNegatives} of ${FormFields.Epesm.NEGATIVES_TO_CLEAR} consecutive negative results; test again monthly."))
            }
        }
        section(parent, "Relapse testing", FormFields.Epesm.RELAPSE_FORM, status.relapses,
            emptyText = "No relapse recorded", prefill = { relapsePrefill() }) {
            val result = it.value(FormFields.Epesm.RELAPSE_PCR_RESULT)
            val action = it.value(FormFields.Epesm.RELAPSE_ACTION)
            listOf(result, action).filter { v -> v.isNotBlank() }.joinToString(" · ")
                .ifBlank { "Relapse suspected: ${it.value(FormFields.Epesm.RELAPSE_SUSPECTED)}" }
        }
        section(parent, "Clearance", FormFields.Epesm.CLEARANCE_FORM, listOfNotNull(clearance), single = true,
            emptyText = "Not cleared yet", dateOf = { it.value(FormFields.Epesm.CLEARANCE_DATE) }) {
            "Cleared by ${it.value(FormFields.Epesm.CLEARED_BY_NAME).ifBlank { "-" }}"
        }
    }

    /** Where the survivor is in the follow-up visits (day 22 after discharge, for 14 days). */
    private fun followUpMessage(status: VhfSurvivorMonitoring.Status): String {
        val start = status.followUpStart
        val end = status.followUpEnd
        if (start == null || end == null) return "Start with the discharge assessment at discharge from isolation."
        val today = LocalDate.now()
        val window = "day ${FormFields.Epesm.FOLLOW_UP_START_DAY} after discharge, for ${FormFields.Epesm.FOLLOW_UP_DAYS} days"
        return when {
            today < start -> "Follow-up visits start on ${start.format(SHOWN_DATE)} ($window) and run until ${end.format(SHOWN_DATE)}."
            today <= end -> "Follow-up visits: day ${ChronoUnit.DAYS.between(start, today) + 1} of ${FormFields.Epesm.FOLLOW_UP_DAYS}, until ${end.format(SHOWN_DATE)}."
            else -> "Follow-up visits ended on ${end.format(SHOWN_DATE)}. Semen / breast milk testing continues monthly from month 3 after onset."
        }
    }

    /** A relapse record opened from an alert: relapse suspected. */
    private fun relapsePrefill() = mapOf(FormFields.Epesm.RELAPSE_SUSPECTED to "yes")

    // ---- Shared

    private fun startRender(): ViewGroup {
        binding.lnEmpty.visibility = View.GONE
        return binding.lnParent.also { it.removeAllViews() }
    }

    /**
     * One form's records under a header with its add button ([single]: one record, edited in
     * place once saved). Each record is a card with [headline] and its date that folds open to
     * show all answers and an Edit button.
     */
    private fun section(
        parent: ViewGroup,
        title: String,
        form: String,
        records: List<Record>,
        single: Boolean = false,
        emptyText: String,
        prefill: () -> Map<String, String> = { emptyMap() },
        onAdd: (() -> Unit)? = null,
        dateOf: (Record) -> String = { it.value(FormFields.Vhf.DATE_CHECKED) },
        toneOf: (Record) -> CardTone = { CardTone.NEUTRAL },
        headline: (Record) -> String,
    ) {
        val context = requireContext()
        val add = onAdd ?: { VhfContactActions.openEpesmForm(context, form, prefill()) }
        // A single record is edited from its card, so only an empty section offers to add.
        parent.addView(
            if (single && records.isNotEmpty()) VhfCards.sectionHeader(parent, title)
            else VhfCards.sectionHeader(parent, title, if (single) "Record" else "Add", R.drawable.ic_vhf_person_add, add)
        )
        if (records.isEmpty()) {
            parent.addView(SummaryRows.note(context, emptyText))
            return
        }
        records.forEach { record ->
            val date = dateOf(record).ifBlank { record.createdAt }
            val fields = questionsOf(form).mapNotNull { (linkId, text) ->
                record.value(linkId).takeIf { it.isNotBlank() }?.let { text to it }
            }
            parent.addView(
                VhfCards.record(
                    parent,
                    title = headline(record).ifBlank { "Recorded" },
                    subtitle = shown(date),
                    fields = fields,
                    expanded = record.id in expanded,
                    onToggle = { open -> if (open) expanded += record.id else expanded -= record.id },
                    tone = toneOf(record),
                    onEdit = { editRecord(record, form) },
                )
            )
        }
    }

    /** Opens the saved record's form with its answers; saving updates it in place. */
    private fun editRecord(record: Record, form: String) {
        val context = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val responseId = withContext(Dispatchers.IO) {
                runCatching {
                    fhirEngine.search<QuestionnaireResponse> {
                        filter(QuestionnaireResponse.ENCOUNTER, { value = "Encounter/${record.id}" })
                    }.firstOrNull()?.resource?.idElement?.idPart
                }.getOrNull()
            }
            if (!isAdded) return@launch
            if (responseId.isNullOrBlank()) {
                Toast.makeText(context, "This record cannot be edited (its form was not found).", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val formatter = FormatterClass()
            formatter.saveSharedPref("resourceId", responseId, context)
            formatter.saveSharedPref("questionnaire", form, context)
            startActivity(Intent(context, EditChecklistActivity::class.java))
        }
    }

    /** (linkId, text) of every question in [form], in order. */
    private fun questionsOf(form: String): List<Pair<String, String>> = questionTexts.getOrPut(form) {
        runCatching {
            val json = requireContext().assets.open(form).bufferedReader().use { it.readText() }
            buildList {
                fun walk(items: JSONArray?) {
                    if (items == null) return
                    for (i in 0 until items.length()) {
                        val item: JSONObject = items.getJSONObject(i)
                        val type = item.optString("type")
                        if (type != "group" && type != "display") add(item.getString("linkId") to item.optString("text"))
                        walk(item.optJSONArray("item"))
                    }
                }
                walk(JSONObject(json).optJSONArray("item"))
            }
        }.getOrDefault(emptyList())
    }

    /** "2026-10-08" or "2026-10-08 11:40:12" -> "08 Oct 2026"; anything else as is. */
    private fun shown(value: String): String =
        VhfContactTracker.parseDate(value)?.format(SHOWN_DATE) ?: value.ifBlank { "Date not recorded" }

    companion object {
        private const val TAG = "EPESM"
        private const val ARG_CONTACT = "contact"
        private const val ARG_DIED = "died"
        private val SHOWN_DATE = DateTimeFormatter.ofPattern("dd MMM yyyy")

        /** Tab titles: a contact's release (Part 1), a confirmed case's survivor monitoring. */
        const val CONTACT_TAB = "Post exposure"
        const val SURVIVOR_TAB = "Survivor"

        /**
         * @param contact the record is a contact (day-22 release) rather than a confirmed case.
         * @param died the case outcome is Dead (survivor monitoring does not apply).
         */
        fun newInstance(contact: Boolean, died: Boolean = false) = VhfSurvivorFragment().apply {
            arguments = Bundle().apply {
                putBoolean(ARG_CONTACT, contact)
                putBoolean(ARG_DIED, died)
            }
        }
    }
}
