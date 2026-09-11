package dev.eclipse.ssh.data.local

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.CONNECT_TIMEOUT_RANGE
import dev.eclipse.ssh.data.model.DEFAULT_CONNECT_TIMEOUT_SECONDS
import dev.eclipse.ssh.data.model.HostKeyPolicy
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.INHERIT_RECONNECT_BACKOFF
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

    /**
     * Every advanced column survives the mapping, set to something other than its default.
     *
     * One profile with all twenty-one changed at once, rather than twenty-one assertions on one field: a
     * mapper written by hand fails by *dropping* a field, and a field that is dropped reads back as its
     * default. A test that only ever compares defaults to defaults cannot see that happen.
     */
    @Test
    fun `advanced per host options round trip through Room entity`() {
        val tuned = HostProfile(
            id = "h5",
            name = "Tuned",
            host = "tuned.example.com",
            username = "root",
            compression = true,
            keepAliveEnabled = false,
            serverAliveCountMax = 7,
            authTimeoutSeconds = 120,
            autoReconnect = false,
            maxReconnectAttempts = 11,
            reconnectBackoffSeconds = 20,
            usePty = false,
            terminalType = "vt100",
            terminalColumns = 132,
            terminalRows = 43,
            keyboardInteractiveAuth = false,
            legacyAlgorithms = true,
            ciphers = "aes256-gcm@openssh.com,aes128-ctr",
            kexAlgorithms = "curve25519-sha256,diffie-hellman-group14-sha256",
            macs = "hmac-sha2-256-etm@openssh.com",
            hostKeyAlgorithms = "ssh-ed25519,rsa-sha2-512",
            startupCommand = "tmux attach || tmux new",
            // The three text columns are the ones a mapper can mangle rather than drop: a newline in a
            // column joined with anything but a newline comes back as one line, and both of these are
            // parsed line by line at connect time.
            environment = "LANG=en_US.UTF-8\nTZ=Europe/Amsterdam",
            savedForwards = "L:8080:intranet.example:80\nD:1080",
            remoteDesktop = "V:10.0.1.5:5900 view-only",
            // The spelling as typed, not normalised: the mapping moves text, and every MAC spelling
            // parseMac accepts is one the column can hold.
            wakeOnLanMac = "4C-2E-81-1A-02-F7",
            agentForwarding = true,
            hostKeyPolicy = HostKeyPolicy.STRICT,
        )

        assertThat(tuned.toEntity().toDomain()).isEqualTo(tuned)
    }

    /**
     * The defaults reproduce what the engine did before any of this was configurable.
     *
     * This is the half that matters for an install upgrading into version 12 or 13: `MIGRATION_11_12`
     * and `MIGRATION_12_13` fill these columns with literals, and the literals have to agree with the
     * values here or an existing host changes behaviour on upgrade without anybody asking it to.
     */
    @Test
    fun `advanced options default to the behaviour that used to be hard-coded`() {
        val fresh = HostProfile(id = "h6", name = "Plain", host = "plain.example.com", username = "root")

        assertThat(fresh.compression).isFalse()
        assertThat(fresh.keepAliveEnabled).isTrue()
        assertThat(fresh.serverAliveCountMax).isEqualTo(3)
        assertThat(fresh.authTimeoutSeconds).isEqualTo(30)
        assertThat(fresh.autoReconnect).isTrue()
        assertThat(fresh.maxReconnectAttempts).isEqualTo(5)
        // The inherit sentinel, so the app-wide reconnect delay keeps meaning something.
        assertThat(fresh.reconnectBackoffSeconds).isEqualTo(INHERIT_RECONNECT_BACKOFF)
        assertThat(fresh.usePty).isTrue()
        assertThat(fresh.terminalType).isEqualTo("xterm-256color")
        // Zero is "match the screen", which is the only geometry the app ever asked for.
        assertThat(fresh.terminalColumns).isEqualTo(0)
        assertThat(fresh.terminalRows).isEqualTo(0)
        assertThat(fresh.keyboardInteractiveAuth).isTrue()
        // The grant is opt-in: a host that never spoke about forwarding must not acquire it.
        assertThat(fresh.agentForwarding).isFalse()
        assertThat(fresh.legacyAlgorithms).isNull()
        assertThat(fresh.hostKeyPolicy).isEqualTo(HostKeyPolicy.ASK)
        // Null, not empty. An empty algorithm list is a real instruction - propose nothing - and a
        // host that had never been asked about ciphers must keep negotiating the way it always did.
        assertThat(fresh.ciphers).isNull()
        assertThat(fresh.kexAlgorithms).isNull()
        assertThat(fresh.macs).isNull()
        assertThat(fresh.hostKeyAlgorithms).isNull()
        // Empty, not null: for these three "nothing" and "absent" are the same thing, so a second way
        // to say it would only give the read sites a null to forget about.
        assertThat(fresh.startupCommand).isEmpty()
        assertThat(fresh.environment).isEmpty()
        assertThat(fresh.savedForwards).isEmpty()
        assertThat(fresh.remoteDesktop).isEmpty()
        // Empty for the same reason: "no address to wake" is the only thing a null could add here,
        // and the kebab item treats blank as its not-configured case.
        assertThat(fresh.wakeOnLanMac).isEmpty()

        val entity = fresh.toEntity()
        assertThat(entity.hostKeyPolicy).isEqualTo("ASK")
        assertThat(entity.legacyAlgorithms).isNull()
        assertThat(entity.ciphers).isNull()
        assertThat(entity.startupCommand).isEmpty()
        assertThat(entity.savedForwards).isEmpty()
        assertThat(entity.wakeOnLanMac).isEmpty()
        assertThat(entity.toDomain()).isEqualTo(fresh)
    }

    /**
     * An unrecognised host-key policy in the database reads back as the one that asks.
     *
     * A TEXT column can hold anything - a hand-edited backup, a row written by a newer build that was
     * then downgraded - and the two failure modes here are not equal. Falling back to "ask" costs a
     * prompt; falling back to "trust on first use" would silently pin whatever answered.
     */
    @Test
    fun `an unknown host key policy falls back to asking`() {
        val entity = HostProfile(id = "h7", name = "P", host = "p.example.com", username = "root")
            .toEntity()
            .copy(hostKeyPolicy = "SOMETHING_ELSE")

        assertThat(entity.toDomain().hostKeyPolicy).isEqualTo(HostKeyPolicy.ASK)
    }

    /** No advanced value is redacted, but nothing here may leak a secret either. */
    @Test
    fun `the redacting toString reports advanced options and still hides secrets`() {
        val tuned = HostProfile(
            id = "h8",
            name = "Tuned",
            host = "tuned.example.com",
            username = "root",
            socksPassword = "socks-secret",
            compression = true,
            terminalType = "screen-256color",
            // Both of these are free text the user typed, and both have an obvious way to end up
            // holding a credential: a startup command that logs into something else, an environment
            // carrying an API token. So neither may be printed, however useful it would be.
            startupCommand = "vault login token=startup-secret",
            environment = "API_TOKEN=environment-secret",
            hostKeyPolicy = HostKeyPolicy.ACCEPT_NEW,
        )

        val rendered = tuned.toString()

        assertThat(rendered).doesNotContain("socks-secret")
        assertThat(rendered).contains("socksPassword=***")
        assertThat(rendered).doesNotContain("startup-secret")
        assertThat(rendered).doesNotContain("environment-secret")
        // Presence still reported, because "the startup command did not run" is a real bug report and
        // whether there was one at all is the first thing that narrows it.
        assertThat(rendered).contains("startupCommand=***")
        assertThat(rendered).contains("environment=***")
        assertThat(rendered).contains("compression=true")
        assertThat(rendered).contains("terminalType=screen-256color")
        assertThat(rendered).contains("hostKeyPolicy=ACCEPT_NEW")
    }

    /**
     * A host with nothing in those two fields says so, rather than claiming to be hiding something.
     *
     * The other half of the redaction: `***` for every state would make the trace useless for the one
     * question it is read for. A blank field is not a secret, and printing it as one would mean a user
     * reporting "my startup command never runs" could not be told that the host has none.
     */
    @Test
    fun `the redacting toString distinguishes an empty field from a hidden one`() {
        val rendered = HostProfile(id = "h9", name = "P", host = "p.example.com", username = "root").toString()

        assertThat(rendered).contains("startupCommand=\"\"")
        assertThat(rendered).contains("environment=\"\"")
        assertThat(rendered).contains("socksPassword=null")
    }
}
