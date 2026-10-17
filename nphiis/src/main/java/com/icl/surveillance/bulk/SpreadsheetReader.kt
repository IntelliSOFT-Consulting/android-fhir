package com.icl.surveillance.bulk

import android.util.Xml
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.math.BigDecimal
import java.util.zip.ZipInputStream
import org.xmlpull.v1.XmlPullParser

/**
 * Reads the first worksheet of an Excel .xlsx file (or a .csv file) into a table of text cells,
 * without an Excel library: an .xlsx is a zip of XML parts. Cell values are returned as Excel
 * stores them (dates as serial numbers, see [BulkContactImport]).
 */
object SpreadsheetReader {

    data class Table(
        val headers: List<String>,
        /** Data rows (after the header row); each maps column index -> value. */
        val rows: List<Row>,
    )

    data class Row(
        /** Row number as shown in Excel (header = 1). */
        val number: Int,
        val cells: Map<Int, String>,
    )

    fun read(input: InputStream, fileName: String?): Table {
        val bytes = input.readBytes()
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val raw = when {
            isZip -> readXlsx(bytes)
            fileName?.endsWith(".xls", ignoreCase = true) == true ->
                throw IllegalArgumentException("Old .xls files are not supported. Save the file as .xlsx (Excel Workbook) and try again.")
            else -> readCsv(String(bytes, Charsets.UTF_8).removePrefix("﻿"))
        }
        val nonEmpty = raw.filter { (_, cells) -> cells.values.any { it.isNotBlank() } }
        if (nonEmpty.isEmpty()) return Table(emptyList(), emptyList())
        // The header row: the one with most text (non-number) cells among the first rows, so a
        // title row above it (e.g. "BUNDIBUGYO VIRUS DISEASE ... FORM") is skipped.
        val headerIndex = nonEmpty.take(HEADER_SEARCH_ROWS).withIndex()
            .maxByOrNull { (_, row) -> row.second.values.count { it.isNotBlank() && it.toBigDecimalOrNull() == null } }
            ?.index ?: 0
        val (_, headerCells) = nonEmpty[headerIndex]
        val width = (headerCells.keys.maxOrNull() ?: -1) + 1
        val headers = (0 until width).map { headerCells[it]?.trim().orEmpty() }
        val rows = nonEmpty.drop(headerIndex + 1).map { (number, cells) -> Row(number, cells) }
        return Table(headers, rows)
    }

    private const val HEADER_SEARCH_ROWS = 10

    // ---- .xlsx

