package com.icl.surveillance.clients

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import ca.uhn.fhir.context.FhirContext
import com.google.android.fhir.datacapture.QuestionnaireFragment
import com.icl.surveillance.R
import com.icl.surveillance.utils.ProgressDialogManager
import com.icl.surveillance.viewmodels.AddClientViewModel
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.QuestionnaireResponse

class AddClientFragment : Fragment(R.layout.fragment_add_client) {

    private val viewModel: AddClientViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpActionBar()
        setHasOptionsMenu(true)
        updateArguments()
        if (savedInstanceState == null) {
            addQuestionnaireFragment()
        }
        observePatientSaveAction()

        /** Use the provided cancel|submit buttons from the sdc library */
        childFragmentManager.setFragmentResultListener(
            QuestionnaireFragment.SUBMIT_REQUEST_KEY,
            viewLifecycleOwner,
        ) { _, _ ->
            onSubmitAction()
        }
        childFragmentManager.setFragmentResultListener(
            QuestionnaireFragment.CANCEL_REQUEST_KEY,
            viewLifecycleOwner,
        ) { _, _ ->
            NavHostFragment.findNavController(this).navigateUp()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                NavHostFragment.findNavController(this).navigateUp()
                true
            }

            else -> false
        }
    }

    private fun setUpActionBar() {
        (requireActivity() as AppCompatActivity).supportActionBar?.apply {
            title = requireContext().getString(R.string.add_client)
            setDisplayHomeAsUpEnabled(true)
        }
    }

    private fun updateArguments() {
        requireArguments().putString(QUESTIONNAIRE_FILE_PATH_KEY, "add-case.json")
    }

    private fun addQuestionnaireFragment() {
        childFragmentManager.commit {
            add(
                R.id.add_patient_container,
                QuestionnaireFragment.builder()
                    .setQuestionnaire(viewModel.questionnaireJson)
                    .setShowCancelButton(true)
                    .setSubmitButtonText("Submit")
                    .build(),
                QUESTIONNAIRE_FRAGMENT_TAG,
            )
        }
    }

    private fun onSubmitAction() {

        ProgressDialogManager.show(requireContext(), "Please wait...")
        lifecycleScope.launch {
            val questionnaireFragment =
                childFragmentManager.findFragmentByTag(QUESTIONNAIRE_FRAGMENT_TAG)
                        as QuestionnaireFragment
            savePatient(questionnaireFragment.getQuestionnaireResponse())
        }
    }

    private fun savePatient(questionnaireResponse: QuestionnaireResponse) {
        val context = FhirContext.forR4()
        val questionnaireResponseString =
            context.newJsonParser().encodeResourceToString(questionnaireResponse)

        viewModel.savePatientData(
            questionnaireResponse,
            requireContext()
        )
    }

    private fun observePatientSaveAction() {
        viewModel.isPatientSaved.observe(viewLifecycleOwner) {
            ProgressDialogManager.dismiss()

            if (!it) {
                Toast.makeText(
                    requireContext(),
                    "Please Enter all Required Fields.",
                    Toast.LENGTH_SHORT
                )
                    .show()
                return@observe
            }
            Toast.makeText(requireContext(), "Case is saved.", Toast.LENGTH_SHORT).show()
            NavHostFragment.findNavController(this).navigateUp()
        }
    }

    companion object {
        const val QUESTIONNAIRE_FILE_PATH_KEY = "questionnaire-file-path-key"
        const val QUESTIONNAIRE_FRAGMENT_TAG = "questionnaire-fragment-tag"
    }
}
