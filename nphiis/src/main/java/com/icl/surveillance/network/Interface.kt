package com.icl.surveillance.network

import com.icl.surveillance.models.DbResetPassword
import com.icl.surveillance.models.DbSetPasswordReq
import com.icl.surveillance.models.DbSignIn
import com.icl.surveillance.models.DbSignInResponse
import com.icl.surveillance.models.FCMToken
import com.icl.surveillance.models.FhirBundle
import com.icl.surveillance.models.NotificationResponse
import com.icl.surveillance.models.UserResponse
import com.icl.surveillance.utils.Constants.BASE_URL
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Url

interface Interface {

    @POST("provider/login")
    suspend fun signInUser(@Body dbSignIn: DbSignIn): Response<DbSignInResponse>

    @GET
    suspend fun fetchBundle(
        @Url url: String,
        @Header("Authorization") token: String,
    ): FhirBundle

    @POST("provider/fcm/token")
    @Headers("Content-Type: application/json")
    suspend fun updateFCMToken(
        @Body body: FCMToken,
        @Header("Authorization") token: String,
    ): Response<Any>

    @PUT("Patient/{id}")
    @Headers("Content-Type: application/json")
    suspend fun sendPatientToServer(
        @Path("id") id: String,
        @Body payload: RequestBody,
        @Header("Authorization") token: String,
    ): Response<Any>

    @POST(BASE_URL)
    @Headers("Content-Type: application/json")
    suspend fun sendBundleToServer(
        @Body payload: RequestBody,
        @Header("Authorization") token: String,
    ): Response<Any>

    @GET("provider/me")
    suspend fun getUserInfo(
        @Header("Authorization") token: String,
    ): Response<UserResponse>

    @GET("notifications")
    suspend fun pullUserAlerts(
        @Header("Authorization") token: String,
    ): Response<NotificationResponse>

    @GET("provider/reset-password")
    suspend fun resetPassword(
        @Query("idNumber") idNumber: String,
        @Query("email", encoded = true) email: String,
    ): Response<DbResetPassword>

    @POST("provider/reset-password")
    suspend fun setNewPassword(@Body dbSetPasswordReq: DbSetPasswordReq): Response<Any>
}