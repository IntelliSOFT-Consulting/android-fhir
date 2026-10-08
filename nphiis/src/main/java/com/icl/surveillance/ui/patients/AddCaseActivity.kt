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
import com.icl.surveillance.fhir.forms.ContactFollowUpState
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.ui.patients.custom.VhfContactActions
import com.icl.surveillance.fhir.forms.FormPrefill
import com.icl.surveillance.fhir.forms.VhfContactTracker
import com.icl.surveillance.fhir.forms.VhfDemoMode
import com.icl.surveillance.models.UserRole
import com.icl.surveillance.utils.ContribQuestionnaireItemViewHolderFactoryMatchersProviderFactory
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.utils.LocationUtils
import com.icl.surveillance.utils.ProgressDialogManager
import com.icl.surveillance.viewmodels.ScreenerViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.Type

class AddCaseActivity : AppCompatActivity() {
    private val fhirEngine: FhirEngine by lazy {
        FhirApplication.fhirEngine(applicationContext)
    }

    private enum class SaveCaseType {
        LAB,
        CONTACT
    }

    private data class SaveCaseConfig(
        val type: SaveCaseType,
        val title: String? = null
    )

    companion object {
        private const val TAG = "AddCaseActivity"

        private val SAVE_CASE_CONFIGS = mapOf(
            "measles-lab-results.json" to
                    SaveCaseConfig(SaveCaseType.LAB, "Measles Lab Information"),
            "measles-lab-reg-results.json" to
                    SaveCaseConfig(SaveCaseType.LAB, "Measles Regional Lab Information"),
            "afp-case-stool-lab-results.json" to
                    SaveCaseConfig(SaveCaseType.LAB, "AFP Stool Lab Information"),
            "afp-sixty-days.json" to
                    SaveCaseConfig(SaveCaseType.LAB, "AFP 60 Day Follow Up"),
            "afp-itd-lab.json" to
                    SaveCaseConfig(SaveCaseType.LAB, "AFP ITD Lab Information"),
            FormFields.Vhf.LAB_FORM to
                    SaveCaseConfig(SaveCaseType.LAB, FormFields.Vhf.LAB_TITLE),
            FormFields.Vhf.CONTACT_FOLLOW_UP_FORM to
                    SaveCaseConfig(SaveCaseType.LAB, FormFields.Vhf.CONTACT_FOLLOW_UP_TITLE),
            FormFields.Vhf.CONTACT_MONITORING_FORM to
                    SaveCaseConfig(SaveCaseType.LAB, FormFields.Vhf.CONTACT_MONITORING_TITLE),
            "vl-case-lab-information.json" to
                    SaveCaseConfig(SaveCaseType.LAB, "VL Laboratory Examination"),
            "vl-case-sixMonthsFollowup.json" to
                    SaveCaseConfig(SaveCaseType.LAB, "VL Follow Up Information"),
            "vl-case-hospitilization.json" to
                    SaveCaseConfig(SaveCaseType.LAB, "VL Hospitalization Information"),
            "afp-final-lab-results.json" to
                    SaveCaseConfig(SaveCaseType.LAB, "AFP Final Lab Information"),
            "afp-contact-tracing.json" to
                    SaveCaseConfig(SaveCaseType.CONTACT)
        )
    }


    private val viewModel: ScreenerViewModel by viewModels()
    private lateinit var binding:
            ActivityAddCaseBinding // Binding class name is based on layout file name

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityAddCaseBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        val titleName = FormatterClass().getSharedPref("title", this@AddCaseActivity)
        val resolvedTitle =
            titleName?.trim().takeUnless { it.isNullOrBlank() }
                ?: getString(R.string.new_case_description)
        binding.toolbarTitle.text = resolvedTitle

