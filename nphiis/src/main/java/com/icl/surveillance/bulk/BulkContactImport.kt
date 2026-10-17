package com.icl.surveillance.bulk

import android.content.Context
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum
import ca.uhn.fhir.model.api.TemporalPrecisionEnum
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.validation.Invalid
import com.google.android.fhir.datacapture.validation.QuestionnaireResponseValidator
import com.google.android.fhir.get
import com.google.android.fhir.search.search
import com.icl.surveillance.fhir.forms.CaseSlugs
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.fhir.forms.LocalWins
import com.icl.surveillance.fhir.forms.ReportingSitePrefill
import com.icl.surveillance.fhir.forms.VhfContactTracker
import com.icl.surveillance.fhir.forms.VhfSourceCases
import com.icl.surveillance.fhir.forms.valueOf
import com.icl.surveillance.models.LocationLevel
import com.icl.surveillance.models.UserRole
import com.icl.surveillance.ui.patients.CaseIdentifierIndex
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import timber.log.Timber
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.DecimalType
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Location
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemComponent
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemType
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.StringType
import org.hl7.fhir.r4.model.Type

/**
 * Bulk upload of VHF contacts from a spreadsheet laid out like the MoH Contact Listing Form
 * (the contact form, vhf-contact.json; sheets laid out like the case form still work for the
 * questions the two forms share).
 *
 * Columns are matched to the form's questions by linkId or by question text (case and spacing
 * ignored; repeated texts such as "County" go to the form's questions in order). Each row
 * becomes a filled-in contact form, linked to its source case (picked in the app, or the row's
 * "Case ID" EPID number), and is then saved through the app's normal case save (Patient,
 * Encounter, answers, EPID, link, follow-up schedule).
 */
class BulkContactImport(private val context: Context, private val fhirEngine: FhirEngine) {

    /** Line-list columns that do not map to one question but are worked out (see [planRow]). */
    enum class Special { FULL_NAME, AGE_YEARS, RELATIONSHIP, FLIGHT_SEAT, COMMENT, CONTACT_TYPE, HEALTH_WORKER }

    data class Column(
        val index: Int,
        val header: String,
        val item: QuestionnaireItemComponent?,
        val special: Special? = null,
    ) {
        val used get() = item != null || special != null
    }

    data class RowPlan(
        val rowNumber: Int,
        val name: String,
        val response: QuestionnaireResponse?,
        /** Problems that stop the row from being imported. */
        val errors: List<String>,
        /** Values that could not be used (the rest of the row is imported). */
        val warnings: List<String>,
        /** Required questions left unanswered. */
        val missingRequired: List<String>,
        val sourcePatientId: String?,
        val duplicateOf: String?,
        /** Residence county / sub-county (they make the EPID number), e.g. "NAIROBI, KIBRA". */
        val residence: String = "",
    ) {
        val importable get() = response != null && errors.isEmpty() && duplicateOf == null
    }

    /** The health facility the contacts are reported from, with its ward / sub-county / county. */
    data class ReportingSite(
        val facility: Location,
        val ward: Location?,
        val subCounty: Location?,
        val county: Location?,
    ) {
        val label: String
            get() = listOfNotNull(facility.name, ward?.name, subCounty?.name, county?.name)
                .filter { it.isNotBlank() }.joinToString(", ")
    }

    data class Plan(
        val columns: List<Column>,
        val unmatchedHeaders: List<String>,
        val rows: List<RowPlan>,
    )

    val questionnaire: Questionnaire by lazy {
        val json = context.assets.open(FormFields.Vhf.CONTACT_FORM).bufferedReader().use { it.readText() }
        FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()
            .parseResource(Questionnaire::class.java, json)
    }

    private val questions: List<QuestionnaireItemComponent> by lazy {
        val all = ArrayList<QuestionnaireItemComponent>()
        fun walk(items: List<QuestionnaireItemComponent>) {
            for (item in items) {
                if (item.type != QuestionnaireItemType.GROUP && item.type != QuestionnaireItemType.DISPLAY) all += item
                walk(item.item)
            }
        }
        walk(questionnaire.item)
        all
    }

    /** Matches the header row to the form's questions. */
    fun matchColumns(headers: List<String>): List<Column> {
        val byLinkId = questions.filter { it.linkId !in REPORTING_SITE_QUESTIONS }.associateBy { normalize(it.linkId) } +
            ALIASES.mapNotNull { (alias, linkId) ->
                questions.firstOrNull { it.linkId == linkId }?.let { normalize(alias) to it }
            }
        // The Reporting Site questions are filled from the chosen facility, never from a column:
        // a "County" / "Sub-county" column is the person's residence (and drives the EPID).
        val byText = questions.filter { it.linkId !in REPORTING_SITE_QUESTIONS }
            .groupBy { normalize(it.text.orEmpty()) }
        val used = HashSet<String>()
        val usedSpecials = HashSet<Special>()
        // "Date of Exposure (dd.mm.yyyy)" -> "date of exposure": a name followed by more words.
        fun startsWithWords(key: String, name: String) = key == name || key.startsWith("$name ")
        return headers.mapIndexed { index, header ->
            val key = normalize(header)
            if (key.isEmpty()) return@mapIndexed Column(index, header, null)
            byLinkId[key]?.takeIf { it.linkId !in used }?.let { item ->
                used += item.linkId
                return@mapIndexed Column(index, header, item)
            }
            SPECIALS.entries.firstOrNull { (name, special) ->
                special !in usedSpecials && startsWithWords(key, normalize(name))
            }?.let { (_, special) ->
                usedSpecials += special
                return@mapIndexed Column(index, header, null, special)
            }
            val item = byText[key]?.firstOrNull { it.linkId !in used }
                ?: LINE_LIST_ALIASES.entries.firstOrNull { (name, _) -> startsWithWords(key, normalize(name)) }
                    ?.let { (_, linkId) -> questions.firstOrNull { it.linkId == linkId && it.linkId !in used } }
            item?.let { used += it.linkId }
            Column(index, header, item)
        }
    }

