package com.icl.surveillance.fhir.forms

import android.content.Context
import com.icl.surveillance.models.QuestionnaireAnswer
import com.icl.surveillance.utils.FormatterClass
import org.hl7.fhir.r4.model.Address
import org.hl7.fhir.r4.model.DecimalType
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.StringType
import org.json.JSONObject

/**
 * Where a person lives, picked on the map (ResidencePickerActivity): a name plus coordinates.
 *
 * It is picked before the form opens, kept until the form is submitted ([stage] / [take]) and
 * then appended to the submission (see [appendTo]): the QuestionnaireResponse gets a
 * [EXTENSION_URL] extension, the Patient's home address carries the standard FHIR geolocation,
 * and the values are saved as answers ([answers]) so they are stored like any other field.
 */
data class ResidenceLocation(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    /** How it was picked: "search", "map" (pin moved) or "gps" (current location). */
    val source: String,
) {
    fun toJson(): String = JSONObject()
        .put("name", name)
        .put("address", address)
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("source", source)
        .toString()

    /** "Kongowea, Nyali (-4.0217, 39.6982)". */
    val label: String
        get() = listOf(name, address.takeIf { it != name }).filterNotNull().filter { it.isNotBlank() }
            .joinToString(", ") + " (%.5f, %.5f)".format(latitude, longitude)

    /** Hidden answers of the case form (residence-map-* under the residence questions). */
    fun formAnswers(): Map<String, String> = mapOf(
        LINK_NAME to name.ifBlank { address },
        LINK_ADDRESS to address,
        LINK_LATITUDE to latitude.toBigDecimal().toPlainString(),
        LINK_LONGITUDE to longitude.toBigDecimal().toPlainString(),
    ).filterValues { it.isNotBlank() }

    /** The same values as plain answers, for forms without the hidden questions. */
    fun answers(): List<QuestionnaireAnswer> = listOf(
        QuestionnaireAnswer(LINK_NAME, "Residence (map)", name.ifBlank { address }),
        QuestionnaireAnswer(LINK_ADDRESS, "Residence address (map)", address),
        QuestionnaireAnswer(LINK_LATITUDE, "Residence latitude", latitude.toString()),
        QuestionnaireAnswer(LINK_LONGITUDE, "Residence longitude", longitude.toString()),
    ).filter { it.answer.isNotBlank() }

    /** Appends the location to the submission's response and Patient. */
    fun appendTo(response: QuestionnaireResponse, patient: Patient) {
        response.extension.removeAll { it.url == EXTENSION_URL }
        response.addExtension(Extension(EXTENSION_URL).apply {
            addExtension("name", StringType(name))
            if (address.isNotBlank()) addExtension("address", StringType(address))
            addExtension("latitude", DecimalType(latitude))
            addExtension("longitude", DecimalType(longitude))
            addExtension("source", StringType(source))
        })

        val home = Address().apply {
            use = Address.AddressUse.HOME
            type = Address.AddressType.PHYSICAL
            text = listOf(name, address).filter { it.isNotBlank() }.distinct().joinToString(", ")
            addExtension(Extension(GEOLOCATION_URL).apply {
                addExtension("latitude", DecimalType(latitude))
                addExtension("longitude", DecimalType(longitude))
            })
        }
        patient.addAddress(home)
    }

    companion object {
        const val EXTENSION_URL = "http://nphiis.health.go.ke/fhir/StructureDefinition/residence-location"
        const val GEOLOCATION_URL = "http://hl7.org/fhir/StructureDefinition/geolocation"

        const val LINK_NAME = "residence-map-name"
        const val LINK_ADDRESS = "residence-map-address"
        const val LINK_LATITUDE = "residence-map-latitude"
        const val LINK_LONGITUDE = "residence-map-longitude"

        private const val PREF_PENDING = "pendingResidenceLocation"

        fun fromJson(json: String?): ResidenceLocation? = runCatching {
            val o = JSONObject(json ?: return null)
            ResidenceLocation(
                name = o.optString("name"),
                address = o.optString("address"),
                latitude = o.getDouble("latitude"),
                longitude = o.getDouble("longitude"),
                source = o.optString("source"),
            )
        }.getOrNull()

        /** Keeps [location] for the form that opens next; it is added when that form is saved. */
        fun stage(context: Context, location: ResidenceLocation) =
            FormatterClass().saveSharedPref(PREF_PENDING, location.toJson(), context)

        /** The staged location, if any (still staged). */
        fun peek(context: Context): ResidenceLocation? =
            fromJson(FormatterClass().getSharedPref(PREF_PENDING, context))

        /** Returns and clears the staged location. */
        fun take(context: Context): ResidenceLocation? = peek(context).also { clear(context) }

        fun clear(context: Context) = FormatterClass().deleteSharedPref(PREF_PENDING, context)
    }
}
