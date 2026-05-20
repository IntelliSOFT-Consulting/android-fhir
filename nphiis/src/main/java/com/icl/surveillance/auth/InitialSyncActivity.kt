package com.icl.surveillance.auth

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.sync.CurrentSyncJobStatus
import com.google.android.fhir.sync.Sync
import com.icl.surveillance.MainActivity
import com.icl.surveillance.R
import com.icl.surveillance.databinding.ActivityInitialSyncBinding
import com.icl.surveillance.fhir.AppFhirSyncWorker
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.fhir.NPHIISSyncProgressStore
import com.icl.surveillance.utils.FhirBundleLoader
import com.icl.surveillance.utils.FormatterClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.collections.emptyList

class InitialSyncActivity : AppCompatActivity() {
    private lateinit var fhirEngine: FhirEngine
    private lateinit var binding: ActivityInitialSyncBinding

    private lateinit var locationMonitor: NPHIISSyncProgressStore
    private var hasNavigatedToMain = false
    private var hasRetriedOneTimeSync = false

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
        locationMonitor = NPHIISSyncProgressStore(applicationContext)
        val isSyncDone = FormatterClass().isSyncDone(this)
        Timber.tag("InitialSyncActivity").d("isSyncDone: $isSyncDone")

        if (isSyncDone) {
            Timber.tag("InitialSyncActivity").d("Sync already done, moving to MainActivity")
            startMain()
            return
        }

        Timber.tag("InitialSyncActivity").d("Starting fresh sync")
        startOneTimeSync()
        observeLocationProgress()
    }

    private fun startOneTimeSync() {
        lifecycleScope.launch {
            try {
                Sync.oneTimeSync<AppFhirSyncWorker>(this@InitialSyncActivity)
                    .catch { throwable ->
                        Timber.tag("FHIR_SYNC")
                            .e(throwable, "Error running initial sync: ${throwable.message}")
                        retryInitialSyncOnceOrShowError()
                    }
                    .collect { status ->
                        when (status) {
                            is CurrentSyncJobStatus.Succeeded -> completeInitialSync()
                            is CurrentSyncJobStatus.Failed -> retryInitialSyncOnceOrShowError()
                            else -> Unit
                        }
                    }
            } catch (e: Exception) {
                Timber.tag("FHIR_SYNC").e(e, "Error launching initial sync: ${e.message}")
                retryInitialSyncOnceOrShowError()
            }
        }
    }

    private fun retryInitialSyncOnceOrShowError() {
        if (hasRetriedOneTimeSync) {
            binding.syncStatusText.text =
                getString(R.string.initial_sync_failed_check_network_and_retry)
            return
        }
        hasRetriedOneTimeSync = true
        binding.syncStatusText.text = getString(R.string.retrying_sync)
        lifecycleScope.launch {
            delay(1500)
            startOneTimeSync()
        }
    }

    private fun completeInitialSync() {
        if (hasNavigatedToMain) return
        hasNavigatedToMain = true
        FormatterClass().setSyncDone(this@InitialSyncActivity)
        binding.syncStatusText.text = getString(R.string.all_data_imported_successfully)
        lifecycleScope.launch {
            delay(2000)
            FormatterClass().setSyncDone(this@InitialSyncActivity)
            startMain()
        }
    }

    private fun observeLocationProgress() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                locationMonitor.progress.collect { progress ->
                    val count = progress.locationDownloaded
                    val type = progress.currentType
                    if (!hasNavigatedToMain) {
                        binding.syncStatusText.text =
                            if (type.isBlank()) "Locations synced: $count"
                            else "$type synced: $count"
                    }
                }
            }
        }
    }


    private fun startMain() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }

        startActivity(intent)
        finish()
    }

}
