package com.icl.surveillance.fhir.forms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecimenIdTest {

    @Test
    fun `formats a timestamp as 8 upper-case alphanumeric characters`() {
        val id = SpecimenId.format(1_792_051_200_000L) // 15 Oct 2026
        assertEquals(8, id.length)
        assertTrue(id.all { it in '0'..'9' || it in 'A'..'Z' })
    }

    @Test
    fun `pads small values and keeps time order`() {
        assertEquals("00000000", SpecimenId.format(0))
        assertEquals("ZZZZZZZZ", SpecimenId.format(2_821_109_907_455L))
        assertTrue(SpecimenId.format(1_792_051_200_000L) < SpecimenId.format(1_792_051_200_001L))
    }
}
