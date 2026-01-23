package com.icl.surveillance.viewmodels.factories

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.icl.surveillance.viewmodels.EditSupervisorChecklistViewModel

class EditSupervisorChecklistViewModelFactory(
    private val application: Application,
    private val questionnaireId: String,
    private val questionnaire: String
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(EditSupervisorChecklistViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return EditSupervisorChecklistViewModel(
                application,
                questionnaireId,
                questionnaire
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
