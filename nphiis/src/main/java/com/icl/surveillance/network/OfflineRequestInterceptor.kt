package com.icl.surveillance.network

import android.content.Context
import com.icl.surveillance.utils.NetworkUtils
import okhttp3.Interceptor
import okhttp3.Response

class OfflineRequestInterceptor(
    private val context: Context
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (!NetworkUtils.isInternetAvailable(context.applicationContext)) {
            throw NoNetworkException()
        }

        return chain.proceed(chain.request())
    }
}
