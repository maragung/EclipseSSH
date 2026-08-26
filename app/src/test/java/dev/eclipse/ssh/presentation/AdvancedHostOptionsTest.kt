package dev.eclipse.ssh.presentation

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.ALGORITHM_LIST_MAX_LENGTH
import dev.eclipse.ssh.data.model.AUTH_TIMEOUT_RANGE
import dev.eclipse.ssh.data.model.DEFAULT_TERMINAL_TYPE
import dev.eclipse.ssh.data.model.ENVIRONMENT_MAX_LENGTH
import dev.eclipse.ssh.data.model.ForwardEntry
import dev.eclipse.ssh.data.model.ForwardType
import dev.eclipse.ssh.data.model.HostKeyPolicy
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.INHERIT_RECONNECT_BACKOFF
import dev.eclipse.ssh.data.model.MAX_RECONNECT_ATTEMPTS_RANGE
import dev.eclipse.ssh.data.model.MAX_SAVED_FORWARDS
import dev.eclipse.ssh.data.model.RECONNECT_BACKOFF_RANGE
import dev.eclipse.ssh.data.model.SERVER_ALIVE_COUNT_RANGE
import dev.eclipse.ssh.data.model.STARTUP_COMMAND_MAX_LENGTH
import dev.eclipse.ssh.data.model.TERMINAL_COLUMNS_RANGE
import dev.eclipse.ssh.data.model.TERMINAL_ROWS_RANGE
import dev.eclipse.ssh.ssh.AlgorithmKind
import dev.eclipse.ssh.ssh.supportedAlgorithmNames
import org.junit.Test

/**
 * The Advanced section of the host form, as rules rather than as widgets.
 *
 * Reachable in the app only by opening a dialog and expanding a section, which is the one thing this
 * project cannot drive: Robolectric never idles with a Compose dialog window open. Getting a sentinel
 * wrong here is silent - a host saved with 0 columns asks the server for a pty no columns wide, and a
 * host saved with 5-second backoff quietly overrides whatever Settings says - so each rule is stated.
 */
class AdvancedHostOptionsTest {

    @Test
    fun `a new host starts on the behaviour the app had before any of this was configurable`() {
        val defaults = AdvancedHostOptions.DEFAULTS

        assertThat(defaults.isDefault).isTrue()
        assertThat(defaults.isValid).isTrue()
        assertThat(defaults.keepAliveEnabled).isTrue()
        assertThat(defaults.autoReconnect).isTrue()
        assertThat(defaults.usePty).isTrue()
        assertThat(defaults.keyboardInteractiveAuth).isTrue()
        assertThat(defaults.compression).isFalse()
        assertThat(defaults.terminalType).isEqualTo(DEFAULT_TERMINAL_TYPE)
        assertThat(defaults.hostKeyPolicy).isEqualTo(HostKeyPolicy.ASK)
        // Null, not false: a host follows the app-wide legacy-algorithm switch until it says otherwise.
        assertThat(defaults.legacyAlgorithms).isNull()
        // Blank, not a rendering of this build's default lists: showing MINA's twelve ciphers in the box
        // would turn a host with no opinion into a host that has pinned this release's list forever.
        assertThat(defaults.ciphers).isEmpty()
        assertThat(defaults.kexAlgorithms).isEmpty()
        assertThat(defaults.macs).isEmpty()
        assertThat(defaults.hostKeyAlgorithms).isEmpty()
        assertThat(defaults.startupCommand).isEmpty()
        assertThat(defaults.environment).isEmpty()
        assertThat(defaults.forwards).isEmpty()
    }

    @Test
    fun `a host being created and a host that was never configured show the same form`() {
        assertThat(AdvancedHostOptions.from(null)).isEqualTo(AdvancedHostOptions.DEFAULTS)
        assertThat(AdvancedHostOptions.from(profile())).isEqualTo(AdvancedHostOptions.DEFAULTS)
    }

    @Test
    fun `applying the defaults to an unconfigured host changes nothing`() {
        val host = profile()

        assertThat(AdvancedHostOptions.DEFAULTS.applyTo(host)).isEqualTo(host)
    }

