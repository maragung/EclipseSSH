package dev.eclipse.ssh.ssh

import com.google.common.truth.Truth.assertThat
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.apache.sshd.client.session.ClientSession
import org.junit.Test

/**
 * The store's answer to a concurrent question: are the host-scoped queries safe to call while
 * another thread is emptying the maps?
 *
 * They have to be, because that is the shape of the real traffic: the service's restore pass and
 * the saved-forwards path ask "which of this host's sessions should I use?" on their own threads
 * while a tab being closed runs [SshSessionStore.forget] and a UI adoption runs
 * [SshSessionStore.rekey]. CI answered the question once with a crash -
 * `NoSuchElementException` out of `ConcurrentHashMap$KeyIterator.next` beneath
 * [SshSessionStore.sessionKeysForHost] - because the queries walked a *live* key-set view whose
 * entries were being removed mid-traversal. A `ConcurrentHashMap` iterator is only weakly
 * consistent: it may end a traversal early, and when the table is emptied between `hasNext` and
 * `next` it throws instead.
 *
 * The fix was to snapshot the keys through a retrying helper rather than to lock the maps, so this
 * test stresses exactly that contract: one thread churns install/rekey/forget across a few hundred
 * keys while the test thread asks every host-scoped question in a tight loop. The assertions are
 * deliberately weak - "does not throw" and "every answer is a key the churn could have installed" -
 * because the only thing a slightly-stale answer is allowed to be is *an* answer.
 */
class SshSessionStoreTest {

    @Test
    fun `host-scoped queries survive concurrent install rekey and forget churn`() {
        val store = SshSessionStore()
        // Every key the churn thread can ever file, so any query result can be checked against it.
        val churnableKeys = (0 until KEY_COUNT)
            .flatMap { i -> listOf("restore-$i", "tab-$i") }
            .toHashSet()
        val hosts = listOf("alpha", "beta")
        val stop = AtomicBoolean(false)
        val rounds = AtomicInteger()
        val churnFailure = AtomicReference<Throwable?>()

        val churner = Thread {
            try {
                while (!stop.get()) {
                    for (i in 0 until KEY_COUNT) {
                        val host = hosts[i % hosts.size]
                        store.install("restore-$i", stubSession(), host)
                        store.rekey("restore-$i", "tab-$i")
                        store.forget("tab-$i")
                    }
                    rounds.incrementAndGet()
                }
            } catch (t: Throwable) {
                churnFailure.compareAndSet(null, t)
            }
        }
        churner.start()
        try {
            // Bounded by wall clock rather than iteration count so the test costs the same on a
            // slow shared runner as on a fast one, and still fails - rather than hanging - if the
            // queries regress into a spin.
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(READ_BUDGET_MS)
            while (System.nanoTime() < deadline) {
                for (host in hosts) {
                    // Subset, not equality: the churner may have removed half of these between the
                    // snapshot and now, and that is an answer the contract allows.
                    val keys = store.sessionKeysForHost(host)
                    assertThat(churnableKeys).containsAtLeastElementsIn(keys)
                    assertThat(churnableKeys).containsAtLeastElementsIn(store.liveForHost(host))
                    store.primarySessionFor(host)?.let { assertThat(churnableKeys).contains(it) }
                }
                assertThat(churnableKeys).containsAtLeastElementsIn(store.liveKeysByHost().map { it.first })
                assertThat(hosts).containsAtLeastElementsIn(store.liveHostIds())
            }
        } finally {
            stop.set(true)
            churner.join(TimeUnit.SECONDS.toMillis(10))
        }

        // The churn must actually have happened, or the loop above proved nothing.
        assertThat(churner.isAlive).isFalse()
        assertThat(rounds.get()).isAtLeast(1)
        churnFailure.get()?.let { throw it }
    }

    /**
     * A [ClientSession] that answers the two liveness questions the store asks and nothing else.
     *
     * The store's contract is what is under test, not SSH: a real loopback session per key would
     * make a few hundred keys of churn cost a few hundred authentications and test the server
     * instead. The close path is deliberately inert - the store hands the real socket work to its
     * own release scope, and a stub must not turn that into an error it would report as a fault.
     */
    private fun stubSession(): ClientSession =
        Proxy.newProxyInstance(
            ClientSession::class.java.classLoader,
            arrayOf(ClientSession::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "isOpen" -> true
                "isAuthenticated" -> true
                "close" -> null
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "SshSessionStoreTest stub"
                else -> null
            }
        } as ClientSession

    private companion object {
        /** A few hundred keys, so a traversal spans enough table bins for a removal to land mid-iteration. */
        private const val KEY_COUNT = 300

        /** Enough reading to meet the churner mid-drain, short enough to respect the CI budget. */
        private const val READ_BUDGET_MS = 3_000L
    }
}
