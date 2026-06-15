package com.utcam.smartdevices.auth.domain

import com.google.firebase.auth.FirebaseUser

/**
 * Domain-layer contract for authentication operations.
 * The data layer (AuthRepository) is the only implementation.
 * The presentation layer and tests depend on this interface, not the concrete class.
 */
interface IAuthRepository {
    val currentUser: FirebaseUser?

    suspend fun signUp(email: String, password: String): Result<FirebaseUser>

    suspend fun signIn(email: String, password: String): Result<FirebaseUser>

    suspend fun sendPasswordReset(email: String): Result<Unit>

    suspend fun getIdToken(forceRefresh: Boolean = false): Result<String>

    fun signOut()
}