    private fun readXlsx(bytes: ByteArray): List<Pair<Int, Map<Int, String>>> {
        val parts = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.removePrefix("/")
                if (name.endsWith(".xml") || name.endsWith(".rels")) parts[name] = zip.readBytes()
            }
        }
        val shared = parts["xl/sharedStrings.xml"]?.let { sharedStrings(it) }.orEmpty()
        val sheetPath = firstSheetPath(parts)
            ?: throw IllegalArgumentException("No worksheet found in this file.")
        val sheet = parts[sheetPath] ?: throw IllegalArgumentException("No worksheet found in this file.")
        return sheetRows(sheet, shared)
    }

    /** The first sheet in workbook order (workbook.xml + its relationships). */
    private fun firstSheetPath(parts: Map<String, ByteArray>): String? {
        val workbook = parts["xl/workbook.xml"]
        val rels = parts["xl/_rels/workbook.xml.rels"]
        if (workbook != null && rels != null) {
            var firstRid: String? = null
            parse(workbook) { p ->
                if (firstRid == null && p.eventType == XmlPullParser.START_TAG && p.name == "sheet") {
                    firstRid = (0 until p.attributeCount)
                        .firstOrNull { p.getAttributeName(it) == "id" || p.getAttributeName(it).endsWith(":id") }
                        ?.let { p.getAttributeValue(it) }
                }
            }
            var target: String? = null
            parse(rels) { p ->
                if (p.eventType == XmlPullParser.START_TAG && p.name == "Relationship" &&
                    p.getAttributeValue(null, "Id") == firstRid
                ) target = p.getAttributeValue(null, "Target")
            }
            target?.let { t ->
                val path = if (t.startsWith("/")) t.removePrefix("/") else "xl/$t"
                if (parts.containsKey(path)) return path
            }
        }
        return parts.keys.filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .minByOrNull { it.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }
    }

    private fun sharedStrings(xml: ByteArray): List<String> {
        val strings = ArrayList<String>()
        val current = StringBuilder()
        var inSi = false
        var inPhonetic = false
        var inText = false
        parse(xml) { p ->
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "si" -> { inSi = true; current.setLength(0) }
                    "rPh" -> inPhonetic = true
                    "t" -> inText = inSi && !inPhonetic
                }
                XmlPullParser.TEXT -> if (inText) current.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "si" -> { inSi = false; strings += current.toString() }
                    "rPh" -> inPhonetic = false
                    "t" -> inText = false
                }
            }
        }
        return strings
    }

    private fun sheetRows(xml: ByteArray, shared: List<String>): List<Pair<Int, Map<Int, String>>> {
        val rows = ArrayList<Pair<Int, Map<Int, String>>>()
        var rowNumber = 0
        var cells = HashMap<Int, String>()
        var column = 0
        var cellType: String? = null
        var nextColumn = 0
        val value = StringBuilder()
        var inValue = false
        parse(xml) { p ->
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> {
                        rowNumber = p.getAttributeValue(null, "r")?.toIntOrNull() ?: (rowNumber + 1)
                        cells = HashMap()
                        nextColumn = 0
                    }
                    "c" -> {
                        column = p.getAttributeValue(null, "r")?.let { columnIndex(it) } ?: nextColumn
                        nextColumn = column + 1
                        cellType = p.getAttributeValue(null, "t")
                        value.setLength(0)
                    }
                    "v", "t" -> inValue = true
                }
                XmlPullParser.TEXT -> if (inValue) value.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> inValue = false
                    "c" -> {
                        val raw = value.toString()
                        val text = when (cellType) {
                            "s" -> raw.trim().toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                            "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                            "inlineStr", "str", "e" -> raw
                            else -> plainNumber(raw)
                        }
                        if (text.isNotEmpty()) cells[column] = text
                    }
                    "row" -> rows += rowNumber to cells
                }
            }
        }
        return rows
    }

    /** "B12" -> 1 (zero-based column). */
    private fun columnIndex(reference: String): Int {
        var index = 0
        for (ch in reference) {
            if (!ch.isLetter()) break
            index = index * 26 + (ch.uppercaseChar() - 'A' + 1)
        }
        return index - 1
    }

    /** Numbers as typed: no "7.12345678E8" or trailing ".0". */
    private fun plainNumber(raw: String): String {
        val trimmed = raw.trim()
        return runCatching { BigDecimal(trimmed).stripTrailingZeros().toPlainString() }.getOrDefault(trimmed)
    }

    private fun parse(xml: ByteArray, onEvent: (XmlPullParser) -> Unit) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(ByteArrayInputStream(xml), "UTF-8")
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            // Tag names without a namespace prefix ("x:c" -> "c").
            onEvent(LocalNameParser(parser))
        }
    }

    /** Presents prefixed tag names (x:row) by their local name. */
    private class LocalNameParser(private val p: XmlPullParser) : XmlPullParser by p {
        override fun getName(): String? = p.name?.substringAfter(':')
    }

    // ---- .csv

    private fun readCsv(text: String): List<Pair<Int, Map<Int, String>>> {
        val delimiter = if (text.lineSequence().firstOrNull()?.count { it == ';' }
                ?.let { it > (text.lineSequence().first().count { c -> c == ',' }) } == true) ';' else ','
        val rows = ArrayList<Pair<Int, Map<Int, String>>>()
        var cells = HashMap<Int, String>()
        val field = StringBuilder()
        var column = 0
        var inQuotes = false
        var line = 1
        var i = 0
        fun endField() { if (field.isNotEmpty()) cells[column] = field.toString(); field.setLength(0); column++ }
        fun endRow() { endField(); rows += line to cells; cells = HashMap(); column = 0; line++ }
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == delimiter -> endField()
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    endRow()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || cells.isNotEmpty()) endRow()
        return rows
    }
}
