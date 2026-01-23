package com.icl.surveillance.cases

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.QuestionnaireFragment
import com.google.android.fhir.search.search
import com.google.android.material.button.MaterialButton
import com.icl.surveillance.R
import com.icl.surveillance.clients.AddClientFragment.Companion.QUESTIONNAIRE_FILE_PATH_KEY
import com.icl.surveillance.clients.AddClientFragment.Companion.QUESTIONNAIRE_FRAGMENT_TAG
import com.icl.surveillance.databinding.ActivityGeneralEditorBinding
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.utils.ContribQuestionnaireItemViewHolderFactoryMatchersProviderFactory
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.utils.ProgressDialogManager
import com.icl.surveillance.viewmodels.AddClientViewModel
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.MeasureReport
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import org.json.JSONObject
import kotlin.getValue

class GeneralEditorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityGeneralEditorBinding
    private val viewModel: AddClientViewModel by viewModels()
    private lateinit var fhirEngine: FhirEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityGeneralEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        fhirEngine = FhirApplication.fhirEngine(this@GeneralEditorActivity)
        val titleName = FormatterClass().getSharedPref("AddParentTitle", this)
        supportActionBar.apply { title = titleName }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        updateArguments()
        if (savedInstanceState == null) {
            addQuestionnaireFragment()
        }
        observePatientSaveAction()

        supportFragmentManager.setFragmentResultListener(
            QuestionnaireFragment.SUBMIT_REQUEST_KEY,
            this@GeneralEditorActivity,
        ) { _, _ ->
            onSubmitAction()
        }
        supportFragmentManager.setFragmentResultListener(
            QuestionnaireFragment.CANCEL_REQUEST_KEY,
            this@GeneralEditorActivity,
        ) { _, _ ->
            onBackPressed()
        }
    }

    private fun onSubmitAction() {
        ProgressDialogManager.show(this, "Please Wait.....")
        lifecycleScope.launch {
            val questionnaireFragment =
                supportFragmentManager.findFragmentByTag(QUESTIONNAIRE_FRAGMENT_TAG)
                        as QuestionnaireFragment
            saveCase(questionnaireFragment.getQuestionnaireResponse())
        }
    }

    private fun saveCase(questionnaireResponse: QuestionnaireResponse) {

        // Let's get the respective MeasureReport
        val patientId =
            FormatterClass().getSharedPref("patientId", this@GeneralEditorActivity)
        if (patientId != null) {
            lifecycleScope.launch {
                val res = fhirEngine.search<MeasureReport> {
                    filter(
                        MeasureReport.SUBJECT,
                        { value = "Patient/$patientId" })
                }.take(5)
                if (res.isNotEmpty()) {
                    val response = res.first().resource

                    viewModel.updatePatientData(
                        questionnaireResponse,
                        this@GeneralEditorActivity,response
                    )
                }
            }
        }
    }

    private fun observePatientSaveAction() {
        viewModel.isPatientSaved.observe(this) {
            ProgressDialogManager.dismiss()

            if (!it) {
                Toast.makeText(this, "Please Enter all Required Fields.", Toast.LENGTH_SHORT).show()
                return@observe
            }
            showSuccessDialog(this@GeneralEditorActivity)

        }
    }

    private fun addQuestionnaireFragment() {
        lifecycleScope.launch {
            val patientId =
                FormatterClass().getSharedPref("patientId", this@GeneralEditorActivity)
            if (patientId != null) {
                val res = fhirEngine.search<QuestionnaireResponse> {
                    filter(
                        QuestionnaireResponse.SUBJECT,
                        { value = "Patient/$patientId" })
                }.take(5)
                if (res.isNotEmpty()) {
                    val questionnaireResponse = res.first().resource
                    val jsonParser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()
                    val questionnaireResponseString =
                        jsonParser.encodeResourceToString(questionnaireResponse)
                    println(" Current ID -> ${questionnaireResponse.idPart}")

                    FormatterClass().saveSharedPref(
                        "activeResponse",
                        questionnaireResponse.idPart,
                        this@GeneralEditorActivity
                    )
                    //let's get the first section of the Questionnaire response with the reporting site
                    val jsonObject = JSONObject(questionnaireResponseString)
                    val extractedAnswers =
                        FormatterClass().extractStructuredAnswersOnlyFromItems(jsonObject)

                    val county = extractedAnswers.find { it.linkId == "294367770999" }?.answer
                    val subCountry = extractedAnswers.find { it.linkId == "819946803642" }?.answer
                    val ward = extractedAnswers.find { it.linkId == "819943434" }?.answer
                    val facility = extractedAnswers.find { it.linkId == "819946803677" }?.answer


                    val reportingSite = questionnaireResponse.item
                        .find { it.linkId == "151479012557" }
                        ?: QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
                            linkId = "151479012557"
                            text = "Reporting Site"
                            questionnaireResponse.addItem(this)
                        }

                    val userCounty =
                        createCountyAnswer("$county", "$county", "294367770999", "County")

                    val userSubCounty = createCountyAnswer(
                        "$subCountry",
                        "$subCountry",
                        "819946803642",
                        "Sub County"
                    )


                    val userWard = createCountyAnswer("$ward", "$ward", "819943434", "Ward")


                    val userFacility =
                        createCountyAnswer("$facility", "$facility", "819946803677", "Facility")
                    // 3. Update ONLY those items
                    reportingSite.updateOrAddChild(userCounty)
                    reportingSite.updateOrAddChild(userSubCounty)
                    reportingSite.updateOrAddChild(userWard)
                    reportingSite.updateOrAddChild(userFacility)


                    if (supportFragmentManager.findFragmentByTag(QUESTIONNAIRE_FRAGMENT_TAG) == null) {
                        supportFragmentManager.commit {
                            setReorderingAllowed(true)
                            val questionnaireFragmentBuilder =
                                QuestionnaireFragment.builder().apply {
                                    setShowSubmitAnywayButton(false)
                                    setQuestionnaireResponse(
                                        FhirContext.forR4Cached().newJsonParser()
                                            .encodeResourceToString(questionnaireResponse)
                                    )
                                    setCustomQuestionnaireItemViewHolderFactoryMatchersProvider(
                                        ContribQuestionnaireItemViewHolderFactoryMatchersProviderFactory
                                            .LOCATION_WIDGET_PROVIDER,
                                    )
                                    setQuestionnaire(viewModel.questionnaireJson)
                                }
                            add(
                                R.id.add_patient_container,
                                questionnaireFragmentBuilder.build(),
                                QUESTIONNAIRE_FRAGMENT_TAG
                            )
                        }
                    }
                }
            } else {
                Toast.makeText(
                    this@GeneralEditorActivity,
                    "Record Not Found, Please Try Again.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

    }

    fun QuestionnaireResponse.QuestionnaireResponseItemComponent.updateOrAddChild(
        newChild: QuestionnaireResponse.QuestionnaireResponseItemComponent
    ) {
        val existing = this.item.find { it.linkId == newChild.linkId }

        if (existing != null) {
            // Replace only the content of this single child item
            existing.text = newChild.text
            existing.answer = newChild.answer
            existing.item = newChild.item
        } else {
            // Add if missing
            this.addItem(newChild)
        }
    }


    fun createCountyAnswer(
        ref: String,
        dis: String,
        id: String,
        label: String
    ): QuestionnaireResponse.QuestionnaireResponseItemComponent {

        val reference = Reference().apply {
            reference = "Location/$ref"
            display = dis
        }

        return QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
            linkId = id
            text = label
            answerFirstRep.value = reference
        }
    }

    private fun showCancelScreenerQuestionnaireAlertDialog() {
        val builder = AlertDialog.Builder(this)
        builder.apply {
            setMessage(getString(R.string.cancel_questionnaire_message))
            setPositiveButton(getString(android.R.string.yes)) { _, _ ->
                this@GeneralEditorActivity.finish()
            }
            setNegativeButton(getString(android.R.string.no)) { _, _ -> }
        }
        val alertDialog = builder.create()
        alertDialog.show()
    }

    fun showSuccessDialog(context: Context) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.success_dialog, null)
        val alertDialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialogView.findViewById<MaterialButton>(R.id.btn_cancel).setOnClickListener {
            alertDialog.dismiss()
            this@GeneralEditorActivity.finish()
        }

        dialogView.findViewById<MaterialButton>(R.id.btn_finish).setOnClickListener {
            // handle finish action
            this@GeneralEditorActivity.finish()
            alertDialog.dismiss()
        }

        alertDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        alertDialog.show()
    }

    override fun onBackPressed() {

        showCancelScreenerQuestionnaireAlertDialog()
        super.onBackPressed()
    }

    private fun updateArguments() {
        val json = FormatterClass().getSharedPref("questionnaire", this@GeneralEditorActivity)
        intent.putExtra(QUESTIONNAIRE_FILE_PATH_KEY, json)
    }

    override fun onSupportNavigateUp(): Boolean {
        showCancelScreenerQuestionnaireAlertDialog()
        return true
    }
}