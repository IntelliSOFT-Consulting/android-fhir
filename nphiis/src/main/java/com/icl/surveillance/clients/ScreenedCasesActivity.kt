package com.icl.surveillance.clients

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.icl.surveillance.R
import com.icl.surveillance.cases.CaseListingActivity
import com.icl.surveillance.databinding.ActivityScreenedCasesBinding
import com.icl.surveillance.databinding.ItemScreenedCaseBinding
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.fhir.forms.CaseTypes
import com.icl.surveillance.fhir.forms.VhfScreened
import com.icl.surveillance.fhir.forms.VhfTraining
import com.icl.surveillance.utils.FormatterClass
import kotlinx.coroutines.launch
import timber.log.Timber

/** VHF: people screened (e.g. at points of entry) who still need a case investigation. */
class ScreenedCasesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScreenedCasesBinding
    private var all: List<VhfScreened.Row> = emptyList()
    private val adapter = ScreenedAdapter { row -> openDetail(row) }

    private val detailLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            // Investigated: the person is now a VHF case and leaves this list.
            setResult(Activity.RESULT_OK)
            load()
            val name = result.data?.getStringExtra(ScreenedCaseDetailActivity.EXTRA_MOVED_NAME)
            Snackbar.make(
                binding.root,
                getString(R.string.screened_moved, name ?: getString(R.string.screened_title)),
                Snackbar.LENGTH_LONG
            ).setAction(R.string.screened_open_list) { openCaseList() }.show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityScreenedCasesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = getString(R.string.screened_title)
            setDisplayHomeAsUpEnabled(true)
        }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.search.doAfterTextChanged { show(filter(it?.toString().orEmpty())) }
        load()
    }

    private fun load() {
        binding.progress.visibility = View.VISIBLE
        binding.empty.visibility = View.GONE
        lifecycleScope.launch {
            all = runCatching { VhfScreened.list(FhirApplication.fhirEngine(this@ScreenedCasesActivity)) }
                .onFailure { Timber.tag("VHF_SCREENED").e(it, "Could not load screened people") }
                .getOrDefault(emptyList())
            binding.progress.visibility = View.GONE
            show(filter(binding.search.text?.toString().orEmpty()))
        }
    }

    private fun filter(query: String): List<VhfScreened.Row> {
        val q = query.trim()
        if (q.isEmpty()) return all
        return all.filter { row ->
            listOf(row.name, row.phone, row.idNumber).any { it.contains(q, ignoreCase = true) }
        }
    }

    private fun show(rows: List<VhfScreened.Row>) {
        adapter.submit(rows)
        binding.summary.text =
            resources.getQuantityString(R.plurals.screened_summary, all.size, all.size)
        binding.empty.visibility =
            if (rows.isEmpty() && binding.progress.visibility != View.VISIBLE) View.VISIBLE else View.GONE
    }

    private fun openDetail(row: VhfScreened.Row) {
        detailLauncher.launch(
            Intent(this, ScreenedCaseDetailActivity::class.java)
                .putExtra(ScreenedCaseDetailActivity.EXTRA_PATIENT_ID, row.patientId)
        )
    }

    private fun openCaseList() {
        VhfTraining.setOn(this, false)
        FormatterClass().saveSharedPref("listingTitle", " VHF Case List", this)
        FormatterClass().saveSharedPref("currentCase", CaseTypes.VHF, this)
        startActivity(Intent(this, CaseListingActivity::class.java))
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private class ScreenedAdapter(
        private val onClick: (VhfScreened.Row) -> Unit,
    ) : RecyclerView.Adapter<ScreenedAdapter.Holder>() {

        private var rows: List<VhfScreened.Row> = emptyList()

        fun submit(newRows: List<VhfScreened.Row>) {
            rows = newRows
            notifyDataSetChanged()
        }

        class Holder(val binding: ItemScreenedCaseBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            ItemScreenedCaseBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]
            with(holder.binding) {
                name.text = row.name
                phone.text = "Phone: ${row.phone.ifBlank { "—" }}"
                idNumber.text = "ID: ${row.idNumber.ifBlank { "—" }}"
                root.setOnClickListener { onClick(row) }
            }
        }
    }
}