    /**
     * Reads every row into a form response, ready to import. [sourceEpid]: the case picked in
     * the app (every row becomes its contact); null to use each row's "Case ID" (source EPID).
     */
    suspend fun plan(table: SpreadsheetReader.Table, sourcePatientId: String?, site: ReportingSite?): Plan {
        val columns = matchColumns(table.headers)
        val unmatched = columns.filter { !it.used && it.header.isNotBlank() }.map { it.header.replace('\n', ' ') }
        val seenInFile = HashSet<String>()
        val rows = table.rows
            // Rows with nothing in the columns used (e.g. a row of follow-up dates under the header).
            .filter { row -> columns.any { it.used && !row.cells[it.index].isNullOrBlank() } }
            .map { row -> planRow(row, columns, sourcePatientId, seenInFile, site) }
        return Plan(columns.filter { it.used }, unmatched, rows)
    }

    private suspend fun planRow(
        row: SpreadsheetReader.Row,
        columns: List<Column>,
        pickedSourceId: String?,
        seenInFile: MutableSet<String>,
        site: ReportingSite?,
    ): RowPlan {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        val answers = LinkedHashMap<String, List<Type>>()
        val chosenLocations = ArrayList<String>()

        for (column in columns) {
            val raw = row.cells[column.index]?.trim().orEmpty()
            if (raw.isEmpty()) continue
            column.special?.let { special ->
                applySpecial(special, column.header, raw, answers, errors, warnings)
                continue
            }
            val item = column.item ?: continue
            // Its choices are the cases on the device (filled in at runtime): keep the text.
            if (item.linkId == FormFields.Vhf.SOURCE_CASE_EPID) {
                answers[item.linkId] = listOf(StringType(raw))
                continue
            }
            val values = convert(item, raw, chosenLocations)
            if (values.isEmpty()) {
                warnings += "${column.header.replace('\n', ' ')}: \"$raw\" not understood"
            } else {
                answers[item.linkId] = values
            }
        }
        completeResidence(answers, warnings)
        contactFormDefaults(answers)

        // Reporting site, as the form fills it for this user (hidden role + area, and the
        // facility in the section for the user's level); the save tags records with it.
        if (site == null) errors += "No reporting health facility (pick one above)"
        else answers.putAll(reportingAnswers(site))

        // Every row is a contact.
        answers[FormFields.Vhf.CASE_TYPE] = listOf(coding(FormFields.Vhf.CASE_TYPE, "contact") ?: Coding().setCode("contact"))

        // Source case: the case picked in the app (by its record, EPID or not), else the row's EPID.
        val rowEpid = answers[FormFields.Vhf.SOURCE_CASE_EPID]?.firstOrNull()?.let { VhfSourceCases.epidFrom(display(it)) }
        val source = when {
            pickedSourceId != null -> runCatching { fhirEngine.get<Patient>(pickedSourceId) }.getOrNull()
            rowEpid != null -> VhfSourceCases.findByEpid(fhirEngine, rowEpid)
            else -> null
        }
        when {
            pickedSourceId == null && rowEpid == null ->
                errors += "No source case (pick one above, or fill the \"Case ID\" column with its EPID number)"
            source == null -> errors += "Source case ${rowEpid ?: ""} is not on this device"
            else -> {
                // The case's EPID when it has one; the link itself is by record (see BulkUploadActivity).
                val epid = VhfSourceCases.epidOf(source)
                if (epid != null) answers[FormFields.Vhf.SOURCE_CASE_EPID] = listOf(Coding().setCode(epid).setDisplay(epid))
                else answers.remove(FormFields.Vhf.SOURCE_CASE_EPID)
                // As when registering a contact from its case: human contact, the case's disease.
                if (FormFields.Vhf.EXPOSURE_TYPE !in answers) {
                    coding(FormFields.Vhf.EXPOSURE_TYPE, FormFields.Vhf.EXPOSURE_HUMAN_CONTACT)
                        ?.let { answers[FormFields.Vhf.EXPOSURE_TYPE] = listOf(it) }
                }
                if (FormFields.Vhf.DISEASE !in answers) {
                    sourceDisease(source)?.let { answers[FormFields.Vhf.DISEASE] = listOf(it) }
                }
            }
        }

        val surname = answers[FormFields.Person.SURNAME]?.firstOrNull()?.let(::display).orEmpty()
        val given = answers[FormFields.Person.FIRST_NAME]?.firstOrNull()?.let(::display).orEmpty()
        val name = "$given $surname".trim()
        if (name.isEmpty()) errors += "No name"

        // Duplicates: the same person twice in the file, or already a contact of the case.
        val idNumber = answers[FormFields.Vhf.NATIONAL_ID]?.firstOrNull()?.let(::display)
        val phone = answers[FormFields.Vhf.PHONE]?.firstOrNull()?.let(::display)
        val fileKey = idNumber?.lowercase() ?: "${name.lowercase()}|${phone.orEmpty()}"
        var duplicateOf: String? = null
        if (name.isNotEmpty() && !seenInFile.add("${source?.idElement?.idPart}|$fileKey")) {
            duplicateOf = "an earlier row of this file"
        } else if (source != null && name.isNotEmpty()) {
            duplicateOf = existingContact(source.idElement.idPart, name, phone, idNumber)
        }

        val response = if (errors.isEmpty()) buildResponse(answers) else null
        val missing = response?.let { missingRequired(it) }.orEmpty()
        val residence = listOf(COUNTY, SUB_COUNTY)
            .mapNotNull { answers[it]?.firstOrNull()?.let(::display)?.takeIf { text -> text.isNotBlank() } }
            .joinToString(", ")
        if (residence.isEmpty()) {
            warnings += "No residence county / sub-county: the EPID uses the reporting facility's"
        }
        return RowPlan(row.number, name.ifEmpty { "(no name)" }, response, errors, warnings, missing,
            source?.idElement?.idPart, duplicateOf, residence)
    }

