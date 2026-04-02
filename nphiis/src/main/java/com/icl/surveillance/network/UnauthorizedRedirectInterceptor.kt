package com.icl.surveillance.network

import android.content.Context
import okhttp3.Interceptor
import okhttp3.Response

class UnauthorizedRedirectInterceptor(
    private val context: Context
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        val hasBearerToken =
            request.header("Authorization")?.startsWith("Bearer ", ignoreCase = true) == true

        if (response.code == 401 && hasBearerToken) {
            SessionExpiryHandler.handleUnauthorized(context)
        }

        return response
    }
}
