package com.icl.surveillance.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.icl.surveillance.models.LocationRef

/**
 * Turns county / sub-county / ward names (e.g. from the Client Registry) into the app's Location
 * ids, using the bundled location files (assets/fhir-bundle-*-kenya.json). Each level must sit
 * under the one above it; a name that cannot be placed is left out rather than guessed.
 */
class LocationResolver private constructor(
    private val counties: List<Loc>,
    private val subCounties: List<Loc>,
    private val wards: List<Loc>,
) {
    data class Loc(val id: String, val name: String, val parentId: String?)
    data class Resolved(val county: LocationRef?, val subCounty: LocationRef?, val ward: LocationRef?)

    fun resolve(county: String?, subCounty: String?, ward: String?): Resolved {
        val c = find(counties, county, parentId = null)
        val s = c?.let { find(subCounties, subCounty, parentId = it.id) }
        val w = s?.let { find(wards, ward, parentId = it.id) }
        return Resolved(c?.ref(), s?.ref(), w?.ref())
    }

    /**
     * Exact name first (ignoring case, spaces and punctuation); otherwise the single place under
     * [parentId] whose name contains the given one (e.g. "SOUTH" -> "NYERI SOUTH").
     */
    private fun find(all: List<Loc>, name: String?, parentId: String?): Loc? {
        val wanted = normalize(name ?: return null).ifEmpty { return null }
        val pool = if (parentId == null) all else all.filter { it.parentId == parentId }
        pool.firstOrNull { normalize(it.name) == wanted }?.let { return it }
        return pool.filter { normalize(it.name).contains(wanted) }.singleOrNull()
    }

    private fun Loc.ref() = LocationRef(id, name)

    companion object {
        @Volatile private var instance: LocationResolver? = null

        fun get(context: Context): LocationResolver =
            instance ?: synchronized(this) {
                instance ?: LocationResolver(
                    load(context, "fhir-bundle-counties-kenya.json"),
                    load(context, "fhir-bundle-sub-counties-kenya.json"),
                    load(context, "fhir-bundle-wards-kenya.json"),
                ).also { instance = it }
            }

        private fun normalize(value: String) = value.uppercase().replace(Regex("[^A-Z0-9]"), "")

        private fun load(context: Context, file: String): List<Loc> = runCatching {
            val bundle = context.assets.open(file).bufferedReader().use {
                Gson().fromJson(it, JsonObject::class.java)
            }
            bundle.getAsJsonArray("entry").mapNotNull { entry ->
                val r = entry.asJsonObject.getAsJsonObject("resource") ?: return@mapNotNull null
                val id = r.get("id")?.asString ?: return@mapNotNull null
                val name = r.get("name")?.asString ?: return@mapNotNull null
                val parent = r.getAsJsonObject("partOf")?.get("reference")?.asString?.substringAfter("Location/")
                Loc(id, name, parent)
            }
        }.getOrDefault(emptyList())
    }
}
