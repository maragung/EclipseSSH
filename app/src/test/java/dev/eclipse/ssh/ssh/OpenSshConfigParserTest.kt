package dev.eclipse.ssh.ssh

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.ProxyType
import org.junit.Test

class OpenSshConfigParserTest {
    @Test
    fun `parses multiple host blocks`() {
        val config = """
            Host web
                HostName web.example.com
                User deploy
                Port 2222

            Host db
                HostName 10.0.0.5
                User admin
        """.trimIndent()

        val hosts = OpenSshConfigParser.parse(config)

        assertThat(hosts).hasSize(2)
        assertThat(hosts[0].name).isEqualTo("web")
        assertThat(hosts[0].host).isEqualTo("web.example.com")
        assertThat(hosts[0].username).isEqualTo("deploy")
        assertThat(hosts[0].port).isEqualTo(2222)
        assertThat(hosts[1].host).isEqualTo("10.0.0.5")
        assertThat(hosts[1].port).isEqualTo(22)
    }

    @Test
    fun `skips wildcard patterns and comments`() {
        val config = """
            # production wildcard
            Host *.example.com
                User root
            Host web
                HostName web.example.com
        """.trimIndent()

        val hosts = OpenSshConfigParser.parse(config)

        assertThat(hosts).hasSize(1)
        assertThat(hosts.single().name).isEqualTo("web")
    }

    @Test
    fun `maps proxyjump and hostname port shorthand`() {
        val config = """
            Host jump
                HostName bastion:2200
                User ops
                ProxyJump gateway@proxy.corp:22
        """.trimIndent()

        val host = OpenSshConfigParser.parse(config).single()

        assertThat(host.host).isEqualTo("bastion")
        assertThat(host.port).isEqualTo(2200)
        assertThat(host.proxyType).isEqualTo(ProxyType.PROXY_JUMP)
        assertThat(host.proxyJump).isEqualTo("gateway@proxy.corp:22")
    }

    /**
     * ConnectTimeout and ServerAliveInterval map onto the per-host connection options.
     *
     * An imported profile has to connect the way the user's own `ssh_config` says it does;
     * silently dropping these two meant a host tuned for a slow link arrived with the 15-second
     * default and looked broken.
     */
    @Test
    fun `maps connecttimeout and serveraliveinterval onto connection options`() {
        val config = """
            Host slow
                HostName slow.example.com
                User deploy
                ConnectTimeout 120
                ServerAliveInterval 45
        """.trimIndent()

        val host = OpenSshConfigParser.parse(config).single()

        assertThat(host.connectTimeoutSeconds).isEqualTo(120)
        assertThat(host.keepAliveSeconds).isEqualTo(45)
    }

    /** Directive names are case-insensitive in ssh_config, so the parser must not care either. */
    @Test
    fun `connection option keywords are case insensitive`() {
        val config = """
            Host mixed
                hostname mixed.example.com
                connecttimeout 30
                SERVERALIVEINTERVAL 60
        """.trimIndent()

        val host = OpenSshConfigParser.parse(config).single()

        assertThat(host.connectTimeoutSeconds).isEqualTo(30)
        assertThat(host.keepAliveSeconds).isEqualTo(60)
    }

    /**
     * A block with no ConnectTimeout keeps the app default, and a `ConnectTimeout 0` does too.
     *
     * Zero is not "give up immediately" in ssh_config — it means "use the system default" — so
     * honouring it literally would turn every such host into an instant timeout. Out-of-range
     * values fall back rather than clamp for the same reason: the file is stating an intent the
     * app cannot express, and the safe reading of an unexpressible intent is the default.
     */
    @Test
    fun `out of range connection options fall back to the defaults`() {
        val config = """
            Host zero
                HostName zero.example.com
                ConnectTimeout 0
                ServerAliveInterval 0

            Host huge
                HostName huge.example.com
                ConnectTimeout 99999
                ServerAliveInterval 99999

            Host junk
                HostName junk.example.com
                ConnectTimeout yes
                ServerAliveInterval sometimes

            Host bare
                HostName bare.example.com
        """.trimIndent()

        val hosts = OpenSshConfigParser.parse(config)

        assertThat(hosts).hasSize(4)
        assertThat(hosts.map { it.connectTimeoutSeconds })
            .containsExactly(15, 15, 15, 15)
        assertThat(hosts.map { it.keepAliveSeconds }).containsExactly(null, null, null, null)
    }

    /** Both ends of each range are legitimate and must survive the import untouched. */
    @Test
    fun `connection options at the range bounds are preserved`() {
        val config = """
            Host low
                HostName low.example.com
                ConnectTimeout 5
                ServerAliveInterval 5

            Host high
                HostName high.example.com
                ConnectTimeout 300
                ServerAliveInterval 600
        """.trimIndent()

        val hosts = OpenSshConfigParser.parse(config)

        assertThat(hosts.map { it.connectTimeoutSeconds }).containsExactly(5, 300).inOrder()
        assertThat(hosts.map { it.keepAliveSeconds }).containsExactly(5, 600).inOrder()
    }
}
