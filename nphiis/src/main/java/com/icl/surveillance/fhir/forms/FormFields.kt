package com.icl.surveillance.fhir.forms

/**
 * Single source of truth for the identifiers used when a questionnaire is submitted, extracted
 * into FHIR resources and read back by the case lists.
 *
 * - [CaseTypes] / [CaseSlugs]: the module a record belongs to.
 * - [FhirSystems]: identifier, tag and coding systems written on extracted resources.
 * - [FormFields]: questionnaire linkIds (also the Observation.code of each extracted answer).
 */

/** Module names, as stored in the `currentCase` preference and in Encounter.reasonCode. */
object CaseTypes {
    const val MEASLES = "Measles Case Information"
    const val AFP = "AFP Case Information"
    const val VL = "VL Case Information"
    const val VHF = "VHF Case Information"
    const val MOH_505 = "MOH 505 Reporting Form"
    const val RUMOR = "Social Listening and Rumor Tracking Tool"
    const val MPOX_INFORMATION = "Mpox Information"
    const val MPOX_REGISTER = "Mpox Register"
    const val MPOX_TALLY_SHEET = "Mpox - Tally Sheet"
    const val MPOX_SUPERVISOR_CHECKLIST = "Mpox - Supervisor Checklist"
    const val RCCE_COMMUNITY = "RCCE - Community Questionnaire"
    const val RCCE_COUNTY = "RCCE - County/Subcounty Interface"

    /** Modules whose submissions create a case (Patient + Encounter) and appear in case lists. */
    val CASE_LISTED = listOf(
        MEASLES, AFP, VL, VHF, MOH_505, MPOX_INFORMATION, RCCE_COMMUNITY, RCCE_COUNTY,
    )
}

/**
 * Slug of a module name, e.g. "Social Listening and Rumor Tracking Tool" ->
 * "social-listening-and-rumor-tracking-tool". Used as the Patient case-identifier system.
 */
fun String.toCaseSlug(): String =
    trim()
        .lowercase()
        .replace(NON_SLUG_CHARS, "")
        .replace(WHITESPACE, "-")
        .replace(REPEATED_DASHES, "-")

private val NON_SLUG_CHARS = "[^a-z0-9\\s-]".toRegex()
private val WHITESPACE = "\\s+".toRegex()
private val REPEATED_DASHES = "-+".toRegex()

/** Slugs of [CaseTypes] (`CaseTypes.X.toCaseSlug()`), usable as `when` branch constants. */
object CaseSlugs {
    const val MEASLES = "measles-case-information"
    const val AFP = "afp-case-information"
    const val VL = "vl-case-information"
    const val VHF = "vhf-case-information"
    const val MOH_505 = "moh-505-reporting-form"
    const val RUMOR = "social-listening-and-rumor-tracking-tool"
    const val MPOX_INFORMATION = "mpox-information"
    const val MPOX_REGISTER = "mpox-register"
    const val MPOX_TALLY_SHEET = "mpox-tally-sheet"
    const val MPOX_SUPERVISOR_CHECKLIST = "mpox-supervisor-checklist"
    const val RCCE_COMMUNITY = "rcce-community-questionnaire"
    const val RCCE_COUNTY = "rcce-countysubcounty-interface"

    /** List-screen key covering both RCCE forms. */
    const val RCCE = "rcce"
    val RCCE_FORMS = listOf(RCCE_COMMUNITY, RCCE_COUNTY)
}

/** Systems and codes written on the resources extracted from a submission. */
object FhirSystems {
    /** Identifier holding the creation timestamp ("yyyy-MM-dd HH:mm:ss") on Patient/Encounter. */
    const val SYSTEM_CREATION = "system-creation"
    const val SYSTEM_CREATION_CODE = "system_creation"
    const val SYSTEM_CREATION_DISPLAY = "System Creation"

    /** Second reason code on every case Encounter. */
    const val CASE_INFORMATION = "case-information"

