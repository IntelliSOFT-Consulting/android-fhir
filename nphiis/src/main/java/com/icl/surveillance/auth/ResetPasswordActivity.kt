package com.icl.surveillance.auth

import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.icl.surveillance.R
import com.icl.surveillance.databinding.ActivityResetPasswordBinding
import com.icl.surveillance.models.DbSetPasswordReq
import com.icl.surveillance.network.RetrofitCallsAuthentication
import com.icl.surveillance.utils.FormatterClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ResetPasswordActivity : AppCompatActivity() {
    private lateinit var binding: ActivityResetPasswordBinding
    private var retrofitCallsAuthentication = RetrofitCallsAuthentication()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityResetPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.apply {
            title = "Reset Password"
        }
        binding.apply {
            btnSubmit.setOnClickListener {
                val code = binding.codeEditText.text.toString().trim()
                val password = binding.passwordEditText.text.toString().trim()
                val confirm = binding.confirmPasswordEditText.text.toString().trim()

                if (code.isEmpty() || password.isEmpty() || confirm.isEmpty()) {
                    Toast.makeText(
                        this@ResetPasswordActivity,
                        "All fields are required",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                if (code.isEmpty()) {
                    binding.codeInputLayout.error = "Please enter the code"
                    binding.codeEditText.requestFocus()
                    return@setOnClickListener
                }
                if (password.isEmpty()) {
                    binding.passwordInputLayout.error = "Please enter your password"
                    binding.passwordEditText.requestFocus()
                    return@setOnClickListener
                }
                if (confirm.isEmpty()) {
                    binding.confirmPasswordInputLayout.error = "Please confirm the password"
                    binding.confirmPasswordEditText.requestFocus()
                    return@setOnClickListener
                }
                if (password != confirm) {
                    binding.confirmPasswordInputLayout.error = "Passwords do not match"
                    return@setOnClickListener
                } else {
                    binding.confirmPasswordInputLayout.error = null
                }

                CoroutineScope(Dispatchers.Main).launch {

                    val progressDialog = ProgressDialog(this@ResetPasswordActivity)
                    progressDialog.setTitle("Please wait..")
                    progressDialog.setMessage("Authentication in progress..")
                    progressDialog.setCanceledOnTouchOutside(false)
                    progressDialog.show()

                    val job = Job()
                    CoroutineScope(Dispatchers.IO + job).launch {
                        val idNumber =
                            FormatterClass().getSharedPref("idNumber", this@ResetPasswordActivity)
                        val dbSetPasswordReq = DbSetPasswordReq(code, "$idNumber", password)
                        val pairReturn = retrofitCallsAuthentication
                            .setPassword(this@ResetPasswordActivity, dbSetPasswordReq)

                        val messageCode = pairReturn.first
                        val messageToast = pairReturn.second

                        CoroutineScope(Dispatchers.Main).launch {
                            Toast.makeText(
                                this@ResetPasswordActivity, messageToast,
                                Toast.LENGTH_SHORT
                            ).show()
                            if (messageCode == 200 || messageCode == 201) {
                                val intent = Intent(
                                    this@ResetPasswordActivity,
                                    LoginActivity::class.java
                                )
                                startActivity(intent)
                            }
                        }
                    }.join()
                    progressDialog.dismiss()

                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}