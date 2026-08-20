package dev.eclipse.ssh

import androidx.core.net.toUri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Edge-case coverage for [parseSshDeepLink].
 *
 * The manifest exports the `ssh` and `sftp` schemes to BROWSABLE intents, so anything a web page
 * can put in a URI reaches this parser. These tests pin the two properties that matter: an
 * unacceptable link is rejected outright rather than turned into a half-populated profile, and an
 * accepted link never carries a credential or an out-of-range port into the app.
 *
 * Runs under Robolectric because android.net.Uri is a stubbed platform class on the host JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepLinkParsingTest {

    @Test
    fun `full ssh link populates host user and port`() {
        val profile = parseSshDeepLink("ssh://deploy@example.com:2222".toUri())

        assertThat(profile).isNotNull()
        assertThat(profile!!.host).isEqualTo("example.com")
        assertThat(profile.username).isEqualTo("deploy")
        assertThat(profile.port).isEqualTo(2222)
        assertThat(profile.name).isEqualTo("deploy@example.com")
    }

    @Test
    fun `sftp scheme is accepted as well as ssh`() {
        assertThat(parseSshDeepLink("sftp://deploy@example.com".toUri())).isNotNull()
    }

    @Test
    fun `missing port falls back to 22`() {
        assertThat(parseSshDeepLink("ssh://deploy@example.com".toUri())!!.port).isEqualTo(22)
    }

    @Test
    fun `missing user falls back to root`() {
        assertThat(parseSshDeepLink("ssh://example.com".toUri())!!.username).isEqualTo("root")
    }

    @Test
    fun `password in the link is discarded rather than imported`() {
        val profile = parseSshDeepLink("ssh://deploy:hunter2@example.com".toUri())

        assertThat(profile!!.username).isEqualTo("deploy")
        // The credential must not survive anywhere on the profile.
        assertThat(profile.username).doesNotContain("hunter2")
        assertThat(profile.name).doesNotContain("hunter2")
        assertThat(profile.host).doesNotContain("hunter2")
    }

    @Test
    fun `null uri is rejected`() {
        assertThat(parseSshDeepLink(null)).isNull()
    }

    @Test
    fun `foreign schemes are rejected`() {
        assertThat(parseSshDeepLink("http://example.com".toUri())).isNull()
        assertThat(parseSshDeepLink("https://example.com".toUri())).isNull()
        assertThat(parseSshDeepLink("file:///etc/passwd".toUri())).isNull()
        assertThat(parseSshDeepLink("javascript:alert(1)".toUri())).isNull()
        assertThat(parseSshDeepLink("SSH://example.com".toUri())).isNull()
    }

    @Test
    fun `links without a host are rejected`() {
        assertThat(parseSshDeepLink("ssh://".toUri())).isNull()
        assertThat(parseSshDeepLink("ssh:".toUri())).isNull()
        assertThat(parseSshDeepLink("ssh:///some/path".toUri())).isNull()
    }

    @Test
    fun `out of range and unparseable ports fall back to 22`() {
        // Uri.port returns -1 for a port it cannot parse, and the range check has to catch both
        // that and a syntactically valid but nonsensical port.
        assertThat(parseSshDeepLink("ssh://example.com:0".toUri())!!.port).isEqualTo(22)
        assertThat(parseSshDeepLink("ssh://example.com:70000".toUri())!!.port).isEqualTo(22)
        assertThat(parseSshDeepLink("ssh://example.com:-1".toUri())!!.port).isEqualTo(22)
        assertThat(parseSshDeepLink("ssh://example.com:notaport".toUri())!!.port).isEqualTo(22)
    }

    @Test
    fun `boundary ports are accepted`() {
        assertThat(parseSshDeepLink("ssh://example.com:1".toUri())!!.port).isEqualTo(1)
        assertThat(parseSshDeepLink("ssh://example.com:65535".toUri())!!.port).isEqualTo(65535)
    }

    @Test
    fun `ipv6 literal host survives parsing`() {
        val profile = parseSshDeepLink("ssh://deploy@[2001:db8::1]:2222".toUri())

        assertThat(profile).isNotNull()
        assertThat(profile!!.port).isEqualTo(2222)
        assertThat(profile.host).contains("2001:db8::1")
    }

    @Test
    fun `hostile and malformed links are rejected or defaulted but never crash`() {
        // Every one of these must return a usable profile or null — never throw out of the
        // activity's onCreate/onNewIntent, which would crash the app from an untrusted link.
        val hostile = listOf(
            "ssh://@example.com",
            "ssh://:@example.com",
            "ssh://deploy@",
            "ssh://deploy@:22",
            "ssh://example.com:22:22",
            "ssh://" + "a".repeat(5_000),
            "ssh://exa mple.com",
            "ssh://example.com/../../etc/passwd",
            "ssh://example.com?x=%00",
            "ssh://example.com#frag",
            "ssh://exam%00ple.com",
            // \u0000 rather than a raw NUL byte. The byte was here literally, which made grep
            // and every other text tool call this file binary and suppress its contents, so the
            // whole class was invisible to a search of the test suite. It is also the kind of
            // character a patch, a copy-paste or an editor that refuses NUL silently drops --
            // and a dropped NUL turns this case into an ordinary hostname that still passes,
            // which is the test quietly ceasing to test anything. The escape is the same string.
            "ssh://\u0000example.com",
        )

        hostile.forEach { raw ->
            val profile = runCatching { parseSshDeepLink(raw.toUri()) }
            assertThat(profile.exceptionOrNull()).isNull()
            // If a profile did come back, it must be internally coherent.
            profile.getOrNull()?.let {
                assertThat(it.host).isNotEmpty()
                assertThat(it.username).isNotEmpty()
                assertThat(it.port).isIn(1..65535)
            }
        }
    }
}
