package com.icl.surveillance.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.icl.surveillance.MainActivity
import com.icl.surveillance.R
import com.icl.surveillance.databinding.ActivityLauncherBinding
import com.icl.surveillance.utils.FormatterClass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LauncherActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLauncherBinding
    private val UPDATE_REQUEST_CODE = 100
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLauncherBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val appUpdateManager = AppUpdateManagerFactory.create(this)
        val appUpdateInfoTask = appUpdateManager.appUpdateInfo


        appUpdateInfoTask.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
                && appUpdateInfo.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
            ) {
                // Request the immediate update
                appUpdateManager.startUpdateFlowForResult(
                    appUpdateInfo,
                    AppUpdateType.IMMEDIATE,
                    this@LauncherActivity,
                    UPDATE_REQUEST_CODE
                )
            }
        }
        appUpdateInfoTask.addOnFailureListener { e ->
            // Handle the failure
            e.printStackTrace()
        }
        lifecycleScope.launch {
            delay(3000) // 3 seconds
            val loggedIn = FormatterClass().getSharedPref("isLoggedIn", this@LauncherActivity)
            if (loggedIn != null) {
                if (FormatterClass().isSyncDone(this@LauncherActivity)) {
                    val intent = Intent(this@LauncherActivity, MainActivity::class.java)
                    startActivity(intent)
                } else {
                    val intent = Intent(this@LauncherActivity, InitialSyncActivity::class.java)
                    startActivity(intent)
                }
                this@LauncherActivity.finish()
            } else {
                binding.getStartedButton.visibility = View.VISIBLE
            }
        }
        binding.apply {
            getStartedButton.apply {
                setOnClickListener {
                    FormatterClass().clearCache(this@LauncherActivity)
                    FormatterClass().deleteSharedPref("isLoggedIn", this@LauncherActivity)
                    val intent = Intent(this@LauncherActivity, LoginActivity::class.java)
                    startActivity(intent)
                    this@LauncherActivity.finish()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == UPDATE_REQUEST_CODE && resultCode != Activity.RESULT_OK) {
            // Update failed or canceled — you can close the app to enforce
            finish()
        }
    }
}