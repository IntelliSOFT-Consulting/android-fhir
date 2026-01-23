package com.icl.surveillance.auth

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.fhir.FhirEngine
import com.icl.surveillance.MainActivity
import com.icl.surveillance.R
import com.icl.surveillance.databinding.ActivityInitialSyncBinding
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.fhir.LocationDownloadedWorker
import com.icl.surveillance.utils.FhirBundleLoader
import com.icl.surveillance.utils.FormatterClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.emptyList

class InitialSyncActivity : AppCompatActivity() {
    private lateinit var fhirEngine: FhirEngine
    private lateinit var binding: ActivityInitialSyncBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityInitialSyncBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        fhirEngine = FhirApplication.fhirEngine(this@InitialSyncActivity)
        if (FormatterClass().isSyncDone(this)) {
            startMain()

        } else {
            handleInitialFHIRLocalSync()
        }
    }

    private fun handleInitialFHIRSync() {
        val workerRequest = OneTimeWorkRequestBuilder<LocationDownloadedWorker>().build()
        val workerId = workerRequest.id
        WorkManager.getInstance(this@InitialSyncActivity).enqueue(workerRequest)

        WorkManager.getInstance(this@InitialSyncActivity)
            .getWorkInfoByIdLiveData(workerId)
            .observe(this) { workInfo ->
                workInfo?.progress?.let { data ->
                    val processed = data.getInt("processed", 0)
                    val skipped = data.getInt("skipped", 0)
                    val failed = data.getInt("failed", 0)
                    val message = buildString {
                        if (processed > 0) append("Processed $processed ")
                        if (skipped > 0) append(", skipped $skipped")
                        if (failed > 0) append(", failed $failed")
                    }
                    binding.syncStatusText.text = message
                }

                // Optional: handle completion
                if (workInfo?.state?.isFinished == true) {
                    Toast.makeText(this, "Import complete", Toast.LENGTH_SHORT).show()
                    startMain()
                }
            }

    }

    private fun handleInitialFHIRLocalSync() {
        lifecycleScope.launch {
            val loader = FhirBundleLoader(this@InitialSyncActivity)
            val status = binding.syncStatusText   // or findViewById
            fun update(msg: String) {

                status.text = msg
            }

            update("Preparing data…")
            val assetManager = assets
            // List all files in assets/bundles and sort by page number
            val assetFiles = assetManager.list("bundles")?.sortedBy { fileName ->
                Regex("bundle_page_(\\d+)\\.json").find(fileName)?.groupValues?.get(1)?.toInt() ?: 0
            } ?: emptyList()

            val totalProcessed = AtomicInteger(0)
            val totalSkipped = AtomicInteger(0)
            val totalFailed = AtomicInteger(0)

            val totalEntries = assetFiles.sumOf { fileName ->
                assetManager.open("bundles/$fileName").use { loader.parseFhirBundle(it).entry.size }
            }

            for (fileName in assetFiles) {
                assetManager.open("bundles/$fileName").use { inputStream ->
                    val bundle = loader.parseFhirBundle(inputStream)

                    var lastProcessedInBundle = 0
                    var lastSkippedInBundle = 0
                    var lastFailedInBundle = 0

                    withContext(Dispatchers.IO) {
                        loader.createBundleInEngine(
                            fhirEngine,
                            bundle
                        ) { processed, skipped, failed, total ->

                            // Compute only the new entries since last callback
                            val deltaProcessed = processed - lastProcessedInBundle
                            val deltaSkipped = skipped - lastSkippedInBundle
                            val deltaFailed = failed - lastFailedInBundle

                            // Update per-bundle trackers
                            lastProcessedInBundle = processed
                            lastSkippedInBundle = skipped
                            lastFailedInBundle = failed

                            // Increment global totals
                            totalProcessed.addAndGet(deltaProcessed)
                            totalSkipped.addAndGet(deltaSkipped)
                            totalFailed.addAndGet(deltaFailed)

                            val message = buildString {
                                if (totalProcessed.get() > 0) append("Processed ${totalProcessed.get()} ") 
                                if (totalFailed.get() > 0) append(", failed ${totalFailed.get()}")
                            }
                            CoroutineScope(Dispatchers.Main).launch {
                                update(message)
                            }
                        }
                    }
                }
            }

            update("All data imported successfully.")
            FormatterClass().setSyncDone(this@InitialSyncActivity)
            lifecycleScope.launch {
                delay(2000)
                startMain()
            }
        }
    }

    private fun handleInitialSync() {
        lifecycleScope.launch {
            val loader = FhirBundleLoader(this@InitialSyncActivity)
            val status = binding.syncStatusText   // or findViewById
            fun update(msg: String) {

                status.text = msg
            }

            update("Preparing data…")

            importBundleFile(
                loader, fhirEngine,
                "fhir-bundle-counties-kenya.json",
                "Counties",
                ::update
            )

            importBundleFile(
                loader, fhirEngine,
                "fhir-bundle-sub-counties-kenya.json",
                "Sub Counties",
                ::update
            )

            importBundleFile(
                loader, fhirEngine,
                "fhir-bundle-wards-kenya.json",
                "Wards",
                ::update
            )

            importBundleFile(
                loader, fhirEngine,
                "fhir-bundle-facilities-kenya.json",
                "Facilities",
                ::update
            )
            update("All data imported successfully.")
            FormatterClass().setSyncDone(this@InitialSyncActivity)
            startMain()
        }
    }

    private fun startMain() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private suspend fun importBundleFile(
        loader: FhirBundleLoader,
        engine: FhirEngine,
        fileName: String,
        label: String,
        onStatus: (String) -> Unit
    ) {
        onStatus("Preparing $label…")

        val inputStream = withContext(Dispatchers.IO) {
            loader.loadBundleJson(fileName)
        }

        onStatus("Parsing $label…")

        val bundle = withContext(Dispatchers.IO) {
            loader.parseFhirBundle(inputStream)
        }
        onStatus("Loading ${bundle.entry.size - 1} $label…")

        withContext(Dispatchers.IO) {
            loader.createBundleInEngine(engine, bundle) { processed, skipped, failed, total ->
                val message = buildString {
                    append("Processed $processed / $total")
                    if (skipped > 0) append(", skipped $skipped")
                    if (failed > 0) append(", failed $failed")
                }
                CoroutineScope(Dispatchers.Main).launch {
                    onStatus(message)
                }
            }
        }
    }
}