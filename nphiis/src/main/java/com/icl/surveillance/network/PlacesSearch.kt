package com.icl.surveillance.network

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.icl.surveillance.BuildConfig
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

/**
 * Google Places API (New) over HTTPS: autocomplete + place details for the residence picker.
 *
 * Called directly (OkHttp) instead of through the Places SDK: the SDK needs a newer Guava than the
 * FHIR engine / datacapture modules allow. Uses the same key as the map (nphiis.mapsApiKey); for a
 * key restricted to Android apps the package name and signing-certificate SHA-1 are sent too.
 */
class PlacesSearch(private val context: Context) {

    data class Suggestion(val placeId: String, val primary: String, val secondary: String)

    data class PlaceDetails(
        val name: String,
        val address: String,
        val latitude: Double,
        val longitude: Double,
    )

    /** Bias towards an area (e.g. what is on screen). */
    data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double)

    private val key = BuildConfig.MAPS_API_KEY
    private val gson = Gson()

    /** One session per search: autocomplete calls + the details fetch that ends it. */
    private var sessionToken: String? = null

    val isAvailable: Boolean get() = key.isNotBlank()

    suspend fun autocomplete(query: String, bias: Bounds?): List<Suggestion> = withContext(Dispatchers.IO) {
        val token = sessionToken ?: UUID.randomUUID().toString().also { sessionToken = it }
        val body = JsonObject().apply {
            addProperty("input", query)
            addProperty("sessionToken", token)
            add("includedRegionCodes", JsonArray().apply { add("ke") })
            bias?.let { b ->
                add("locationBias", JsonObject().apply {
                    add("rectangle", JsonObject().apply {
                        add("low", latLng(b.south, b.west))
                        add("high", latLng(b.north, b.east))
                    })
                })
            }
        }
        val request = Request.Builder()
            .url("$BASE/places:autocomplete")
            .post(gson.toJson(body).toRequestBody(JSON))
            .applyHeaders(fieldMask = null)
            .build()
        val json = execute(request) ?: return@withContext emptyList()
        json.getAsJsonArray("suggestions")?.mapNotNull { element ->
            val prediction = element.asJsonObject.getAsJsonObject("placePrediction") ?: return@mapNotNull null
            val placeId = prediction.get("placeId")?.asString ?: return@mapNotNull null
            val format = prediction.getAsJsonObject("structuredFormat")
            Suggestion(
                placeId = placeId,
                primary = format?.getAsJsonObject("mainText")?.get("text")?.asString
                    ?: prediction.getAsJsonObject("text")?.get("text")?.asString.orEmpty(),
                secondary = format?.getAsJsonObject("secondaryText")?.get("text")?.asString.orEmpty(),
            )
        }.orEmpty()
    }

    suspend fun details(placeId: String): PlaceDetails? = withContext(Dispatchers.IO) {
        val url = "$BASE/places/$placeId".toHttpUrl().newBuilder()
            .apply { sessionToken?.let { addQueryParameter("sessionToken", it) } }
            .build()
        sessionToken = null // a session ends with the details request
        val request = Request.Builder()
            .url(url)
            .get()
            .applyHeaders(fieldMask = "id,displayName,formattedAddress,location")
            .build()
        val json = execute(request) ?: return@withContext null
        val location = json.getAsJsonObject("location") ?: return@withContext null
        PlaceDetails(
            name = json.getAsJsonObject("displayName")?.get("text")?.asString.orEmpty(),
            address = json.get("formattedAddress")?.asString.orEmpty(),
            latitude = location.get("latitude")?.asDouble ?: return@withContext null,
            longitude = location.get("longitude")?.asDouble ?: return@withContext null,
        )
    }

    private fun execute(request: Request): JsonObject? = runCatching {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string()
            if (!response.isSuccessful) {
                Timber.tag(TAG).w("Places %d: %s", response.code, text)
                return null
            }
            gson.fromJson(text, JsonObject::class.java)
        }
    }.onFailure { Timber.tag(TAG).w(it, "Places request failed") }.getOrNull()

    private fun Request.Builder.applyHeaders(fieldMask: String?): Request.Builder = apply {
        header("X-Goog-Api-Key", key)
        header("X-Android-Package", context.packageName)
        signingSha1()?.let { header("X-Android-Cert", it) }
        fieldMask?.let { header("X-Goog-FieldMask", it) }
    }

    private fun latLng(lat: Double, lng: Double) = JsonObject().apply {
        addProperty("latitude", lat)
        addProperty("longitude", lng)
    }

    /** SHA-1 of the app's signing certificate (hex, upper case), as Google key restrictions use. */
    @SuppressLint("PackageManagerGetSignatures")
    @Suppress("DEPRECATION")
    private fun signingSha1(): String? = cachedSha1 ?: runCatching {
        val pm = context.packageManager
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        } ?: return null
        MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
            .joinToString("") { "%02X".format(it) }
    }.getOrNull().also { cachedSha1 = it }

    companion object {
        private const val TAG = "RESIDENCE_PICKER"
        private const val BASE = "https://places.googleapis.com/v1"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        @Volatile
        private var cachedSha1: String? = null

        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}
