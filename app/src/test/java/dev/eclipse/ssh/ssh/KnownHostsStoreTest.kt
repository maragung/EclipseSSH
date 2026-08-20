package dev.eclipse.ssh.ssh

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Trusting, revoking and clearing a host key all have to reach disk before the call returns.
 *
 * The writes used to be `apply()`, which is asynchronous with no guarantee it ever completes, and the
 * result was dropped. Both directions of that are bad, and in opposite ways:
 *
 *  - a lost `save` means the fingerprint the user just accepted is gone at the next launch, so they are
 *    asked to verify the same server again and learn to tap through the one prompt that matters;
 *  - a lost `remove` or `clear` means a key the user revoked comes back after a restart, and the app
 *    silently trusts a server the user decided not to trust.
 *
 * A second store over the same context is how each test checks the write actually landed: it reads the
 * preferences file from scratch, the way the next process would, so an in-memory cache cannot make a
 * dropped write look successful. Every mutator returns whether it was stored, and the callers report it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KnownHostsStoreTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** A store that has only ever read the file, standing in for the app's next launch. */
    private fun nextLaunch() = KnownHostsStore(context)

    @Test
    fun `a trusted fingerprint is on disk before save returns`() {
        assertThat(nextLaunch().save("example.com", 22, "SHA256:aaa")).isTrue()

        assertThat(nextLaunch().get("example.com", 22)).isEqualTo("SHA256:aaa")
    }

    @Test
    fun `a revoked host stays revoked across a restart`() {
        val store = nextLaunch()
        store.save("example.com", 22, "SHA256:aaa")

        assertThat(store.remove("example.com", 22)).isTrue()

        assertWithMessage("a revoked key came back").that(nextLaunch().get("example.com", 22)).isNull()
    }

    @Test
    fun `clearing every key survives a restart`() {
        val store = nextLaunch()
        store.save("one.example.com", 22, "SHA256:aaa")
        store.save("two.example.com", 2222, "SHA256:bbb")

        assertThat(store.clear()).isTrue()

        assertThat(nextLaunch().all()).isEmpty()
    }

    @Test
    fun `an imported known-hosts file is on disk before the import returns`() {
        assertThat(
            nextLaunch().putAll(
                mapOf("one.example.com:22" to "SHA256:aaa", "two.example.com:2222" to "SHA256:bbb"),
            ),
        ).isTrue()

        assertThat(nextLaunch().all())
            .containsExactly("one.example.com:22", "SHA256:aaa", "two.example.com:2222", "SHA256:bbb")
    }

    @Test
    fun `the same host on another port is a different key`() {
        val store = nextLaunch()
        store.save("example.com", 22, "SHA256:aaa")
        store.save("example.com", 2222, "SHA256:bbb")

        assertThat(store.get("example.com", 22)).isEqualTo("SHA256:aaa")
        assertThat(store.get("example.com", 2222)).isEqualTo("SHA256:bbb")
        // Revoking one must not revoke the other: a jump host and a container often share a name.
        store.remove("example.com", 2222)
        assertThat(nextLaunch().get("example.com", 22)).isEqualTo("SHA256:aaa")
        assertThat(nextLaunch().get("example.com", 2222)).isNull()
    }

    @Test
    fun `a host that was never trusted has no key`() {
        assertThat(nextLaunch().get("never.example.com", 22)).isNull()
    }
}