    @Test
    fun `every option survives the trip from a host into the form and back`() {
        val tuned = profile().copy(
            compression = true,
            keepAliveEnabled = false,
            serverAliveCountMax = 6,
            authTimeoutSeconds = 90,
            autoReconnect = false,
            maxReconnectAttempts = 12,
            reconnectBackoffSeconds = 9,
            usePty = false,
            terminalType = "screen-256color",
            terminalColumns = 132,
            terminalRows = 43,
            keyboardInteractiveAuth = false,
            legacyAlgorithms = true,
            hostKeyPolicy = HostKeyPolicy.ACCEPT_NEW,
            ciphers = "aes256-gcm@openssh.com,aes128-ctr",
            kexAlgorithms = "curve25519-sha256",
            macs = "hmac-sha2-256-etm@openssh.com",
            hostKeyAlgorithms = "ssh-ed25519,rsa-sha2-512",
            startupCommand = "tmux attach || tmux new",
            environment = "LANG=en_US.UTF-8\nTZ=Europe/Amsterdam",
            savedForwards = "L:8080:intranet.example:80\nD:1080",
        )

        val edited = AdvancedHostOptions.from(tuned)

        assertThat(edited.isValid).isTrue()
        assertThat(edited.isDefault).isFalse()
        assertThat(edited.applyTo(profile())).isEqualTo(tuned)
    }

    @Test
    fun `the two automatic sizes come back as empty boxes rather than a zero to decode`() {
        val host = profile().copy(terminalColumns = 0, terminalRows = 0)

        val edited = AdvancedHostOptions.from(host)

        assertThat(edited.terminalColumns).isEmpty()
        assertThat(edited.terminalRows).isEmpty()
    }

    @Test
    fun `an inherited reconnect delay comes back as an empty box too`() {
        val host = profile().copy(reconnectBackoffSeconds = INHERIT_RECONNECT_BACKOFF)

        assertThat(AdvancedHostOptions.from(host).reconnectBackoffSeconds).isEmpty()
    }

    @Test
    fun `clearing a size field means match the screen rather than zero columns`() {
        val options = AdvancedHostOptions.DEFAULTS.copy(terminalColumns = "", terminalRows = "")

        val applied = options.applyTo(profile().copy(terminalColumns = 132, terminalRows = 43))

        assertThat(applied.terminalColumns).isEqualTo(0)
        assertThat(applied.terminalRows).isEqualTo(0)
        // A cleared field is a decision, not an error, so it must not block the save.
        assertThat(options.isValid).isTrue()
    }

    @Test
    fun `clearing the reconnect delay hands the decision back to Settings`() {
        val options = AdvancedHostOptions.DEFAULTS.copy(reconnectBackoffSeconds = "")

        val applied = options.applyTo(profile().copy(reconnectBackoffSeconds = 9))

        assertThat(applied.reconnectBackoffSeconds).isEqualTo(INHERIT_RECONNECT_BACKOFF)
        assertThat(options.backoffValid).isTrue()
    }

    @Test
    fun `a number outside its range is refused per field and blocks the save`() {
        val bad = AdvancedHostOptions.DEFAULTS.copy(
            serverAliveCountMax = (SERVER_ALIVE_COUNT_RANGE.last + 1).toString(),
            authTimeoutSeconds = (AUTH_TIMEOUT_RANGE.first - 1).toString(),
            maxReconnectAttempts = "0",
            reconnectBackoffSeconds = (RECONNECT_BACKOFF_RANGE.last + 1).toString(),
            terminalColumns = (TERMINAL_COLUMNS_RANGE.first - 1).toString(),
            terminalRows = (TERMINAL_ROWS_RANGE.last + 1).toString(),
        )

        assertThat(bad.serverAliveCountValid).isFalse()
        assertThat(bad.authTimeoutValid).isFalse()
        assertThat(bad.maxReconnectAttemptsValid).isFalse()
        assertThat(bad.backoffValid).isFalse()
        assertThat(bad.columnsValid).isFalse()
        assertThat(bad.rowsValid).isFalse()
        assertThat(bad.isValid).isFalse()
    }

    @Test
    fun `the edges of every range are accepted`() {
        val edges = AdvancedHostOptions.DEFAULTS.copy(
            serverAliveCountMax = SERVER_ALIVE_COUNT_RANGE.first.toString(),
            authTimeoutSeconds = AUTH_TIMEOUT_RANGE.last.toString(),
            maxReconnectAttempts = MAX_RECONNECT_ATTEMPTS_RANGE.last.toString(),
            reconnectBackoffSeconds = RECONNECT_BACKOFF_RANGE.first.toString(),
            terminalColumns = TERMINAL_COLUMNS_RANGE.last.toString(),
            terminalRows = TERMINAL_ROWS_RANGE.first.toString(),
        )

        assertThat(edges.isValid).isTrue()
    }

