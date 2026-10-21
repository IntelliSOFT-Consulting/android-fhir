package com.icl.surveillance.fhir.forms

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.get
import org.hl7.fhir.r4.model.Location
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import timber.log.Timber

/**
 * Location answers (county, sub county, ward, facility) carry the Location id and a display name.
 * The display can be wrong when it was copied in from elsewhere (a screening record, a login
 * profile): e.g. a sub-county answer showing the county's name. The id is what was picked, so the
 * display is set from that Location's own name before the answers are saved and shown.
 */
object LocationDisplays {
    private const val TAG = "LOCATION_DISPLAY"

    /** Sets every Location answer's display in [response] to the Location's name. Returns how many changed. */
    suspend fun fix(fhirEngine: FhirEngine, response: QuestionnaireResponse): Int {
        val names = HashMap<String, String?>()
        var changed = 0
        suspend fun visit(items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>) {
            for (item in items) {
                for (answer in item.answer) {
                    val ref = answer.value as? Reference
                    val id = ref?.reference?.takeIf { it.startsWith("Location/") }?.removePrefix("Location/")
                        ?.takeIf { it.isNotBlank() && it != "null" && it != "unknown" }
                    if (ref != null && id != null) {
                        val name = names.getOrPut(id) {
                            runCatching { fhirEngine.get<Location>(id).name }.getOrNull()?.takeIf { it.isNotBlank() }
                        }
                        if (name != null && name != ref.display) {
                            Timber.tag(TAG).w("%s: Location/%s shown as '%s', is '%s'", item.linkId, id, ref.display, name)
                            ref.display = name
                            changed++
                        }
                    }
                    visit(answer.item)
                }
                visit(item.item)
            }
        }
        visit(response.item)
        return changed
    }
}
