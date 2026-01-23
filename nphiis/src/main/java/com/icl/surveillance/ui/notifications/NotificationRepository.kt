package com.icl.surveillance.ui.notifications

import android.content.Context
import com.icl.surveillance.models.NotificationResponse
import com.icl.surveillance.network.Interface
import com.icl.surveillance.network.RetrofitBuilder
import com.icl.surveillance.utils.Constants.ALERTS_BASE_URL
import com.icl.surveillance.utils.FormatterClass

class NotificationRepository {
    suspend fun getNotifications(context: Context): NotificationResponse? {

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

                    return if (statusCode == 200 || statusCode == 201) {

                        body
                    } else {
                        null
                    }
                } else {
                    return null
                }
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }
}