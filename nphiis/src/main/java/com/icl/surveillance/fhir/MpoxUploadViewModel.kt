package com.icl.surveillance.fhir

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.FhirVersionEnum
import ca.uhn.fhir.parser.IParser
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.search.Order
import com.google.android.fhir.search.search
import com.icl.surveillance.network.RetrofitCallsAuthentication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.MeasureReport
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.QuestionnaireResponse


class MpoxUploadViewModel(
    private val fhirEngine: FhirEngine
) : ViewModel() {

    private var pageUpload = 0
    private val pageSizeUpload = 50
    private var hasMoreUpload = true
    private var isUploadLoading = false

    fun prepareUploadData(slug: String, context: Context) {
        viewModelScope.launch {
            while (hasMoreUpload) {
                when (slug) {
                    "patients" -> {
                        prepareListInBatches(context)
                    }

                    "encounters" -> {
                        prepareEncountersBatches(slug, context)
                    }

                    "observations" -> {
                        prepareObsBatches(slug, context)
                    }

                    "measureReports" -> {
                        prepareMeasureReportBatches(slug, context)
                    }

                    "questionnaireResponses" -> {
                        prepareQuestionnaireResponseBatches(slug, context)
                    }

                    else -> {

                    }
                }
                delay(500L) // optional pause to mimic scrolling
            }
        }
    }

    fun prepareListInBatches(context: Context) {

        if (!hasMoreUpload || isUploadLoading) return
        isUploadLoading = true
        pageUpload++
        val jsonParser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()

        viewModelScope.launch(Dispatchers.IO) {
            val results = fhirEngine.search<Patient> {
                sort(Patient.GIVEN, Order.ASCENDING)
                count = pageSizeUpload
                from = (pageUpload - 1) * pageSizeUpload
            }

            if (results.isEmpty()) {
                hasMoreUpload = false
            } else {
                val bundle = Bundle()
                bundle.type = Bundle.BundleType.TRANSACTION

                results.forEach { patient ->
                    val patientResource = patient.resource//.copy() as Patient
//                    patientResource.nameFirstRep.family = "test-bundle-2"
                    //  sendSingleEntry(jsonParser, patientResource)
                    val bundleEntry = Bundle.BundleEntryComponent()
                    bundleEntry.resource = patientResource
                    bundleEntry.fullUrl = "Patient/${patientResource.idElement.idPart}"
                    bundleEntry.request = Bundle.BundleEntryRequestComponent()
                    bundleEntry.request.setMethod(Bundle.HTTPVerb.PUT)
                    bundleEntry.request.url =
                        "Patient/${patientResource.idElement.idPart}"
                    bundle.addEntry(bundleEntry)
                }
                sendBundleToServer(jsonParser, bundle, context)
            }
            isUploadLoading = false
        }
    }

    fun prepareEncountersBatches(nameQuery: String, context: Context) {
        val isSummary = nameQuery.contains("mpox")
        if (!hasMoreUpload || isUploadLoading) return
        isUploadLoading = true
        pageUpload++
        val jsonParser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()

        viewModelScope.launch(Dispatchers.IO) {
            val results = fhirEngine.search<Encounter> {
                count = pageSizeUpload
                from = (pageUpload - 1) * pageSizeUpload
            }

            if (results.isEmpty()) {
                hasMoreUpload = false
            } else {
                val bundle = Bundle()
                bundle.type = Bundle.BundleType.TRANSACTION

                results.forEach { patient ->
                    val patientResource = patient.resource//.copy() as Encounter
//                    patientResource.nameFirstRep.family = "test-bundle-2"
                    //  sendSingleEntry(jsonParser, patientResource)
                    val bundleEntry = Bundle.BundleEntryComponent()
                    bundleEntry.resource = patientResource
                    bundleEntry.fullUrl = "Encounter/${patientResource.idElement.idPart}"
                    bundleEntry.request = Bundle.BundleEntryRequestComponent()
                    bundleEntry.request.setMethod(Bundle.HTTPVerb.PUT)
                    bundleEntry.request.url =
                        "Encounter/${patientResource.idElement.idPart}"
                    bundle.addEntry(bundleEntry)
                }
                sendBundleToServer(jsonParser, bundle, context)
            }
            isUploadLoading = false
        }
    }

    fun prepareObsBatches(nameQuery: String, context: Context) {
        val isSummary = nameQuery.contains("mpox")
        if (!hasMoreUpload || isUploadLoading) return
        isUploadLoading = true
        pageUpload++
        val jsonParser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()

        viewModelScope.launch(Dispatchers.IO) {
            val results = fhirEngine.search<Observation> {
                count = pageSizeUpload
                from = (pageUpload - 1) * pageSizeUpload
            }

            if (results.isEmpty()) {
                hasMoreUpload = false
            } else {
                val bundle = Bundle()
                bundle.type = Bundle.BundleType.TRANSACTION

                results.forEach { patient ->
                    val patientResource = patient.resource//.copy() as Observation
//                    patientResource.nameFirstRep.family = "test-bundle-2"
                    //  sendSingleEntry(jsonParser, patientResource)
                    val bundleEntry = Bundle.BundleEntryComponent()
                    bundleEntry.resource = patientResource
                    bundleEntry.fullUrl = "Observation/${patientResource.idElement.idPart}"
                    bundleEntry.request = Bundle.BundleEntryRequestComponent()
                    bundleEntry.request.setMethod(Bundle.HTTPVerb.PUT)
                    bundleEntry.request.url =
                        "Observation/${patientResource.idElement.idPart}"
                    bundle.addEntry(bundleEntry)
                }
                sendBundleToServer(jsonParser, bundle, context)
            }
            isUploadLoading = false
        }
    }

    fun prepareQuestionnaireResponseBatches(nameQuery: String, context: Context) {
        val isSummary = nameQuery.contains("mpox")
        if (!hasMoreUpload || isUploadLoading) return
        isUploadLoading = true
        pageUpload++
        val jsonParser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()

        viewModelScope.launch(Dispatchers.IO) {
            val results = fhirEngine.search<QuestionnaireResponse> {
                count = pageSizeUpload
                from = (pageUpload - 1) * pageSizeUpload
            }

            if (results.isEmpty()) {
                hasMoreUpload = false
            } else {
                val bundle = Bundle()
                bundle.type = Bundle.BundleType.TRANSACTION

                results.forEach { patient ->
                    val patientResource = patient.resource//.copy() as QuestionnaireResponse
//                    patientResource.nameFirstRep.family = "test-bundle-2"
                    //  sendSingleEntry(jsonParser, patientResource)
                    val bundleEntry = Bundle.BundleEntryComponent()
                    bundleEntry.resource = patientResource
                    bundleEntry.fullUrl =
                        "QuestionnaireResponse/${patientResource.idElement.idPart}"
                    bundleEntry.request = Bundle.BundleEntryRequestComponent()
                    bundleEntry.request.setMethod(Bundle.HTTPVerb.PUT)
                    bundleEntry.request.url =
                        "QuestionnaireResponse/${patientResource.idElement.idPart}"
                    bundle.addEntry(bundleEntry)
                }
                sendBundleToServer(jsonParser, bundle, context)
            }
            isUploadLoading = false
        }
    }

    fun prepareMeasureReportBatches(nameQuery: String, context: Context) {
        val isSummary = nameQuery.contains("mpox")
        if (!hasMoreUpload || isUploadLoading) return
        isUploadLoading = true
        pageUpload++
        val jsonParser = FhirContext.forCached(FhirVersionEnum.R4).newJsonParser()

        viewModelScope.launch(Dispatchers.IO) {
            val results = fhirEngine.search<MeasureReport> {
                count = pageSizeUpload
                from = (pageUpload - 1) * pageSizeUpload
            }

            if (results.isEmpty()) {
                hasMoreUpload = false
            } else {
                val bundle = Bundle()
                bundle.type = Bundle.BundleType.TRANSACTION

                results.forEach { patient ->
                    val patientResource = patient.resource//.copy() as MeasureReport
//                    patientResource.nameFirstRep.family = "test-bundle-2"
                    //  sendSingleEntry(jsonParser, patientResource)
                    val bundleEntry = Bundle.BundleEntryComponent()
                    bundleEntry.resource = patientResource
                    bundleEntry.fullUrl = "MeasureReport/${patientResource.idElement.idPart}"
                    bundleEntry.request = Bundle.BundleEntryRequestComponent()
                    bundleEntry.request.setMethod(Bundle.HTTPVerb.PUT)
                    bundleEntry.request.url =
                        "MeasureReport/${patientResource.idElement.idPart}"
                    bundle.addEntry(bundleEntry)
                }
                sendBundleToServer(jsonParser, bundle, context)
            }
            isUploadLoading = false
        }
    }

    private fun sendBundleToServer(
        jsonParser: IParser,
        bundle: Bundle,
        context: Context
    ) {
        viewModelScope.launch {
            println("API Response:::: Preparing data")
            val payload = jsonParser.encodeResourceToString(bundle)
            val apiCall = RetrofitCallsAuthentication()
            val json = jsonParser.encodeResourceToString(bundle)
            val requestBody = json.toRequestBody("application/json".toMediaType())
            apiCall.sendBundleToServer(requestBody, context)
        }
    }

}