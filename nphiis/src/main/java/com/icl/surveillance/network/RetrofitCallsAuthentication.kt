package com.icl.surveillance.network

import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.icl.surveillance.auth.InitialSyncActivity
import com.icl.surveillance.models.DbResetPasswordData
import com.icl.surveillance.models.DbResponseError
import com.icl.surveillance.models.DbSetPasswordReq
import com.icl.surveillance.models.DbSignIn
import com.icl.surveillance.models.FCMToken
import com.icl.surveillance.models.User
import com.icl.surveillance.utils.Constants.ALERTS_BASE_URL
import com.icl.surveillance.utils.Constants.BASE_AUTH_URL
import com.icl.surveillance.utils.Constants.BASE_URL
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.viewmodels.SyncFragmentViewModel
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.RequestBody
import retrofit2.Response

class RetrofitCallsAuthentication {
    private val backgroundProcessingScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("BackgroundProcessing")
    )

    fun loginUser(viewModel: SyncFragmentViewModel, context: Context, dbSignIn: DbSignIn) {
        CoroutineScope(Dispatchers.Main).launch {
            val job = Job()
            CoroutineScope(Dispatchers.IO + job).launch { startLogin(viewModel, context, dbSignIn) }
                .join()
        }
    }

    fun getUserProfile(viewModel: SyncFragmentViewModel, context: Context) {

        CoroutineScope(Dispatchers.Main).launch {
            val job = Job()
            CoroutineScope(Dispatchers.IO + job).launch { getUserDetails(viewModel, context) }
                .join()
        }
    }

    fun pullUserAlerts(context: Context) {

        CoroutineScope(Dispatchers.Main).launch {
            val job = Job()
            CoroutineScope(Dispatchers.IO + job).launch { startPullingUserAlerts(context) }.join()
        }
    }

    fun updateOrCreateToken(context: Context, token: String) {

        CoroutineScope(Dispatchers.Main).launch {
            val job = Job()
            CoroutineScope(Dispatchers.IO + job).launch {
                startUpdateOrCreateToken(
                    context,
                    FCMToken(token = token)
                )
            }.join()
        }
    }

    fun sendPatientToServer(
        id: String,
        payload: RequestBody,
        context: Context
    ) {
        backgroundProcessingScope.launch {
            val baseUrl = BASE_URL//"https://hapi.fhir.org/baseR4/"
            val apiService =
                RetrofitBuilder.getRetrofit(baseUrl).create(Interface::class.java)
            println("API Response:::: Started posting data $id -> payload $payload")
            try {
                val token = FormatterClass().getSharedPref("access_token", context)
                if (token != null) {
                    val apiInterface = apiService.sendPatientToServer(id, payload, "Bearer $token")
                    if (apiInterface.isSuccessful) {
                        val statusCode = apiInterface.code()
                        val body = apiInterface.body()
                        if (statusCode == 200 || statusCode == 201) {
                            if (body != null) {
                                println("API Response::::Success $body")

                            } else {
                                println("API Response:::: Body is null")
                            }
                        } else {
                            println("API Response:::: Error Status Code: $statusCode")
                        }
                    } else {
                        val errorBody = apiInterface.errorBody()?.string()
                        println("API Response:::: Error Response for $id")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                println("API Response:::: Error -> ${e.message}")
            }
        }
    }

    fun sendBundleToServer(payload: RequestBody, context: Context) {
        backgroundProcessingScope.launch {
            val baseUrl = BASE_URL
            val apiService =
                RetrofitBuilder.getRetrofit(baseUrl).create(Interface::class.java)
            println("API Response:::: Started posting data")
            try {
                val token = FormatterClass().getSharedPref("access_token", context)
                if (token != null) {
                    val apiInterface = apiService.sendBundleToServer(payload, "Bearer $token")
                    if (apiInterface.isSuccessful) {
                        val statusCode = apiInterface.code()
                        val body = apiInterface.body()
                        if (statusCode == 200 || statusCode == 201) {
                            if (body != null) {
                                println("API Response:::: $body")
                            } else {
                                println("API Response:::: Body is null")
                            }
                        } else {
                            println("API Response:::: Error Status Code: $statusCode")
                        }
                    } else {
                        println("API Response:::: Error Response: ${apiInterface.code()} ")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                println("API Response:::: Error -> ${e.message}")
            }
        }
    }

    private fun startUpdateOrCreateToken(context: Context, body: FCMToken) {
        val job1 = Job()
        CoroutineScope(Dispatchers.Main + job1).launch {
            var messageToast = ""
            val job = Job()
            CoroutineScope(Dispatchers.IO + job)
                .launch {
                    val formatter = FormatterClass()
                    val apiService =
                        RetrofitBuilder.getRetrofit(ALERTS_BASE_URL).create(Interface::class.java)
                    try {
                        val token = formatter.getSharedPref("access_token", context)
                        if (token != null) {
                            val apiInterface = apiService.updateFCMToken(body, "Bearer $token")
                            if (apiInterface.isSuccessful) {

                                val statusCode = apiInterface.code()
                                val body = apiInterface.body()

                                if (statusCode == 200 || statusCode == 201) {

                                    messageToast = "Success"

                                } else {
                                    messageToast = "Error: The request was not successful"
                                }
                            } else {
                                apiInterface.errorBody()?.let {
                                    messageToast =
                                        "Invalid  credentials. Please try again."
                                }
                            }
                        }
                    } catch (e: Exception) {
                        messageToast = "Cannot login user.."
                    }
                }
                .join()
            CoroutineScope(Dispatchers.Main).launch {

//                Toast.makeText(context, messageToast, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startLogin(viewModel: SyncFragmentViewModel, context: Context, dbSignIn: DbSignIn) {

        val job1 = Job()
        CoroutineScope(Dispatchers.Main + job1).launch {
            val progressDialog = ProgressDialog(context)
            progressDialog.setTitle("Please wait..")
            progressDialog.setMessage("Authentication in progress..")
            progressDialog.setCanceledOnTouchOutside(false)
            progressDialog.show()

            var messageToast = ""
            val job = Job()
            CoroutineScope(Dispatchers.IO + job)
                .launch {
                    val formatter = FormatterClass()

                    val apiService =
                        RetrofitBuilder.getRetrofit(BASE_AUTH_URL).create(Interface::class.java)
                    try {

                        val apiInterface = apiService.signInUser(dbSignIn)
                        if (apiInterface.isSuccessful) {

                            val statusCode = apiInterface.code()
                            val body = apiInterface.body()

                            if (statusCode == 200 || statusCode == 201) {

                                if (body != null) {

                                    val access_token = body.access_token
                                    val expires_in = body.expires_in
                                    val refresh_expires_in = body.refresh_expires_in
                                    val refresh_token = body.refresh_token

                                    formatter.saveSharedPref("access_token", access_token, context)
                                    formatter.saveSharedPref(
                                        "expires_in",
                                        expires_in,
                                        context
                                    )
                                    formatter.saveSharedPref(
                                        "refresh_expires_in",
                                        refresh_expires_in,
                                        context
                                    )
                                    formatter.saveSharedPref(
                                        "refresh_token",
                                        refresh_token,
                                        context
                                    )
                                    formatter.saveSharedPref("isLoggedIn", "true", context)
                                    getUserDetails(viewModel, context)
                                    messageToast = "Login successful."

                                    val intent = Intent(context, InitialSyncActivity::class.java)
                                    intent.addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                    )
                                    context.startActivity(intent)
                                    if (context is Activity) {
                                        context.finish()
                                    }
                                } else {
                                    messageToast = "Error: Body is null"
                                }
                            } else {
                                messageToast = "Error: The request was not successful"
                            }
                        } else {
                            apiInterface.errorBody()?.let {
                                //                  val errorBody = JSONObject(it.string())
                                messageToast =
                                    "Invalid login credentials. Please try again." // errorBody.getString("error")
                            }
                        }
                    } catch (e: Exception) {

                        messageToast = "Can't log in user, please check your internet"
                    }
                }
                .join()
            CoroutineScope(Dispatchers.Main).launch {
                progressDialog.dismiss()

                if (messageToast != "Login successful.") {
                    AlertDialog.Builder(context)
                        .setTitle("Login Status")
                        .setMessage(messageToast)
                        .setCancelable(true)
                        .setPositiveButton("OK") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .show()
                } else {
                    Toast.makeText(context, messageToast, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startPullingUserAlerts(context: Context) {

        val job1 = Job()
        CoroutineScope(Dispatchers.Main + job1).launch {
            val progressDialog = ProgressDialog(context)
//            progressDialog.setTitle("Please wait..")
            progressDialog.setMessage("Please wait...")
            progressDialog.setCanceledOnTouchOutside(false)
            progressDialog.show()

            var messageToast = ""
            val job = Job()
            CoroutineScope(Dispatchers.IO + job)
                .launch {
                    val formatter = FormatterClass()
                    val apiService =
                        RetrofitBuilder.getRetrofit(ALERTS_BASE_URL).create(Interface::class.java)
                    try {

                        val token = formatter.getSharedPref("access_token", context)
                        if (token != null) {
                            val apiInterface = apiService.pullUserAlerts("Bearer $token")
                            if (apiInterface.isSuccessful) {

                                val statusCode = apiInterface.code()
                                val body = apiInterface.body()

                                if (statusCode == 200 || statusCode == 201) {

                                } else {
                                    messageToast = "Error: The request was not successful"
                                }
                            } else {
                                apiInterface.errorBody()?.let {
                                    messageToast =
                                        "Invalid login credentials. Please try again."
                                }
                            }
                        }
                    } catch (e: Exception) {
                        messageToast = "Cannot login user.."
                    }
                }
                .join()
            CoroutineScope(Dispatchers.Main).launch {
                progressDialog.dismiss()
                Toast.makeText(context, messageToast, Toast.LENGTH_LONG).show()
            }
        }
    }


    private fun getUserDetails(viewModel: SyncFragmentViewModel, context: Context) {

        CoroutineScope(Dispatchers.IO).launch {
            val formatter = FormatterClass()
            val apiService =
                RetrofitBuilder.getRetrofit(BASE_AUTH_URL).create(Interface::class.java)
            try {
                val token = formatter.getSharedPref("access_token", context)
                if (token != null) {
                    val apiInterface = apiService.getUserInfo("Bearer $token")
                    if (apiInterface.isSuccessful) {
                        viewModel.triggerOneTimeSync()
                        val statusCode = apiInterface.code()
                        val body = apiInterface.body()
                        if (statusCode == 200 || statusCode == 201) {
                            if (body != null) {
                                val user = body.user
                                saveUserInformation(user, context)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun saveUserInformation(user: User, context: Context) {
        val formatter = FormatterClass()
        val location = user.locationInfo
        val userDetails =
            mapOf(
                "firstName" to user.firstName,
                "lastName" to user.lastName,
                "role" to user.role,
                "id" to user.id,
                "fhirPractitionerId" to user.fhirPractitionerId,
                "practitionerRole" to user.practitionerRole,
                "idNumber" to user.idNumber,
                "fullNames" to user.fullNames,
                "phone" to (user.phone ?: ""),
                "email" to user.email,
                "facility" to location?.facility,
                "facilityName" to location?.facilityName,
                "ward" to location?.ward,
                "wardName" to location?.wardName,
                "subCounty" to location?.subCounty,
                "subCountyName" to location?.subCountyName,
                "county" to location?.county,
                "countyName" to location?.countyName,
                "country" to location?.country,
                "countryName" to location?.countryName
            )

        var counter = 0
        userDetails.forEach { (key, value) ->
            counter++
            formatter.saveSharedPref(key, "$value", context)
        }
        // Optional debug log
        Log.d("UserPrefs", "User info saved to SharedPreferences: $userDetails")
    }

    fun getResetPassword(context: Context, dbResetPasswordData: DbResetPasswordData) = runBlocking {
        resetPassword(context, dbResetPasswordData)
    }

    private suspend fun resetPassword(
        context: Context,
        dbResetPasswordData: DbResetPasswordData
    ): Pair<Int, String> {

        var messageToast = ""
        var messageCode = 400

        val formatter = FormatterClass()

        val apiService = RetrofitBuilder.getRetrofit(BASE_AUTH_URL).create(Interface::class.java)
        try {
            val idNumber = dbResetPasswordData.idNumber
            val email = dbResetPasswordData.email

            val apiInterface = apiService.resetPassword(idNumber, email)
            if (apiInterface.isSuccessful) {

                val statusCode = apiInterface.code()
                val body = apiInterface.body()

                messageCode = statusCode

                messageToast =
                    if (statusCode == 200 || statusCode == 201) {
                        body?.response ?: "Cannot reset user password!"
                    } else {
                        "Cannot reset user password!!"
                    }
            } else {
                // Parse the error response
                val errorResponse = parseError(apiInterface)
                messageToast = errorResponse?.error ?: "Cannot reset user password! Try again"
            }
        } catch (e: Exception) {

            messageToast = "Cannot reset user password.."
        }
        return Pair(messageCode, messageToast)
    }

    // Parse error response using Gson
    private fun parseError(response: Response<*>): DbResponseError? {
        return try {
            response.errorBody()?.let {
                val gson = Gson()
                val type = object : TypeToken<DbResponseError>() {}.type
                gson.fromJson(it.charStream(), type)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun setPassword(context: Context, dbSetPasswordReq: DbSetPasswordReq) = runBlocking {
        setPasswordBac(context, dbSetPasswordReq)
    }

    private suspend fun setPasswordBac(
        context: Context,
        dbSetPasswordReq: DbSetPasswordReq
    ): Pair<Int, String> {

        var messageToast = ""
        var messageCode = 400

        val formatter = FormatterClass()

        val apiService = RetrofitBuilder.getRetrofit(BASE_AUTH_URL).create(Interface::class.java)
        try {

            val apiInterface = apiService.setNewPassword(dbSetPasswordReq)
            if (apiInterface.isSuccessful) {

                val statusCode = apiInterface.code()
                val body = apiInterface.body()
                messageCode = statusCode

                messageToast =
                    if (statusCode == 200 || statusCode == 201) {
                        if (body != null) {
                            "Password Reset was successful.."
                        } else {
                            "Password Reset was not successful. Try again later"
                        }
                    } else {
                        "The request was not successful. Try again!"
                    }
            } else {
                // Parse the error response
                val errorResponse = parseError(apiInterface)
                messageToast = errorResponse?.error ?: "Cannot reset user password! Try again"
            }
        } catch (e: Exception) {


            messageToast = "Cannot set new password.."
        }
        return Pair(messageCode, messageToast)
    }
}