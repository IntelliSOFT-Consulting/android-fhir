package com.icl.surveillance.monitor

import com.icl.surveillance.utils.Constants.BASE_URL
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface FhirDataSource {

    @PUT("Patient/{id}")
    @Headers("Content-Type: application/json")
    suspend fun createPatient(
        @Path("id") id: String,
        @Body payload: RequestBody
    ): Response<Any>

    @PUT("Observation/{id}")
    @Headers("Content-Type: application/json")
    suspend fun createObservation(
        @Path("id") id: String,
        @Body payload: RequestBody
    ): Response<Any>

    @PUT("Encounter/{id}")
    @Headers("Content-Type: application/json")
    suspend fun createEncounter(
        @Path("id") id: String,
        @Body payload: RequestBody
    ): Response<Any>

    @PUT("QuestionnaireResponse/{id}")
    @Headers("Content-Type: application/json")
    suspend fun createQuestionnaireResponse(
        @Path("id") id: String,
        @Body payload: RequestBody
    ): Response<Any>

    @PUT("MeasureReport/{id}")
    suspend fun createMeasureReport(
        @Path("id") id: String,
        @Body payload: RequestBody
    ): Response<Any>

    @POST(BASE_URL)
    @Headers("Content-Type: application/json")
    suspend fun sendBundleToServer(
        @Body payload: RequestBody
    ): Response<LocalBundleResponse>
}