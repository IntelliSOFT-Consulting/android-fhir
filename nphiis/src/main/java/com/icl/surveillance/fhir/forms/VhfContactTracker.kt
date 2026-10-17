package com.icl.surveillance.fhir.forms

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.get
import com.google.android.fhir.search.search
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import java.time.ZoneId

/** One recorded daily follow-up (vhf-contact-follow-up.json). */
data class ContactVisit(
    val encounterId: String,
    val day: Int?,
    val date: LocalDate?,
    val symptomatic: Boolean,
    val recordedAt: String,
    /** Symptoms ticked at this follow up, as shown ("Fever, Headache"). */
    val symptoms: String = "",
    val bleedingSite: String = "",
    val otherSymptoms: String = "",
    /** Temperatures in °C when taken, e.g. "AM 37.2 °C · PM 38.6 °C". */
    val temperature: String = "",
    /** The same temperatures as numbers (AM, PM, or the older single reading). */
    val temperatures: List<Double> = emptyList(),
    /** "Contact status today" = Died. */
    val died: Boolean = false,
    val deathDate: LocalDate? = null,
    /** "Referred to hospital?" answer, when the day was marked X. */
    val referred: String = "",
) {
    /** Paper form: fever (ticked, or a temperature of 38 °C or more) and/or bleeding. */
    val feverOrBleeding: Boolean
        get() {
            val ticked = symptoms.lowercase()
            return "fever" in ticked || "hemorrhage" in ticked || "bleeding" in ticked ||
                temperatures.any { it >= FormFields.Vhf.FEVER_THRESHOLD_C }
        }

    /** Paper form's daily mark: X when the contact died or has fever and/or bleeding, else 0. */
    val markedX: Boolean get() = died || feverOrBleeding

    val mark: String get() = if (markedX) "X" else "0"

    /** Why the day is X, for display ("Died", "Fever, bleeding", "Temperature 38.4 °C"). */
    val markReason: String
        get() = when {
            died -> "Died"
            !feverOrBleeding -> if (symptomatic) "Other symptoms only" else "No fever or bleeding"
            else -> {
                val ticked = symptoms.lowercase()
                listOfNotNull(
                    "Fever".takeIf { "fever" in ticked },
                    "Bleeding".takeIf { "hemorrhage" in ticked || "bleeding" in ticked },
                    temperatures.filter { it >= FormFields.Vhf.FEVER_THRESHOLD_C }.maxOrNull()
                        ?.let { "Temperature $it °C" },
                ).joinToString(", ")
            }
        }
}

/**
 * Where a VHF contact is in its 21-day follow-up, worked out only from what was captured on the
 * CIF: the contact's "Date of contact" (day 0), the daily "Contact Follow Up" records and the
 * latest "Current monitoring status". Nothing here is stored; it is recalculated on every view.
 */
