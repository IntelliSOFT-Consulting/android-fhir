package com.icl.surveillance.ui.patients

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.QuestionnaireFragment
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.search.search
import com.google.android.material.button.MaterialButton
import com.icl.surveillance.R
import com.icl.surveillance.clients.AddClientFragment.Companion.QUESTIONNAIRE_FILE_PATH_KEY
import com.icl.surveillance.clients.AddClientFragment.Companion.QUESTIONNAIRE_FRAGMENT_TAG
import com.icl.surveillance.databinding.ActivityAddCaseBinding
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.utils.ContribQuestionnaireItemViewHolderFactoryMatchersProviderFactory
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.utils.LocationUtils
import com.icl.surveillance.utils.ProgressDialogManager
import com.icl.surveillance.viewmodels.ScreenerViewModel
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.Type
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date

class AddCaseActivity : AppCompatActivity() {
    private lateinit var fhirEngine: FhirEngine


    private val viewModel: ScreenerViewModel by viewModels()
    private lateinit var binding:
            ActivityAddCaseBinding // Binding class name is based on layout file name

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityAddCaseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        fhirEngine = FhirApplication.fhirEngine(this@AddCaseActivity)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val titleName = FormatterClass().getSharedPref("title", this@AddCaseActivity)
        supportActionBar.apply { title = titleName }

        LocationUtils.requestCurrentLocation(
            this,
            onLocationReceived = { lat, lon ->
                println("Latitude: $lat, Longitude: $lon")

                val latitude = lat.toString()
                val longitude = lon.toString()
                FormatterClass().saveSharedPref("latitude", latitude, this)
                FormatterClass().saveSharedPref("longitude", longitude, this)
            },
            onError = { error ->
                println("Error: $error")
            }
        )

        updateArguments()
        if (savedInstanceState == null) {
            addQuestionnaireFragment()
        }
        observePatientSaveAction()
        supportFragmentManager.setFragmentResultListener(
            QuestionnaireFragment.SUBMIT_REQUEST_KEY,
            this@AddCaseActivity,
        ) { _, _ ->
            onSubmitAction()
        }
        supportFragmentManager.setFragmentResultListener(
            QuestionnaireFragment.CANCEL_REQUEST_KEY,
            this@AddCaseActivity,
        ) { _, _ ->
            onBackPressed()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        showCancelScreenerQuestionnaireAlertDialog()
        return true
    }

    private fun onSubmitAction() {
//        ProgressDialogManager.show(this, "Please wait.....")
        lifecycleScope.launch {
            val questionnaireFragment =
                supportFragmentManager.findFragmentByTag(QUESTIONNAIRE_FRAGMENT_TAG)
                        as QuestionnaireFragment

            val questionnaireResponse = questionnaireFragment.getQuestionnaireResponse()
            // Print the response to the log
            val jsonParser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()
            val questionnaireResponseString =
                jsonParser.encodeResourceToString(questionnaireResponse)
            Log.e("response", questionnaireResponseString)
            println("Response $questionnaireResponseString")
            saveCase(questionnaireFragment.getQuestionnaireResponse(), questionnaireResponseString)
        }
    }

    private fun saveCase(
        questionnaireResponse: QuestionnaireResponse,
        questionnaireResponseString: String
    ) {

        val patientId = FormatterClass().getSharedPref("patientIdParent", this@AddCaseActivity)
        val questionnaire = FormatterClass().getSharedPref("questionnaire", this@AddCaseActivity)
        val encounter = FormatterClass().getSharedPref("encounterId", this@AddCaseActivity)

        println("Parent Encounter $encounter Patient Id $patientId")
        when (questionnaire) {

            "measles-lab-results.json" -> {
                viewModel.completeLabAssessment(
                    questionnaireResponse,
                    "$patientId",
                    "$encounter",
                    "Measles Lab Information",
                    questionnaireResponseString, this@AddCaseActivity
                )
            }

            "measles-lab-reg-results.json" ->
                viewModel.completeLabAssessment(
                    questionnaireResponse,
                    "$patientId",
                    "$encounter",
                    "Measles Regional Lab Information",
                    questionnaireResponseString, this@AddCaseActivity
                )

            "afp-case-stool-lab-results.json" ->
                viewModel.completeLabAssessment(
                    questionnaireResponse,
                    "$patientId",
                    "$encounter",
                    "AFP Stool Lab Information",
                    questionnaireResponseString, this@AddCaseActivity
                )

            "afp-sixty-days.json" ->
                viewModel.completeLabAssessment(
                    questionnaireResponse,
                    "$patientId",
                    "$encounter",
                    "AFP 60 Day Follow Up",
                    questionnaireResponseString, this@AddCaseActivity
                )

            "afp-itd-lab.json" ->
                viewModel.completeLabAssessment(
                    questionnaireResponse,
                    "$patientId",
                    "$encounter",
                    "AFP ITD Lab Information",
                    questionnaireResponseString, this@AddCaseActivity
                )

            "vl-case-lab-information.json" ->
                viewModel.completeLabAssessment(
                    questionnaireResponse,
                    "$patientId",
                    "$encounter",
                    "VL Laboratory Examination",
                    questionnaireResponseString, this@AddCaseActivity
                )

            "vl-case-sixMonthsFollowup.json" ->
                viewModel.completeLabAssessment(
                    questionnaireResponse,
                    "$patientId",
                    "$encounter",
                    "VL Follow Up Information",
                    questionnaireResponseString, this@AddCaseActivity
                )

            "vl-case-hospitilization.json" ->
                viewModel.completeLabAssessment(
                    questionnaireResponse,
                    "$patientId",
                    "$encounter",
                    "VL Hospitalization Information",
                    questionnaireResponseString, this@AddCaseActivity
                )

            "afp-final-lab-results.json" ->
                viewModel.completeLabAssessment(
                    questionnaireResponse,
                    "$patientId",
                    "$encounter",
                    "AFP Final Lab Information",
                    questionnaireResponseString, this@AddCaseActivity
                )

            "afp-contact-tracing.json" -> {
                viewModel.completeContactAssessment(
                    questionnaireResponse,
                    "$patientId",
                    "$encounter",
                    questionnaireResponseString, this@AddCaseActivity
                )
            }
        }
    }

