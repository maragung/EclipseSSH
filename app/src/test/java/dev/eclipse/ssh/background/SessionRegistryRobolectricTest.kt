package dev.eclipse.ssh.background

import android.content.Context
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.security.SecureVault
import dev.eclipse.ssh.security.StandInAndroidKeyStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The credential the reconnect ladder authenticates with survives the cancellation that starts it.
 *
 * The bug these tests pin down cost three releases. A connect attempt stores its credential here once
 * the session is up; when that session dies, the ladder's own `connect` cancels the attempt still
 * running - and the write was part of that attempt. On a device the write is the slower of the two by
 * far (read the file, encrypt, write a scratch copy, fsync, rename, all on a background thread), so a
 * session that died young lost it. The reconnect the cancellation existed to start then arrived with
 * nothing to offer and reported *"No more authentication methods available"*: the exact words a wrong
 * password produces, on a password that was right.
 *
 * A coroutine that cancels itself before asking for the write is a deterministic stand-in for losing
 * that race - it is the same condition, arrived at without depending on which thread wins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionRegistryRobolectricTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var registry: SessionRegistry

    @Before
    fun startEmpty() {
        // The vault's key lives in AndroidKeyStore, which the host JVM does not have.
        StandInAndroidKeyStore.install()
        registry = SessionRegistry(context, SecureVault())
        // DataStore hands out one instance per process, so what a previous test wrote is still there.
        runBlocking { registry.clear() }
    }

    @After
    fun forgetVaultKey() {
        StandInAndroidKeyStore.uninstall()
    }

    @Test
    fun `a credential asked for by an already cancelled attempt is still stored`() = runBlocking<Unit> {
        var reported: Throwable? = null
        val attempt = launch(start = CoroutineStart.UNDISPATCHED) {
            // What the reconnect ladder does to the attempt that is in the middle of this write.
            coroutineContext.job.cancel()
            // Not dropped: the caller still learns its attempt has been replaced, which is what stops
            // it going on to present a session. Where it learns is deliberately not asserted. The write
            // is dispatched to Dispatchers.IO (it encrypts, so it may not run on the UI thread - see
            // SessionRegistry.write), and resuming a cancelled coroutine after a real dispatch delivers
            // its cancellation there rather than at the next suspension point. Both orders satisfy the
            // guarantee; pinning one would be asserting which dispatcher the write happens to use.
            reported = runCatching {
                registry.register("host-cancelled", "hunter2")
                yield()
            }.exceptionOrNull()
        }
        attempt.join()

        assertThat(registry.credential("host-cancelled")).isEqualTo("hunter2")
        assertThat(registry.activeHostIds.first()).contains("host-cancelled")
        assertThat(reported).isInstanceOf(CancellationException::class.java)
    }

    @Test
    fun `a forget asked for by an already cancelled attempt still happens`() = runBlocking<Unit> {
        registry.register("host-forgotten", "hunter2")

        val attempt = launch(start = CoroutineStart.UNDISPATCHED) {
            coroutineContext.job.cancel()
            registry.unregister("host-forgotten")
        }
        attempt.join()

        // The half the user's side of the bargain depends on: a credential they asked the app to drop
        // is not left on disk because the coroutine that was dropping it went away.
        assertThat(registry.credential("host-forgotten")).isNull()
        assertThat(registry.activeHostIds.first()).doesNotContain("host-forgotten")
    }

    @Test
    fun `registering without a credential leaves the stored one alone`() = runBlocking<Unit> {
        registry.register("host-adopted", "hunter2")

        // Adopting a live session registers whatever the caller happens to hold, which for a session
        // the background service dialled is nothing at all. Clearing on that would forget a working
        // credential every time the app reused a session instead of dialling one.
        registry.register("host-adopted", password = null)

        assertThat(registry.credential("host-adopted")).isEqualTo("hunter2")
        assertThat(registry.activeHostIds.first()).contains("host-adopted")
    }
}
