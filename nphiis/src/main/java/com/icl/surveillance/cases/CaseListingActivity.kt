package com.icl.surveillance.cases

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.fhir.FhirEngine
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.icl.surveillance.R
import com.icl.surveillance.adapters.MpoxPatientAdapter
import com.icl.surveillance.adapters.PatientItemRecyclerViewAdapter
import com.icl.surveillance.adapters.PatientItemRecyclerViewAdapterRumor
import com.icl.surveillance.databinding.ActivityCaseListingBinding
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.models.UserRole
import com.icl.surveillance.ui.patients.FullCaseDetailsActivity
import com.icl.surveillance.ui.patients.PatientListViewModel
import com.icl.surveillance.ui.patients.SummarizedActivity
import com.icl.surveillance.ui.patients.responses.ResponseQuestionnaireActivity
import com.icl.surveillance.utils.FormatterClass
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class CaseListingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaseListingBinding
    private lateinit var fhirEngine: FhirEngine
    private lateinit var patientListViewModel: PatientListViewModel
    private var roleScopedCases: List<PatientListViewModel.PatientItem> = emptyList()
    private val selectedCounties = mutableSetOf<String>()
    private val selectedSubCounties = mutableSetOf<String>()
    private var currentRole: UserRole? = null
    private var searchQuery: String = ""
    private var searchListenerAttached = false
    private var activeCaseAdapter: PatientItemRecyclerViewAdapter? = null
    private var activeMpoxAdapter: MpoxPatientAdapter? = null
    private var showLocationFilterMenu: Boolean = false
    private var mpoxPatientsCollectorJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCaseListingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        val titleName = FormatterClass().getSharedPref("listingTitle", this)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        val resolvedTitle =
            titleName?.trim().takeUnless { it.isNullOrBlank() }
                ?: getString(R.string.title_dashboard)
        binding.toolbarTitle.text = resolvedTitle


        fhirEngine = FhirApplication.fhirEngine(this)
        patientListViewModel =
            ViewModelProvider(
                this,
                PatientListViewModel.PatientListViewModelFactory(
                    this.application, fhirEngine
                ),
            ).get(PatientListViewModel::class.java)
        setupSearchListener()
        loadData()
    }

    fun loadData() {

        val units = FormatterClass().getFacilityIdsForWard(this@CaseListingActivity, "units")
        val titleName = FormatterClass().getSharedPref("listingTitle", this)
        val currentCase = FormatterClass().getSharedPref("currentCase", this)
        val recyclerView: RecyclerView = binding.patientListContainer.patientList
        val adapter = PatientItemRecyclerViewAdapter(this::onPatientItemClicked, "$titleName", this)
        val adapterRumor = PatientItemRecyclerViewAdapterRumor(this::onRumorItemClicked)

        val formatter = FormatterClass()
        val storedRole = formatter.getSharedPref("practitionerRole", this)
        val storedCounty = formatter.getSharedPref("countyName", this)
        val storedSubCounty = formatter.getSharedPref("subCountyName", this)
        val userRole = UserRole.fromAny(storedRole ?: "")
        currentRole = userRole
        searchQuery = binding.tvEpidNo.text?.toString().orEmpty()

        if (currentCase != null) {
            val slug = currentCase.toSlug()
            when (slug) {
                "social-listening-and-rumor-tracking-tool" -> {
                    mpoxPatientsCollectorJob?.cancel()
                    activeMpoxAdapter = null
                    showLocationFilterMenu = false
                    invalidateOptionsMenu()
                    activeCaseAdapter = null
                    patientListViewModel.liveRumorCases.removeObservers(this)
                    patientListViewModel.handleCurrentRumorCaseListing(slug, units, userRole)
                    recyclerView.adapter = adapterRumor
                    patientListViewModel.liveRumorCases.observe(this) {
                        binding.apply {
                            count.visibility = View.VISIBLE
                            count.text = "Showing ${it.size} Results"
                            patientListContainer.pbProgress.visibility = View.GONE
                        }

                        if (it.isEmpty()) {
                            binding.apply {
                                patientListContainer.emptyStateLayout.visibility = View.VISIBLE
                                patientListContainer.caseCount.text =
                                    getString(R.string.matching_cases_single, it.size)
                            }
                        } else {
                            binding.apply {
                                patientListContainer.emptyStateLayout.visibility = View.GONE
                            }
                        }

                        adapterRumor.submitList(it)
                    }
                }

                "mpox-register" -> {
                    showLocationFilterMenu = canShowLocationFilters(userRole)
                    if (!showLocationFilterMenu) {
                        selectedCounties.clear()
                        selectedSubCounties.clear()
                    }
                    invalidateOptionsMenu()
                    activeCaseAdapter = null
                    mpoxPatientsCollectorJob?.cancel()
                    val adapterRegister = MpoxPatientAdapter(
                        mutableListOf(),
                        this::onPatientItemClicked,
                        "$titleName",
                        this@CaseListingActivity
                    )
                    activeMpoxAdapter = adapterRegister
                    recyclerView.adapter = adapterRegister
                    recyclerView.layoutManager = LinearLayoutManager(this@CaseListingActivity)
                    patientListViewModel.loadMpoxPatientList(slug, units, userRole)


                    mpoxPatientsCollectorJob = lifecycleScope.launch {
                        patientListViewModel.patients.collect { newList ->
                            roleScopedCases =
                                applyRoleScope(newList, userRole, storedCounty, storedSubCounty)
                            pruneSelectedFilters()
                            applyCaseFilters()
                            binding.patientListContainer.pbProgress.visibility = View.GONE
                        }
                    }

                    recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                            super.onScrolled(rv, dx, dy)
                            val layoutManager = rv.layoutManager as LinearLayoutManager
                            val visibleItemCount = layoutManager.childCount
                            val totalItemCount = layoutManager.itemCount
                            val firstVisibleItem = layoutManager.findFirstVisibleItemPosition()

                            if (visibleItemCount + firstVisibleItem >= totalItemCount - 5) {
                                patientListViewModel.loadMpoxPatientList(slug, units, userRole)
                            }
                        }
                    })
                }

                else -> {
                    mpoxPatientsCollectorJob?.cancel()
                    activeMpoxAdapter = null
                    activeCaseAdapter = adapter
                    showLocationFilterMenu = canShowLocationFilters(userRole)
                    if (!showLocationFilterMenu) {
                        selectedCounties.clear()
                        selectedSubCounties.clear()
                    }
                    invalidateOptionsMenu()
                    patientListViewModel.liveSearchedCases.removeObservers(this)
                    patientListViewModel.handleCurrentCaseListing(slug, units, userRole)
                    recyclerView.adapter = adapter
                    patientListViewModel.liveSearchedCases.observe(this) { cases ->
                        roleScopedCases =
                            applyRoleScope(cases, userRole, storedCounty, storedSubCounty)
                        pruneSelectedFilters()
                        applyCaseFilters()
                        binding.patientListContainer.pbProgress.visibility = View.GONE
                    }
                }
            }
        } else {
            mpoxPatientsCollectorJob?.cancel()
            activeMpoxAdapter = null
            showLocationFilterMenu = false
            invalidateOptionsMenu()
        }
    }

    private fun setupSearchListener() {
        if (searchListenerAttached) return
        binding.tvEpidNo.addTextChangedListener { text ->
            searchQuery = text?.toString().orEmpty()
            applyCaseFilters()
        }
        searchListenerAttached = true
    }

    private fun canShowLocationFilters(role: UserRole?): Boolean {
        return role == UserRole.ADMINISTRATOR || role == UserRole.COUNTY_DISEASE_SURVEILLANCE_OFFICER
    }

    private fun normalizeLocationValue(value: String): String {
        return value.trim().lowercase(Locale.getDefault())
    }

    private fun hasSelection(value: String, selected: Set<String>): Boolean {
        if (selected.isEmpty()) return false
        val normalizedValue = normalizeLocationValue(value)
        return selected.any { normalizeLocationValue(it) == normalizedValue }
    }

    private fun getAvailableCounties(): List<String> {
        return roleScopedCases
            .map { it.county.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { normalizeLocationValue(it) }
            .sortedBy { normalizeLocationValue(it) }
    }

    private fun getAvailableSubCounties(): List<String> {
        val sourceCases = if (
            currentRole == UserRole.ADMINISTRATOR &&
            selectedCounties.isNotEmpty()
        ) {
            roleScopedCases.filter { hasSelection(it.county, selectedCounties) }
        } else {
            roleScopedCases
        }

        return sourceCases
            .map { it.subCounty.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { normalizeLocationValue(it) }
            .sortedBy { normalizeLocationValue(it) }
    }

    private fun pruneSelectedFilters() {
        val countyOptions = getAvailableCounties()
        val countyKeys = countyOptions.map { normalizeLocationValue(it) }.toSet()
        selectedCounties.removeIf { normalizeLocationValue(it) !in countyKeys }

        val subCountyOptions = getAvailableSubCounties()
        val subCountyKeys = subCountyOptions.map { normalizeLocationValue(it) }.toSet()
        selectedSubCounties.removeIf { normalizeLocationValue(it) !in subCountyKeys }

        invalidateOptionsMenu()
    }

    private fun showCountyFilterDialog() {
        val options = getAvailableCounties()
        if (options.isEmpty()) {
            Toast.makeText(this, R.string.no_filter_options_available, Toast.LENGTH_SHORT).show()
            return
        }

        val workingSelection = selectedCounties.toMutableSet()
        val checkedItems = options.map { hasSelection(it, workingSelection) }.toBooleanArray()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.filter_county)
            .setMultiChoiceItems(options.toTypedArray(), checkedItems) { _, which, isChecked ->
                val value = options[which]
                if (isChecked) {
                    workingSelection.add(value)
                } else {
                    workingSelection.removeIf {
                        normalizeLocationValue(it) == normalizeLocationValue(value)
                    }
                }
            }
            .setNeutralButton(R.string.reset) { _, _ ->
                selectedCounties.clear()
                pruneSelectedFilters()
                applyCaseFilters()
            }
            .setPositiveButton(R.string.apply) { _, _ ->
                selectedCounties.clear()
                selectedCounties.addAll(workingSelection)
                pruneSelectedFilters()
                applyCaseFilters()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        styleDialogActionButtons(dialog)
    }

    private fun showSubCountyFilterDialog() {
        val options = getAvailableSubCounties()
        if (options.isEmpty()) {
            Toast.makeText(this, R.string.no_filter_options_available, Toast.LENGTH_SHORT).show()
            return
        }

        val workingSelection = selectedSubCounties.toMutableSet()
        val checkedItems = options.map { hasSelection(it, workingSelection) }.toBooleanArray()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.filter_sub_county)
            .setMultiChoiceItems(options.toTypedArray(), checkedItems) { _, which, isChecked ->
                val value = options[which]
                if (isChecked) {
                    workingSelection.add(value)
                } else {
                    workingSelection.removeIf {
                        normalizeLocationValue(it) == normalizeLocationValue(value)
                    }
                }
            }
            .setNeutralButton(R.string.reset) { _, _ ->
                selectedSubCounties.clear()
                applyCaseFilters()
                invalidateOptionsMenu()
            }
            .setPositiveButton(R.string.apply) { _, _ ->
                selectedSubCounties.clear()
                selectedSubCounties.addAll(workingSelection)
                applyCaseFilters()
                invalidateOptionsMenu()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        styleDialogActionButtons(dialog)
    }

    private fun styleDialogActionButtons(dialog: AlertDialog) {
        val actionColor = MaterialColors.getColor(
            this,
            androidx.appcompat.R.attr.colorPrimary,
            ContextCompat.getColor(this, R.color.blue)
        )
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(actionColor)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(actionColor)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(actionColor)
    }

    private fun resetLocationFilters() {
        selectedCounties.clear()
        selectedSubCounties.clear()
        applyCaseFilters()
        invalidateOptionsMenu()
    }

    private fun applyRoleScope(
        cases: List<PatientListViewModel.PatientItem>,
        userRole: UserRole?,
        storedCounty: String?,
        storedSubCounty: String?
    ): List<PatientListViewModel.PatientItem> {
        return when (userRole) {
            UserRole.COUNTY_DISEASE_SURVEILLANCE_OFFICER -> {
                if (storedCounty.isNullOrBlank()) {
                    cases
                } else {
                    cases.filter { it.county.contains(storedCounty, ignoreCase = true) }
                }
            }

            UserRole.SUBCOUNTY_DISEASE_SURVEILLANCE_OFFICER -> {
                if (storedSubCounty.isNullOrBlank()) {
                    cases
                } else {
                    cases.filter { it.subCounty.contains(storedSubCounty, ignoreCase = true) }
                }
            }

            else -> cases
        }
    }

    private fun applyCaseFilters() {
        val adapter = activeCaseAdapter
        val mpoxAdapter = activeMpoxAdapter
        if (adapter == null && mpoxAdapter == null) return

        var filtered = roleScopedCases
        if (currentRole == UserRole.ADMINISTRATOR && selectedCounties.isNotEmpty()) {
            filtered = filtered.filter { hasSelection(it.county, selectedCounties) }
        }
        if (
            (currentRole == UserRole.ADMINISTRATOR || currentRole == UserRole.COUNTY_DISEASE_SURVEILLANCE_OFFICER)
            && selectedSubCounties.isNotEmpty()
        ) {
            filtered = filtered.filter { hasSelection(it.subCounty, selectedSubCounties) }
        }
        if (searchQuery.isNotBlank()) {
            filtered = filtered.filter {
                it.epid.contains(searchQuery, ignoreCase = true) ||
                    it.name.contains(searchQuery, ignoreCase = true)
            }
        }

        adapter?.setData(filtered)
        mpoxAdapter?.setData(filtered)
        binding.count.visibility = View.VISIBLE
        binding.count.text = "Showing ${filtered.size} Results"
        binding.patientListContainer.emptyStateLayout.visibility =
            if (filtered.isEmpty()) View.VISIBLE else View.GONE
        binding.patientListContainer.caseCount.text =
            getString(R.string.matching_cases_single, filtered.size)
        invalidateOptionsMenu()
    }

    override fun onResume() {
        super.onResume()
        try {
            loadData()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        mpoxPatientsCollectorJob?.cancel()
        super.onDestroy()
    }

    private fun onRumorItemClicked(patientItem: PatientListViewModel.RumorItem) {
        val currentCase = FormatterClass().getSharedPref("currentCase", this)
        FormatterClass().saveSharedPref("resourceId", patientItem.resourceId, this)
        FormatterClass().saveSharedPref("encounterId", patientItem.encounterId, this)
        FormatterClass().deleteSharedPref("isCase", this)
        if (currentCase != null) {
            val slug = currentCase.toSlug()

            FormatterClass().saveSharedPref("latestEncounter", slug, this)
            when (slug) {
                "social-listening-and-rumor-tracking-tool",
                "vl-case-information",
                "moh-505-reporting-form",
                "afp-case-information" -> {
                    startActivity(Intent(this@CaseListingActivity, SummarizedActivity::class.java))
                }

                else -> {
                    startActivity(
                        Intent(
                            this@CaseListingActivity,
                            FullCaseDetailsActivity::class.java
                        )
                    )
                }
            }
        } else {
            Toast.makeText(this, "Please try again later ", Toast.LENGTH_SHORT).show()
        }
    }

    private fun onPatientItemClicked(patientItem: PatientListViewModel.PatientItem) {
        val currentCase = FormatterClass().getSharedPref("currentCase", this)
        FormatterClass().saveSharedPref("patientId", patientItem.resourceId, this)
        FormatterClass().saveSharedPref("resourceId", patientItem.resourceId, this)
        FormatterClass().saveSharedPref("encounterId", patientItem.encounterId, this)
        FormatterClass().saveSharedPref(
            "encounterQuestionnaire",
            patientItem.encounterQuestionnaire,
            this
        )
        FormatterClass().deleteSharedPref("isCase", this)
        FormatterClass().deleteSharedPref("isVaccinated", this)

        FormatterClass().saveSharedPref("patientIdParent", patientItem.resourceId, this)

        println("Parent Encounter  Clicked ${patientItem.encounterId} and respective Patient ${patientItem.resourceId}")
        if (currentCase != null) {
            val slug = currentCase.toSlug()

            FormatterClass().saveSharedPref("latestEncounter", slug, this)
            val activityIntent = Intent(this@CaseListingActivity, SummarizedActivity::class.java)
            val activityIntent2 =
                Intent(this@CaseListingActivity, ResponseQuestionnaireActivity::class.java)
            when (slug) {

                "mpox-supervisor-checklist" -> {

                    startActivity(activityIntent2)

                }

                "social-listening-and-rumor-tracking-tool",
                "vl-case-information", "mpox-tally-sheet",
                "afp-case-information",
                "rcce" -> {
                    startActivity(activityIntent)
                }

                else -> {
                    FormatterClass().apply {
                        saveSharedPref("isCase", patientItem.caseList, this@CaseListingActivity)
                        saveSharedPref(
                            "isVaccinated",
                            patientItem.vaccinated,
                            this@CaseListingActivity
                        )
                    }
                    startActivity(activityIntent)
                }
            }

        } else {
            Toast.makeText(this, "Please try again later ", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_upload, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val canUseLocationFilters = showLocationFilterMenu && canShowLocationFilters(currentRole)
        val filterItem = menu?.findItem(R.id.action_filter)
        filterItem?.isVisible = canUseLocationFilters
        filterItem?.subMenu?.findItem(R.id.action_filter_county)?.isVisible =
            currentRole == UserRole.ADMINISTRATOR
        filterItem?.subMenu?.findItem(R.id.action_filter_sub_county)?.isVisible =
            currentRole == UserRole.ADMINISTRATOR ||
                currentRole == UserRole.COUNTY_DISEASE_SURVEILLANCE_OFFICER
        filterItem?.subMenu?.findItem(R.id.action_reset_location_filters)?.isVisible =
            selectedCounties.isNotEmpty() || selectedSubCounties.isNotEmpty()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_filter_county -> {
                showCountyFilterDialog()
                true
            }

            R.id.action_filter_sub_county -> {
                showSubCountyFilterDialog()
                true
            }

            R.id.action_reset_location_filters -> {
                resetLocationFilters()
                true
            }

            R.id.action_refresh -> {
//                SweetAlertDialog(this, SweetAlertDialog.WARNING_TYPE)
//                    .setTitleText("Are you sure?")
//                    .setContentText("Are you sure you wish to upload your  data?")
//                    .setConfirmText("Yes,Upload!")
//                    .setConfirmClickListener { sDialog ->
//                        lifecycleScope.launch {
//                            //  patientListViewModel.prepareUploadData("mpox-register")
//                            val workRequest = OneTimeWorkRequestBuilder<MpoxSyncWorker>().build()
//                            WorkManager.getInstance(this@CaseListingActivity).enqueue(workRequest)
//                        }
//                        Toast.makeText(
//                            this@CaseListingActivity,
//                            "Uploading data.....",
//                            Toast.LENGTH_SHORT
//                        )
//                            .show()
//                        sDialog.dismissWithAnimation()
//                    }
//                    .show()


                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun String.toSlug(): String {
        return this
            .trim() // remove leading/trailing spaces
            .lowercase() // make all lowercase
            .replace("[^a-z0-9\\s-]".toRegex(), "") // remove special characters
            .replace("\\s+".toRegex(), "-") // replace spaces with hyphens
            .replace("-+".toRegex(), "-") // collapse multiple hyphens
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