    enum class LinkCheck(val label: String) {
        LINKED("linked to the case"),
        LINK_ADDED("link to the case added after saving"),
        NOT_VISIBLE("saved, but NOT found under the case"),
        NOT_FOUND("saved record not found"),
    }

    /**
     * After a contact is saved: makes sure its Patient links to the source case (adding the link
     * if the save did not), then checks the case's Contacts tab will find it (the same search).
     */
    suspend fun ensureLinked(patientId: String, sourceId: String): LinkCheck {
        val patient = runCatching { fhirEngine.get<Patient>(patientId) }.getOrNull() ?: return LinkCheck.NOT_FOUND
        val wasLinked = patient.link.any { it.other?.referenceElement?.idPart == sourceId }
        if (!wasLinked) {
            patient.addLink().setOther(Reference("Patient/$sourceId")).setType(Patient.LinkType.SEEALSO)
            LocalWins.update(fhirEngine, patient)
            CaseIdentifierIndex.put(patient)
        }
        val visible = patientId in VhfContactTracker.linkedContactIds(fhirEngine, sourceId)
        return when {
            !visible -> LinkCheck.NOT_VISIBLE
            wasLinked -> LinkCheck.LINKED
            else -> LinkCheck.LINK_ADDED
        }
    }

    data class SourceCase(val patientId: String, val label: String)

    /**
     * VHF cases on this device that contacts can be added to: every VHF record that is not
     * itself a contact, with or without an EPID number yet (newest first).
     */
    suspend fun sourceCases(): List<SourceCase> =
        CaseIdentifierIndex.patientsWith(fhirEngine, setOf(CaseSlugs.VHF)).keys
            .mapNotNull { id -> runCatching { fhirEngine.get<Patient>(id) }.getOrNull() }
            .mapNotNull { patient ->
                val type = caseTypeOf(patient)
                if (type.equals(FormFields.Vhf.CASE_TYPE_CONTACT, ignoreCase = true)) return@mapNotNull null
                // Privacy: source cases are listed by EPID number only, never by name.
                val epid = VhfSourceCases.epidOf(patient) ?: "EPID pending (record ${patient.idElement.idPart.take(8)})"
                val label = epid + if (type.isNotBlank()) " ($type)" else ""
                patient to SourceCase(patient.idElement.idPart, label)
            }
            .sortedByDescending { (patient, _) -> patient.meta.lastUpdated?.time ?: 0L }
            .map { it.second }

    private suspend fun caseTypeOf(patient: Patient): String {
        val encounterId = patient.identifier.firstOrNull { it.system == CaseSlugs.VHF }?.value ?: return ""
        return fhirEngine.search<Observation> {
            filter(Observation.ENCOUNTER, { value = "Encounter/$encounterId" })
            filter(Observation.CODE, { value = of(Coding().apply { code = FormFields.Vhf.CASE_TYPE }) })
        }.valueOf(FormFields.Vhf.CASE_TYPE)
    }

    /** The source case's disease answer (Ebola, Marburg...), as a choice of the form. */
    private suspend fun sourceDisease(source: Patient): Type? {
        val encounterId = source.identifier.firstOrNull { it.system == CaseSlugs.VHF }?.value ?: return null
        val answer = fhirEngine.search<Observation> {
            filter(Observation.ENCOUNTER, { value = "Encounter/$encounterId" })
            filter(Observation.CODE, { value = of(Coding().apply { code = FormFields.Vhf.DISEASE }) })
        }.valueOf(FormFields.Vhf.DISEASE)
        val item = questions.firstOrNull { it.linkId == FormFields.Vhf.DISEASE } ?: return null
        return answer.takeIf { it.isNotBlank() }?.let { choice(item, it) }
    }

    /** A contact of [sourceId] already on the device with the same ID number, or name and phone. */
    private suspend fun existingContact(sourceId: String, name: String, phone: String?, idNumber: String?): String? =
        VhfContactTracker.linkedContactIds(fhirEngine, sourceId)
            .mapNotNull { id -> runCatching { fhirEngine.get<Patient>(id) }.getOrNull() }
            .firstOrNull { patient ->
                val sameId = idNumber != null && patient.identifier.any { it.value.equals(idNumber, ignoreCase = true) }
                val sameName = patient.nameFirstRep.nameAsSingleString.trim().equals(name, ignoreCase = true) ||
                    patient.nameFirstRep.let { "${it.givenAsSingleString} ${it.family}".trim() }.equals(name, ignoreCase = true)
                val samePhone = phone == null || patient.telecom.any { digits(it.value) == digits(phone) }
                sameId || (sameName && samePhone)
            }
            ?.let { patient -> "existing contact ${patient.nameFirstRep.nameAsSingleString.trim()}" }

    // ---- Values

    private suspend fun convert(item: QuestionnaireItemComponent, raw: String, chosenLocations: MutableList<String>): List<Type> {
        val parts = if (item.repeats) raw.split(';', '|', '\n').map { it.trim() }.filter { it.isNotEmpty() } else listOf(raw)
        return parts.mapNotNull { value -> convertOne(item, value, chosenLocations) }
            .let { if (item.repeats) it else it.take(1) }
    }

