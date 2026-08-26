package dev.eclipse.ssh.ssh

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.DEFAULT_TERMINAL_TYPE
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.KEEP_ALIVE_RANGE
import dev.eclipse.ssh.data.model.TERMINAL_COLUMNS_RANGE
import dev.eclipse.ssh.data.model.TERMINAL_ROWS_RANGE
import org.apache.sshd.common.compression.BuiltinCompressions
import org.junit.Test

/**
 * The per-host settings that have to be turned into numbers and lists before a session exists.
 *
 * All of these are decided during the connect, most of them before authentication and two of them
 * before the session object is even finished being constructed, so none can be observed from a running
 * app - a wrong answer shows up as a session that dies quietly two minutes later, or as a key exchange
 * that fails for reasons the log attributes to the server. They are pure functions for that reason and
 * are stated here directly.
 */
class SessionTuningTest {

    @Test
    fun `a host with no interval of its own follows the app wide one`() {
        assertThat(resolveKeepAliveSeconds(profile(), globalSeconds = 45)).isEqualTo(45)
    }

    @Test
    fun `a host with its own interval keeps it whatever settings says`() {
        val profile = profile().copy(keepAliveSeconds = 20)

        assertThat(resolveKeepAliveSeconds(profile, globalSeconds = 45)).isEqualTo(20)
    }

    @Test
    fun `a host that switched the keep alive off gets no interval at all`() {
        // Not "a very long interval": zero is what switches the heartbeat off in MINA, and switching it
        // off is the whole point of the setting.
        val profile = profile().copy(keepAliveEnabled = false, keepAliveSeconds = 20)

        assertThat(resolveKeepAliveSeconds(profile, globalSeconds = 45)).isEqualTo(0)
    }

    @Test
    fun `an interval outside the allowed range is clamped rather than trusted`() {
        // A profile can arrive from an imported vault backup that no form ever validated.
        assertThat(resolveKeepAliveSeconds(profile().copy(keepAliveSeconds = 1), globalSeconds = 45))
            .isEqualTo(KEEP_ALIVE_RANGE.first)
        assertThat(resolveKeepAliveSeconds(profile().copy(keepAliveSeconds = 100_000), globalSeconds = 45))
            .isEqualTo(KEEP_ALIVE_RANGE.last)
        // Including when the out-of-range number came from Settings rather than the host.
        assertThat(resolveKeepAliveSeconds(profile(), globalSeconds = 100_000))
            .isEqualTo(KEEP_ALIVE_RANGE.last)
    }

    /**
     * The coupling this whole workstream exists for: no heartbeat means no idle timeout.
     *
     * With the heartbeat off there are no missed replies for an idle timeout to back up, so the timeout
     * stops being a backstop and becomes the only thing in the app that ends healthy sessions - on
     * exactly the host whose owner asked for *less* traffic.
     */
    @Test
    fun `switching the keep alive off switches the idle timeout off with it`() {
        assertThat(idleTimeoutSeconds(keepAliveSeconds = 0, serverAliveCountMax = 3)).isEqualTo(0L)
    }

    @Test
    fun `the idle timeout outlives every reply the heartbeat is allowed to miss`() {
        val keepAlive = 30
        val misses = 3

        val idle = idleTimeoutSeconds(keepAlive, misses)

        // Strictly greater, or the idle clock fires first and reports a timeout for a session the
        // heartbeat had not given up on - the wrong reason, and one that reads as the server's fault.
        assertThat(idle).isGreaterThan(keepAlive.toLong() * misses)
        assertThat(idle).isEqualTo(30L * 3 + 60L)
    }

    @Test
    fun `a host that tolerates more missed replies waits longer before the backstop fires`() {
        val lenient = idleTimeoutSeconds(keepAliveSeconds = 30, serverAliveCountMax = 10)
        val strict = idleTimeoutSeconds(keepAliveSeconds = 30, serverAliveCountMax = 1)

        assertThat(lenient).isGreaterThan(strict)
        assertThat(strict).isGreaterThan(30L)
    }

