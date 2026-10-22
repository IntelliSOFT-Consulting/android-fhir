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

    /**
     * Records last changed before this are never downloaded (start of sync: 15 Oct 2026, midnight
     * Nairobi time = 14 Oct 21:00 UTC; written in UTC because Instant.parse and the URL need "Z").
     * A resource type's own last-synced time is used when later. Locations are exempt on the
     * first sync (the full list is needed).
     */
    private val SYNC_START = "2026-01-01T21:00:00Z"

    private val LOCATION_URL = "Location?_count=$LOCATION_PAGE_SIZE&_sort=_lastUpdated"

    // NOTE: keep your intended behavior; this is just as you had it.
    // Page sizes are kept small for clinical resources: a single QuestionnaireResponse can carry
    // 150-250 answers, and a 500-response page (parsed into HAPI objects) exhausted the heap
    // (OutOfMemoryError) once the server held a few thousand submissions.
    private val CORE_URLS =
        if (FormatterClass().isSyncDone(context)) {
            listOf(
                "Patient?_count=$PATIENT_PAGE_SIZE&_sort=_lastUpdated",
                "QuestionnaireResponse?_count=$RESPONSE_PAGE_SIZE&_sort=_lastUpdated",
                "DiagnosticReport?_count=$REPORT_PAGE_SIZE&_sort=_lastUpdated",
                "Group?_count=$GROUP_PAGE_SIZE&_sort=_lastUpdated",
                // Records the server creates or changes without touching their Patient (e.g. a
                // contact registered by the HIE: new Encounter + answers on an existing Patient)
                // never come through Patient/$everything, which only runs for changed Patients.
                "Encounter?_count=$ENCOUNTER_PAGE_SIZE&_sort=_lastUpdated",
                "Observation?_count=$OBSERVATION_PAGE_SIZE&_sort=_lastUpdated",
                "Specimen?_count=$ENCOUNTER_PAGE_SIZE&_sort=_lastUpdated",
                "Location?_count=500&_sort=_lastUpdated",
            )
        } else {
            listOf(
                "Location?_count=$LOCATION_PAGE_SIZE&_sort=_lastUpdated",
            )
        }

    private val urls: LinkedList<String> = LinkedList()
    private var shouldMarkLocationSeedDone = false

    private val enqueuedEverythingPatients = mutableSetOf<String>()
    private val everythingPages = mutableSetOf<String>()
    private var currentIsEverything = false
    private val seenUrls = mutableSetOf<String>()

    private suspend fun seedUrlsIfNeeded() {
        if (seeded) return

        urls.clear()
        tracker.startRun()
        lastResolvedResourceType = null

        // Their own downloads are new: start them from SYNC_START once. (Timestamps saved earlier
        // for these types came from Patient/$everything and would skip records it never returned.)
        if (FormatterClass().getSharedPref(TYPE_SYNC_RESET_PREF, context) != "true") {
            dataStore.clearTimestamps(listOf(ResourceType.Encounter, ResourceType.Observation, ResourceType.Specimen))
            FormatterClass().saveSharedPref(TYPE_SYNC_RESET_PREF, "true", context)
        }

        val isFirstTimeForLocation = !FormatterClass().isSyncDone(context)
        if (isFirstTimeForLocation) {
            urls.add(LOCATION_URL)
            shouldMarkLocationSeedDone = true
        }

        urls.addAll(CORE_URLS)
        // Logcat "SYNC_PHASE": the first sync downloads Locations only; everything else starts
        // once they are all in (FormatterClass.isSyncDone).
        android.util.Log.i(
            "SYNC_PHASE",
            if (isFirstTimeForLocation) "Initial sync: Locations only" else "Full sync: ${urls.size} record types",
        )
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

            // Pages of a Patient/$everything download do not move the per-type timestamps:
            // they hold only that patient's records, so a later timestamp from them would make
            // the type's own download skip other records.
            currentIsEverything = url.contains("\$everything") || url in everythingPages
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
                if (currentIsEverything) everythingPages.add(nextUrl)
                urls.add(nextUrl)
            }
        }

        var bundleCollection: Collection<Resource> = mutableListOf()
        if (response is Bundle && response.type == Bundle.BundleType.SEARCHSET) {
            bundleCollection =
                response.entry
                    .mapNotNull { it.resource }
                    .also { if (!currentIsEverything) extractAndSaveLastUpdateTimestampToFetchFutureUpdates(it) }
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
            SYNC_START
        } else {
            maxIsoInstant(SYNC_START, normalizeToInstant(lastUpdated))
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

    private companion object {
        const val PATIENT_PAGE_SIZE = 100
        const val RESPONSE_PAGE_SIZE = 50
        const val REPORT_PAGE_SIZE = 50
        const val GROUP_PAGE_SIZE = 100
        // Smaller pages (were 950, ~0.5 MB each): each reply is quicker, so the server is far
        // less likely to stall long enough to time out.
        const val LOCATION_PAGE_SIZE = 400
        const val ENCOUNTER_PAGE_SIZE = 100
        const val OBSERVATION_PAGE_SIZE = 200

        /** Set once the Encounter / Observation / Specimen downloads have started from SYNC_START. */
        private const val TYPE_SYNC_RESET_PREF = "typeDownloadsResetV1"
    }
}
