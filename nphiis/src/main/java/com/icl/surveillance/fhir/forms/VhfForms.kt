package com.icl.surveillance.fhir.forms

import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.QuestionnaireResponse

/** Which VHF form a saved record was made with, so viewing and editing use that form. */
object VhfForms {

    /** Meta tag on a VHF registration response naming its form (vhf-case.json / vhf-contact.json). */
    const val FORM_TAG_SYSTEM = "vhf-form"

    /**
     * Marks [response] as made with [formFile] when that is a VHF registration form (case or
     * contact); any earlier mark is replaced (e.g. a contact converted to a case).
     */
    fun stamp(response: QuestionnaireResponse, formFile: String?) {
        if (!FormFields.Vhf.isRegistrationForm(formFile)) return
        response.meta.tag.removeAll { it.system == FORM_TAG_SYSTEM }
        response.meta.addTag(Coding().setSystem(FORM_TAG_SYSTEM).setCode(formFile))
    }

    fun isStamped(response: QuestionnaireResponse): Boolean =
        response.meta.tag.any { it.system == FORM_TAG_SYSTEM }

    /**
     * [FormFields.Vhf.CONTACT_FORM] for a contact registered with the contact form, else
     * [FormFields.Vhf.CASE_FORM]. Records saved from now on carry a form tag ([stamp]). Older
     * ones are told apart by their questions: the case form always saves its Travel History
     * section, which the contact form does not have, and a contact-form record names its source
     * case, type of contact or "Type of case = Contact".
     */
    fun formFor(response: QuestionnaireResponse?): String {
        if (response == null) return FormFields.Vhf.CASE_FORM
        response.meta.tag.firstOrNull { it.system == FORM_TAG_SYSTEM }?.code
            ?.takeIf { FormFields.Vhf.isRegistrationForm(it) }
            ?.let { return it }

        val linkIds = HashSet<String>()
        var caseTypeContact = false
        fun collect(items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>) {
            items.forEach { item ->
                linkIds += item.linkId
                if (item.linkId == FormFields.Vhf.CASE_TYPE && item.answer.any { answer ->
                        (answer.value as? Coding)?.code.equals(FormFields.Vhf.CASE_TYPE_CONTACT, ignoreCase = true)
                    }
                ) caseTypeContact = true
                collect(item.item)
                item.answer.forEach { collect(it.item) }
            }
        }
        collect(response.item)
        val contactMarks = caseTypeContact ||
            FormFields.Vhf.CONTACT_CATEGORY in linkIds ||
            FormFields.Vhf.SOURCE_CASE_EPID in linkIds
        return if (contactMarks && "vhf-travel" !in linkIds) FormFields.Vhf.CONTACT_FORM
        else FormFields.Vhf.CASE_FORM
    }
}
