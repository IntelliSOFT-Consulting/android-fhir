package com.icl.surveillance.fhir

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking


class MpoxSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {
    private val repo = FhirRepository(appContext)

    override fun doWork(): Result {
        return try {
            runBlocking {
                // Step 1: Upload Patients
                prepareListInBatches("patients", applicationContext)
                // Wait until done (since suspending)

                // Step 2: Upload Encounters
                prepareEncountersBatches("encounters", applicationContext)

                // Step 3: Upload Observations
                prepareObsBatches("observations", applicationContext)

                // Step 4: Upload Measure Reports
                prepareMeasureReportsBatches("measureReports", applicationContext)

                // Step 5: Upload Questionnaire Responses
                prepareQuestionnaireResponsesBatches("questionnaireResponses", applicationContext)
            }
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry() // or Result.failure()
        }
    }

    private fun prepareListInBatches(nameQuery: String, context: Context) {
        // move your code here but make it suspend-friendly
        CoroutineScope(Dispatchers.IO).launch {
            repo.handleDataUpload(nameQuery, context)
        }
    }

    private fun prepareEncountersBatches(nameQuery: String, context: Context) {
        // move your code here but make it suspend-friendly
        CoroutineScope(Dispatchers.IO).launch {
            repo.handleDataUpload(nameQuery, context)
        }
    }

    private fun prepareObsBatches(nameQuery: String, context: Context) {
        // move your code here but make it suspend-friendly
        CoroutineScope(Dispatchers.IO).launch {
            repo.handleDataUpload(nameQuery, context)
        }
    }

    private fun prepareMeasureReportsBatches(nameQuery: String, context: Context) {
        // move your code here but make it suspend-friendly
        CoroutineScope(Dispatchers.IO).launch {
            repo.handleDataUpload(nameQuery, context)
        }
    }

    private fun prepareQuestionnaireResponsesBatches(nameQuery: String, context: Context) {
        // move your code here but make it suspend-friendly
        CoroutineScope(Dispatchers.IO).launch {
            repo.handleDataUpload(nameQuery, context)
        }
    }
}
