package dev.eclipse.ssh.data.model

import java.util.UUID

data class HostProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val username: String,
    val port: Int = 22,
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    val group: String = "Personal",
    val tags: List<String> = emptyList(),
    val isFavorite: Boolean = false,
    val lastConnectedAt: Long? = null,
    val fingerprint: String? = null,
    val proxyType: ProxyType = ProxyType.NONE,
    val proxyJump: String? = null,
    val socksHost: String? = null,
    val socksPort: Int = 1080,
    val socksUsername: String? = null,
    val socksPassword: String? = null,
    val accentColor: Long? = null,
    /**
     * How long the TCP connect, key exchange and authentication may each take before the attempt is
     * abandoned. Was hard-coded to 15s inside [dev.eclipse.ssh.ssh.SshConnectionManager], which is
     * far too short for a satellite or heavily loaded link and far too long for a fast one the user
     * knows is down.
     */
    val connectTimeoutSeconds: Int = DEFAULT_CONNECT_TIMEOUT_SECONDS,
    /**
     * Per-host keep-alive (SSH keepalive request) in seconds, or null to follow
     * [AppSettings.keepAliveSeconds]. A host behind an aggressive NAT needs a shorter interval than
     * the global default, and a metered link needs a longer one.
     */
    val keepAliveSeconds: Int? = null,
    /**
     * Open and log in to an SFTP session automatically once the SSH handshake succeeds, so the Files
     * tab is already usable when the shell appears.
     *
     * Defaults to on because that is what the app has always done — the connect path listed the
     * remote home directory unconditionally — and turning it off for existing profiles on upgrade
     * would look like the file browser had broken. Off means SSH only: nothing opens a second channel
     * on this host until the user asks for a listing, which is what a locked-down account that permits
     * a shell but no SFTP subsystem needs, and what saves the round trip on a metered link.
     */
    val autoLoginSftp: Boolean = DEFAULT_AUTO_LOGIN_SFTP,
    /**
     * Offer zlib compression during the key exchange.
     *
     * Worth it on a slow or metered link, where a screenful of text compresses to a fraction of its
     * size, and not worth it otherwise: it costs CPU on both ends and gains nothing on output that is
     * already dense, such as a file transfer or a binary dump. Off by default, which is also what
     * OpenSSH does. The server has to agree, and one that does not simply negotiates `none`.
     */
    val compression: Boolean = false,
    /**
     * Send SSH keep-alives at all.
     *
     * On (the default) the client asks the server "are you still there?" every
     * [keepAliveSeconds] and closes the session after [serverAliveCountMax] unanswered asks, which is
     * what holds a NAT mapping open and what notices a server that has gone away. Off means the app
     * sends nothing and, critically, that it also stops *concluding* anything from silence: the
     * client-side idle timeout is disabled with it, because an idle timeout that outlives the
     * heartbeat is a timer that kills healthy sessions. Turn it off for a link that bills by the
     * packet, or a middlebox that dislikes the request; the cost is that a dead peer is only noticed
     * when something is typed.
     */
    val keepAliveEnabled: Boolean = true,
    /**
     * Unanswered keep-alives tolerated before the session is closed, OpenSSH's `ServerAliveCountMax`.
     *
     * Together with [keepAliveSeconds] this is how long a broken link takes to notice: interval times
     * count. Three at thirty seconds - the default - is ninety seconds of patience, which survives a
     * lift, a tunnel and a garbage-collection pause on the server. Lower it to notice faster at the
     * risk of ending a session over one dropped packet.
     */
    val serverAliveCountMax: Int = DEFAULT_SERVER_ALIVE_COUNT_MAX,
    /**
     * How long authentication may take once the transport is up, separately from
     * [connectTimeoutSeconds].
     *
     * These are different waits and they used to share one number. A server that answers its socket
     * instantly can still spend a minute in a PAM stack, an LDAP lookup or a hardware-token prompt,
     * and a user who shortened the connect timeout because the *host* is usually fast was also
     * shortening the time their own two-factor prompt had to be answered in.
     */
    val authTimeoutSeconds: Int = DEFAULT_AUTH_TIMEOUT_SECONDS,
    /**
     * Bring the session back by itself when the transport dies for a reason that is not the user's
     * doing.
     *
     * Off means a dropped session stays dropped, showing what ended it, until Reconnect is pressed -
     * which is what a host on a metered link or one that must never be dialled unattended needs. It
     * has no effect on a session the user closed, an authentication failure or a shell that exited:
     * none of those reconnect either way.
     */
    val autoReconnect: Boolean = true,
    /**
     * Consecutive automatic attempts before the app stops trying and reports the reason.
     *
     * The allowance comes back when a session proves it can stay up, so this bounds a flap rather
     * than a lifetime.
     */
    val maxReconnectAttempts: Int = DEFAULT_MAX_RECONNECT_ATTEMPTS,
    /**
     * The first reconnect wait in seconds; each further attempt doubles it, plus jitter, up to five
     * minutes.
     *
     * Zero inherits the app-wide reconnect delay from Settings, the same way a null
     * [keepAliveSeconds] does. A per-host number that defaulted to the *same* value as the global
     * setting would not be a default at all - it would silently override it, so a user who raised the
     * app-wide delay to a minute would find every existing host still retrying after five seconds and
     * nothing on screen explaining why.
     */
    val reconnectBackoffSeconds: Int = INHERIT_RECONNECT_BACKOFF,
    /**
     * Ask the server for a pseudo-terminal.
     *
     * On for anything interactive - it is what makes `top`, `vim`, job control, line editing and
     * colour work, because without it the far end has no terminal to describe. Off runs the login
     * shell with its input and output on plain pipes, which is what a locked-down account that
     * refuses a pty needs, and what makes a shell behave like a script: no prompt, no echo of the
     * kind a terminal draws, and programs that require a terminal refuse to start.
     */
    val usePty: Boolean = true,
    /**
     * The `TERM` value sent with the pty request, which is how the server decides what escape
     * sequences this terminal understands.
     *
     * `xterm-256color` is the default and matches what the emulator actually implements. Lowering it
     * to `xterm` or `vt100` is for a server whose terminfo database has never heard of the 256-colour
     * entry, where the symptom is a program that refuses to start or draws in the wrong colours.
     */
    val terminalType: String = DEFAULT_TERMINAL_TYPE,
    /**
     * Columns to request for the pty, or 0 to use whatever fits the screen.
     *
     * Forcing a width is for output that has to line up with something - a table, a log format, a
     * diff - at a size the phone would not have chosen. The screen still shows as much as fits and
     * pans sideways for the rest.
     */
    val terminalColumns: Int = 0,
    /** Rows to request for the pty, or 0 to use whatever fits the screen. */
    val terminalRows: Int = 0,
    /**
     * Allow the keyboard-interactive method during authentication.
     *
     * On by default because it is how most servers ask for a password behind PAM, and how every
     * server asks for a second factor. Turning it off restricts this host to public key and plain
     * password, which is the way out of a PAM stack that prompts in a loop. Ignored when the host's
     * [authMethod] *is* keyboard-interactive, since that would leave nothing to authenticate with.
     */
    val keyboardInteractiveAuth: Boolean = true,
    /**
     * Per-host override of the global legacy-algorithm switch, or null to follow it.
     *
     * The global switch is all-or-nothing, so one elderly switch or IPMI card that only speaks
     * `ssh-rsa` and `diffie-hellman-group14-sha1` forced every other host into the same weakened set.
     * Setting it here keeps the deprecated cryptography on the one host that needs it.
     */
    val legacyAlgorithms: Boolean? = null,
    /**
     * What to do when this host presents a key that is not in known-hosts.
     *
     * A *changed* key is refused under every policy, which is the whole point of pinning: only the
     * first sighting is a question, and only [HostKeyPolicy] decides who answers it.
     */
    val hostKeyPolicy: HostKeyPolicy = HostKeyPolicy.ASK,
) {
    /**
     * Redacts [socksPassword].
     *
     * A `data class` generates a `toString` that prints every property, and this one carries a proxy
     * password. That makes leaking it one string interpolation away — a log line, an exception
     * message, a snackbar built from `"$host"`, a crash report that dumps state. Nothing in the app
     * does that today, which is precisely why it is worth closing now: the next person to add
     * `Log.d(TAG, "connecting to $host")` should not be able to publish a credential by accident.
     * Every other field is either public information or already on screen.
     */
    override fun toString(): String = "HostProfile(id=$id, name=$name, host=$host, username=$username, " +
        "port=$port, authMethod=$authMethod, group=$group, tags=$tags, isFavorite=$isFavorite, " +
        "lastConnectedAt=$lastConnectedAt, fingerprint=$fingerprint, proxyType=$proxyType, " +
        "proxyJump=$proxyJump, socksHost=$socksHost, socksPort=$socksPort, socksUsername=$socksUsername, " +
        "socksPassword=${if (socksPassword == null) "null" else "***"}, accentColor=$accentColor, " +
        "connectTimeoutSeconds=$connectTimeoutSeconds, keepAliveSeconds=$keepAliveSeconds, " +
        "autoLoginSftp=$autoLoginSftp, compression=$compression, keepAliveEnabled=$keepAliveEnabled, " +
        "serverAliveCountMax=$serverAliveCountMax, authTimeoutSeconds=$authTimeoutSeconds, " +
        "autoReconnect=$autoReconnect, maxReconnectAttempts=$maxReconnectAttempts, " +
        "reconnectBackoffSeconds=$reconnectBackoffSeconds, usePty=$usePty, terminalType=$terminalType, " +
        "terminalColumns=$terminalColumns, terminalRows=$terminalRows, " +
        "keyboardInteractiveAuth=$keyboardInteractiveAuth, legacyAlgorithms=$legacyAlgorithms, " +
        "hostKeyPolicy=$hostKeyPolicy)"

    companion object {
        /**
         * The default for [autoLoginSftp], named so the form, the Room migration and the vault
         * importer cannot drift apart — three places that each have to answer "what does a host
         * that never said anything about SFTP do?" with the same word.
         */
        const val DEFAULT_AUTO_LOGIN_SFTP = true
    }
}

