package com.icl.surveillance.debug

import android.text.InputType
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
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

/** DEBUG ONLY: dialogs behind the Profile screen's "Generate Test Data" button. */
object SyntheticDataDialogs {

    fun open(fragment: Fragment) {
        if (SyntheticDataGenerator.isRunning) showProgress(fragment) else showConfig(fragment)
    }

    private fun showConfig(fragment: Fragment) {
        val context = fragment.requireContext()
        val modules = SyntheticDataGenerator.modules
        val density = context.resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val moduleBoxes = modules.map { module -> CheckBox(context).apply { text = module.label } }
        val selectAll = CheckBox(context).apply { text = "Select all" }
        val countInput = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("500")
            hint = "Records per module"
        }
        val varyAnswers = CheckBox(context).apply {
            text = "Vary multiple-choice answers and counts (follow-up questions are filled in)"
            isChecked = true
        }
        val useLatestSubmission = CheckBox(context).apply {
            text = "Copy the latest real submission as a template when available"
            isChecked = false
        }
        val spreadLocations = CheckBox(context).apply {
            text = "Spread records across all counties"
            isChecked = true
        }
        val uploadInBatches = CheckBox(context).apply {
            text = "Upload every ${SyntheticDataGenerator.DEFAULT_UPLOAD_EVERY} records " +
                    "(recommended — prevents out-of-memory on sync)"
            isChecked = true
        }
        val note = TextView(context).apply {
            text = "Records are built from each module's form (no manual entry needed) and " +
                    "saved through the normal submission workflow. Records carry no " +
                    "test marker and WILL sync to the server you are logged into — do not run " +
                    "this against production. Generated IDs are logged on this device only."
            textSize = 12f
            setPadding(0, pad / 2, 0, 0)
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(context).apply { text = "Modules" })
            addView(selectAll)
            moduleBoxes.forEach { addView(it) }
            addView(TextView(context).apply {
                text = "Records per module"
                setPadding(0, pad / 2, 0, 0)
            })
            addView(countInput)
            addView(varyAnswers)
            addView(useLatestSubmission)
            addView(spreadLocations)
            addView(uploadInBatches)
            addView(note)
        }
        // Only this view scrolls; the dialog's Cancel / Generate buttons stay pinned below it.
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(content)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Generate test data")
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Generate", null) // click handled below so we can validate
            .create()

        dialog.setOnShowListener {
            val generate = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            fun refresh() {
                generate.isEnabled = moduleBoxes.any { it.isChecked }
            }
            var syncing = false
            selectAll.setOnCheckedChangeListener { _, checked ->
                if (syncing) return@setOnCheckedChangeListener
                syncing = true
                moduleBoxes.forEach { it.isChecked = checked }
                syncing = false
                refresh()
            }
            moduleBoxes.forEach { box ->
                box.setOnCheckedChangeListener { _, _ ->
                    if (syncing) return@setOnCheckedChangeListener
                    syncing = true
                    selectAll.isChecked = moduleBoxes.all { it.isChecked }
                    syncing = false
                    refresh()
                }
            }
            refresh()

            generate.setOnClickListener {
                val count = countInput.text.toString().toIntOrNull()
                if (count == null || count !in 1..5000) {
                    countInput.error = "Enter a number between 1 and 5000"
                    return@setOnClickListener
                }
                val selected = modules.filterIndexed { i, _ -> moduleBoxes[i].isChecked }
                SyntheticDataGenerator.start(
                    context,
                    selected,
                    SyntheticDataGenerator.Options(
                        count = count,
                        varyAnswers = varyAnswers.isChecked,
                        spreadLocations = spreadLocations.isChecked,
                        useLatestSubmission = useLatestSubmission.isChecked,
                        uploadEvery = if (uploadInBatches.isChecked) {
                            SyntheticDataGenerator.DEFAULT_UPLOAD_EVERY
                        } else 0,
                    )
                )
                dialog.dismiss()
                showProgress(fragment)
            }
        }
        dialog.show()
    }

    private fun showProgress(fragment: Fragment) {
        val context = fragment.requireContext()
        val pad = (20 * context.resources.displayMetrics.density).toInt()
        val body = TextView(context).apply { setPadding(pad, pad / 2, pad, 0) }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Generating test data")
            .setView(body)
            .setPositiveButton("Hide", null)
            .setNegativeButton("Stop") { _, _ -> SyntheticDataGenerator.cancel() }
            .show()

        val window = fragment.requireActivity().window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val job = fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                SyntheticDataGenerator.state.collect { state ->
                    body.text = render(state)
                    if (state.finished) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        dialog.setTitle(if (state.cancelled) "Stopped" else "Done")
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.text = "Close"
                    }
                }
            }
        }
        dialog.setOnDismissListener {
            job.cancel()
            if (!SyntheticDataGenerator.isRunning) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun render(state: SyntheticDataGenerator.State): String = buildString {
        if (state.running) {
            append("Working on: ${state.currentModule ?: "-"}\n")
            state.phase?.let { append("$it\n") }
        }
        append("Progress: ${state.done} / ${state.total}\n\n")
        state.reports.forEach { r ->
            append("• ${r.label}: ")
            if (!r.seedFound) {
                append("skipped")
            } else {
                append("${r.saved} saved")
                if (r.invalid > 0) append(", ${r.invalid} invalid")
                if (r.failed > 0) append(", ${r.failed} failed")
                if (r.counties > 0) append(" across ${r.counties} counties")
            }
            r.note?.let { append("\n   $it") }
            append('\n')
        }
        if (state.running) append("\nYou can hide this dialog; generation continues in the background.")
    }
}
