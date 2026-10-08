package com.icl.surveillance.ui.patients.custom

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.fhir.get
import com.icl.surveillance.R
import com.icl.surveillance.clients.AddClientFragment.Companion.QUESTIONNAIRE_FILE_PATH_KEY
import com.icl.surveillance.clients.AddParentCaseActivity
import com.icl.surveillance.databinding.FragmentVlLabBinding
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.fhir.forms.CaseSlugs
import com.icl.surveillance.fhir.forms.CaseTypes
import com.icl.surveillance.fhir.forms.ContactFollowUpState
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.fhir.forms.FormPrefill
import com.icl.surveillance.fhir.forms.VhfContactTracker
import com.icl.surveillance.fhir.forms.valueOf
import com.icl.surveillance.ui.patients.SummarizedActivity
import com.icl.surveillance.utils.FormatterClass
import com.google.android.fhir.search.search
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient

/**
 * "Contacts" tab of a VHF case: every person registered as a contact of this case, with where
 * each is in their 21-day follow up. "Register a contact" opens the VHF case form with Type of
 * case = Contact, the case's disease and Human contact exposure already selected, and links the
 * new record to this case (Patient.link).
 */
class VhfContactsFragment : Fragment() {

    private data class ContactRow(
        val patientId: String,
        val encounterId: String,
        val name: String,
        val epid: String,
        val state: ContactFollowUpState?,
    )

    private var _binding: FragmentVlLabBinding? = null
    private val binding get() = _binding!!
    private val fhirEngine by lazy { FhirApplication.fhirEngine(requireContext()) }

