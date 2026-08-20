package dev.eclipse.ssh.ssh

import org.apache.sshd.common.SshConstants
import org.apache.sshd.common.SshException

/**
 * Disconnect reasons (RFC 4253 §11.1) that a second attempt cannot change.
 *
 * Deliberately short. Most reasons *are* worth retrying — `SSH2_DISCONNECT_MAC_ERROR` and
 * `SSH2_DISCONNECT_CONNECTION_LOST` describe a transport that went wrong on the way, which is exactly
 * what a retry is for. These four describe a decision: no algorithm in common, a host key the user has
 * not accepted, a credential the server rejected, a user name it will not serve.
 */
private val FINAL_DISCONNECT_CODES = setOf(
    SshConstants.SSH2_DISCONNECT_KEY_EXCHANGE_FAILED,
    SshConstants.SSH2_DISCONNECT_HOST_KEY_NOT_VERIFIABLE,
    SshConstants.SSH2_DISCONNECT_NO_MORE_AUTH_METHODS_AVAILABLE,
    SshConstants.SSH2_DISCONNECT_ILLEGAL_USER_NAME,
)

/**
 * The messages SSHD raises locally, before any disconnect packet is exchanged, for the same three
 * decisions. Host key verification and algorithm negotiation both happen before that point, so the
 * code alone would miss them on some paths.
 */
private val FINAL_FAILURE_MARKERS = listOf(
    "Unable to negotiate",
    "Server key did not validate",
    "No more authentication methods available",
)

/** How far down a `cause` chain to look, so a self-referencing chain cannot spin here. */
private const val MAX_CAUSE_DEPTH = 8

/**
 * Whether a failed connect attempt can only fail the same way again.
 *
 * Retrying was unconditional, and for these rejections that was worse than useless. A mistyped
 * password was offered to the server three times, one and a half then three seconds apart — which is
 * how a typo locks an account or lands an address in `fail2ban` — and the user waited out the whole
 * delay to be told something the server had decided at the first attempt. An unverified host key spent
 * the same time re-raising a challenge that was already on screen. A failed algorithm negotiation
 * cannot come out differently at all.
 *
 * Note what is *not* here: timeouts, refused connections, resets, DNS failures, a proxy that hung up.
 * Those are the transient failures the retry loop exists for and they still get all three attempts.
 */
fun connectFailureIsFinal(error: Throwable): Boolean {
    if ((error as? SshException)?.disconnectCode in FINAL_DISCONNECT_CODES) return true
    val messages = generateSequence(error) { previous -> previous.cause?.takeIf { it !== previous } }
        .take(MAX_CAUSE_DEPTH)
        .mapNotNull { it.message }
        .joinToString(separator = " ")
    return FINAL_FAILURE_MARKERS.any { marker -> marker in messages }
}

/**
 * The connection error as the tab's status line should read it.
 *
 * A negotiation failure gets the one thing the user can act on put first. SSHD's own message is a
 * good diagnostic — it prints both algorithm lists — but it never mentions that this app has a switch
 * for precisely this server generation, and the setting is not something anyone would think to look
 * for while reading a list of cipher names. The original text is kept in full after the hint rather
 * than replaced: the lists are how someone works out *which* algorithm is missing.
 */
fun describeConnectFailure(error: Throwable?): String {
    val message = error?.message?.takeIf { it.isNotBlank() } ?: return "Connection failed"
    val negotiation = (error as? SshException)?.disconnectCode == SshConstants.SSH2_DISCONNECT_KEY_EXCHANGE_FAILED ||
        "Unable to negotiate" in message
    if (!negotiation) return message
    return "No algorithm in common with the server. If it is an old server, turn on " +
        "Legacy algorithms in Settings. ($message)"
}

/**
 * The markers a server leaves when it will not start the SFTP subsystem at all.
 *
 * Matched on the whole cause chain because the refusal surfaces differently depending on where it is
 * decided: MINA raises "Unknown channel type" or an `SSH_OPEN_UNKNOWN_CHANNEL_TYPE` failure when the
 * server has no subsystem factory, and a plain "subsystem" mention when it declines the request by
 * name. All of them mean the same thing to the user, and none of them means the shell is broken.
 */
private val SFTP_UNAVAILABLE_MARKERS = listOf(
    "subsystem",
    "unknown channel",
    "SSH_OPEN_UNKNOWN_CHANNEL_TYPE",
    "SSH_OPEN_ADMINISTRATIVELY_PROHIBITED",
)

/** Markers for a transport that went away before the second channel could be opened. */
private val SFTP_CLOSED_MARKERS = listOf(
    "session is closed",
    "session closed",
    "channel is closed",
    "closed session",
    "socket is closed",
    "connection reset",
    "connection lost",
)

/**
 * Why SFTP is unavailable, phrased for a person rather than for a log.
 *
 * Worth translating rather than passing MINA's text straight through, because the two common causes
 * read as catastrophes and are not. "Unknown channel type" means the account has a shell but no
 * `sftp-server`, which is a deliberate server configuration; a closed session means the connection
 * dropped in the window between the shell coming up and the file browser asking for a listing. Both
 * leave the terminal working, and the message has to say so — the alternative is a user who thinks
 * their session died.
 *
 * The original text is kept for anything unrecognised: a server-side "Permission denied" or a quota
 * message is more useful than any sentence this app could invent, and dropping it would leave the
 * failure undiagnosable. Nothing here interpolates a credential; the inputs are transport errors.
 */
fun describeSftpFailure(error: Throwable?): String {
    if (error == null) return "SFTP is unavailable"
    val chain = generateSequence(error) { previous -> previous.cause?.takeIf { it !== previous } }
        .take(MAX_CAUSE_DEPTH)
        .mapNotNull { it.message }
        .joinToString(separator = " ")
    return when {
        // A dropped transport is checked first, and the order is load-bearing. MINA wraps whatever
        // went wrong in "Failed to open subsystem sftp", so a session that died mid-handshake carries
        // the word "subsystem" too - and telling the user their server does not support SFTP when the
        // truth is that the network went away sends them to configure a server that was fine.
        SFTP_CLOSED_MARKERS.any { chain.contains(it, ignoreCase = true) } ->
            "The connection closed before SFTP could start."
        chain.contains("permission denied", ignoreCase = true) ->
            "The account is not allowed to use SFTP here. The terminal still works."
        SFTP_UNAVAILABLE_MARKERS.any { chain.contains(it, ignoreCase = true) } ->
            "This server does not offer SFTP for this account. The terminal still works."
        else -> error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
    }
}
