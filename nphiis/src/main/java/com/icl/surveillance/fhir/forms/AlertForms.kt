package com.icl.surveillance.fhir.forms

import org.hl7.fhir.r4.model.QuestionnaireResponse

/** Which alert form a saved alert was made with, so viewing and editing use that form. */
object AlertForms {

    /**
     * [FormFields.AlertLog.BACKUP_FORM] for an alert saved with the earlier Alert Log (it has none
     * of the triage form's sections), else [FormFields.AlertLog.FORM].
     */
    fun formFor(response: QuestionnaireResponse?): String =
        if (response == null || !response.hasItem() ||
            response.item.any { it.linkId == FormFields.AlertLog.TRIAGE_SECTION }
        ) FormFields.AlertLog.FORM
        else FormFields.AlertLog.BACKUP_FORM
}