/** Bounds shared by the UI validation and the engine, so neither can accept what the other rejects. */
const val DEFAULT_CONNECT_TIMEOUT_SECONDS = 15
val CONNECT_TIMEOUT_RANGE = 5..300
val KEEP_ALIVE_RANGE = 5..600

/**
 * Bounds and defaults for the advanced per-host settings.
 *
 * Every one of these is read in at least three places - the form that edits it, the Room row that
 * stores it and the engine that applies it - and a profile can also arrive from an imported vault
 * backup that no form ever validated. Naming the bounds once is what lets the engine clamp without
 * having to trust its input and the form reject without having to guess what the engine will do.
 */
const val DEFAULT_AUTH_TIMEOUT_SECONDS = 30
val AUTH_TIMEOUT_RANGE = 5..600

const val DEFAULT_SERVER_ALIVE_COUNT_MAX = 3
val SERVER_ALIVE_COUNT_RANGE = 1..10

const val DEFAULT_MAX_RECONNECT_ATTEMPTS = 5
val MAX_RECONNECT_ATTEMPTS_RANGE = 1..20

/** [HostProfile.reconnectBackoffSeconds] value meaning "use the app-wide delay from Settings". */
const val INHERIT_RECONNECT_BACKOFF = 0
val RECONNECT_BACKOFF_RANGE = 1..60

