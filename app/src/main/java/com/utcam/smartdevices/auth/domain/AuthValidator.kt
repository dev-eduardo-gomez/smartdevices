package com.utcam.smartdevices.auth.domain

/**
 * Pure Kotlin validation logic for authentication inputs.
 *
 * Deliberately Android-free: uses a plain JVM regex instead of
 * android.util.Patterns.EMAIL_ADDRESS so this object is testable with
 * standard JVM unit tests (no Robolectric required).
 */
object AuthValidator {

    /**
     * Matches: one or more non-whitespace/non-@ chars, then @, then one or more
     * non-whitespace/non-@ chars, then a dot, then one or more non-whitespace/non-@ chars.
     * Compiled once at class-load time.
     */
    private val EMAIL_REGEX = Regex("""^[^@\s]+@[^@\s]+\.[^@\s]+$""")

    /**
     * Returns true if [email] is non-empty and matches the basic email format.
     * Firebase is the authoritative validator; this is a pre-submission format gate only.
     */
    fun isValidEmail(email: String): Boolean = email.matches(EMAIL_REGEX)

    /**
     * Returns true if [password] has at least 8 characters.
     * Firebase enforces a minimum of 6; we choose 8 for stronger user guidance.
     */
    fun isValidPassword(password: String): Boolean = password.length >= 8
}
