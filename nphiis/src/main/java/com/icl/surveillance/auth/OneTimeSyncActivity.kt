package com.icl.surveillance.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.icl.surveillance.MainActivity
import com.icl.surveillance.R
import com.icl.surveillance.databinding.ActivityOneTimeSyncBinding
import com.icl.surveillance.fhir.LocationDownloadViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class OneTimeSyncActivity : AppCompatActivity() {
    private lateinit var binding: ActivityOneTimeSyncBinding
    private val viewModel = LocationDownloadViewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityOneTimeSyncBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        binding.apply {
            animationView.playAnimation()
            cancelButton.setOnClickListener {
                // Handle cancellation logic here
            }
        }

        initWorkManager()

        viewModel.fetchComplete.observe(this@OneTimeSyncActivity) {
            if (it) {
                val intent = Intent(this@OneTimeSyncActivity, MainActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(intent)
                this@OneTimeSyncActivity.finish()

            }
        }

        lifecycleScope.launch {
            delay(5000)
            val intent = Intent(this@OneTimeSyncActivity, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            this@OneTimeSyncActivity.finish()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            initWorkManager()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initWorkManager() {
        /*
        lifecycleScope.launch {
            val workRequest =
                PeriodicWorkRequestBuilder<LocationDownloadedWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED) // Ensure network is available
                            .build()
                    )
                    .build()
            val uniqueName = "location_downloader"//${UUID.randomUUID()}"

            WorkManager.getInstance(this@OneTimeSyncActivity)
                .enqueueUniquePeriodicWork(
                    uniqueName,  // unique name to avoid duplicates
                    ExistingPeriodicWorkPolicy.KEEP,   // KEEP = don't run again if already enqueued
                    workRequest
                )

            WorkManager.getInstance(this@OneTimeSyncActivity)
                .getWorkInfoByIdLiveData(workRequest.id)
                .observe(this@OneTimeSyncActivity) { workInfo ->
                    when (workInfo?.state) {
                        WorkInfo.State.RUNNING -> {
                            println("Worker Status running")
                        }

                        WorkInfo.State.SUCCEEDED -> {
                            println("Worker Status finished successfully")
                            val wasComplete =
                                workInfo.outputData.getBoolean("fetch_complete", false)
                            if (wasComplete) {
                                println("✅ Data fetching complete. Update ViewModel here.")
                                viewModel.setFetchingComplete(true)
                            }
                        }

                        WorkInfo.State.FAILED -> println("Worker Status failed")
                        else -> {
                            println("Worker Status Unknown")

                        }
                    }
                }
        }*/
    }
}