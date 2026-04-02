package com.icl.surveillance.utils

import android.content.Context
import android.util.Log
import ca.uhn.fhir.context.FhirContext
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.search.search
import com.icl.surveillance.models.BundleImportResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Location
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Resource
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream

class FhirBundleLoader(private val context: Context) {

    suspend fun loadBundleJson(fileName: String): InputStream {
        return withContext(Dispatchers.IO) {
            context.assets.open(fileName)
        }
    }

    suspend fun parseFhirBundle(json: InputStream): Bundle {
        return withContext(Dispatchers.IO) {
            json.use { stream ->
                val parser = FhirContext.forR4().newJsonParser()
                parser.parseResource(Bundle::class.java, stream.reader())
            }
        }
    }

    suspend fun createBundleInEngine(
        engine: FhirEngine,
        bundle: Bundle,
        onProgress: ((processed: Int, skipped: Int, failed: Int, total: Int) -> Unit)? = null
    ): BundleImportResult {

        var processed = 0
        var failed = 0
        var skipped = 0
        val total = bundle.entry.size
        val progressInterval = 20

        withContext(Dispatchers.IO.limitedParallelism(2)) {

            val existingIds = engine.search<Location> { }
                .mapNotNull { it.resource.id }
                .toHashSet()

            bundle.entry.chunked(250).map { batch ->
                async {
                    batch.forEachIndexed { index, entry ->
                        val id = entry.resource.id
                        if (existingIds.contains(id)) {
                            skipped++
                        } else {
                            try {
                                engine.create(entry.resource)
                                processed++
                            } catch (e: Exception) {
                                failed++
                                Log.e("FHIR", "Failed to import ${entry.resource.id}: ${e.message}")
                            }
                            if ((index + 1) % progressInterval == 0 || index == bundle.entry.lastIndex) {
                                val currentProcessed = processed
                                val currentSkipped = skipped
                                val currentFailed = failed

                                // Launch UI update on Main thread
                                withContext(Dispatchers.Main) {
                                    onProgress?.invoke(currentProcessed, currentSkipped, currentFailed, total)
                                }
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        return BundleImportResult(processed, failed, skipped)
    }


}