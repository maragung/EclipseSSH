package dev.eclipse.ssh.presentation

import dev.eclipse.ssh.data.model.ALGORITHM_LIST_MAX_LENGTH
import dev.eclipse.ssh.data.model.AUTH_TIMEOUT_RANGE
import dev.eclipse.ssh.data.model.DEFAULT_AUTH_TIMEOUT_SECONDS
import dev.eclipse.ssh.data.model.ENVIRONMENT_MAX_LENGTH
import dev.eclipse.ssh.data.model.ForwardEntry
import dev.eclipse.ssh.data.model.MAX_SAVED_FORWARDS
import dev.eclipse.ssh.data.model.STARTUP_COMMAND_MAX_LENGTH
import dev.eclipse.ssh.data.model.decodeForwardRules
import dev.eclipse.ssh.data.model.encodeForwardRules
import dev.eclipse.ssh.data.model.DEFAULT_MAX_RECONNECT_ATTEMPTS
import dev.eclipse.ssh.data.model.DEFAULT_SERVER_ALIVE_COUNT_MAX
import dev.eclipse.ssh.data.model.DEFAULT_TERMINAL_TYPE
import dev.eclipse.ssh.data.model.HostKeyPolicy
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.INHERIT_RECONNECT_BACKOFF
import dev.eclipse.ssh.data.model.MAX_RECONNECT_ATTEMPTS_RANGE
import dev.eclipse.ssh.data.model.RECONNECT_BACKOFF_RANGE
import dev.eclipse.ssh.data.model.SERVER_ALIVE_COUNT_RANGE
import dev.eclipse.ssh.data.model.TERMINAL_COLUMNS_RANGE
import dev.eclipse.ssh.data.model.TERMINAL_ROWS_RANGE
import dev.eclipse.ssh.ssh.AlgorithmKind
import dev.eclipse.ssh.ssh.AlgorithmListReview
import dev.eclipse.ssh.ssh.parseEnvironment
import dev.eclipse.ssh.ssh.reviewAlgorithmList

/**
 * The Advanced section of the Add / Edit Host form, as data.
 *
 * Same reasoning as [HostFormDraft], which this sits beside: a rule reachable only by tapping through
 * an `AlertDialog` cannot be tested on this project at all, because Robolectric never idles with a
 * Compose dialog window open. Everything here - what a blank field means, which numbers are accepted,
 * what Reset restores, and how the section's widgets become the host's columns - is therefore a plain
 * value with plain tests, and the composable holds nothing but the state it is editing.
 *
 * The numeric fields are strings for the reason every form's numeric fields are: an `Int` cannot
 * represent "the user has cleared this field", and three of these fields need exactly that state to
 * mean something specific - "inherit the app-wide delay", "match the screen".
 *
 * Only settings Apache MINA SSHD 2.19.0 can actually apply per host are here. Four of the settings that
 * were asked for are absent, and their absence is deliberate rather than an omission:
 *
 *  - **Socket read timeout.** `NIO2_READ_TIMEOUT` is read from the client when the socket is created,
 *    not from the session, so it cannot differ per host - and a read deadline on an interactive shell
 *    is the very bug this release exists to remove: a session sitting at a prompt with nothing to say
 *    is healthy, and a deadline cannot tell it from a dead one.
 *  - **Agent forwarding.** Now present as [agentForwarding] - and its switch is the one place the
 *    trust warning lives, because enabling it is a grant: the server's administrator can request
 *    signatures as you while a session with forwarding is open.
 *  - **X11 forwarding.** MINA's client has no X11 channel implementation, so a switch would set a flag
 *    nothing reads.
 *  - **"Allow TCP forwarding".** Forwarding is per-tunnel on a client; the protocol has no session-wide
 *    client-side switch to expose. The Port forwarding screen is where it is decided.
 */
