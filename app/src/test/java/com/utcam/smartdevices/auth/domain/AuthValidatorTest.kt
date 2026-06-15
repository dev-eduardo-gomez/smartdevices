package com.utcam.smartdevices.auth.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthValidatorTest {

    // ── isValidEmail ──────────────────────────────────────────────────────────

    @Test
    fun `isValidEmail returns true for a standard valid email`() {
        assertTrue(AuthValidator.isValidEmail("user@example.com"))
    }

    @Test
    fun `isValidEmail returns true for subdomain email`() {
        assertTrue(AuthValidator.isValidEmail("user@mail.example.com"))
    }

    @Test
    fun `isValidEmail returns false when at-sign is missing`() {
        assertFalse(AuthValidator.isValidEmail("userexample.com"))
    }

    @Test
    fun `isValidEmail returns false for empty string`() {
        assertFalse(AuthValidator.isValidEmail(""))
    }

    @Test
    fun `isValidEmail returns false when domain part is missing after at-sign`() {
        // "bad@" — has @ but no domain
        assertFalse(AuthValidator.isValidEmail("bad@"))
    }

    @Test
    fun `isValidEmail returns false when email contains spaces`() {
        assertFalse(AuthValidator.isValidEmail("user @example.com"))
    }

    @Test
    fun `isValidEmail returns false when domain has no dot`() {
        // "user@nodot" — no TLD separator
        assertFalse(AuthValidator.isValidEmail("user@nodot"))
    }

    // ── isValidPassword ───────────────────────────────────────────────────────

    @Test
    fun `isValidPassword returns true for exactly 8 characters`() {
        assertTrue(AuthValidator.isValidPassword("abcdefgh"))
    }

    @Test
    fun `isValidPassword returns true for more than 8 characters`() {
        assertTrue(AuthValidator.isValidPassword("abcdefghi"))
    }

    @Test
    fun `isValidPassword returns false for 7 characters`() {
        assertFalse(AuthValidator.isValidPassword("abcdefg"))
    }

    @Test
    fun `isValidPassword returns false for empty string`() {
        assertFalse(AuthValidator.isValidPassword(""))
    }
}
