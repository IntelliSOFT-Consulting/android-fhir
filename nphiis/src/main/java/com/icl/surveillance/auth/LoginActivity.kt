package com.icl.surveillance.auth

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.icl.surveillance.R
import com.icl.surveillance.databinding.ActivityLoginBinding
import com.icl.surveillance.models.DbSignIn
import com.icl.surveillance.network.RetrofitCallsAuthentication
import com.icl.surveillance.viewmodels.SyncFragmentViewModel
import kotlin.getValue

class LoginActivity : AppCompatActivity() {

    private var retrofitCallsAuthentication = RetrofitCallsAuthentication()
    private lateinit var binding: ActivityLoginBinding
    private val viewModel: SyncFragmentViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }




        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1001
                )
            }
        }

        binding.apply {
            tvForgotPassword.setOnClickListener {
                startActivity(Intent(this@LoginActivity, ForgotPasswordActivity::class.java))
            }
            loginCard.apply {
                animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(1000)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            }
            logo.apply {
                animate()
                    .alpha(1f)
                    .setDuration(400)
                    .setStartDelay(100)
                    .start()
            }
            btnLogin.setOnClickListener {
                val email = etEmail.text.toString()
                val password = etPassword.text.toString()

                if (email.isEmpty()) {
                    binding.emailLayout.error = "Please enter username"
                    return@setOnClickListener
                }
                // check password
                if (password.isEmpty()) {
                    binding.passwordLayout.error = "Please enter password"
                    return@setOnClickListener
                }

                val dbSignIn = DbSignIn(idNumber = email, password = password, "Facility")
                retrofitCallsAuthentication.loginUser(
                    viewModel = viewModel,
                    context = this@LoginActivity,
                    dbSignIn = dbSignIn
                )

            }
        }
    }
}