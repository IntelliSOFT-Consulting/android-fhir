package com.icl.surveillance.ui.patients.responses

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.commit
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum
import com.google.android.fhir.datacapture.QuestionnaireFragment
import com.google.android.material.button.MaterialButton
import com.icl.surveillance.R
import com.icl.surveillance.databinding.ActivityEditChecklistBinding
import com.icl.surveillance.utils.ContribQuestionnaireItemViewHolderFactoryMatchersProviderFactory
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.utils.ProgressDialogManager
import com.icl.surveillance.viewmodels.EditSupervisorChecklistViewModel
import com.icl.surveillance.viewmodels.factories.EditSupervisorChecklistViewModelFactory
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.fhir.forms.ContactFollowUpState
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.fhir.forms.VhfContactTracker
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.QuestionnaireResponse

class EditChecklistActivity : AppCompatActivity() {
    private lateinit var viewModel: EditSupervisorChecklistViewModel

    private lateinit var binding: ActivityEditChecklistBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityEditChecklistBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val titleText = when (FormatterClass().getSharedPref("questionnaire", this)) {
            "mpox-register.json" -> "Edit Mpox Register"
            "mpox-tally-sheet.json" -> "Edit Summary Sheet"
            "mpox-supervisor-checklist.json" -> "Edit Supervisor Checklist"
            "add-case.json" -> "Edit Measles Case"
            "afp-case.json" -> "Edit AFP Case"
            "vl-case.json" -> "Edit VL Case"
            "vhf-case.json" -> "Edit VHF Case"
            "vhf-lab-results.json" -> "Edit Laboratory Results"
            "vhf-contact-monitoring.json" -> "Edit Contact Monitoring"
            "rumor-tracking-case.json" -> "Edit Rumor Report"
            "social-community.json", "social-county.json" -> "Edit Social Investigation"
            else -> "Edit Record"
        }
        supportActionBar.apply { title = titleText }
        val questionnaireId =
            FormatterClass().getSharedPref("resourceId", this)
        println("Selected Questionnaire ID: $questionnaireId")

        val questionnaire =
            FormatterClass().getSharedPref("questionnaire", this@EditChecklistActivity)
        val factory = EditSupervisorChecklistViewModelFactory(
            application = application, questionnaireId =
                "$questionnaireId", questionnaire =
                "$questionnaire"
        )
        viewModel = ViewModelProvider(this, factory)[EditSupervisorChecklistViewModel::class.java]

        updateArguments()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        viewModel.liveEditData.observe(this) { addQuestionnaireFragment(it) }
        observePatientSaveAction()

        supportFragmentManager.setFragmentResultListener(
            QuestionnaireFragment.SUBMIT_REQUEST_KEY,
            this@EditChecklistActivity,
        ) { _, _ ->
            onSubmitAction()
        }
        supportFragmentManager.setFragmentResultListener(
            QuestionnaireFragment.CANCEL_REQUEST_KEY,
            this@EditChecklistActivity,
        ) { _, _ ->
            onBackPressed()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onBackPressed() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("Exit")
            .setMessage("Are you sure you want to exit?")
            .setPositiveButton("Yes") { _, _ ->
                super.onBackPressed() // Exit the activity
            }
            .setNegativeButton("No") { dialog, _ ->
                dialog.dismiss() // Dismiss the dialog
            }
            .create()

        dialog.show()
    }

    private fun onSubmitAction() {
        ProgressDialogManager.show(this, "Please wait.....")
        lifecycleScope.launch {
            val questionnaireFragment =
                supportFragmentManager.findFragmentByTag(QUESTIONNAIRE_FRAGMENT_TAG)
                        as QuestionnaireFragment
            val jsonParser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()

            val questionnaire =
                FormatterClass().getSharedPref("questionnaire", this@EditChecklistActivity)
            val questionnaireResponse = questionnaireFragment.getQuestionnaireResponse()
            val questionnaireResponseString =
                jsonParser.encodeResourceToString(questionnaireResponse)
            if (!passesMonitoringRule(questionnaire, questionnaireResponse)) {
                ProgressDialogManager.dismiss()
                return@launch
            }

            viewModel.updatePatient(
                this@EditChecklistActivity,
                questionnaireResponse,
                questionnaire,
                questionnaireResponseString
            )
        }
    }

