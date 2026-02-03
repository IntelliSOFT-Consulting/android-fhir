package com.icl.surveillance.network

import android.content.Context
import com.icl.surveillance.utils.Constants.BASE_AUTH_URL

class AuthRepository(private val context: Context) {

    suspend fun refreshToken(): Boolean {
        // 1. Read refresh token from DataStore / EncryptedSharedPrefs
        val refreshToken = TokenStore.getRefreshToken(context)
            ?: return false

        val apiService =
            RetrofitBuilder.getRetrofit(BASE_AUTH_URL).create(Interface::class.java)
        try {

//            val apiInterface = apiService.signInUser(dbSignIn)
//            if (apiInterface.isSuccessful) {
//
//                // 2. Call backend
//                val response = api.refreshToken(refreshToken)
//
//                // 3. Persist new token
//                TokenStore.saveTokens(
//                    context,
//                    accessToken = response.accessToken,
//                    refreshToken = response.refreshToken,
//                )
//            }
            return true
        } catch (e: Exception) {
            return false
        }
    }
}