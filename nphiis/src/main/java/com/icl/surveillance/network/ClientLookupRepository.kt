package com.icl.surveillance.network

import android.content.Context
import com.icl.surveillance.models.ClientLookupOutcome
import com.google.gson.Gson
import com.icl.surveillance.models.ClientLookupResponse
import com.icl.surveillance.models.IprsLookupResponse
import com.icl.surveillance.models.LookupSource
import com.icl.surveillance.utils.Constants
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.utils.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.util.Base64
import java.net.URLEncoder
import retrofit2.Response
import timber.log.Timber

/**
 * Looks a person up by identification number in IPRS (SHA Health ID) or the Client Registry.
 * Both replies are turned into the form's demographics (ClientDemographics).
 */
object ClientLookupRepository {

    /** Logcat tag for the IPRS request and reply. */
    private const val IPRS_LOG = "IPRS"

    suspend fun lookup(
        context: Context,
        source: LookupSource,
        identifier: String,
        /** Case-form code, e.g. national-id; sent as the API's own value. */
        identificationType: String,
    ): ClientLookupOutcome = withContext(Dispatchers.IO) {
        if (source == LookupSource.CLIENT_REGISTRY && Constants.CLIENT_LOOKUP_USE_MOCK) {
            delay(800) // feel of a network call
            return@withContext fromCr(MockClientRegistry.search(identifier, identificationType))
        }
        if (!NetworkUtils.isInternetAvailable(context)) return@withContext ClientLookupOutcome.Offline
        try {
            when (source) {
                LookupSource.IPRS -> lookupIprs(identifier, identificationType)
                LookupSource.CLIENT_REGISTRY -> lookupCr(context, identifier, identificationType)
            }
        } catch (e: NoNetworkException) {
            ClientLookupOutcome.Offline
        } catch (e: Exception) {
            Timber.w(e, "Client lookup (%s) failed", source)
            ClientLookupOutcome.Failed("Could not reach ${source.label}. Try again.")
        }
    }

    private suspend fun lookupIprs(identifier: String, identificationType: String): ClientLookupOutcome {
        val type = Constants.IPRS_LOOKUP_ID_TYPES[identificationType]
            ?: return ClientLookupOutcome.Failed("IPRS only holds national IDs. Use the Client Registry for this ID type.")
        val useFixedHeader = Constants.IPRS_LOOKUP_BASIC_AUTH.isNotBlank()
        if (!useFixedHeader &&
            (Constants.IPRS_LOOKUP_USERNAME.isBlank() || Constants.IPRS_LOOKUP_PASSWORD.isBlank())
        ) {
            Timber.tag(IPRS_LOG).w("Not called: IPRS username or password is empty in Constants")
            return ClientLookupOutcome.Failed("IPRS lookup is not set up on this build (missing credentials).")
        }
        val url = iprsUrl(identifier, type)
        // The credentials themselves are never logged.
        Timber.tag(IPRS_LOG).d("GET %s (Basic auth from %s)",
            url,
            if (useFixedHeader) "IPRS_LOOKUP_BASIC_AUTH" else "username/password")

        val api = RetrofitBuilder.getRetrofit(Constants.IPRS_LOOKUP_BASE_URL).create(Interface::class.java)
        val response = try {
            api.lookupIprs(
                url,
                if (useFixedHeader) Constants.IPRS_LOOKUP_BASIC_AUTH.trim()
                else basicAuth(Constants.IPRS_LOOKUP_USERNAME, Constants.IPRS_LOOKUP_PASSWORD),
            )
        } catch (e: Exception) {
            Timber.tag(IPRS_LOG).e(e, "Request failed before a response")
            throw e
        }

        val raw = if (response.isSuccessful) response.body()?.string() else response.errorBody()?.string()
        Timber.tag(IPRS_LOG).d("Sent: %s %s", response.raw().request.method, response.raw().request.url)
        Timber.tag(IPRS_LOG).d("HTTP %d %s", response.code(), response.message())
        Timber.tag(IPRS_LOG).d("Body: %s", raw ?: "<empty>")

        failure(response, LookupSource.IPRS)?.let {
            Timber.tag(IPRS_LOG).w("Outcome: %s", it)
            return it
        }
        val parsed = try {
            Gson().fromJson(raw, IprsLookupResponse::class.java)
        } catch (e: Exception) {
            Timber.tag(IPRS_LOG).e(e, "Could not read the response body")
            return ClientLookupOutcome.Failed("IPRS sent a reply the app could not read.")
        }
        val person = parsed?.data ?: run {
            Timber.tag(IPRS_LOG).d("Outcome: not found (no data)")
            return ClientLookupOutcome.NotFound
        }
        val client = person.toDemographics()
        Timber.tag(IPRS_LOG).d("Parsed: %s", person)
        Timber.tag(IPRS_LOG).d("Form demographics: %s", client)
        return if (client.fullName.isBlank()) ClientLookupOutcome.NotFound
        else ClientLookupOutcome.Found(client, "IPRS")
    }

    private suspend fun lookupCr(
        context: Context,
        identifier: String,
        identificationType: String,
    ): ClientLookupOutcome {
        val token = FormatterClass().getSharedPref("access_token", context)
            ?: return ClientLookupOutcome.Failed("You are not signed in. Sign in again and retry.")
        val api = RetrofitBuilder.getRetrofit(Constants.CLIENT_LOOKUP_BASE_URL).create(Interface::class.java)
        val response = api.lookupClient(
            Constants.CLIENT_LOOKUP_URL,
            mapOf(
                Constants.CLIENT_LOOKUP_ID_PARAM to identifier,
                Constants.CLIENT_LOOKUP_TYPE_PARAM to
                    (Constants.CLIENT_LOOKUP_ID_TYPES[identificationType] ?: identificationType),
            ),
            "Bearer $token",
        )
        failure(response, LookupSource.CLIENT_REGISTRY)?.let { return it }
        return fromCr(response.body())
    }

    /**
     * e.g. https://healthid.sha.go.ke/api/v1/lookup/persons?id_number=32645167&type=citizen
     */
    private fun iprsUrl(idNumber: String, type: String): String =
        Constants.IPRS_LOOKUP_URL +
            "?${Constants.IPRS_LOOKUP_ID_PARAM}=${URLEncoder.encode(idNumber.trim(), "UTF-8")}" +
            "&${Constants.IPRS_LOOKUP_TYPE_PARAM}=${URLEncoder.encode(type, "UTF-8")}"

    /** "Basic " + Base64("username:password"), UTF-8, no line breaks. */
    private fun basicAuth(username: String, password: String): String =
        "Basic " + Base64.encodeToString("$username:$password".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    /** NotFound for 404, Failed for other errors, null when the reply can be read. */
    private fun failure(response: Response<*>, source: LookupSource): ClientLookupOutcome? = when {
        response.code() == 404 -> ClientLookupOutcome.NotFound
        response.code() == 401 || response.code() == 403 ->
            ClientLookupOutcome.Failed("${source.label} refused the request (${response.code()}). Check the lookup credentials.")
        !response.isSuccessful ->
            ClientLookupOutcome.Failed("${source.label} returned an error (${response.code()}).")
        else -> null
    }

    private fun fromCr(body: ClientLookupResponse?): ClientLookupOutcome {
        val client = body?.client
        return if (body?.found == true && client != null) ClientLookupOutcome.Found(client, body.source ?: "CR")
        else ClientLookupOutcome.NotFound
    }
}
