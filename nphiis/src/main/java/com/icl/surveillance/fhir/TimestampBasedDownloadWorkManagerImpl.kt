package com.icl.surveillance.fhir

import android.content.Context
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.sync.DownloadWorkManager
import com.google.android.fhir.sync.download.DownloadRequest
import com.icl.surveillance.models.NPHIISSyncProgress
import com.icl.surveillance.utils.FormatterClass
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import org.hl7.fhir.exceptions.FHIRException
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.ListResource
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter.ISO_INSTANT
import java.util.UUID

class TimestampBasedDownloadWorkManagerImpl(
    private val dataStore: DemoDataStore,
    val context: Context,
    val fhirEngine: FhirEngine,
    val scope: CoroutineScope,
//    var urls: LinkedList<String> = LinkedList(),
) : DownloadWorkManager {
    private var seeded = false
    private val resourceTypeList = ResourceType.values().map { it.name }

    // --- LIVE TRACKER ---
    private val locationMonitor = NPHIISSyncProgressStore(context)
    private val tracker = NPHIISSyncTracker(locationMonitor, locationTarget = 17000)
    private var runId: String? = null
    private val BASELINE_START_OF_YEAR: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private val FLOOR_2026 = "2026-01-01T00:00:00Z"
    private val ISO_INSTANT: DateTimeFormatter =
        DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)
    private val CORE_URLS = listOf(
        "Patient?_count=200&_sort=_lastUpdated",
        "QuestionnaireResponse?_count=200&_sort=_lastUpdated",
        "MeasureReport?_count=200&_sort=_lastUpdated",
        "Specimen?_count=200&_sort=_lastUpdated",
    )

    private val LOCATION_URL = "Location?_count=900&_sort=_lastUpdated"
    private val urls: LinkedList<String> = LinkedList()
    private var shouldMarkLocationSeedDone = false

    private fun seedUrlsIfNeeded() {
        if (seeded) return

        urls.clear()
        urls.addAll(CORE_URLS)

        val isFirstTimeForLocation = !FormatterClass().isSyncDone(context)
        if (isFirstTimeForLocation) {
            urls.add(LOCATION_URL)
            shouldMarkLocationSeedDone = true
        }

        seeded = true
    }

    override suspend fun getNextRequest(): DownloadRequest? {
        FormatterClass().getSharedPref("isLoggedIn", context) ?: return null
        seedUrlsIfNeeded()

        var url = urls.poll() ?: run {
            // ✅ All queued work done for this run
            if (shouldMarkLocationSeedDone) {
//                FormatterClass().setSyncDone(context)
                shouldMarkLocationSeedDone = false
            }
            return null
        }

        val resourceTypeToDownload =
            ResourceType.fromCode(url.findAnyOf(resourceTypeList, ignoreCase = true)!!.second)

        locationMonitor.setCurrentType(resourceTypeToDownload.name)

        dataStore.getLastUpdateTimestamp(resourceTypeToDownload)?.let {
            url = affixLastUpdatedTimestamp(url, it)
        }
        return DownloadRequest.of(url)
    }

    override suspend fun getSummaryRequestUrls(): Map<ResourceType, String> {
        return urls.associate { url ->
            val resourceType = ResourceType.fromCode(url.substringBefore("?"))
            //            if (resourceType == ResourceType.Patient) {
            //                resourceType to
            // url.plus("&${SyncDataParams.SUMMARY_KEY}=${SyncDataParams.SUMMARY_COUNT_VALUE}")
            //            } else {
            resourceType to url
            //            }
        }
    }

    override suspend fun processResponse(response: Resource): Collection<Resource> {
        // As per FHIR documentation :
        // If the search fails (cannot be executed, not that there are no matches), the
        // return value SHALL be a status code 4xx or 5xx with an OperationOutcome.
        // See https://www.hl7.org/fhir/http.html#search for more details.
        if (response is OperationOutcome) {
            throw FHIRException(response.issueFirstRep.diagnostics)
        }

        // If the resource returned is a List containing Patients, extract Patient references and fetch
        // all resources related to the patient using the $everything operation.
        if (response is ListResource) {

            for (entry in response.entry) {

                val reference = Reference(entry.item.reference)
                if (reference.referenceElement.resourceType.equals("Patient")) {
                    val patientUrl = "${entry.item.reference}/\$everything"
                    urls.add(patientUrl)
                }
            }
        }

        // If the resource returned is a Bundle, check to see if there is a "next" relation referenced
        // in the Bundle.link component, if so, append the URL referenced to list of URLs to download.
        if (response is Bundle) {

            // ---- Location monitoring: count Location entries in this page ----
            val locationInThisPage =
                response.entry.mapNotNull { it.resource }
                    .count { it.resourceType == ResourceType.Location }

            if (locationInThisPage > 0) {
                locationMonitor.addLocationDownloaded(locationInThisPage)
            }

            for (entry in response.entry) {
                val type = entry.resource.resourceType.toString()
                if (type == "Patient") {
                    val patientId = entry.resource.idElement.idPart
                    val patientUrl = "Patient/$patientId/\$everything"
                    urls.add(patientUrl)
                }
            }

            val nextUrl =
                response.link.firstOrNull { component -> component.relation == "next" }?.url
            if (nextUrl != null) {
                urls.add(nextUrl)
            }
        }

        // Finally, extract the downloaded resources from the bundle.
        var bundleCollection: Collection<Resource> = mutableListOf()
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
            .entries
            .map { map ->
                dataStore.saveLastUpdatedTimestamp(
                    map.key,
                    map.value.maxOfOrNull { it.meta.lastUpdated }?.toTimeZoneString() ?: "",
                )
            }
    }

    /**
     * Affixes the last updated timestamp to the request URL.
     *
     * If the request URL includes the `$everything` parameter, the last updated timestamp will be
     * attached using the `_since` parameter. Otherwise, the last updated timestamp will be attached
     * using the `_lastUpdated` parameter.
     */

    private fun affixLastUpdatedTimestamp(url: String, lastUpdated: String?): String {
        if (url.contains("&page_token")) return url

        val isEverything = url.contains("\$everything")
        if (url.contains("Location")) {
            return if (!lastUpdated.isNullOrBlank()) {

                if (FormatterClass().isSyncDone(context)) {
                    setOrReplaceQueryParam(
                        url,
                        "_lastUpdated",
                        "gt${normalizeToInstant(lastUpdated)}"
                    )
                } else {
                    url
                }
            } else {
                url
            }
        }
        // Compute ONE lower bound
        val lowerBound = if (lastUpdated.isNullOrBlank()) {
            FLOOR_2026
        } else {
            maxIsoInstant(FLOOR_2026, normalizeToInstant(lastUpdated))
        }

        return if (isEverything) {
            // Ensure only one _since
            setOrReplaceQueryParam(url, "_since", lowerBound)
        } else {
            // Ensure only one _lastUpdated (single, strict constraint)
            setOrReplaceQueryParam(url, "_lastUpdated", "gt$lowerBound")
        }
    }

    private fun normalizeToInstant(ts: String): String {
        return try {
            // Accepts "2025-09-04T00:46:33.677+03:00"
            java.time.OffsetDateTime.parse(ts).toInstant().toString()
        } catch (_: Exception) {
            try {
                Instant.parse(ts).toString()
            } catch (_: Exception) {
                ts // last resort, but try hard to avoid this path
            }
        }
    }

    private fun maxIsoInstant(a: String, b: String): String {
        return try {
            val ia = Instant.parse(a)
            val ib = Instant.parse(b)
            if (ib.isAfter(ia)) b else a
        } catch (_: Exception) {
            // If parsing fails, prefer b if it's not blank, else a
            b.ifBlank { a }
        }
    }

    private fun setOrReplaceQueryParam(url: String, key: String, value: String): String {
        // Replace if present
        val regex = Regex("([?&])$key=[^&]*")
        return if (regex.containsMatchIn(url)) {
            url.replace(regex, "$1$key=$value")
        } else {
            appendQueryParam(url, key, value)
        }
    }

    private fun appendQueryParam(url: String, key: String, value: String): String {
        val sep = if (url.contains("?")) "&" else "?"
        return "$url$sep$key=$value"
    }


    private fun affixLastUpdatedTimestampOld(url: String, lastUpdated: String): String {
        var downloadUrl = url
        val storedInstant: Instant? =
            lastUpdated.takeIf { it.isNotBlank() }
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }

        // ✅ If first time -> baseline start of year; else stored
        val effectiveStart: Instant = storedInstant ?: BASELINE_START_OF_YEAR
        val ts = ISO_INSTANT.format(effectiveStart)

        // $everything uses _since
        if (url.contains("\$everything")) {
            val cleaned = url.replace(Regex("[&?]_since=[^&]*"), "")
            val joiner = if (cleaned.contains("?")) "&" else "?"
            return "$cleaned${joiner}_since=$ts"
        }

        // Normal search uses _lastUpdated (replace any existing)
        val cleaned = url.replace(Regex("[&?]_lastUpdated=[^&]*"), "")
        val joiner = if (cleaned.contains("?")) "&" else "?"
        downloadUrl = "$cleaned${joiner}_lastUpdated=ge$ts"

        // Affix lastUpdate to a $everything query using _since as per:
        // https://hl7.org/fhir/operation-patient-everything.html
//        if (downloadUrl.contains("\$everything")) {
//            downloadUrl =
//                if (downloadUrl.contains("?")) {
//                    "$downloadUrl&_since=$lastUpdated"
//                } else {
//                    "$downloadUrl?_since=$lastUpdated"
//                }
//        }
//        if (!downloadUrl.contains("\$everything")) {
//            downloadUrl =
//                if (downloadUrl.contains("&_count=")) {
//                    url
//                } else if (downloadUrl.contains("&_lastUpdated")) {
//                    url
//                } else if (downloadUrl.contains("sort")) {
//                    "$downloadUrl&_lastUpdated=gt$lastUpdated"
//                } else {
//                    "$downloadUrl?_lastUpdated=gt$lastUpdated"
//                }
//        }
//        if (downloadUrl.contains("_lastUpdated=")) {
//            downloadUrl = url
//        }

        // Do not modify any URL set by a server that specifies the token of the page to return.
        if (downloadUrl.contains("&page_token")) {
            downloadUrl = url
        }
        return downloadUrl
    }

    private fun Date.toTimeZoneString(): String {
        val simpleDateFormat =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.getDefault())
                .withZone(ZoneId.systemDefault())
        return simpleDateFormat.format(this.toInstant())
    }
}