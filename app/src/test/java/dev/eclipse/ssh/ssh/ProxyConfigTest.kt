package dev.eclipse.ssh.ssh

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.CONNECT_TIMEOUT_RANGE
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.PORT_RANGE
import dev.eclipse.ssh.data.model.ProxyType
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The proxy leg of a connection: the budget it is given, and what it is allowed to say about itself.
 *
 * Covers [socksProxyConfig] and [httpProxyConfig], which are the only two places production code
 * builds a proxy config — `SshConnectionManager.connect` calls them and nothing else constructs one
 * (the two test suites that do are exercising the connectors, not the wiring).
 */
class ProxyConfigTest {

    private fun profile(
        type: ProxyType = ProxyType.SOCKS5,
        host: String? = "proxy.internal",
        port: Int = 1080,
        username: String? = null,
        password: String? = null,
    ) = HostProfile(
        name = "behind a proxy",
        host = "10.0.0.5",
        username = "deploy",
        authMethod = AuthMethod.PASSWORD,
        proxyType = type,
        socksHost = host,
        socksPort = port,
        socksUsername = username,
        socksPassword = password,
    )

    /**
     * The regression test for a real defect: the per-host connect timeout was ignored on the proxy
     * leg.
     *
     * Both configs default `connectTimeoutMs` to 15 000, and `SshConnectionManager` built them with
     * named arguments that omitted the field — so a host given five minutes because it is reached
     * through a slow bastion still abandoned the bastion handshake after fifteen seconds, and the
     * timeout the user had set was unreachable on exactly the connections most likely to need it.
     * Nothing failed loudly; the connection just gave up early and blamed the proxy.
     *
     * The units are the other half of it. `HostProfile` stores seconds, the config takes
     * milliseconds, and both connectors feed the value straight to `Future.get(..., MILLISECONDS)`;
     * a missing ×1000 would have looked like an instant proxy failure.
     */
    @Test
    fun `the per host connect budget governs the proxy handshake too`() {
        // The ends of the range the form and the importer accept, so neither bound is special-cased.
        listOf(CONNECT_TIMEOUT_RANGE.first, 30, CONNECT_TIMEOUT_RANGE.last).forEach { seconds ->
            val budget = seconds.toLong()

            assertThat(socksProxyConfig(profile(), budget).connectTimeoutMs)
                .isEqualTo(seconds * 1_000L)
            assertThat(httpProxyConfig(profile(ProxyType.HTTP_CONNECT), budget).connectTimeoutMs)
                .isEqualTo(seconds * 1_000L)
        }
    }

    /**
     * And the number it must no longer silently be. Stated separately from the loop above because
     * this is the value the bug produced, and 15 seconds is also a plausible thing for a host to be
     * configured with — the check has to be that the config follows the host, not that it avoids one
     * particular number.
     */
    @Test
    fun `the old hard coded fifteen seconds is not what a host with a longer budget gets`() {
        val generous = CONNECT_TIMEOUT_RANGE.last.toLong()

        assertThat(socksProxyConfig(profile(), generous).connectTimeoutMs).isNotEqualTo(15_000L)
        assertThat(httpProxyConfig(profile(ProxyType.HTTP_CONNECT), generous).connectTimeoutMs)
            .isNotEqualTo(15_000L)
    }

    /**
     * Neither config may print its password.
     *
     * These objects are handed to Apache MINA SSHD as connection attributes, so the app does not
     * control where they are stringified next: sshd logs attribute maps and session dumps, and
     * `slf4j-simple` writes to `System.err`, which on Android is logcat. The generated `toString` of
     * a data class prints every field, so a proxy password would have been readable in the device log
     * of any build whose sshd logging was on — and in whatever crash reporter or OEM log collector
     * picked it up afterwards.
     */
    @Test
    fun `neither proxy config can print its password`() {
        val secret = "s0cks-Pa55phrase-sentinel"
        val socks = socksProxyConfig(profile(username = "proxyuser", password = secret), 30L)
        val http = httpProxyConfig(
            profile(ProxyType.HTTP_CONNECT, username = "proxyuser", password = secret),
            30L,
        )

        listOf(socks.toString(), http.toString()).forEach { rendered ->
            assertThat(rendered).doesNotContain(secret)
            // Not merely absent by luck of formatting: the field must be visibly redacted, so a
            // reader of a log knows a credential was in play rather than assuming there was none.
            assertThat(rendered).contains(SocksProxyConfig.REDACTED)
        }
        // The field itself is untouched — redaction is about rendering, not about withholding the
        // credential from the connector that has to send it.
        assertThat(socks.password).isEqualTo(secret)
        assertThat(http.password).isEqualTo(secret)
    }