    private suspend fun convertOne(item: QuestionnaireItemComponent, value: String, chosenLocations: MutableList<String>): Type? =
        when (item.type) {
            QuestionnaireItemType.STRING, QuestionnaireItemType.TEXT ->
                if (item.linkId in PHONE_QUESTIONS) phone(value)?.let { StringType(it) } else StringType(value)
            QuestionnaireItemType.INTEGER -> value.toBigDecimalOrNull()?.toInt()?.let { IntegerType(it) }
            QuestionnaireItemType.DECIMAL -> value.toBigDecimalOrNull()?.let { DecimalType(it) }
            QuestionnaireItemType.BOOLEAN -> yesNo(value)?.let { BooleanType(it) }
            QuestionnaireItemType.DATE -> parseDate(value)?.let { DateType(it, TemporalPrecisionEnum.DAY) }
            QuestionnaireItemType.DATETIME -> parseDateTime(value)?.let { DateTimeType(it, TemporalPrecisionEnum.SECOND) }
            QuestionnaireItemType.CHOICE, QuestionnaireItemType.OPENCHOICE ->
                choice(item, value) ?: if (item.type == QuestionnaireItemType.OPENCHOICE) StringType(value) else null
            QuestionnaireItemType.REFERENCE -> location(value, chosenLocations, item.linkId)
            else -> null
        }

    /** A choice by code or display (case and spacing ignored); "Yes"/"No" also as Y/N/1/0. */
    private fun choice(item: QuestionnaireItemComponent, value: String): Type? {
        val key = normalize(value)
        val options = item.answerOption.map { it.value }
        options.firstOrNull { option ->
            when (option) {
                is Coding -> normalize(option.code.orEmpty()) == key || normalize(option.display.orEmpty()) == key
                else -> normalize(option.primitiveValue().orEmpty()) == key
            }
        }?.let { return it.copy() }
        yesNo(value)?.let { yes ->
            options.firstOrNull { (it as? Coding)?.code == if (yes) "yes" else "no" }?.let { return it.copy() }
        }
        // A short form that starts only one option, e.g. "M" -> Male, "F" -> Female.
        if (key.isNotEmpty()) {
            options.filter { option ->
                val display = normalize((option as? Coding)?.display ?: option.primitiveValue().orEmpty())
                display.startsWith(key)
            }.singleOrNull()?.let { return it.copy() }
        }
        return null
    }

    private fun coding(linkId: String, code: String): Coding? =
        questions.firstOrNull { it.linkId == linkId }?.answerOption
            ?.mapNotNull { it.value as? Coding }?.firstOrNull { it.code == code }?.copy()

    /**
     * A Location by name (spacing, case and punctuation ignored: "Lang'ata" = "Langata"), at the
     * level the question asks for: the server walks sub-county -> county -> country from these
     * answers, so a same-named ward or facility must never be picked for the sub-county.
     * When several still match, the one inside an area chosen earlier in the row wins.
     * The County question also accepts a sub-county ([completeResidence] moves it down).
     */
    private suspend fun location(name: String, chosenLocations: MutableList<String>, linkId: String): Type? {
        val matches = locationsNamed(name)
        val candidates = when (linkId) {
            COUNTY -> matches.filter { it.isCounty() }.ifEmpty { matches.filter { isSubCounty(it) } }
            SUB_COUNTY -> matches.filter { isSubCounty(it) }
            WARD -> matches.filter { isWard(it) }
            else -> matches
        }
        val location = candidates.firstOrNull { it.partOf?.referenceElement?.idPart in chosenLocations }
            ?: candidates.firstOrNull() ?: return null
        val id = location.idElement.idPart
        chosenLocations += id
        return Reference("Location/$id").setDisplay(location.name)
    }

    private val locationCache = HashMap<String, Location?>()

    private suspend fun locationById(id: String?): Location? {
        if (id.isNullOrBlank()) return null
        return locationCache.getOrPut(id) { runCatching { fhirEngine.get<Location>(id) }.getOrNull() }
    }

    private suspend fun parentOf(location: Location): Location? =
        locationById(location.partOf?.referenceElement?.idPart)

    private suspend fun isSubCounty(location: Location) = parentOf(location)?.isCounty() == true

    private suspend fun isWard(location: Location) = parentOf(location)?.let { isSubCounty(it) } == true

    private suspend fun locationsNamed(name: String): List<Location> {
        val key = locationKey(name)
        if (key.isEmpty()) return emptyList()
        // The name index is searched by its start; the full name is then compared loosely.
        val start = name.trim().takeWhile { it.isLetterOrDigit() }.ifEmpty { name.trim() }
        return fhirEngine.search<Location> { filter(Location.NAME, { value = start }) }
            .map { it.resource }
            .filter { locationKey(it.name.orEmpty()) == key }
    }

    private fun Location.isCounty() = partOf?.referenceElement?.idPart == "0"

    /**
     * Answers the contact form fills itself when a contact is registered in the app: date of
     * birth known / unknown (from the row), country and date of listing (today). Row values win.
     */
    private fun contactFormDefaults(answers: MutableMap<String, List<Type>>) {
        if (DOB_KNOWN !in answers) {
            val known = FormFields.Person.DATE_OF_BIRTH in answers
            coding(DOB_KNOWN, if (known) "known" else "unknown")?.let { answers[DOB_KNOWN] = listOf(it) }
        }
        if (COUNTRY !in answers) answers[COUNTRY] = listOf(StringType("Kenya"))
        if (LISTING_DATE !in answers) answers[LISTING_DATE] = listOf(DateType(Date(), TemporalPrecisionEnum.DAY))
    }

