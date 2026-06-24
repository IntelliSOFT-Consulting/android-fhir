package com.icl.surveillance.adapters

import com.icl.surveillance.ui.patients.PatientListViewModel

interface CaseListDataAdapter {
    fun setData(list: List<PatientListViewModel.PatientItem>)
}
