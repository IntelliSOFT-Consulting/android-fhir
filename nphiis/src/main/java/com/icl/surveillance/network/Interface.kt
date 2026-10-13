package com.icl.surveillance.network

import com.icl.surveillance.models.AuthTokenResponse
import com.icl.surveillance.models.DbResetPassword
import com.icl.surveillance.models.DbSetPasswordReq
import com.icl.surveillance.models.DbSignIn
import com.icl.surveillance.models.DbSignInResponse
import com.icl.surveillance.models.FCMToken
import com.icl.surveillance.models.FhirBundle
import com.icl.surveillance.models.NotificationResponse
import com.icl.surveillance.models.RefreshToken
import com.icl.surveillance.models.SetNewPasswordReq
import com.icl.surveillance.models.UserResponse
import com.icl.surveillance.utils.Constants.BASE_URL
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap
import retrofit2.http.Url

interface Interface {

    @POST("provider/login")
    suspend fun signInUser(@Body dbSignIn: DbSignIn): Response<DbSignInResponse>

    @POST("provider/refresh_token")
    suspend fun refreshToken(@Body data: RefreshToken): Response<AuthTokenResponse>

    @GET
    suspend fun fetchBundle(
        @Url url: String,
        @Header("Authorization") token: String
    ): FhirBundle

    @POST("notifications/config")
    @Headers("Content-Type: application/json")
    suspend fun updateFCMToken(
        @Body body: FCMToken,
        @Header("Authorization") token: String
    ): Response<Any>

    @GET("provider/me")
    suspend fun getUserInfo(
        @Header("Authorization") token: String
    ): Response<UserResponse>

    @GET("notifications")
    suspend fun pullUserAlerts(
        @Header("Authorization") token: String
    ): Response<NotificationResponse>

    @GET("provider/reset-password")
    suspend fun resetPassword(
        @Query("idNumber") idNumber: String,
        @Query("email", encoded = true) email: String
    ): Response<DbResetPassword>

    @POST("provider/reset-password")
    suspend fun setNewPassword(@Body dbSetPasswordReq: DbSetPasswordReq): Response<Any>

    @POST("provider/reset-password")
    suspend fun setPassword(@Body dbSetPasswordReq: SetNewPasswordReq): Response<Any>

    /** Client Registry patient search. See Constants.CLIENT_LOOKUP_URL. Raw body: logged, then parsed. */
    @GET
    @Headers("Accept: application/json")
    suspend fun lookupClient(
        @Url url: String,
        @QueryMap params: Map<String, String>,
        @Header("Authorization") token: String
    ): Response<okhttp3.ResponseBody>

    /** DHA middleware access token (client credentials). See Constants.DHA_TOKEN_URL. */
    @FormUrlEncoded
    @POST
    @Headers("Accept: application/json")
    suspend fun dhaToken(
        @Url url: String,
        @Field("client_id") clientId: String,
        @Field("client_secret") clientSecret: String
    ): Response<okhttp3.ResponseBody>

    /**
     * IPRS person lookup (SHA Health ID): GET with Basic auth. See Constants.IPRS_LOOKUP_URL.
     * e.g. GET https://healthid.sha.go.ke/api/v1/lookup/persons?id_number=32645167&type=citizen
     *      Authorization: Basic base64(username:password)
     */
    @GET
    @Headers("Accept: application/json")
    suspend fun lookupIprs(
        /** Full URL including the query, see ClientLookupRepository.iprsUrl. */
        @Url url: String,
        /** Basic auth (Constants.IPRS_LOOKUP_USERNAME / PASSWORD), not the app's bearer token. */
        @Header("Authorization") basicAuth: String
    ): Response<okhttp3.ResponseBody>
}
