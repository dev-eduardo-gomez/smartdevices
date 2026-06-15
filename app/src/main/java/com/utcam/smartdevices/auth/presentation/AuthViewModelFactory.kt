package com.utcam.smartdevices.auth.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.utcam.smartdevices.auth.data.AuthRepository
import com.utcam.smartdevices.auth.domain.IAuthRepository

/**
 * Manual-DI [ViewModelProvider.Factory] for [AuthViewModel].
 *
 * Constructs an [AuthViewModel] with the supplied [repository]. In production,
 * [repository] defaults to [AuthRepository] (the only concrete implementation).
 * Tests and advanced callers can supply a custom [IAuthRepository] (e.g., FakeAuthRepository)
 * without this factory — they instantiate AuthViewModel directly with the desired repo.
 *
 * Usage in Compose (activity-scoped so all three auth screens share the same instance):
 * ```
 * val vm: AuthViewModel = viewModel(factory = AuthViewModelFactory())
 * ```
 *
 * Design ref: Design section 3 + 4 (Manual DI, Decision D4).
 */
class AuthViewModelFactory(
    private val repository: IAuthRepository = AuthRepository(),
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AuthViewModel::class.java)) {
            "AuthViewModelFactory can only create AuthViewModel instances, " +
                "got ${modelClass.simpleName}"
        }
        return AuthViewModel(repository) as T
    }
}
