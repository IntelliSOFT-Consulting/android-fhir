package com.icl.surveillance.network

import android.content.Context
import com.icl.surveillance.models.ClientLookupOutcome
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.icl.surveillance.utils.JsonDetails
import com.icl.surveillance.models.CrPatient
import com.icl.surveillance.models.IprsLookupResponse
import com.icl.surveillance.models.LookupSource
import com.icl.surveillance.utils.Constants
import com.icl.surveillance.utils.LocationResolver
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

    /** Logcat tag for the Client Registry request and reply. */
    private const val CR_LOG = "CR"

    suspend fun lookup(
        context: Context,
        source: LookupSource,
        identifier: String,
        /** Case-form code, e.g. national-id; sent as the API's own value. */
        identificationType: String,
        /** Client Registry only: [identifier] is the parent's / guardian's ID; find their children. */
        minor: Boolean = false,
    ): ClientLookupOutcome = withContext(Dispatchers.IO) {
        if (source == LookupSource.CLIENT_REGISTRY && Constants.CLIENT_LOOKUP_USE_MOCK) {
            delay(800) // feel of a network call
            val raw = MockClientRegistry.find(identifier, crType(identificationType))
            return@withContext fromCr(context, raw)
        }
        if (!NetworkUtils.isInternetAvailable(context)) return@withContext ClientLookupOutcome.Offline
        try {
            when (source) {
                LookupSource.IPRS -> lookupIprs(identifier, identificationType)
                LookupSource.CLIENT_REGISTRY -> lookupCrThenIprs(
                    identificationType, minor,
                    crLookup = { lookupCr(context, identifier, identificationType, minor) },
                    iprsLookup = { lookupIprs(identifier, identificationType) },
                )
            }
        } catch (e: NoNetworkException) {
            ClientLookupOutcome.Offline
        } catch (e: Exception) {
            Timber.w(e, "Client lookup (%s) failed", source)
            ClientLookupOutcome.Failed("Could not reach ${source.label}. Try again.")
        }
    }

    internal suspend fun lookupCrThenIprs(
        identificationType: String,
        minor: Boolean,
        crLookup: suspend () -> ClientLookupOutcome,
        iprsLookup: suspend () -> ClientLookupOutcome,
    ): ClientLookupOutcome {
        val result = crLookup()
        if (result != ClientLookupOutcome.NotFound ||
            identificationType !in Constants.IPRS_LOOKUP_ID_TYPES
        ) return result
        val fallback = iprsLookup()
        return if (minor && fallback is ClientLookupOutcome.Found)
            ClientLookupOutcome.Dependents(fallback.client, emptyList(), fallback.source)
        else fallback
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

        val api = RetrofitBuilder.getExternalRetrofit(Constants.IPRS_LOOKUP_BASE_URL).create(Interface::class.java)
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

    /**
     * Client Registry search on the DHA middleware, with a DHA access token (DhaTokenProvider).
     * A 401 gets one retry with a fresh token. Logcat tag: CR.
     */
    private suspend fun lookupCr(
        context: Context,
        identifier: String,
        identificationType: String,
        minor: Boolean,
    ): ClientLookupOutcome {
        val params = mapOf(
            Constants.CLIENT_LOOKUP_ID_PARAM to identifier,
            Constants.CLIENT_LOOKUP_TYPE_PARAM to crType(identificationType),
        )
        val api = RetrofitBuilder.getExternalRetrofit(Constants.CLIENT_LOOKUP_BASE_URL).create(Interface::class.java)

        var response: Response<okhttp3.ResponseBody>? = null
        for (attempt in 1..2) {
            val token = DhaTokenProvider.token(forceRefresh = attempt > 1)
                ?: return ClientLookupOutcome.Failed("Could not sign in to the Client Registry (DHA token). Try again.")
            Timber.tag(CR_LOG).d("GET %s %s", Constants.CLIENT_LOOKUP_URL, params)
            response = api.lookupClient(Constants.CLIENT_LOOKUP_URL, params, "Bearer $token")
            if (response.code() != 401) break
            Timber.tag(CR_LOG).w("401 with the cached token; fetching a new one")
        }
        val reply = response!!
        val raw = if (reply.isSuccessful) reply.body()?.string() else reply.errorBody()?.string()
        Timber.tag(CR_LOG).d("Sent: %s %s", reply.raw().request.method, reply.raw().request.url)
        Timber.tag(CR_LOG).d("HTTP %d %s", reply.code(), reply.message())
        Timber.tag(CR_LOG).d("Body: %s", raw ?: "<empty>")
        if (isCrNoMatch(reply.code(), raw)) return ClientLookupOutcome.NotFound
        failure(reply, LookupSource.CLIENT_REGISTRY)?.let {
            Timber.tag(CR_LOG).w("Outcome: %s", it)
            return it
        }
        val record = try {
            Gson().fromJson(raw, JsonObject::class.java)
        } catch (e: Exception) {
            Timber.tag(CR_LOG).e(e, "Could not read the response body")
            return ClientLookupOutcome.Failed("The Client Registry sent a reply the app could not read.")
        }
        return fromCr(context, record).also { Timber.tag(CR_LOG).d("Outcome: %s", it) }
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

    internal fun isCrNoMatch(status: Int, raw: String?): Boolean {
        if (status != 400) return false
        val message = runCatching {
            Gson().fromJson(raw, JsonObject::class.java)?.get("message")?.asString
        }.getOrNull()
        return message == "failed to get patient record: zero results found in client registry fetch patient search response"
    }
    /** NotFound for 404, Failed for other errors, null when the reply can be read. */
    private fun failure(response: Response<*>, source: LookupSource): ClientLookupOutcome? = when {
        response.code() == 404 -> ClientLookupOutcome.NotFound
        response.code() == 401 || response.code() == 403 ->
            ClientLookupOutcome.Failed("${source.label} refused the request (${response.code()}). Check the lookup credentials.")
        !response.isSuccessful ->
            ClientLookupOutcome.Failed("${source.label} returned an error (${response.code()}).")
        else -> null
    }

    /** App ID type code -> the CR's identification_type spelling (e.g. national-id -> "National ID"). */
    private fun crType(code: String): String = Constants.CLIENT_LOOKUP_ID_TYPES[code] ?: code

    /**
     * The CR reply as a household: the principal member and every dependant, each mapped to the
     * form's demographics and carrying every field received (JsonDetails) for display. The list is
     * shown for both adult and minor searches; the healthcare worker selects who the case is for.
     */
    private fun fromCr(context: Context, record: JsonObject?): ClientLookupOutcome {
        if (record == null) return ClientLookupOutcome.NotFound
        val gson = Gson()
        val principal = runCatching { gson.fromJson(record, CrPatient::class.java) }.getOrNull()
        if (principal == null || !principal.isRecord) return ClientLookupOutcome.NotFound
        val locations = LocationResolver.get(context)

        val groups = record.get("dependants")?.takeIf { it.isJsonArray }?.asJsonArray
        val children = groups?.flatMap { groupElement ->
            val group = groupElement.takeIf { it.isJsonObject }?.asJsonObject ?: return@flatMap emptyList()
            val relationship = group.get("relationship")?.takeIf { it.isJsonPrimitive }?.asString
            // The group's own fields (relationship, date added, total) head each dependant's details.
            val groupRows = JsonDetails.rows(group, skip = setOf("result"))
            val results = group.get("result")?.takeIf { it.isJsonArray }?.asJsonArray ?: return@flatMap emptyList()
            results.mapNotNull { element ->
                val raw = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val dependant = runCatching { gson.fromJson(raw, CrPatient::class.java) }.getOrNull()
                    ?.takeIf { it.isRecord } ?: return@mapNotNull null
                dependant.toDemographics(locations, guardian = principal, relationship = relationship)
                    .copy(recordDetails = groupRows + JsonDetails.rows(raw))
            }
        }.orEmpty()

        val dependantsSummary = groups?.mapNotNull { g ->
            val o = g.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val n = o.get("result")?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0
            "$n ${o.get("relationship")?.takeIf { it.isJsonPrimitive }?.asString ?: "dependant"}"
        }?.joinToString(", ")?.ifBlank { null }

        val person = principal.toDemographics(locations).copy(
            recordDetails = JsonDetails.rows(record, skip = setOf("dependants")) +
                listOf("Dependants" to (dependantsSummary ?: "None")),
        )
        return ClientLookupOutcome.Dependents(guardian = person, children = children, source = "CR")
    }
}
