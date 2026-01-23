package com.icl.surveillance.auth

import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.icl.surveillance.R
import com.icl.surveillance.databinding.ActivityForgotPasswordBinding
import com.icl.surveillance.models.DbResetPasswordData
import com.icl.surveillance.network.RetrofitCallsAuthentication
import com.icl.surveillance.utils.FormatterClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ForgotPasswordActivity : AppCompatActivity() {
    private lateinit var binding: ActivityForgotPasswordBinding
    private var retrofitCallsAuthentication = RetrofitCallsAuthentication()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityForgotPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.apply {
            title = "Forgot Password"
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        binding.apply {
            btnSubmit.setOnClickListener {

                val emailAddress = etEmail.text.toString()
                if (emailAddress.isEmpty()) {
                    emailLayout.error = "Please enter Username"
                    etEmail.requestFocus()
                    return@setOnClickListener
                }



                binding.emailLayout.error = null 
                val payload = DbResetPasswordData(
                    idNumber = emailAddress,
                    email = emailAddress
                )
                CoroutineScope(Dispatchers.Main).launch {

                    val progressDialog = ProgressDialog(this@ForgotPasswordActivity)
                    progressDialog.setTitle("Please wait..")
                    progressDialog.setMessage("Authentication in progress..")
                    progressDialog.setCanceledOnTouchOutside(false)
                    progressDialog.show()

                    val job = Job()
                    CoroutineScope(Dispatchers.IO + job).launch {
                        FormatterClass().saveSharedPref(
                            "idNumber",
                            emailAddress,
                            this@ForgotPasswordActivity
                        )
                        val pairReturn = retrofitCallsAuthentication
                            .getResetPassword(this@ForgotPasswordActivity, payload)

                        val messageCode = pairReturn.first
                        val messageToast = pairReturn.second

                        CoroutineScope(Dispatchers.Main).launch {
                            Toast.makeText(
                                this@ForgotPasswordActivity, messageToast,
                                Toast.LENGTH_SHORT
                            ).show()
                            if (messageCode == 200 || messageCode == 201) {
                                val intent = Intent(
                                    this@ForgotPasswordActivity,
                                    ResetPasswordActivity::class.java
                                )
                                startActivity(intent)
                                this@ForgotPasswordActivity.finish()
                            }
                        }

                    }.join()
                    progressDialog.dismiss()

                }

            }
            haveCodeTextView.setOnClickListener {
                startActivity(
                    Intent(
                        this@ForgotPasswordActivity,
                        ResetPasswordActivity::class.java
                    )
                )
                finish()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}