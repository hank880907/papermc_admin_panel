package org.rainbowhunter.adminpanel.core.auth

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Argon2Test {
    private val hasher = Argon2Hasher()

    @Test
    fun `verify accepts the original password`() {
        val hash = hasher.hash("correct horse battery staple")
        assertTrue(hasher.verify(hash, "correct horse battery staple"))
    }

    @Test
    fun `verify rejects a different password`() {
        val hash = hasher.hash("correct horse battery staple")
        assertFalse(hasher.verify(hash, "wrong password"))
    }

    @Test
    fun `hashing the same password twice produces different hashes`() {
        val a = hasher.hash("password123")
        val b = hasher.hash("password123")
        // Argon2 uses a random salt, so hashes differ even for identical inputs
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b)
        assertTrue(hasher.verify(a, "password123"))
        assertTrue(hasher.verify(b, "password123"))
    }
}
