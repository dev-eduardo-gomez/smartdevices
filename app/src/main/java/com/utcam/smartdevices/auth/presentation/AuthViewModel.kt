package com.utcam.smartdevices.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.utcam.smartdevices.auth.domain.AuthValidator
import com.utcam.smartdevices.auth.domain.IAuthRepository
import com.utcam.smartdevices.auth.domain.mapFirebaseAuthError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared ViewModel for all authentication screens (Login, Register, ForgotPassword).
 *
 * Construction:
 * - [repository] — the domain interface; injected as [FakeAuthRepository] in tests,
 *   as [AuthRepository] in production via [AuthViewModelFactory].
 * - [ioDispatcher] — dispatcher used for the actual repository suspension call (off-main work).
 *   Defaults to [Dispatchers.IO]. Injected as a [StandardTestDispatcher] in unit tests so
 *   the test can advance virtual time between Loading and Success/Error via
 *   [kotlinx.coroutines.test.advanceUntilIdle] (Design D8 / R2).
 *
 * Key design points:
 * - [_uiState] is mutated SYNCHRONOUSLY before launching the coroutine for Loading.
 *   This guarantees StateFlow emits Loading before the suspend call starts, making it
 *   observable by Turbine even when [ioDispatcher] is eager (UnconfinedTestDispatcher).
 * - The suspend repository call runs inside [withContext](ioDispatcher) so it can be
 *   dispatched to a test scheduler's queue when [ioDispatcher] is a [StandardTestDispatcher].
 *
 * State machine:
 *   Idle → Loading → Success(idToken) | Error(message)
 *
 * Callers MUST invoke [consumeState] after handling a terminal state (Success / Error)
 * to return to [AuthUiState.Idle] and prevent stale state on re-composition.
 *
 * Reset password success:
 * Emits [AuthUiState.Success](idToken = null) to represent "email sent". This is the
 * enumeration-safe design (D5): the ForgotPasswordScreen checks for any Success after
 * a reset call and displays the generic confirmation message regardless of the token value.
 *
 * JWT seam (AUTH-7):
 * After a successful signIn or signUp, [IAuthRepository.getIdToken] is called and its
 * result is surfaced in Success. If getIdToken fails, Success(idToken = null) is still
 * emitted — the auth succeeded; the token is a best-effort seam, not a gate.
 */
class AuthViewModel(
    private val repository: IAuthRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    private val _uiState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)

    /**
     * Observable authentication UI state. Collect with [kotlinx.coroutines.flow.collectAsState]
     * in Compose or with [app.cash.turbine.test] in unit tests.
     */
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    /**
     * True when there is a currently authenticated Firebase user.
     * Used by [AuthNavHost] to compute the start destination without triggering a Firebase call.
     */
    val isLoggedIn: Boolean
        get() = repository.currentUser != null

    // ── Public actions ────────────────────────────────────────────────────────

    /**
     * Attempts to sign in with [email] and [password].
     *
     * Validation runs synchronously BEFORE any repo call (AUTH-3). Invalid inputs emit
     * [AuthUiState.Error] and return immediately without touching Firebase.
     *
     * Loading is emitted synchronously before the coroutine is launched so Turbine
     * (and the Compose UI) always observes Loading as a distinct intermediate state.
     *
     * On success, retrieves the Firebase ID token (JWT seam, AUTH-7) and emits
     * [AuthUiState.Success](idToken). A token fetch failure still emits Success(null).
     */
    fun signIn(email: String, password: String) {
        if (!validateEmailAndPassword(email, password)) return
        _uiState.value = AuthUiState.Loading
        viewModelScope.launch {
            val result = withContext(ioDispatcher) { repository.signIn(email, password) }
            result
                .onSuccess { _uiState.value = AuthUiState.Success(fetchToken()) }
                .onFailure { _uiState.value = AuthUiState.Error(mapFirebaseAuthError(it)) }
        }
    }

    /**
     * Attempts to create a new Firebase account with [email] and [password].
     *
     * Same validation, loading, and JWT-seam behaviour as [signIn].
     * [IAuthRepository.signUp] also calls sendEmailVerification() best-effort
     * (fire-and-forget; does not affect the emitted state).
     */
    fun signUp(email: String, password: String) {
        if (!validateEmailAndPassword(email, password)) return
        _uiState.value = AuthUiState.Loading
        viewModelScope.launch {
            val result = withContext(ioDispatcher) { repository.signUp(email, password) }
            result
                .onSuccess { _uiState.value = AuthUiState.Success(fetchToken()) }
                .onFailure { _uiState.value = AuthUiState.Error(mapFirebaseAuthError(it)) }
        }
    }

    /**
     * Sends a password reset email to [email].
     *
     * Validates the email format first (no Firebase call for malformed emails).
     * On success emits Success(null) — the enumeration-safe generic outcome (AUTH-6, D5).
     * On a network or other hard error, emits Error with the mapped message.
     */
    fun resetPassword(email: String) {
        if (!AuthValidator.isValidEmail(email)) {
            _uiState.value = AuthUiState.Error("Please enter a valid email address.")
            return
        }
        _uiState.value = AuthUiState.Loading
        viewModelScope.launch {
            val result = withContext(ioDispatcher) { repository.sendPasswordReset(email) }
            result
                .onSuccess { _uiState.value = AuthUiState.Success(idToken = null) }
                .onFailure { _uiState.value = AuthUiState.Error(mapFirebaseAuthError(it)) }
        }
    }

    /**
     * Signs the current user out and resets [uiState] to [AuthUiState.Idle].
     *
     * This is synchronous — [IAuthRepository.signOut] does not suspend. The caller
     * (HomeScreen) is responsible for navigating back to LoginScreen after this call.
     */
    fun signOut() {
        repository.signOut()
        _uiState.value = AuthUiState.Idle
    }

    /**
     * Resets [uiState] back to [AuthUiState.Idle].
     *
     * Call this after the UI has processed a terminal state (Success or Error) to prevent
     * stale state from triggering repeated side-effects on re-composition.
     */
    fun consumeState() {
        _uiState.value = AuthUiState.Idle
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Validates email + password synchronously. Emits Error and returns false on failure.
     */
    private fun validateEmailAndPassword(email: String, password: String): Boolean {
        if (!AuthValidator.isValidEmail(email)) {
            _uiState.value = AuthUiState.Error("Please enter a valid email address.")
            return false
        }
        if (!AuthValidator.isValidPassword(password)) {
            _uiState.value = AuthUiState.Error("Password must be at least 8 characters.")
            return false
        }
        return true
    }

    /**
     * Fetches the Firebase ID token as a best-effort JWT seam (AUTH-7).
     * Must be called from within a [withContext] block using [ioDispatcher].
     * Returns null if the fetch fails — auth already succeeded at this point.
     */
    private suspend fun fetchToken(): String? =
        withContext(ioDispatcher) {
            repository.getIdToken(forceRefresh = false).getOrNull()
        }
}
