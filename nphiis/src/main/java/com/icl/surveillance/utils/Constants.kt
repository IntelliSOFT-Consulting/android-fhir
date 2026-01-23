package com.icl.surveillance.utils

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import com.icl.surveillance.R

object Constants {
//    const val BASE_URL = "https://dsrfhir.intellisoftkenya.com/hapi/fhir/"

//    const val BASE_AUTH_URL = "https://dsrkeycloak.intellisoftkenya.com/auth/"
//    const val ALERTS_BASE_URL = "https://dsrfhir.intellisoftkenya.com/api/"

    const val BASE_URL = "https://auth.nphiis.nphl.go.ke/fhir/"
    const val BASE_AUTH_URL = "https://auth.nphiis.nphl.go.ke/"
    const val ALERTS_BASE_URL = BASE_AUTH_URL// "https://dsrfhir.intellisoftkenya.com/api/"


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
        "294367770999",
        "819946803642",
        "819943434",
        "819946803677",
        "438862163919"
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

    const val TEST_TOKEN ="eyJhbGciOiJSUzI1NiIsInR5cCIgOiAiSldUIiwia2lkIiA6ICJxbTVfeFpQWFVHV1I0YTdwbkpLZ1VORVRNbERMMlpHeXhUSndNSEx5UkhjIn0.eyJleHAiOjE3ODQ0NjI4MzgsImlhdCI6MTc2ODkxMDgzOCwianRpIjoiZjVlYWQwNTMtZjk4NC00YjZjLWJkYjktMGY5NjIzYjZhMzdjIiwiaXNzIjoiaHR0cHM6Ly9hcGkubnBoaWlzLmhlYWx0aC5nby5rZS9yZWFsbXMvbWFzdGVyIiwiYXVkIjoiYWNjb3VudCIsInN1YiI6ImNkNDA4MTFhLWIxNzQtNDVkMC1hZDYzLTZmZjU2ZWQyNDlkZiIsInR5cCI6IkJlYXJlciIsImF6cCI6ImNoYW5qby1jbGllbnQtYXBpcyIsInNpZCI6ImFmMWMyOWM3LTdhNjMtNDIzZC04NTM0LTIyNjk2ZmQ2MjEwYiIsImFjciI6IjEiLCJyZWFsbV9hY2Nlc3MiOnsicm9sZXMiOlsiZGVmYXVsdC1yb2xlcy1tYXN0ZXIiLCJvZmZsaW5lX2FjY2VzcyIsInVtYV9hdXRob3JpemF0aW9uIl19LCJyZXNvdXJjZV9hY2Nlc3MiOnsiYWNjb3VudCI6eyJyb2xlcyI6WyJtYW5hZ2UtYWNjb3VudCIsIm1hbmFnZS1hY2NvdW50LWxpbmtzIiwidmlldy1wcm9maWxlIl19fSwic2NvcGUiOiJlbWFpbCBvcmdhbml6YXRpb24gcHJvZmlsZSBvcGVuaWQiLCJlbWFpbF92ZXJpZmllZCI6ZmFsc2UsIm5hbWUiOiJLaXByb3RpY2ggSmFwaGV0aCIsInByZWZlcnJlZF91c2VybmFtZSI6IjMyNjQ1MTY3IiwiZ2l2ZW5fbmFtZSI6IktpcHJvdGljaCIsImZhbWlseV9uYW1lIjoiSmFwaGV0aCIsImVtYWlsIjoiamtpcHJvdGljaEBpbnRlbGxpc29mdGtlbnlhLmNvbSJ9.aSLmzqE_lp2YINGv2iA1gImoZ-CXnfuhLqpT1Lyp7FF9T_7U3FPXKV_lXHCzluMqJRBtlD5SVChJC4CilbPCqbcYUohLPB7wrncl2upSySFaWSttETOB1qdaWo5xd_riHEdyfppmfENg7QCqn9nIC2GthDjrU31Fb-js0sx6_ihu4uVrgSAri-2TxPq5BIXc3entX99tBlydDfc_K1i_nVxF5qsKjzc43GB82jpPMZ2dssgC0IXMaYzr67_lLE_pqzqVsYIOHrLUlv9F27AY3znsXKYKdrkj6KIZhmC82u2jBCcjaLWf7QtnPHB_tsj89QJvqvF_DI9yjJX5IfQLrA"
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

