package com.icl.surveillance.models

import com.icl.surveillance.R
import kotlinx.serialization.Serializable

data class QuestionnaireAnswer(
    val linkId: String,
    val text: String,
    val answer: String
)

@Serializable
data class OutputGroup(
    val linkId: String,
    val text: String,
    val type: String,
    val items: List<OutputItem> = emptyList()
)

@Serializable
data class OutputItem(
    val linkId: String,
    var text: String,
    val type: String,
    var value: String? = "",
    var parentOperator: String? = "==",
    val enable: Boolean = true,
    val parentLink: String? = null,
    val parentResponse: String? = null,
)

@Serializable
data class QuestionnaireItem(
    val item: List<GroupItem>
)

@Serializable
data class QuestionnaireItemChild(
    val item: List<ChildItem>
)

@Serializable
data class GroupItem(
    val linkId: String,
    val text: String,
    val type: String,
    val item: List<ChildItem>? = null
)

@Serializable
data class ChildItem(
    val linkId: String,
    val text: String,
    val type: String,
    val item: List<ChildItem>? = null,
    val enableWhen: List<EnableWhen>? = null
)

data class EnableWhen(
    val question: String,
    val operator: String,
    val answerCoding: AnswerCoding? = null,
    val answerString: String? = null,
    val answerBoolean: Boolean? = null,
    val answerDate: String? = null,
    val answerInteger: Int?
)

data class AnswerCoding(
    val code: String,
    val display: String?
)


data class FCMToken(
    val token: String
)

data class BundleImportResult(
    val processed: Int,
    val failed: Int,
    val skipped: Int
)

data class RefreshToken(
    val refresh_token: String
)

data class DbSignIn(
    val idNumber: String,
    val password: String,
    val location: String,
)

data class DbResetPasswordData(val idNumber: String, val email: String)
data class FhirBundle(
    val resourceType: String,
    val id: String,
    val type: String,
    val link: List<FhirLink>?,
    val entry: List<FhirEntry>?
)

data class FhirLink(
    val relation: String,
    val url: String
)

data class FhirEntry(
    val fullUrl: String,
    val resource: LocationResource,
    val search: SearchInfo
)

data class LocationResource(
    val resourceType: String = "Location",
    val id: String,
    val meta: Meta,
    val name: String,
    val type: List<LocationType>,
    val partOf: PartOf? = null
)

data class Meta(
    val versionId: String,
    val lastUpdated: String,
    val source: String
)

data class LocationType(
    val coding: List<Coding>
)

data class Coding(
    val system: String,
    val code: String,
    val display: String
)

data class PartOf(
    val reference: String,
    val display: String
)


data class SearchInfo(
    val mode: String
)

data class DbSetPasswordReq(val resetCode: String, val idNumber: String, val password: String)
data class SetNewPasswordReq(
    val temporaryPassword: String,
    val idNumber: String,
    val password: String
)

data class DbSignInResponse(
    val access_token: String,
    val expires_in: String,
    val refresh_expires_in: String,
    val refresh_token: String,
    val firstLogin: Boolean,
)

data class DbResetPassword(
    val status: String,
    val response: String,
)

data class DbResponseError(
    val status: String,
    val error: String,
)


data class Notification(
    val id: String,
    val practitionerId: String,
    val encounterId: String,
    val investigationDate: String,
    val dueDate: String,
    val title: String,
    val body: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String
)

data class CaseOption(
    val title: String,
    val showCount: Boolean = false,
    val count: Int = 0
)

data class SpecimenConfig(
    val type: String,
    val entryLinkId: String,
    val dateLinkId: String
)

data class NotificationResponse(
    val status: String? = null,
    val notifications: List<Notification>? = emptyList(),
    val total: Int? = 0
)


data class UserResponse(
    val status: String,
    val user: User
)

