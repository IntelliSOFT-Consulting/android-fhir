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
import kotlinx.coroutines.launch

/** Dialogs behind Settings > Simulate VHF workflow (administrators). */
object VhfWorkflowDialogs {

    fun open(fragment: Fragment) {
        if (VhfWorkflowSimulator.isRunning) showProgress(fragment) else showConfig(fragment)
    }

    private fun showConfig(fragment: Fragment) {
        val context = fragment.requireContext()
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val steps = TextView(context).apply {
            text = "One person through the VHF workflow, saved the way the screens save it:\n\n" +
                "1. A suspected source case\n" +
                "2. A flagged (screened) person\n" +
                "3. The flagged person investigated as a contact of the source case\n" +
                "4. Three daily follow ups (symptomatic on day 3)\n" +
                "5. Converted to a suspected case (monitoring outcome, then the CIF)\n" +
                "6. The lab confirms the specimen (positive)\n" +
                "7. The CIF updated from suspected to confirmed\n" +
                "8. The outcome recorded: died\n" +
                "9. A final sync and a check of every record"
        }
        val syncEachStep = CheckBox(context).apply {
            text = "Sync after each step and check the records again (recommended)"
            isChecked = true
        }
        val note = TextView(context).apply {
            text = "The records sync to the server you are logged into, tagged with the run's id " +
                "(${VhfWorkflowSimulator.TAG_SYSTEM}). Do not run this against production. " +
                "The lab result is created on this device, as NPHL would send it."
            textSize = 12f
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(steps)
            addView(syncEachStep)
            addView(note)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("Simulate VHF workflow")
            .setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Run") { _, _ ->
                VhfWorkflowSimulator.start(context, syncEachStep.isChecked)
                showProgress(fragment)
            }
            .show()
    }

    private fun showProgress(fragment: Fragment) {
        val context = fragment.requireContext()
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val body = TextView(context).apply { setPadding(pad, pad / 2, pad, 0) }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Simulating VHF workflow")
            .setView(ScrollView(context).apply { addView(body) })
            .setPositiveButton("Hide", null)
            .setNegativeButton("Stop") { _, _ -> VhfWorkflowSimulator.cancel() }
            .show()

        val window = fragment.requireActivity().window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val job = fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                VhfWorkflowSimulator.state.collect { state ->
                    body.text = render(state)
                    if (state.finished) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        val passed = state.steps.size == STEP_COUNT && state.steps.all { it.passed }
                        dialog.setTitle(if (passed) "All steps passed" else "Finished with problems")
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.text = "Close"
                    }
                }
            }
        }
        dialog.setOnDismissListener {
            job.cancel()
            if (!VhfWorkflowSimulator.isRunning) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private const val STEP_COUNT = 9

    private fun render(state: VhfWorkflowSimulator.State): String = buildString {
        state.runId?.let { append("Run: $it\n\n") }
        state.steps.sortedBy { it.number }.forEach { step ->
            val done = step.checks.isNotEmpty() || step.error != null
            val mark = when {
                !done -> "…"
                step.passed -> "✓"
                else -> "✗"
            }
            append("$mark ${step.number}. ${step.title}\n")
            step.error?.let { append("   Error: $it\n") }
            step.checks.filterNot { it.passed }.forEach { append("   ✗ ${it.name} ${it.detail}\n") }
            step.synced?.let { append(if (it) "   Synced\n" else "   ✗ Sync failed (offline or server error)\n") }
            step.afterSync.filterNot { it.passed }.forEach { append("   ✗ after sync: ${it.name} ${it.detail}\n") }
        }
        if (state.running) append("\nYou can hide this dialog; the run continues in the background.")
        if (state.finished) append("\nEvery check is in logcat (tag ${VhfWorkflowSimulator.TAG}).")
    }
}
