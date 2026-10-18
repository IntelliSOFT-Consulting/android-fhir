package com.icl.surveillance.bulk

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.icl.surveillance.databinding.ActivityContactMigrationBinding
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.fhir.forms.ContactGroups
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Settings > Migrate contacts: counts VHF contacts still linked to their case with Patient.link,
 * moves them into the case's contact Group (see [ContactGroupMigration]) and shows the result,
 * with a CSV log to save. Sync before and after so the server holds the Groups.
 */
class ContactMigrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContactMigrationBinding
    private val migration by lazy { ContactGroupMigration(this, FhirApplication.fhirEngine(this)) }
    private var plan: ContactGroupMigration.Plan? = null
    private var running = false
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
            Toast.makeText(this@ContactMigrationActivity, if (ok) "Log saved" else "Could not save the log", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityContactMigrationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = "Migrate contacts"
            setDisplayHomeAsUpEnabled(true)
        }
        binding.migrateButton.setOnClickListener { confirm() }
        binding.saveLog.setOnClickListener { logFile?.let { saveLog.launch(it.name) } }
        loadPlan()
    }

    private fun loadPlan() {
        binding.progress.isIndeterminate = true
        binding.progress.visibility = View.VISIBLE
        binding.planTitle.text = "Checking contacts on this device…"
        binding.planDetail.text = ""
        binding.migrateButton.isEnabled = false
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { migration.plan() } }
            binding.progress.visibility = View.GONE
            found.onFailure { error ->
                Timber.tag(ContactGroups.TAG).e(error, "Could not check contacts")
                binding.planTitle.text = "Could not check contacts"
                binding.planDetail.text = error.message ?: error.javaClass.simpleName
            }.onSuccess { p ->
                plan = p
                if (p.items.isEmpty()) {
                    binding.planTitle.text = "Nothing to migrate"
                    binding.planDetail.text = "Every contact on this device is already in its case's contact group."
                } else {
                    binding.planTitle.text = "${p.movable.size} contacts of ${p.sourceCases} cases to move"
                    binding.planDetail.text = buildString {
                        append("Each contact is added to its source case's contact group and its old link is removed.")
                        if (p.missingSource.isNotEmpty()) {
                            append("\n\n${p.missingSource.size} contacts are linked to a case that is not on this device. ")
                            append("They keep their link and are listed in the log; sync and run again, or the server migration covers them.")
                        }
                    }
                    binding.migrateButton.isEnabled = p.items.isNotEmpty()
                }
            }
        }
    }

    private fun confirm() {
        val p = plan ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle("Migrate ${p.movable.size} contacts?")
            .setMessage("Sync first so this device holds the latest records. After migrating, sync again to send the contact groups to the server.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Migrate") { _, _ -> run(p) }
            .show()
    }

    private fun run(p: ContactGroupMigration.Plan) {
        running = true
        binding.migrateButton.isEnabled = false
        binding.result.visibility = View.GONE
        binding.saveLog.visibility = View.GONE
        binding.progress.isIndeterminate = false
        binding.progress.max = p.items.size.coerceAtLeast(1)
        binding.progress.progress = 0
        binding.progress.visibility = View.VISIBLE
        binding.planTitle.text = "Migrating…"
        lifecycleScope.launch {
            val summary = withContext(Dispatchers.IO) {
                runCatching {
                    migration.migrate(p) { done, total ->
                        withContext(Dispatchers.Main) {
                            binding.progress.setProgressCompat(done, true)
                            binding.planDetail.text = "$done of $total"
                        }
                    }
                }
            }
            running = false
            binding.progress.visibility = View.GONE
            summary.onFailure { error ->
                Timber.tag(ContactGroups.TAG).e(error, "Migration stopped")
                binding.planTitle.text = "Migration stopped"
                binding.planDetail.text = error.message ?: error.javaClass.simpleName
                binding.migrateButton.isEnabled = true
            }.onSuccess { s ->
                logFile = s.logFile
                binding.planTitle.text = "Migration finished"
                binding.planDetail.text = "Sync now to send the contact groups to the server."
                binding.result.text = ContactGroupMigration.Outcome.values()
                    .filter { (s.counts[it] ?: 0) > 0 }
                    .joinToString("\n") { "${s.counts[it]} · ${it.label}" }
                    .ifEmpty { "Nothing changed." }
                binding.result.visibility = View.VISIBLE
                binding.saveLog.visibility = View.VISIBLE
                // Run again shows what is left (links to cases not on this device).
                binding.migrateButton.isEnabled = false
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            if (!running) finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!running) super.onBackPressed()
    }
}
