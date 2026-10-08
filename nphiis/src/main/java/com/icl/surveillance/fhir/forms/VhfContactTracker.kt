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
)

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

    val firstSymptomaticVisit: ContactVisit?
        get() = visits.filter { it.symptomatic }.minByOrNull { it.day ?: Int.MAX_VALUE }

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
        val caseAnswers = observationsOf(fhirEngine, caseEncounterId)
        val exposureDate = parseDate(caseAnswers.valueOf(FormFields.Vhf.CONTACT_DATE))
        val registered = parseDate(
            caseEncounter.identifier
                .firstOrNull { it.system == FhirSystems.SYSTEM_CREATION }?.value
        ) ?: caseEncounter.meta?.lastUpdated?.let {
            it.toInstant().atZone(ZoneId.systemDefault()).toLocalDate()
        } ?: today
        val dayZero = exposureDate ?: registered

        val children = fhirEngine.search<Encounter> {
            filter(Encounter.SUBJECT, { value = "Patient/$patientId" })
            filter(Encounter.PART_OF, { value = "Encounter/$caseEncounterId" })
        }.map { it.resource }

        val visits = children
            .filter { it.reasonTitle() == FormFields.Vhf.CONTACT_FOLLOW_UP_TITLE }
            .map { encounter ->
                val answers = observationsOf(fhirEngine, encounter.logicalId)
                val date = parseDate(answers.valueOf(FormFields.Vhf.FOLLOW_UP_DATE))
                // The actual date of follow up decides the day when the date of contact is known;
                // demo mode keeps the entered day (demo follow ups are all dated today).
                val enteredDay = answers.valueOf(FormFields.Vhf.FOLLOW_UP_DAY).trim().toIntOrNull()
                val day = if (date != null && exposureDate != null && !VhfDemoMode.enabled) {
                    ChronoUnit.DAYS.between(exposureDate, date).toInt()
                } else {
                    enteredDay
                }
                ContactVisit(
                    encounterId = encounter.logicalId,
                    day = day,
                    date = date,
                    symptomatic = answers.valueOf(FormFields.Vhf.FOLLOW_UP_SYMPTOMATIC)
                        .equals("Yes", ignoreCase = true),
                    recordedAt = encounter.createdAt(),
                )
            }
            .sortedWith(compareBy<ContactVisit> { it.day ?: Int.MAX_VALUE }.thenBy { it.recordedAt })

        val latestMonitoring = children
            .filter { it.reasonTitle() == FormFields.Vhf.CONTACT_MONITORING_TITLE }
            .maxByOrNull { it.createdAt() }
        val monitoringStatus = latestMonitoring
            ?.let { observationsOf(fhirEngine, it.logicalId).valueOf(FormFields.Vhf.MONITORING_STATUS) }
            .orEmpty()

        return ContactFollowUpState(
            caseType = caseAnswers.valueOf(FormFields.Vhf.CASE_TYPE),
            exposureDate = exposureDate,
            dayZero = dayZero,
            daysSinceExposure = ChronoUnit.DAYS.between(dayZero, today).toInt(),
            visits = visits,
            monitoringStatus = monitoringStatus,
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