    /**
     * Makes the residence a complete chain the server can resolve (sub-county -> county ->
     * country), filling missing levels from the levels below:
     * - a sub-county in the County column ("Embakasi East") becomes the sub-county;
     * - a ward without a sub-county gives its sub-county;
     * - the county is always the sub-county's own county (a mismatch is corrected and noted).
     */
    private suspend fun completeResidence(answers: MutableMap<String, List<Type>>, warnings: MutableList<String>) {
        fun refId(linkId: String) = (answers[linkId]?.firstOrNull() as? Reference)?.referenceElement?.idPart
        fun ref(location: Location) = listOf(Reference("Location/${location.idElement.idPart}").setDisplay(location.name))

        // Sub-county given in the County column.
        locationById(refId(COUNTY))?.let { county ->
            if (!county.isCounty()) {
                if (SUB_COUNTY !in answers && isSubCounty(county)) answers[SUB_COUNTY] = ref(county)
                answers.remove(COUNTY)
            }
        }
        // Sub-county from the ward.
        val ward = locationById(refId(WARD))
        if (ward != null && SUB_COUNTY !in answers) {
            parentOf(ward)?.takeIf { isSubCounty(it) }?.let { answers[SUB_COUNTY] = ref(it) }
        }
        // County from the sub-county.
        val subCounty = locationById(refId(SUB_COUNTY)) ?: return
        val county = parentOf(subCounty)?.takeIf { it.isCounty() }
        if (county == null) {
            warnings += "Sub county \"${subCounty.name}\" is not linked to a county on this device; left out"
            answers.remove(SUB_COUNTY)
            return
        }
        val given = refId(COUNTY)
        if (given != null && given != county.idElement.idPart) {
            warnings += "County changed to ${county.name} (the county of ${subCounty.name})"
        }
        answers[COUNTY] = ref(county)
        if (ward != null && parentOf(ward)?.idElement?.idPart != subCounty.idElement.idPart) {
            warnings += "Ward \"${ward.name}\" is not in ${subCounty.name}; left out"
            answers.remove(WARD)
        }
    }

    /**
     * Columns of contact line lists worked out into the form's questions: full name, age,
     * free-text relationship, flight seat, and a comment marking the row as a confirmed case.
     */
    private fun applySpecial(
        special: Special,
        header: String,
        raw: String,
        answers: MutableMap<String, List<Type>>,
        errors: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        when (special) {
            Special.FULL_NAME -> {
                val words = raw.split(Regex("\\s+")).filter { it.isNotBlank() }
                if (words.isEmpty()) return
                answers[FormFields.Person.SURNAME] = listOf(StringType(words.last()))
                answers[FormFields.Person.FIRST_NAME] =
                    listOf(StringType(if (words.size > 1) words.dropLast(1).joinToString(" ") else words.first()))
            }

            Special.AGE_YEARS -> {
                val years = raw.toBigDecimalOrNull()?.toInt()
                if (years == null || years < 0 || years > 120) {
                    warnings += "${header.replace('\n', ' ')}: \"$raw\" not understood"
                    return
                }
                if (FormFields.Person.DATE_OF_BIRTH !in answers) {
                    coding(DOB_KNOWN, "unknown")?.let { answers[DOB_KNOWN] = listOf(it) }
                    answers[AGE_YEARS] = listOf(IntegerType(years))
                }
            }

            Special.RELATIONSHIP -> {
                val text = raw.trim()
                val key = normalize(text)
                val code = when {
                    FAMILY_WORDS.any { key.contains(it) } -> "family"
                    HEALTH_WORKER_WORDS.any { key == it || key.contains(it) } -> "hcw"
                    key.contains("friend") -> "friend"
                    key.contains("workmate") || key.contains("colleague") -> "workmate"
                    else -> "other"
                }
                val option = coding(RELATIONSHIP, code) ?: coding(RELATIONSHIP, "other") ?: return
                answers[RELATIONSHIP] = listOf(option)
                if (option.code == "other") answers[RELATIONSHIP_OTHER] = listOf(StringType(text))
                // Line lists have no "Healthcare worker" column: a health worker relation
                // (HCW, nurse, lab, port health...) answers it; a "Healthcare worker" column wins.
                if (FormFields.Vhf.CONTACT_HCW !in answers) {
                    coding(FormFields.Vhf.CONTACT_HCW, if (code == "hcw") "yes" else "no")
                        ?.let { answers[FormFields.Vhf.CONTACT_HCW] = listOf(it) }
                }
            }

            Special.FLIGHT_SEAT -> {
                val seat = raw.trim()
                if (normalize(seat) in setOf("n a", "na", "none", "nil", "")) return
                if (CONTACT_LOCATION !in answers) coding(CONTACT_LOCATION, "travel")?.let { answers[CONTACT_LOCATION] = listOf(it) }
                if (EXPOSURE_LOCATION !in answers) answers[EXPOSURE_LOCATION] = listOf(StringType("Flight, seat $seat"))
            }

            Special.COMMENT -> {
                if (normalize(raw).contains("confirmed case")) errors += "Marked as a confirmed case, not a contact"
            }

            // "1, 3", "1 2 4", "2;4" or words ("body fluids, household"): the form's types 1-4.
            Special.CONTACT_TYPE -> {
                val key = normalize(raw)
                val codes = LinkedHashSet<String>()
                Regex("[1-4]").findAll(raw).forEach { CONTACT_TYPE_CODES[it.value]?.let(codes::add) }
                val words = key.split(' ')
                CONTACT_TYPE_WORDS.forEach { (word, code) ->
                    // Phrases anywhere; single words only as the start of a word ("ate" is not "date").
                    val found = if (' ' in word) key.contains(word) else words.any { it.startsWith(word) }
                    if (found) codes += code
                }
                val options = codes.mapNotNull { coding(FormFields.Vhf.CONTACT_CATEGORY, it) }
                if (options.isEmpty()) warnings += "${header.replace('\n', ' ')}: \"$raw\" not understood (use 1, 2, 3, 4)"
                else answers[FormFields.Vhf.CONTACT_CATEGORY] = options
            }

            // "Y", "N", "Yes - Kenyatta National Hospital", "Y (KNH)": the answer and the facility.
            Special.HEALTH_WORKER -> {
                val text = raw.trim()
                val first = text.split(Regex("[\\s,;:()\\-–/]+")).firstOrNull().orEmpty()
                val yes = yesNo(first) ?: yesNo(text)
                if (yes == null) {
                    // Only a facility name: the contact works there.
                    coding(FormFields.Vhf.CONTACT_HCW, "yes")?.let { answers[FormFields.Vhf.CONTACT_HCW] = listOf(it) }
                    answers[FormFields.Vhf.CONTACT_HCW_FACILITY] = listOf(StringType(text))
                    return
                }
                coding(FormFields.Vhf.CONTACT_HCW, if (yes) "yes" else "no")
                    ?.let { answers[FormFields.Vhf.CONTACT_HCW] = listOf(it) }
                val facility = text.drop(first.length).trim(' ', '-', '–', ':', ',', ';', '(', ')', '/').trim()
                if (yes && facility.isNotBlank() && FormFields.Vhf.CONTACT_HCW_FACILITY !in answers) {
                    answers[FormFields.Vhf.CONTACT_HCW_FACILITY] = listOf(StringType(facility))
                }
            }
        }
    }

