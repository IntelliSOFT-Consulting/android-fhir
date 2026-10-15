package com.icl.surveillance.network

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.icl.surveillance.BuildConfig
import com.icl.surveillance.models.ClientLookupOutcome
import com.icl.surveillance.models.CrPatient
import com.icl.surveillance.models.LookupSource
import com.icl.surveillance.utils.Constants
import com.icl.surveillance.utils.FormatterClass
import com.icl.surveillance.utils.JsonDetails
import com.icl.surveillance.utils.LocationResolver
import com.icl.surveillance.utils.NetworkUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Uses the app session; registry selection and fallback belong to the backend. */
object ClientLookupRepository {
    /** Logcat tag of the validate-client call (debug builds only; the token is never logged). */
    private const val LOG = "VALIDATE_CLIENT"

    private fun log(message: String, vararg args: Any?) {
        if (BuildConfig.DEBUG) Timber.tag(LOG).d(message, *args)
    }

    /** Pretty-printed JSON for logcat, or the raw text when it is not JSON. */
    private fun pretty(raw: String?): String = runCatching {
        GsonBuilder().setPrettyPrinting().create()
            .toJson(Gson().fromJson(raw, com.google.gson.JsonElement::class.java))
    }.getOrDefault(raw.orEmpty())

    /** Logcat truncates long lines: log in chunks. */
    private fun logBody(label: String, body: String) {
        if (!BuildConfig.DEBUG) return
        val chunks = body.chunked(3000)
        chunks.forEachIndexed { i, chunk -> Timber.tag(LOG).d("%s (%d/%d)\n%s", label, i + 1, chunks.size, chunk) }
    }

    suspend fun lookup(
        context: Context,
        source: LookupSource,
        identifier: String,
        identificationType: String,
        minor: Boolean = false,
        dateOfBirth: String,
    ): ClientLookupOutcome = withContext(Dispatchers.IO) {
        if (source == LookupSource.CLIENT_REGISTRY && Constants.CLIENT_LOOKUP_USE_MOCK) {
            return@withContext fromCr(context, MockClientRegistry.find(identifier, crType(identificationType)))
        }
        if (!NetworkUtils.isInternetAvailable(context)) return@withContext ClientLookupOutcome.Offline
        try {
            val api = RetrofitBuilder.getExternalRetrofit(Constants.CLIENT_LOOKUP_BASE_URL, logRequests = false)
                .create(Interface::class.java)
            val params = registryParams(identifier, identificationType, dateOfBirth)
            var token = TokenStore.getAccessToken(context)
                ?: FormatterClass().getSharedPref("access_token", context)
            if (token.isNullOrBlank()) return@withContext ClientLookupOutcome.Failed("Sign in to continue.")
            log("--> GET %s params=%s", Constants.CLIENT_LOOKUP_URL, params)
            var reply = api.lookupClient(Constants.CLIENT_LOOKUP_URL, params, "Bearer $token")
            log("<-- %d %s (first try)", reply.code(), reply.message())
            if (reply.code() == 401) {
                log("401: refreshing the session token and retrying")
                reply.errorBody()?.close()
                if (!AuthRepository(context).refreshToken()) {
                    return@withContext ClientLookupOutcome.Failed("Sign in to continue.")
                }
                token = TokenStore.getAccessToken(context)
                if (token.isNullOrBlank()) return@withContext ClientLookupOutcome.Failed("Sign in to continue.")
                reply = api.lookupClient(Constants.CLIENT_LOOKUP_URL, params, "Bearer $token")
                log("<-- %d %s (after refresh)", reply.code(), reply.message())
                if (reply.code() == 401) SessionExpiryHandler.handleUnauthorized(context)
            }
            if (!reply.isSuccessful) {
                val error = reply.errorBody()?.use { it.string() }
                logBody("<-- ${reply.code()} error body", pretty(error))
                return@withContext if (reply.code() == 404) ClientLookupOutcome.NotFound
                else ClientLookupOutcome.Failed("Search could not be completed.")
            }
            val raw = reply.body()?.use { it.string() }
            logBody("<-- ${reply.code()} response body", pretty(raw))
            val record = registryRecord(raw)
            if (record == null) {
                log("Result: not found")
                return@withContext ClientLookupOutcome.NotFound
            }
            val result = fromCr(context, record)
            log("Result: %s", result.javaClass.simpleName)
            if (result == ClientLookupOutcome.NotFound)
                ClientLookupOutcome.Failed("Unrecognized search response.")
            else result
        } catch (e: CancellationException) {
            throw e
        } catch (e: NoNetworkException) {
            log("Offline: %s", e.message)
            ClientLookupOutcome.Offline
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Timber.tag(LOG).e(e, "Search failed: %s", e.message)
            ClientLookupOutcome.Failed("Search could not be completed.")
        }
    }

    internal fun registryParams(identifier: String, identificationType: String, dateOfBirth: String) =
        mapOf(
            "identification_number" to identifier.trim(),
            "identification_type" to crType(identificationType),
            "date_of_birth" to dateOfBirth,
        )

    /** The combined endpoint returns {status, found, source, patient}. */
    internal fun registryRecord(raw: String?): JsonObject? {
        require(!raw.isNullOrBlank()) { "Empty response" }
        val root = Gson().fromJson(raw, JsonObject::class.java)
        require(root != null && root.get("status")?.asString == "success") { "Invalid response" }
        val found = root.get("found")
        require(found != null && found.isJsonPrimitive && found.asJsonPrimitive.isBoolean) { "Missing result status" }
        if (!found.asBoolean) return null
        val patient = root.get("patient")
        require(patient != null && patient.isJsonObject) { "Missing patient" }
        return patient.asJsonObject
    }
    private fun crType(code: String): String = Constants.CLIENT_LOOKUP_ID_TYPES[code] ?: code
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
