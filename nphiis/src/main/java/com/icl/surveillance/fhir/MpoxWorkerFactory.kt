package com.icl.surveillance.fhir

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

class MpoxWorkerFactory(
    private val repo: FhirRepository
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        return when (workerClassName) {
            MpoxSyncWorker::class.java.name ->
                MpoxSyncWorker(appContext, workerParameters)

            else -> null
        }
    }
}