/**
 * The `TERM` the emulator actually implements: 256 colours, DEC private modes, xterm modifier
 * parameters and bracketed paste.
 */
const val DEFAULT_TERMINAL_TYPE = "xterm-256color"

/**
 * The terminal types offered in the form, rather than a free text field.
 *
 * A typo in `TERM` fails in a way nobody connects to the setting: the server looks the name up in
 * terminfo, finds nothing, and either refuses to run `vim` or draws with the wrong escapes. Every
 * entry here exists in a stock terminfo database.
 */
val TERMINAL_TYPE_CHOICES = listOf(
    "xterm-256color",
    "xterm",
    "screen-256color",
    "screen",
    "vt100",
    "linux",
    "dumb",
)

/**
 * A forced pty size, where 0 means "whatever fits the screen".
 *
 * The lower bounds are the smallest sizes a shell is usable at rather than protocol limits: a pty 4
 * columns wide is accepted by every server and by nothing a user would want to read.
 */
val TERMINAL_COLUMNS_RANGE = 20..500
val TERMINAL_ROWS_RANGE = 5..200

/** True when a forced pty size is meaningful, i.e. anything but the "match the screen" sentinel. */
fun Int.isForcedTerminalSize(): Boolean = this != 0

/** Default TCP ports, named so the form, the importer and the entity cannot drift apart. */
const val DEFAULT_SSH_PORT = 22
const val DEFAULT_SOCKS_PORT = 1080

