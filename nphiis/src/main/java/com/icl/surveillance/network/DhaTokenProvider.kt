package com.icl.surveillance.network

import com.google.gson.JsonObject
import com.google.gson.Gson
import com.icl.surveillance.utils.Constants
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Access token for the DHA middleware (Client Registry), from Constants.DHA_TOKEN_URL with the
 * client credentials. Kept in memory and reused until shortly before it expires.
 * Logcat tag: DHA (the token itself is never logged).
 */
object DhaTokenProvider {

    private const val LOG = "DHA"

    /** Renew this long before the stated expiry. */
    private const val EXPIRY_MARGIN_MS = 60_000L

    /** Used when the reply does not say how long the token lasts. */
    private const val DEFAULT_LIFETIME_S = 300L

    private val lock = Mutex()
    private var token: String? = null
    private var expiresAtMs = 0L

    /** A valid token, fetching a new one when needed; null when DHA refuses or cannot be reached. */
    suspend fun token(forceRefresh: Boolean = false): String? = lock.withLock {
        val cached = token
        if (!forceRefresh && cached != null && System.currentTimeMillis() < expiresAtMs) {
            return@withLock cached
        }
        fetch()
    }

    fun clear() {
        token = null
        expiresAtMs = 0L
    }

    private suspend fun fetch(): String? {
        Timber.tag(LOG).d("POST %s (client %s)", Constants.DHA_TOKEN_URL, Constants.DHA_CLIENT_ID)
        val api = RetrofitBuilder.getExternalRetrofit(Constants.DHA_BASE_URL).create(Interface::class.java)
        val response = api.dhaToken(Constants.DHA_TOKEN_URL, Constants.DHA_CLIENT_ID, Constants.DHA_CLIENT_SECRET)
        val raw = if (response.isSuccessful) response.body()?.string() else response.errorBody()?.string()
        Timber.tag(LOG).d("Token HTTP %d", response.code())
        if (!response.isSuccessful) {
            Timber.tag(LOG).w("Token refused: %s", raw)
            clear()
            return null
        }
        val json = runCatching { Gson().fromJson(raw, JsonObject::class.java) }.getOrNull()
        // { access_token, expires_in } at the top level or inside "data".
        val body = json?.getAsJsonObject("data")?.takeIf { it.has("access_token") || it.has("token") } ?: json
        val access = body?.str("access_token") ?: body?.str("token") ?: body?.str("accessToken")
        if (access.isNullOrBlank()) {
            // Show the reply's shape without the token values.
            Timber.tag(LOG).w("No access token in reply; fields: %s", json?.keySet())
            clear()
            return null
        }
        val lifetimeS = body?.get("expires_in")?.takeIf { it.isJsonPrimitive }
            ?.let { runCatching { it.asLong }.getOrNull() } ?: DEFAULT_LIFETIME_S
        token = access
        expiresAtMs = System.currentTimeMillis() + lifetimeS * 1000 - EXPIRY_MARGIN_MS
        Timber.tag(LOG).d("Token OK (%s, expires in %ds)", body?.str("token_type") ?: "Bearer", lifetimeS)
        return access
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
