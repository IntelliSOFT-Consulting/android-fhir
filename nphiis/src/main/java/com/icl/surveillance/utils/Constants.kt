package com.icl.surveillance.utils

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import com.icl.surveillance.R

object Constants {
    const val UPLOAD_BUNDLE_SIZE = 500

    const val BASE_URL = "https://dsrfhir.intellisoftkenya.com/v2/fhir/"
    const val BASE_AUTH_URL = "https://dsrkeycloak.intellisoftkenya.com/auth/"
    const val ALERTS_BASE_URL = "https://dsrfhir.intellisoftkenya.com/api/"

    // ---- Client lookup before a new case form (ClientLookupActivity). Two sources, picked on screen.

    /**
     * IPRS, via SHA Health ID: GET [IPRS_LOOKUP_URL]?id_number=<number>&type=citizen.
     * Reply: { "data": { "first_name", "last_name", "other_names", "sex": "M", "date_of_birth", ... } }.
     * Basic auth with [IPRS_LOOKUP_USERNAME] / [IPRS_LOOKUP_PASSWORD]; the app's own login token
     * is not sent to this host.
     */
    const val IPRS_LOOKUP_BASE_URL = "https://healthid.sha.go.ke/api/"
    const val IPRS_LOOKUP_URL = "${IPRS_LOOKUP_BASE_URL}v1/lookup/persons"
    const val IPRS_LOOKUP_ID_PARAM = "id_number"
    const val IPRS_LOOKUP_TYPE_PARAM = "type"

    /** SHA Health ID Basic auth. TODO: move out of source before release. */
    const val IPRS_LOOKUP_USERNAME = "IN2SKF4bspFkgtnxNNMRLXXY2XpiNiv6"
    /**
     * Ready-made Basic auth value for testing. When not blank it is sent as-is instead of building
     * one from [IPRS_LOOKUP_USERNAME] / [IPRS_LOOKUP_PASSWORD]; clear it to use those again.
     */
    const val IPRS_LOOKUP_BASIC_AUTH =
        "Basic SU4yU0tGNGJzcEZrZ3RueE5OTVJMWFhZMlhwaU5pdjY6MURHeU5oOTRGQUNDV0hwWGptUmlTZ0drV0xCWXpOdHA="

    const val IPRS_LOOKUP_PASSWORD = "1DGyNh94FACCWHpXjmRiSgGkWLBYzNtp"

    /** Case-form ID type code -> IPRS `type`. IPRS holds national IDs only. */
    val IPRS_LOOKUP_ID_TYPES = mapOf("national-id" to "citizen")

    /**
     * Client Registry (CR) patient search, via the backend:
     * GET [CLIENT_LOOKUP_URL]?identification_number=<number>&identification_type=<type>
     * with the user's bearer token. Reply: ClientLookupResponse.
     */
    const val CLIENT_LOOKUP_BASE_URL = "https://dsrfhir.intellisoftkenya.com/api/"
    const val CLIENT_LOOKUP_URL = "${CLIENT_LOOKUP_BASE_URL}v1/patients"
    const val CLIENT_LOOKUP_ID_PARAM = "identification_number"
    const val CLIENT_LOOKUP_TYPE_PARAM = "identification_type"

    /** true: answer CR lookups from MockClientRegistry test data until the CR API is ready. */
    const val CLIENT_LOOKUP_USE_MOCK = true

    /** Case-form ID type code -> identification_type value the CR expects. TODO: confirm values. */
    val CLIENT_LOOKUP_ID_TYPES = mapOf(
        "national-id" to "National ID",
        "passport" to "Passport",
        "birth-certificate" to "Birth Certificate",
    )

   /*    const val BASE_URL = "https://auth.nphiis.health.go.ke/fhir/"
       const val BASE_AUTH_URL = "https://auth.nphiis.health.go.ke/"
       const val ALERTS_BASE_URL = BASE_AUTH_URL*/

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

    const val TEST_TOKEN =
        "eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJxbTVfeFpQWFVHV1I0YTdwbkpLZ1VORVRNbERMMlpHeXhUSndNSEx5UkhjIn0.eyJleHAiOjE3ODU3Mzc3ODEsImlhdCI6MTc3MDE4NTc4MSwianRpIjoiZmFlOTU5ZTEtMGJiNy00ZjQ5LWJlZjUtMDg0YzUwYzcyZDc5IiwiaXNzIjoiaHR0cHM6Ly9hcGkubnBoaWlzLmhlYWx0aC5nby5rZS9yZWFsbXMvbWFzdGVyIiwiYXVkIjoiYWNjb3VudCIsInN1YiI6ImI0NWYzMTIyLTBiZGQtNDdhNS1hMDM4LTg2ZGFmNDc3NmNlYSIsInR5cCI6IkJlYXJlciIsImF6cCI6ImNoYW5qby1jbGllbnQtYXBpcyIsInNpZCI6IjdhZmFmYTQwLTgzY2UtNDE3Yy05MWQyLTkzYjdjMWY2MWYyZCIsImFjciI6IjEiLCJyZWFsbV9hY2Nlc3MiOnsicm9sZXMiOlsiZGVmYXVsdC1yb2xlcy1tYXN0ZXIiLCJvZmZsaW5lX2FjY2VzcyIsInVtYV9hdXRob3JpemF0aW9uIl19LCJyZXNvdXJjZV9hY2Nlc3MiOnsiYWNjb3VudCI6eyJyb2xlcyI6WyJtYW5hZ2UtYWNjb3VudCIsIm1hbmFnZS1hY2NvdW50LWxpbmtzIiwidmlldy1wcm9maWxlIl19fSwic2NvcGUiOiJlbWFpbCBvcmdhbml6YXRpb24gcHJvZmlsZSBvcGVuaWQiLCJlbWFpbF92ZXJpZmllZCI6ZmFsc2UsIm5hbWUiOiJEZW5uaXMgTW9zb3RpIiwicHJlZmVycmVkX3VzZXJuYW1lIjoiMjgwOTMyNjUiLCJnaXZlbl9uYW1lIjoiRGVubmlzIiwiZmFtaWx5X25hbWUiOiJNb3NvdGkiLCJlbWFpbCI6ImRvbnNvbmdvMjNAZ21haWwuY29tIn0.nLy64OoYxlRmwHCQD3Lk_TklkUnypxSmRN5j6_0weRp_KfFGT3dPfKuTn2qEj7GhCn1Ky75DfmC-K-AIiAPDYi9mwzNBPHVHvhmAlE29CM4-VAwN64ygt-KSumMelXK3lz76OHzm-YPXf5D_5uP3DN-aEgsl5ljMAm98xU8z5gnFYS377Oq2AlFfG0IU4oLP_8X3jUL_CuMiDM_WvXySxrSuE-scl4R4-kH5emcvT4mpYKT4eNRTw9VErSsT3zfaYtt2snc9S07UC086ARh4jfaNeOfQHYpmOWtrk-v6uBJviQB99tmt7UN4K-evdZk7nz4ZVyevzo5Dff2L48Syqg"

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