    fun flattenItems(items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>):
            List<QuestionnaireResponse.QuestionnaireResponseItemComponent> {

        return items.flatMap { item ->
            listOf(item) + flattenItems(item.item)   // include this item + all children
        }
    }

    private fun addQuestionnaireFragment() {
        val resourceId = FormatterClass().getSharedPref(
            "resourceId", this@AddCaseActivity
        ) // aka Questionnaire
        if (resourceId != null) {
            lifecycleScope.launch {
                val searchResult =
                    fhirEngine.search<QuestionnaireResponse> {
                        filter(Resource.RES_ID, { value = of(resourceId) })
                    }
                if (searchResult.isEmpty()) {
                    Toast.makeText(
                        this@AddCaseActivity,
                        "Please try again later",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                searchResult.first().let { response ->

                    val resource = QuestionnaireResponse()
                    val json = FormatterClass().getSharedPref("questionnaire", this@AddCaseActivity)
                    when (json) {
                        "measles-lab-results.json" -> {
                            val allItems = flattenItems(response.resource.item)


                            val sendingDateItem = allItems.find { item ->
                                item.hasLinkId() && item.linkId == "718251724172"
                            }

                            val sendingDateAnswer = sendingDateItem?.answerFirstRep?.value
                            println("Previous Date of sending ${response.resource.logicalId} ->  ${sendingDateAnswer?.primitiveValue()} current questionnaire $json")

                            val dateReceived =
                                QuestionnaireResponse.QuestionnaireResponseItemComponent()

                            dateReceived.linkId = "718251724172"
                            dateReceived.text = "Date specimen sent to lab"
                            val sendingDate = (sendingDateAnswer as? DateType)?.value
                            val cleanedDate =
                                sendingDate?.let { FormatterClass().formatToMMddyyyy(it) }

                            dateReceived.answerFirstRep.value = DateType(cleanedDate)

                            resource.item.add(dateReceived)
                        }

                        else -> {

                        }
                    }

                    if (supportFragmentManager.findFragmentByTag(QUESTIONNAIRE_FRAGMENT_TAG) == null) {
                        supportFragmentManager.commit {

                            setReorderingAllowed(true)
                            val questionnaireFragmentBuilder =
                                QuestionnaireFragment.builder().apply {
                                    setShowSubmitAnywayButton(false)
                                    setQuestionnaireResponse(
                                        FhirContext.forR4Cached().newJsonParser()
                                            .encodeResourceToString(resource)
                                    )
                                    setCustomQuestionnaireItemViewHolderFactoryMatchersProvider(
                                        ContribQuestionnaireItemViewHolderFactoryMatchersProviderFactory
                                            .LOCATION_WIDGET_PROVIDER,
                                    )
                                    setQuestionnaire(viewModel.questionnaire)
                                }
                            add(
                                R.id.add_patient_container,
                                questionnaireFragmentBuilder.build(),
                                QUESTIONNAIRE_FRAGMENT_TAG
                            )
                        }
                    }
                }
            }
        } else {
            Toast.makeText(
                this@AddCaseActivity,
                "Please try again later",
                Toast.LENGTH_SHORT
            ).show()
            this@AddCaseActivity.finish()
        }
    }

    private fun observePatientSaveAction() {
        viewModel.isResourcesSaved.observe(this@AddCaseActivity) {
            ProgressDialogManager.dismiss()
            if (!it) {
                Toast.makeText(
                    this@AddCaseActivity,
                    "Please Enter all Required Fields.",
                    Toast.LENGTH_SHORT
                )
                    .show()
                return@observe
            }

            showSuccessDialog(this@AddCaseActivity)
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
            this@AddCaseActivity.finish()
        }

        dialogView.findViewById<MaterialButton>(R.id.btn_finish).setOnClickListener {
            // handle finish action
            this@AddCaseActivity.finish()
            alertDialog.dismiss()
        }

        alertDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        alertDialog.show()
    }

    private fun updateArguments() {
        val json = FormatterClass().getSharedPref("questionnaire", this@AddCaseActivity)
        intent.putExtra(QUESTIONNAIRE_FILE_PATH_KEY, json)
    }

    private fun showCancelScreenerQuestionnaireAlertDialog() {
        val builder = AlertDialog.Builder(this)
        builder.apply {
            setMessage(getString(R.string.cancel_questionnaire_message))
            setPositiveButton(getString(android.R.string.yes)) { _, _ ->
                this@AddCaseActivity.finish()
            }
            setNegativeButton(getString(android.R.string.no)) { _, _ -> }
        }
        val alertDialog = builder.create()
        alertDialog.show()
    }

    override fun onBackPressed() {
        showCancelScreenerQuestionnaireAlertDialog()
        super.onBackPressed()
    }

}
