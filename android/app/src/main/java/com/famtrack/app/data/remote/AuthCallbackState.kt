package com.famtrack.app.data.remote

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class GoogleCallbackError(
    val code: String?,
    val description: String?
)

object AuthCallbackState {

    private val _error = MutableStateFlow<GoogleCallbackError?>(null)
    val error: StateFlow<GoogleCallbackError?> = _error

    fun publish(code: String?, description: String?) {
        _error.value = GoogleCallbackError(code, description?.take(MAX_DESCRIPTION_LENGTH))
    }

    fun clear() {
        _error.value = null
    }

    private const val MAX_DESCRIPTION_LENGTH = 200
}