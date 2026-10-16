package com.icl.surveillance.clients

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.icl.surveillance.fhir.forms.FormDrafts

/**
 * Before ID verification for a new VHF case: if this form has an unfinished draft, asks whether
 * to continue it (straight to the form, the draft's verification already done) or start a new
 * case. Start new keeps the draft until the new form saves one of its own (which replaces it);
 * open the form with AddParentCaseActivity.EXTRA_START_NEW so it is not offered again. Without a
 * draft, [onStartNew] runs directly.
 */
object UnfinishedDraftPrompt {
    fun offer(
        context: Context,
        questionnaireFile: String,
        onContinue: () -> Unit,
        onStartNew: () -> Unit,
    ) {
        val draft = FormDrafts.pending(context, questionnaireFile) ?: return onStartNew()
        AlertDialog.Builder(context)
            .setTitle("Unfinished case")
            .setMessage(FormDrafts.unfinishedVhfMessage(draft))
            .setCancelable(false)
            .setPositiveButton("Continue") { _, _ -> onContinue() }
            .setNegativeButton("Start new") { _, _ -> onStartNew() }
            .show()
    }
}
