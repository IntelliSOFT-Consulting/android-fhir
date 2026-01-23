package com.icl.surveillance.fhir

import android.content.Context
import androidx.work.WorkerParameters
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.search.revInclude
import com.google.android.fhir.search.search
import com.google.android.fhir.sync.AcceptLocalConflictResolver
import com.google.android.fhir.sync.DownloadWorkManager
import com.google.android.fhir.sync.FhirSyncWorker
import com.google.android.fhir.sync.upload.HttpCreateMethod
import com.google.android.fhir.sync.upload.HttpUpdateMethod
import com.google.android.fhir.sync.upload.UploadStrategy
import com.icl.surveillance.models.LocationLevel
import com.icl.surveillance.models.UserRole
import com.icl.surveillance.utils.FormatterClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.hl7.fhir.r4.model.Location
import org.hl7.fhir.r4.model.ResourceType
import java.util.LinkedList

class AppFhirSyncWorker(appContext: Context, workerParams: WorkerParameters) :
    FhirSyncWorker(appContext, workerParams) {
    override fun getDownloadWorkManager(): DownloadWorkManager {
        val engine = FhirApplication.fhirEngine(applicationContext)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

//        val facilityIds = getRespectiveFilteredResourcesSuspend(applicationContext, engine)
//        println("All Respective IDs expected $facilityIds")
        val manager = TimestampBasedDownloadWorkManagerImpl(
            dataStore = FhirApplication.dataStore(applicationContext),
            context = applicationContext,
            fhirEngine = engine,
            scope = scope,
//            urls = facilityIds
        )

        return manager
    }


    private fun buildResourceUrlsForFacility(facilityId: String): List<String> {
        return listOf(
            "Patient?_tag=Location/$facilityId&_sort=_lastUpdated",
            "Encounter?_tag=Location/$facilityId&_sort=_lastUpdated",
            "QuestionnaireResponse?_tag=Location/$facilityId&_sort=_lastUpdated",
            "MeasureReport?_tag=Location/$facilityId&_sort=_lastUpdated",
            "Observation?_tag=Location/$facilityId&_sort=_lastUpdated",
            "Specimen?_tag=Location/$facilityId&_sort=_lastUpdated"
        )
    }

    private suspend fun getFacilitiesByLevelSuspend(
        fhirEngine: FhirEngine,
        startId: String,
        level: LocationLevel
    ): List<String> = withContext(Dispatchers.IO) {
        val facilityIds = mutableListOf<String>()
        val locationIdsToProcess = mutableListOf(startId)

        when (level) {
            LocationLevel.FACILITY -> facilityIds.add(startId)

            LocationLevel.WARD -> {
                for (wardId in locationIdsToProcess) {
                    val cachedFacilityIds =
                        FormatterClass().getFacilityIds(applicationContext, wardId)
                    if (!cachedFacilityIds.isNullOrEmpty()) {
                        facilityIds.addAll(cachedFacilityIds)
                        continue
                    }

                    val facilities = fhirEngine.search<Location> {
                        filter(Location.PARTOF, { value = "Location/$wardId" })
                    }
                    val fetchedIds = facilities.map { it.resource.logicalId }
                    FormatterClass().saveFacilityIds(applicationContext, wardId, fetchedIds)
                    facilityIds.addAll(fetchedIds)
                }
            }

            LocationLevel.SUB_COUNTY -> {
                val cachedFacilities =
                    FormatterClass().getFacilityIdsForWard(applicationContext, startId)
                if (!cachedFacilities.isNullOrEmpty()) {
                    facilityIds.addAll(cachedFacilities)
                } else {
                    val wards = fhirEngine.search<Location> {
                        filter(Location.PARTOF, { value = "Location/$startId" })
                        revInclude<Location>(Location.PARTOF)
                    }

                    val allFacilityIds = wards.flatMap { ward ->
                        ward.revIncluded?.get(ResourceType.Location to Location.PARTOF.paramName)
                            ?.map { it.logicalId } ?: emptyList()
                    }

                    FormatterClass().saveFacilityIdsForWard(
                        applicationContext,
                        startId,
                        allFacilityIds
                    )
                    facilityIds.addAll(allFacilityIds)
                }
            }

            LocationLevel.COUNTY -> {
                val cachedFacilities =
                    FormatterClass().getFacilityIdsForWard(applicationContext, startId)
                if (!cachedFacilities.isNullOrEmpty()) {
                    facilityIds.addAll(cachedFacilities)
                } else {

                    // 1. County → SubCounties
                    val subCounties = fhirEngine.search<Location> {
                        filter(Location.PARTOF, { value = "Location/$startId" })
                    }

                    val allFacilityIds = mutableListOf<String>()

                    // 2. SubCounty → Wards
                    for (subCounty in subCounties) {
                        val wards = fhirEngine.search<Location> {
                            filter(
                                Location.PARTOF,
                                { value = "Location/${subCounty.resource.logicalId}" })
                        }

                        // 3. Ward → Facilities
                        for (ward in wards) {
                            val facilities = fhirEngine.search<Location> {
                                filter(
                                    Location.PARTOF,
                                    { value = "Location/${ward.resource.logicalId}" })
                            }

                            allFacilityIds.addAll(
                                facilities.map { it.resource.logicalId }
                            )
                        }
                    }
                    FormatterClass().saveFacilityIdsForWard(
                        applicationContext,
                        startId,
                        allFacilityIds
                    )

                    facilityIds.addAll(allFacilityIds)
                }
            }
            LocationLevel.NATIONAL -> {
                val counties = fhirEngine.search<Location> { }
                val countyIds = counties.map { it.resource.logicalId }

            }
        }

        facilityIds
    }

    fun getRespectiveFilteredResourcesSuspend(
        context: Context,
        engine: FhirEngine
    ): LinkedList<String> {
        val formatter = FormatterClass()
        val storedRole = formatter.getSharedPref("practitionerRole", context)
        val userRole = UserRole.fromAny(storedRole ?: "")
        val urls = mutableListOf<String>()
        

        when (userRole) {
            UserRole.FACILITY_SURVEILLANCE_FOCAL_PERSON,
            UserRole.SUPERVISOR,
            UserRole.VACCINATOR -> {
                val facilityId = formatter.getSharedPref("facility", context)
                if (!facilityId.isNullOrEmpty()) {
                    urls.addAll(buildResourceUrlsForFacility(facilityId))
                }
            }

            UserRole.SUBCOUNTY_DISEASE_SURVEILLANCE_OFFICER -> {
                val subCounty = formatter.getSharedPref("subCounty", context)
                if (!subCounty.isNullOrEmpty()) {
                    val facilities =
                        runBlocking {
                            getFacilitiesByLevelSuspend(
                                engine,
                                subCounty,
                                LocationLevel.SUB_COUNTY
                            )
                        }
                    urls.addAll(facilities.flatMap { buildResourceUrlsForFacility(it) })
                }
            }

            UserRole.COUNTY_DISEASE_SURVEILLANCE_OFFICER -> {
                val county = formatter.getSharedPref("county", context)
                if (!county.isNullOrEmpty()) {
                    val facilities = runBlocking {
                        getFacilitiesByLevelSuspend(
                            engine,
                            county,
                            LocationLevel.COUNTY
                        )
                    }
                    urls.addAll(facilities.flatMap {
                        buildResourceUrlsForFacility(it)
                    })
                }
            }

            else -> {}
        }

        return LinkedList(urls)
    }

    override fun getConflictResolver() = AcceptLocalConflictResolver

    override fun getFhirEngine() = FhirApplication.fhirEngine(applicationContext)

    override fun getUploadStrategy(): UploadStrategy =
        UploadStrategy.forBundleRequest(
            methodForCreate = HttpCreateMethod.PUT,
            methodForUpdate = HttpUpdateMethod.PATCH,
            squash = true,
            bundleSize = 300,
        )
}
