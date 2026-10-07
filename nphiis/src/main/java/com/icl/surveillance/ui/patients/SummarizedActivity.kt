package com.icl.surveillance.ui.patients

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.search.search
import com.google.android.material.tabs.TabLayoutMediator
import com.google.gson.Gson
import com.icl.surveillance.R
import com.icl.surveillance.adapters.GroupPagerAdapter
import com.icl.surveillance.cases.GeneralEditorActivity
import com.icl.surveillance.databinding.ActivitySummarizedBinding
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.fhir.forms.CaseSlugs
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.fhir.forms.toCaseSlug
import com.icl.surveillance.models.ChildItem
import com.icl.surveillance.models.OutputGroup
import com.icl.surveillance.models.OutputItem
import com.icl.surveillance.models.QuestionnaireItem
import com.icl.surveillance.ui.patients.custom.ContactInformationFragment
import com.icl.surveillance.ui.patients.custom.FollowUpFormFragment
import com.icl.surveillance.ui.patients.custom.ITDLabFragment
import com.icl.surveillance.ui.patients.custom.LocalLabFragment
import com.icl.surveillance.ui.patients.custom.RegionalLabFragment
import com.icl.surveillance.ui.patients.custom.VlFollowupFragment
import com.icl.surveillance.ui.patients.custom.VlLabFragment
import com.icl.surveillance.ui.patients.custom.VlTreatmentFragment
import com.icl.surveillance.ui.patients.custom.afp.AFPFollowUpFragment
import com.icl.surveillance.ui.patients.data.LabResultsFragment
import com.icl.surveillance.ui.patients.data.RegionalLabResultsFragment
import com.icl.surveillance.ui.patients.responses.EditChecklistActivity
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.viewmodels.ClientDetailsViewModel
import com.icl.surveillance.viewmodels.factories.PatientDetailsViewModelFactory
import java.time.LocalDate
import java.time.Period
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hl7.fhir.r4.model.QuestionnaireResponse
import timber.log.Timber


class SummarizedActivity : AppCompatActivity() {
    private lateinit var groups: MutableList<OutputGroup>
    private lateinit var binding: ActivitySummarizedBinding
    private lateinit var fhirEngine: FhirEngine
    private lateinit var patientDetailsViewModel: ClientDetailsViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySummarizedBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val patientId = FormatterClass().getSharedPref("resourceId", this@SummarizedActivity)
        val currentCase = FormatterClass().getSharedPref("currentCase", this@SummarizedActivity)


        val slug = currentCase?.toCaseSlug()
        fhirEngine = FhirApplication.fhirEngine(this@SummarizedActivity)
        patientDetailsViewModel =
            ViewModelProvider(
                this,
                PatientDetailsViewModelFactory(
                    this@SummarizedActivity.application, fhirEngine, "$patientId"
                ),
            ).get(ClientDetailsViewModel::class.java)

