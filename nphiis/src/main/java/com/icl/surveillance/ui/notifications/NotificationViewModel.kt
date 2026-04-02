package com.icl.surveillance.ui.notifications

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.icl.surveillance.models.Notification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class NotificationUiState {
    object Loading : NotificationUiState()
    data class Success(val notifications: List<Notification>) : NotificationUiState()
    object Empty : NotificationUiState()
    data class Error(val message: String) : NotificationUiState()
}

class NotificationViewModel : ViewModel() {
    private val repository = NotificationRepository()

    private val _uiState = MutableStateFlow<NotificationUiState>(NotificationUiState.Loading)
    val uiState: StateFlow<NotificationUiState> = _uiState

    fun fetchNotifications(context: Context) {
        viewModelScope.launch {
            _uiState.value = NotificationUiState.Loading
            try {
                val response = repository.getNotifications(context)
                val data = response?.notifications ?: emptyList()

                if (data.isEmpty()) {
                    _uiState.value = NotificationUiState.Empty
                } else {
                    _uiState.value = NotificationUiState.Success(data)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.value = NotificationUiState.Error(e.message ?: "An error occurred")
            }
        }
    }
}