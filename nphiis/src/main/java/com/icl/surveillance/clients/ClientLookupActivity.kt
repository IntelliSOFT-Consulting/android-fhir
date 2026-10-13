package com.icl.surveillance.clients

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointBackward
import com.google.android.material.datepicker.MaterialDatePicker
import com.icl.surveillance.R
import com.icl.surveillance.clients.AddClientFragment.Companion.QUESTIONNAIRE_FILE_PATH_KEY
import com.icl.surveillance.databinding.ActivityClientLookupBinding
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.fhir.forms.FormPrefill
import com.icl.surveillance.models.ClientDemographics
import com.icl.surveillance.models.ClientLookupOutcome
import com.icl.surveillance.models.LookupSource
import com.icl.surveillance.network.ClientLookupRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Client Identification: first step of adding a case in the Outbreak Response Module. The user
 * picks where to search (IPRS or the Client Registry), enters the ID number and date of birth, and
 * the case form opens with the person's details filled in. They can also continue with only what
 * they typed, or skip.
 *
 * Start it with the same extras as [AddParentCaseActivity] ("AddParentTitle" and
 * [QUESTIONNAIRE_FILE_PATH_KEY]); the form's result is forwarded to whoever started this screen.
 */
class ClientLookupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClientLookupBinding
    private var lookupJob: Job? = null

    private var selectedSource = LookupSource.IPRS

    /** Shown label to the case form's answer code. */
    private val allIdTypes = linkedMapOf(
        "National ID" to FormFields.Vhf.ID_TYPE_NATIONAL_ID_CODE,
        "Passport" to "passport",
        "Birth certificate" to "birth-certificate",
    )

    /** IPRS holds national IDs only. */
    private val idTypes: Map<String, String>
        get() = if (selectedSource == LookupSource.IPRS)
            allIdTypes.filterValues { it == FormFields.Vhf.ID_TYPE_NATIONAL_ID_CODE }
        else allIdTypes
    private var selectedIdType = FormFields.Vhf.ID_TYPE_NATIONAL_ID_CODE

    /** yyyy-MM-dd, or null until picked. */
    private var selectedDob: String? = null

    private val questionnaireFile: String
        get() = intent.getStringExtra(QUESTIONNAIRE_FILE_PATH_KEY) ?: FormFields.Vhf.CASE_FORM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClientLookupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = getString(R.string.client_lookup_title)
            setDisplayHomeAsUpEnabled(true)
        }

        savedInstanceState?.getString(STATE_SOURCE)
            ?.let { name -> LookupSource.values().firstOrNull { it.name == name } }
            ?.let { selectedSource = it }
        savedInstanceState?.getString(STATE_ID_TYPE)?.let { selectedIdType = it }
        setUpSource()

        binding.idNumber.doAfterTextChanged {
            binding.idNumberLayout.error = null
            showIdle()
        }
        binding.idNumber.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                hideKeyboard()
                if (selectedDob == null) pickDob() else search()
                true
            } else false
        }
        binding.dob.setOnClickListener { pickDob() }
        binding.dobLayout.setEndIconOnClickListener { pickDob() }
        savedInstanceState?.getString(STATE_DOB)?.let { setDob(it) }

        binding.searchButton.setOnClickListener { search() }
        binding.notThisPersonButton.setOnClickListener {
            showIdle()
            binding.idNumber.requestFocus()
            binding.idNumber.selectAll()
        }
        binding.continueWithIdButton.setOnClickListener { openForm(idOnlyPrefill()) }
        binding.skipButton.setOnClickListener { openForm(emptyMap()) }
    }

    // ---- Inputs

    private fun setUpSource() {
        binding.sourceToggle.check(
            if (selectedSource == LookupSource.IPRS) R.id.sourceIprs else R.id.sourceCr
        )
        applySource()
        binding.sourceToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectedSource = if (checkedId == R.id.sourceIprs) LookupSource.IPRS else LookupSource.CLIENT_REGISTRY
            applySource()
            showIdle()
        }
    }

    private fun applySource() {
        binding.sourceNote.setText(
            if (selectedSource == LookupSource.IPRS) R.string.client_lookup_source_note_iprs
            else R.string.client_lookup_source_note_cr
        )
        setUpIdType()
    }

    /** Fills the ID type list for the current source; hidden when there is only one choice. */
    private fun setUpIdType() {
        val types = idTypes
        val labels = types.keys.toList()
        if (selectedIdType !in types.values) selectedIdType = types.values.first()
        binding.idType.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, labels))
        binding.idType.setText(types.entries.first { it.value == selectedIdType }.key, false)
        binding.idTypeLayout.visibility = if (labels.size > 1) View.VISIBLE else View.GONE
        applyIdInputType()
        binding.idType.setOnItemClickListener { _, _, position, _ ->
            selectedIdType = types.getValue(labels[position])
            applyIdInputType()
            showIdle()
        }
    }

    private fun applyIdInputType() {
        binding.idNumber.inputType =
            if (selectedIdType == FormFields.Vhf.ID_TYPE_NATIONAL_ID_CODE) InputType.TYPE_CLASS_NUMBER
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
    }

    private fun enteredId(): String = binding.idNumber.text?.toString()?.trim().orEmpty()

    /**
     * Calendar date picker. Tap the month/year at the top to jump straight to a year. Opens on the
     * picked date, or otherwise on [DEFAULT_AGE_YEARS] years ago; future dates are blocked.
     */
    private fun pickDob() {
        val utc = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val today = MaterialDatePicker.todayInUtcMilliseconds()
        val earliest = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = today; add(Calendar.YEAR, -MAX_AGE_YEARS)
        }.timeInMillis
        val selected = selectedDob?.let { runCatching { utc.parse(it)?.time }.getOrNull() }
        val openAt = selected ?: Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = today; add(Calendar.YEAR, -DEFAULT_AGE_YEARS)
        }.timeInMillis

        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(R.string.client_lookup_dob)
            .setInputMode(MaterialDatePicker.INPUT_MODE_CALENDAR)
            .setCalendarConstraints(
                CalendarConstraints.Builder()
                    .setStart(earliest)
                    .setEnd(today)
                    .setOpenAt(openAt)
                    .setValidator(DateValidatorPointBackward.now())
                    .build()
            )
            .apply { selected?.let { setSelection(it) } }
            .build()
        picker.addOnPositiveButtonClickListener { millis -> setDob(utc.format(Date(millis))) }
        picker.show(supportFragmentManager, "client_lookup_dob")
    }

    private fun setDob(value: String) {
        selectedDob = value
        binding.dob.setText(value)
        binding.dobLayout.error = null
        showIdle()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectedDob?.let { outState.putString(STATE_DOB, it) }
        outState.putString(STATE_SOURCE, selectedSource.name)
        outState.putString(STATE_ID_TYPE, selectedIdType)
    }

    // ---- Search

    private fun search() {
        val id = enteredId()
        val dob = selectedDob
        if (id.isEmpty()) binding.idNumberLayout.error = getString(R.string.client_lookup_id_required)
        if (dob == null) binding.dobLayout.error = getString(R.string.client_lookup_dob_required)
        if (id.isEmpty() || dob == null) return
        hideKeyboard()
        lookupJob?.cancel()
        showLoading(true)
        val source = selectedSource
        lookupJob = lifecycleScope.launch {
            val outcome = ClientLookupRepository.lookup(this@ClientLookupActivity, source, id, selectedIdType)
            showLoading(false)
            when (outcome) {
                is ClientLookupOutcome.Found -> showMatch(outcome)
                ClientLookupOutcome.NotFound ->
                    showMessage(getString(R.string.client_lookup_not_found, id, source.label))
                ClientLookupOutcome.Offline -> showMessage(getString(R.string.client_lookup_offline))
                is ClientLookupOutcome.Failed -> showMessage(outcome.message)
            }
        }
    }

    // ---- States

    private fun showMatch(found: ClientLookupOutcome.Found) {
        val client = found.client
        binding.messageCard.visibility = View.GONE
        binding.resultCard.visibility = View.VISIBLE

        val name = client.fullName.ifBlank { client.identificationNumber ?: enteredId() }
        binding.resultName.text = name
        binding.resultInitials.text = initials(name)
        binding.resultSource.text = getString(R.string.client_lookup_source, found.sourceLabel)

        val idLabel = allIdTypes.entries.firstOrNull { it.value == client.identificationType }?.key ?: "ID number"
        val place = listOfNotNull(client.ward?.name, client.subCounty?.name, client.county?.name)
            .joinToString(", ") { titleCase(it) }
            .ifBlank { client.currentResidence.orEmpty() }
        val rows = listOf(
            idLabel to (client.identificationNumber ?: enteredId()),
            "Sex" to client.sex?.let { titleCase(it) },
            "Date of birth" to client.dateOfBirth,
            "Phone" to client.phone,
            "Residence" to place,
            "Village" to client.village,
            "Next of kin" to client.nextOfKin,
        )
        binding.resultRows.removeAllViews()
        val inflater = LayoutInflater.from(this)
        rows.filter { !it.second.isNullOrBlank() }.forEach { (label, value) ->
            val row = inflater.inflate(R.layout.item_label_value_modern, binding.resultRows, false)
            row.findViewById<TextView>(R.id.tv_label).text = label
            row.findViewById<TextView>(R.id.tv_value).text = value
            binding.resultRows.addView(row)
        }

        if (dobMismatch(client)) {
            binding.dobWarning.text = getString(R.string.client_lookup_dob_mismatch, selectedDob)
            binding.dobWarning.visibility = View.VISIBLE
        } else {
            binding.dobWarning.visibility = View.GONE
        }
        binding.useDetailsButton.setOnClickListener { openForm(prefillFor(client)) }
        scrollTo(binding.resultCard)
    }

    private fun showMessage(message: String) {
        binding.resultCard.visibility = View.GONE
        binding.messageCard.visibility = View.VISIBLE
        binding.messageText.text = message
        scrollTo(binding.messageCard)
    }

    private fun showIdle() {
        binding.resultCard.visibility = View.GONE
        binding.messageCard.visibility = View.GONE
    }

    private fun showLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.INVISIBLE
        binding.searchButton.isEnabled = !loading
        binding.searchButton.setText(if (loading) R.string.client_lookup_searching else R.string.client_lookup_search)
        listOf(binding.idNumber, binding.idType, binding.dob, binding.sourceIprs, binding.sourceCr)
            .forEach { it.isEnabled = !loading }
        if (loading) showIdle()
    }

    private fun scrollTo(view: View) {
        view.post { view.requestRectangleOnScreen(android.graphics.Rect(0, 0, view.width, view.height), false) }
    }

    // ---- Opening the form

    /** Case-form answers (by linkId) for what was typed: ID and date of birth. */
    private fun idOnlyPrefill(): Map<String, String> = buildMap {
        val id = enteredId()
        if (id.isNotEmpty()) {
            // Adults and minors have separate "Type of identification" questions; only the one
            // offering this code takes the answer. Both depend on the date of birth.
            put(FormFields.Vhf.ID_TYPE, selectedIdType)
            put(FormFields.Vhf.ID_TYPE_MINOR, selectedIdType)
            put(FormFields.Vhf.NATIONAL_ID, id)
        }
        selectedDob?.let {
            put(ClientDemographics.DOB_KNOWN, "known")
            put(FormFields.Person.DATE_OF_BIRTH, it)
        }
    }

    /** What was typed, overlaid with the record. The record's date of birth wins when it has one. */
    private fun prefillFor(client: ClientDemographics): Map<String, String> =
        idOnlyPrefill() + client.toPrefill()

    private fun dobMismatch(client: ClientDemographics): Boolean =
        !client.dateOfBirth.isNullOrBlank() && selectedDob != null && client.dateOfBirth != selectedDob

    /** Opens the case form with [prefill]; its result goes straight back to our caller. */
    private fun openForm(prefill: Map<String, String>) {
        if (prefill.isEmpty()) FormPrefill.clear(this) else FormPrefill.stage(this, questionnaireFile, prefill)
        val formIntent = Intent(this, AddParentCaseActivity::class.java).apply {
            putExtras(intent)
            addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT)
        }
        startActivity(formIntent)
        finish()
    }

    // ---- Helpers

    private fun initials(name: String): String =
        name.split(Regex("\\s+")).filter { it.isNotEmpty() }
            .let { parts -> listOfNotNull(parts.firstOrNull(), parts.drop(1).lastOrNull()) }
            .joinToString("") { it.take(1).uppercase() }

    private fun titleCase(value: String): String =
        value.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.idNumber.windowToken, 0)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        private const val STATE_DOB = "selectedDob"

        /** The date picker opens this many years back when no date is picked yet. */
        private const val DEFAULT_AGE_YEARS = 30
        private const val MAX_AGE_YEARS = 120
        private const val STATE_SOURCE = "lookupSource"
        private const val STATE_ID_TYPE = "idType"
    }
}
