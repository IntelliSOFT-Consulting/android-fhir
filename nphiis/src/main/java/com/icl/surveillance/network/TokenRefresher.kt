package com.icl.surveillance.network

import android.content.Context
import com.icl.surveillance.fhir.FhirApplication
import com.icl.surveillance.utils.NetworkUtils
import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * Keeps the access token usable while the device is online: refreshes it when it has expired
 * or is about to ([REFRESH_MARGIN_SECONDS]), so a sync never starts with a token the server
 * will reject. Callers at the same time (sync, periodic worker, reconnect) share one refresh.
 */
object TokenRefresher {
    const val TAG = "TOKEN_REFRESH"

    /** Refresh this long before the token's `exp`, so a long sync does not outlive it. */
    const val REFRESH_MARGIN_SECONDS = 5 * 60L

    private val lock = Mutex()

    /**
     * Makes sure the access token is valid for at least [REFRESH_MARGIN_SECONDS], refreshing it
     * when online. True when the token is (now) good; false when offline, logged out, or the
     * refresh failed.
     */
    suspend fun ensureFresh(context: Context, force: Boolean = false): Boolean = lock.withLock {
        val appContext = context.applicationContext
        val token = FhirApplication.currentAccessToken() ?: TokenStore.getAccessToken(appContext)
        if (!force && token != null && !isExpiringSoon(token)) return true
        if (TokenStore.getRefreshToken(appContext).isNullOrBlank()) return false
        if (!NetworkUtils.isInternetAvailable(appContext)) return false

        AuthRepository(appContext).refreshToken().also { refreshed ->
            Timber.tag(TAG).d("Token refresh %s", if (refreshed) "succeeded" else "failed")
        }
    }

    /**
     * True when [token] (a JWT) expires within [marginSeconds] of [nowSeconds], or its expiry
     * cannot be read.
     */
    fun isExpiringSoon(
        token: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
        marginSeconds: Long = REFRESH_MARGIN_SECONDS,
    ): Boolean {
        val exp = expiryOf(token) ?: return true
        return exp - nowSeconds <= marginSeconds
    }

    /** The `exp` claim of JWT [token], in epoch seconds. */
    fun expiryOf(token: String): Long? = runCatching {
        val payload = token.split(".").getOrNull(1) ?: return null
        val json = String(Base64.getUrlDecoder().decode(payload.trimEnd('=')), Charsets.UTF_8)
        Regex(""""exp"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toLong()
    }.getOrNull()
}
