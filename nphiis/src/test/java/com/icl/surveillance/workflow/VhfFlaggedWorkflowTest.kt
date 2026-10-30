package com.icl.surveillance.workflow

import com.icl.surveillance.debug.VhfFlaggedWorkflow
import com.icl.surveillance.debug.VhfFlaggedWorkflow.Decision
import com.icl.surveillance.fhir.forms.FormFields
import com.icl.surveillance.fhir.forms.VhfSpecimens
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings > Process flagged VHF cases: which cases phase 2 takes to clearance, and the survivor
 * monitoring dates it records (debug/VhfFlaggedWorkflow).
 */
class VhfFlaggedWorkflowTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 30)

    @Test
    fun `only a final positive result moves a case on`() {
        assertEquals(Decision.Advance, VhfFlaggedWorkflow.decide(VhfSpecimens.Result.POSITIVE.label, cleared = false, died = false))
        listOf(VhfSpecimens.Result.NEGATIVE, VhfSpecimens.Result.PENDING, VhfSpecimens.Result.REJECTED).forEach {
            assertTrue(it.label, VhfFlaggedWorkflow.decide(it.label, cleared = false, died = false) is Decision.Skip)
        }
        assertTrue(VhfFlaggedWorkflow.decide(null, cleared = false, died = false) is Decision.Skip)
    }

    @Test
    fun `a cleared or dead case is never advanced again`() {
        val positive = VhfSpecimens.Result.POSITIVE.label
        assertEquals(Decision.Skip("already cleared"), VhfFlaggedWorkflow.decide(positive, cleared = true, died = false))
        assertTrue(VhfFlaggedWorkflow.decide(positive, cleared = false, died = true) is Decision.Skip)
    }

    @Test
    fun `survivor dates are all in the past and follow the monitoring window`() {
        val dates = VhfFlaggedWorkflow.SurvivorDates(today)
        val all = listOf(dates.admitted, dates.dischargePcr1, dates.dischargePcr2, dates.discharged) + dates.visits + dates.samples
        assertTrue("no future dates", all.all { !it.isAfter(today) })
        assertTrue(dates.admitted < dates.dischargePcr1 && dates.dischargePcr1 < dates.dischargePcr2 && dates.dischargePcr2 < dates.discharged)
        val window = FormFields.Epesm.FOLLOW_UP_START_DAY.toLong() until
            FormFields.Epesm.FOLLOW_UP_START_DAY.toLong() + FormFields.Epesm.FOLLOW_UP_DAYS
        dates.visits.forEach { assertTrue("$it in the visit window", ChronoUnit.DAYS.between(dates.discharged, it) in window) }
        assertEquals(FormFields.Epesm.NEGATIVES_TO_CLEAR, dates.samples.size)
        assertTrue(dates.samples.first() < dates.samples.last())
    }
}
