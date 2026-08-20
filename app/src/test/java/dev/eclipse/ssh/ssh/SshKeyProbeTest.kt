package dev.eclipse.ssh.ssh

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The Add Host form's key check.
 *
 * These are the verdicts that decide whether Save may be pressed, so every branch matters: accepting a
 * file the app cannot actually read produces a host that is configured for key auth and fails at
 * connect time, long after the file picker has closed.
 *
 * The encrypted fixture is a real `ssh-keygen -t ed25519` key with 4 KDF rounds — the lowest value
 * OpenSSH will emit — so the passphrase paths exercise the actual bcrypt-pbkdf code without making the
 * suite slow.
 */
class SshKeyProbeTest {

    @Test
    fun `unencrypted key reports its type`() {
        val probe = probeSshKey(fixture("plain_ed25519"), "id_ed25519", passphrase = null)
        assertThat(probe).isInstanceOf(SshKeyProbe.Ready::class.java)
        assertThat((probe as SshKeyProbe.Ready).type).isEqualTo("Ed25519")
    }

    @Test
    fun `encrypted key without a passphrase asks for one`() {
        val probe = probeSshKey(fixture("encrypted_ed25519"), "id_ed25519", passphrase = null)
        assertThat(probe).isEqualTo(SshKeyProbe.PassphraseRequired)
    }

    @Test
    fun `encrypted key with a blank passphrase asks for one rather than failing`() {
        // A blank field is mid-typing, not a wrong answer. Reporting "wrong passphrase" for an empty
        // field would put an error under a field the user has not filled in yet.
        val probe = probeSshKey(fixture("encrypted_ed25519"), "id_ed25519", passphrase = "   ")
        assertThat(probe).isEqualTo(SshKeyProbe.PassphraseRequired)
    }

    @Test
    fun `encrypted key with the right passphrase reports its type`() {
        val probe = probeSshKey(fixture("encrypted_ed25519"), "id_ed25519", FIXTURE_PASSPHRASE)
        assertThat(probe).isInstanceOf(SshKeyProbe.Ready::class.java)
        assertThat((probe as SshKeyProbe.Ready).type).isEqualTo("Ed25519")
    }

    @Test
    fun `encrypted key with the wrong passphrase is unreadable`() {
        val probe = probeSshKey(fixture("encrypted_ed25519"), "id_ed25519", "not the passphrase")
        assertThat(probe).isInstanceOf(SshKeyProbe.Unreadable::class.java)
    }

    @Test
    fun `a public key is rejected by name rather than by parse failure`() {
        val probe = probeSshKey(fixture("plain_ed25519.pub"), "id_ed25519.pub", passphrase = null)
        assertThat(probe).isInstanceOf(SshKeyProbe.Unreadable::class.java)
        assertThat((probe as SshKeyProbe.Unreadable).reason).contains("public key")
    }

    @Test
    fun `an empty file is unreadable`() {
        val probe = probeSshKey(ByteArray(0), "id_rsa", passphrase = null)
        assertThat(probe).isInstanceOf(SshKeyProbe.Unreadable::class.java)
        assertThat((probe as SshKeyProbe.Unreadable).reason).contains("empty")
    }

    @Test
    fun `arbitrary bytes are unreadable and never quoted back`() {
        // The reason is fixed text on purpose. Some parsers include a prefix of their input in the
        // exception message, and surfacing that would put key material into the UI and the logs.
        val junk = ByteArray(4096) { (it % 251).toByte() }
        val probe = probeSshKey(junk, "photo.jpg", "hunter2")
        assertThat(probe).isInstanceOf(SshKeyProbe.Unreadable::class.java)
        val reason = (probe as SshKeyProbe.Unreadable).reason
        assertThat(reason).isEqualTo("Wrong passphrase, or a key format this app cannot read")
    }

    @Test
    fun `an OpenSSH public key line is recognised whatever the algorithm`() {
        listOf("ssh-rsa AAAAB3Nz", "ssh-ed25519 AAAAC3Nz", "ecdsa-sha2-nistp256 AAAAE2Vj", "ssh-dss AAAAB3Nz")
            .forEach { line ->
                val probe = probeSshKey(line.toByteArray(), "key.pub", passphrase = null)
                assertThat(probe).isInstanceOf(SshKeyProbe.Unreadable::class.java)
                assertThat((probe as SshKeyProbe.Unreadable).reason).contains("public key")
            }
    }

    @Test
    fun `a PEM public key is recognised too`() {
        val pem = "-----BEGIN PUBLIC KEY-----\nMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE\n-----END PUBLIC KEY-----\n"
        val probe = probeSshKey(pem.toByteArray(), "key.pem", passphrase = null)
        assertThat((probe as SshKeyProbe.Unreadable).reason).contains("public key")
    }

    @Test
    fun `rsa and ecdsa key types are labelled with their size`() {
        val rsa = SshKeyLoader.load(SshKeyAlgorithm.RSA_2048.generate().privatePem.toByteArray(), "rsa")
        val ecdsa = SshKeyLoader.load(SshKeyAlgorithm.ECDSA_P256.generate().privatePem.toByteArray(), "ec")
        assertThat(sshKeyTypeLabel(rsa)).isEqualTo("RSA 2048")
        assertThat(sshKeyTypeLabel(ecdsa)).isEqualTo("ECDSA 256")
    }

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("keys/$name")) { "Missing fixture keys/$name" }
            .use { it.readBytes() }

    private companion object {
        const val FIXTURE_PASSPHRASE = "correct horse"
    }
}
