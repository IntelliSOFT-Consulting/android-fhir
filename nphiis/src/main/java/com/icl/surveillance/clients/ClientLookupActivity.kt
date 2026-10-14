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
import com.icl.surveillance.fhir.forms.VhfContactTracker
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.models.ClientDemographics
import com.icl.surveillance.models.ClientLookupOutcome
import com.icl.surveillance.models.LookupSource
import com.icl.surveillance.network.ClientLookupRepository
import com.icl.surveillance.utils.Constants
import com.icl.surveillance.utils.InternetRequiredDialog
import com.icl.surveillance.utils.NetworkUtils
import com.icl.surveillance.utils.PhoneNumbers
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Client Identification: first step of adding a case in the Outbreak Response Module. The user
 * picks where to search (the Client Registry, or IPRS), enters the ID number and date of birth, and
 * the case form opens with the person's details filled in and locked (read-only). On the Client
 * Registry a minor is found through their parent's / guardian's ID: the guardian's registered
 * children are listed, the user picks one, and the guardian becomes the next of kin. They can also
 * enter the details manually (editable), continue with only what they typed, or skip. Without
 * internet an "Internet required" alert offers to open the Wi-Fi / mobile data settings.
 *
 * Start it with the same extras as [AddParentCaseActivity] ("AddParentTitle" and
 * [QUESTIONNAIRE_FILE_PATH_KEY]); the form's result is forwarded to whoever started this screen.
 */
class ClientLookupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClientLookupBinding
    private var lookupJob: Job? = null

    private var selectedSource = LookupSource.CLIENT_REGISTRY

    /** Shown label to the case form's answer code. */
    private val allIdTypes: Map<String, String> =
        Constants.CLIENT_LOOKUP_ID_TYPES.entries.associate { (code, label) -> label to code }

    /**
     * IPRS holds national IDs only. On the Client Registry adults (and, for minors, the parent /
     * guardian) are searched by any of the CR's adult document types.
     */
    private val idTypes: Map<String, String>
        get() = if (selectedSource == LookupSource.IPRS)
            allIdTypes.filterValues { it == FormFields.Vhf.ID_TYPE_NATIONAL_ID_CODE }
        else allIdTypes.filterValues { it !in Constants.CLIENT_LOOKUP_CHILD_ID_TYPES }
    private var selectedIdType = FormFields.Vhf.ID_TYPE_NATIONAL_ID_CODE

    /** Client Registry: searching for a minor through their parent's / guardian's ID. */
    private var selectedMinor = false
    private val isMinorSearch: Boolean
        get() = selectedSource == LookupSource.CLIENT_REGISTRY && selectedMinor

    /** Last minor search result, kept to go back to the list and to fill in the next of kin. */
    private var lastGuardian: ClientDemographics? = null
    private var lastChildren: List<ClientDemographics> = emptyList()
    private var lastSourceLabel: String? = null

    /** yyyy-MM-dd, or null until picked. */
    private var selectedDob: String? = null

    private var internetDialog: AlertDialog? = null

    /** Set when the user went to the internet settings from the alert; checked on return. */
    private var awaitingConnection = false

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
        selectedMinor = savedInstanceState?.getBoolean(STATE_MINOR) ?: false
        setUpAgeGroup()
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
            if (lastGuardian != null) {
                showChildrenList() // back to the guardian and their children
            } else {
                showIdle()
                binding.idNumber.requestFocus()
                binding.idNumber.selectAll()
            }
        }
        binding.childNotListedButton.setOnClickListener { openForm(idOnlyPrefill()) }
        binding.continueWithIdButton.setOnClickListener { openForm(idOnlyPrefill()) }
        binding.enterManuallyButton.setOnClickListener { openForm(idOnlyPrefill()) }
        binding.skipButton.setOnClickListener { openForm(emptyMap()) }

        if (savedInstanceState == null && needsInternet() && !isOnline()) showInternetRequired()
    }

    override fun onResume() {
        super.onResume()
        if (!awaitingConnection) return
        awaitingConnection = false
        if (isOnline()) {
            internetDialog?.dismiss()
            Toast.makeText(this, R.string.client_lookup_back_online, Toast.LENGTH_SHORT).show()
        } else {
            showInternetRequired()
        }
    }

    override fun onDestroy() {
        internetDialog?.dismiss()
        super.onDestroy()
    }

    // ---- Connectivity

    /** Client Registry test data works offline; everything else needs the network. */
    private fun needsInternet(): Boolean =
        !(selectedSource == LookupSource.CLIENT_REGISTRY && Constants.CLIENT_LOOKUP_USE_MOCK)

    private fun isOnline(): Boolean = NetworkUtils.isInternetAvailable(this)

    private fun showInternetRequired() {
        if (internetDialog?.isShowing == true) return
        internetDialog = InternetRequiredDialog.show(
            activity = this,
            manualLabel = getString(R.string.internet_required_manual),
            onManual = { openForm(idOnlyPrefill()) },
            onOpenSettings = { awaitingConnection = true },
        )
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
        // Adult / minor applies to the Client Registry only; IPRS holds adults' national IDs.
        binding.ageGroupSection.visibility =
            if (selectedSource == LookupSource.CLIENT_REGISTRY) View.VISIBLE else View.GONE
        setUpIdType()
        applyAgeGroup()
    }

    private fun setUpAgeGroup() {
        binding.ageToggle.check(if (selectedMinor) R.id.ageMinor else R.id.ageAdult)
        binding.ageToggle.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == View.NO_ID) return@setOnCheckedChangeListener
            selectedMinor = checkedId == R.id.ageMinor
            applyAgeGroup()
            showIdle()
        }
    }

    /** Labels follow whose ID is asked for: the client's own, or the parent's / guardian's. */
    private fun applyAgeGroup() {
        val minor = isMinorSearch
        binding.ageNote.setText(if (selectedMinor) R.string.client_lookup_minor_note else R.string.client_lookup_adult_note)
        binding.idTypeLayout.hint = getString(if (minor) R.string.client_lookup_guardian_id_type else R.string.client_lookup_id_type)
        binding.idNumberLayout.hint = getString(if (minor) R.string.client_lookup_guardian_id_number else R.string.client_lookup_id_number)
        binding.dobLayout.hint = getString(if (minor) R.string.client_lookup_child_dob else R.string.client_lookup_dob)
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
     * picked date, or otherwise on today (selected); future dates are blocked.
     */
    private fun pickDob() {
        val utc = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val today = MaterialDatePicker.todayInUtcMilliseconds()
        val earliest = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = today; add(Calendar.YEAR, -MAX_AGE_YEARS)
        }.timeInMillis
        val selected = selectedDob?.let { runCatching { utc.parse(it)?.time }.getOrNull() }
        val openAt = selected ?: today

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
            .setSelection(openAt)
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
        outState.putBoolean(STATE_MINOR, selectedMinor)
    }

    // ---- Search

    private fun search() {
        val id = enteredId()
        val dob = selectedDob
        if (id.isEmpty()) binding.idNumberLayout.error = getString(R.string.client_lookup_id_required)
        if (dob == null) binding.dobLayout.error = getString(R.string.client_lookup_dob_required)
        if (id.isEmpty() || dob == null) return
        hideKeyboard()
        if (needsInternet() && !isOnline()) {
            showInternetRequired()
            return
        }
        lookupJob?.cancel()
        showLoading(true)
        val source = selectedSource
        val minor = isMinorSearch
        lookupJob = lifecycleScope.launch {
            val outcome =
                ClientLookupRepository.lookup(this@ClientLookupActivity, source, id, selectedIdType, minor)
            showLoading(false)
            when (outcome) {
                is ClientLookupOutcome.Found -> showMatch(outcome.client, outcome.sourceLabel)
                is ClientLookupOutcome.Dependents -> showChildren(outcome)
                ClientLookupOutcome.NotFound -> showMessage(
                    getString(
                        if (minor) R.string.client_lookup_guardian_not_found else R.string.client_lookup_not_found,
                        id, source.label,
                    )
                )
                ClientLookupOutcome.Offline -> {
                    showMessage(getString(R.string.client_lookup_offline))
                    showInternetRequired()
                }
                is ClientLookupOutcome.Failed -> showMessage(outcome.message)
            }
        }
    }

    // ---- States

    /**
     * [isGuardian]: the parent / guardian picked from a minor search, filled in as themselves
     * (not with the child's date of birth or themselves as next of kin).
     */
    private fun showMatch(client: ClientDemographics, sourceLabel: String, isGuardian: Boolean = false) {
        hideCards()
        binding.resultCard.visibility = View.VISIBLE

        val name = client.fullName.ifBlank { client.identificationNumber ?: enteredId() }
        binding.resultName.text = name
        binding.resultInitials.text = initials(name)
        binding.resultSource.text = getString(R.string.client_lookup_source, sourceLabel)

        // Everything received from the registry (or the mapped details when there is no raw record).
        fillRows(binding.resultRows, detailsOf(client))

        if (!isGuardian && dobMismatch(client)) {
            binding.dobWarning.text = getString(R.string.client_lookup_dob_mismatch, selectedDob)
            binding.dobWarning.visibility = View.VISIBLE
        } else {
            binding.dobWarning.visibility = View.GONE
        }
        // Verified details are locked on the form; "Enter details manually instead" keeps it editable.
        binding.useDetailsButton.setOnClickListener {
            val prefill = if (isGuardian) client.toPrefill() else prefillFor(client)
            openForm(prefill, locked = client.toPrefill().keys)
        }
        scrollTo(binding.resultCard)
    }

    /** Minor search result: the guardian's children to pick from (or a note that there are none). */
    private fun showChildren(result: ClientLookupOutcome.Dependents) {
        lastGuardian = result.guardian
        // Minor search: the child born on the date entered first.
        lastChildren = if (isMinorSearch) result.children.sortedByDescending { it.dateOfBirth == selectedDob }
        else result.children
        lastSourceLabel = ClientLookupOutcome.Found(result.guardian, result.source).sourceLabel
        // The parent / guardian is always offered, so the list is shown even without children.
        showChildrenList()
    }

    /**
     * Minor search result: the principal member and each dependant, with every field received, so
     * the healthcare worker can compare them and select the one this case is for.
     */
    private fun showChildrenList() {
        val guardian = lastGuardian ?: return
        hideCards()
        binding.childrenCard.visibility = View.VISIBLE
        binding.childrenSource.text = getString(R.string.client_lookup_source, lastSourceLabel)
        binding.childrenTitle.text =
            getString(R.string.client_lookup_children_title, guardian.fullName.ifBlank { enteredId() })
        binding.childrenList.removeAllViews()

        // Adult search: the date entered is the principal's own. Minor search: it is the child's.
        addPersonCard(
            guardian,
            role = getString(R.string.client_lookup_guardian_badge),
            dobMatch = !isMinorSearch && guardian.dateOfBirth != null && guardian.dateOfBirth == selectedDob,
        ) { showMatch(guardian, lastSourceLabel ?: "", isGuardian = isMinorSearch) }
        if (lastChildren.isEmpty()) {
            binding.childrenList.addView(TextView(this).apply {
                setText(R.string.client_lookup_no_children_short)
                setTextColor(getColor(R.color.lookup_text_secondary))
                textSize = 13f
                setPadding(0, 24, 0, 8)
            })
        }
        lastChildren.forEach { child ->
            addPersonCard(
                child,
                role = child.relationship ?: getString(R.string.client_lookup_dependant_badge),
                dobMatch = isMinorSearch && child.dateOfBirth != null && child.dateOfBirth == selectedDob,
            ) { showMatch(withGuardian(child, guardian), lastSourceLabel ?: "") }
        }
        scrollTo(binding.childrenCard)
    }

    private fun addPersonCard(
        person: ClientDemographics,
        role: String,
        dobMatch: Boolean = false,
        onSelect: () -> Unit,
    ) {
        val card = LayoutInflater.from(this).inflate(R.layout.item_lookup_person, binding.childrenList, false)
        val name = person.fullName.ifBlank { person.identificationNumber ?: "Unnamed" }
        card.findViewById<TextView>(R.id.personInitials).text = initials(name)
        card.findViewById<TextView>(R.id.personName).text = name
        card.findViewById<TextView>(R.id.personRole).text = role
        card.findViewById<View>(R.id.personDobMatch).visibility = if (dobMatch) View.VISIBLE else View.GONE
        card.findViewById<TextView>(R.id.personGender).apply {
            val gender = person.sex?.takeIf { it.isNotBlank() }?.let { titleCase(it) }
            text = gender ?: getString(R.string.client_lookup_gender_unknown)
        }
        fillRows(card.findViewById(R.id.personRows), detailsOf(person))

        // Name and gender only; the header (or "Show details") expands everything received.
        val details = card.findViewById<View>(R.id.personDetails)
        val chevron = card.findViewById<View>(R.id.personExpand)
        val toggleText = card.findViewById<TextView>(R.id.personToggleText)
        val toggle = View.OnClickListener {
            val open = details.visibility != View.VISIBLE
            details.visibility = if (open) View.VISIBLE else View.GONE
            chevron.animate().rotation(if (open) 90f else 0f).setDuration(150).start()
            toggleText.setText(if (open) R.string.client_lookup_hide_details else R.string.client_lookup_show_details)
        }
        card.findViewById<View>(R.id.personHeader).setOnClickListener(toggle)
        toggleText.setOnClickListener(toggle)

        card.findViewById<TextView>(R.id.personSelect).apply {
            text = getString(R.string.client_lookup_select_person, person.givenNames?.substringBefore(" ") ?: name)
            setOnClickListener { onSelect() }
        }
        binding.childrenList.addView(card)
    }

    /** Every field received for [person]; falls back to the mapped details when there are none. */
    private fun detailsOf(person: ClientDemographics): List<Pair<String, String?>> =
        person.recordDetails.ifEmpty {
            val place = listOfNotNull(person.ward?.name, person.subCounty?.name, person.county?.name)
                .joinToString(", ") { titleCase(it) }
                .ifBlank { person.currentResidence.orEmpty() }
            listOf(
                (allIdTypes.entries.firstOrNull { it.value == person.identificationType }?.key ?: "ID number")
                    to person.identificationNumber,
                "Sex" to person.sex?.let { titleCase(it) },
                "Date of birth" to person.dateOfBirth,
                "Phone" to person.phone,
                "Residence" to place,
                "Village" to person.village,
                "Next of kin" to person.nextOfKin,
            )
        }

    private fun fillRows(container: android.widget.LinearLayout, rows: List<Pair<String, String?>>) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        rows.filter { !it.second.isNullOrBlank() }.forEach { (label, value) ->
            val row = inflater.inflate(R.layout.item_label_value_modern, container, false)
            row.findViewById<TextView>(R.id.tv_label).text = label
            row.findViewById<TextView>(R.id.tv_value).text = value
            container.addView(row)
        }
    }

    /**
     * The child's record, with the guardian as next of kin and the guardian's residence where the
     * child's own is missing.
     */
    private fun withGuardian(child: ClientDemographics, guardian: ClientDemographics): ClientDemographics =
        child.copy(
            nextOfKin = child.nextOfKin?.takeIf { it.isNotBlank() } ?: guardian.fullName.ifBlank { null },
            nextOfKinPhone = child.nextOfKinPhone?.takeIf { it.isNotBlank() } ?: guardian.phone,
            country = child.country ?: guardian.country,
            county = child.county ?: guardian.county,
            subCounty = child.subCounty ?: guardian.subCounty,
            ward = child.ward ?: guardian.ward,
            village = child.village ?: guardian.village,
            currentResidence = child.currentResidence ?: guardian.currentResidence,
            residenceType = child.residenceType ?: guardian.residenceType,
        )

    private fun showMessage(message: String) {
        hideCards()
        binding.messageCard.visibility = View.VISIBLE
        binding.messageText.text = message
        binding.continueWithIdButton.setText(
            if (isMinorSearch) R.string.client_lookup_continue_manually
            else R.string.client_lookup_continue_with_entered
        )
        scrollTo(binding.messageCard)
    }

    private fun hideCards() {
        binding.resultCard.visibility = View.GONE
        binding.messageCard.visibility = View.GONE
        binding.childrenCard.visibility = View.GONE
    }

    /** Back to the search form; a previous result no longer applies. */
    private fun showIdle() {
        hideCards()
        lastGuardian = null
        lastChildren = emptyList()
        lastSourceLabel = null
    }

    private fun showLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.INVISIBLE
        binding.searchButton.isEnabled = !loading
        binding.searchButton.setText(if (loading) R.string.client_lookup_searching else R.string.client_lookup_search)
        listOf(
            binding.idNumber, binding.idType, binding.dob, binding.sourceIprs, binding.sourceCr,
            binding.ageAdult, binding.ageMinor,
        )
            .forEach { it.isEnabled = !loading }
        if (loading) showIdle()
    }

    private fun scrollTo(view: View) {
        view.post { view.requestRectangleOnScreen(android.graphics.Rect(0, 0, view.width, view.height), false) }
    }

    // ---- Opening the form

    /**
     * Case-form answers (by linkId) for what was typed: ID and date of birth. For a minor the ID
     * typed is the guardian's, so it is not the child's ID; the guardian (when found) becomes the
     * next of kin instead.
     */
    private fun idOnlyPrefill(): Map<String, String> = buildMap {
        val id = enteredId()
        if (isMinorSearch) {
            lastGuardian?.let { guardian ->
                guardian.fullName.takeIf { it.isNotBlank() }?.let { put(FormFields.Vhf.NEXT_OF_KIN, it) }
                PhoneNumbers.toLocal(guardian.phone)?.let { put(FormFields.Vhf.NEXT_OF_KIN_PHONE, it) }
            }
        } else if (id.isNotEmpty()) {
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

    /**
     * Opens the case form with [prefill]; [locked] linkIds are read-only there. The form's result
     * goes straight back to our caller.
     */
    private fun openForm(prefill: Map<String, String>, locked: Set<String> = emptySet()) {
        internetDialog?.dismiss()
        // Registering a contact of a case: keep the contact details its Contacts tab staged
        // (Type of case, exposure, disease, source EPID) and add what was found here.
        val contact = if (isContactRegistration()) FormPrefill.peek(this, questionnaireFile) else null
        val values = contact?.values.orEmpty() + prefill
        val lockedIds = contact?.locked.orEmpty() + locked
        if (values.isEmpty()) FormPrefill.clear(this)
        else FormPrefill.stage(this, questionnaireFile, values, lockedIds)
        val formIntent = Intent(this, AddParentCaseActivity::class.java).apply {
            putExtras(intent)
            addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT)
        }
        startActivity(formIntent)
        finish()
    }

    // ---- Helpers

    private fun isContactRegistration(): Boolean =
        !FormatterClass().getSharedPref(VhfContactTracker.PREF_SOURCE_PATIENT, this).isNullOrBlank()

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

        private const val MAX_AGE_YEARS = 120
        private const val STATE_SOURCE = "lookupSource"
        private const val STATE_ID_TYPE = "idType"
        private const val STATE_MINOR = "minor"
    }
}
