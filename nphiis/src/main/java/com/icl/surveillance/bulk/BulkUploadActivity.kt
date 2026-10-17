package com.icl.surveillance.bulk

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.lifecycleScope
import com.icl.surveillance.R
import com.icl.surveillance.clients.AddClientFragment.Companion.QUESTIONNAIRE_FILE_PATH_KEY
import com.icl.surveillance.databinding.ActivityBulkUploadBinding
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.fhir.forms.CaseTypes
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.fhir.forms.VhfContactTracker
import com.icl.surveillance.fhir.forms.VhfScreened
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.viewmodels.AddClientViewModel
import com.icl.surveillance.viewmodels.AddClientViewModel.PersistResult
import androidx.core.widget.doAfterTextChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Settings > Bulk Upload: VHF contacts from an Excel (.xlsx) or CSV file laid out like the case
 * form. Pick the source case (or use the file's "Contact Link to Case" column), choose the file,
 * check the preview, then import; each row is saved like a contact registered by hand.
 */
class BulkUploadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBulkUploadBinding
    private val fhirEngine by lazy { FhirApplication.fhirEngine(this) }
    private val importer by lazy { BulkContactImport(applicationContext, fhirEngine) }

    private var sources: List<BulkContactImport.SourceCase> = emptyList()
    /** Null = each row's own "Contact Link to Case" column. */
    private var selectedSourceId: String? = null
    private var table: SpreadsheetReader.Table? = null
    private var site: BulkContactImport.ReportingSite? = null
    private var facilityResults: List<BulkContactImport.ReportingSite> = emptyList()
    private var facilitySearch: Job? = null
    private var fileName: String? = null
    private var plan: BulkContactImport.Plan? = null
    private var importing = false

    /** The list shows the last import's results until another file or source case is chosen. */
    private var importDone = false

    /** The log of the last import (one CSV line per row, written as it goes). */
    private var logFile: File? = null

    private val saveLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val file = logFile ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                }.isSuccess
            }
            Toast.makeText(this@BulkUploadActivity, if (ok) "Log saved" else "Could not save the log", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { readFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityBulkUploadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "Bulk Upload"
            setDisplayHomeAsUpEnabled(true)
        }
        binding.chooseFile.setOnClickListener { pickFile.launch(MIME_TYPES) }
        binding.importButton.setOnClickListener { confirmImport() }
        binding.saveLog.setOnClickListener { logFile?.let { saveLog.launch(it.name) } }
        binding.includeIncomplete.setOnCheckedChangeListener { _, _ -> if (!importing && !importDone) showPreview() }
        loadSources()
        setUpFacility()
    }

    /** Reporting facility: the user's own (facility level), else searched by name in their area. */
    private fun setUpFacility() {
        lifecycleScope.launch {
            // As for synthetic data: the site comes from the user's assigned area.
            val default = withContext(Dispatchers.IO) { runCatching { importer.defaultSite() }.getOrNull() }
            if (default != null) {
                site = default
                binding.facility.setText(default.label, false)
                // A facility-level user reports from their own facility only.
                binding.facilityLayout.isEnabled = !importer.isFacilityUser
                replan()
            }
        }
        binding.facility.doAfterTextChanged { editable ->
            val text = editable?.toString().orEmpty()
            // Picking a result puts its label in the field: that is a choice, not a new search.
            if (text == site?.label || facilityResults.any { it.label == text }) return@doAfterTextChanged
            if (site != null) { site = null; replan() }
            facilitySearch?.cancel()
            if (text.trim().length < 3) return@doAfterTextChanged
            facilitySearch = lifecycleScope.launch {
                delay(300)
                val result = withContext(Dispatchers.IO) { runCatching { importer.searchFacilities(text) } }
                result.exceptionOrNull()?.let {
                    Timber.tag(TAG).e(it, "Facility search failed")
                    binding.facilityLayout.error = "Search failed: ${it.message ?: it.javaClass.simpleName}"
                    return@launch
                }
                val found = result.getOrDefault(emptyList())
                facilityResults = found
                binding.facility.setAdapter(ResultsAdapter(found.map { it.label }))
                if (found.isNotEmpty() && binding.facility.hasFocus()) binding.facility.showDropDown()
                binding.facilityLayout.error = if (found.isEmpty()) "No health facility in your area matches \"$text\"" else null
            }
        }
        binding.facility.setOnItemClickListener { _, _, position, _ ->
            facilitySearch?.cancel()
            site = facilityResults.getOrNull(position)
            binding.facilityLayout.error = null
            Timber.tag(TAG).d("Reporting facility chosen: %s", site?.label)
            replan()
        }
    }

    private fun loadSources() {
        lifecycleScope.launch {
            sources = withContext(Dispatchers.IO) {
                runCatching { importer.sourceCases() }
                    .onFailure { Timber.tag(TAG).e(it, "Could not list the VHF cases") }
                    .getOrDefault(emptyList())
            }
            Timber.tag(TAG).d("Source cases on this device: %d", sources.size)
            if (sources.isEmpty()) binding.sourceLayout.helperText = "No VHF cases on this device yet - only the file's column can be used."

            val labels = listOf(FROM_FILE) + sources.map { it.label }
            binding.source.setAdapter(ArrayAdapter(this@BulkUploadActivity, android.R.layout.simple_list_item_1, labels))
            binding.source.setText(FROM_FILE, false)
            binding.source.setOnItemClickListener { _, _, position, _ ->
                selectedSourceId = if (position == 0) null else sources[position - 1].patientId
                Timber.tag(TAG).d("Source case chosen: %s", if (position == 0) "file column" else sources[position - 1].label)
                replan()
            }
        }
    }

    private fun readFile(uri: Uri) {
        fileName = displayName(uri)
        binding.status.text = ""
        // The mode is switched while hidden (Material does not allow it while shown).
        binding.progress.visibility = View.GONE
        binding.progress.isIndeterminate = true
        binding.progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)!!.use { SpreadsheetReader.read(it, fileName) }
                }
            }
            binding.progress.visibility = View.GONE
            result.onSuccess { read ->
                table = read
                replan()
            }.onFailure {
                Timber.tag(TAG).e(it, "Could not read %s", fileName)
                table = null
                plan = null
                showMessage("Could not read ${fileName ?: "the file"}: ${it.message ?: "unknown error"}")
            }
        }
    }

    private fun replan() {
        val read = table ?: return
        importDone = false
        binding.progress.visibility = View.GONE
        binding.progress.isIndeterminate = true
        binding.progress.visibility = View.VISIBLE
        binding.importButton.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { importer.plan(read, selectedSourceId, site) } }
            binding.progress.visibility = View.GONE
            result.onSuccess { plan = it; showPreview() }
                .onFailure {
                    Timber.tag(TAG).e(it, "Could not prepare the import")
                    showMessage("Could not read the rows: ${it.message ?: "unknown error"}")
                }
        }
    }

    private fun rowsToImport(p: BulkContactImport.Plan) =
        p.rows.filter { it.importable && (binding.includeIncomplete.isChecked || it.missingRequired.isEmpty()) }

    private fun showPreview() {
        val p = plan ?: return
        val toImport = rowsToImport(p)
        val incomplete = p.rows.count { it.importable && it.missingRequired.isNotEmpty() }
        val duplicates = p.rows.count { it.duplicateOf != null }
        val errors = p.rows.count { it.errors.isNotEmpty() }
        val text = buildString {
            append(fileName ?: "File").append(" · ").append(p.rows.size).append(" rows\n")
            append("Reporting facility: ").append(site?.label ?: "not chosen – pick one above").append("\n")
            append("Columns used: ").append(p.columns.size)
            if (p.unmatchedHeaders.isNotEmpty()) {
                append("\nColumns not matched to a question (ignored): ")
                append(p.unmatchedHeaders.joinToString(", "))
            }
            append("\n\nTo import: ").append(toImport.size)
            if (incomplete > 0) append("\nWith unanswered required questions: ").append(incomplete)
            if (duplicates > 0) append("\nAlready registered (skipped): ").append(duplicates)
            if (errors > 0) append("\nWith errors (skipped): ").append(errors)

        }
        binding.preview.text = text
        binding.previewCard.visibility = View.VISIBLE
        showEntries(p, toImport.toSet())
        binding.importButton.isEnabled = toImport.isNotEmpty() && !importing
        binding.importButton.text = if (toImport.isEmpty()) "Nothing to import" else "Import ${toImport.size} contacts"
    }

    private fun confirmImport() {
        val p = plan ?: return
        val rows = rowsToImport(p)
        if (rows.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Import contacts")
            .setMessage("Add ${rows.size} contacts? Each is saved like a contact registered by hand and syncs on the next sync.")
            .setPositiveButton("Import") { _, _ -> runImport(rows) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runImport(rows: List<BulkContactImport.RowPlan>) {
        importing = true
        binding.importButton.isEnabled = false
        binding.chooseFile.isEnabled = false
        binding.progress.visibility = View.GONE
        binding.progress.isIndeterminate = false
        binding.progress.max = rows.size
        binding.progress.progress = 0
        binding.progress.visibility = View.VISIBLE
        // A private instance: only its suspend save is used, as by the test-data generator.
        val saver = AddClientViewModel(
            application, SavedStateHandle(mapOf(QUESTIONNAIRE_FILE_PATH_KEY to FormFields.Vhf.CASE_FORM))
        )
        val formatter = FormatterClass()
        val log = ImportLog.start(this, fileName)
        logFile = log.file
        binding.saveLog.visibility = View.GONE
        lifecycleScope.launch {
            // Rows not imported are logged first, with the reason.
            withContext(Dispatchers.IO) {
                plan?.rows.orEmpty().filter { it !in rows }.forEach { row ->
                    log.row(row, ImportLog.SKIPPED, skipReason(row))
                }
            }
            plan?.rows.orEmpty().filter { it !in rows }.forEach { row ->
                setEntry(row, "Skipped: ${skipReason(row)}", Tone.SKIPPED)
            }
            rows.forEach { setEntry(it, "Waiting", Tone.WAITING) }

            val failed = ArrayList<String>()
            var saved = 0
            var notLinked = 0
            rows.forEachIndexed { index, row ->
                binding.status.text = "Importing ${index + 1} of ${rows.size}: ${row.name}"
                setEntry(row, "Saving…", Tone.WAITING)
                val previous = saver.lastSavedCase
                val result = withContext(Dispatchers.IO) {
                    // The link comes from the row (its source case); never from a screen left open.
                    formatter.deleteSharedPref(VhfScreened.PREF_SOURCE, applicationContext)
                    row.sourcePatientId?.let {
                        formatter.saveSharedPref(VhfContactTracker.PREF_SOURCE_PATIENT, it, applicationContext)
                    } ?: formatter.deleteSharedPref(VhfContactTracker.PREF_SOURCE_PATIENT, applicationContext)
                    runCatching {
                        saver.persistPatientData(
                            row.response!!, applicationContext,
                            caseOverride = CaseTypes.VHF,
                            requireValid = false,
                        )
                    }.getOrElse {
                        Timber.tag(TAG).e(it, "Row %d could not be saved", row.rowNumber)
                        PersistResult.FAILED
                    }
                }
                val patientId = saver.lastSavedCase?.takeIf { result == PersistResult.SAVED && it != previous }?.first

                if (result != PersistResult.SAVED || patientId == null) {
                    val why = saver.lastSaveError ?: if (result == PersistResult.SAVED) "saved record id unknown" else "$result"
                    failed += "Row ${row.rowNumber} – ${row.name}: $why"
                    setEntry(row, "Failed: $why", Tone.FAILED)
                    withContext(Dispatchers.IO) { log.row(row, ImportLog.FAILED, "Could not be saved: $why") }
                } else {
                    // Check (and if needed repair) the link to the source case.
                    val link = withContext(Dispatchers.IO) {
                        row.sourcePatientId?.let { sourceId ->
                            runCatching { importer.ensureLinked(patientId, sourceId) }
                                .onFailure { Timber.tag(TAG).e(it, "Row %d: link check failed", row.rowNumber) }
                                .getOrNull()
                        }
                    }
                    saved++
                    val linkOk = link == BulkContactImport.LinkCheck.LINKED || link == BulkContactImport.LinkCheck.LINK_ADDED
                    if (!linkOk) notLinked++
                    val detail = listOfNotNull(
                        link?.label ?: "link not checked",
                        row.missingRequired.takeIf { it.isNotEmpty() }?.let { "unanswered: ${it.size} questions" },
                    ).joinToString(" · ")
                    setEntry(row, "Imported · $detail", if (linkOk) Tone.DONE else Tone.FAILED)
                    withContext(Dispatchers.IO) {
                        log.row(
                            row, ImportLog.IMPORTED,
                            listOfNotNull(
                                link?.label ?: "link not checked",
                                row.missingRequired.takeIf { it.isNotEmpty() }?.let { "Unanswered: " + it.joinToString(", ") },
                            ).joinToString("; "),
                            patientId = patientId,
                        )
                    }
                }
                binding.progress.progress = index + 1
            }
            formatter.deleteSharedPref(VhfContactTracker.PREF_SOURCE_PATIENT, applicationContext)
            Timber.tag(TAG).i(
                "Import finished: %d of %d saved, %d failed, %d not linked",
                saved, rows.size, failed.size, notLinked,
            )
            binding.saveLog.visibility = View.VISIBLE
            importDone = true
            importing = false
            binding.progress.visibility = View.GONE
            binding.chooseFile.isEnabled = true
            binding.importButton.isEnabled = false
            binding.importButton.text = "Imported – choose a file to import more"
            binding.status.text = buildString {
                append("Imported $saved of ${rows.size} contacts")
                append(if (notLinked == 0) ", all linked to their case." else ". $notLinked NOT linked to their case – see the list below.")
                if (failed.isNotEmpty()) append("\n${failed.size} could not be saved – see the list below.")
                append("\nThey upload on the next sync. Save the log for a record of each row.")
            }
        }
    }

    /** Shows the search results as they are (the default adapter re-filters by word start). */
    private inner class ResultsAdapter(private val items: List<String>) :
        ArrayAdapter<String>(this@BulkUploadActivity, android.R.layout.simple_list_item_1, items) {
        override fun getFilter(): android.widget.Filter = object : android.widget.Filter() {
            override fun performFiltering(constraint: CharSequence?) =
                FilterResults().apply { values = items; count = items.size }

            override fun publishResults(constraint: CharSequence?, results: FilterResults?) = notifyDataSetChanged()
        }
    }

    private enum class Tone(val color: Int) {
        READY(R.color.home_card_title),
        WAITING(R.color.home_card_subtitle),
        DONE(R.color.selection_sheet_secondary_icon_tint),
        SKIPPED(R.color.snackbar_warning),
        FAILED(R.color.account_danger_action),
    }

    private val entryViews = HashMap<Int, TextView>()

    /** One line per row: "Row 4 · Name" and its status. */
    private fun showEntries(p: BulkContactImport.Plan, toImport: Set<BulkContactImport.RowPlan>) {
        binding.entries.removeAllViews()
        entryViews.clear()
        val density = resources.displayMetrics.density
        p.rows.forEach { row ->
            val view = TextView(this).apply {
                textSize = 13f
                setPadding(0, (6 * density).toInt(), 0, (6 * density).toInt())
            }
            binding.entries.addView(view)
            entryViews[row.rowNumber] = view
            val notes = buildList {
                if (row.residence.isNotEmpty()) add("residence: ${row.residence}")
                addAll(row.warnings)
                if (row.missingRequired.isNotEmpty()) add("unanswered: " + row.missingRequired.take(3).joinToString(", ") +
                    if (row.missingRequired.size > 3) " +${row.missingRequired.size - 3} more" else "")
            }.joinToString("; ")
            when {
                row in toImport -> setEntry(row, "Ready" + if (notes.isEmpty()) "" else " · $notes", Tone.READY)
                else -> setEntry(row, "Will be skipped: ${skipReason(row)}", Tone.SKIPPED)
            }
        }
    }

    private fun setEntry(row: BulkContactImport.RowPlan, status: String, tone: Tone) {
        val view = entryViews[row.rowNumber] ?: return
        view.text = "Row ${row.rowNumber} · ${row.name}\n$status"
        view.setTextColor(ContextCompat.getColor(this, tone.color))
    }

    private fun skipReason(row: BulkContactImport.RowPlan): String = when {
        row.errors.isNotEmpty() -> row.errors.joinToString("; ")
        row.duplicateOf != null -> "Same person as ${row.duplicateOf}"
        row.missingRequired.isNotEmpty() -> "Unanswered required questions: " + row.missingRequired.joinToString(", ")
        else -> "Not selected"
    }

    private fun showMessage(text: String) {
        binding.preview.text = text
        binding.previewCard.visibility = View.VISIBLE
        binding.importButton.isEnabled = false
    }

    private fun displayName(uri: Uri): String? =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            if (!importing) finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (importing) return // finish the import first
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    companion object {
        private const val TAG = "BULK_UPLOAD"
        private const val FROM_FILE = "Use the file's \"Contact Link to Case\" column"
        private val MIME_TYPES = arrayOf(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-excel",
            "text/csv",
            "text/comma-separated-values",
            "application/octet-stream",
        )
    }
}