    @Test
    fun `a nonsense miss count still leaves room for one reply`() {
        // Zero misses would make the idle timeout equal to the interval, so the backstop would fire in
        // the same second the first keep-alive was sent.
        assertThat(idleTimeoutSeconds(keepAliveSeconds = 30, serverAliveCountMax = 0))
            .isEqualTo(idleTimeoutSeconds(keepAliveSeconds = 30, serverAliveCountMax = 1))
    }

    @Test
    fun `public key is offered before anything that can leak a password`() {
        val offered = preferredAuths(profile()).split(",")

        assertThat(offered.first()).isEqualTo("publickey")
    }

    @Test
    fun `keyboard interactive is offered by default and withdrawn when the host says so`() {
        assertThat(preferredAuths(profile())).contains("keyboard-interactive")
        assertThat(preferredAuths(profile().copy(keyboardInteractiveAuth = false)))
            .doesNotContain("keyboard-interactive")
        // Password stays either way: withdrawing keyboard-interactive is about not being asked twice,
        // not about refusing to log in.
        assertThat(preferredAuths(profile().copy(keyboardInteractiveAuth = false))).contains("password")
    }

    @Test
    fun `a host that authenticates by keyboard interactive keeps the method it was configured to use`() {
        val profile = profile().copy(
            authMethod = AuthMethod.KEYBOARD_INTERACTIVE,
            keyboardInteractiveAuth = false,
        )

        assertThat(preferredAuths(profile)).contains("keyboard-interactive")
    }

    @Test
    fun `compression off proposes only none`() {
        val factories = compressionFactories(enabled = false)

        assertThat(factories.map { it.name }).containsExactly("none")
    }

    @Test
    fun `compression on proposes the delayed variant first and still leaves none to fall back to`() {
        val names = compressionFactories(enabled = true).map { it.name }

        // The literal wire names on purpose. These strings go out in KEX_INIT and are what a server
        // matches on, and Kotlin's Enum.name shadows MINA's own getName() on these constants, so
        // BuiltinCompressions.delayedZlib.name reads "delayedZlib" here and never "zlib@openssh.com".
        //
        // A server with no compression support has to have something to agree to, or enabling
        // compression on this host would mean failing to connect to that server at all.
        assertThat(names.last()).isEqualTo("none")
        if (BuiltinCompressions.delayedZlib.isSupported) {
            // zlib@openssh.com before plain zlib: it starts compressing only after authentication.
            assertThat(names.first()).isEqualTo("zlib@openssh.com")
        }
        assertThat(names).containsNoDuplicates()
        assertThat(compressionFactories(enabled = true).all { it.create() != null }).isTrue()
    }

    @Test
    fun `a pty is asked for with the terminal type the emulator implements`() {
        val request = ptyRequestFor(profile(), measuredColumns = 96, measuredRows = 30)

        assertThat(request.enabled).isTrue()
        assertThat(request.terminalType).isEqualTo(DEFAULT_TERMINAL_TYPE)
        assertThat(request.columns).isEqualTo(96)
        assertThat(request.rows).isEqualTo(30)
    }

    @Test
    fun `a host that forced a size wins over what was measured`() {
        val profile = profile().copy(terminalColumns = 132, terminalRows = 43)

        val request = ptyRequestFor(profile, measuredColumns = 96, measuredRows = 30)

        assertThat(request.columns).isEqualTo(132)
        assertThat(request.rows).isEqualTo(43)
    }

    @Test
    fun `a forced size is clamped to something a shell is usable at`() {
        val profile = profile().copy(terminalColumns = 4, terminalRows = 9_999)

        val request = ptyRequestFor(profile, measuredColumns = 96, measuredRows = 30)

        assertThat(request.columns).isEqualTo(TERMINAL_COLUMNS_RANGE.first)
        assertThat(request.rows).isEqualTo(TERMINAL_ROWS_RANGE.last)
    }

    @Test
    fun `each axis is forced on its own`() {
        // Forcing the width of a table without also fixing how much of it fits on the phone is the
        // reason these are two settings and not one.
        val request = ptyRequestFor(profile().copy(terminalColumns = 132), measuredColumns = 96, measuredRows = 30)

        assertThat(request.columns).isEqualTo(132)
        assertThat(request.rows).isEqualTo(30)
    }

