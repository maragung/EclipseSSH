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
     * Per-host keep-alive (SSH IGNORE heartbeat) in seconds, or null to follow
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
        "autoLoginSftp=$autoLoginSftp)"

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
    val state: SessionConnectionState = SessionConnectionState.CONNECTING,
    val lastError: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
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

enum class SessionConnectionState { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED }

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
