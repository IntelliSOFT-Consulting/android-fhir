package com.icl.surveillance.location

import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.google.android.fhir.datacapture.extensions.getRequiredOrOptionalText
import com.google.android.fhir.datacapture.extensions.getValidationErrorMessage
import com.google.android.fhir.datacapture.extensions.localizedFlyoverSpanned
import com.google.android.fhir.datacapture.extensions.tryUnwrapContext
import com.google.android.fhir.datacapture.validation.ValidationResult
import com.google.android.fhir.datacapture.views.HeaderView
import com.google.android.fhir.datacapture.views.QuestionnaireViewItem
import com.google.android.fhir.datacapture.views.factories.QuestionnaireItemAndroidViewHolderDelegate
import com.google.android.fhir.datacapture.views.factories.QuestionnaireItemAndroidViewHolderFactory
import com.google.android.fhir.datacapture.views.factories.QuestionnaireItemViewHolder
import com.google.android.fhir.datacapture.views.factories.QuestionnaireItemViewHolderDelegate
import com.google.android.fhir.datacapture.views.factories.QuestionnaireItemViewHolderFactory
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.icl.surveillance.R
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.DecimalType
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.StringType


object LocationGpsCoordinateViewHolderFactory :
    QuestionnaireItemAndroidViewHolderFactory(
        R.layout.location_gps_coordinate_view,
    ) {
    override fun getQuestionnaireItemViewHolderDelegate() =
        object : QuestionnaireItemAndroidViewHolderDelegate {
            override lateinit var questionnaireViewItem: QuestionnaireViewItem

            private lateinit var header: HeaderView
            protected lateinit var textInputLayout: TextInputLayout
            private lateinit var autoCompleteTextView: MaterialAutoCompleteTextView

            override fun init(itemView: View) {
                header = itemView.findViewById(R.id.header)
                textInputLayout = itemView.findViewById(R.id.text_input_layout)
                autoCompleteTextView = itemView.findViewById(R.id.autoCompleteTextView)
            }

            override fun bind(questionnaireViewItem: QuestionnaireViewItem) {
                header.bind(questionnaireViewItem)

                header.context.tryUnwrapContext()?.apply {
                    val gpsCoordinateExtensionValue =
                        questionnaireViewItem.questionnaireItem
                            .getExtensionByUrl(PRIMARY_GPS_COORDINATE_EXTENSION_URL)
                            ?.value as? StringType
                            ?: questionnaireViewItem.questionnaireItem
                                .getExtensionByUrl(GPS_COORDINATE_EXTENSION_URL)
                                .value as StringType
                    when (gpsCoordinateExtensionValue.valueAsString) {
                        GPS_COORDINATE_EXTENSION_VALUE_LATITUDE -> {
                            supportFragmentManager.setFragmentResultListener(
                                CurrentLocationDialogFragment.LATITUDE_REQUEST_RESULT_KEY,
                                this,
                            ) { _, bundleResult ->
                                val latitude =
                                    bundleResult.getDouble(CurrentLocationDialogFragment.LATITUDE_REQUEST_RESULT_KEY)
                                lifecycleScope.launch {
                                    questionnaireViewItem.setAnswer(
                                        QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent()
                                            .apply {
                                                value = DecimalType(latitude)
                                            },
                                    )
                                }
                            }
                        }

                        GPS_COORDINATE_EXTENSION_VALUE_LONGITUDE -> {
                            supportFragmentManager.setFragmentResultListener(
                                CurrentLocationDialogFragment.LONGITUDE_REQUEST_RESULT_KEY,
                                this,
                            ) { _, bundleResult ->
                                val longitude =
                                    bundleResult.getDouble(CurrentLocationDialogFragment.LONGITUDE_REQUEST_RESULT_KEY)
                                lifecycleScope.launch {
                                    questionnaireViewItem.setAnswer(
                                        QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent()
                                            .apply {
                                                value = DecimalType(longitude)
                                            },
                                    )
                                }
                            }
                        }

                        GPS_COORDINATE_EXTENSION_VALUE_ALTITUDE -> {
                            supportFragmentManager.setFragmentResultListener(
                                CurrentLocationDialogFragment.ALTITUDE_REQUEST_RESULT_KEY,
                                this,
                            ) { _, bundleResult ->
                                val altitude =
                                    bundleResult.getDouble(CurrentLocationDialogFragment.ALTITUDE_REQUEST_RESULT_KEY)
                                lifecycleScope.launch {
                                    questionnaireViewItem.setAnswer(
                                        QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent()
                                            .apply {
                                                value = DecimalType(altitude)
                                            },
                                    )
                                }
                            }
                        }
                    }
                }

                with(textInputLayout) {
                    hint = questionnaireViewItem.enabledDisplayItems.localizedFlyoverSpanned
                    helperText = getRequiredOrOptionalText(questionnaireViewItem, context)
                }

                displayValidationResult(questionnaireViewItem.validationResult)

                val questionnaireItemViewItemDecimalAnswer =
                    questionnaireViewItem.answers.singleOrNull()?.valueDecimalType?.value?.toString()

                if (questionnaireItemViewItemDecimalAnswer.isNullOrEmpty()) {
                    autoCompleteTextView.setText("")
                } else if (
                    questionnaireItemViewItemDecimalAnswer.toDoubleOrNull() !=
                    autoCompleteTextView.text.toString().toDoubleOrNull()
                ) {
                    autoCompleteTextView.setText(questionnaireItemViewItemDecimalAnswer)
                }
            }

            private fun displayValidationResult(validationResult: ValidationResult) {
                textInputLayout.error =
                    getValidationErrorMessage(
                        textInputLayout.context,
                        questionnaireViewItem,
                        validationResult,
                    )
            }

            override fun setReadOnly(isReadOnly: Boolean) {
                // No user input
            }
        }

    fun matcher(questionnaireItem: Questionnaire.QuestionnaireItemComponent): Boolean {
        return questionnaireItem.hasExtension(PRIMARY_GPS_COORDINATE_EXTENSION_URL) ||
                questionnaireItem.hasExtension(GPS_COORDINATE_EXTENSION_URL)
    }

    const val PRIMARY_GPS_COORDINATE_EXTENSION_URL =
        "https://github.com/google/android-fhir/StructureDefinition/gps-coordinate"
    const val GPS_COORDINATE_EXTENSION_URL = "gps-coordinate"
    const val GPS_COORDINATE_EXTENSION_VALUE_LATITUDE = "latitude"
    const val GPS_COORDINATE_EXTENSION_VALUE_LONGITUDE = "longitude"
    const val GPS_COORDINATE_EXTENSION_VALUE_ALTITUDE = "altitude"
}
