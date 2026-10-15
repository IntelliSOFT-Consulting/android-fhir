package com.icl.surveillance.ui.patients.custom

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.get
import com.google.android.fhir.search.search
import com.icl.surveillance.databinding.ActivityContactTreeBinding
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.fhir.forms.CaseSlugs
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.fhir.forms.VhfContactTracker
import com.icl.surveillance.fhir.forms.valueOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.IdType
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import timber.log.Timber

/**
 * The contact tree of a VHF case: from the earliest source case on this device down through
 * every contact (Patient.link to its source case) and their contacts. Opened from the case's
 * Contacts tab with [EXTRA_PATIENT_ID]; that case is highlighted.
 */
class ContactTreeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContactTreeBinding
    private val fhirEngine: FhirEngine by lazy { FhirApplication.fhirEngine(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityContactTreeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "Contact tree"
            setDisplayHomeAsUpEnabled(true)
        }
        binding.tree.onNodeTap = { node ->
            binding.selection.text = when {
                node == null -> "Tap a dot to see who it is"
                else -> buildString {
                    append(node.name)
                    append("\nEPID: ").append(node.epid.ifBlank { "pending" })
                    append(if (node.isContact) "  ·  Contact" else "  ·  Case")
                    if (node.isCurrent) append("  ·  This case")
                }
            }
        }
        load(intent.getStringExtra(EXTRA_PATIENT_ID).orEmpty())
    }

    private fun load(patientId: String) {
        if (patientId.isBlank()) {
            showMessage("No case selected.")
            return
        }
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { buildTree(rootOf(patientId), patientId, mutableSetOf(), 0) }
            }
            binding.progress.visibility = View.GONE
            result.onSuccess { root ->
                if (root == null) showMessage("This case is not on this device.")
                else binding.tree.setTree(root)
            }.onFailure {
                Timber.tag(TAG).e(it, "Could not build the contact tree for %s", patientId)
                showMessage("Unable to load the contact tree. Please try again.")
            }
        }
    }

    private fun showMessage(text: String) {
        binding.selection.text = text
    }

    /** Walks up Patient.link (contact -> source case) to the earliest case on this device. */
    private suspend fun rootOf(patientId: String): String {
        var current = patientId
        val seen = mutableSetOf(current)
        while (true) {
            val patient = patient(current) ?: return current
            val parent = sourceOf(patient) ?: return current
            if (!seen.add(parent) || patient(parent) == null) return current
            current = parent
        }
    }

    private suspend fun buildTree(
        patientId: String,
        currentId: String,
        visited: MutableSet<String>,
        depth: Int,
    ): ContactTreeView.Node? {
        if (!visited.add(patientId) || visited.size > MAX_NODES) return null
        val patient = patient(patientId) ?: return null
        val children = if (depth >= MAX_DEPTH) emptyList() else
            VhfContactTracker.linkedContactIds(fhirEngine, patientId)
                .filter { it !in visited }
                .mapNotNull { buildTree(it, currentId, visited, depth + 1) }
        return ContactTreeView.Node(
            patientId = patientId,
            name = patient.nameFirstRep.nameAsSingleString?.trim().orEmpty().ifBlank { "Unnamed" },
            epid = patient.identifier.firstOrNull { it.type.codingFirstRep.code == "EPID" }?.value.orEmpty(),
            isContact = caseTypeOf(patient).equals(FormFields.Vhf.CASE_TYPE_CONTACT, ignoreCase = true),
            isCurrent = patientId == currentId,
            children = children,
        )
    }

    private suspend fun patient(id: String): Patient? =
        runCatching { fhirEngine.get<Patient>(id) }.getOrNull()

    /** The source case a contact was registered from (Patient.link, type see-also). */
    private fun sourceOf(patient: Patient): String? =
        patient.link
            .filter { it.type == Patient.LinkType.SEEALSO }
            .firstNotNullOfOrNull { link ->
                link.other?.reference?.takeIf { it.startsWith("Patient/") }?.let { IdType(it).idPart }
            }

    private suspend fun caseTypeOf(patient: Patient): String {
        val encounterId = patient.identifier.firstOrNull { it.system == CaseSlugs.VHF }?.value
            ?: return ""
        return fhirEngine.search<Observation> {
            filter(Observation.ENCOUNTER, { value = "Encounter/$encounterId" })
            filter(Observation.CODE, { value = of(Coding().apply { code = FormFields.Vhf.CASE_TYPE }) })
        }.valueOf(FormFields.Vhf.CASE_TYPE)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_PATIENT_ID = "contactTreePatientId"
        private const val TAG = "CONTACT_TREE"
        private const val MAX_DEPTH = 12
        private const val MAX_NODES = 500
    }
}
