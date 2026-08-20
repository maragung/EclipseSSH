package dev.eclipse.ssh.data.settings

import android.util.Base64
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.data.model.Snippet
import dev.eclipse.ssh.security.SecretCipher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Snippets are stored encrypted, and a command written by an older build is not lost to that.
 *
 * The reason this file exists: a snippet is a command a person typed, and the ones worth saving are
 * the ones that carry a secret. They were being written to the app's preferences file in the clear.
 *
 * The assertions that matter read the stored value itself rather than trusting that `encrypt()` is
 * called somewhere. They get at it through the cipher, which is the only code the repository hands
 * the stored string to: [PeekCipher.seen] is exactly what came back out of the store on a read, and
 * [PeekCipher.written] is exactly what went into it on a save. Reading the `.preferences_pb` file
 * directly would be one step closer to the disk, but `preferencesDataStore` fixes that file's path
 * at the first access anywhere in the classloader - which, for this store, is whichever Robolectric
 * test built the view model first - so the path this test can compute is not reliably the one being
 * written. The stored string is stored verbatim either way.
 *
 * That same caching is why the store outlives each method here: names are numbered, the order is
 * fixed, and every test scopes its assertions to the ids it wrote rather than to the whole list.
 */
@RunWith(RobolectricTestRunner::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@Config(sdk = [35])
class SnippetRepositoryTest {

    private val context get() = RuntimeEnvironment.getApplication()

    private fun repository(cipher: SecretCipher = Base64Cipher()) = SnippetRepository(context, cipher)

    /** Reads the store through a cipher that keeps what it was handed, and returns that raw value. */
    private suspend fun storedPayload(): String {
        val peek = PeekCipher()
        repository(peek).snippets.first()
        assertWithMessage("nothing was in the store to inspect").that(peek.seen).isNotEmpty()
        return peek.seen.joinToString(separator = "\n")
    }

    @Test
    fun `01 an empty store yields no snippets`() = runTest {
        assertThat(repository().snippets.first()).isEmpty()
    }

    @Test
    fun `02 a saved snippet round trips`() = runTest {
        val repo = repository()
        repo.save(Snippet(id = "s2", label = "Database", command = "mysql -u root -phunter2"))

        val stored = repo.snippets.first().single { it.id == "s2" }
        assertThat(stored.label).isEqualTo("Database")
        assertThat(stored.command).isEqualTo("mysql -u root -phunter2")
    }

    @Test
    fun `03 the command is not stored in the clear`() = runTest {
        val repo = repository()
        repo.save(Snippet(id = "s3", label = "Vaulttoken", command = "export TOKEN=super-secret-value"))
        // Proves the payload inspected below is the live one, not a leftover the read failed on.
        assertThat(repo.snippets.first().map(Snippet::id)).contains("s3")

        val payload = storedPayload()
        assertWithMessage("the command is readable in the stored value").that(payload)
            .doesNotContain("super-secret-value")
        // The label goes with it: people name a snippet after what it does.
        assertWithMessage("the label is readable in the stored value").that(payload)
            .doesNotContain("Vaulttoken")
    }

    @Test
    fun `04 the reserved separators cannot corrupt a row`() = runTest {
        val repo = repository()
        // The two control characters the storage format uses as separators. Neither can be typed, but
        // a label or command assembled from pasted terminal output can carry one, and before they were
        // stripped a snippet containing either came back with somebody else's command in it, or split
        // into two unparseable halves and silently deleted itself.
        val fieldSeparator = 0x1f.toChar()
        val rowSeparator = 0x1e.toChar()
        repo.save(
            Snippet(
                id = "s4",
                label = "we${fieldSeparator}ird${rowSeparator}label",
                command = "echo${fieldSeparator} still here",
            ),
        )

        val stored = repo.snippets.first().single { it.id == "s4" }
        assertThat(stored.label).isEqualTo("weirdlabel")
        assertThat(stored.command).isEqualTo("echo still here")
    }

    @Test
    fun `05 a plaintext payload from an older build is still read and then encrypted`() = runTest {
        // What the previous implementation wrote: the encoded rows, straight into the preference.
        // Modelled with a cipher that does nothing, which is exactly what the old code did, so the
        // value it records on the way in is byte-for-byte the value that landed in the store.
        val legacy = PeekCipher(encrypted = false)
        repository(legacy).save(Snippet(id = "s5", label = "Legacy", command = "tail -f /var/log/legacy"))
        assertWithMessage("the legacy write should be plaintext, or this test proves nothing")
            .that(legacy.written.last()).contains("tail -f /var/log/legacy")

        val upgraded = repository()
        assertThat(upgraded.snippets.first().single { it.id == "s5" }.command)
            .isEqualTo("tail -f /var/log/legacy")

        // Any later write re-encrypts the whole list, which is what takes the old plaintext out of
        // the store rather than leaving it there next to the new rows.
        upgraded.save(Snippet(id = "s5b", label = "New", command = "echo rewritten"))
        assertThat(storedPayload()).doesNotContain("tail -f /var/log/legacy")
        assertThat(upgraded.snippets.first().map(Snippet::id)).containsAtLeast("s5", "s5b")
    }

    @Test
    fun `06 a payload this key cannot decrypt yields no snippets rather than garbage`() = runTest {
        repository().save(Snippet(id = "s6", label = "Fine", command = "echo fine"))

        // A vault key that has gone missing - app data restored onto another device, or the key
        // invalidated. The store is unreadable, and the honest answer is that there is nothing in it;
        // the alternative is rows of Base64 in the snippet sheet.
        assertThat(repository(UnreadableCipher()).snippets.first()).isEmpty()
    }

    @Test
    fun `07 delete removes only that snippet`() = runTest {
        val repo = repository()
        repo.save(Snippet(id = "s7a", label = "Keep", command = "echo keep"))
        repo.save(Snippet(id = "s7b", label = "Drop", command = "echo drop"))

        repo.delete("s7b")

        val ids = repo.snippets.first().map(Snippet::id)
        assertThat(ids).contains("s7a")
        assertThat(ids).doesNotContain("s7b")
    }

    /**
     * Stands in for [dev.eclipse.ssh.security.SecureVault], whose key lives in `AndroidKeyStore`.
     *
     * Base64 with a trailing tag on purpose: it has the shape the real payload has - printable, and
     * with none of the control characters the storage format uses as separators - which is what the
     * plaintext-versus-ciphertext discriminator depends on.
     */
    private open class Base64Cipher : SecretCipher {
        override fun encrypt(value: String): String =
            Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) + ".tag"

        override fun decrypt(payload: String): String {
            require(payload.endsWith(".tag")) { "not one of ours" }
            return String(Base64.decode(payload.removeSuffix(".tag"), Base64.NO_WRAP), Charsets.UTF_8)
        }
    }

    /**
     * Keeps every value the repository hands the cipher, so a test can look at what is really stored.
     *
     * With [encrypted] off it is the identity, which is both what the old build did and the only way
     * to know that what went into the store is what the test wrote.
     */
    private class PeekCipher(private val encrypted: Boolean = true) : Base64Cipher() {
        val written = mutableListOf<String>()
        val seen = mutableListOf<String>()

        override fun encrypt(value: String): String =
            (if (encrypted) super.encrypt(value) else value).also { written += it }

        override fun decrypt(payload: String): String {
            seen += payload
            return super.decrypt(payload)
        }
    }

    /** A vault that cannot read what is there, the way a lost key behaves. */
    private class UnreadableCipher : SecretCipher {
        override fun encrypt(value: String): String = "unusable"
        override fun decrypt(payload: String): String = throw IllegalStateException("no key")
    }
}
