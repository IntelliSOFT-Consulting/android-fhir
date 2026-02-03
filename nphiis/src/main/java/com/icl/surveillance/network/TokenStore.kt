package com.icl.surveillance.network

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.icl.surveillance.utils.FormatterClass
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

object TokenStore {

    private val Context.dataStore by preferencesDataStore(
        name = "auth_tokens"
    )

    private val ACCESS_TOKEN = stringPreferencesKey("access_token")
    private val REFRESH_TOKEN = stringPreferencesKey("refresh_token")

    suspend fun getAccessToken(context: Context): String? {
        return context.dataStore.data
            .map { prefs -> prefs[ACCESS_TOKEN] }
            .firstOrNull()
    }

    suspend fun getRefreshToken(context: Context): String? {
        return context.dataStore.data
            .map { prefs -> prefs[REFRESH_TOKEN] }
            .firstOrNull()
    }

    suspend fun saveTokens(
        context: Context,
        accessToken: String,
        refreshToken: String,
    ) {
        context.dataStore.edit { prefs ->
            prefs[ACCESS_TOKEN] = accessToken
            prefs[REFRESH_TOKEN] = refreshToken
        }
        FormatterClass().saveSharedPref("access_token", accessToken, context)
        FormatterClass().saveSharedPref("refresh_token", refreshToken, context)
    }

    suspend fun clear(context: Context) {
        context.dataStore.edit { it.clear() }
    }

}