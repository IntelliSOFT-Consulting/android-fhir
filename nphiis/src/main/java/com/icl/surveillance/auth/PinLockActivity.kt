package com.icl.surveillance.auth

import android.annotation.SuppressLint
import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.icl.surveillance.R
import com.icl.surveillance.databinding.ActivityPinLockBinding
import com.icl.surveillance.models.SetNewPasswordReq
import com.icl.surveillance.network.RetrofitCallsAuthentication
import com.icl.surveillance.utils.FormatterClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class PinLockActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPinLockBinding
    private var retrofitCallsAuthentication = RetrofitCallsAuthentication()
    private val isProfilePasswordChange by lazy {
        intent.getBooleanExtra(EXTRA_IS_PROFILE_PASSWORD_CHANGE, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityPinLockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        val screenTitle = getString(if (isProfilePasswordChange) R.string.change_password else R.string.set_new_password)
        supportActionBar?.title = screenTitle
        binding.apply {
            tvTitle.text = screenTitle

            currentPasswordEditText.clearErrorOnTextChange(binding.currentPasswordInputLayout)
            passwordEditText.clearErrorOnTextChange(binding.passwordInputLayout)
            confirmPasswordEditText.clearErrorOnTextChange(binding.confirmPasswordInputLayout)

            btnSubmit.setOnClickListener {
                val code = binding.currentPasswordEditText.text.toString().trim()
                val password = binding.passwordEditText.text.toString().trim()
                val confirm = binding.confirmPasswordEditText.text.toString().trim()

                if (code.isEmpty() || password.isEmpty() || confirm.isEmpty()) {
                    Toast.makeText(
                        this@PinLockActivity,
                        "All fields are required",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                if (code.isEmpty()) {
                    binding.currentPasswordInputLayout.error = "Please enter current password"
                    binding.currentPasswordEditText.requestFocus()
                    return@setOnClickListener
                }
                if (password.isEmpty()) {
                    binding.passwordInputLayout.error = "Please enter your password"
                    binding.passwordEditText.requestFocus()
                    return@setOnClickListener
                }
                if (password.length < 6) {
                    binding.passwordInputLayout.error = "Password must be at least 6 characters"
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

                    val progressDialog = ProgressDialog(this@PinLockActivity)
                    progressDialog.setTitle("Please wait..")
                    progressDialog.setMessage("Authentication in progress..")
                    progressDialog.setCanceledOnTouchOutside(false)
                    progressDialog.show()

                    val job = Job()
                    CoroutineScope(Dispatchers.IO + job).launch {
                        val idNumber =
                            FormatterClass().getSharedPref("idNumber", this@PinLockActivity)
                        val dbSetPasswordReq = SetNewPasswordReq(code, "$idNumber", password)
                        val pairReturn = retrofitCallsAuthentication
                            .setNewPassword(this@PinLockActivity, dbSetPasswordReq)

                        val messageCode = pairReturn.first
                        val messageToast = pairReturn.second

                        CoroutineScope(Dispatchers.Main).launch {
                            Toast.makeText(
                                this@PinLockActivity, messageToast,
                                Toast.LENGTH_SHORT
                            ).show()
                            if (messageCode == 200 || messageCode == 201) {
                                handleSuccessfulPasswordChange()
                            }
                        }
                    }.join()
                    progressDialog.dismiss()

                }
            }
        }
    }

    fun TextInputEditText.clearErrorOnTextChange(errorLayout: TextInputLayout) {
        this.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                errorLayout.error = null
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    // Handles the toolbar Up button
    override fun onSupportNavigateUp(): Boolean {
        handleExitAction()
        return true
    }

    @SuppressLint("MissingSuperCall")
    override fun onBackPressed() {
        handleExitAction()
    }

    private fun handleSuccessfulPasswordChange() {
        if (isProfilePasswordChange) {
            setResult(RESULT_OK)
            finish()
            return
        }
        navigateToLoginAndClearStack()
    }

    private fun handleExitAction() {
        if (isProfilePasswordChange) {
            finish()
            return
        }
        navigateToLoginAndClearStack()
    }

    private fun navigateToLoginAndClearStack() {
        val intent = Intent(this, LoginActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        finish()
    }

    companion object {
        const val EXTRA_IS_PROFILE_PASSWORD_CHANGE = "extra_is_profile_password_change"
    }
}
