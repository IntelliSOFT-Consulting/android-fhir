package com.icl.surveillance.utils

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import com.icl.surveillance.BuildConfig
import com.icl.surveillance.R

/**
 * Endpoints and secrets are not kept here: they come from local.properties via BuildConfig
 * (see nphiis/build.gradle.kts and local.properties.example).
 */
object Constants {
    const val UPLOAD_BUNDLE_SIZE = 500

    const val BASE_URL = BuildConfig.BASE_URL
    const val BASE_AUTH_URL = BuildConfig.BASE_AUTH_URL
    const val ALERTS_BASE_URL = BuildConfig.ALERTS_BASE_URL

    // ---- Client lookup before a new case form (ClientLookupActivity). Two sources, picked on screen.

    /**
     * IPRS, via SHA Health ID: GET [IPRS_LOOKUP_URL]?id_number=<number>&type=citizen.
     * Reply: { "data": { "first_name", "last_name", "other_names", "sex": "M", "date_of_birth", ... } }.
     * Basic auth with [IPRS_LOOKUP_USERNAME] / [IPRS_LOOKUP_PASSWORD]; the app's own login token
     * is not sent to this host.
     */
    const val IPRS_LOOKUP_BASE_URL = BuildConfig.IPRS_BASE_URL
    const val IPRS_LOOKUP_URL = "${IPRS_LOOKUP_BASE_URL}v1/lookup/persons"
    const val IPRS_LOOKUP_ID_PARAM = "id_number"
    const val IPRS_LOOKUP_TYPE_PARAM = "type"

    /** SHA Health ID Basic auth (local.properties: nphiis.iprs.username / nphiis.iprs.password). */
    const val IPRS_LOOKUP_USERNAME = BuildConfig.IPRS_USERNAME
    /**
     * Ready-made Basic auth value for testing. When not blank it is sent as-is instead of building
     * one from [IPRS_LOOKUP_USERNAME] / [IPRS_LOOKUP_PASSWORD]; clear it to use those again.
     */
    const val IPRS_LOOKUP_BASIC_AUTH = BuildConfig.IPRS_BASIC_AUTH

    const val IPRS_LOOKUP_PASSWORD = BuildConfig.IPRS_PASSWORD

    /** Case-form ID type code -> IPRS `type`. IPRS holds national IDs only. */
    val IPRS_LOOKUP_ID_TYPES = mapOf("national-id" to "citizen")

    /**
     * DHA middleware (UAT). The Client Registry is called with a token from [DHA_TOKEN_URL]:
     * POST form client_id + client_secret -> { "access_token": ..., "expires_in": ... }.
     */
    const val DHA_BASE_URL = BuildConfig.DHA_BASE_URL
    const val DHA_TOKEN_URL = "${DHA_BASE_URL}v1/tenants/token"

    /** DHA client credentials (local.properties: nphiis.dha.clientId / nphiis.dha.clientSecret). */
    const val DHA_CLIENT_ID = BuildConfig.DHA_CLIENT_ID
    const val DHA_CLIENT_SECRET = BuildConfig.DHA_CLIENT_SECRET

    /**
     * Client Registry (CR) patient search on the DHA middleware:
     * GET [CLIENT_LOOKUP_URL]?identification_number=<number>&identification_type=<type>
     * with "Authorization: Bearer <DHA token>". Reply: CrPatient (the person, with their
     * "dependants" — used for the minor search by the parent's / guardian's ID).
     */
    const val CLIENT_LOOKUP_BASE_URL = BuildConfig.CR_BASE_URL
    const val CLIENT_LOOKUP_URL = "${CLIENT_LOOKUP_BASE_URL}v1/patients"
    const val CLIENT_LOOKUP_ID_PARAM = "identification_number"
    const val CLIENT_LOOKUP_TYPE_PARAM = "identification_type"

    /**
     * true: answer CR lookups from MockClientRegistry test data (offline, no DHA calls).
     * false: real Client Registry on the DHA middleware.
     */
    const val CLIENT_LOOKUP_USE_MOCK = false

