package com.icl.surveillance.fhir

import android.content.Context
import android.net.Uri
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.sync.DownloadWorkManager
import com.google.android.fhir.sync.download.DownloadRequest
import com.icl.surveillance.utils.FormatterClass
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import org.hl7.fhir.exceptions.FHIRException
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.ListResource
import org.hl7.fhir.r4.model.OperationOutcome
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType

class TimestampBasedDownloadWorkManagerImpl(
    private val dataStore: DemoDataStore,
    val context: Context,
    val fhirEngine: FhirEngine,
    val scope: CoroutineScope,
) : DownloadWorkManager {

    private var seeded = false
    private val resourceTypeList = ResourceType.values().map { it.name }.toSet()

    // --- LIVE TRACKER ---
    private val locationMonitor = NPHIISSyncProgressStore(context)
    private val tracker = NPHIISSyncTracker(locationMonitor, locationTarget = 17000)
    private var lastResolvedResourceType: ResourceType? = null

    private val FLOOR_2026 = "2026-01-01T00:00:00Z"

    private val LOCATION_URL = "Location?_count=950&_sort=_lastUpdated"

    // NOTE: keep your intended behavior; this is just as you had it.
    private val CORE_URLS =
        if (FormatterClass().isSyncDone(context)) {
            listOf(
                "Patient?_count=200&_sort=_lastUpdated",
                "QuestionnaireResponse?_count=500&_sort=_lastUpdated",
                "Location?_count=500&_sort=_lastUpdated",
            )
        } else {
            listOf(
                "Location?_count=950&_sort=_lastUpdated",
            )
        }

    private val urls: LinkedList<String> = LinkedList()
    private var shouldMarkLocationSeedDone = false

    private val enqueuedEverythingPatients = mutableSetOf<String>()
    private val seenUrls = mutableSetOf<String>()

    private suspend fun seedUrlsIfNeeded() {
        if (seeded) return

        urls.clear()
        tracker.startRun()
        lastResolvedResourceType = null

        val isFirstTimeForLocation = !FormatterClass().isSyncDone(context)
        if (isFirstTimeForLocation) {
            urls.add(LOCATION_URL)
            shouldMarkLocationSeedDone = true
        }

        urls.addAll(CORE_URLS)
        seeded = true
    }

    override suspend fun getNextRequest(): DownloadRequest? {
        FormatterClass().getSharedPref("isLoggedIn", context) ?: return null
        seedUrlsIfNeeded()

        while (true) {
            var url = urls.poll() ?: run {
                if (shouldMarkLocationSeedDone) {
                    FormatterClass().setSyncDone(context)
                    shouldMarkLocationSeedDone = false
                }
                tracker.done()
                return null
            }

            if (!seenUrls.add(url)) {
                continue
            }

            val resourceTypeToDownload =
                resolveResourceTypeFromUrl(url) ?: lastResolvedResourceType ?: continue
            lastResolvedResourceType = resourceTypeToDownload
            tracker.onTypeStart(resourceTypeToDownload.name)

            dataStore.getLastUpdateTimestamp(resourceTypeToDownload)?.let {
                url = affixLastUpdatedTimestamp(url, it, resourceTypeToDownload)
            }

            return DownloadRequest.of(url)
        }
    }

    override suspend fun getSummaryRequestUrls(): Map<ResourceType, String> {
        return urls.mapNotNull { url ->
            resolveResourceTypeFromUrl(url)?.let { resourceType ->
                resourceType to url
            }
        }.toMap()
    }

    override suspend fun processResponse(response: Resource): Collection<Resource> {
        if (response is OperationOutcome) {
            throw FHIRException(response.issueFirstRep.diagnostics)
        }

        // If List containing Patients -> queue $everything for those patient refs
        if (response is ListResource) {
            for (entry in response.entry) {
                val reference = Reference(entry.item.reference)
                if (reference.referenceElement.resourceType.equals("Patient")) {
                    val patientUrl = "${entry.item.reference}/\$everything"
                    urls.add(patientUrl)
                }
            }
        }

        if (response is Bundle) {
            val locationInThisPage =
                response.entry.mapNotNull { it.resource }
                    .count { it.resourceType == ResourceType.Location }
            if (locationInThisPage > 0) {
                tracker.onLocationPageDownloaded(locationInThisPage)
            }

            val typesInBundle: Set<ResourceType> =
                response.entry.mapNotNull { it.resource?.resourceType }.toSet()
            if (typesInBundle.size == 1) {
                lastResolvedResourceType = typesInBundle.first()
            }

            val isPatientOnlySearchPage =
                response.type == Bundle.BundleType.SEARCHSET &&
                        typesInBundle.size == 1 &&
                        typesInBundle.contains(ResourceType.Patient)
            if (isPatientOnlySearchPage) {
                for (entry in response.entry) {
                    val res = entry.resource ?: continue
                    val patientId = res.idElement?.idPart ?: continue

                    // ✅ enqueue once per patient
                    if (enqueuedEverythingPatients.add(patientId)) {
                        urls.add("Patient/$patientId/\$everything")
                    }
                }
            }
            val nextUrl = response.link.firstOrNull { it.relation == "next" }?.url
            if (nextUrl != null) {
                urls.add(nextUrl)
            }
        }

        var bundleCollection: Collection<Resource> = mutableListOf()
        if (response is Bundle && response.type == Bundle.BundleType.SEARCHSET) {
            bundleCollection =
                response.entry
                    .mapNotNull { it.resource }
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
            .forEach { map ->
                dataStore.saveLastUpdatedTimestamp(
                    map.key,
                    map.value.maxOfOrNull { it.meta.lastUpdated }?.toTimeZoneString() ?: "",
                )
            }
    }

    private fun affixLastUpdatedTimestamp(
        url: String,
        lastUpdated: String?,
        resourceType: ResourceType,
    ): String {
        if (url.contains("&page_token")) return url

        val isEverything = url.contains("\$everything")

        if (resourceType == ResourceType.Location) {
            if (!FormatterClass().isSyncDone(context)) {
                return url
            }
        }

        val lowerBound = if (lastUpdated.isNullOrBlank()) {
            FLOOR_2026
        } else {
            maxIsoInstant(FLOOR_2026, normalizeToInstant(lastUpdated))
        }

        return if (isEverything) {
            setOrReplaceQueryParam(url, "_since", lowerBound)
        } else {
            setOrReplaceQueryParam(url, "_lastUpdated", "gt$lowerBound")
        }
    }

    private fun normalizeToInstant(ts: String): String {
        return try {
            OffsetDateTime.parse(ts).toInstant().toString()
        } catch (_: Exception) {
            try {
                Instant.parse(ts).toString()
            } catch (_: Exception) {
                ts
            }
        }
    }

    private fun maxIsoInstant(a: String, b: String): String {
        return try {
            val ia = Instant.parse(a)
            val ib = Instant.parse(b)
            if (ib.isAfter(ia)) b else a
        } catch (_: Exception) {
            b.ifBlank { a }
        }
    }

    private fun setOrReplaceQueryParam(url: String, key: String, value: String): String {
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

    private fun resolveResourceTypeFromUrl(url: String): ResourceType? {
        val pathSegments = runCatching { Uri.parse(url).pathSegments }.getOrDefault(emptyList())
        pathSegments.asReversed().forEach { segment ->
            toResourceType(segment)?.let { return it }
        }

        val pathPart = url.substringBefore("?").substringAfterLast("/")
        return toResourceType(pathPart)
    }

    private fun toResourceType(raw: String?): ResourceType? {
        val candidate = raw?.trim().orEmpty()
        if (candidate.isEmpty()) return null
        val normalized = candidate.substringBefore("/")
        val matchedName = resourceTypeList.firstOrNull { it.equals(normalized, ignoreCase = true) }
            ?: return null
        return runCatching { ResourceType.fromCode(matchedName) }.getOrNull()
    }

    private fun Date.toTimeZoneString(): String {
        val simpleDateFormat =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.getDefault())
                .withZone(ZoneId.systemDefault())
        return simpleDateFormat.format(this.toInstant())
    }
}
