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
            else -> ""
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

            viewModel.updatePatient(
                this@EditChecklistActivity,
                questionnaireResponse,
                questionnaire,
                questionnaireResponseString
            )
        }
    }

    private fun addQuestionnaireFragment(pair: Pair<String, String>) {

        lifecycleScope.launch {
            supportFragmentManager.commit {
                add(
                    R.id.add_patient_container,
                    QuestionnaireFragment.builder().apply {
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