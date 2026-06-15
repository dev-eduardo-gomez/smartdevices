package com.utcam.smartdevices.auth.domain

import com.google.firebase.auth.FirebaseAuthException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.IOException

class AuthErrorTest {

    private fun mockFirebaseAuthException(errorCode: String): FirebaseAuthException {
        val ex = mock(FirebaseAuthException::class.java)
        `when`(ex.errorCode).thenReturn(errorCode)
        return ex
    }

    // ── Known error codes ─────────────────────────────────────────────────────

    @Test
    fun `maps ERROR_INVALID_EMAIL to friendly message`() {
        val ex = mockFirebaseAuthException("ERROR_INVALID_EMAIL")
        assertEquals(
            "Please enter a valid email address.",
            mapFirebaseAuthError(ex)
        )
    }

    @Test
    fun `maps ERROR_EMAIL_ALREADY_IN_USE to friendly message`() {
        val ex = mockFirebaseAuthException("ERROR_EMAIL_ALREADY_IN_USE")
        assertEquals(
            "An account with this email already exists.",
            mapFirebaseAuthError(ex)
        )
    }

    @Test
    fun `maps ERROR_WEAK_PASSWORD to friendly message`() {
        val ex = mockFirebaseAuthException("ERROR_WEAK_PASSWORD")
        assertEquals(
            "Password must be at least 8 characters.",
            mapFirebaseAuthError(ex)
        )
    }

    @Test
    fun `maps ERROR_USER_NOT_FOUND to friendly message`() {
        val ex = mockFirebaseAuthException("ERROR_USER_NOT_FOUND")
        assertEquals(
            "No account found for this email.",
            mapFirebaseAuthError(ex)
        )
    }

    @Test
    fun `maps ERROR_WRONG_PASSWORD to friendly message`() {
        val ex = mockFirebaseAuthException("ERROR_WRONG_PASSWORD")
        assertEquals(
            "Incorrect password. Please try again.",
            mapFirebaseAuthError(ex)
        )
    }

    @Test
    fun `maps ERROR_INVALID_CREDENTIAL to friendly message`() {
        val ex = mockFirebaseAuthException("ERROR_INVALID_CREDENTIAL")
        assertEquals(
            "Incorrect email or password. Please try again.",
            mapFirebaseAuthError(ex)
        )
    }

    @Test
    fun `maps ERROR_USER_DISABLED to friendly message`() {
        val ex = mockFirebaseAuthException("ERROR_USER_DISABLED")
        assertEquals(
            "This account has been disabled.",
            mapFirebaseAuthError(ex)
        )
    }

    @Test
    fun `maps ERROR_NETWORK_REQUEST_FAILED to friendly message`() {
        val ex = mockFirebaseAuthException("ERROR_NETWORK_REQUEST_FAILED")
        assertEquals(
            "Check your internet connection and try again.",
            mapFirebaseAuthError(ex)
        )
    }

    @Test
    fun `maps ERROR_TOO_MANY_REQUESTS to friendly message`() {
        val ex = mockFirebaseAuthException("ERROR_TOO_MANY_REQUESTS")
        assertEquals(
            "Too many attempts. Please try again later.",
            mapFirebaseAuthError(ex)
        )
    }

    // ── Fallback paths ────────────────────────────────────────────────────────

    @Test
    fun `maps unknown FirebaseAuthException error code to generic fallback`() {
        val ex = mockFirebaseAuthException("ERROR_CUSTOM_UNKNOWN")
        assertEquals(
            "Something went wrong. Please try again.",
            mapFirebaseAuthError(ex)
        )
    }

    @Test
    fun `maps generic IOException to generic fallback`() {
        val ex = IOException("network failure")
        assertEquals(
            "Something went wrong. Please try again.",
            mapFirebaseAuthError(ex)
        )
    }

    @Test
    fun `maps generic RuntimeException to generic fallback`() {
        val ex = RuntimeException("unexpected")
        assertEquals(
            "Something went wrong. Please try again.",
            mapFirebaseAuthError(ex)
        )
    }
}
