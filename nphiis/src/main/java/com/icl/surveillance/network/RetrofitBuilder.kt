package com.icl.surveillance.network

import android.util.Log
import com.icl.surveillance.BuildConfig
import com.icl.surveillance.fhir.FhirApplication
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.TlsVersion
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitBuilder {

    fun getRetrofit(baseUrl: String): Retrofit = build(baseUrl, signOutOn401 = true)

    /**
     * For outside services (DHA Client Registry, SHA / IPRS). Same as [getRetrofit] but a 401 from
     * them does not sign the user out of the app: their tokens are not the app's session.
     */
    fun getExternalRetrofit(baseUrl: String): Retrofit = build(baseUrl, signOutOn401 = false)

    private fun build(baseUrl: String, signOutOn401: Boolean): Retrofit {
        val builder =
            OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(5, TimeUnit.MINUTES)
                .connectTimeout(2, TimeUnit.MINUTES)
                // Key change: allow compatible TLS for older / OEM devices
                .connectionSpecs(
                    listOf(
                        ConnectionSpec.MODERN_TLS,
                        ConnectionSpec.COMPATIBLE_TLS
                    )
                )

        builder.addInterceptor(OfflineRequestInterceptor(FhirApplication.appContext()))

        val interceptor =
            HttpLoggingInterceptor { message ->
                Log.e("API_RELEASE", message)
            }.apply {
                level = HttpLoggingInterceptor.Level.BODY
                // Never print credentials (bearer tokens, IPRS Basic auth) to logcat.
                redactHeader("Authorization")
            }
        builder.addInterceptor(interceptor)
        if (signOutOn401) builder.addInterceptor(UnauthorizedRedirectInterceptor(FhirApplication.appContext()))


        val client = builder.build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
}
