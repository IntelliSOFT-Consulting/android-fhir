package com.icl.surveillance.fhir

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.hl7.fhir.r4.model.ResourceType

private val Context.dataStorage: DataStore<Preferences> by
preferencesDataStore(name = "surveilance_app_storage")

class DemoDataStore(private val context: Context) {

    suspend fun saveLastUpdatedTimestamp(resourceType: ResourceType, timestamp: String) {
        context.dataStorage.edit { pref ->
            pref[stringPreferencesKey(resourceType.name)] = timestamp
        }
    }

    suspend fun getLastUpdateTimestamp(resourceType: ResourceType): String? {
        return context.dataStorage.data.first()[stringPreferencesKey(resourceType.name)]
    }

    suspend fun clearAllTimestamps(resourceTypes: List<ResourceType>) {
        context.dataStorage.edit { pref ->
            resourceTypes.forEach { type ->
                pref.remove(stringPreferencesKey(type.name))
            }
        }
    }

}
