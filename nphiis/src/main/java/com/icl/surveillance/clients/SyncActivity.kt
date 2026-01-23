package com.icl.surveillance.clients

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.icl.surveillance.R
import com.icl.surveillance.databinding.ActivitySyncBinding
import com.icl.surveillance.viewmodels.PeriodicSyncViewModel
import kotlinx.coroutines.launch

class SyncActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySyncBinding
    private val periodicSyncViewModel: PeriodicSyncViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySyncBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        refreshPeriodicSynUi()
        setUpSyncButtons()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun onBackPressed() {
        super.onBackPressed()
    }

    private fun setUpSyncButtons() {
        val syncNowButton = findViewById<Button>(R.id.sync_now_button)
        val cancelSyncButton = findViewById<Button>(R.id.cancel_sync_button)
        syncNowButton.apply {
            setOnClickListener {
                periodicSyncViewModel.collectPeriodicSyncJobStatus()
                toggleButtonVisibility(
                    hiddenButton = syncNowButton,
                    visibleButton = cancelSyncButton
                )
                visibility = View.GONE
            }
        }
        cancelSyncButton.apply {
            setOnClickListener {
                periodicSyncViewModel.cancelPeriodicSyncJob()
                toggleButtonVisibility(
                    hiddenButton = cancelSyncButton,
                    visibleButton = syncNowButton
                )
                visibility = View.GONE
            }
        }
    }

    private fun toggleButtonVisibility(hiddenButton: View, visibleButton: View) {
        hiddenButton.visibility = View.GONE
        visibleButton.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            periodicSyncViewModel.cancelPeriodicSyncJob()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun refreshPeriodicSynUi() {
        try {
            lifecycleScope.launch {
                lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    periodicSyncViewModel.uiStateFlow.collect { uiState ->
                        uiState.lastSyncStatus?.let {
                            findViewById<TextView>(R.id.last_sync_status).text = it
                        }

                        uiState.lastSyncTime?.let {
                            findViewById<TextView>(R.id.last_sync_time).text = it
                        }

                        uiState.currentSyncStatus?.let {
                            findViewById<TextView>(R.id.current_sync_status).text = it
                        }

                        val syncIndicator = findViewById<ProgressBar>(R.id.sync_indicator)
                        val progressLabel = findViewById<TextView>(R.id.progress_percentage_label)

                        if (uiState.progress != null) {
                            syncIndicator.progress = uiState.progress
                            syncIndicator.visibility = View.VISIBLE

                            progressLabel.text = "${uiState.progress}"
                            progressLabel.visibility = View.VISIBLE
                        } else {
                            syncIndicator.progress = 0
                            progressLabel.visibility = View.GONE
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}