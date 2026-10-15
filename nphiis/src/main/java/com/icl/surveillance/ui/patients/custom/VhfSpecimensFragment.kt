package com.icl.surveillance.ui.patients.custom

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.icl.surveillance.R
import com.icl.surveillance.databinding.FragmentVlLabBinding
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.fhir.forms.VhfSpecimens
import com.icl.surveillance.utils.FormatterClass
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Laboratory" tab of a VHF case: the specimens collected for the case, each with its own
 * result (from the Specimen, see [VhfSpecimens]), and the case's final classification derived
 * from them. There is no lab results form.
 */
class VhfSpecimensFragment : Fragment() {

    private var _binding: FragmentVlLabBinding? = null
    private val binding get() = _binding!!
    private val fhirEngine by lazy { FhirApplication.fhirEngine(requireContext()) }
    /** e.g. "Thu, 15 Oct 2026". */
    private val dateFormat = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
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
        val patientId = formatter.getSharedPref("patientIdParent", requireContext())
            ?: formatter.getSharedPref("patientId", requireContext())
            ?: return
        val encounterId = formatter.getSharedPref("encounterId", requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { VhfSpecimens.forCase(fhirEngine, patientId, encounterId) }
            }
            if (_binding == null) return@launch
            loaded.onSuccess { render(it) }.onFailure {
                timber.log.Timber.tag("SPECIMEN").e(it, "Could not load specimens for Patient/%s", patientId)
                binding.lnParent.removeAllViews()
                binding.tvEmptyMessage.text = "Unable to load specimens. Please try again."
                binding.lnEmpty.visibility = View.VISIBLE
            }
        }
    }

    private fun render(rows: List<VhfSpecimens.Row>) {
        binding.lnEmpty.visibility = View.GONE
        val parent = binding.lnParent
        parent.removeAllViews()

        val summary = VhfSpecimens.summarize(rows)
        if (summary == null) {
            parent.addView(
                VhfCards.action(
                    parent, CardTone.NEUTRAL, R.drawable.ic_vhf_info,
                    "No specimens collected",
                    "Specimens recorded in the case form's laboratory section appear here with their results.",
                )
            )
            return
        }

        parent.addView(
            VhfCards.stats(
                parent,
                listOf(
                    Triple("Positive", rows.count { it.result == VhfSpecimens.Result.POSITIVE }.toString(), CardTone.ALERT),
                    Triple("Negative", rows.count { it.result == VhfSpecimens.Result.NEGATIVE }.toString(), CardTone.SUCCESS),
                    Triple(
                        "Pending",
                        rows.count { it.result == VhfSpecimens.Result.PENDING || it.result == VhfSpecimens.Result.REJECTED }.toString(),
                        CardTone.INFO,
                    ),
                )
            )
        )

        val (tone, icon, message) = when (summary.result) {
            VhfSpecimens.Result.POSITIVE -> Triple(
                CardTone.ALERT, R.drawable.ic_vhf_warning,
                "At least one specimen tested positive."
            )
            VhfSpecimens.Result.NEGATIVE -> Triple(
                CardTone.SUCCESS, R.drawable.ic_vhf_check_circle,
                if (rows.size == 1) "The specimen tested negative." else "All ${rows.size} specimens tested negative."
            )
            else -> Triple(
                CardTone.INFO, R.drawable.ic_vhf_info,
                "Waiting for results on ${rows.count { it.result != VhfSpecimens.Result.NEGATIVE }} of ${rows.size} specimens."
            )
        }
        parent.addView(
            VhfCards.action(
                parent, tone, icon,
                "${FormFields.Vhf.FINAL_CLASSIFICATION_LABEL}: ${summary.classification}",
                message,
            )
        )

        parent.addView(VhfCards.sectionHeader(parent, "Specimens (${rows.size})"))
        rows.forEach { row ->
            val rowTone = when (row.result) {
                VhfSpecimens.Result.POSITIVE -> CardTone.ALERT
                VhfSpecimens.Result.NEGATIVE -> CardTone.SUCCESS
                VhfSpecimens.Result.REJECTED -> CardTone.WARNING
                VhfSpecimens.Result.PENDING -> CardTone.INFO
            }
            parent.addView(
                VhfCards.specimen(
                    parent,
                    type = row.type,
                    lines = buildList {
                        add("Specimen ID: ${row.specimenId ?: "Not assigned"}")
                        add("Collected: ${row.collected?.let { dateFormat.format(it) } ?: "Not recorded"}")
                        add("Sent to lab: ${row.sent?.let { dateFormat.format(it) } ?: "Not recorded"}")
                        add(
                            "Results: " + when {
                                row.resultDate != null -> dateFormat.format(row.resultDate)
                                row.tests.isEmpty() -> "Awaiting results"
                                else -> "Date not recorded"
                            }
                        )
                        // Each test (Observation in a DiagnosticReport for this specimen).
                        row.tests.forEach { test ->
                            add("${test.name}: ${test.value}" + if (test.isFinal) "" else " (preliminary)")
                        }
                        addAll(row.notes)
                    },
                    status = row.statusLabel,
                    tone = rowTone,
                )
            )
        }
    }
}