data class User(
    val firstName: String?,                // Might not be returned
    val lastName: String?,                 // Might not be returned
    val fhirPractitionerId: String?,       // Can be null or missing
    val practitionerRole: String?,         // e.g. "VACCINATOR"
    val role: String?,                     // e.g. "VACCINATOR"
    val id: String?,                       // UUID
    val idNumber: String?,                 // e.g. "400300"
    val fullNames: String?,                // Sometimes missing
    val phone: String?,                    // Nullable (confirmed)
    val email: String?,                    // Nullable (confirmed)
    val status: Boolean?,                  // Present in API
    val locationInfo: LocationInfo?
)

data class LocationInfo(
    val facility: String?,
    val facilityName: String?,
    val ward: String?,
    val wardName: String?,
    val subCounty: String?,
    val subCountyName: String?,
    val county: String?,
    val countyName: String?,
    val country: String?,
    val countryName: String?
)


data class UserProfilePrefs(
    val firstName: String,
    val lastName: String,
    val fullNames: String,
    val email: String,
    val phone: String,
    val idNumber: String,
    val role: String,
    val county: String,
    val countyName: String,
    val subCounty: String,
    val subCountyName: String,
    val ward: String,
    val wardName: String,
    val facility: String,
    val facilityName: String
)

/**
 * Roles as defined on the NPHIIS web (user management).
 *
 * Every role is mapped to a [scope] (its area of jurisdiction). All access control in the app
 * (listing, counts, filters, sync, profile) is driven by [scope], so new roles only need to be
 * added here.
 *
 * Data entry is unchanged: questionnaires only understand the four legacy role codes in
 * `user_role`, so each role writes [formRole] for its scope.
 */
enum class UserRole(val key: String, val scope: LocationLevel, vararg val aliases: String) {
    // National
    ADMINISTRATOR("administrator", LocationLevel.NATIONAL),
    SUPERUSER("superuser", LocationLevel.NATIONAL),
    NATIONAL_FOCAL_PERSON("national_focal_person", LocationLevel.NATIONAL),
    NATIONAL_VIEWER("national_viewer", LocationLevel.NATIONAL),
    NATIONAL_LAB("national_lab", LocationLevel.NATIONAL),

    // County
    COUNTY_DISEASE_SURVEILLANCE_OFFICER("county_user", LocationLevel.COUNTY),
    COUNTY_HEALTH_RECORDS_INFORMATION_OFFICER("county_hrio", LocationLevel.COUNTY),
    COUNTY_HEALTH_PROMOTION_OFFICER("county_hpo", LocationLevel.COUNTY),
    COUNTY_VACCINATOR("county_vaccinator", LocationLevel.COUNTY),

    // Sub-county
    SUBCOUNTY_DISEASE_SURVEILLANCE_OFFICER("sub_county_user", LocationLevel.SUB_COUNTY),
    SUBCOUNTY_HEALTH_RECORDS_INFORMATION_OFFICER("sub_county_hrio", LocationLevel.SUB_COUNTY),
    SUBCOUNTY_HEALTH_PROMOTION_OFFICER("sub_county_hpo", LocationLevel.SUB_COUNTY),
    SUBCOUNTY_VACCINATOR("sub_county_vaccinator", LocationLevel.SUB_COUNTY),

    // Ward
    WARD_HEALTH_RECORDS_INFORMATION_OFFICER("ward_hrio", LocationLevel.WARD),
    WARD_HEALTH_PROMOTION_OFFICER("ward_hpo", LocationLevel.WARD),
    WARD_VACCINATOR("ward_vaccinator", LocationLevel.WARD),

    // Facility
    FACILITY_SURVEILLANCE_FOCAL_PERSON("facility_nurse", LocationLevel.FACILITY),
    FACILITY_HEALTH_RECORDS_INFORMATION_OFFICER("facility_hrio", LocationLevel.FACILITY),
    FACILITY_HEALTH_PROMOTION_OFFICER("facility_hpo", LocationLevel.FACILITY),
    FACILITY_VACCINATOR("facility_vaccinator", LocationLevel.FACILITY),
    LAB_TECHNICIAN("lab_technician", LocationLevel.FACILITY),
    SUPERVISOR("supervisor", LocationLevel.FACILITY, "SUPERVISORS"),
    VACCINATOR("vaccinator", LocationLevel.FACILITY),
    NURSE("nurse", LocationLevel.FACILITY);