    /**
     * App ID type code -> the CR's identification_type value (exact spelling from the CR API:
     * "National ID", "Alien ID", "Refugee ID", "Mandate Number", "Birth Certificate",
     * "Birth Notification", "Temporary ID", "ClientRegistry ID"). The order is the order shown.
     * Codes that also exist on the case form (national-id, birth-certificate) fill its
     * "Type of identification"; for the others only the number is filled in.
     */
    val CLIENT_LOOKUP_ID_TYPES = linkedMapOf(
        "national-id" to "National ID",
        "alien-id" to "Alien ID",
        "refugee-id" to "Refugee ID",
        "birth-certificate" to "Birth Certificate",
        "birth-notification" to "Birth Notification",
        "temporary-id" to "Temporary ID",
        "mandate-number" to "Mandate Number",
        "client-registry-id" to "ClientRegistry ID",
    )

    /** Child documents: not offered for an adult or a parent / guardian search. */
    val CLIENT_LOOKUP_CHILD_ID_TYPES = setOf("birth-certificate", "birth-notification")


    //MOH 505
    const val COUNTY = "a4-county"
    const val SUB_COUNTY = "a3-sub-county"
    const val WARD = "819943434"
    const val HEALTH_FACILITY = "819946803677"
    const val FACILITY_TYPE = "438862163919"
    const val WEEK_ENDING_DATE = "728034137219"
    val FACILITY_DETAILS =
        listOf(COUNTY, SUB_COUNTY, WARD, HEALTH_FACILITY, FACILITY_TYPE, WEEK_ENDING_DATE)

    const val AEFI = "aefi-summary"
    const val BACTERIAL_MENINGITIS = "bacterial-meningitis-summary"
    const val ACUTE_JAUNDICE = "acute-jaundice-summary"
    const val NEONATAL_DEATHS = "neonatal-deaths-summary"
    const val ACUTE_MALNUTRITION = "acute-malnutrition-summary"
    const val CHIKUNGUNYA = "chikungunya-summary"
    const val COVID_19 = "covid--19-summary"
    const val SARI_CLUSTER = "sari-cluster-ge3-cases-summary"
    const val DENGUE = "dengue-summary"
    const val MEASLES = "measles-summary"
    const val RIFT_VALLEY_FEVER = "rift-valley-fever-summary"
    const val TYPHOID = "typhoid-summary"
    const val ANTHRAX = "anthrax-summary"
    const val GUINEA_WORM = "guinea-worm-disease-summary"
    const val VHF = "vhf-summary"
    const val ZIKA = "zika-virus-summary"
    const val SUSPECTED_MALARIA = "suspected-malaria-summary"
    const val YELLOW_FEVER = "yellow-fever-summary"
    const val SUSPECTED_MDR_XDR_TB = "suspected-mdr-xdr-tb-summary"
    const val OTHERS = "others-specify-summary"

    val ALL = listOf(
        AEFI,
        BACTERIAL_MENINGITIS,
        ACUTE_JAUNDICE,
        NEONATAL_DEATHS,
        ACUTE_MALNUTRITION,
        CHIKUNGUNYA,
        COVID_19,
        SARI_CLUSTER,
        DENGUE,
        MEASLES,
        RIFT_VALLEY_FEVER,
        TYPHOID,
        ANTHRAX,
        GUINEA_WORM,
        VHF,
        ZIKA,
        SUSPECTED_MALARIA,
        YELLOW_FEVER,
        SUSPECTED_MDR_XDR_TB,
        OTHERS
    )

    val ALL_LINK_IDS = FACILITY_DETAILS + ALL
    val DEFAULT_EXCLUDES = listOf(
        "user_role", "user_facility", "user_ward", "user_sub_county", "user_county"
    )

    val ADMINISTRATOR_EXCLUDES = listOf(
        "294367770999_national",
        "819946803642_national",
        "819943434_national",
        "819946803677_national",
        "438862163919_national"
    )
    val COUNTY_DISEASE_SURVEILLANCE_OFFICER_EXCLUDES = listOf(
        "294367770999_county",
        "819946803642_county",
        "819943434_county",
        "819946803677_county",
        "438862163919_county"
    )
    val SUBCOUNTY_DISEASE_SURVEILLANCE_OFFICER_EXCLUDES = listOf(
        "294367770999_sub_county",
        "819946803642_sub_county",
        "819943434_sub_county",
        "819946803677_sub_county",
        "438862163919_sub_county"
    )