/** A TCP port. 0 is reserved and would fail to bind, so the usable range starts at 1. */
val PORT_RANGE = 1..65535

/**
 * A SHA-256 host key fingerprint exactly as the app prints it: `SHA256:` followed by unpadded base64
 * of a 32-byte digest, so 43 characters and no `=`. `KnownHostsVerifier.fingerprint` produces this
 * shape and it is what the host-key prompt shows, so a pin can be copied out of the prompt and pasted
 * straight back into the form.
 *
 * Shared deliberately. The Add Host form validates a typed pin against it and the vault importer
 * validates an imported one, and if those two ever drifted apart the result would be silent: a
 * fingerprint the form accepts but the importer drops un-pins a host on restore, with nothing shown.
 */
val HOST_KEY_FINGERPRINT_PATTERN = Regex("^SHA256:[A-Za-z0-9+/]{43}$")

fun HostProfile.matchesQuery(query: String): Boolean {
    val normalized = query.trim()
    if (normalized.isBlank()) return true
    return sequenceOf(name, host, username, group)
        .plus(tags.asSequence())
        .any { it.contains(normalized, ignoreCase = true) }
}

enum class AuthMethod(val label: String) {
    PASSWORD("Password"),
    SSH_KEY("SSH key"),
    KEYBOARD_INTERACTIVE("Keyboard interactive"),
}

/**
 * What happens the first time a host presents a key that known-hosts has never seen.
 *
 * A key that *changed* is refused whatever this says - that is the attack pinning exists to catch,
 * and no per-host setting turns it off.
 */
enum class HostKeyPolicy(val label: String, val detail: String) {
    ASK(
        label = "Ask me",
        detail = "Show the fingerprint and wait for an answer before connecting.",
    ),
    ACCEPT_NEW(
        label = "Trust on first use",
        detail = "Pin the first key seen without asking, and refuse it if it ever changes.",
    ),
    STRICT(
        label = "Refuse unknown keys",
        detail = "Connect only to a key that is already pinned. Nothing is ever asked or stored.",
    ),
}

enum class ProxyType(val label: String) {
    NONE("Direct connection"),
    PROXY_JUMP("ProxyJump"),
    SOCKS5("SOCKS5 proxy"),
    HTTP_CONNECT("HTTP CONNECT proxy"),
}

enum class ForwardType(val label: String) {
    LOCAL("Local"),
    REMOTE("Remote"),
    DYNAMIC("Dynamic (SOCKS5)"),
}

data class ForwardEntry(
    val id: String = UUID.randomUUID().toString(),
    val type: ForwardType,
    val localPort: Int,
    val remoteHost: String? = null,
    val remotePort: Int? = null,
    val hostId: String? = null,
)

data class Snippet(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val command: String,
)

data class ServerStats(
    val hostname: String,
    val uptime: String,
    val loadAverage: String,
    val memoryUsed: String,
    val memoryTotal: String,
    val diskUsed: String,
    val diskTotal: String,
)