    /** Coding system of MeasureReport populations built from answers. */
    const val QUESTIONNAIRE_ANSWERS = "questionnaire-answers"

    /** Observation code / display of the generated EPID number. */
    const val EPID = "EPID"
    const val EPID_DISPLAY = "EPID No"

    private const val STRUCTURE_DEFINITION_BASE = "http://example.org/fhir/StructureDefinition/"
    const val MANAGING_LOCATION_SUFFIX = "-managingLocation"

    /** Tag system / extension URL linking a resource to its facility, e.g. "patient". */
    fun managingLocation(resource: String) =
        "$STRUCTURE_DEFINITION_BASE$resource$MANAGING_LOCATION_SUFFIX"

    const val SUPERVISOR_CHECKLIST = "supervisor_checklist"
    const val GEO_LOCATION = "geo-location"
    const val GEO_LOCATION_DETAILS = "geo-location-details"
}

/** Questionnaire linkIds, grouped by form section. */
object FormFields {

    /** Main date of a record: onset (case forms), week ending (MOH 505), report date (Mpox). */
    const val EVENT_DATE = "728034137219"

    /**
     * Reporting-site questions. Each exists once per user role, with a role suffix, because the
     * form shows a different group per role (see [variants]).
     */
    object ReportingSite {
        const val GROUP = "151479012557"
        const val COUNTY = "294367770999"
        const val SUB_COUNTY = "819946803642"
        const val WARD = "819943434"
        const val FACILITY = "819946803677"
        const val FACILITY_TYPE = "438862163919"

        const val USER_ROLE = "user_role"
        const val USER_COUNTY = "user_county"
        const val USER_SUB_COUNTY = "user_sub_county"
        const val USER_WARD = "user_ward"
        const val USER_FACILITY = "user_facility"

        const val SUFFIX_SUB_COUNTY = "_sub_county"
        const val SUFFIX_COUNTY = "_county"
        const val SUFFIX_NATIONAL = "_national"

        /** The facility-level question plus its sub-county, county and national variants. */
        fun variants(base: String) = listOf(
            base, base + SUFFIX_SUB_COUNTY, base + SUFFIX_COUNTY, base + SUFFIX_NATIONAL,
        )

        val COUNTY_VARIANTS = variants(COUNTY)
        val SUB_COUNTY_VARIANTS = variants(SUB_COUNTY)
        val WARD_VARIANTS = variants(WARD)
        val FACILITY_VARIANTS = variants(FACILITY)
        val FACILITY_TYPE_VARIANTS = variants(FACILITY_TYPE)
    }

    /** Patient residence questions of the case forms. */
    object Residence {
        const val COUNTY = "a4-county"
        const val SUB_COUNTY = "a3-sub-county"
        const val WARD = "a2-ward"
    }

    /** Patient details shared by Measles, AFP and the Mpox register. */
    object Person {
        const val FIRST_NAME = "873240407472"
        const val MIDDLE_NAME = "246751846436"
        const val SURNAME = "486402457213"
        const val DATE_OF_BIRTH = "257830485990"
        const val SEX = "929966324957"
        const val PHONE = "754217593839"
    }

    object Measles {
        const val EPID_NUMBER = "992818778559"
        const val CASE_OR_LINE_LIST = "865158268604"
        const val PARENT_NAME = "parent"
        const val PARENT_RESIDENCE = "242811643559"
        const val PARENT_NEIGHBOURHOOD = "946232932304"
        const val PARENT_STREET = "424111786438"
        const val PARENT_TOWN = "110761799063"
        const val PARENT_SUB_COUNTY = "885995384353"
        const val PARENT_COUNTY = "301322368614"
        const val MR_VACCINE_LAST_30_DAYS = "308128177300"

        /** Parent address lines, in the order they are written. */
        val PARENT_ADDRESS_LINES = listOf(
            PARENT_RESIDENCE, PARENT_NEIGHBOURHOOD, PARENT_STREET,
            PARENT_TOWN, PARENT_SUB_COUNTY, PARENT_COUNTY,
        )