internal data class AdvancedHostOptions(
    val compression: Boolean = false,
    val keepAliveEnabled: Boolean = true,
    val serverAliveCountMax: String = DEFAULT_SERVER_ALIVE_COUNT_MAX.toString(),
    val authTimeoutSeconds: String = DEFAULT_AUTH_TIMEOUT_SECONDS.toString(),
    val autoReconnect: Boolean = true,
    val maxReconnectAttempts: String = DEFAULT_MAX_RECONNECT_ATTEMPTS.toString(),
    /** Blank inherits the app-wide reconnect delay from Settings. */
    val reconnectBackoffSeconds: String = "",
    val usePty: Boolean = true,
    val terminalType: String = DEFAULT_TERMINAL_TYPE,
    /** Blank matches the on-screen terminal, which is what almost every host wants. */
    val terminalColumns: String = "",
    /** Blank matches the on-screen terminal. */
    val terminalRows: String = "",
    val keyboardInteractiveAuth: Boolean = true,
    /**
     * Ask this host for SSH agent forwarding on its interactive shells.
     *
     * Off by default - the switch is a grant, and the form's helper text is where the user is told
     * what they are granting rather than only what they are getting. See [HostProfile.agentForwarding].
     */
    val agentForwarding: Boolean = false,
    /** Null follows the app-wide legacy-algorithm switch; true or false overrides it for this host. */
    val legacyAlgorithms: Boolean? = null,
    val hostKeyPolicy: HostKeyPolicy = HostKeyPolicy.ASK,
    /*
     * The four algorithm preference lists, as typed. Blank means "whatever the library negotiates",
     * which is what every host means until someone changes it, so blank is the default and not a
     * rendering of some default list - showing MINA's own twelve ciphers in the box would turn a host
     * with no opinion into a host that has pinned this build's list forever.
     */
    val ciphers: String = "",
    val kexAlgorithms: String = "",
    val macs: String = "",
    val hostKeyAlgorithms: String = "",
    /** Typed into the shell after it opens. Blank sends nothing. */
    val startupCommand: String = "",
    /** One `NAME=VALUE` per line, sent with the channel. Blank sends nothing. */
    val environment: String = "",
    /**
     * The host's saved port forwards, as rules rather than as text.
     *
     * Decoded on the way in and re-encoded on the way out, so the editor works with the list a user sees
     * and the column keeps the text a backup can carry. See `decodeForwardRules`.
     */
    val forwards: List<ForwardEntry> = emptyList(),
) {

    val serverAliveCountValid: Boolean = serverAliveCountMax.toIntOrNull() in SERVER_ALIVE_COUNT_RANGE
    val authTimeoutValid: Boolean = authTimeoutSeconds.toIntOrNull() in AUTH_TIMEOUT_RANGE
    val maxReconnectAttemptsValid: Boolean = maxReconnectAttempts.toIntOrNull() in MAX_RECONNECT_ATTEMPTS_RANGE
    val backoffValid: Boolean = reconnectBackoffSeconds.isBlank() ||
        reconnectBackoffSeconds.toIntOrNull() in RECONNECT_BACKOFF_RANGE
    val columnsValid: Boolean = terminalColumns.isBlank() || terminalColumns.toIntOrNull() in TERMINAL_COLUMNS_RANGE
    val rowsValid: Boolean = terminalRows.isBlank() || terminalRows.toIntOrNull() in TERMINAL_ROWS_RANGE

    /*
     * The algorithm lists, reviewed against what this device can actually do.
     *
     * Computed here rather than in the composable so that Save can refuse a list the connect would have
     * failed on: this is the whole point of validating in the form, and a check that lived only in a
     * `supportingText` would colour the field red and still let the host be saved. Held as the whole
     * review, not a boolean, because the field also needs the names it rejected.
     */
    val cipherReview: AlgorithmListReview = reviewAlgorithmList(AlgorithmKind.CIPHERS, ciphers)
    val kexReview: AlgorithmListReview = reviewAlgorithmList(AlgorithmKind.KEX, kexAlgorithms)
    val macReview: AlgorithmListReview = reviewAlgorithmList(AlgorithmKind.MACS, macs)
    val hostKeyAlgorithmReview: AlgorithmListReview = reviewAlgorithmList(AlgorithmKind.HOST_KEYS, hostKeyAlgorithms)

    /**
     * Length, separately from the reviews above.
     *
     * The reviews cannot answer this: an unsupported name already fails them, so a list of a thousand
     * *valid* names is the case that gets through - and it is the realistic one, because the chip rows in
     * the section append rather than replace and a paste is a paste. Bounded here as well as at the
     * importer, so the form cannot save what a restore would truncate. See [ALGORITHM_LIST_MAX_LENGTH].
     */
    val algorithmListsValid: Boolean = listOf(ciphers, kexAlgorithms, macs, hostKeyAlgorithms)
        .all { it.length <= ALGORITHM_LIST_MAX_LENGTH }

    /** Bounded so a pasted file cannot become a startup command. See [STARTUP_COMMAND_MAX_LENGTH]. */
    val startupCommandValid: Boolean = startupCommand.length <= STARTUP_COMMAND_MAX_LENGTH

    /**
     * Whether every line of [environment] is a variable the protocol can carry.
     *
     * Compared by count rather than by re-implementing the rule: [parseEnvironment] already knows which
     * lines are usable, so a line it drops is a line the user should be told about, and asking it is the
     * only way the message and the behaviour cannot drift apart.
     */
    val environmentValid: Boolean = environment.length <= ENVIRONMENT_MAX_LENGTH &&
        parseEnvironment(environment).size == environment.lines().count { line ->
            line.trim().let { it.isNotEmpty() && !it.startsWith('#') }
        }

    /** No more rules than a host may carry, so an editor cannot build a profile an import would truncate. */
    val forwardsValid: Boolean = forwards.size <= MAX_SAVED_FORWARDS

    /**
     * Whether these options may be saved.
     *
     * The keep-alive interval itself is not here: it belongs to [HostFormDraft], which has validated it
     * since before this section existed. This is only about the fields the section owns.
     */
    val isValid: Boolean = serverAliveCountValid && authTimeoutValid && maxReconnectAttemptsValid &&
        backoffValid && columnsValid && rowsValid &&
        cipherReview.isAcceptable && kexReview.isAcceptable && macReview.isAcceptable &&
        hostKeyAlgorithmReview.isAcceptable && algorithmListsValid &&
        startupCommandValid && environmentValid && forwardsValid

    /**
     * Whether anything here differs from the shipped defaults, which is what enables Reset.
     *
     * A getter, not a stored value: a stored one is computed in the constructor, and the constructor of
     * [DEFAULTS] itself runs while the companion is still initialising it, so that one instance would
     * compare itself against null and report "customised" forever - and [from] hands that very instance
     * to every unconfigured host.
     */
    val isDefault: Boolean get() = this == DEFAULTS

    /** Copies these options onto [profile]. Blank optional fields become their sentinels, not zeroes. */
    fun applyTo(profile: HostProfile): HostProfile = profile.copy(
        compression = compression,
        keepAliveEnabled = keepAliveEnabled,
        serverAliveCountMax = serverAliveCountMax.toIntOrNull()?.coerceIn(SERVER_ALIVE_COUNT_RANGE)
            ?: DEFAULT_SERVER_ALIVE_COUNT_MAX,
        authTimeoutSeconds = authTimeoutSeconds.toIntOrNull()?.coerceIn(AUTH_TIMEOUT_RANGE)
            ?: DEFAULT_AUTH_TIMEOUT_SECONDS,
        autoReconnect = autoReconnect,
        maxReconnectAttempts = maxReconnectAttempts.toIntOrNull()?.coerceIn(MAX_RECONNECT_ATTEMPTS_RANGE)
            ?: DEFAULT_MAX_RECONNECT_ATTEMPTS,
        reconnectBackoffSeconds = reconnectBackoffSeconds.toIntOrNull()?.coerceIn(RECONNECT_BACKOFF_RANGE)
            ?: INHERIT_RECONNECT_BACKOFF,
        usePty = usePty,
        terminalType = terminalType.trim().ifBlank { DEFAULT_TERMINAL_TYPE },
        terminalColumns = terminalColumns.toIntOrNull()?.coerceIn(TERMINAL_COLUMNS_RANGE) ?: 0,
        terminalRows = terminalRows.toIntOrNull()?.coerceIn(TERMINAL_ROWS_RANGE) ?: 0,
        keyboardInteractiveAuth = keyboardInteractiveAuth,
        agentForwarding = agentForwarding,
        legacyAlgorithms = legacyAlgorithms,
        // Blank becomes null, not "", because null is the column's "no opinion" and an empty list would
        // be a real instruction to propose nothing. See [Migrations.MIGRATION_12_13].
        ciphers = ciphers.trim().take(ALGORITHM_LIST_MAX_LENGTH).ifBlank { null },
        kexAlgorithms = kexAlgorithms.trim().take(ALGORITHM_LIST_MAX_LENGTH).ifBlank { null },
        macs = macs.trim().take(ALGORITHM_LIST_MAX_LENGTH).ifBlank { null },
        hostKeyAlgorithms = hostKeyAlgorithms.trim().take(ALGORITHM_LIST_MAX_LENGTH).ifBlank { null },
        startupCommand = startupCommand.trim().take(STARTUP_COMMAND_MAX_LENGTH),
        environment = environment.trim().take(ENVIRONMENT_MAX_LENGTH),
        savedForwards = encodeForwardRules(forwards),
        hostKeyPolicy = hostKeyPolicy,
    )

    companion object {

        val DEFAULTS = AdvancedHostOptions()

        /**
         * The options as [profile] has them, or [DEFAULTS] for a host being created.
         *
         * The two sentinel columns come back as blank fields rather than "0", because a user who opens
         * a host they never configured should see the same empty box a new host shows - reading `0` in
         * a column count and having to know it means "automatic" is a puzzle, not a setting.
         */
        fun from(profile: HostProfile?): AdvancedHostOptions {
            if (profile == null) return DEFAULTS
            return AdvancedHostOptions(
                compression = profile.compression,
                keepAliveEnabled = profile.keepAliveEnabled,
                serverAliveCountMax = profile.serverAliveCountMax.toString(),
                authTimeoutSeconds = profile.authTimeoutSeconds.toString(),
                autoReconnect = profile.autoReconnect,
                maxReconnectAttempts = profile.maxReconnectAttempts.toString(),
                reconnectBackoffSeconds = profile.reconnectBackoffSeconds
                    .takeIf { it != INHERIT_RECONNECT_BACKOFF }?.toString().orEmpty(),
                usePty = profile.usePty,
                terminalType = profile.terminalType,
                terminalColumns = profile.terminalColumns.takeIf { it != 0 }?.toString().orEmpty(),
                terminalRows = profile.terminalRows.takeIf { it != 0 }?.toString().orEmpty(),
                keyboardInteractiveAuth = profile.keyboardInteractiveAuth,
                agentForwarding = profile.agentForwarding,
                legacyAlgorithms = profile.legacyAlgorithms,
                ciphers = profile.ciphers.orEmpty(),
                kexAlgorithms = profile.kexAlgorithms.orEmpty(),
                macs = profile.macs.orEmpty(),
                hostKeyAlgorithms = profile.hostKeyAlgorithms.orEmpty(),
                startupCommand = profile.startupCommand,
                environment = profile.environment,
                forwards = decodeForwardRules(profile.savedForwards, profile.id),
                hostKeyPolicy = profile.hostKeyPolicy,
            )
        }
    }
}

/**
 * True when [value] is inside this range. Null - an unparseable or empty field - never is.
 *
 * Written out rather than relying on the stdlib's nullable `contains`, so that `in` reads the same for
 * every field here whether or not its blank state is meaningful.
 */
private operator fun IntRange.contains(value: Int?): Boolean = value != null && value >= first && value <= last
