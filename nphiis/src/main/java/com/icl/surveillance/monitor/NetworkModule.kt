package com.icl.surveillance.monitor

import com.google.firebase.sessions.dagger.Provides
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.icl.surveillance.utils.Constants.BASE_URL
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.hl7.fhir.r4.model.Resource
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class NetworkModule {

    @Provides
    fun provideFhirDataSource(): FhirDataSource {
        val interceptor = HttpLoggingInterceptor()
        interceptor.level = HttpLoggingInterceptor.Level.BODY

        val client = OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .connectTimeout(2, TimeUnit.MINUTES)
            .addInterceptor(interceptor).build()


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