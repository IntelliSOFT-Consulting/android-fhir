package com.icl.surveillance.debug

import android.view.WindowManager
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.fhir.forms.VhfScreened
import kotlinx.coroutines.launch

/** Dialogs behind Settings > Process flagged VHF cases (administrators). */
object VhfFlaggedWorkflowDialogs {

    fun open(fragment: Fragment) {
        if (VhfFlaggedWorkflow.isRunning) showProgress(fragment) else showConfig(fragment)
    }

    private fun showConfig(fragment: Fragment) {
        val context = fragment.requireContext()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val flagged = runCatching { VhfScreened.count(FhirApplication.fhirEngine(context)) }.getOrDefault(0)
            val waiting = VhfFlaggedWorkflow.pendingCount(context)
            val pad = (20 * context.resources.displayMetrics.density).toInt()
            val steps = TextView(context).apply {
                text = "Phase 1 · Investigate flagged ($flagged waiting)\n" +
                    "For each flagged person:\n" +
                    "1. CIF from their screening answers, as a Suspected case\n" +
                    "2. A blood specimen sent to the lab\n" +
                    "3. ${VhfFlaggedWorkflow.CONTACTS_PER_CASE} contacts registered, days 1–3 of follow up\n" +
                    "4. Contact 1 symptomatic on day 3, converted to Suspected with its own specimen\n\n" +
                    "Then post the lab results.\n\n" +
                    "Phase 2 · Advance lab-confirmed ($waiting cases from phase 1)\n" +
                    "For each case with a final positive result:\n" +
                    "5. Type of case Confirmed, outcome Alive\n" +
                    "6. Discharge assessment, 3 survivor visits, 2 negative samples\n" +
                    "7. Clearance\n" +
                    "Pending and negative cases are left as they are and listed."
            }
            val syncEachPerson = CheckBox(context).apply {
                text = "Sync after each person (needed for contact numbers)"
                isChecked = true
            }
            val note = TextView(context).apply {
                text = "Records save the way the screens save them and sync to the server you are logged " +
                    "into, tagged ${VhfFlaggedWorkflow.TAG_SYSTEM}. Answers the screening did not capture are " +
                    "generated. Survivor dates are counted back from today (discharge " +
                    "${VhfFlaggedWorkflow.SurvivorDates.DISCHARGED_DAYS_AGO} days ago) because the forms take " +
                    "no future dates. Do not run this against production."
                textSize = 12f
            }
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
                addView(steps)
                addView(syncEachPerson)
                addView(note)
            }
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle("Process flagged VHF cases")
                .setView(ScrollView(context).apply { addView(content) })
                .setNeutralButton("Cancel", null)
                .setNegativeButton(VhfFlaggedWorkflow.Phase.ADVANCE.title) { _, _ ->
                    confirm(fragment, VhfFlaggedWorkflow.Phase.ADVANCE, waiting, syncEachPerson.isChecked)
                }
                .setPositiveButton(VhfFlaggedWorkflow.Phase.INVESTIGATE.title) { _, _ ->
                    confirm(fragment, VhfFlaggedWorkflow.Phase.INVESTIGATE, flagged, syncEachPerson.isChecked)
                }
                .show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = flagged > 0
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = waiting > 0
        }
    }

    /** Both phases write many records: say how many people first. */
    private fun confirm(fragment: Fragment, phase: VhfFlaggedWorkflow.Phase, count: Int, sync: Boolean) {
        val what = when (phase) {
            VhfFlaggedWorkflow.Phase.INVESTIGATE ->
                "Investigate all $count flagged people? Each becomes a suspected case with " +
                    "${VhfFlaggedWorkflow.CONTACTS_PER_CASE} contacts. This cannot be undone from the app."
            VhfFlaggedWorkflow.Phase.ADVANCE ->
                "Check $count cases for a final lab result and take the positive ones to clearance?"
        }
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(phase.title)
            .setMessage(what)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Run") { _, _ ->
                VhfFlaggedWorkflow.start(fragment.requireContext(), phase, sync)
                showProgress(fragment)
            }
            .show()
    }

    private fun showProgress(fragment: Fragment) {
        val context = fragment.requireContext()
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val body = TextView(context).apply { setPadding(pad, pad / 2, pad, 0) }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(VhfFlaggedWorkflow.state.value.phase?.title ?: "Process flagged VHF cases")
            .setView(ScrollView(context).apply { addView(body) })
            .setPositiveButton("Hide", null)
            .setNegativeButton("Stop") { _, _ -> VhfFlaggedWorkflow.cancel() }
            .show()

        val window = fragment.requireActivity().window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val job = fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                VhfFlaggedWorkflow.state.collect { state ->
                    body.text = render(state)
                    if (state.finished) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        val failed = state.results.count { !it.passed }
                        dialog.setTitle(if (failed == 0) "Finished" else "Finished · $failed with problems")
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.text = "Close"
                    }
                }
            }
        }
        dialog.setOnDismissListener {
            job.cancel()
            if (!VhfFlaggedWorkflow.isRunning) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun render(state: VhfFlaggedWorkflow.State): String = buildString {
        state.runId?.let { append("Run: $it\n") }
        append("${state.results.size} of ${state.total} done")
        val failed = state.results.count { !it.passed }
        if (failed > 0) append(" · $failed with problems")
        append("\n")
        state.current?.let { append("Now: $it\n") }
        append("\n")
        state.results.asReversed().forEach { result ->
            append(if (result.passed) "✓ " else "✗ ").append(result.name).append("\n")
            append("   ").append(result.outcome).append("\n")
            result.problems.forEach { append("   $it\n") }
        }
        if (state.running) append("\nYou can hide this dialog; the run continues in the background.")
        if (state.finished) append("\nEvery step is in logcat (tag ${VhfFlaggedWorkflow.TAG}).")
    }
}
