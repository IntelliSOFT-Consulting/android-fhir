package com.icl.surveillance.fhir.forms

import org.hl7.fhir.r4.model.QuestionnaireResponse

/** Which VHF form a saved record was made with, so editing opens that form. */
object VhfForms {

    /**
     * [FormFields.Vhf.CONTACT_FORM] for a contact registered with the contact form, else
     * [FormFields.Vhf.CASE_FORM]. The contact form has no Travel History section, which the case
     * form always saves, so a record with contact-form answers and no travel section is a
     * contact-form record (a converted contact, re-saved in the case form, has the section).
     */
    fun formFor(response: QuestionnaireResponse?): String {
        if (response == null) return FormFields.Vhf.CASE_FORM
        val linkIds = HashSet<String>()
        fun collect(items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>) {
            items.forEach { item ->
                linkIds += item.linkId
                collect(item.item)
                item.answer.forEach { collect(it.item) }
            }
        }
        collect(response.item)
        val contactForm = linkIds.contains(FormFields.Vhf.CONTACT_CATEGORY)
        return if (contactForm && "vhf-travel" !in linkIds) FormFields.Vhf.CONTACT_FORM
        else FormFields.Vhf.CASE_FORM
    }
}
