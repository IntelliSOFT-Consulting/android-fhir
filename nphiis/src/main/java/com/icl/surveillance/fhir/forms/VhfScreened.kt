package com.icl.surveillance.fhir.forms

import android.content.Context
import ca.uhn.fhir.context.FhirContext
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.get
import com.google.android.fhir.search.search
import com.icl.surveillance.ui.patients.CaseIdentifierIndex
import java.util.Date
import java.util.UUID
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.DateTimeType
import org.hl7.fhir.r4.model.DateType
import org.hl7.fhir.r4.model.DecimalType
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.StringType
import org.hl7.fhir.r4.model.Type
import timber.log.Timber

/**
 * VHF screened people: a Patient carrying a [FormFields.Vhf.SCREENED_SYSTEM] identifier plus its
 * screening QuestionnaireResponse (answers keyed by the VHF case form's linkIds; no Encounter).
 *
 * A case investigation started from a screened person updates that same Patient and response
 * (see AddClientViewModel.persistPatientData): the screened identifier becomes
 * [FormFields.Vhf.SCREENING_NUMBER_SYSTEM], so the person leaves the screened list and appears
 * in the VHF case list.
 */
object VhfScreened {

    private const val TAG = "VHF_SCREENED"

    /** Patient id of the screened person whose case investigation is being filled in. */
    const val PREF_SOURCE = "vhfScreenedSource"

    /** Sections of the case form that screening captures, shown on the screened detail screen. */
    val SCREENING_SECTIONS = linkedMapOf(
        "vhf-demographics" to "Demographics",
        "vhf-travel" to "Travel History",
        "vhf-clinical" to "Clinical Information",
    )

    data class Row(
        val patientId: String,
        val name: String,
        val phone: String,
        val idNumber: String,
        val screenedOn: Date?,
    )

    data class Field(val label: String, val value: String)
    data class Section(val title: String, val fields: List<Field>)

    data class Details(
        val row: Row,
        val sections: List<Section>,
        /** Starting answers for the case investigation form (see FormPrefill). */
        val prefill: Map<String, String>,
    )

    fun isScreened(patient: Patient): Boolean =
        patient.active && patient.identifier.any { it.system in FormFields.Vhf.SCREENED_SYSTEMS }

    /** Screened people still waiting for a case investigation, newest first. */
    suspend fun list(engine: FhirEngine): List<Row> =
        CaseIdentifierIndex.patientsWith(engine, FormFields.Vhf.SCREENED_SYSTEMS).keys
            .mapNotNull { id -> runCatching { engine.get<Patient>(id) }.getOrNull() }
            .filter { isScreened(it) } // investigated people no longer carry the identifier
            .filterNot { VhfTraining.isTraining(it) }
            .map { it.toRow() }
            .sortedByDescending { it.screenedOn?.time ?: 0L }

    suspend fun count(engine: FhirEngine): Int = list(engine).size

    /** The screening response: the person's response that is not part of a case Encounter. */
    suspend fun screeningResponse(engine: FhirEngine, patientId: String): QuestionnaireResponse? =
        engine.search<QuestionnaireResponse> {
            filter(QuestionnaireResponse.SUBJECT, { value = "Patient/$patientId" })
        }.map { it.resource }
            .filter { !it.hasEncounter() }
            .maxByOrNull { it.authored?.time ?: 0L }

    suspend fun details(context: Context, engine: FhirEngine, patientId: String): Details? {
        val patient = runCatching { engine.get<Patient>(patientId) }.getOrNull() ?: return null
        val response = screeningResponse(engine, patientId)
        val labels = formLabels(context)

        val sections = SCREENING_SECTIONS.mapNotNull { (groupId, title) ->
            val group = response?.item?.let { findItem(it, groupId) } ?: return@mapNotNull null
            val fields = leaves(group.item)
                .filter { it.hasAnswer() }
                .map { item ->
                    Field(
                        label = labels[item.linkId] ?: item.text ?: item.linkId,
                        value = item.answer.mapNotNull { display(it.value) }.joinToString(", "),
                    )
                }
                .filter { it.value.isNotBlank() }
            Section(title, fields).takeIf { fields.isNotEmpty() }
        }

        val fromResponse: Map<String, String> = response?.let { r ->
            leaves(r.item)
                .filter { it.hasAnswer() }
                .associate { item ->
                    item.linkId to FormPrefill.multi(item.answer.mapNotNull { prefillValue(it.value) })
                }
                .filterValues { it.isNotBlank() }
        }.orEmpty()

        return Details(patient.toRow(), sections, patientPrefill(patient) + fromResponse)
    }