    @Test
    fun `a required number cannot be left blank`() {
        // Unlike the three optional fields, these have no meaning as "unset": there is no sensible
        // "inherit" for an authentication timeout, so an empty box is an unfinished form.
        val blank = AdvancedHostOptions.DEFAULTS.copy(
            serverAliveCountMax = "",
            authTimeoutSeconds = "",
            maxReconnectAttempts = "",
        )

        assertThat(blank.serverAliveCountValid).isFalse()
        assertThat(blank.authTimeoutValid).isFalse()
        assertThat(blank.maxReconnectAttemptsValid).isFalse()
    }

    @Test
    fun `text that is not a number is refused rather than read as one`() {
        val nonsense = AdvancedHostOptions.DEFAULTS.copy(
            authTimeoutSeconds = "30s",
            terminalColumns = "-80",
            terminalRows = "8O",
        )

        assertThat(nonsense.authTimeoutValid).isFalse()
        assertThat(nonsense.columnsValid).isFalse()
        assertThat(nonsense.rowsValid).isFalse()
    }

    /**
     * A profile that no form ever validated - an imported vault backup, a hand-edited export - is
     * clamped on the way out rather than saved as it arrived.
     */
    @Test
    fun `an out of range value that reached the form anyway is clamped on save`() {
        val wild = AdvancedHostOptions.DEFAULTS.copy(
            serverAliveCountMax = "9999",
            authTimeoutSeconds = "1",
            maxReconnectAttempts = "9999",
            reconnectBackoffSeconds = "9999",
            terminalColumns = "9999",
            terminalRows = "1",
        )

        val applied = wild.applyTo(profile())

        assertThat(applied.serverAliveCountMax).isEqualTo(SERVER_ALIVE_COUNT_RANGE.last)
        assertThat(applied.authTimeoutSeconds).isEqualTo(AUTH_TIMEOUT_RANGE.first)
        assertThat(applied.maxReconnectAttempts).isEqualTo(MAX_RECONNECT_ATTEMPTS_RANGE.last)
        assertThat(applied.reconnectBackoffSeconds).isEqualTo(RECONNECT_BACKOFF_RANGE.last)
        assertThat(applied.terminalColumns).isEqualTo(TERMINAL_COLUMNS_RANGE.last)
        assertThat(applied.terminalRows).isEqualTo(TERMINAL_ROWS_RANGE.first)
    }

    @Test
    fun `a blank terminal type is saved as the type the emulator implements`() {
        val applied = AdvancedHostOptions.DEFAULTS.copy(terminalType = "   ").applyTo(profile())

        assertThat(applied.terminalType).isEqualTo(DEFAULT_TERMINAL_TYPE)
    }

    @Test
    fun `a terminal type keeps its own name and loses the whitespace around it`() {
        val applied = AdvancedHostOptions.DEFAULTS.copy(terminalType = " vt100 ").applyTo(profile())

        assertThat(applied.terminalType).isEqualTo("vt100")
    }

    @Test
    fun `reset is offered only when something was changed and restores every field`() {
        val changed = AdvancedHostOptions.DEFAULTS.copy(compression = true, terminalColumns = "132")
        assertThat(changed.isDefault).isFalse()

        // What the Reset button does, and the assertion that it is complete: any field the button
        // forgot would leave isDefault false and the button still enabled after tapping it.
        assertThat(AdvancedHostOptions.DEFAULTS.isDefault).isTrue()
        assertThat(AdvancedHostOptions.DEFAULTS.applyTo(changed.applyTo(profile())))
            .isEqualTo(profile())
    }

    @Test
    fun `switching one option off does not disturb the others`() {
        // The section edits twenty-one columns with one value object, so a copy that dropped a field
        // would silently reset it - the failure mode of every form that maps widgets to columns by hand.
        val tuned = AdvancedHostOptions.from(
            profile().copy(compression = true, terminalColumns = 132, hostKeyPolicy = HostKeyPolicy.STRICT),
        )

        val applied = tuned.copy(keepAliveEnabled = false).applyTo(profile())

        assertThat(applied.keepAliveEnabled).isFalse()
        assertThat(applied.compression).isTrue()
        assertThat(applied.terminalColumns).isEqualTo(132)
        assertThat(applied.hostKeyPolicy).isEqualTo(HostKeyPolicy.STRICT)
    }

    @Test
    fun `the section leaves the rest of the host alone`() {
        // applyTo is handed a profile the main form has already filled in, so anything it touched
        // outside its own twenty-one columns would be an edit the user never made.
        val host = profile().copy(
            name = "Prod",
            host = "prod.example.com",
            port = 2222,
            username = "deploy",
            keepAliveSeconds = 20,
            connectTimeoutSeconds = 45,
        )

        val applied = AdvancedHostOptions.from(host).copy(compression = true).applyTo(host)

        assertThat(applied).isEqualTo(host.copy(compression = true))
    }