        val questionnaire = FormatterClass().getSharedPref("questionnaire", this@AddCaseActivity)
        if (isLabQuestionnaire(questionnaire) && !hasLabAccess()) {
            Toast.makeText(
                this@AddCaseActivity,
                "Only Admin or Laboratory users can add lab information.",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        updateArguments()
        observePatientSaveAction()
        deferStartupWork(savedInstanceState)
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

    private fun deferStartupWork(savedInstanceState: Bundle?) {
        binding.root.post {
            if (isFinishing || isDestroyed) {
                return@post
            }

            requestCurrentLocationSafely()
            if (savedInstanceState == null) {
                addQuestionnaireFragment()
            }
        }
    }

    private fun requestCurrentLocationSafely() {
        LocationUtils.requestCurrentLocation(
            this,
            onLocationReceived = { lat, lon ->
                val latitude = lat.toString()
                val longitude = lon.toString()
                FormatterClass().saveSharedPref("latitude", latitude, this)
                FormatterClass().saveSharedPref("longitude", longitude, this)
            },
            onError = { error ->
                println("Error: $error")
            },
        )
    }

    override fun onDestroy() {
        if (isFinishing) {
            FormPrefill.clear(this)
            // A cancelled "Convert to case" must not carry over to the next monitoring entry.
            FormatterClass().deleteSharedPref(VhfContactActions.PREF_CONVERT_AFTER_MONITORING, this)
        }
        super.onDestroy()
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
            println("Response $questionnaireResponseString")
            if (!passesContactChecks(questionnaireResponse)) return@launch
            saveCase(questionnaireFragment.getQuestionnaireResponse(), questionnaireResponseString)
        }
    }

    private fun saveCase(
        questionnaireResponse: QuestionnaireResponse,
        questionnaireResponseString: String
    ) {
        val formatter = FormatterClass()
        val patientId = formatter.getSharedPref("patientIdParent", this@AddCaseActivity)
        val questionnaire = formatter.getSharedPref("questionnaire", this@AddCaseActivity)
        val encounterId = formatter.getSharedPref("encounterId", this@AddCaseActivity)

        if (patientId.isNullOrBlank() || encounterId.isNullOrBlank()) {
            Toast.makeText(
                this@AddCaseActivity,
                "Missing patient or encounter information. Please try again.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val saveConfig = questionnaire?.let(SAVE_CASE_CONFIGS::get)
        if (saveConfig == null) {
            Log.w(TAG, "Unsupported questionnaire for saveCase: $questionnaire")
            Toast.makeText(
                this@AddCaseActivity,
                "Unsupported questionnaire. Please try again.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        when (saveConfig.type) {
            SaveCaseType.LAB -> {
                val title = saveConfig.title ?: run {
                    Log.w(TAG, "Missing lab title for questionnaire: $questionnaire")
                    Toast.makeText(
                        this@AddCaseActivity,
                        "Unable to save this questionnaire. Please try again.",
                        Toast.LENGTH_SHORT
                    ).show()
                    return
                }

                viewModel.completeLabAssessment(
                    questionnaireResponse,
                    patientId,
                    encounterId,
                    title,
                    questionnaireResponseString,
                    this@AddCaseActivity
                )
            }

            SaveCaseType.CONTACT -> {
                viewModel.completeContactAssessment(
                    questionnaireResponse,
                    patientId,
                    encounterId,
                    questionnaireResponseString,
                    this@AddCaseActivity
                )
            }
        }
    }

    /**
     * VHF contact rules that need the contact's own record: a follow-up cannot be dated before the
     * date of contact, and a contact cannot be released before all 21 days have passed.
     */
    private suspend fun passesContactChecks(response: QuestionnaireResponse): Boolean {
        val formatter = FormatterClass()
        val questionnaire = formatter.getSharedPref("questionnaire", this@AddCaseActivity)
        if (questionnaire != FormFields.Vhf.CONTACT_FOLLOW_UP_FORM &&
            questionnaire != FormFields.Vhf.CONTACT_MONITORING_FORM
        ) return true
        val patientId = formatter.getSharedPref("patientIdParent", this@AddCaseActivity) ?: return true
        val encounterId = formatter.getSharedPref("encounterId", this@AddCaseActivity) ?: return true
        val demo = VhfDemoMode.refresh(this@AddCaseActivity)
        val state = withContext(Dispatchers.IO) {
            VhfContactTracker.load(fhirEngine, patientId, encounterId)
        } ?: return true

        val answers = flattenItems(response.item)
        fun answer(linkId: String) =
            answers.firstOrNull { it.linkId == linkId }?.answer?.firstOrNull()?.value

        val problem = if (questionnaire == FormFields.Vhf.CONTACT_FOLLOW_UP_FORM) {
            val exposure = state.exposureDate
            val followUpDate = (answer(FormFields.Vhf.FOLLOW_UP_DATE) as? DateType)
                ?.valueAsString?.let(VhfContactTracker::parseDate)
            val day = (answer(FormFields.Vhf.FOLLOW_UP_DAY) as? IntegerType)?.value
            val format = ContactFollowUpState.DISPLAY
            val dateProblem = when {
                // Demo mode records any day in the schedule dated today.
                demo || exposure == null || followUpDate == null -> null
                followUpDate.isBefore(exposure) ->
                    "Date of follow up cannot be before the date of contact (${format.format(exposure)})."

                // Day of follow up and its date must agree: day N is N days after the date of contact.
                day != null && exposure.plusDays(day.toLong()) != followUpDate -> {
                    val actualDay = ChronoUnit.DAYS.between(exposure, followUpDate)
                    "Day $day falls on ${format.format(exposure.plusDays(day.toLong()))}, but the date " +
                            "of follow up is ${format.format(followUpDate)} (day $actualDay). " +
                            "Correct the day or the date."
                }

                else -> null
            }
            val next = state.nextDay
            dateProblem ?: when {
                day == null -> null
                day in state.recordedDays -> "Day $day follow up is already recorded."
                // The schedule is filled in order: day N needs days 1 to N-1 first.
                day >= 1 && next != null && day > next ->
                    "Record day $next first. Follow ups are recorded in order."

                else -> null
            }
        } else {
            val status = answer(FormFields.Vhf.MONITORING_STATUS) as? Coding
            if (status?.code == FormFields.Vhf.STATUS_COMPLETED_CODE && !state.windowComplete) {
                "A contact can only be released once the day 21 follow up " +
                        "(${ContactFollowUpState.DISPLAY.format(state.lastDay)}) is recorded or has passed."
            } else null
        }
        if (problem != null) {
            Toast.makeText(this@AddCaseActivity, problem, Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    fun flattenItems(items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>):
            List<QuestionnaireResponse.QuestionnaireResponseItemComponent> {

        return items.flatMap { item ->
            listOf(item) + flattenItems(item.item)   // include this item + all children
        }
    }

    private fun addQuestionnaireFragment() {
        // Answers staged by the case summary (e.g. the contact's follow-up day); taken once.
        val prefill = FormPrefill.take(
            this, FormatterClass().getSharedPref("questionnaire", this)
        )
        val resourceId = FormatterClass().getSharedPref(
            "resourceId", this@AddCaseActivity
        ) // aka Questionnaire
        if (resourceId != null) {
            lifecycleScope.launch(Dispatchers.Default) {
                val searchResult =
                    fhirEngine.search<QuestionnaireResponse> {
                        filter(Resource.RES_ID, { value = of(resourceId) })
                    }
                if (searchResult.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@AddCaseActivity,
                            "Please try again later",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
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

                    if (prefill.isNotEmpty()) {
                        val questionnaire = FhirContext.forR4Cached().newJsonParser()
                            .parseResource(Questionnaire::class.java, viewModel.questionnaire)
                        FormPrefill.applyTo(questionnaire, resource, prefill)
                    }

                    val questionnaireResponseJson =
                        FhirContext.forR4Cached().newJsonParser().encodeResourceToString(resource)
                    val questionnaireJson = viewModel.questionnaire

                    withContext(Dispatchers.Main) {
                        if (supportFragmentManager.findFragmentByTag(QUESTIONNAIRE_FRAGMENT_TAG) != null) {
                            return@withContext
                        }
                        supportFragmentManager.commit {

                            setReorderingAllowed(true)
                            val questionnaireFragmentBuilder =
                                QuestionnaireFragment.builder().apply {
                                    setShowSubmitAnywayButton(false)
                                    setQuestionnaireResponse(questionnaireResponseJson)
                                    setCustomQuestionnaireItemViewHolderFactoryMatchersProvider(
                                        ContribQuestionnaireItemViewHolderFactoryMatchersProviderFactory
                                            .LOCATION_WIDGET_PROVIDER,
                                    )
                                    setQuestionnaire(questionnaireJson)
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

            if (continueConversion()) return@observe
            showSuccessDialog(this@AddCaseActivity)
        }
    }

    /**
     * Step 2 of "Convert to case": once its monitoring outcome is saved, the contact's case form
     * opens (Type of case set to Suspected) in place of the success dialog.
     */
    private fun continueConversion(): Boolean {
        val formatter = FormatterClass()
        val pending = formatter.getSharedPref(VhfContactActions.PREF_CONVERT_AFTER_MONITORING, this)
        val questionnaire = formatter.getSharedPref("questionnaire", this)
        formatter.deleteSharedPref(VhfContactActions.PREF_CONVERT_AFTER_MONITORING, this)
        if (pending != "true" || questionnaire != FormFields.Vhf.CONTACT_MONITORING_FORM) return false
        Toast.makeText(this, "Outcome saved. Now complete the case details.", Toast.LENGTH_LONG).show()
        VhfContactActions.editRecord(this, convertToSuspected = true) { finish() }
        return true
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

    private fun isLabQuestionnaire(questionnaire: String?): Boolean {
        return when (questionnaire) {
            "measles-lab-results.json",
            "measles-lab-reg-results.json",
            "afp-case-stool-lab-results.json",
            "afp-itd-lab.json",
            "afp-final-lab-results.json",
            "vl-case-lab-information.json",
            FormFields.Vhf.LAB_FORM -> true

            else -> false
        }
    }

    private fun hasLabAccess(): Boolean {
        val formatter = FormatterClass()
        val storedRole =
            formatter.getSharedPref("practitionerRole", this) ?: formatter.getSharedPref(
                "role",
                this
            )
        if (storedRole.isNullOrBlank()) {
            return false
        }
        UserRole.fromAny(storedRole)?.let { return it.canAccessLab }
        val role = storedRole.lowercase()
        return role.contains("admin") || role.contains("laboratory") || role.contains("lab")
    }

}