    /**
     * Saves a screened person (dummy data and imports): the Patient is built from the screening
     * answers and marked with the screened identifier; the response is saved without an
     * Encounter. Returns the Patient id.
     */
    suspend fun createScreened(
        engine: FhirEngine,
        response: QuestionnaireResponse,
        extraTags: List<Coding> = emptyList(),
    ): String {
        val answers = leaves(response.item)
            .filter { it.hasAnswer() }
            .associate { it.linkId to it.answer.mapNotNull { a -> display(a.value) }.joinToString(", ") }
        fun answer(linkId: String) = answers[linkId]?.takeIf { it.isNotBlank() }

        val patient = Patient().apply {
            id = UUID.randomUUID().toString()
            active = true
        }
        PatientMapper.applyName(
            patient,
            first = answer(FormFields.Person.FIRST_NAME),
            middle = null,
            surname = answer(FormFields.Person.SURNAME),
            order = PatientMapper.NameOrder.SURNAME_AS_FAMILY,
        )
        PatientMapper.applySex(patient, answer(FormFields.Person.SEX))
        PatientMapper.applyBirthDate(patient, answer(FormFields.Person.DATE_OF_BIRTH))
        answer(FormFields.Vhf.PHONE)?.let { patient.addTelecom(PatientMapper.mobile(it)) }
        answer(FormFields.Vhf.NATIONAL_ID)?.let { idNumber ->
            patient.addIdentifier(Identifier().setSystem(FormFields.Vhf.ID_TYPE_NATIONAL_ID_CODE).setValue(idNumber))
        }
        PatientMapper.applyContact(
            patient,
            fullName = answer(FormFields.Vhf.NEXT_OF_KIN),
            phone = answer(FormFields.Vhf.NEXT_OF_KIN_PHONE),
        )
        PatientMapper.applyResidence(
            patient,
            county = answer(FormFields.Residence.COUNTY).orEmpty(),
            subCounty = answer(FormFields.Residence.SUB_COUNTY).orEmpty(),
        )
        val screeningNumber = "SCR-" + patient.id.take(8).uppercase()
        patient.addIdentifier(Identifier().setSystem(FormFields.Vhf.SCREENED_SYSTEM).setValue(screeningNumber))
        patient.addIdentifier(
            Identifier().setSystem(FhirSystems.SYSTEM_CREATION)
                .setValue(com.icl.surveillance.utils.FormatterClass().formatDateTime(Date()))
        )
        if (extraTags.isNotEmpty()) patient.meta.tag = patient.meta.tag + extraTags.map { it.copy() }

        response.id = UUID.randomUUID().toString()
        response.subject = Reference("Patient/${patient.id}")
        response.encounter = null
        if (!response.hasAuthored()) response.authored = Date()
        response.status = QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED
        if (extraTags.isNotEmpty()) response.meta.tag = response.meta.tag + extraTags.map { it.copy() }

        engine.create(patient, response)
        CaseIdentifierIndex.put(patient)
        Timber.tag(TAG).d("screened %s (%s) created", patient.id, screeningNumber)
        return patient.id
    }

    // ---------------------------------------------------------------------------------------

    private fun Patient.toRow() = Row(
        patientId = logicalId,
        name = nameFirstRep.nameAsSingleString?.trim().orEmpty().ifBlank { "Unnamed" },
        phone = telecom.firstOrNull { it.hasValue() }?.value.orEmpty(),
        idNumber = identifier.firstOrNull { it.system == FormFields.Vhf.ID_TYPE_NATIONAL_ID_CODE }?.value
            ?: identifier.firstOrNull { it.system in FormFields.Vhf.SCREENED_SYSTEMS }?.value
            ?: "",
        screenedOn = meta.lastUpdated
            ?: identifier.firstOrNull { it.system == FhirSystems.SYSTEM_CREATION }?.value
                ?.let { runCatching { DateTimeType(it.replace(' ', 'T')).value }.getOrNull() },
    )

