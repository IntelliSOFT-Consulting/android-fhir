package com.icl.surveillance.monitor

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.icl.surveillance.BuildConfig
import com.icl.surveillance.utils.Constants.BASE_URL
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.hl7.fhir.r4.model.Resource
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class NetworkModule {


    fun provideFhirDataSource(): FhirDataSource {
        val clientBuilder = OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .connectTimeout(2, TimeUnit.MINUTES)
        if (BuildConfig.DEBUG) {
            val interceptor = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            }
            clientBuilder.addInterceptor(interceptor)
        }
        val client = clientBuilder.build()


        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(createGson()))
            .build()

        return retrofit.create(FhirDataSource::class.java)
    }

    private fun createGson(): Gson {
        return GsonBuilder()
            .registerTypeAdapter(Resource::class.java, ResourceDeserializer())
            .setDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")
            .create()
    }
}