    /**
     * Redaction has to leave a line that is still worth reading. A log that cannot say which proxy
     * was in use trades one debugging problem for another, so everything that is not the secret stays.
     */
    @Test
    fun `a redacted config still identifies the proxy and the budget`() {
        val rendered = socksProxyConfig(
            profile(host = "bastion.example.com", port = 8080, username = "proxyuser", password = "x"),
            45L,
        ).toString()

        assertThat(rendered).contains("bastion.example.com")
        assertThat(rendered).contains("8080")
        assertThat(rendered).contains("45000")
        assertThat(rendered).contains("proxyuser")
    }

    /**
     * An unauthenticated proxy is distinguishable from an authenticated one whose password is hidden.
     * Otherwise every log line would imply a credential and "is the password reaching the connector"
     * would be unanswerable from a log — the question that most often needs answering.
     */
    @Test
    fun `a proxy with no password says so rather than pretending to hide one`() {
        listOf(null, "").forEach { absent ->
            val rendered = socksProxyConfig(profile(password = absent), 30L).toString()

            assertThat(rendered).contains("password=none")
            assertThat(rendered).doesNotContain(SocksProxyConfig.REDACTED)
        }
    }

    /**
     * A proxy route with no host cannot be connected, and the message is the one the user is shown —
     * `connect` turns this into the error on the session card. Reachable in production despite the
     * Add Host validation, because a profile can also arrive from an imported backup or from an
     * older row that predates the field.
     */
    @Test
    fun `an incomplete proxy route is rejected with the message the user sees`() {
        listOf(null, "", "   ").forEach { missing ->
            assertThat(
                assertThrows(IllegalArgumentException::class.java) {
                    socksProxyConfig(profile(host = missing), 30L)
                }.message,
            ).isEqualTo("SOCKS5 proxy host and port are not configured")

            // Same shape, and the message names the right protocol rather than mentioning SOCKS to
            // someone who configured an HTTP proxy.
            assertThat(
                assertThrows(IllegalArgumentException::class.java) {
                    httpProxyConfig(profile(ProxyType.HTTP_CONNECT, host = missing), 30L)
                }.message,
            ).isEqualTo("HTTP CONNECT proxy host and port are not configured")
        }
    }

    @Test
    fun `a proxy port outside the addressable range is rejected`() {
        // 0 is the "any port" sentinel and would have the OS choose one; the rest cannot be dialled.
        listOf(PORT_RANGE.first - 1, PORT_RANGE.last + 1, -1, Int.MAX_VALUE).forEach { bad ->
            assertThrows(IllegalArgumentException::class.java) {
                socksProxyConfig(profile(port = bad), 30L)
            }
            assertThrows(IllegalArgumentException::class.java) {
                httpProxyConfig(profile(ProxyType.HTTP_CONNECT, port = bad), 30L)
            }
        }
        // And the boundaries themselves are accepted, so the guard is not off by one.
        listOf(PORT_RANGE.first, PORT_RANGE.last).forEach { edge ->
            assertThat(socksProxyConfig(profile(port = edge), 30L).port).isEqualTo(edge)
        }
    }

    /**
     * Surrounding whitespace in a pasted proxy address is stripped, and only for the host — trimming
     * a credential would silently change it.
     */
    @Test
    fun `a pasted proxy host is trimmed but credentials are left exactly as entered`() {
        val config = socksProxyConfig(
            profile(host = "  proxy.internal\n", username = " padded ", password = " secret "),
            30L,
        )

        assertThat(config.host).isEqualTo("proxy.internal")
        assertThat(config.username).isEqualTo(" padded ")
        assertThat(config.password).isEqualTo(" secret ")
    }

    /**
     * A username that is present but blank must not be offered as a credential. sshd would attempt
     * an authenticated handshake with an empty user, which a proxy that wants no authentication can
     * reject outright — turning a working route into a failure by way of an empty text field.
     */
    @Test
    fun `a blank proxy username is treated as no username at all`() {
        listOf(null, "", "   ").forEach { blank ->
            val socks = socksProxyConfig(profile(username = blank), 30L)
            val http = httpProxyConfig(profile(ProxyType.HTTP_CONNECT, username = blank), 30L)

            assertThat(socks.username).isNull()
            assertThat(http.username).isNull()
            assertThat(socks.hasCredentials).isFalse()
            assertThat(http.hasCredentials).isFalse()
        }
    }

    /**
     * A password with no username is still a credential. Some proxies are configured that way, and
     * `hasCredentials` decides whether the connector negotiates an authenticating method at all — so
     * treating this as anonymous would drop the password before it was ever sent.
     */
    @Test
    fun `a password without a username still counts as a credential`() {
        assertThat(socksProxyConfig(profile(password = "only-a-password"), 30L).hasCredentials).isTrue()
        assertThat(
            httpProxyConfig(profile(ProxyType.HTTP_CONNECT, password = "only-a-password"), 30L)
                .hasCredentials,
        ).isTrue()
    }
}