data class SessionTab(
    val id: String = UUID.randomUUID().toString(),
    val hostId: String,
    val title: String,
    val state: SessionConnectionState = SessionConnectionState.IDLE,
    val lastError: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    /**
     * True while the device has no network and this session is being held through the gap.
     *
     * Deliberately *not* a [SessionConnectionState]. The session is connected - socket, pty and buffer
     * all untouched - and the app is waiting a minute to see whether the network comes back before it
     * concludes anything, so any state that implied otherwise would be false, and would also start the
     * tab's own recovery machinery against a session that has nothing wrong with it. What is true is a
     * fact about the phone, not about the session, so it travels beside the state and changes only what
     * the status line says. See `NETWORK_GRACE_MS`.
     */
    val networkHeld: Boolean = false,
    /** Where this session's SFTP channel is — see [SftpSessionState]. */
    val sftpState: SftpSessionState = SftpSessionState.IDLE,
    /**
     * Why SFTP is unavailable, in a sentence fit to show the user, or null.
     *
     * Kept on the tab rather than left to the transient snackbar because a session can outlive the
     * message by hours: without it, a Files tab that lists nothing has no explanation on screen and
     * looks like an empty home directory.
     */
    val sftpError: String? = null,
)

/**
 * Where a session's SFTP channel is, which is a different question from where its shell is.
 *
 * A session is [SessionConnectionState.CONNECTED] as soon as the shell is up. SFTP is a second
 * channel on that same authenticated transport, and a server may serve one and refuse the other — a
 * locked-down account with a shell but no `sftp-server` subsystem is a normal configuration, not an
 * error in this app — so the two states have to be reportable separately.
 */
enum class SftpSessionState {
    /** Nothing has been attempted yet: the session is still connecting, or it just came up. */
    IDLE,

    /** [HostProfile.autoLoginSftp] is off for this host, so the app connected the shell only. */
    DISABLED,

    /** An SFTP channel is being opened and the remote home directory listed. */
    CONNECTING,

    /** SFTP answered: the file browser is usable for this host. */
    READY,

    /** SFTP could not be started. [SessionTab.sftpError] says why; the shell is unaffected. */
    FAILED,
}

data class HostKeyChallenge(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val changed: Boolean,
)

/**
 * Where one session is in its life, as a state machine with exactly one state at a time.
 *
 * The whole app reads a session's state from here - the tab, the full-screen status line, the
 * notification, the decision whether to offer a keyboard - so the set has to be able to express every
 * situation a session can actually be in. It used to have four members, and the two it was missing
 * were the two the user most needed to be told apart:
 *
 *  - a handshake that has completed and is now *offering credentials* looked identical to one still
 *    waiting for a TCP connection, so a host that answers instantly and then spends ten seconds on a
 *    slow PAM stack said "Connecting…" the whole time and looked hung;
 *  - a session that ended because something went wrong looked identical to one that ended because the
 *    shell exited. Both said "Disconnected", in the same colour, with the same affordances - so the
 *    two cases that call for opposite reactions from the user (fix something, versus nothing is wrong)
 *    were indistinguishable at a glance.
 *
 * [CHANNEL_PTY_INITIALIZING] is the third of those, and the one this app was most often wrong about.
 * Authentication succeeding is not the session being ready: a channel has to be opened on the transport
 * and a pty allocated on it, and both of those talk to the server. A failure there used to be reported
 * with the words the reconnect ladder uses - the attempt loop sets RECONNECTING and "Retrying
 * connection…" for any failed attempt - so the single most-reported symptom of this app, *log in
 * successfully and watch the tab immediately say Reconnecting*, was in part this phase failing under a
 * label that described something else entirely. Given its own state, the phase is nameable in the UI,
 * distinguishable in the trace, and no longer borrows another state's vocabulary.
 *
 * The transitions, and nothing else, are legal:
 *
 * ```
 * IDLE ─▶ CONNECTING ─▶ AUTHENTICATING ─▶ CHANNEL_PTY_INITIALIZING ─▶ CONNECTED ─▶ DISCONNECTED ─▶ (IDLE)
 *           │  │             │                    │                      │
 *           │  └─────────────┴────────────────────┴─▶ ERROR ◀────────────┤ (transport failed, exhausted)
 *           │                                                            │
 *           └──▶ RECONNECTING ◀──────────────────────────────────────────┘ (a genuine drop, backing off)
 *                     │
 *                     └──▶ CONNECTING (the next attempt)
 * ```
 *
 * A reconnect is deliberately not broken down this way: it stays RECONNECTING from the drop to the
 * shell, because which phase attempt three is in matters less to a user than the fact that the app is
 * still trying. The network grace period is not a state either - a session holding a link that went
 * away is still CONNECTED, with the status line saying so.
 */
