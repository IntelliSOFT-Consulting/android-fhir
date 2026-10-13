package com.icl.surveillance.fhir.forms

import android.content.Context
import com.icl.surveillance.utils.FormatterClass
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.Patient
import java.util.UUID

/**
 * "VHF Training": the VHF workflow (same forms, contacts, follow up, monitoring, lab) used for
 * practice. A record saved while in Trainings carries a Patient identifier
 * `system = vhf-training, value = test_xxxxxxxx` (and a matching meta tag), so it can be told
 * apart on the device, on the server and in reports. The VHF lists hide training records and the
 * Trainings lists show only them.
 *
 * The mode is set when a list or form is opened from the home screen (VHF tile = off,
 * VHF Training tile = on) and lasts until the other tile is opened.
 */
object VhfTraining {
    const val TITLE = "VHF Training"
    const val SYSTEM = "vhf-training"
    const val CODE = "TRAINING"
    const val PREFIX = "test_"
    private const val PREF_MODE = "vhfTrainingMode"

    fun isOn(context: Context): Boolean = FormatterClass().getSharedPref(PREF_MODE, context) == "true"

    fun setOn(context: Context, on: Boolean) =
        FormatterClass().saveSharedPref(PREF_MODE, on.toString(), context)

    fun isTraining(patient: Patient): Boolean =
        patient.identifier.any { it.system == SYSTEM } || patient.meta.tag.any { it.system == SYSTEM }

    /** Whether [patient] belongs in the VHF list being shown in the current mode. */
    fun matches(context: Context, patient: Patient): Boolean = isTraining(patient) == isOn(context)

    /** Marks a new [patient] as a training record: identifier test_xxxxxxxx and a meta tag. */
    fun mark(patient: Patient) {
        if (isTraining(patient)) return
        val value = PREFIX + UUID.randomUUID().toString().replace("-", "").take(8)
        patient.addIdentifier(Identifier().apply {
            system = SYSTEM
            this.value = value
            type.addCoding(Coding().apply { code = CODE; display = "Training record" })
        })
        // meta.tag may be a read-only list (set with listOf when the record was built): copy it.
        patient.meta.tag = (patient.meta.tag.orEmpty() + Coding(SYSTEM, value, "Training record")).toMutableList()
    }

    /** The test_xxxxxxxx value of a training record, or null. */
    fun idOf(patient: Patient): String? = patient.identifier.firstOrNull { it.system == SYSTEM }?.value
}
