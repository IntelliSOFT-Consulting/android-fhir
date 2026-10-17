package com.icl.surveillance.bulk

import org.junit.Assert.assertEquals
import org.junit.Test

class SpreadsheetReaderTest {

    @Test
    fun `reads a csv with a header row, quotes and empty lines`() {
        val csv = "Family name (surname),Given names,Phone number\n" +
            "Otieno,\"Mary, Achieng\",0712345678\n\n" +
            "Kamau,John,\n"
        val table = SpreadsheetReader.read(csv.byteInputStream(), "contacts.csv")
        assertEquals(listOf("Family name (surname)", "Given names", "Phone number"), table.headers)
        assertEquals(2, table.rows.size)
        assertEquals("Mary, Achieng", table.rows[0].cells[1])
        assertEquals("0712345678", table.rows[0].cells[2])
        assertEquals("Kamau", table.rows[1].cells[0])
    }

    @Test
    fun `column names match questions whatever the case and punctuation`() {
        assertEquals(
            BulkContactImport.normalize("Family name (surname)"),
            BulkContactImport.normalize("FAMILY NAME  surname "),
        )
    }

    @Test
    fun `skips a title row above the column names and keeps Excel row numbers`() {
        val csv = "CONTACT FOLLOW-UP FORM\n" +
            "No,Full Name,Sex (M/F),County\n" +
            "1,Abdi Hussein,M,Nairobi\n"
        val table = SpreadsheetReader.read(csv.byteInputStream(), "list.csv")
        assertEquals(listOf("No", "Full Name", "Sex (M/F)", "County"), table.headers)
        assertEquals(1, table.rows.size)
        assertEquals(3, table.rows[0].number)
        assertEquals("Abdi Hussein", table.rows[0].cells[1])
    }
}