enum class SessionConnectionState {
    /** A tab exists and nothing has been attempted on it yet. */
    IDLE,

    /** Dialling: TCP, the proxy if there is one, and the SSH key exchange. */
    CONNECTING,

    /** The transport is up and the server is being offered credentials. */
    AUTHENTICATING,

    /**
     * Logged in, and opening the shell: a channel on the transport, then a pty on the channel.
     *
     * Brief on a healthy server and not brief at all on a loaded one, which is the case worth naming -
     * `MaxSessions` reached, a server out of ptys, or a `ForceCommand` that refuses one. Every second of
     * it used to read as "Connecting…" or, on a failure, as "Reconnecting…".
     */
    CHANNEL_PTY_INITIALIZING,

    /** Authenticated, with a pty open and a shell on the other end of it. */
    CONNECTED,

    /** The session dropped and an attempt to bring it back is armed or in flight. */
    RECONNECTING,

    /** Over, for a reason that is nobody's fault: the shell exited, or the user disconnected. */
    DISCONNECTED,

    /** Over because something failed: refused credentials, an unreachable host, a dead transport. */
    ERROR,
}

/** Whether a session in this state has a shell that can be typed into. */
val SessionConnectionState.isLive: Boolean
    get() = this == SessionConnectionState.CONNECTED

/** Whether this state is one a session is *working through* rather than resting in. */
val SessionConnectionState.isBusy: Boolean
    get() = this == SessionConnectionState.CONNECTING ||
        this == SessionConnectionState.AUTHENTICATING ||
        this == SessionConnectionState.CHANNEL_PTY_INITIALIZING ||
        this == SessionConnectionState.RECONNECTING

/**
 * Whether the session is over and only the user can restart it.
 *
 * The test for "offer Reconnect", and the reason it is here rather than spelled out at each call site:
 * every one of those sites compared against [SessionConnectionState.DISCONNECTED] alone, so adding
 * [SessionConnectionState.ERROR] to the enum without this would have quietly withdrawn the Reconnect
 * button from the sessions that need it most.
 */
val SessionConnectionState.isEnded: Boolean
    get() = this == SessionConnectionState.DISCONNECTED || this == SessionConnectionState.ERROR

/**
 * Whether the server has already accepted this session's credentials.
 *
 * The test a late phase callback has to pass before it is allowed to write anything. The callback that
 * reports HANDSHAKE and AUTHENTICATE crosses threads, so an AUTHENTICATING dispatched by a login that
 * finished in milliseconds can be delivered *after* the shell it led to is already open - and telling a
 * session with a working pty that it is still logging in is a state machine running backwards. Guarding
 * on [isLive] alone left the same hole one step earlier, since
 * [SessionConnectionState.CHANNEL_PTY_INITIALIZING] sits between the two.
 */
val SessionConnectionState.isPastAuthentication: Boolean
    get() = this == SessionConnectionState.CHANNEL_PTY_INITIALIZING || this == SessionConnectionState.CONNECTED

data class TransferItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val direction: TransferDirection,
    val hostName: String,
    val progress: Float,
    val status: TransferStatus,
    val sizeLabel: String,
    val hostId: String? = null,
    val remotePath: String? = null,
    val localUri: String? = null,
    val transferredBytes: Long = 0,
    val totalBytes: Long? = null,
    val retryCount: Int = 0,
    val scheduledAt: Long? = null,
    val repeatMinutes: Long? = null,
)

enum class TransferDirection(val label: String) { UPLOAD("Upload"), DOWNLOAD("Download") }
enum class TransferStatus { RUNNING, PAUSED, COMPLETE, FAILED, QUEUED }
enum class SyncDirection(val label: String) { LOCAL_TO_REMOTE("Local → Remote"), REMOTE_TO_LOCAL("Remote → Local") }

