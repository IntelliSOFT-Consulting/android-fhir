package com.icl.surveillance.monitor

import com.google.gson.*
import org.hl7.fhir.r4.model.*
import java.lang.reflect.Type


class ResourceDeserializer : JsonDeserializer<Resource> {

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): Resource {
        val jsonObject = json.asJsonObject
        val resourceType = jsonObject.get("resourceType").asString

        return when (resourceType) {
            "Patient" -> context.deserialize(jsonObject, Patient::class.java)
            "Observation" -> context.deserialize(jsonObject, Observation::class.java)
            "Encounter" -> context.deserialize(jsonObject, Encounter::class.java)
            "QuestionnaireResponse" -> context.deserialize(
                jsonObject,
                QuestionnaireResponse::class.java
            )

            "MeasureReport" -> context.deserialize(jsonObject, MeasureReport::class.java)
            "Practitioner" -> context.deserialize(jsonObject, Practitioner::class.java)
            "Organization" -> context.deserialize(jsonObject, Organization::class.java)
            "Location" -> context.deserialize(jsonObject, Location::class.java)
            "Bundle" -> context.deserialize(jsonObject, Bundle::class.java)
            "OperationOutcome" -> context.deserialize(jsonObject, OperationOutcome::class.java)
            // Add other resource types as needed
            else -> throw JsonParseException("Unknown resource type: $resourceType")
        }
    }
}