    // ---- Reporting site

    private val prefs = com.icl.surveillance.utils.FormatterClass()
    private fun pref(key: String) = prefs.getSharedPref(key, context).orEmpty()

    private val userScope: LocationLevel?
        get() = UserRole.fromAny(pref("practitionerRole"))?.scope

    val isFacilityUser: Boolean get() = userScope == LocationLevel.FACILITY

    /**
     * The form's Reporting Site answers for [site], filled as the synthetic data generator does:
     * the hidden role / assigned-area answers from the user's profile (ReportingSitePrefill), and
     * the county / sub-county / ward / facility chain in every variant of those questions (one
     * per user level), so the section the form shows for this user, and the facility the save
     * reads (AddClientViewModel.resolveFacilityInfo), are both answered.
     */
    private fun reportingAnswers(site: ReportingSite): Map<String, List<Type>> {
        fun ref(location: Location?): List<Type>? = location?.let {
            listOf(Reference("Location/${it.idElement.idPart}").setDisplay(it.name))
        }
        return buildMap {
            ReportingSitePrefill.hiddenAnswers(context).forEach { (linkId, value) ->
                if (value.isNotBlank()) put(linkId, listOf(StringType(value)))
            }
            ref(site.county)?.let { value -> FormFields.ReportingSite.COUNTY_VARIANTS.forEach { put(it, value) } }
            ref(site.subCounty)?.let { value -> FormFields.ReportingSite.SUB_COUNTY_VARIANTS.forEach { put(it, value) } }
            ref(site.ward)?.let { value -> FormFields.ReportingSite.WARD_VARIANTS.forEach { put(it, value) } }
            ref(site.facility)?.let { value -> FormFields.ReportingSite.FACILITY_VARIANTS.forEach { put(it, value) } }
        }
    }

    /**
     * The reporting site worked out from the user's assigned area (profile), like the generator's
     * location chain: a facility-level user's own facility; otherwise the first facility (by
     * name) inside the user's ward / sub-county / county. Null for national users (no area):
     * they pick the facility.
     */
    suspend fun defaultSite(): ReportingSite? {
        val scope = userScope
        val facilityId = when (scope) {
            LocationLevel.FACILITY -> pref("facility")
            LocationLevel.WARD -> firstDescendant(pref("ward"), 1)
            LocationLevel.SUB_COUNTY -> firstDescendant(pref("subCounty"), 2)
            LocationLevel.COUNTY -> firstDescendant(pref("county"), 3)
            else -> null
        }?.takeIf { it.isNotBlank() } ?: return null
        val facility = runCatching { fhirEngine.get<Location>(facilityId) }.getOrNull() ?: return null
        return reportingSite(facility)
    }

    /** Walks [levels] down from [areaId], taking the first child by name each time. */
    private suspend fun firstDescendant(areaId: String, levels: Int): String? {
        var id = areaId.takeIf { it.isNotBlank() } ?: return null
        repeat(levels) {
            id = fhirEngine.search<Location> { filter(Location.PARTOF, { value = "Location/$id" }) }
                .map { it.resource }
                .filter { !it.name.isNullOrBlank() }
                .minByOrNull { it.name.lowercase(Locale.ROOT) }
                ?.idElement?.idPart ?: return null
        }
        return id
    }

    /** A facility with its ward, sub-county and county (walking Location.partOf). */
    suspend fun reportingSite(facility: Location): ReportingSite? {
        // Ancestors, nearest first, up to the top (partOf Location/0, or no parent on the device).
        val chain = ArrayList<Location>()
        var current: Location = facility
        val seen = hashSetOf(facility.idElement.idPart)
        while (chain.size < MAX_LOCATION_DEPTH) {
            val parentId = parentIdOf(current) ?: break
            if (parentId == "0" || !seen.add(parentId)) break
            current = runCatching { fhirEngine.get<Location>(parentId) }.getOrNull() ?: break
            chain += current
        }
        // A health facility is a lowest-level place (nothing inside it) under at least one area.
        if (chain.isEmpty() || hasChildren(facility)) return null
        val fromTop = chain.reversed() // county, sub-county, ward
        return ReportingSite(
            facility,
            ward = fromTop.getOrNull(2),
            subCounty = fromTop.getOrNull(1),
            county = fromTop.getOrNull(0),
        )
    }

