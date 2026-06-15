package com.utcam.smartdevices.auth.presentation

/**
 * Represents the UI state for all authentication screens (Login, Register, ForgotPassword).
 * Emitted by AuthViewModel via a StateFlow; each screen observes and renders accordingly.
 */
sealed interface AuthUiState {
    data object Idle : AuthUiState
    data object Loading : AuthUiState
    data class Success(val idToken: String?) : AuthUiState
    data class Error(val message: String) : AuthUiState
}