    @Test
    fun `a host that never measured a viewport asks for no size rather than a guessed one`() {
        // Null is not zero: the channel's own default geometry is what fills this in, and a zero here
        // would be sent as a pty 0 columns wide.
        val request = ptyRequestFor(profile(), measuredColumns = null, measuredRows = null)

        assertThat(request.columns).isNull()
        assertThat(request.rows).isNull()
    }

    @Test
    fun `a forced size survives a viewport that has not been measured yet`() {
        // Which is what makes a forced width hold through a rotation rather than reverting for one frame.
        val profile = profile().copy(terminalColumns = 132, terminalRows = 43)

        val request = ptyRequestFor(profile, measuredColumns = null, measuredRows = null)

        assertThat(request.columns).isEqualTo(132)
        assertThat(request.rows).isEqualTo(43)
    }

    @Test
    fun `a host that turned the pty off still reports a terminal type and geometry`() {
        // Both are ignored while the pty is off, and both have to be right the moment it is turned back
        // on - the alternative is a channel opened with no pty and a window-change sent against it.
        val request = ptyRequestFor(profile().copy(usePty = false), measuredColumns = 96, measuredRows = 30)

        assertThat(request.enabled).isFalse()
        assertThat(request.terminalType).isEqualTo(DEFAULT_TERMINAL_TYPE)
        assertThat(request.columns).isEqualTo(96)
    }

    @Test
    fun `a blank terminal type falls back rather than being sent as an empty TERM`() {
        val request = ptyRequestFor(profile().copy(terminalType = "  "), measuredColumns = 96, measuredRows = 30)

        assertThat(request.terminalType).isEqualTo(DEFAULT_TERMINAL_TYPE)
    }

    @Test
    fun `a session opened with no profile behaves exactly as the app did before hosts could choose`() {
        // Every call site that has no profile in scope - an adopted session, a restored tab - has to
        // land on the old hard-coded behaviour rather than on a disabled pty.
        val request = ptyRequestFor(profile = null, measuredColumns = 96, measuredRows = 30)

        assertThat(request.enabled).isTrue()
        assertThat(request.terminalType).isEqualTo(DEFAULT_TERMINAL_TYPE)
        assertThat(request.columns).isEqualTo(96)
        assertThat(request.rows).isEqualTo(30)
    }

    @Test
    fun `an environment column becomes the variables it names, in the order it names them`() {
        val environment = parseEnvironment("LANG=en_US.UTF-8\nTZ=Europe/Amsterdam\nEDITOR=vim")

        assertThat(environment).containsExactly(
            "LANG", "en_US.UTF-8",
            "TZ", "Europe/Amsterdam",
            "EDITOR", "vim",
        ).inOrder()
    }

    @Test
    fun `a host that was never given an environment sends no channel requests at all`() {
        // Not an empty variable, and not a request with nothing in it: a channel request per variable is
        // what MINA sends, so "no environment" has to mean no requests or every session pays for a
        // feature nobody configured.
        listOf(null, "", "   ", "\n\n", "# just a comment").forEach { text ->
            assertThat(parseEnvironment(text)).isEmpty()
        }
    }

    @Test
    fun `a value keeps the characters that make it a value`() {
        // The three shapes that a naive split on '=' or on whitespace would destroy, and all three are
        // ordinary: a locale with a dot, a PATH with colons, a value that simply contains an equals sign.
        val environment = parseEnvironment("PATH=/usr/local/bin:/usr/bin\nOPTS=-Dkey=value\nGREETING=hello world")

        assertThat(environment["PATH"]).isEqualTo("/usr/local/bin:/usr/bin")
        assertThat(environment["OPTS"]).isEqualTo("-Dkey=value")
        assertThat(environment["GREETING"]).isEqualTo("hello world")
    }

    @Test
    fun `one layer of matching quotes is a convenience, not a shell`() {
        // Quotes let a value with spaces be written the way it would be in a file. Only one layer and only
        // when both ends match, because there is no shell in a channel request - pretending otherwise
        // would make a value mean something different here than it does anywhere else.
        assertThat(parseEnvironment("TZ=\"Europe/London\"")["TZ"]).isEqualTo("Europe/London")
        assertThat(parseEnvironment("TZ='Europe/London'")["TZ"]).isEqualTo("Europe/London")
        // Unbalanced stays verbatim: dropping the one quote would be this app editing the value.
        assertThat(parseEnvironment("TZ=\"Europe/London")["TZ"]).isEqualTo("\"Europe/London")
        assertThat(parseEnvironment("PS1='\\u@\\h '")["PS1"]).isEqualTo("\\u@\\h ")
    }