    /** "Location/123", "123" or a full URL: the parent's id. */
    private fun parentIdOf(location: Location): String? =
        location.partOf?.reference?.trim()?.takeIf { it.isNotEmpty() }
            ?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }

    private suspend fun hasChildren(location: Location): Boolean =
        fhirEngine.search<Location> {
            filter(Location.PARTOF, { value = "Location/${location.idElement.idPart}" })
            count = 1
        }.isNotEmpty()

    /** Health facilities whose name contains [text], inside the user's assigned area. */
    suspend fun searchFacilities(text: String): List<ReportingSite> {
        if (text.trim().length < 3) return emptyList()
        val scope = userScope
        val assigned = when (scope) {
            LocationLevel.COUNTY -> pref("county")
            LocationLevel.SUB_COUNTY -> pref("subCounty")
            LocationLevel.WARD -> pref("ward")
            LocationLevel.FACILITY -> pref("facility")
            else -> ""
        }
        val named = fhirEngine.search<Location> {
            filter(Location.NAME, {
                value = text.trim()
                modifier = com.google.android.fhir.search.StringFilterModifier.CONTAINS
            })
            count = 200
        }.map { it.resource }
        val sites = named.mapNotNull { reportingSite(it) }
        Timber.tag(ImportLog.TAG).d(
            "Facility search \"%s\" (%s, area '%s'): %d places named, %d facilities",
            text, scope, assigned, named.size, sites.size,
        )
        return sites
            .filter { site ->
                if (assigned.isBlank()) return@filter true
                val area = when (scope) {
                    LocationLevel.COUNTY -> site.county
                    LocationLevel.SUB_COUNTY -> site.subCounty
                    LocationLevel.WARD -> site.ward
                    LocationLevel.FACILITY -> site.facility
                    else -> null
                }
                area == null || area.idElement.idPart == assigned || area.name.equals(assigned, ignoreCase = true)
            }
            .take(30)
    }

    // ---- Response

    /** The form response with the answered questions, in the form's order and nesting. */
    private fun buildResponse(answers: Map<String, List<Type>>): QuestionnaireResponse {
        fun build(items: List<QuestionnaireItemComponent>): List<QuestionnaireResponse.QuestionnaireResponseItemComponent> =
            items.mapNotNull { item ->
                val children = build(item.item)
                val own = answers[item.linkId].orEmpty()
                when {
                    item.type == QuestionnaireItemType.GROUP && children.isNotEmpty() ->
                        QuestionnaireResponse.QuestionnaireResponseItemComponent().setLinkId(item.linkId)
                            .setText(item.text).also { it.item = children.toMutableList() }
                    own.isNotEmpty() ->
                        QuestionnaireResponse.QuestionnaireResponseItemComponent().setLinkId(item.linkId)
                            .setText(item.text).also { responseItem ->
                                responseItem.answer = own.map { value ->
                                    QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().setValue(value)
                                }.toMutableList()
                                // Questions nested under a question go under its answer.
                                if (children.isNotEmpty()) responseItem.answer.first().item = children.toMutableList()
                            }
                    else -> null
                }
            }
        return QuestionnaireResponse().apply {
            questionnaire = this@BulkContactImport.questionnaire.url
            status = QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED
            authored = Date()
            item = build(this@BulkContactImport.questionnaire.item).toMutableList()
        }
    }

    private suspend fun missingRequired(response: QuestionnaireResponse): List<String> =
        runCatching {
            QuestionnaireResponseValidator.validateQuestionnaireResponse(questionnaire, response, context)
                .filterValues { results -> results.any { it is Invalid } }
                .keys.map { linkId -> questions.firstOrNull { it.linkId == linkId }?.text ?: linkId }
        }.getOrDefault(emptyList())

    // ---- Parsing helpers

    private fun parseDate(value: String): Date? = excelSerial(value) ?: parseWith(value, DATE_FORMATS)

    private fun parseDateTime(value: String): Date? =
        excelSerial(value) ?: parseWith(value, DATE_TIME_FORMATS) ?: parseWith(value, DATE_FORMATS)
            ?: runCatching { DateTimeType(value).value }.getOrNull()

    /** Excel stores dates as days since 30 Dec 1899 (fraction = time of day). */
    private fun excelSerial(value: String): Date? {
        val number = value.toBigDecimalOrNull() ?: return null
        if (number < BigDecimal(1) || number > BigDecimal(80000)) return null
        val days = number.toInt()
        val millisOfDay = ((number.toDouble() - days) * 86_400_000L).toLong()
        return Calendar.getInstance(TimeZone.getDefault()).apply {
            clear()
            set(1899, Calendar.DECEMBER, 30)
            add(Calendar.DAY_OF_MONTH, days)
            add(Calendar.MILLISECOND, millisOfDay.toInt())
        }.time
    }

    private fun parseWith(value: String, patterns: List<String>): Date? =
        patterns.firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }.parse(value)
            }.getOrNull()
        }

    private fun yesNo(value: String): Boolean? = when (value.trim().lowercase()) {
        "yes", "y", "true", "1" -> true
        "no", "n", "false", "0" -> false
        else -> null
    }

    private fun display(value: Type): String = when (value) {
        is Coding -> value.display ?: value.code.orEmpty()
        is Reference -> value.display ?: value.reference.orEmpty()
        else -> value.primitiveValue().orEmpty()
    }

    private fun digits(value: String?): String = value.orEmpty().filter(Char::isDigit).takeLast(9)

    /**
     * A Kenyan mobile number as the form wants it (07xxxxxxxx / 01xxxxxxxx): restores the 0
     * Excel drops (712345678) and turns +254 / 254 into 0. Two numbers in a cell: the first.
     * Null when it is not a mobile number.
     */
    private fun phone(value: String): String? {
        val first = value.split('/', ',', ';', '&').first()
        val digits = first.filter(Char::isDigit)
        val local = when {
            digits.length == 12 && digits.startsWith("254") -> "0" + digits.substring(3)
            digits.length == 9 && (digits.startsWith("7") || digits.startsWith("1")) -> "0$digits"
            digits.length == 10 && digits.startsWith("0") -> digits
            else -> null
        }
        return local
    }

    /** Letters and digits only, lower case: "Lang’ata " -> "langata". */
    private fun locationKey(name: String): String = name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    companion object {
        /** Day first (Kenya), then ISO. */
        private val DATE_FORMATS = listOf("yyyy-MM-dd", "dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy", "dd.MM.yyyy", "d MMM yyyy")
        private val DATE_TIME_FORMATS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm",
            "dd/MM/yyyy HH:mm:ss", "dd/MM/yyyy HH:mm", "d/M/yyyy H:mm",
        )

        /**
         * Clearer column names for questions whose text appears more than once in the form
         * ("County" is also asked for the reporting site).
         */
        private val PHONE_QUESTIONS = setOf(FormFields.Vhf.PHONE, FormFields.Vhf.NEXT_OF_KIN_PHONE)

        private const val MAX_LOCATION_DEPTH = 6

        /** Reporting Site questions (all levels) and its hidden user_* questions. */
        private val REPORTING_SITE_QUESTIONS: Set<String> =
            (FormFields.ReportingSite.COUNTY_VARIANTS + FormFields.ReportingSite.SUB_COUNTY_VARIANTS +
                FormFields.ReportingSite.WARD_VARIANTS + FormFields.ReportingSite.FACILITY_VARIANTS +
                FormFields.ReportingSite.FACILITY_TYPE_VARIANTS + listOf(
                    FormFields.ReportingSite.USER_ROLE, FormFields.ReportingSite.USER_COUNTY,
                    FormFields.ReportingSite.USER_SUB_COUNTY, FormFields.ReportingSite.USER_WARD,
                    FormFields.ReportingSite.USER_FACILITY,
                )).toSet()
        private const val COUNTY = "a4-county"
        private const val SUB_COUNTY = "a3-sub-county"
        private const val WARD = "a2-ward"
        private const val DOB_KNOWN = "442636360588"
        private const val COUNTRY = "vhf-country"
        private const val LISTING_DATE = "vhf-investigation-date"

        /** Contact Listing Form "Type of Contact": 1 fluids, 2 body, 3 linens, 4 household. */
        private val CONTACT_TYPE_CODES = mapOf(
            "1" to "body-fluids", "2" to "physical-contact", "3" to "linens", "4" to "household",
        )
        private val CONTACT_TYPE_WORDS = listOf(
            "fluid" to "body-fluids", "blood" to "body-fluids", "vomit" to "body-fluids",
            "physical" to "physical-contact", "touched the body" to "physical-contact",
            "linen" to "linens", "cloth" to "linens", "dish" to "linens",
            "household" to "household", "slept" to "household", "ate" to "household",
        )
        private const val AGE_YEARS = "492731638700"
        private const val RELATIONSHIP = "vhf-relationship"
        private const val RELATIONSHIP_OTHER = "vhf-relationship-other"
        private const val CONTACT_LOCATION = "vhf-contact-location"
        private const val EXPOSURE_LOCATION = "vhf-exposure-location"

        /** Column names of contact line lists (matched by their first words). */
        private val SPECIALS = linkedMapOf(
            "Full name" to Special.FULL_NAME,
            "Name" to Special.FULL_NAME,
            "Names" to Special.FULL_NAME,
            "Age" to Special.AGE_YEARS,
            "Relation to confirmed case" to Special.RELATIONSHIP,
            "Relation to case" to Special.RELATIONSHIP,
            "Relationship" to Special.RELATIONSHIP,
            "Flight seat" to Special.FLIGHT_SEAT,
            "Seat" to Special.FLIGHT_SEAT,
            "Comment" to Special.COMMENT,
            "Comments" to Special.COMMENT,
            "Type of contact" to Special.CONTACT_TYPE,
            // Line lists (e.g. the BVD contact list) code the Contact Listing Form's type of
            // contact (1-4) in an "Exposure Type" column.
            "Exposure type" to Special.CONTACT_TYPE,
            "Healthcare worker" to Special.HEALTH_WORKER,
            "Health care worker" to Special.HEALTH_WORKER,
            "HCW" to Special.HEALTH_WORKER,
        )

        /** Line-list column names for single questions (matched by their first words). */
        private val LINE_LIST_ALIASES = linkedMapOf(
            "Date of exposure" to "vhf-contact-date",
            "Date of last exposure" to "vhf-contact-date",
            "Mobile phone number" to FormFields.Vhf.PHONE,
            "Phone" to FormFields.Vhf.PHONE,
            "Telephone" to FormFields.Vhf.PHONE,
            "Sex" to "929966324957",
            "Gender" to "929966324957",
            "Address" to "vhf-village",
            "Residence" to "vhf-village",
            "Sub county" to SUB_COUNTY,
            "Subcounty" to SUB_COUNTY,
            "County" to COUNTY,
            "Ward" to "a2-ward",
            "Village" to "vhf-village",
            "ID number" to FormFields.Vhf.NATIONAL_ID,
            "National ID" to FormFields.Vhf.NATIONAL_ID,
            // Contact Listing Form and older case-form (CIF) sheets.
            "Case ID" to FormFields.Vhf.SOURCE_CASE_EPID,
            "Source case EPID" to FormFields.Vhf.SOURCE_CASE_EPID,
            "Case EPID" to FormFields.Vhf.SOURCE_CASE_EPID,
            "Contact link to case" to FormFields.Vhf.SOURCE_CASE_EPID,
            "Family name" to FormFields.Person.SURNAME,
            "Given names" to FormFields.Person.FIRST_NAME,
            "First name" to FormFields.Person.FIRST_NAME,
            "Date of last contact" to "vhf-contact-date",
            "Date of investigation" to LISTING_DATE,
            "Date of listing" to LISTING_DATE,
            "If yes what facility" to FormFields.Vhf.CONTACT_HCW_FACILITY,
            "Head of household" to FormFields.Vhf.HEAD_OF_HOUSEHOLD,
        )

        private val FAMILY_WORDS = listOf(
            "relative", "family", "spouse", "wife", "husband", "child", "son", "daughter",
            "mother", "father", "brother", "sister", "parent", "cousin", "uncle", "aunt",
        )
        private val HEALTH_WORKER_WORDS = listOf(
            "hcw", "health care worker", "healthcare worker", "nurse", "doctor", "clinician",
            "clinical officer", "lab", "laboratory", "sonographer", "radiographer",
            "patient attendant", "pharmacist", "student nurse", "port health",
        )

        private val ALIASES = mapOf(
            "County of residence" to "a4-county",
            "Sub County of residence" to "a3-sub-county",
            "Ward of residence" to "a2-ward",
        )

        fun normalize(text: String): String =
            text.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()
    }
}
