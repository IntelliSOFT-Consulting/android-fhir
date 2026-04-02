package com.icl.surveillance.fhir

import org.hl7.fhir.r4.model.CodeableConcept
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Identifier


class ResourceIdentification {

    fun createLocationIdentifier(
        typeCode: String,
        typeDisplay: String,
        value: String,
        system: String,
        display: String
    ): Identifier {
        return Identifier().apply {
            use = Identifier.IdentifierUse.OFFICIAL
            this.system = system
            this.value = value
            this.type = CodeableConcept().apply {
                coding = listOf(
                    Coding().apply {
                        this.system = "http://terminology.hl7.org/CodeSystem/v2-0203"
                        this.code = typeCode
                        this.display = typeDisplay
                    }
                )
                this.text = display
            }
        }
    }


}