data class ContactFollowUpState(
    val caseType: String,
    /** "Date of contact" on the contact's record; null when it was not captured. */
    val exposureDate: LocalDate?,
    /** Day 0 of the window: the date of contact, or the registration date when it is missing. */
    val dayZero: LocalDate,
    /** Days from day 0 to today. */
    val daysSinceExposure: Int,
    val visits: List<ContactVisit>,
    /** Latest "Current monitoring status", or blank when no monitoring record exists. */
    val monitoringStatus: String,
    /** The contact's residence on its record (pre-fills where the follow up was done). */
    val residence: Map<String, String> = emptyMap(),
) {
    private val window = FormFields.Vhf.FOLLOW_UP_WINDOW_DAYS

    val isContact: Boolean
        get() = caseType.equals(FormFields.Vhf.CASE_TYPE_CONTACT, ignoreCase = true)

    /** Follow-up day for today, kept within 0-21 for the "Day of follow up" question. */
    val currentDay: Int
        get() = daysSinceExposure.coerceIn(0, window)

    val lastDay: LocalDate
        get() = dayZero.plusDays(window.toLong())

    fun dateOfDay(day: Int): LocalDate = dayZero.plusDays(day.toLong())

    val recordedDays: Set<Int>
        get() = visits.mapNotNull { it.day }.toSet()

    /** Days 1-21 that have passed (today excluded) without a follow-up record. */
    val missedDays: List<Int>
        get() = (1..minOf(daysSinceExposure - 1, window)).filter { it !in recordedDays }

    /** First follow up with symptoms, or marked X for fever / bleeding (a temperature alone counts). */
    val firstSymptomaticVisit: ContactVisit?
        get() = visits.filter { it.symptomatic || it.feverOrBleeding }.minByOrNull { it.day ?: Int.MAX_VALUE }

    /** A daily follow up that recorded the contact as died. */
    val deathVisit: ContactVisit?
        get() = visits.filter { it.died }.minByOrNull { it.day ?: Int.MAX_VALUE }

    /** Daily mark per follow-up day (paper form): "X" or "0"; days without a record are absent. */
    val marks: Map<Int, String>
        get() = visits.filter { it.day != null }.groupBy { it.day!! }
            .mapValues { (_, dayVisits) -> if (dayVisits.any { it.markedX }) "X" else "0" }

    /** A monitoring status other than "Under follow up" ends the follow-up. */
    val isClosed: Boolean
        get() = monitoringStatus.isNotBlank() &&
            !monitoringStatus.equals(FormFields.Vhf.STATUS_UNDER_FOLLOW_UP, ignoreCase = true)

    /** Day 21 has passed, or its follow up is recorded: the contact can be released. */
    val windowComplete: Boolean
        get() = daysSinceExposure > window ||
            ((daysSinceExposure == window || VhfDemoMode.enabled) && window in recordedDays)

    /** The next day in the schedule (1-21) without a record; null once all 21 are recorded. */
    val nextDay: Int?
        get() = (1..window).firstOrNull { it !in recordedDays }

    /**
     * Follow ups are recorded in order; the next day opens once its date has arrived
     * (in demo mode, straight away).
     */
    val nextDayOpen: Boolean
        get() = nextDay?.let { it <= daysSinceExposure || VhfDemoMode.enabled } ?: false

    val isDueToday: Boolean
        get() = isContact && !isClosed && daysSinceExposure in 1..window &&
            daysSinceExposure !in recordedDays

    /** One-line status for the case list and the source case's contact list. */
    val summary: String
        get() = when {
            !isContact -> ""
            isClosed -> monitoringStatus
            deathVisit != null -> "Died on day ${deathVisit?.day ?: "?"} - record outcome"
            firstSymptomaticVisit != null ->
                "Symptomatic on day ${firstSymptomaticVisit?.day ?: "?"} - convert to case"
            windowComplete -> "Day $window complete - close out"
            daysSinceExposure < 0 -> "Follow up starts ${DISPLAY.format(dayZero)}"
            daysSinceExposure in recordedDays -> "Day $daysSinceExposure/$window - followed up"
            else -> "Day $daysSinceExposure/$window - due today"
        }

    companion object {
        val DISPLAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy")
    }
}

object VhfContactTracker {

    /** Set while a contact is being registered from its source case's Contacts tab. */
    const val PREF_SOURCE_PATIENT = "vhfContactSourcePatient"

    /**
     * Loads the follow-up state of the VHF record [caseEncounterId] belonging to [patientId].
     * Returns null when the case encounter cannot be found.
     */
    suspend fun load(
        fhirEngine: FhirEngine,
        patientId: String,
        caseEncounterId: String,
        today: LocalDate = LocalDate.now(),
    ): ContactFollowUpState? {
        val caseEncounter = runCatching { fhirEngine.get<Encounter>(caseEncounterId) }.getOrNull()
            ?: return null
        val caseAnswers = observationsOf(fhirEngine, caseEncounterId).map { it.resource }
        val children = fhirEngine.search<Encounter> {
            filter(Encounter.SUBJECT, { value = "Patient/$patientId" })
            filter(Encounter.PART_OF, { value = "Encounter/$caseEncounterId" })
        }.map { ChildRecord(it.resource.logicalId, it.resource.reasonTitle(), it.resource.createdAt()) }
        val childAnswers = recordsToRead(children).associate { child ->
            child.id to observationsOf(fhirEngine, child.id).map { it.resource }
        }
        return assemble(caseAnswers, registeredOn(caseEncounter, today), children, childAnswers, today)
    }

    /** A follow-up or monitoring record of a contact (a child Encounter of its case encounter). */
    data class ChildRecord(val id: String, val title: String, val createdAt: String)

