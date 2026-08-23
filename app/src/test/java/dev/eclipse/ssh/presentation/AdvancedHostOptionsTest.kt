package dev.eclipse.ssh.presentation

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.AUTH_TIMEOUT_RANGE
import dev.eclipse.ssh.data.model.DEFAULT_TERMINAL_TYPE
import dev.eclipse.ssh.data.model.HostKeyPolicy
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.INHERIT_RECONNECT_BACKOFF
import dev.eclipse.ssh.data.model.MAX_RECONNECT_ATTEMPTS_RANGE
import dev.eclipse.ssh.data.model.RECONNECT_BACKOFF_RANGE
import dev.eclipse.ssh.data.model.SERVER_ALIVE_COUNT_RANGE
import dev.eclipse.ssh.data.model.TERMINAL_COLUMNS_RANGE
import dev.eclipse.ssh.data.model.TERMINAL_ROWS_RANGE
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
        // The section edits fourteen columns with one value object, so a copy that dropped a field would
        // silently reset it - the failure mode of every form that maps widgets to columns by hand.
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
        // outside its own fourteen columns would be an edit the user never made.
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

    private fun profile() = HostProfile(
        id = "advanced-host",
        name = "Advanced",
        host = "advanced.example.com",
        username = "root",
    )
}
