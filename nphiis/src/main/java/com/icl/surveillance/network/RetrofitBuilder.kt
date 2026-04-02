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

    fun getRetrofit(baseUrl: String): Retrofit {
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
            }.apply { level = HttpLoggingInterceptor.Level.BODY }
        builder.addInterceptor(interceptor)
        builder.addInterceptor(UnauthorizedRedirectInterceptor(FhirApplication.appContext()))


        val client = builder.build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
}
