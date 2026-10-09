package com.icl.surveillance.fhir.forms

import android.content.Context
import android.util.Log
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.LocalChange
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.search.search
import com.icl.surveillance.utils.FormatterClass
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Resource
import timber.log.Timber

/**
 * Saving edits to records the app does not download (Observations, Encounters).
 *
 * The sync engine uploads an edit as a PATCH tied to the version this device last saw
 * (If-Match). When the server already holds a newer version (it updates some records after
 * receiving them), the server refuses the whole upload with HTTP 409 "Version N is not the most
 * recent version". Records that are downloaded are reconciled by the download step; these are
 * not, so the upload would fail on every sync.
 *
 * Instead such an edit is saved as a full replacement: the record is purged with its pending
 * changes and created again with the same id and the edited content, which uploads as a PUT
 * without a version check. The device's copy wins.
 */
object LocalWins {
    private const val TAG = "LocalWins"
    private const val DONE_PREF = "localWinsPendingUpdatesV1"
    private const val PAGE_SIZE = 200

    /** Saves [resource] (already stored locally) so its upload replaces the server copy. */
    suspend fun save(fhirEngine: FhirEngine, resource: Resource) {
        fhirEngine.withTransaction {
            purge(resource.resourceType, resource.logicalId, forcePurge = true)
            create(resource)
        }
    }

    /**
     * One-off repair for edits already queued as version-checked updates (the cause of HTTP 409
     * on upload): each Observation or Encounter with a pending update is re-saved with [save].
     * Runs before sync until a pass completes; [force] runs it again (Settings > Update Data).
     * Returns how many records were re-saved, or null if the pass failed.
     */
    suspend fun replacePendingUpdates(context: Context, fhirEngine: FhirEngine, force: Boolean = false): Int? {
        val formatter = FormatterClass()
        if (!force && formatter.getSharedPref(DONE_PREF, context) == "true") return 0
        return try {
            val fixed = replace<Observation>(fhirEngine) + replace<Encounter>(fhirEngine)
            formatter.saveSharedPref(DONE_PREF, "true", context)
            Timber.tag(TAG).i("Re-saved $fixed record(s) with pending version-checked updates")
            fixed
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Re-saving pending updates failed; will retry on next sync")
            null
        }
    }

    private suspend inline fun <reified R : Resource> replace(fhirEngine: FhirEngine): Int {
        // Collect first, then re-save: re-saving changes the stored order, which would shift
        // the pages of a scan still in progress.
        val toFix = mutableListOf<R>()
        var from = 0
        while (true) {
            val page = fhirEngine.search<R> {
                count = PAGE_SIZE
                this.from = from
            }.map { it.resource }
            page.filterTo(toFix) { resource ->
                fhirEngine.getLocalChanges(resource.resourceType, resource.logicalId)
                    .any { it.type == LocalChange.Type.UPDATE }
            }
            if (page.size < PAGE_SIZE) break
            from += PAGE_SIZE
        }
        toFix.forEach { save(fhirEngine, it) }
        return toFix.size
    }
}