    val VACCINATOR_EXCLUDES = listOf(
        "294367770999", "819946803642", "819943434", "819946803677", "438862163919"
    )
    val VALID_VACCINATOR_EXCLUDES =
        ADMINISTRATOR_EXCLUDES + COUNTY_DISEASE_SURVEILLANCE_OFFICER_EXCLUDES + SUBCOUNTY_DISEASE_SURVEILLANCE_OFFICER_EXCLUDES

    val VALID_COUNTY_DISEASE_SURVEILLANCE_OFFICER_EXCLUDES =
        ADMINISTRATOR_EXCLUDES + SUBCOUNTY_DISEASE_SURVEILLANCE_OFFICER_EXCLUDES + VACCINATOR_EXCLUDES
    val VALID_SUBCOUNTY_DISEASE_SURVEILLANCE_OFFICER_EXCLUDES =
        ADMINISTRATOR_EXCLUDES + COUNTY_DISEASE_SURVEILLANCE_OFFICER_EXCLUDES + VACCINATOR_EXCLUDES

    val VALID_ADMINISTRATOR_EXCLUDES =
        COUNTY_DISEASE_SURVEILLANCE_OFFICER_EXCLUDES + VACCINATOR_EXCLUDES + SUBCOUNTY_DISEASE_SURVEILLANCE_OFFICER_EXCLUDES
    val MPOX_GUIDES = listOf(
        "294367770999",  // County
        "819946803642",  // Subcounty
        "819943434",     // Ward
        "village",       // Village
        "team_no",       // Team No
        "team_type",     // Team type
        "campaign_day",  // Campaign Day
        "728034137219",  // Date
        "hcw_18_39_reported",       // Were any healthcare workers aged 18–39 years reported?
        "hcw_40_59_reported",       // Were any healthcare workers aged 40–59 years reported?
        "hcw_60_plus_reported",     // Were any healthcare workers aged 60+ years reported?
        "sw_18_39_reported",        // Were any sex workers aged 18–39 years reported?
        "sw_40_59_reported",        // Were any sex workers aged 40–59 years reported?
        "sw_60_plus_reported",      // Were any sex workers aged 60+ years reported?
        "td_18_39_reported",        // Were any truck drivers aged 18–39 years reported?
        "td_40_59_reported",        // Were any truck drivers aged 40–59 years reported?
        "td_60_plus_reported",      // Were any truck drivers aged 60+ years reported?
        "others_18_39_reported",    // Were any others aged 18–39 years reported?
        "others_40_59_reported",    // Were any others aged 40–59 years reported?
        "others_60_plus_reported",  // Were any others aged 60+ years reported?
        "aefi_yes_no"               // Reported AEFI?

    )


    val ALL_MPOX_LINK_IDS = FACILITY_DETAILS + MPOX_GUIDES


    const val LOCATION_STARTER = "${BASE_URL}Location?_count=200&_offset=0"
    const val NOTIFICATION_CODE = 1001
    const val FIRST_LAUNCH_KEY = "is_first_launch"
    private const val PREF_NAME = "pagination_pref"
    private const val KEY_NEXT_URL = "next_url"
    private const val KEY_DOWNLOAD_COMPLETE = "download_complete"

    const val TEST_TOKEN = BuildConfig.DEBUG_TOKEN

    fun saveNextUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        prefs.edit().putString(KEY_NEXT_URL, url).apply()
    }

    fun getNextUrl(context: Context): String? {
        val prefs = context.getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        return prefs.getString(KEY_NEXT_URL, null)
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, MODE_PRIVATE)
        prefs.edit().remove(KEY_NEXT_URL).apply()
    }

    fun markActionComplete(context: Context, action: Boolean) {
        val sharedPreferences: SharedPreferences =
            context.getSharedPreferences(context.getString(R.string.app_name), MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        editor.putBoolean(KEY_DOWNLOAD_COMPLETE, action)
        editor.apply()

    }

    fun isDownloadComplete(context: Context): Boolean {
        val sharedPreferences: SharedPreferences =
            context.getSharedPreferences(context.getString(R.string.app_name), MODE_PRIVATE)
        return sharedPreferences.getBoolean(KEY_DOWNLOAD_COMPLETE, false)
    }
}

