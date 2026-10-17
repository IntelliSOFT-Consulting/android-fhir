package com.icl.surveillance.bulk

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import timber.log.Timber

/**
 * CSV log of a bulk upload, one line per spreadsheet row, appended (and flushed) as each row is
 * handled, so it is complete up to the last row even if the import is interrupted. Kept in the
 * app's files (bulk-uploads/); the Bulk Upload screen saves a copy wherever the user picks.
 *
 * Columns: time, row, name, status (imported / failed / skipped), detail, patient_id, source_case_id.
 */
class ImportLog private constructor(val file: File) {

    fun row(
        row: BulkContactImport.RowPlan,
        status: String,
        detail: String,
        patientId: String? = null,
    ) = synchronized(this) {
        // Also to Logcat (tag BULK_UPLOAD), to follow the import live.
        Timber.tag(TAG).i(
            "%s row %d – %s%s%s",
            status, row.rowNumber, row.name,
            if (detail.isBlank()) "" else " ($detail)",
            patientId?.let { " -> Patient/$it" }.orEmpty(),
        )
        file.appendText(
            listOf(
                TIME.format(Date()), row.rowNumber.toString(), row.name, status, detail,
                patientId.orEmpty(), row.sourcePatientId.orEmpty(),
            ).joinToString(",") { csv(it) } + "\n"
        )
    }

    companion object {
        const val TAG = "BULK_UPLOAD"
        const val IMPORTED = "imported"
        const val FAILED = "failed"
        const val SKIPPED = "skipped"

        private val TIME = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        /** A new log for an import of [sourceFile], named after it and the time. */
        fun start(context: Context, sourceFile: String?): ImportLog {
            val dir = File(context.filesDir, "bulk-uploads").apply { mkdirs() }
            val base = sourceFile?.substringBeforeLast('.')?.replace(Regex("[^A-Za-z0-9_-]+"), "_")
                ?.take(40)?.trim('_').orEmpty().ifEmpty { "upload" }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val file = File(dir, "bulk-upload-log-$base-$stamp.csv")
            file.writeText("time,row,name,status,detail,patient_id,source_case_id\n")
            Timber.tag(TAG).i("Import of %s started, log %s", sourceFile ?: "file", file.name)
            return ImportLog(file)
        }

        private fun csv(value: String): String =
            if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
                "\"" + value.replace("\"", "\"\"") + "\""
            } else value
    }
}
