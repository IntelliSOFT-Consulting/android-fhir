package com.icl.surveillance.debug

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.search.search
import kotlin.random.Random
import org.hl7.fhir.r4.model.Location

/**
 * DEBUG ONLY. Picks a real County → Sub-county → Ward → Facility chain from the Location
 * hierarchy stored on the device (`Location.partOf`, rooted at `Location/0` = Kenya), so synthetic
 * records are spread across all counties instead of the seed record's facility.
 */
internal class LocationPicker(private val engine: FhirEngine) {

    data class Place(val id: String, val name: String)

    data class Chain(val county: Place, val subCounty: Place, val ward: Place, val facility: Place)

    private val children = HashMap<String, List<Place>>()
    private var counties: List<Place> = emptyList()

    /** Loads the counties; returns how many are available on this device. */
    suspend fun load(): Int {
        counties = childrenOf(KENYA_ID).sortedBy { it.name }
        return counties.size
    }

    /**
     * Counties are taken round-robin by [index] so records are spread evenly; the lower levels are
     * random. Counties with an incomplete hierarchy on the device are skipped.
     */
    suspend fun pick(index: Int, random: Random): Chain? {
        if (counties.isEmpty()) return null
        for (attempt in counties.indices) {
            val county = counties[(index + attempt) % counties.size]
            val subCounty = childrenOf(county.id).randomOrNull(random) ?: continue
            val ward = childrenOf(subCounty.id).randomOrNull(random) ?: continue
            val facility = childrenOf(ward.id).randomOrNull(random) ?: continue
            return Chain(county, subCounty, ward, facility)
        }
        return null
    }

    private suspend fun childrenOf(id: String): List<Place> =
        children.getOrPut(id) {
            engine.search<Location> { filter(Location.PARTOF, { value = "Location/$id" }) }
                .mapNotNull { result ->
                    val name = result.resource.name?.trim()
                    if (name.isNullOrEmpty()) null else Place(result.resource.logicalId, name)
                }
        }

    private companion object {
        const val KENYA_ID = "0"
    }
}
