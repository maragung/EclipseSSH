package dev.eclipse.ssh.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinHasherTest {
    @Test
    fun `hash and verify accept correct pin`() {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.hash("1234", salt)
        assertTrue(PinHasher.verify("1234", salt, hash))
    }

    @Test
    fun `verify rejects wrong pin`() {
        val salt = PinHasher.newSalt()
        val hash = PinHasher.hash("1234", salt)
        assertFalse(PinHasher.verify("9999", salt, hash))
    }

    @Test
    fun `salts are random and produce different hashes`() {
        val hash1 = PinHasher.hash("1234", PinHasher.newSalt())
        val hash2 = PinHasher.hash("1234", PinHasher.newSalt())
        assertTrue(hash1 != hash2)
    }
}
