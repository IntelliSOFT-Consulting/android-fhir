package com.icl.surveillance.cases

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import cn.pedant.SweetAlert.SweetAlertDialog
import com.google.android.fhir.FhirEngine
import com.icl.surveillance.R
import com.icl.surveillance.adapters.MpoxPatientAdapter
import com.icl.surveillance.adapters.PatientItemRecyclerViewAdapter
import com.icl.surveillance.adapters.PatientItemRecyclerViewAdapterRumor
import com.icl.surveillance.databinding.ActivityCaseListingBinding
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.fhir.MpoxSyncWorker
import com.icl.surveillance.models.UserRole
import com.icl.surveillance.ui.patients.FullCaseDetailsActivity
import com.icl.surveillance.ui.patients.PatientListViewModel
import com.icl.surveillance.ui.patients.SummarizedActivity
import com.icl.surveillance.ui.patients.responses.ResponseQuestionnaireActivity
import com.icl.surveillance.utils.FormatterClass
import kotlinx.coroutines.launch

class CaseListingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaseListingBinding
    private lateinit var fhirEngine: FhirEngine
    private val items = mutableListOf<PatientListViewModel.PatientItem>()
    private lateinit var patientListViewModel: PatientListViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCaseListingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        val titleName = FormatterClass().getSharedPref("listingTitle", this)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.apply {
            title = " $titleName"
        }


        fhirEngine = FhirApplication.fhirEngine(this)
        patientListViewModel =
            ViewModelProvider(
                this,
                PatientListViewModel.PatientListViewModelFactory(
                    this.application, fhirEngine
                ),
            ).get(PatientListViewModel::class.java)
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
        val userRole = UserRole.fromAny(storedRole ?: "")

        if (currentCase != null) {
            val slug = currentCase.toSlug()
            when (slug) {
                "social-listening-and-rumor-tracking-tool" -> {
                    patientListViewModel.handleCurrentRumorCaseListing(slug,units,userRole)
                    recyclerView.adapter = adapterRumor
                    patientListViewModel.liveRumorCases.observe(this) {
                        binding.apply {
                            count.text = "Showing ${it.size} Results"
                            patientListContainer.pbProgress.visibility = View.GONE
                        }

                        if (it.isEmpty()) {
                            binding.apply {
                                patientListContainer.caseCount.visibility = View.VISIBLE
                            }
                        } else {
                            binding.apply { patientListContainer.caseCount.visibility = View.GONE }
                        }

                        adapterRumor.submitList(it)
                    }
                }

                "mpox-register" -> {
                    val adapterRegister = MpoxPatientAdapter(
                        items,
                        this::onPatientItemClicked,
                        "$titleName",
                        this@CaseListingActivity
                    )
                    recyclerView.adapter = adapterRegister
                    recyclerView.layoutManager = LinearLayoutManager(this@CaseListingActivity)
                    patientListViewModel.loadMpoxPatientList(slug, units,userRole)


                    lifecycleScope.launch {
                        patientListViewModel.patients.collect { newList ->
                            adapterRegister.addPatients(newList)
                            if (newList.isNotEmpty()) {
                                binding.apply {
                                    count.text = "Showing ${newList.size} Results"
                                    patientListContainer.pbProgress.visibility = View.GONE
                                }
                            }
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
                    patientListViewModel.handleCurrentCaseListing(slug,units,userRole)
                    recyclerView.adapter = adapter
                    patientListViewModel.liveSearchedCases.observe(this) {
                        binding.apply {
                            patientListContainer.pbProgress.visibility = View.GONE
                        }

                        if (it.isEmpty()) {
                            binding.apply {
                                patientListContainer.caseCount.visibility = View.VISIBLE
                            }
                        } else {
                            binding.apply { patientListContainer.caseCount.visibility = View.GONE }
                        }
                        adapter.setData(it)

                        binding.apply {
                            tvEpidNo.addTextChangedListener { text ->
                                adapter.filter(text.toString())
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            loadData()
        } catch (e: Exception) {
            e.printStackTrace()
        }
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_refresh -> {
                SweetAlertDialog(this, SweetAlertDialog.WARNING_TYPE)
                    .setTitleText("Are you sure?")
                    .setContentText("Are you sure you wish to upload your  data?")
                    .setConfirmText("Yes,Upload!")
                    .setConfirmClickListener { sDialog ->
                        lifecycleScope.launch {
                            //  patientListViewModel.prepareUploadData("mpox-register")
                            val workRequest = OneTimeWorkRequestBuilder<MpoxSyncWorker>().build()
                            WorkManager.getInstance(this@CaseListingActivity).enqueue(workRequest)
                        }
                        Toast.makeText(
                            this@CaseListingActivity,
                            "Uploading data.....",
                            Toast.LENGTH_SHORT
                        )
                            .show()
                        sDialog.dismissWithAnimation()
                    }
                    .show()


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

