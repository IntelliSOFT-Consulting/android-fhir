package com.icl.surveillance.fhir

import android.content.Context
import com.google.common.reflect.TypeToken
import com.google.gson.Gson
import com.icl.surveillance.models.LocalLocationEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Location
import org.hl7.fhir.r4.model.Meta
import org.hl7.fhir.r4.model.Organization
import org.hl7.fhir.r4.model.Reference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A helper class for creating FHIR resources from JSON data.
 */
class ResourceCreationHelper {
    /**
     * Reads a JSON file from the assets folder.
     *
     * @param context The application context.
     * @param fileName The name of the file to read from the assets folder.
     * @return The content of the file as a string.
     */
    fun readJsonFromAssets(context: Context, fileName: String): String {
        return context.assets.open(fileName).bufferedReader().use { it.readText() }
    }

    /**
     * Parses a JSON string into a list of [LocalLocationEntry] objects.
     *
     * @param json The JSON string to parse.
     * @return A list of [LocalLocationEntry] objects.
     */
    fun parseLocationEntries(json: String): List<LocalLocationEntry> {
        val gson = Gson()
        val listType = object : TypeToken<List<LocalLocationEntry>>() {}.type
        return gson.fromJson(json, listType)
    }

    /**
     * Creates a list of FHIR [Location] resources from the `locations.json` file in the assets folder.
     *
     * @param context The application context.
     * @return A list of FHIR [Location] resources.
     */
    suspend fun createLocations(context: Context): List<Location> = withContext(Dispatchers.IO) {
        val dataList = mutableListOf<Location>()
        val json = readJsonFromAssets(context, "locations.json")
        val locations = parseLocationEntries(json)
        locations.forEach { entry ->
            println("Creating location ${entry.resource.name}")

            val location = Location().apply {
                id = entry.resource.id
                meta = Meta().apply {
                    versionId = entry.resource.meta.versionId
                    lastUpdated = try {
                        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
                            .apply { timeZone = TimeZone.getTimeZone("UTC") }
                            .parse(entry.resource.meta.lastUpdated)
                    } catch (e: Exception) {
                        Date()
                    }
                    source = entry.resource.meta.source
                }
                name = entry.resource.name
                val coding = entry.resource.type?.firstOrNull()?.coding?.firstOrNull()
                if (coding != null) {
                    type = mutableListOf(CodeableConcept().apply {
                        addCoding(Coding().apply {
                            system = coding.system
                            code = coding.code
                            display = coding.display
                        })
                    })
                }
                partOf = entry.resource.partOf?.let {
                    Reference().apply {
                        reference = it.reference
                        display = it.display
                    }
                }
            }
            dataList.add(location)
        }
        dataList
    }

    /**
     * Creates a list of FHIR [Organization] resources from the `locations.json` file in the assets folder.
     * This function is designed to create an organization for each location defined in the JSON file.
     *
     * @param context The application context.
     * @return A list of FHIR [Organization] resources.
     */

    suspend fun createRespectiveOrganization(context: Context): List<Organization> =
        withContext(Dispatchers.IO) {
            val dataList = mutableListOf<Organization>()
            val json = readJsonFromAssets(context, "locations.json")
            val locations = parseLocationEntries(json)
            locations.forEach { entry ->
                val location = Organization().apply {
                    id = entry.resource.id
                    meta = Meta().apply {
                        versionId = entry.resource.meta.versionId
                        lastUpdated = try {
                            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
                                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                                .parse(entry.resource.meta.lastUpdated)
                        } catch (e: Exception) {
                            Date()
                        }
                        source = entry.resource.meta.source
                    }
                    name = entry.resource.name
                    val coding = entry.resource.type?.firstOrNull()?.coding?.firstOrNull()
                    if (coding != null) {
                        type = mutableListOf(CodeableConcept().apply {
                            addCoding(Coding().apply {
                                system = coding.system
                                code = coding.code
                                display = coding.display
                            })
                        })
                    }
                    partOf = entry.resource.partOf?.let {
                        Reference().apply {
                            reference = it.reference.replace("Location", "Organization")
                            display = it.display
                        }
                    }
                }
                dataList.add(location)
            }
            dataList
        }

}
