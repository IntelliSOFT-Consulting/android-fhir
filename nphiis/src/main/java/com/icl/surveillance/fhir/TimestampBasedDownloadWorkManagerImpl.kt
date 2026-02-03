package com.icl.surveillance.fhir

import android.content.Context
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.sync.DownloadWorkManager
import com.google.android.fhir.sync.download.DownloadRequest
import com.icl.surveillance.utils.FormatterClass
import org.hl7.fhir.r4.model.*
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.LinkedList
import kotlinx.coroutines.CoroutineScope
import org.hl7.fhir.exceptions.FHIRException
import java.util.Date

class TimestampBasedDownloadWorkManagerImpl(
    private val dataStore: DemoDataStore,
    val context: Context,
    val fhirEngine: FhirEngine,
    val scope: CoroutineScope,
) : DownloadWorkManager {

    private val resourceTypeList = ResourceType.values().map { it.name }

    // --- LIVE TRACKER ---
    private val locationMonitor = NPHIISSyncProgressStore(context)
    private val tracker = NPHIISSyncTracker(locationMonitor, locationTarget = 17000)
    private var runId: String? = null

    // ✅ Hard rule: never load records updated before 2026
    private val BASELINE_2026: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val ISO_INSTANT: DateTimeFormatter =
        DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

    // ✅ Seed URLs: NO _lastUpdated here (we inject centrally)
    private val urls = LinkedList(
        listOf(
            "Patient?_count=200&_sort=_lastUpdated",
            "MeasureReport?_count=200&_sort=_lastUpdated",
            "QuestionnaireResponse?_count=200&_sort=_lastUpdated",
            "Location?_count=900&_sort=_lastUpdated",
        )
    )

    override suspend fun getNextRequest(): DownloadRequest? {
        FormatterClass().getSharedPref("isLoggedIn", context) ?: return null

        var url = urls.poll() ?: return null

        val resourceTypeToDownload =
            ResourceType.fromCode(url.substringBefore("?"))

        locationMonitor.setCurrentType(resourceTypeToDownload.name)

        val last = dataStore.getLastUpdateTimestamp(resourceTypeToDownload) // may be null/blank
        url = affixLastUpdatedTimestamp(url, last)

        return DownloadRequest.of(url)
    }

    override suspend fun getSummaryRequestUrls(): Map<ResourceType, String> {
        return urls.associate { url ->
            val resourceType = ResourceType.fromCode(url.substringBefore("?"))
            resourceType to url
        }
    }

    override suspend fun processResponse(response: Resource): Collection<Resource> {
        if (response is OperationOutcome) {
            throw FHIRException(response.issueFirstRep.diagnostics)
        }

        // If the resource returned is a List containing Patients, enqueue $everything
        if (response is ListResource) {
            for (entry in response.entry) {
                val reference = Reference(entry.item.reference)
                if (reference.referenceElement.resourceType == "Patient") {
                    val patientUrl = "${entry.item.reference}/\$everything"
                    urls.add(patientUrl)
                }
            }
        }

        if (response is Bundle) {
            // ---- Location monitoring: count Location entries in this page ----
            val locationInThisPage =
                response.entry.mapNotNull { it.resource }
                    .count { it.resourceType == ResourceType.Location }

            if (locationInThisPage > 0) {
                locationMonitor.addLocationDownloaded(locationInThisPage)
            }

            // Enqueue Patient/$id/$everything for patients found in bundles
            for (entry in response.entry) {
                val type = entry.resource.resourceType.toString()
                if (type == "Patient") {
                    val patientId = entry.resource.idElement.idPart
                    val patientUrl = "Patient/$patientId/\$everything"
                    urls.add(patientUrl)
                }
            }

            // Follow pagination
            val nextUrl =
                response.link.firstOrNull { it.relation == "next" }?.url
            if (nextUrl != null) {
                urls.add(nextUrl)
            }
        }

        // Extract resources + save watermark
        var bundleCollection: Collection<Resource> = emptyList()
        if (response is Bundle && response.type == Bundle.BundleType.SEARCHSET) {
            bundleCollection =
                response.entry
                    .map { it.resource }
                    .also { extractAndSaveLastUpdateTimestampToFetchFutureUpdates(it) }
        }

        return bundleCollection
    }

    private suspend fun extractAndSaveLastUpdateTimestampToFetchFutureUpdates(
        resources: List<Resource>,
    ) {
        resources
            .groupBy { it.resourceType }
            .forEach { (type, list) ->

                val maxDate: Date =
                    list
                        .mapNotNull { it.meta?.lastUpdated }
                        .maxOrNull()
                        ?: return@forEach


                val bumped = maxDate.toInstant().plusMillis(1)

                dataStore.saveLastUpdatedTimestamp(
                    type,
                    DateTimeFormatter.ISO_INSTANT.format(bumped)
                )
            }
    }

    /**
     * Attach timestamp filter:
     * - $everything => _since
     * - normal searches => _lastUpdated
     *
     * Hard clamp to BASELINE_2026, so you *never* fetch pre-2026.
     */
    private fun affixLastUpdatedTimestamp(url: String, lastUpdated: String?): String {
        // Do not modify any URL set by a server that specifies the token of the page to return.
        if (url.contains("page_token")) return url

        val storedInstant: Instant? =
            lastUpdated
                ?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }

        // ✅ enforce baseline: effectiveStart = max(stored, baseline)
        val effectiveStart = maxOf(storedInstant ?: BASELINE_2026, BASELINE_2026)
        val ts = ISO_INSTANT.format(effectiveStart)

        // $everything uses _since
        if (url.contains("\$everything")) {
            val cleaned = url.replace(Regex("[&?]_since=[^&]*"), "")
            val joiner = if (cleaned.contains("?")) "&" else "?"
            return "$cleaned${joiner}_since=$ts"
        }

        // normal search uses _lastUpdated (replace any existing one)
        val cleaned = url.replace(Regex("[&?]_lastUpdated=[^&]*"), "")
        val joiner = if (cleaned.contains("?")) "&" else "?"
        return "$cleaned${joiner}_lastUpdated=ge$ts"
    }
}