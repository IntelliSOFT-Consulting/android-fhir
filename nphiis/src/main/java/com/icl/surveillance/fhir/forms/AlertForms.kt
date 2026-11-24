package com.icl.surveillance.fhir.forms

import com.icl.surveillance.models.QuestionnaireAnswer
import org.hl7.fhir.r4.model.HumanName
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

/** What an FVD alert's record is named after. */
object FvdAlerts {

    /** The case's name; a public health event is "<village, facility or sub-county> Alert". */
    fun nameOf(answers: List<QuestionnaireAnswer>): HumanName {
        val name = HumanName()
        answers.answerOf(FormFields.FvdAlert.CASE_NAME)?.trim()?.takeIf { it.isNotEmpty() }?.let { case ->
            val words = case.split(Regex("\\s+"))
            name.family = words.last()
            words.dropLast(1).forEach { name.addGiven(it) }
            return name
        }
        name.family = FormFields.AlertLog.NO_PATIENT_NAME
        listOf(FormFields.FvdAlert.VILLAGE, FormFields.FvdAlert.FACILITY, FormFields.Residence.SUB_COUNTY)
            .firstNotNullOfOrNull { answers.answerOf(it) }
            ?.let { name.addGiven(it) }
        return name
    }
}
