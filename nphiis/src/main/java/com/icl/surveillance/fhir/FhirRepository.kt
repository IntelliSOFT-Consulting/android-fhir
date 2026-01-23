package com.icl.surveillance.fhir

import android.content.Context


class FhirRepository(
    private val context: Context
) {
    private val fhirEngine = FhirApplication.fhirEngine(context)
    private val viewModel = MpoxUploadViewModel(fhirEngine)
    fun handleDataUpload(slug: String, context: Context) {
        viewModel.prepareUploadData(slug, context)
    }
}