        const val OTHER_SPECIMEN_COLLECTED = "258912872921"
        const val OTHER_SPECIMEN_TYPE = "340507649387"
        const val OTHER_SPECIMEN_DATE = "699353598445"

        /** Specimen type, "was it collected?" question and collection-date question. */
        val SPECIMENS = listOf(
            Specimen("Blood", "918495737998", "8962468583341"),
            Specimen("Urine", "433195098993", "915783129731"),
            Specimen("Respiratory Sample", "270749570400", "183705125522"),
        )

        data class Specimen(val type: String, val collectedLinkId: String, val dateLinkId: String)
    }

    object MeaslesLab {
        const val IGM_RESULT = "measles-igm"
        const val FINAL_CLASSIFICATION = "final-classification"
    }

    object Afp {
        const val GUARDIAN_NAME = "856448027666"
        const val GUARDIAN_PHONE = "576318206363"
        const val STOOL_SPECIMEN_DATE = "737703942433"
    }

    object Vl {
        const val FIRST_NAME = "817903655885"
        const val MIDDLE_NAME = "164840483828"
        const val SURNAME = "606848143908"
        const val SEX = "543806612685"
        const val PHONE = "760016167907"
        const val CONTACT_NAME = "657999955440"
        const val CONTACT_PHONE = "354738003178"
    }

    object VlLab {
        const val RAPID_TEST_RESULT = "286501145394"
        const val DAT_RESULT = "839711142610"
        const val ASPIRATE_RESULT = "108406555539"
        const val MICROSCOPY_RESULT = "320819009291"
        const val FINAL_DIAGNOSIS = "655245793432"
        const val FINAL_DIAGNOSIS_OTHER = "843481153132"

        val TEST_RESULTS = listOf(RAPID_TEST_RESULT, DAT_RESULT, ASPIRATE_RESULT, MICROSCOPY_RESULT)
    }

    /** Viral Hemorrhagic Fever case investigation (vhf-case.json). Name, sex, date of birth,
     *  residence and onset reuse [Person], [Residence] and [EVENT_DATE]. */
    object Vhf {
        /** Laboratory results follow-up form (title = its Encounter.reasonCode). */
        const val LAB_FORM = "vhf-lab-results.json"
        const val LAB_TITLE = "VHF Laboratory Results"

        /** The VHF case investigation form; contacts are registered with the same form. */
        const val CASE_FORM = "vhf-case.json"

        // ---- Contact follow-up. A contact is a VHF record whose "Type of case" is Contact; it is
        // followed for 21 days from the date of contact (day 0), per the CIF / data dictionary.
        const val CASE_TYPE_CONTACT = "Contact"
        const val FOLLOW_UP_WINDOW_DAYS = 21

        /** Daily follow-up (dictionary contact_follow_up, repeatable: one record per day 0-21). */
        const val CONTACT_FOLLOW_UP_FORM = "vhf-contact-follow-up.json"
        const val CONTACT_FOLLOW_UP_TITLE = "VHF Contact Follow Up"
        const val FOLLOW_UP_DAY = "vhf-follow-up-day"
        const val FOLLOW_UP_DATE = "vhf-follow-up-date"
        const val FOLLOW_UP_SYMPTOMATIC = "vhf-follow-up-symptomatic"

        /** Contact monitoring status and sign-off (dictionary contact_monitoring + official). */
        const val CONTACT_MONITORING_FORM = "vhf-contact-monitoring.json"
        const val CONTACT_MONITORING_TITLE = "VHF Contact Monitoring"
        const val MONITORING_STATUS = "vhf-monitoring-status"
        const val MISSED_FOLLOW_UP_DAYS = "vhf-missed-follow-up-days"
        const val STATUS_UNDER_FOLLOW_UP = "Under follow up"
        const val STATUS_COMPLETED = "Completed follow up (released)"
        const val STATUS_COMPLETED_CODE = "completed"
        const val STATUS_BECAME_CASE = "Became a suspected case"

