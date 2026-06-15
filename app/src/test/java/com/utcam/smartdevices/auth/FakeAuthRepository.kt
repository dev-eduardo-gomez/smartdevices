package com.utcam.smartdevices.auth

import com.google.firebase.auth.FirebaseUser
import com.utcam.smartdevices.auth.domain.IAuthRepository
import org.mockito.Mockito.mock

/**
 * In-memory test double for [IAuthRepository].
 *
 * All result fields are programmable before each test. Call-count trackers
 * let tests assert whether a repo method was (or was not) invoked.
 *
 * [FirebaseUser] is a final class; tests that need a success sentinel use
 * [mockFirebaseUser], which is a Mockito mock. The ViewModel never reads
 * FirebaseUser fields (Decision D7), so default-returning mock stubs are safe.
 */
class FakeAuthRepository : IAuthRepository {

    // ── Sentinel ──────────────────────────────────────────────────────────────

    companion object {
        /** A Mockito mock of the final FirebaseUser class, used as a success sentinel. */
        fun mockFirebaseUser(): FirebaseUser = mock(FirebaseUser::class.java)
    }

    // ── Programmable results ──────────────────────────────────────────────────

    var signInResult: Result<FirebaseUser> =
        Result.failure(IllegalStateException("signInResult not configured"))

    var signUpResult: Result<FirebaseUser> =
        Result.failure(IllegalStateException("signUpResult not configured"))

    var resetResult: Result<Unit> =
        Result.failure(IllegalStateException("resetResult not configured"))

    var idTokenResult: Result<String> =
        Result.failure(IllegalStateException("idTokenResult not configured"))

    var currentUserValue: FirebaseUser? = null

    // ── Call-count trackers ───────────────────────────────────────────────────

    var signInCallCount: Int = 0
        private set

    var signUpCallCount: Int = 0
        private set

    var resetCallCount: Int = 0
        private set

    var signOutCallCount: Int = 0
        private set

    var getIdTokenCallCount: Int = 0
        private set

    // ── IAuthRepository implementation ────────────────────────────────────────

    override val currentUser: FirebaseUser?
        get() = currentUserValue

    override suspend fun signIn(email: String, password: String): Result<FirebaseUser> {
        signInCallCount++
        return signInResult
    }

    override suspend fun signUp(email: String, password: String): Result<FirebaseUser> {
        signUpCallCount++
        return signUpResult
    }

    override suspend fun sendPasswordReset(email: String): Result<Unit> {
        resetCallCount++
        return resetResult
    }

    override suspend fun getIdToken(forceRefresh: Boolean): Result<String> {
        getIdTokenCallCount++
        return idTokenResult
    }

    override fun signOut() {
        signOutCallCount++
    }

    // ── Convenience helpers ───────────────────────────────────────────────────

    /** Reset all counters and results to their default (failing) states. */
    fun reset() {
        signInCallCount = 0
        signUpCallCount = 0
        resetCallCount = 0
        signOutCallCount = 0
        getIdTokenCallCount = 0
        signInResult = Result.failure(IllegalStateException("signInResult not configured"))
        signUpResult = Result.failure(IllegalStateException("signUpResult not configured"))
        resetResult = Result.failure(IllegalStateException("resetResult not configured"))
        idTokenResult = Result.failure(IllegalStateException("idTokenResult not configured"))
        currentUserValue = null
    }
}
