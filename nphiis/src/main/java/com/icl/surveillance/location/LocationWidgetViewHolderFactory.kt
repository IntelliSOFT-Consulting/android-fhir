package com.icl.surveillance.location

import android.view.View
import android.view.ViewGroup
import com.google.android.fhir.datacapture.extensions.itemControlCode
import com.google.android.fhir.datacapture.extensions.tryUnwrapContext
import com.google.android.fhir.datacapture.views.GroupHeaderView
import com.google.android.fhir.datacapture.views.HeaderView
import com.google.android.fhir.datacapture.views.QuestionnaireViewItem
import com.google.android.fhir.datacapture.views.factories.QuestionnaireItemAndroidViewHolderDelegate
import com.google.android.fhir.datacapture.views.factories.QuestionnaireItemAndroidViewHolderFactory
import com.google.android.fhir.datacapture.views.factories.QuestionnaireItemViewHolder
import com.google.android.fhir.datacapture.views.factories.QuestionnaireItemViewHolderDelegate
import com.google.android.fhir.datacapture.views.factories.QuestionnaireItemViewHolderFactory
import com.google.android.material.button.MaterialButton
import com.icl.surveillance.R
import org.hl7.fhir.r4.model.Questionnaire

object LocationWidgetViewHolderFactory :
    QuestionnaireItemAndroidViewHolderFactory(R.layout.location_widget_view) {
    override fun getQuestionnaireItemViewHolderDelegate() =
        object : QuestionnaireItemAndroidViewHolderDelegate {
            private lateinit var headerView: HeaderView
            private lateinit var locationWidgetButton: MaterialButton

            override lateinit var questionnaireViewItem: QuestionnaireViewItem

            override fun init(itemView: View) {
                headerView = itemView.findViewById(R.id.header)
                locationWidgetButton = itemView.findViewById(R.id.gps_widget_button)
            }

            override fun bind(questionnaireViewItem: QuestionnaireViewItem) {
                headerView.bind(questionnaireViewItem)
                headerView.context.tryUnwrapContext()?.apply {
                    locationWidgetButton.setOnClickListener {
                        CurrentLocationDialogFragment()
                            .show(
                                supportFragmentManager,
                                CurrentLocationDialogFragment::class.java.simpleName
                            )
                    }
                }
            }

            override fun setReadOnly(isReadOnly: Boolean) {
                // No user input
            }
        }

    fun matcher(questionnaireItem: Questionnaire.QuestionnaireItemComponent): Boolean {
        return questionnaireItem.itemControlCode == LOCATION_WIDGET_UI_CONTROL_CODE
    }

    private const val LOCATION_WIDGET_UI_CONTROL_CODE = "location-widget"
}