    /** This case's record prefs, put back when returning from a contact or a registration. */
    private var savedPrefs: Map<String, String?> = emptyMap()
    private val returnToCase =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { restorePrefs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Survives the case screen being recreated while a contact is open.
        savedInstanceState?.getBundle(STATE_SAVED_PREFS)?.let { bundle ->
            savedPrefs = RECORD_PREFS.associateWith { bundle.getString(it) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (savedPrefs.isNotEmpty()) {
            outState.putBundle(
                STATE_SAVED_PREFS,
                Bundle().apply { savedPrefs.forEach { (key, value) -> putString(key, value) } }
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVlLabBinding.inflate(inflater, container, false)
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
        val sourcePatientId = formatter.getSharedPref("patientIdParent", requireContext()) ?: return
        val sourceEncounterId = formatter.getSharedPref("encounterId", requireContext()) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    loadContacts(sourcePatientId) to loadSource(sourcePatientId, sourceEncounterId)
                }
            }
            if (_binding == null) return@launch
            loaded.onSuccess { (rows, source) -> render(rows, source) }
                .onFailure {
                    binding.fab.visibility = View.GONE
                    binding.getStartedButton.visibility = View.GONE
                    binding.tvEmptyMessage.text = "Unable to load contacts. Please try again."
                    binding.lnEmpty.visibility = View.VISIBLE
                }
        }
    }

    private data class SourceCase(val patientId: String, val name: String, val disease: String, val diseaseOther: String)

    private suspend fun loadSource(patientId: String, encounterId: String): SourceCase {
        val answers = fhirEngine.search<Observation> {
            filter(Observation.ENCOUNTER, { value = "Encounter/$encounterId" })
        }
        val name = runCatching { fhirEngine.get<Patient>(patientId).nameFirstRep.nameAsSingleString }
            .getOrNull().orEmpty()
        return SourceCase(
            patientId = patientId,
            name = name,
            disease = answers.valueOf(FormFields.Vhf.DISEASE),
            diseaseOther = answers.valueOf(FormFields.Vhf.DISEASE_OTHER),
        )
    }

    private suspend fun loadContacts(sourcePatientId: String): List<ContactRow> =
        VhfContactTracker.linkedContactIds(fhirEngine, sourcePatientId).mapNotNull { id ->
            val patient = runCatching { fhirEngine.get<Patient>(id) }.getOrNull() ?: return@mapNotNull null
            val encounterId = patient.identifier.firstOrNull { it.system == CaseSlugs.VHF }?.value
                ?: return@mapNotNull null
            ContactRow(
                patientId = id,
                encounterId = encounterId,
                name = patient.nameFirstRep.nameAsSingleString.ifBlank { "Unnamed contact" },
                epid = patient.identifier.firstOrNull { it.type.codingFirstRep.code == "EPID" }?.value.orEmpty(),
                state = VhfContactTracker.load(fhirEngine, id, encounterId),
            )
        }

    private fun render(rows: List<ContactRow>, source: SourceCase) {
        binding.lnEmpty.visibility = View.GONE
        val parent = binding.lnParent
        parent.removeAllViews()

        if (rows.isEmpty()) {
            binding.fab.visibility = View.GONE
            parent.addView(
                VhfCards.action(
                    parent, CardTone.INFO, R.drawable.ic_vhf_person_add,
                    "No contacts registered yet",
                    "",
                    actionText = "Register contact",
                    actionIcon = R.drawable.ic_vhf_person_add,
                    onAction = { registerContact(source) }
                )
            )
            return
        }

        binding.fab.visibility = View.GONE

        val states = rows.mapNotNull { it.state }.filter { it.isContact }
        val symptomatic = states.count { !it.isClosed && it.firstSymptomaticVisit != null }
        val converted = rows.count { it.state != null && !it.state.isContact }
        parent.addView(
            VhfCards.stats(
                parent,
                buildList {
                    add(Triple("Under follow up", states.count { !it.isClosed }.toString(), CardTone.INFO))
                    add(Triple("Due today", states.count { it.isDueToday }.toString(), CardTone.WARNING))
                    add(Triple("Symptomatic", symptomatic.toString(), if (symptomatic > 0) CardTone.ALERT else CardTone.NEUTRAL))
                    add(Triple("Closed", states.count { it.isClosed }.toString(), CardTone.SUCCESS))
                    if (converted > 0) add(Triple("Converted", converted.toString(), CardTone.NEUTRAL))
                }
            )
        )
        if (symptomatic > 0) {
            parent.addView(
                VhfCards.action(
                    parent, CardTone.ALERT, R.drawable.ic_vhf_warning,
                    if (symptomatic == 1) "1 contact is symptomatic" else "$symptomatic contacts are symptomatic",
                    ""
                )
            )
        }

        parent.addView(
            VhfCards.sectionHeader(
                parent, "Contacts (${rows.size})", "Register contact", R.drawable.ic_vhf_person_add
            ) { registerContact(source) }
        )
        rows.sortedWith(compareBy({ priority(it.state) }, { it.name })).forEach { row ->
            val state = row.state
            val (status, tone) = when {
                state == null -> "Record unavailable" to CardTone.NEUTRAL
                !state.isContact -> "Converted · ${state.caseType.ifBlank { "case" }}" to CardTone.NEUTRAL
                state.isClosed -> state.monitoringStatus to
                    if (state.monitoringStatus == FormFields.Vhf.STATUS_COMPLETED) CardTone.SUCCESS else CardTone.NEUTRAL
                state.firstSymptomaticVisit != null -> state.summary to CardTone.ALERT
                state.isDueToday -> state.summary to CardTone.INFO
                state.missedDays.isNotEmpty() ->
                    "${state.summary} · ${state.missedDays.size} missed" to CardTone.WARNING
                else -> state.summary to CardTone.SUCCESS
            }
            parent.addView(VhfCards.contact(parent, row.name, row.epid, status, tone) { openContact(row) })
        }
    }

    /** Symptomatic first, then due today, then the rest; closed and converted contacts last. */
    private fun priority(state: ContactFollowUpState?): Int = when {
        state == null -> 5
        !state.isContact -> 4
        state.isClosed -> 3
        state.firstSymptomaticVisit != null -> 0
        state.isDueToday -> 1
        else -> 2
    }

    private fun openContact(row: ContactRow) {
        val context = requireContext()
        savePrefs()
        val formatter = FormatterClass()
        formatter.saveSharedPref("patientId", row.patientId, context)
        formatter.saveSharedPref("resourceId", row.patientId, context)
        formatter.saveSharedPref("patientIdParent", row.patientId, context)
        formatter.saveSharedPref("encounterId", row.encounterId, context)
        formatter.saveSharedPref("currentCase", CaseTypes.VHF, context)
        formatter.saveSharedPref("latestEncounter", CaseSlugs.VHF, context)
        formatter.deleteSharedPref("isCase", context)
        returnToCase.launch(Intent(context, SummarizedActivity::class.java))
    }

    private fun registerContact(source: SourceCase) {
        val context = requireContext()
        savePrefs()
        val formatter = FormatterClass()
        formatter.saveSharedPref("currentCase", CaseTypes.VHF, context)
        formatter.saveSharedPref("questionnaire", FormFields.Vhf.CASE_FORM, context)
        val title = if (source.name.isBlank()) "Register contact" else "Register contact of ${source.name}"
        formatter.saveSharedPref("AddParentTitle", title, context)
        formatter.saveSharedPref(VhfContactTracker.PREF_SOURCE_PATIENT, source.patientId, context)
        FormPrefill.stage(
            context,
            FormFields.Vhf.CASE_FORM,
            buildMap {
                put(FormFields.Vhf.CASE_TYPE, FormFields.Vhf.CASE_TYPE_CONTACT)
                put(FormFields.Vhf.EXPOSURE_TYPE, FormFields.Vhf.EXPOSURE_HUMAN_CONTACT)
                if (source.disease.isNotBlank()) put(FormFields.Vhf.DISEASE, source.disease)
                if (source.diseaseOther.isNotBlank()) put(FormFields.Vhf.DISEASE_OTHER, source.diseaseOther)
            }
        )
        returnToCase.launch(
            Intent(context, AddParentCaseActivity::class.java)
                .putExtra("AddParentTitle", title)
                .putExtra(QUESTIONNAIRE_FILE_PATH_KEY, FormFields.Vhf.CASE_FORM)
        )
    }

    private fun savePrefs() {
        val formatter = FormatterClass()
        savedPrefs = RECORD_PREFS.associateWith { formatter.getSharedPref(it, requireContext()) }
    }

    private fun restorePrefs() {
        val context = context ?: return
        val formatter = FormatterClass()
        savedPrefs.forEach { (key, value) ->
            if (value == null) formatter.deleteSharedPref(key, context)
            else formatter.saveSharedPref(key, value, context)
        }
        savedPrefs = emptyMap()
        formatter.deleteSharedPref(VhfContactTracker.PREF_SOURCE_PATIENT, context)
    }

    companion object {
        private const val STATE_SAVED_PREFS = "vhfCaseRecordPrefs"

        /** Prefs that identify the open record; the case summary and its tabs read these. */
        private val RECORD_PREFS = listOf(
            "patientId", "resourceId", "patientIdParent", "encounterId", "currentCase",
            "latestEncounter", "isCase", "questionnaire", "title", "AddParentTitle",
        )
    }
}
