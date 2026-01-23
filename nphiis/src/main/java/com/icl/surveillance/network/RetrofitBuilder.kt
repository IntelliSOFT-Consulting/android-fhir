package com.icl.surveillance.network

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

    val interceptor = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }

    val builder =
        OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .connectTimeout(2, TimeUnit.MINUTES)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectionSpecs(
                listOf(
                    ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                        .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_3)
                        .build()
                )
            )
            .addInterceptor(interceptor)

    val trustAllCerts =
        arrayOf<TrustManager>(
            object : X509TrustManager {
              override fun checkClientTrusted(
                  chain: Array<java.security.cert.X509Certificate>,
                  authType: String,
              ) = Unit

              override fun checkServerTrusted(
                  chain: Array<java.security.cert.X509Certificate>,
                  authType: String,
              ) = Unit

              override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> =
                  arrayOf()
            }
        )

    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, trustAllCerts, java.security.SecureRandom())
    val sslSocketFactory = sslContext.socketFactory

    builder
        .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
        .hostnameVerifier { _, _ -> true }

    val client = builder.build()

    return Retrofit.Builder()
        .baseUrl(baseUrl)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
  }
}
