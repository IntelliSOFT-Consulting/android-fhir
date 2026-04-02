package com.icl.surveillance.fhir

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class LocationDownloadViewModel : ViewModel() {
    private val _fetchComplete = MutableLiveData<Boolean>()
    val fetchComplete: LiveData<Boolean> = _fetchComplete

    fun setFetchingComplete(value: Boolean) {
        _fetchComplete.value = value
    }
}