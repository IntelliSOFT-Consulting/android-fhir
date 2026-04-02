package com.icl.surveillance.network

import android.content.Context
import com.icl.surveillance.models.RefreshToken
import com.icl.surveillance.utils.Constants.BASE_AUTH_URL
import com.icl.surveillance.utils.NetworkUtils

class AuthRepository(private val context: Context) {

    suspend fun refreshToken(): Boolean {
        if (!NetworkUtils.isInternetAvailable(context)) {
            return false
        }

        val refreshToken = TokenStore.getRefreshToken(context)
            ?: return false

        val apiService =
            RetrofitBuilder.getRetrofit(BASE_AUTH_URL).create(Interface::class.java)
        try {

            val apiInterface = apiService.refreshToken(RefreshToken(refresh_token = refreshToken))
            if (apiInterface.isSuccessful) {
                val statusCode = apiInterface.code()
                val body = apiInterface.body()

                if (statusCode == 200 || statusCode == 201) {
                    if (body != null) {
                        TokenStore.saveTokens(
                            context,
                            accessToken = body.access_token,
                            refreshToken = body.refresh_token,
                        )
                        SessionExpiryHandler.markSessionActive()
                        return true
                    }
                }
                return false
            }
            if (apiInterface.code() == 401) {
                SessionExpiryHandler.handleUnauthorized(context)
            }
            return false
        } catch (e: Exception) {
            return false
        }
    }
}
