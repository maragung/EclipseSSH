package dev.eclipse.ssh.ssh

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Collections

/**
 * The two ways the proxy connectors reach into Apache MINA internals, and the degradation each one
 * owes when the internal it depends on is no longer shaped the way MINA 2.19 shapes it.
 *
 * Both are reflection- and cast-hardening rather than wire behaviour, so they are exercised here as
 * plain functions rather than through a live handshake: the point of each fix is precisely what
 * happens on the path a real MINA never takes, which a real MINA cannot be made to take. The happy
 * path is asserted too, so the hardening is not bought at the cost of the behaviour that has to keep
 * working on the version the app actually ships against.
 */
class ProxyConnectorInternalsTest {

    // -------------------------------------------------------------------------
    // Reflection into Nio2ServiceFactory's private fields (see nio2Internals).
    // -------------------------------------------------------------------------

    private class Holder {
        @Suppress("unused")
        private val present: String = "value"
    }

    @Test
    fun `a declared private field is read by reflection`() {
        // The behaviour createConnector relies on for the group/resuming lookup to keep working on the
        // MINA the app ships with: the field is found and its value handed back.
        assertThat(reflectPrivateField(Holder::class.java, Holder(), "present")).isEqualTo("value")
    }

    @Test
    fun `a missing field degrades to null instead of throwing`() {
        // Without the guard this is a NoSuchFieldException raised from inside connector creation on a
        // MINA I/O thread — exactly what a future MINA that renamed the field would produce. nio2Internals
        // turns this null into ProxyAwareClient.createConnector's fall back to the default connector, so a
        // renamed field costs a proxy bypass, not a crash with nothing to catch it.
        assertThat(reflectPrivateField(Holder::class.java, Holder(), "does_not_exist")).isNull()
    }

    // -------------------------------------------------------------------------
    // Writing MINA's private session map (see putIfMutable).
    // -------------------------------------------------------------------------

    @Test
    fun `a writable session map takes the registration`() {
        // The path MINA 2.19 always takes: the map is a real ConcurrentHashMap, so the session is stored
        // and connect() proceeds exactly as the blind cast used to let it.
        val sessions = hashMapOf(1L to "existing")

        assertThat(putIfMutable(sessions, 2L, "registered")).isTrue()
        assertThat(sessions).containsEntry(2L, "registered")
    }

    @Test
    fun `an unwritable session map is reported rather than throwing`() {
        // A stand-in for the one thing the old cast could not survive: a MINA that handed back an
        // unmodifiable view. The cast itself still succeeds — a Kotlin Map and MutableMap are one JVM
        // type — so only the write reveals it, as an UnsupportedOperationException that would otherwise
        // escape connector code with no connect future to carry it. putIfMutable returns false, which the
        // connectors turn into a connect future that fails with a readable message.
        val backing = hashMapOf(1L to "existing")
        val readOnly = Collections.unmodifiableMap(backing)

        assertThat(putIfMutable(readOnly, 2L, "registered")).isFalse()
        // And nothing was half-written on the way to reporting the failure.
        assertThat(backing).doesNotContainKey(2L)
    }
}
