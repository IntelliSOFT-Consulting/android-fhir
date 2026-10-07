package com.icl.surveillance.fhir.forms

import com.icl.surveillance.utils.FormatterClass
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Locale
import org.hl7.fhir.r4.model.Address
import org.hl7.fhir.r4.model.ContactPoint
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.HumanName
import org.hl7.fhir.r4.model.Patient
import timber.log.Timber

/** Maps submitted answers onto the Patient created for a case. */
object PatientMapper {

    /** How a form's three name questions map onto [HumanName]. */
    enum class NameOrder {
        /** family = surname; given = first, middle (Measles, Mpox register). */
        SURNAME_AS_FAMILY,

        /** family = first name; given = middle, surname (AFP, VL — as those forms always did). */
        FIRST_AS_FAMILY,
    }

    fun applyName(patient: Patient, first: String?, middle: String?, surname: String?, order: NameOrder) {
        val name = patient.nameFirstRep
        when (order) {
            NameOrder.SURNAME_AS_FAMILY -> {
                surname?.let { name.family = it }
                first?.let { name.addGiven(it) }
                middle?.let { name.addGiven(it) }
            }

            NameOrder.FIRST_AS_FAMILY -> {
                first?.let { name.family = it }
                middle?.let { name.addGiven(it) }
                surname?.let { name.addGiven(it) }
            }
        }
    }

    /** "Male"/"Female" in any letter case; anything else is recorded as unknown. */
    fun applySex(patient: Patient, sex: String?) {
        sex ?: return
        patient.gender = when (sex.trim().lowercase()) {
            "male" -> Enumerations.AdministrativeGender.MALE
            "female" -> Enumerations.AdministrativeGender.FEMALE
            else -> Enumerations.AdministrativeGender.UNKNOWN
        }
    }

    /** Date of birth answered as yyyy-MM-dd. */
    fun applyBirthDate(patient: Patient, date: String?) {
        date ?: return
        try {
            patient.birthDate = SimpleDateFormat(ISO_DATE, Locale.US).parse(date)
        } catch (e: Exception) {
            Timber.w(e, "Unparseable date of birth: %s", date)
        }
    }

    /** Patient address: sub-county then county, as the case lists expect. */
    fun applyResidence(patient: Patient, county: String, subCounty: String) {
        val address = patient.addressFirstRep
        if (subCounty.isNotEmpty()) {
            address.state = subCounty
            address.addLine(subCounty)
        }
        if (county.isNotEmpty()) {
            address.city = county
            address.addLine(county)
        }
    }

    /** Adds a parent/guardian/contact person. Blank values are skipped. */
    fun applyContact(
        patient: Patient,
        fullName: String? = null,
        phone: String? = null,
        addressLines: List<String> = emptyList(),
    ) {
        val contact = patient.contactFirstRep
        fullName?.let { contact.name = humanName(it) }
        phone?.let { contact.addTelecom(mobile(it)) }
        if (addressLines.isNotEmpty()) {
            contact.address = Address().apply { addressLines.forEach { addLine(it) } }
        }
    }

    fun mobile(number: String): ContactPoint = ContactPoint().apply {
        value = number
        system = ContactPoint.ContactPointSystem.PHONE
        use = ContactPoint.ContactPointUse.MOBILE
    }

    /** "Jane Akinyi Otieno" -> family "Jane", given "Akinyi", "Otieno" (the app's existing format). */
    fun humanName(fullName: String): HumanName {
        val parts = fullName.trim().split(WHITESPACE).filter { it.isNotBlank() }
        return HumanName().apply {
            parts.firstOrNull()?.let { family = it }
            parts.drop(1).forEach { addGiven(it) }
        }
    }

    private const val ISO_DATE = "yyyy-MM-dd"
    private val WHITESPACE = "\\s+".toRegex()
}

/** EPID numbers: `KEN-<county>-<sub-county>-<year>-<module tag>`. */
object EpidNumber {
    const val RUMOR = "RTT-"
    const val MEASLES = "MEA-"
    const val MEASLES_LINE_LIST = "MEA-L"
    const val AFP = "AFP-"
    const val VL = "VL-"
    const val VHF = "VHF-"
    const val MOH_505 = "MOH-505-"
    const val MPOX = "Mpox-"
    const val MPOX_VACCINATION = "MPOVAC-"

    fun kenyan(county: String, subCounty: String, tag: String): String {
        val formatter = FormatterClass()
        return "KEN-${formatter.generateInitials(county)}-${formatter.generateInitials(subCounty)}-" +
                "${LocalDate.now().year}-$tag"
    }

    /** Non-Kenyan Mpox vaccinees: country and vaccination-centre codes instead of county codes. */
    fun foreign(country: String, center: String, tag: String): String =
        "${code3(country)}-${code3(center)}-${LocalDate.now().year}-$tag"

    private fun code3(value: String) = value.padEnd(3, 'X').take(3).uppercase()
}