    /** Answer codes [assemble] reads from the case record (for loading only those). */
    val CASE_ANSWER_CODES = listOf(
        FormFields.Vhf.CASE_TYPE, FormFields.Vhf.CONTACT_DATE, FormFields.Vhf.VILLAGE,
        FormFields.Residence.WARD, FormFields.Residence.SUB_COUNTY, FormFields.Residence.COUNTY,
    )

    /** Answer codes [assemble] reads from follow-up and monitoring records. */
    val CHILD_ANSWER_CODES = listOf(
        FormFields.Vhf.FOLLOW_UP_DATE, FormFields.Vhf.FOLLOW_UP_DAY, FormFields.Vhf.FOLLOW_UP_SYMPTOMATIC,
        FormFields.Vhf.FOLLOW_UP_SYMPTOMS, FormFields.Vhf.FOLLOW_UP_BLEEDING_SITE,
        FormFields.Vhf.FOLLOW_UP_SYMPTOMS_OTHER, FormFields.Vhf.FOLLOW_UP_TEMPERATURE_AM,
        FormFields.Vhf.FOLLOW_UP_TEMPERATURE_PM, FormFields.Vhf.FOLLOW_UP_TEMPERATURE,
        FormFields.Vhf.FOLLOW_UP_CONTACT_STATUS, FormFields.Vhf.FOLLOW_UP_DEATH_DATE,
        FormFields.Vhf.FOLLOW_UP_REFERRED, FormFields.Vhf.MONITORING_STATUS,
    )

    /** The child records whose answers [assemble] needs: every follow up, the latest monitoring. */
    fun recordsToRead(children: List<ChildRecord>): List<ChildRecord> =
        children.filter { it.title == FormFields.Vhf.CONTACT_FOLLOW_UP_TITLE } +
            listOfNotNull(
                children.filter { it.title == FormFields.Vhf.CONTACT_MONITORING_TITLE }.maxByOrNull { it.createdAt }
            )

    /** The day the record was registered (its creation identifier, else when it was last saved). */
    fun registeredOn(caseEncounter: Encounter?, today: LocalDate = LocalDate.now()): LocalDate =
        parseDate(
            caseEncounter?.identifier
                ?.firstOrNull { it.system == FhirSystems.SYSTEM_CREATION }?.value
        ) ?: caseEncounter?.meta?.lastUpdated?.let {
            it.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
        } ?: today