    /** Patient details as form answers, used when the screening response lacks them. */
    private fun patientPrefill(patient: Patient): Map<String, String> = buildMap {
        patient.nameFirstRep.family?.takeIf { it.isNotBlank() }?.let { put(FormFields.Person.SURNAME, it) }
        patient.nameFirstRep.givenAsSingleString?.takeIf { it.isNotBlank() }
            ?.let { put(FormFields.Person.FIRST_NAME, it) }
        patient.gender?.toCode()?.let { put(FormFields.Person.SEX, it) }
        patient.birthDate?.let {
            put(FormFields.Person.DATE_OF_BIRTH, DateType(it).valueAsString)
            put(DOB_KNOWN, "known")
        }
        patient.telecom.firstOrNull { it.hasValue() }?.value?.let { put(FormFields.Vhf.PHONE, it) }
        patient.identifier.firstOrNull { it.system == FormFields.Vhf.ID_TYPE_NATIONAL_ID_CODE }?.value
            ?.let {
                put(FormFields.Vhf.NATIONAL_ID, it)
                put(FormFields.Vhf.ID_TYPE, "National ID")
            }
    }

    private const val DOB_KNOWN = "442636360588"

    /** Answer as shown to a person. */
    private fun display(value: Type?): String? = when (value) {
        is Coding -> value.display ?: value.code
        is Reference -> value.display ?: value.reference
        is DateType -> value.valueAsString
        is DateTimeType -> value.valueAsString?.replace('T', ' ')?.take(16)
        is StringType -> value.value
        is IntegerType -> value.value?.toString()
        is DecimalType -> value.value?.toPlainString()
        is BooleanType -> if (value.booleanValue()) "Yes" else "No"
        else -> value?.primitiveValue()
    }?.takeIf { it.isNotBlank() }

    /** Answer in the form FormPrefill expects (codes for choices, Location/<id>|name). */
    private fun prefillValue(value: Type?): String? = when (value) {
        is Coding -> value.code ?: value.display
        is Reference -> value.reference?.let { ref ->
            FormPrefill.locationReference(ref.substringAfter("Location/"), value.display)
                .takeIf { ref.startsWith("Location/") } ?: ref
        }
        is DateType -> value.valueAsString
        is DateTimeType -> value.valueAsString
        else -> display(value)
    }

    private fun org.hl7.fhir.r4.model.Enumerations.AdministrativeGender.toCode() = when (this) {
        org.hl7.fhir.r4.model.Enumerations.AdministrativeGender.MALE -> "male"
        org.hl7.fhir.r4.model.Enumerations.AdministrativeGender.FEMALE -> "female"
        else -> null
    }

    private fun findItem(
        items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>,
        linkId: String,
    ): QuestionnaireResponse.QuestionnaireResponseItemComponent? =
        items.firstNotNullOfOrNull { item ->
            if (item.linkId == linkId) item else findItem(item.item, linkId)
        }

    /** Answered questions at any depth (group items are walked, not returned). */
    private fun leaves(
        items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>,
    ): List<QuestionnaireResponse.QuestionnaireResponseItemComponent> =
        items.flatMap { item ->
            val nested = item.item + item.answer.flatMap { it.item }
            (if (item.hasAnswer()) listOf(item) else emptyList()) + leaves(nested)
        }

    @Volatile
    private var labelCache: Map<String, String>? = null

    /** linkId -> question text of the VHF case form. */
    private fun formLabels(context: Context): Map<String, String> = labelCache ?: runCatching {
        val json = context.assets.open(FormFields.Vhf.CASE_FORM).bufferedReader().use { it.readText() }
        val questionnaire = FhirContext.forR4Cached().newJsonParser()
            .parseResource(Questionnaire::class.java, json)
        fun walk(items: List<Questionnaire.QuestionnaireItemComponent>): List<Pair<String, String>> =
            items.flatMap { item -> listOf(item.linkId to (item.text ?: item.linkId)) + walk(item.item) }
        walk(questionnaire.item).toMap()
    }.onFailure { Timber.tag(TAG).w(it, "Could not read VHF form labels") }
        .getOrDefault(emptyMap())
        .also { labelCache = it }
}
