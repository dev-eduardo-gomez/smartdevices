package com.utcam.smartdevices.auth.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.utcam.smartdevices.auth.domain.IAuthRepository
import kotlinx.coroutines.tasks.await

/**
 * Single boundary between the app and Firebase Authentication.
 * Everything above this layer (ViewModel, UI) stays Firebase-agnostic and testable.
 */
class AuthRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : IAuthRepository {

    override val currentUser: FirebaseUser?
        get() = auth.currentUser

    override suspend fun signUp(email: String, password: String): Result<FirebaseUser> = runCatching {
        val result = auth.createUserWithEmailAndPassword(email, password).await()
        val user = requireNotNull(result.user) { "Sign-up succeeded but returned no user" }
        // Fire-and-forget email verification; failure must never gate success (AUTH-4)
        runCatching { user.sendEmailVerification().await() }
        user
    }

    override suspend fun signIn(email: String, password: String): Result<FirebaseUser> = runCatching {
        val result = auth.signInWithEmailAndPassword(email, password).await()
        requireNotNull(result.user) { "Sign-in succeeded but returned no user" }
    }

    override suspend fun sendPasswordReset(email: String): Result<Unit> = runCatching {
        auth.sendPasswordResetEmail(email).await()
    }

    override suspend fun getIdToken(forceRefresh: Boolean): Result<String> = runCatching {
        val user = requireNotNull(auth.currentUser) { "No authenticated user" }
        user.getIdToken(forceRefresh).await().token ?: error("Firebase returned null token")
    }

    override fun signOut() = auth.signOut()
}