    /**
     * The release rule of a new monitoring entry applies to an edited one too: a contact can only
     * be released once the day 21 follow up is recorded or has passed.
     */
    private suspend fun passesMonitoringRule(
        questionnaire: String?,
        response: QuestionnaireResponse,
    ): Boolean {
        if (questionnaire != FormFields.Vhf.CONTACT_MONITORING_FORM) return true
        val status = response.item.flatMap { listOf(it) + it.item }
            .firstOrNull { it.linkId == FormFields.Vhf.MONITORING_STATUS }
            ?.answer?.firstOrNull()?.value as? Coding
        if (status?.code != FormFields.Vhf.STATUS_COMPLETED_CODE) return true
        val formatter = FormatterClass()
        val patientId = formatter.getSharedPref("patientIdParent", this) ?: return true
        val encounterId = formatter.getSharedPref("encounterId", this) ?: return true
        val state = withContext(Dispatchers.IO) {
            VhfContactTracker.load(FhirApplication.fhirEngine(this@EditChecklistActivity), patientId, encounterId)
        } ?: return true
        if (state.windowComplete) return true
        Toast.makeText(
            this,
            "A contact can only be released once the day 21 follow up " +
                "(${ContactFollowUpState.DISPLAY.format(state.lastDay)}) is recorded or has passed.",
            Toast.LENGTH_LONG
        ).show()
        return false
    }

    private fun addQuestionnaireFragment(pair: Pair<String, String>) {

        lifecycleScope.launch {
            supportFragmentManager.commit {
                add(
                    R.id.add_patient_container,
                    QuestionnaireFragment.builder().apply {
                        // Edits must pass validation: no "Submit anyway" in the errors dialog.
                        setShowSubmitAnywayButton(false)
                        setCustomQuestionnaireItemViewHolderFactoryMatchersProvider(
                            ContribQuestionnaireItemViewHolderFactoryMatchersProviderFactory
                                .LOCATION_WIDGET_PROVIDER,
                        )
                    }
                        .setQuestionnaire(pair.first)
                        .setQuestionnaireResponse(pair.second)
                        .build(),
                    QUESTIONNAIRE_FRAGMENT_TAG,
                )
            }
        }
    }

    private fun observePatientSaveAction() {
        viewModel.isResourcesSaved.observe(this@EditChecklistActivity) {
            ProgressDialogManager.dismiss()
            if (!it) {
                Toast.makeText(
                    this@EditChecklistActivity,
                    "Please Enter all Required Fields.",
                    Toast.LENGTH_SHORT
                )
                    .show()
                return@observe
            }

            showSuccessDialog(this@EditChecklistActivity)
        }
    }

    fun showSuccessDialog(context: Context) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.success_dialog, null)
        val alertDialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialogView.findViewById<MaterialButton>(R.id.btn_cancel).setOnClickListener {
            alertDialog.dismiss()
            this@EditChecklistActivity.finish()
        }

        dialogView.findViewById<MaterialButton>(R.id.btn_finish).setOnClickListener {
            // handle finish action
            this@EditChecklistActivity.finish()
            alertDialog.dismiss()
        }

        alertDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        alertDialog.show()
    }

    private fun updateArguments() {
        val json = FormatterClass().getSharedPref("questionnaire", this)
        intent.putExtra(QUESTIONNAIRE_FILE_PATH_KEY, json)
    }

    companion object {
        const val QUESTIONNAIRE_FILE_PATH_KEY = "edit-questionnaire-file-path-key"
        const val QUESTIONNAIRE_FRAGMENT_TAG = "edit-questionnaire-fragment-tag"
    }
}