    @Test
    fun `a name that is not a name is dropped, and the rest of the column still sends`() {
        // A name is a protocol field, not a shell word: a channel request carrying a space or a newline in
        // its name is a malformed request and MINA would send it. And one bad line must not cost the login
        // - a host whose environment has a typo in the third line should still get the other two.
        val environment = parseEnvironment(
            listOf(
                "LANG=en_US.UTF-8",   // fine
                "has space=1",        // a space in a name
                "1STARTS_WITH_DIGIT=1",
                "LÄNG=1",             // non-ASCII
                "WITH-DASH=1",
                "NO_EQUALS_AT_ALL",   // nothing it could mean
                "=orphan",            // a value with no name
                "  ",
                "# a comment=1",
                "TZ=Europe/Amsterdam", // fine
            ).joinToString("\n"),
        )

        assertThat(environment.keys).containsExactly("LANG", "TZ").inOrder()
    }

    @Test
    fun `an over long name is refused rather than truncated`() {
        // Truncating would send a *different* variable, which is worse than sending none: `LONG…` cut to
        // 64 characters is a name the server has never heard of and the user cannot see.
        assertThat(parseEnvironment("${"A".repeat(64)}=1")).hasSize(1)
        assertThat(parseEnvironment("${"A".repeat(65)}=1")).isEmpty()
    }

    @Test
    fun `a name assigned twice keeps the last line`() {
        // What a shell script doing the same thing would do, and the only answer that makes editing the
        // field predictable: a line added at the bottom wins.
        assertThat(parseEnvironment("TZ=UTC\nTZ=Europe/Amsterdam")["TZ"]).isEqualTo("Europe/Amsterdam")
    }

    @Test
    fun `a startup command is typed into the shell, ending in a Return`() {
        // A pty, so Return is what a keypress sends and the line discipline turns it into the newline the
        // shell reads. LF works on Linux and is subtly wrong on the systems that do not map it.
        val bytes = startupCommandBytes("tmux attach")

        assertThat(bytes).isNotNull()
        assertThat(String(bytes!!, Charsets.UTF_8)).isEqualTo("tmux attach\r")
    }

    @Test
    fun `no startup command means nothing is written into the shell`() {
        // Null rather than an empty array, so the call site cannot write "nothing" into the pty and print
        // a second prompt above the user's first one.
        listOf(null, "", "   ", "\n", "  \n  ").forEach { command ->
            assertThat(startupCommandBytes(command)).isNull()
        }
    }

    @Test
    fun `a multi line startup command is a multi line paste with exactly one Return at the end`() {
        // Two Returns at the end would run an empty line and print a second prompt, which reads as the
        // command having gone wrong.
        assertThat(startupCommandBytes("cd /srv\nls -la")?.toString(Charsets.UTF_8)).isEqualTo("cd /srv\rls -la\r")
        assertThat(startupCommandBytes("cd /srv\n\n")?.toString(Charsets.UTF_8)).isEqualTo("cd /srv\r")
        assertThat(startupCommandBytes("cd /srv\r\nls\r\n")?.toString(Charsets.UTF_8)).isEqualTo("cd /srv\rls\r")
    }

    @Test
    fun `a command with characters outside ASCII survives as the bytes a shell reads`() {
        // The pty is a byte stream and the shell's charset is UTF-8 on anything current, so a path with an
        // accent in it has to arrive as UTF-8 and not as a mangled single byte per character.
        val bytes = startupCommandBytes("cd /srv/données")

        assertThat(bytes).isEqualTo("cd /srv/données\r".toByteArray(Charsets.UTF_8))
    }

    private fun profile() = HostProfile(
        id = "tuning-host",
        name = "Tuning",
        host = "tuning.example.com",
        username = "root",
    )
}
