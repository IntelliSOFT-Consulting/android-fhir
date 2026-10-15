package com.icl.surveillance.clients

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.icl.surveillance.R
import com.icl.surveillance.clients.AddClientFragment.Companion.QUESTIONNAIRE_FILE_PATH_KEY
import com.icl.surveillance.databinding.ActivityScreenedCaseDetailBinding
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.fhir.forms.CaseTypes
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.fhir.forms.FormPrefill
import com.icl.surveillance.fhir.forms.ResidenceLocation
import com.icl.surveillance.fhir.forms.VhfScreened
import com.icl.surveillance.fhir.forms.VhfTraining
import com.icl.surveillance.utils.FormatterClass
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * A screened person's demographics, travel history and clinical information, with
 * "Start Case Investigation": the VHF case form opens pre-filled with the screening answers.
 */
class ScreenedCaseDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScreenedCaseDetailBinding
    private var details: VhfScreened.Details? = null

    private val patientId: String
        get() = intent.getStringExtra(EXTRA_PATIENT_ID).orEmpty()

    private val formLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(EXTRA_MOVED_NAME, details?.row?.name)
                )
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityScreenedCaseDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = getString(R.string.screened_title)
            setDisplayHomeAsUpEnabled(true)
        }
        binding.investigateButton.setOnClickListener { startInvestigation() }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val loaded = runCatching {
                VhfScreened.details(
                    this@ScreenedCaseDetailActivity,
                    FhirApplication.fhirEngine(this@ScreenedCaseDetailActivity),
                    patientId,
                )
            }.onFailure { Timber.tag("VHF_SCREENED").e(it, "Could not load %s", patientId) }
                .getOrNull()
            binding.progress.visibility = View.GONE
            if (loaded == null) {
                Toast.makeText(this@ScreenedCaseDetailActivity, R.string.screened_not_found, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            details = loaded
            render(loaded)
        }
    }

    private fun render(details: VhfScreened.Details) {
        binding.name.text = details.row.name
        binding.subtitle.text = listOf(details.row.phone, details.row.idNumber.let { if (it.isBlank()) "" else "ID $it" })
            .filter { it.isNotBlank() }
            .joinToString("  ·  ")
        binding.sections.removeAllViews()
        val shown = VhfScreened.SCREENING_SECTIONS.values.map { title ->
            details.sections.firstOrNull { it.title == title } ?: VhfScreened.Section(title, emptyList())
        }
        shown.map { sectionCard(it) }.forEach { binding.sections.addView(it) }
        binding.investigateButton.isEnabled = true
    }

    private fun sectionCard(section: VhfScreened.Section): View {
        val padding = dp(16)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            text = section.title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(context, R.color.lookup_accent))
        })
        if (section.fields.isEmpty()) {
            content.addView(secondary(getString(R.string.screened_no_answers)).apply { setPadding(0, dp(8), 0, 0) })
        }
        section.fields.map { field ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, 0)
                addView(secondary(field.label))
                addView(TextView(context).apply {
                    text = field.value
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(ContextCompat.getColor(context, R.color.lookup_text_primary))
                })
            }
        }.forEach { content.addView(it) }

        return MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            radius = dp(12).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = ContextCompat.getColor(context, R.color.lookup_stroke)
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.lookup_card))
            addView(content)
        }
    }

    private fun secondary(value: String) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(ContextCompat.getColor(context, R.color.lookup_text_secondary))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /**
     * Starts the case investigation: first the map, to pick where the person lives, then the VHF
     * case form pre-filled with the screening answers (the picker opens it and forwards its result).
     */
    private fun startInvestigation() {
        val loaded = details ?: return
        val form = FormFields.Vhf.CASE_FORM
        val formatter = FormatterClass()
        VhfTraining.setOn(this, false)
        formatter.saveSharedPref("currentCase", CaseTypes.VHF, this)
        formatter.saveSharedPref("AddParentTitle", getString(R.string.vhf_form), this)
        formatter.saveSharedPref("questionnaire", form, this)
        // AddClientViewModel continues this person's Patient instead of creating a new one.
        formatter.saveSharedPref(VhfScreened.PREF_SOURCE, patientId, this)
        // Screening answers are a starting point only: everything stays editable.
        FormPrefill.stage(this, form, loaded.prefill)
        Timber.tag("SCREENED_SAVE").d(
            "Investigating screened %s with %d pre-filled answers", patientId, loaded.prefill.size
        )
        ResidenceLocation.clear(this)
        formLauncher.launch(
            Intent(this, ResidencePickerActivity::class.java)
                .putExtra(ResidencePickerActivity.EXTRA_OPEN_FORM, true)
                .putExtra("AddParentTitle", " ${getString(R.string.vhf_form)}")
                .putExtra(QUESTIONNAIRE_FILE_PATH_KEY, form)
        )
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_PATIENT_ID = "screenedPatientId"
        const val EXTRA_MOVED_NAME = "screenedMovedName"
        const val PREF_SCREENED_SOURCE = VhfScreened.PREF_SOURCE
    }
}
