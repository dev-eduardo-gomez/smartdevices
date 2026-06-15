package com.utcam.smartdevices.auth.domain

import com.google.firebase.auth.FirebaseAuthException

private const val FALLBACK_MESSAGE = "Something went wrong. Please try again."

/**
 * Maps a [Throwable] to a user-facing English error message.
 *
 * Known [FirebaseAuthException] error codes are mapped to specific strings.
 * Any unrecognised code or non-Firebase exception falls through to [FALLBACK_MESSAGE].
 *
 * This is a pure top-level function (no Android deps) so it is directly
 * testable with standard JVM unit tests.
 */
fun mapFirebaseAuthError(exception: Throwable): String {
    val errorCode = (exception as? FirebaseAuthException)?.errorCode
        ?: return FALLBACK_MESSAGE

    return when (errorCode) {
        "ERROR_INVALID_EMAIL" ->
            "Please enter a valid email address."
        "ERROR_EMAIL_ALREADY_IN_USE" ->
            "An account with this email already exists."
        "ERROR_WEAK_PASSWORD" ->
            "Password must be at least 8 characters."
        "ERROR_USER_NOT_FOUND" ->
            "No account found for this email."
        "ERROR_WRONG_PASSWORD" ->
            "Incorrect password. Please try again."
        "ERROR_INVALID_CREDENTIAL" ->
            "Incorrect email or password. Please try again."
        "ERROR_USER_DISABLED" ->
            "This account has been disabled."
        "ERROR_NETWORK_REQUEST_FAILED" ->
            "Check your internet connection and try again."
        "ERROR_TOO_MANY_REQUESTS" ->
            "Too many attempts. Please try again later."
        else ->
            FALLBACK_MESSAGE
    }
}