data class AppSettings(
    val isLocked: Boolean = false,
    val biometricUnlock: Boolean = true,
    val darkTheme: Boolean = true,
    val clearClipboardAfterSeconds: Int = 30,
    val keepAliveSeconds: Int = 30,
    val reconnectBaseSeconds: Int = 5,
    val terminalFontSize: Int = 13,
    /**
     * Whether the row of keys a phone keyboard does not have is on screen.
     *
     * Persisted, because hiding it is a deliberate trade the user made - three more rows of output in
     * exchange for two taps to get Esc back - and having to make it again after every launch, rotation
     * or reconnect would make the row feel broken rather than optional. Collapsed still leaves a handle
     * on screen: a terminal with no reachable Esc and no visible way to get one would be a dead end.
     */
    val terminalKeyRowVisible: Boolean = true,
    /**
     * The narrowest terminal the pty is ever told it has, whatever the screen can show.
     *
     * 80 by default, and that number is not arbitrary: it is the width command-line output has been
     * formatted for since the punch card, and the width every program still assumes when it decides
     * where to put a column break. A phone fits somewhere near forty-five columns of legible
     * monospace, and telling the server forty-five is what makes it do the cutting - `ls -l` loses a
     * column, a URL or a hash or a long path folds at whatever character lands on the margin, and a
     * table's alignment goes with it. The break arrives as a newline in the stream, indistinguishable
     * from one that was meant, so nothing downstream can undo it: not the display, not a copy, not a
     * saved log.
     *
     * Asking for 80 instead keeps the lines whole and moves the only real cost - that the screen
     * cannot show all of them at once - to something reversible: the grid pans sideways under the
     * finger. 0 means "exactly what fits on screen", which is the older behaviour and the right choice
     * for anyone who would rather never pan.
     */
    val terminalMinColumns: Int = 80,
    val pinEnabled: Boolean = false,
    val legacyAlgorithms: Boolean = false,
    val terminalTheme: String = TerminalTheme.DARK.name,
    /**
     * Adds `FLAG_SECURE` to the app's window: no screenshots, no screen recording, and no thumbnail
     * of the app in the recents overview.
     *
     * Off by default, and deliberately so. A terminal client is a tool people screenshot — pasting a
     * stack trace or a `df -h` into a ticket is ordinary work, and `FLAG_SECURE` blocks the user's own
     * screenshots as bluntly as anyone else's. What it protects against is the snapshot the system
     * takes when the app goes to the background: whatever the terminal was showing sits in the recents
     * overview, in front of anyone who picks the phone up. Whether that matters depends on what the
     * user does with their sessions, so the choice is theirs rather than the app's.
     */
    val blockScreenshots: Boolean = false,
)

/**
 * The terminal's colour pairs, chosen from a dropdown in Settings.
 *
 * [label] is what that dropdown shows, so it is kept to two words: the control is a compact one on a
 * settings row that also carries a title and a subtitle, and a long label there is what pushes a row
 * into wrapping or clipping on a narrow screen.
 *
 * Persisted by [name], never by ordinal or index — [AppSettings.terminalTheme] holds the constant's
 * name, and a name that no longer exists falls back to [DARK] at every read site. That is what makes
 * this list safe to extend and reorder.
 */
enum class TerminalTheme(val label: String, val background: Long, val foreground: Long) {
    DARK("Dark", 0xFF090D16, 0xFFB3C1D9),
    LIGHT("Light", 0xFFF6F7FB, 0xFF1B2434),
    AMBER("Amber", 0xFF101008, 0xFFFFC966),
    GREEN("Green", 0xFF03160E, 0xFF7CE8A8),
    NORD("Nord", 0xFF2E3440, 0xFFD8DEE9),
    SOLARIZED_DARK("Solarized dark", 0xFF002B36, 0xFF93A1A1),
    SOLARIZED_LIGHT("Solarized light", 0xFFFDF6E3, 0xFF586E75),
    MONOKAI("Monokai", 0xFF272822, 0xFFF8F8F2),
    HIGH_CONTRAST("High contrast", 0xFF000000, 0xFFFFFFFF);

    companion object {
        /** The theme [name] refers to, or [DARK] for a name written by a newer or older build. */
        fun named(name: String?): TerminalTheme = entries.firstOrNull { it.name == name } ?: DARK
    }
}