    val isNational: Boolean get() = scope == LocationLevel.NATIONAL
    val isCounty: Boolean get() = scope == LocationLevel.COUNTY
    val isSubCounty: Boolean get() = scope == LocationLevel.SUB_COUNTY
    val isWard: Boolean get() = scope == LocationLevel.WARD
    val isFacility: Boolean get() = scope == LocationLevel.FACILITY

    val canAccessLab: Boolean
        get() = this == ADMINISTRATOR || this == NATIONAL_LAB || this == LAB_TECHNICIAN

    /**
     * Legacy role code written to the `user_role` questionnaire item. Questionnaire enableWhen
     * rules and [com.icl.surveillance.ui.patients.custom.GroupFragment] only know these four.
     * Ward roles use the sub-county form (county + sub-county locked, ward/facility selectable).
     */
    val formRole: String
        get() = when (scope) {
            LocationLevel.NATIONAL -> "ADMINISTRATOR"
            LocationLevel.COUNTY -> "COUNTY_DISEASE_SURVEILLANCE_OFFICER"
            LocationLevel.SUB_COUNTY, LocationLevel.WARD -> "SUBCOUNTY_DISEASE_SURVEILLANCE_OFFICER"
            LocationLevel.FACILITY -> "VACCINATOR"
        }

    companion object {
        private fun normalize(value: String) =
            value.trim().replace(Regex("[\\s-]+"), "_")

        fun fromAny(value: String?): UserRole? {
            if (value.isNullOrBlank()) return null
            val v = normalize(value)
            return entries.firstOrNull { role ->
                role.name.equals(v, ignoreCase = true) ||
                        role.key.equals(v, ignoreCase = true) ||
                        role.aliases.any { it.equals(v, ignoreCase = true) }
            }
        }

        fun fromKey(key: String): UserRole? =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
    }
}

enum class LocationLevel(val level: Int) {
    NATIONAL(0),
    COUNTY(1),
    SUB_COUNTY(2),
    WARD(3),
    FACILITY(4)
}

data class LocalLocationEntry(
    val fullUrl: String,
    val resource: LocalLocationResource
)

data class LocalLocationResource(
    val resourceType: String,
    val id: String,
    val name: String?,
    val meta: LocalMeta,
    val type: List<LocalLocationType>?,
    val partOf: LocalPartOf? = null
)

data class LocalMeta(
    val versionId: String,
    val lastUpdated: String,
    val source: String
)

data class LocalLocationType(
    val coding: List<LocalCoding>
)

data class LocalCoding(
    val system: String,
    val code: String,
    val display: String?
)

data class LocalPartOf(
    val reference: String,
    val display: String?
)

data class FailedSyncResource(
    val resourceType: String,
    val resourceId: String,
    val errorMessage: String? = null
)

data class FacilityInfo(
    val name: String,
    val code: String
)

data class NPHIISSyncProgress(
    val runId: String = "",
    val status: NPHIISSyncStatus = NPHIISSyncStatus.IDLE,
    val currentType: String = "",
    val locationDownloaded: Int = 0,
    val locationTarget: Int = 15_000,
    val lastMessage: String = "",
    val updatedAtEpochMs: Long = 0L,
)

enum class NPHIISSyncStatus { IDLE, RUNNING, SUCCESS, FAILED }

@Serializable
data class AuthTokenResponse(
    val access_token: String,
    val expires_in: Long,
    val refresh_expires_in: Long,
    val refresh_token: String,
    val token_type: String,
    val `not-before-policy`: Int,
    val session_state: String,
    val scope: String,
    val status: String
)