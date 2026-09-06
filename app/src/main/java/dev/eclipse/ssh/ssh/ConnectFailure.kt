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

/**
 * What MINA leaves behind when the TCP connection was accepted and then dropped before the SSH
 * transport existed.
 *
 * `MissingAttachedSessionException` reads like an internal accounting error and is nothing of the kind:
 * the client's connector attaches an SSH session to the socket as its first act, so "no session
 * attached" means the socket was already gone by then. Servers do this on purpose - `MaxStartups`
 * shedding load, `fail2ban` or tcpwrappers dropping an address, a load balancer with nothing behind it,
 * a firewall that allows the SYN and nothing after - and a rejected algorithm proposal can arrive this
 * way too when the server closes fast enough to beat its own disconnect packet.
 *
 * Recognised because MINA's own text for it is unusable on screen: it names an internal exception class
 * and prints both socket addresses, one of which is the device's own address on the local network.
 */
private val HANDSHAKE_CLOSED_MARKERS = listOf(
    "MissingAttachedSessionException",
    "No session attached",
)

/**
 * The messages a server leaves when what it refused was the *credential*, not the connection.
 *
 * Matched case-insensitively across the whole cause chain because the same refusal arrives in more
 * than one wrapper: OpenSSH sends `SSH2_DISCONNECT_NO_MORE_AUTH_METHODS_AVAILABLE` once the client
 * has exhausted its methods, a keyboard-interactive refusal often surfaces only as the server's own
 * "Permission denied", and a PAM stack reports "authentication failed" from deep inside MINA.
 */
private val CREDENTIAL_REJECTION_MARKERS = listOf(
    "no more authentication methods available",
    "authentication failed",
    "auth fail",
    "permission denied",
    "too many authentication failures",
)

/**
 * Whether the server was reached and turned the *login* away — the password, the key, or the right
 * to use either — as against a transport that never got that far.
 *
 * The distinction drives what the app does next: a rejected credential is the one failure whose fix
 * is a dialog rather than a retry, because the user is holding the thing that has to change. A
 * refused socket or a timeout must stay on the retry path this function excludes them from.
 */
fun isCredentialRejection(error: Throwable?): Boolean {
    if (error == null) return false
    if ((error as? SshException)?.disconnectCode == SshConstants.SSH2_DISCONNECT_NO_MORE_AUTH_METHODS_AVAILABLE) return true
    val messages = causeMessages(error).lowercase()
    return CREDENTIAL_REJECTION_MARKERS.any { marker -> marker in messages }
}

/** How far down a `cause` chain to look, so a self-referencing chain cannot spin here. */
private const val MAX_CAUSE_DEPTH = 8

/** Every message in a `cause` chain, flattened, so a marker is matched wherever it was raised. */
private fun causeMessages(error: Throwable): String =
    generateSequence(error) { previous -> previous.cause?.takeIf { it !== previous } }
        .take(MAX_CAUSE_DEPTH)
        .mapNotNull { it.message }
        .joinToString(separator = " ")

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
    val messages = causeMessages(error)
    return FINAL_FAILURE_MARKERS.any { marker -> marker in messages }
}

/**
 * Whether the connection got as far as the SSH handshake and was turned away *there*.
 *
 * The distinction this draws is between a host that could not be reached and a host that was reached and
 * said no. A refused port, an unknown name, a routing failure and a timeout all happen before a single
 * SSH byte is exchanged; a failed algorithm negotiation and a server that hangs up mid-handshake happen
 * after the socket is up, and mean the server - or something in front of it - made a decision.
 *
 * Written as a function rather than left implicit because two callers need the same judgement and would
 * otherwise reach it by matching MINA's wording independently: [describeConnectFailure], which has to
 * phrase it for a person, and the interoperability tests, which assert that a server configured to
 * refuse *did* refuse rather than that some unrelated failure happened to occur. A negotiation refusal
 * has two faces - the message when the server's disconnect packet arrives, and an early close when the
 * socket dies first - and treating either as "the connect failed somehow" would let a test pass while
 * proving nothing.
 */
fun failedDuringSshHandshake(error: Throwable?): Boolean {
    if (error == null) return false
    if ((error as? SshException)?.disconnectCode == SshConstants.SSH2_DISCONNECT_KEY_EXCHANGE_FAILED) return true
    val messages = causeMessages(error)
    return "Unable to negotiate" in messages || HANDSHAKE_CLOSED_MARKERS.any { it in messages }
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
    // Before the negotiation branch, because this case has no negotiation text to hint about, and it is
    // the one message in this file that must *not* be passed through: MINA's version names an internal
    // exception class and prints both socket addresses, and one of those is this device's own address on
    // the local network. Nothing is lost by replacing it - the original says only that a socket had no
    // session attached, which is not something a user can act on, whereas what happened is.
    if (HANDSHAKE_CLOSED_MARKERS.any { it in causeMessages(error) }) {
        return "The server accepted the connection and then closed it during the SSH handshake. " +
            "It may be refusing connections from this address, limiting how many start at once, " +
            "or sharing no algorithm with this client."
    }
    // Before the passthrough, because the credential case is the one whose raw text points the wrong
    // way: "No more authentication methods available" reads like a server configuration problem, and
    // "Permission denied" like a filesystem one, when both mean the password or key was refused and
    // the next move is the user's. The server's sentence is kept after the rewrite for diagnosis.
    if (isCredentialRejection(error)) {
        return "The server refused the login. The password or key may be wrong, or the account may " +
            "not be allowed to sign in this way. ($message)"
    }
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