        /** Derived on the case summary (not a question): the record has daily follow ups. */
        const val HAS_FOLLOW_UPS = "vhf-derived-has-follow-ups"
        const val STATUS_BECAME_CASE_CODE = "became-case"
        const val FINAL_OUTCOME_DATE = "vhf-final-outcome-date"
        const val CASE_TYPE_SUSPECTED_CODE = "suspected"

        /** Case-form fields used to register a contact from its source case. */
        const val EXPOSURE_TYPE = "vhf-exposure-type"
        const val EXPOSURE_HUMAN_CONTACT = "human"
        const val CONTACT_DATE = "vhf-contact-date"
        const val DISEASE_OTHER = "vhf-disease-other"

        const val DISEASE = "vhf-disease"
        const val CASE_TYPE = "vhf-case-type"
        /** Identification number (any type); its type is ID_TYPE (18+) or ID_TYPE_MINOR. */
        const val NATIONAL_ID = "vhf-national-id"
        const val ID_TYPE = "vhf-id-type"
        const val ID_TYPE_MINOR = "vhf-id-type-minor"
        const val ID_TYPE_NATIONAL_ID_CODE = "national-id"

        /** A contact's source case, by EPID number (see VhfSourceCases). */
        const val SOURCE_CASE_EPID = "vhf-source-case-epid"

        /** Assigned at the laboratory; asked on the lab results form. */
        const val SPECIMEN_ID = "vhf-specimen-id"
        const val PHONE = "vhf-phone"
        const val VILLAGE = "vhf-village"
        const val NEXT_OF_KIN = "vhf-next-of-kin"
        const val NEXT_OF_KIN_PHONE = "vhf-next-of-kin-phone"
        const val OUTCOME = "vhf-outcome"
        const val CASE_STATUS = "vhf-case-status"
        const val SAMPLES_COLLECTED = "vhf-samples-collected"
        const val PRELIMINARY_RESULT = "vhf-preliminary-result"
        const val FINAL_RESULT = "vhf-final-result"

        /** Label shown for the derived classification on the lab tab. */
        const val FINAL_CLASSIFICATION_LABEL = "Final Classification"

        /**
         * Final classification is not captured on the lab form; like Measles it is derived from
         * the laboratory's final result, using the same status labels as the other modules.
         */
        fun finalClassification(finalResult: String?): String =
            when (finalResult?.trim()?.lowercase()) {
                "positive" -> "Confirmed by lab"
                "negative" -> "Discarded"
                else -> "Pending Results"
            }
    }

    object MpoxRegister {
        const val VACCINATION_CENTER = "vaccination_center"
        const val COUNTRY_OF_ORIGIN = "country_of_origin"
    }

    object MpoxTallySheet {
        const val TEAM_NUMBER = "team_no"
        const val SUPERVISOR_NAME = "supervisor_name"
        const val CAMPAIGN_DAY = "campaign_day"
    }

    object Rcce {
        const val OCCUPATION = "occupation"
        const val OCCUPATION_OTHER = "occupation_other"
        const val AGE = "age"
        const val VILLAGE = "village"
    }

    object Rumor {
        const val VILLAGE = "871818396498"
        const val REPORTING_CADRE = "683805917262"
        const val REPORTING_CADRE_OTHER = "223529605110"
        const val AGENCY = "683805917111"
        const val AGENCY_OTHER = "22311605110"

        /** Answers shown on (and searched in) the rumor list. */
        val LIST_FIELDS = listOf(VILLAGE, REPORTING_CADRE, REPORTING_CADRE_OTHER, AGENCY, AGENCY_OTHER) +
                ReportingSite.COUNTY_VARIANTS + ReportingSite.SUB_COUNTY_VARIANTS
    }

    object Geo {
        const val LATITUDE = "latitude"
        const val LONGITUDE = "longitude"
    }
}
