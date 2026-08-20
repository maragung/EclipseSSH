package dev.eclipse.ssh.data.local

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.CONNECT_TIMEOUT_RANGE
import dev.eclipse.ssh.data.model.DEFAULT_CONNECT_TIMEOUT_SECONDS
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.KEEP_ALIVE_RANGE
import dev.eclipse.ssh.data.model.ProxyType
import org.junit.Test

class HostMappingTest {
    @Test
    fun `host profiles round trip through Room entity`() {
        val original = HostProfile(
            id = "host-1",
            name = "Edge",
            host = "edge.example.com",
            username = "deploy",
            port = 2222,
            authMethod = AuthMethod.SSH_KEY,
            group = "Work",
            tags = listOf("production", "priority"),
            isFavorite = true,
            lastConnectedAt = 1234L,
            fingerprint = "SHA256:abc",
            proxyType = ProxyType.PROXY_JUMP,
            proxyJump = "gateway@bastion.example.com:22",
            socksHost = "127.0.0.1",
            socksPort = 9050,
            socksUsername = "proxy-user",
            socksPassword = "proxy-secret",
            connectTimeoutSeconds = 45,
            keepAliveSeconds = 90,
            autoLoginSftp = false,
        )

        assertThat(original.toEntity().toDomain()).isEqualTo(original)
    }

    @Test
    fun `host without proxy settings defaults to direct connection`() {
        val direct = HostProfile(id = "h2", name = "Plain", host = "plain.example.com", username = "root")

        assertThat(direct.proxyType).isEqualTo(ProxyType.NONE)
        assertThat(direct.toEntity().toDomain()).isEqualTo(direct)
    }

    /**
     * A host created without touching Connection options keeps the timeout the engine used to
     * hard-code, and inherits the global keep-alive.
     *
     * The null is the load-bearing half: [dev.eclipse.ssh.ssh.SshConnectionManager] reads
     * `keepAliveSeconds ?: AppSettings.keepAliveSeconds`, so a non-null default would silently
     * override the global setting for every host ever created.
     */
    @Test
    fun `connection options default to the values the engine used to hard-code`() {
        val direct = HostProfile(id = "h3", name = "Plain", host = "plain.example.com", username = "root")

        assertThat(direct.connectTimeoutSeconds).isEqualTo(DEFAULT_CONNECT_TIMEOUT_SECONDS)
        assertThat(direct.connectTimeoutSeconds).isEqualTo(15)
        assertThat(direct.keepAliveSeconds).isNull()

        val entity = direct.toEntity()
        assertThat(entity.connectTimeoutSeconds).isEqualTo(15)
        assertThat(entity.keepAliveSeconds).isNull()
        assertThat(entity.toDomain().keepAliveSeconds).isNull()
    }

    /**
     * The per-host SFTP toggle survives the mapping in both positions, and defaults to on.
     *
     * The default is the load-bearing half. Connecting used to list the remote home directory
     * unconditionally, so on is what every existing profile already does; a false default here would
     * turn the file browser off for the whole install the moment this column shipped.
     */
    @Test
    fun `the SFTP auto login toggle round trips and defaults to on`() {
        val fresh = HostProfile(id = "h4", name = "Plain", host = "plain.example.com", username = "root")
        assertThat(fresh.autoLoginSftp).isTrue()
        assertThat(HostProfile.DEFAULT_AUTO_LOGIN_SFTP).isTrue()
        assertThat(fresh.toEntity().autoLoginSftp).isTrue()

        for (enabled in listOf(true, false)) {
            val host = fresh.copy(id = "sftp-$enabled", autoLoginSftp = enabled)
            assertThat(host.toEntity().autoLoginSftp).isEqualTo(enabled)
            assertThat(host.toEntity().toDomain()).isEqualTo(host)
        }
    }

    /** The two new columns survive the mapping at the ends of their permitted ranges. */
    @Test
    fun `connection options round trip at both range bounds`() {
        for (timeout in listOf(CONNECT_TIMEOUT_RANGE.first, CONNECT_TIMEOUT_RANGE.last)) {
            for (keepAlive in listOf(null, KEEP_ALIVE_RANGE.first, KEEP_ALIVE_RANGE.last)) {
                val host = HostProfile(
                    id = "bounds-$timeout-$keepAlive",
                    name = "Bounds",
                    host = "b.example.com",
                    username = "root",
                    connectTimeoutSeconds = timeout,
                    keepAliveSeconds = keepAlive,
                )

                assertThat(host.toEntity().toDomain()).isEqualTo(host)
            }
        }
    }
}