        loadData()
    }

    override fun onResume() {
        super.onResume()
        try {
            loadData()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun loadData() {
        val patientId = FormatterClass().getSharedPref("resourceId", this@SummarizedActivity)
        val currentCase = FormatterClass().getSharedPref("currentCase", this)
        val latestEncounter = FormatterClass().getSharedPref("latestEncounter", this)
        val isCase = FormatterClass().getSharedPref("isCase", this)

        if (latestEncounter != null) {
            checkIfResourceHasQuestionnaireResponse(this, patientId)
            lifecycleScope.launch {
                val parsedGroups = withContext(Dispatchers.IO) {
                    parseFromAssets(this@SummarizedActivity, latestEncounter)
                }.toMutableList()
                configureTabs(parsedGroups, currentCase, latestEncounter, isCase)
            }
        } else {
            Toast.makeText(this, "Please try again later!!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun configureTabs(
        parsedGroups: MutableList<OutputGroup>,
        currentCase: String?,
        latestEncounter: String,
        isCase: String?
    ) {
        groups = parsedGroups
        val viewPager = binding.viewPager
        val tabLayout = binding.tabLayout

        if (currentCase != null) {
            val slug = currentCase.toCaseSlug()
            val key = when (slug) {
                CaseSlugs.RCCE -> {
                    val encounterQuestionnaire = FormatterClass().getSharedPref(
                        "encounterQuestionnaire",
                        this@SummarizedActivity
                    )
                    "$encounterQuestionnaire"
                }

                CaseSlugs.MPOX_INFORMATION -> CaseSlugs.MPOX_TALLY_SHEET

                else -> slug
            }
            patientDetailsViewModel.getPatientInfoSummaryData(key)
        }

        var customFragments = when (latestEncounter) {
            CaseSlugs.MEASLES -> {
                listOf(
                    "Laboratory Information" to LabResultsFragment(),
                )

            }

            CaseSlugs.AFP -> {
                listOf(
                    "Stool Specimen Results" to LocalLabFragment(),
//                    "ITD Lab Results" to ITDLabFragment(),
//                    "Final Laboratory Results" to RegionalLabFragment(),
                    "60 Day Follow Up" to AFPFollowUpFragment(),
                    "Contact Information" to ContactInformationFragment()
                )
            }

            CaseSlugs.VHF -> {
                listOf(
                    "Laboratory Results" to FollowUpFormFragment.newInstance(
                        FormFields.Vhf.LAB_TITLE,
                        FormFields.Vhf.LAB_FORM
                    )
                )
            }

            CaseSlugs.VL -> {
                listOf(
                    "Laboratory Examination" to VlLabFragment(),
                    "Treatment/Hospitalization" to VlTreatmentFragment(),
                    "Six months followup examinations" to VlFollowupFragment()
                )
            }

            else -> emptyList()
        }

        if (isCase != null) {
            if (isCase != "Case") {
                val itemToRemove = groups.find { it.linkId == "271053545237" }
                if (itemToRemove != null) {
                    groups.remove(itemToRemove)
                    customFragments = emptyList()

                }
            }
        }
        patientDetailsViewModel.liveSummaryData.observe(this) { data ->
            updateSummaryHeader(currentCase, latestEncounter, data)
            groups.forEach { group ->
                // For each item inside the group
                group.items.forEach { outputItem ->
                    // Try to find a matching observation
                    val matchingObservation = data.observations.find { obs ->
                        obs.code == outputItem.linkId
                    }
                    when (outputItem.linkId) {
                        "992818778559" -> { // Retrieve EPID No.
                            outputItem.value = data.epidNo
                        }

                        "920645761660" -> { // Calculate Days since onset
                            outputItem.value = calculateDaysSinceOnset(data.observations)
                        }

                        "calculated_age" -> { // Calculate Days since onset
                            outputItem.value = calculatePatientAge(data.observations)
                        }

                        "age-at-onset" -> {  // Calculate Age at Onset
                            outputItem.value = calculateAgeAtOnset(data.observations)
                        }

                        else ->
                            if (matchingObservation != null) {
                                outputItem.value = matchingObservation.value
                            }
                    }
                }
            }
            val adapter = GroupPagerAdapter(this, groups, customFragments)
            viewPager.adapter = adapter

            TabLayoutMediator(tabLayout, viewPager) { tab, position ->
                tab.text = adapter.getTabTitle(position)
            }.attach()
        }
    }

    private fun updateSummaryHeader(
        currentCase: String?,
        latestEncounter: String?,
        data: PatientListViewModel.CaseDetailSummaryData
    ) {
        val name = data.name.trim()
        binding.summaryTitle.text = if (name.isNotBlank()) name else "Summary"

        val caseLabel = formatCaseTitle(currentCase ?: latestEncounter)
        binding.summarySubtitle.text = if (caseLabel.isNotBlank()) {
            "Case: $caseLabel"
        } else {
            "Case summary"
        }

        val epid = data.epidNo.trim()
        binding.summaryChip.text = if (epid.isNotBlank()) {
            "EPID: $epid"
        } else {
            "Auto-generated"
        }
    }

    private fun formatCaseTitle(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw
            .trim()
            .replace("-", " ")
            .replace("_", " ")
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.lowercase(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }
            }
    }

    private fun checkIfResourceHasQuestionnaireResponse(
        context: Context,
        patientId: String?
    ) {
        if (patientId != null) {
            lifecycleScope.launch {
                val logicalId =
                    patientDetailsViewModel.checkIfResourceHasQuestionnaireResponse(patientId)

                if (logicalId.isNotEmpty()) {
                    // create the option menu:
                    FormatterClass().saveSharedPref("patientId", patientId, context)
                    FormatterClass().saveSharedPref("resourceId", logicalId, context)
                    patientDetailsViewModel.hasQuestionnaireResponse = true

                    invalidateOptionsMenu()
                    supportActionBar?.setDisplayHomeAsUpEnabled(true)
                    supportActionBar?.setDisplayShowHomeEnabled(true)

                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_edit, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val hasResponse = patientDetailsViewModel.hasQuestionnaireResponse // set this as a flag
        menu.findItem(R.id.action_edit)?.isVisible = hasResponse
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_delete -> {
//                SweetAlertDialog(this, SweetAlertDialog.WARNING_TYPE)
//                    .setTitleText("Are you sure?")
//                    .setContentText("You Won't be able to recover this record!")
//                    .setConfirmText("Yes,delete it!")
//                    .setConfirmClickListener { sDialog ->
//                        val patientId =
//                            FormatterClass().getSharedPref("patientId", this@SummarizedActivity)
//                        if (patientId != null) {
//                            lifecycleScope.launch {
//                            }
//                        }
//                        Toast.makeText(this, "Resource Deleted!!", Toast.LENGTH_SHORT).show()
//                        sDialog.dismissWithAnimation()
//                    }
//                    .show()
                return true
            }

            R.id.action_edit -> {

                val currentCase =
                    FormatterClass().getSharedPref("currentCase", this@SummarizedActivity)
                if (currentCase != null) {
                    val slug = currentCase.toCaseSlug()

                    when (slug) {
                        CaseSlugs.MOH_505 -> {
                            lifecycleScope.launch {
                                val patientId =
                                    FormatterClass().getSharedPref(
                                        "patientIdParent",
                                        this@SummarizedActivity
                                    )
                                if (patientId != null) {
                                    val res = fhirEngine.search<QuestionnaireResponse> {
                                        filter(
                                            QuestionnaireResponse.SUBJECT,
                                            { value = "Patient/$patientId" })
                                    }.take(5)
                                    if (res.isNotEmpty()) {
                                        val response = res.first().resource
                                        FormatterClass().saveSharedPref(
                                            "questionnaire",
                                            "moh505.json", this@SummarizedActivity
                                        )
                                        startActivity(
                                            Intent(
                                                this@SummarizedActivity,
                                                GeneralEditorActivity::class.java
                                            ).apply {

                                            }
                                        )

                                    }
                                } else {
                                    Toast.makeText(
                                        this@SummarizedActivity,
                                        "Patient Id not found",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }

                        }

                        CaseSlugs.MPOX_TALLY_SHEET -> {
                            FormatterClass().saveSharedPref(
                                "questionnaire",
                                "mpox-tally-sheet.json",
                                this@SummarizedActivity
                            )
                            Toast.makeText(
                                this@SummarizedActivity,
                                "Coming soon",
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                        else -> {
                            // Every questionnaire-based module edits its saved response in place.
                            val questionnaireFile = questionnaireFileFor(slug)
                            if (questionnaireFile.isEmpty()) {
                                Toast.makeText(
                                    this@SummarizedActivity,
                                    "Editing is not available for this record",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                FormatterClass().saveSharedPref(
                                    "questionnaire",
                                    questionnaireFile,
                                    this@SummarizedActivity
                                )
                                startActivity(
                                    Intent(this@SummarizedActivity, EditChecklistActivity::class.java)
                                )
                            }
                        }
                    }

                }
                return true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }


    fun calculatePatientAge(observations: List<PatientListViewModel.ObservationItem>): String {
        var age = "0"
        val formatter = DateTimeFormatter.ISO_DATE // assumes date format is "yyyy-MM-dd"

        val dob = observations.find { obs ->
            obs.code == "257830485990"
        }?.value
        val created = observations.find { obs ->
            obs.code == "257830485990"
        }?.created

        if (dob == null || created == null) age = "0"
        try {
            val dobDate = LocalDate.parse(dob, formatter)
            // Parse the created date using a formatter
            val createdFormatter = DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss zzz yyyy")
            val createdDate = ZonedDateTime.parse(created, createdFormatter).toLocalDate()

            val period = Period.between(dobDate, createdDate)

            age = "${period.years} years, ${period.months} months, ${period.days} days"
        } catch (e: Exception) {
            age = "0"
        }
        return age
    }

    fun calculateDaysSinceOnset(observations: List<PatientListViewModel.ObservationItem>): String {
        var age = "0"
        val date = observations.find { obs ->
            obs.code == "728034137219"
        }?.value
        val created = observations.find { obs ->
            obs.code == "728034137219"
        }?.created
        if (date == null || created == null) age = "0"
        try {

            // Parse the onset date (simple ISO format)
            val onsetDate = LocalDate.parse(date)
            // Parse the created date using a formatter
            val createdFormatter = DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss zzz yyyy")
            val createdDate = ZonedDateTime.parse(created, createdFormatter).toLocalDate()

            // Calculate the days between
            val daysBetween = ChronoUnit.DAYS.between(onsetDate, createdDate)

            age = "$daysBetween"
        } catch (e: Exception) {
            age = "0"
        }

        return age
    }

    fun calculateAgeAtOnset(observations: List<PatientListViewModel.ObservationItem>): String {
        var age = "0"
        val formatter = DateTimeFormatter.ISO_DATE // assumes date format is "yyyy-MM-dd"

        val dob = observations.find { obs ->
            obs.code == "257830485990"
        }?.value
        val onset = observations.find { obs ->
            obs.code == "728034137219"
        }?.value

        if (dob == null || onset == null) age = "0"
        try {
            val dobDate = LocalDate.parse(dob, formatter)
            val onsetDate = LocalDate.parse(onset, formatter)

            val period = Period.between(dobDate, onsetDate)

            age = "${period.years} years, ${period.months} months, ${period.days} days"
        } catch (e: Exception) {
            age = "0"
        }
        return age
    }

    /** Questionnaire asset of a module (case-list slug); empty when the module has none. */
    private fun questionnaireFileFor(slug: String): String {
        return when (slug) {
            CaseSlugs.MEASLES -> "add-case.json"
            CaseSlugs.AFP -> "afp-case.json"
            CaseSlugs.VL -> "vl-case.json"
            CaseSlugs.VHF -> "vhf-case.json"
            CaseSlugs.MOH_505 -> "moh505.json"
            CaseSlugs.MPOX_INFORMATION -> "mpox-tally-sheet.json"
            CaseSlugs.MPOX_TALLY_SHEET -> "mpox-tally-sheet.json"
            CaseSlugs.RUMOR -> "rumor-tracking-case.json"
            CaseSlugs.MPOX_REGISTER -> "mpox-register.json"
            CaseSlugs.RCCE -> {

                val encounterQuestionnaire = FormatterClass().getSharedPref(
                    "encounterQuestionnaire",
                    this@SummarizedActivity
                )
                println("This is the latest encounter $encounterQuestionnaire")
                when (encounterQuestionnaire) {
                    CaseSlugs.RCCE_COMMUNITY -> "social-community.json"
                    CaseSlugs.RCCE_COUNTY -> "social-county.json"
                    else -> ""

                }
            }

            else -> ""
        }
    }

    fun parseFromAssets(context: Context, latestEncounter: String): List<OutputGroup> {
        var outputGroups: List<OutputGroup> = emptyList()

        val assets = questionnaireFileFor(latestEncounter)
        try {
            if (assets.isNotEmpty()) {
                val jsonContent = context.assets.open(assets)
                    .bufferedReader()
                    .use { it.readText() }

                val gson = Gson()
                val questionnaire = gson.fromJson(jsonContent, QuestionnaireItem::class.java)

                outputGroups = questionnaire.item.map { group ->
                    OutputGroup(
                        linkId = group.linkId,
                        text = group.text,
                        type = group.type,
                        items = group.item?.flatMap { flattenItems(it) } ?: emptyList()
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Timber.tag("TAG").e("File Error ${e.message}")
        }
        return outputGroups

    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }


    fun flattenItems(
        item: ChildItem,
        parentConditions: Map<String, Pair<String, Boolean>> = emptyMap()
    ): List<OutputItem> {
        val currentConditions =
            mutableMapOf<String, Pair<String, Boolean>>().apply { putAll(parentConditions) }

        var enable = true
        var parentLink: String? = null
        var parentResponse: String? = null
        var enableOperator: String? = null
        item.enableWhen?.firstOrNull()?.let { condition ->
            parentLink = condition.question
            enableOperator = condition.operator
            val expectedAnswer = when {
                condition.answerCoding != null -> condition.answerCoding.display
                    ?: condition.answerCoding.code

                condition.answerString != null -> condition.answerString
                condition.answerBoolean != null -> condition.answerBoolean.toString()
                condition.answerDate != null -> condition.answerDate
                condition.answerInteger != null -> condition.answerInteger.toString()
                else -> null
            }
            parentResponse = expectedAnswer
            enable = false // assume not enabled unless condition is met at runtime
        }

        val children = item.item?.flatMap {
            flattenItems(it, currentConditions)
        } ?: emptyList()

        return if (item.type != "display") {

            val current = OutputItem(
                linkId = item.linkId,
                text = item.text,
                type = item.type,
                enable = enable,
                parentLink = parentLink,
                parentResponse = parentResponse,
                parentOperator = enableOperator
            )

            listOf(current) + children

        } else {
            children
        }
    }

}