    @Test
    fun `a blank algorithm list is saved as no opinion rather than as an empty list`() {
        // The distinction the column depends on. Null means "negotiate normally"; "" would be a real
        // instruction to propose nothing at all, which no server can answer - so a host whose boxes are
        // empty has to reach the database as four nulls and not as four empty strings.
        val applied = AdvancedHostOptions.DEFAULTS.copy(ciphers = "   ", macs = "").applyTo(profile())

        assertThat(applied.ciphers).isNull()
        assertThat(applied.kexAlgorithms).isNull()
        assertThat(applied.macs).isNull()
        assertThat(applied.hostKeyAlgorithms).isNull()
        assertThat(AdvancedHostOptions.DEFAULTS.isValid).isTrue()
    }

    @Test
    fun `an algorithm this device cannot do is refused in the form rather than at connect time`() {
        // The whole reason the review is computed here: a name MINA cannot honour would otherwise be
        // saved, and the failure would arrive as a key-exchange error on a host that used to work.
        val options = AdvancedHostOptions.DEFAULTS.copy(ciphers = "aes256-unicorn")

        assertThat(options.cipherReview.isAcceptable).isFalse()
        assertThat(options.cipherReview.unsupported).contains("aes256-unicorn")
        assertThat(options.isValid).isFalse()
    }

    @Test
    fun `the none cipher cannot be saved on a host`() {
        // MINA implements the protocol's null cipher and reports it as available, so nothing but an
        // explicit refusal stops a host being saved with encryption switched off. The form is the last
        // place that refusal can still be explained to the user.
        val options = AdvancedHostOptions.DEFAULTS.copy(ciphers = "none")

        assertThat(options.cipherReview.refused).containsExactly("none")
        assertThat(options.cipherReview.problem).contains("unencrypted")
        assertThat(options.isValid).isFalse()
    }

    @Test
    fun `a list of real algorithms can still be too long to save`() {
        // The case the reviews cannot catch, and the realistic one: the chip rows append rather than
        // replace, and a paste is a paste. Built out of a name this device really does support, so the
        // only thing wrong with the list is its length.
        val supported = supportedAlgorithmNames(AlgorithmKind.CIPHERS).first()
        val pasted = List(ALGORITHM_LIST_MAX_LENGTH / supported.length + 2) { supported }.joinToString(",")
        assertThat(pasted.length).isGreaterThan(ALGORITHM_LIST_MAX_LENGTH)

        val options = AdvancedHostOptions.DEFAULTS.copy(ciphers = pasted)

        assertThat(options.cipherReview.isAcceptable).isTrue()
        assertThat(options.algorithmListsValid).isFalse()
        assertThat(options.isValid).isFalse()
    }

    @Test
    fun `a startup command longer than the column blocks the save rather than being cut in half`() {
        // Truncation is the dangerous answer here: half a command line is still a command, and
        // `cd /srv && rm -rf build` cut at the wrong character is a different instruction entirely.
        val options = AdvancedHostOptions.DEFAULTS.copy(startupCommand = "x".repeat(STARTUP_COMMAND_MAX_LENGTH + 1))

        assertThat(options.startupCommandValid).isFalse()
        assertThat(options.isValid).isFalse()
        assertThat(AdvancedHostOptions.DEFAULTS.copy(startupCommand = "x".repeat(STARTUP_COMMAND_MAX_LENGTH)).isValid)
            .isTrue()
    }

    @Test
    fun `an environment line the protocol cannot carry blocks the save`() {
        // Asked of the parser rather than re-implemented, so the red field and the variables that
        // actually get sent cannot drift apart.
        val options = AdvancedHostOptions.DEFAULTS.copy(environment = "LANG=en_US.UTF-8\nnot an assignment")

        assertThat(options.environmentValid).isFalse()
        assertThat(options.isValid).isFalse()
    }

    @Test
    fun `comments and blank lines are not mistakes in the environment box`() {
        val options = AdvancedHostOptions.DEFAULTS.copy(
            environment = "# the shell needs these\n\nLANG=en_US.UTF-8\n\nTZ=Europe/Amsterdam\n",
        )

        assertThat(options.environmentValid).isTrue()
        assertThat(options.isValid).isTrue()
    }