    /**
     * The follow-up state from already-loaded parts: the case record's answers, its child records
     * and their answers (by child id). [load] reads them one record at a time; the case list
     * loads them for a whole page of cases at once.
     */
    fun assemble(
        caseAnswers: List<Observation>,
        registered: LocalDate,
        children: List<ChildRecord>,
        childAnswers: Map<String, List<Observation>>,
        today: LocalDate = LocalDate.now(),
    ): ContactFollowUpState {
        val exposureDate = parseDate(caseAnswers.valueOf(FormFields.Vhf.CONTACT_DATE))
        val dayZero = exposureDate ?: registered

        val visits = children
            .filter { it.title == FormFields.Vhf.CONTACT_FOLLOW_UP_TITLE }
            .map { child ->
                val answers = childAnswers[child.id].orEmpty()
                val date = parseDate(answers.valueOf(FormFields.Vhf.FOLLOW_UP_DATE))
                // The actual date of follow up decides the day when the date of contact is known;
                // demo mode keeps the entered day (demo follow ups are all dated today).
                val enteredDay = answers.valueOf(FormFields.Vhf.FOLLOW_UP_DAY).trim().toIntOrNull()
                val day = if (date != null && exposureDate != null && !VhfDemoMode.enabled) {
                    ChronoUnit.DAYS.between(exposureDate, date).toInt()
                } else {
                    enteredDay
                }
                val status = answers.valueOf(FormFields.Vhf.FOLLOW_UP_CONTACT_STATUS).trim()
                ContactVisit(
                    encounterId = child.id,
                    day = day,
                    date = date,
                    symptomatic = answers.valueOf(FormFields.Vhf.FOLLOW_UP_SYMPTOMATIC)
                        .equals("Yes", ignoreCase = true),
                    recordedAt = child.createdAt,
                    symptoms = answers.valueOf(FormFields.Vhf.FOLLOW_UP_SYMPTOMS),
                    bleedingSite = answers.valueOf(FormFields.Vhf.FOLLOW_UP_BLEEDING_SITE),
                    otherSymptoms = answers.valueOf(FormFields.Vhf.FOLLOW_UP_SYMPTOMS_OTHER),
                    temperatures = listOf(
                        FormFields.Vhf.FOLLOW_UP_TEMPERATURE_AM,
                        FormFields.Vhf.FOLLOW_UP_TEMPERATURE_PM,
                        FormFields.Vhf.FOLLOW_UP_TEMPERATURE,
                    ).mapNotNull { answers.valueOf(it).trim().toDoubleOrNull() },
                    died = status.equals("Died", ignoreCase = true) ||
                        status.equals(FormFields.Vhf.STATUS_DIED_CODE, ignoreCase = true),
                    deathDate = parseDate(answers.valueOf(FormFields.Vhf.FOLLOW_UP_DEATH_DATE)),
                    referred = answers.valueOf(FormFields.Vhf.FOLLOW_UP_REFERRED),
                    temperature = listOfNotNull(
                        answers.valueOf(FormFields.Vhf.FOLLOW_UP_TEMPERATURE_AM).takeIf { it.isNotBlank() }?.let { "AM $it °C" },
                        answers.valueOf(FormFields.Vhf.FOLLOW_UP_TEMPERATURE_PM).takeIf { it.isNotBlank() }?.let { "PM $it °C" },
                        answers.valueOf(FormFields.Vhf.FOLLOW_UP_TEMPERATURE).takeIf { it.isNotBlank() }?.let { "$it °C" },
                    ).joinToString(" · "),
                )
            }
            .sortedWith(compareBy<ContactVisit> { it.day ?: Int.MAX_VALUE }.thenBy { it.recordedAt })

        val latestMonitoring = children
            .filter { it.title == FormFields.Vhf.CONTACT_MONITORING_TITLE }
            .maxByOrNull { it.createdAt }
        val monitoringStatus = latestMonitoring
            ?.let { childAnswers[it.id].orEmpty().valueOf(FormFields.Vhf.MONITORING_STATUS) }
            .orEmpty()

        return ContactFollowUpState(
            caseType = caseAnswers.valueOf(FormFields.Vhf.CASE_TYPE),
            exposureDate = exposureDate,
            dayZero = dayZero,
            daysSinceExposure = ChronoUnit.DAYS.between(dayZero, today).toInt(),
            visits = visits,
            monitoringStatus = monitoringStatus,
            residence = mapOf(
                FormFields.Vhf.FOLLOW_UP_VILLAGE to caseAnswers.valueOf(FormFields.Vhf.VILLAGE),
                FormFields.Vhf.FOLLOW_UP_WARD to caseAnswers.valueOf(FormFields.Residence.WARD),
                FormFields.Vhf.FOLLOW_UP_SUB_COUNTY to caseAnswers.valueOf(FormFields.Residence.SUB_COUNTY),
                FormFields.Vhf.FOLLOW_UP_COUNTY to caseAnswers.valueOf(FormFields.Residence.COUNTY),
            ).filterValues { it.isNotBlank() && !it.startsWith("Location/") },
        )
    }

    /** Ids of VHF records registered as contacts of [sourcePatientId] (Patient.link). */
    suspend fun linkedContactIds(fhirEngine: FhirEngine, sourcePatientId: String): List<String> =
        fhirEngine.search<Patient> {
            filter(Patient.LINK, { value = "Patient/$sourcePatientId" })
        }.map { it.resource.logicalId }

    private suspend fun observationsOf(fhirEngine: FhirEngine, encounterId: String) =
        fhirEngine.search<Observation> {
            filter(Observation.ENCOUNTER, { value = "Encounter/$encounterId" })
        }

    private fun Encounter.reasonTitle(): String = reasonCodeFirstRep.codingFirstRep.code.orEmpty()

    private fun Encounter.createdAt(): String =
        identifier.firstOrNull { it.system == FhirSystems.SYSTEM_CREATION }?.value.orEmpty()

    /** Accepts `yyyy-MM-dd` and `yyyy-MM-dd HH:mm:ss` (only the date part is used). */
    fun parseDate(value: String?): LocalDate? {
        val text = value?.trim().orEmpty()
        if (text.length < 10) return null
        return runCatching { LocalDate.parse(text.substring(0, 10)) }.getOrNull()
    }
}