    @Test
    fun `an environment longer than the column blocks the save`() {
        // Every line is a real assignment with a name of its own, so the only thing wrong with this box
        // is its size - which is the case the per-line check cannot see.
        val options = AdvancedHostOptions.DEFAULTS.copy(
            environment = List(ENVIRONMENT_MAX_LENGTH / 6 + 2) { "V" + it.toString().padStart(3, '0') + "=x" }
                .joinToString("\n"),
        )

        assertThat(options.environment.length).isGreaterThan(ENVIRONMENT_MAX_LENGTH)
        assertThat(options.environmentValid).isFalse()
        assertThat(options.isValid).isFalse()
    }

    @Test
    fun `the startup command and the environment lose the whitespace around them on save`() {
        val applied = AdvancedHostOptions.DEFAULTS
            .copy(startupCommand = "  tmux attach  ", environment = "\n LANG=en_US.UTF-8 \n")
            .applyTo(profile())

        assertThat(applied.startupCommand).isEqualTo("tmux attach")
        assertThat(applied.environment).isEqualTo("LANG=en_US.UTF-8")
    }

    @Test
    fun `a saved forward reaches the editor as a rule and the column as a line`() {
        val host = profile().copy(savedForwards = "L:8080:intranet.example:80\nD:1080")

        val edited = AdvancedHostOptions.from(host)

        assertThat(edited.forwards).hasSize(2)
        assertThat(edited.forwards.map { it.type })
            .containsExactly(ForwardType.LOCAL, ForwardType.DYNAMIC).inOrder()
        // Tagged with the host that owns them, because a running forward is stopped by host id.
        assertThat(edited.forwards.map { it.hostId }).containsExactly(host.id, host.id)
        assertThat(edited.applyTo(profile())).isEqualTo(host)
    }

    @Test
    fun `a rule the app could not act on does not travel with the host`() {
        // The column can arrive from a hand-edited backup. A line that is not a rule is dropped on the
        // way in, and Save then writes back only what the editor could actually show.
        val host = profile().copy(savedForwards = "D:1080\nL:8080\nX:99")

        val edited = AdvancedHostOptions.from(host)

        assertThat(edited.forwards).hasSize(1)
        assertThat(edited.forwardsValid).isTrue()
        assertThat(edited.applyTo(host).savedForwards).isEqualTo("D:1080")
    }

    @Test
    fun `more forwards than a host may carry blocks the save`() {
        val tooMany = List(MAX_SAVED_FORWARDS + 1) { index ->
            ForwardEntry(type = ForwardType.DYNAMIC, localPort = 1080 + index, hostId = "advanced-host")
        }

        val options = AdvancedHostOptions.DEFAULTS.copy(forwards = tooMany)

        assertThat(options.forwardsValid).isFalse()
        assertThat(options.isValid).isFalse()
        assertThat(AdvancedHostOptions.DEFAULTS.copy(forwards = tooMany.dropLast(1)).isValid).isTrue()
    }

    @Test
    fun `configuring any of the new options enables Reset`() {
        // isDefault decides whether the button is offered at all, so a field it did not know about would
        // be a setting the user could turn on and never get back.
        listOf(
            AdvancedHostOptions.DEFAULTS.copy(ciphers = "aes128-ctr"),
            AdvancedHostOptions.DEFAULTS.copy(kexAlgorithms = "curve25519-sha256"),
            AdvancedHostOptions.DEFAULTS.copy(macs = "hmac-sha2-256"),
            AdvancedHostOptions.DEFAULTS.copy(hostKeyAlgorithms = "ssh-ed25519"),
            AdvancedHostOptions.DEFAULTS.copy(startupCommand = "tmux attach"),
            AdvancedHostOptions.DEFAULTS.copy(environment = "LANG=C"),
            AdvancedHostOptions.DEFAULTS.copy(
                forwards = listOf(ForwardEntry(type = ForwardType.DYNAMIC, localPort = 1080)),
            ),
        ).forEach { assertThat(it.isDefault).isFalse() }
    }

    @Test
    fun `reset clears the new options too`() {
        val configured = AdvancedHostOptions.from(
            profile().copy(
                ciphers = "aes128-ctr",
                startupCommand = "tmux attach",
                environment = "LANG=C",
                savedForwards = "D:1080",
            ),
        )

        assertThat(AdvancedHostOptions.DEFAULTS.applyTo(configured.applyTo(profile()))).isEqualTo(profile())
    }

    private fun profile() = HostProfile(
        id = "advanced-host",
        name = "Advanced",
        host = "advanced.example.com",
        username = "root",
    )
}
