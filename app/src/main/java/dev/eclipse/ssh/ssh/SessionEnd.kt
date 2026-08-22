package dev.eclipse.ssh.ssh

import org.apache.sshd.common.SshConstants

/**
 * Why an interactive shell session ended.
 *
 * The app used to know one thing about an ending: the shell's exit status, as an `Int?`. Every other
 * ending in existence arrived as `null` — a shell killed by a signal, a channel the server closed
 * without saying anything, a heartbeat that gave up, a socket reset, an `SSH_MSG_DISCONNECT` the
 * server sent with a reason attached, and the app's own pruner tidying up after a dead transport.
 * Two consequences followed from that one lost distinction, and both were reported by users:
 *
 *  - **Every ending read as an outage.** `shouldAutoReconnect` reconnects when no status was
 *    reported, because a dropped transport reports none. A shell that ended by itself reports none
 *    either, so a login whose shell exited immediately — `nologin`'s "This account is currently not
 *    available.", a `~/.profile` that fails, a `ForceCommand` that prints and returns, an
 *    administratively killed pty — printed its message, vanished, and came back as *Reconnecting…*
 *    five times before the tab settled on *Disconnected*. Nothing was wrong with the network and
 *    reconnecting could not have helped: the second shell exited exactly like the first.
 *  - **Every ending read the same.** The tab said "Disconnected from the remote host" whatever had
 *    happened, so neither the user nor a maintainer reading a bug report could tell which of those
 *    six causes they had. The information existed at the moment of the close — MINA hands over the
 *    throwable, the disconnect reason and the exit signal — and was thrown away one frame later.
 *
 * So an ending is a *reason*, not a status code. [describeSessionEnd] turns it into something a
 * person can act on and `shouldAutoReconnect` reads the same value to decide whether coming back
 * could possibly help.
 */
sealed interface SessionEnd {

    /**
     * The remote shell finished while its transport was still up.
     *
     * [status] is what the shell reported through `exit-status`, [signal] the name from `exit-signal`
     * when it was killed instead, and both are absent when the server closed the channel without
     * either — which OpenSSH does for a session whose command was refused. All three mean the same
     * thing for recovery: the far end is finished with this shell, and dialling another one would end
     * the same way.
     */
    data class ShellEnded(val status: Int?, val signal: String?) : SessionEnd

    /**
     * The transport was ended by an `SSH_MSG_DISCONNECT`, which carries a reason and a message.
     *
     * [byPeer] separates "the server hung up on us" from the rarer case of MINA deciding to end the
     * connection itself, which it does on a protocol or MAC error. The user needs to be told which:
     * the first is the server's decision — an idle timeout, a shutdown, an administrator — and the
     * second says the connection was corrupted on the way.
     */
    data class Disconnected(val reason: Int, val message: String?, val byPeer: Boolean) : SessionEnd

    /**
     * MINA raised an exception on the transport: the heartbeat gave up, the socket failed, a packet
     * did not parse. The earliest and most specific signal of a genuine drop.
     */
    data class TransportFailed(val cause: Throwable) : SessionEnd

    /** The transport went away with nothing said about why — a bare socket close. */
    data object TransportClosed : SessionEnd

    /**
     * The app released the channel itself.
     *
     * Reached two ways, and they are told apart by `TerminalChannel.endedDeliberately` rather than
     * here: the user closing a tab (deliberate, and the collector never reports it at all), and
     * [SshSessionStore.liveSession] pruning a channel whose session had already been found dead — an
     * ending that is a real outage even though it was the app that performed the last step.
     */
    data object Released : SessionEnd
}

/**
 * What the tab says when a connected session ended by itself, phrased for a person.
 *
 * The distinction that matters most here is between an ending the user caused (their shell exited)
 * and one that happened to them (the link died), because the two need opposite responses: the first
 * is finished and wants a fresh Connect, the second is worth waiting out. The second thing that
 * matters is *specificity* — "Disconnected from the remote host" is what the app said for years and
 * it is the one message that never helped anybody.
 *
 * Nothing here interpolates a credential. The inputs are an exit status, a signal name, a disconnect
 * reason code and MINA's own transport messages; a password or key never reaches any of them. Server
 * text is passed through because a server that says *why* it hung up ("Timeout, your session not
 * responding") is telling the user something no sentence this app could invent would.
 */
fun describeSessionEnd(end: SessionEnd): String = when (end) {
    is SessionEnd.ShellEnded -> when {
        end.signal != null -> "The remote shell was ended by SIG${end.signal.removePrefix("SIG")}"
        end.status == null -> "The remote shell closed the session. Connect again to start a new one."
        end.status == 0 -> "Session ended"
        else -> "Session ended (exit ${end.status})"
    }
    is SessionEnd.TransportFailed -> "Connection lost: ${transportMessage(end.cause)}"
    is SessionEnd.Disconnected -> {
        val reason = end.message?.trim()?.takeIf { it.isNotEmpty() }
            ?: SshConstants.getDisconnectReasonName(end.reason).takeIf { it.isNotBlank() }
            ?: "reason ${end.reason}"
        if (end.byPeer) "The server disconnected: $reason" else "Disconnected: $reason"
    }
    SessionEnd.TransportClosed, SessionEnd.Released -> "Disconnected from the remote host"
}

/**
 * The most specific sentence in a transport failure's cause chain.
 *
 * MINA wraps freely — an `SshException` around an `IOException` around a `SocketException` — and it is
 * usually the innermost one that names what happened ("Connection reset"). Bounded so a cyclic or
 * absurdly deep chain cannot turn a tab's error line into a wall of text, and it falls back to the
 * class name because an exception with no message at all still has to be reportable as *something*.
 */
private fun transportMessage(cause: Throwable): String {
    val chain = generateSequence(cause) { previous -> previous.cause?.takeIf { it !== previous } }
        .take(MAX_TRANSPORT_CAUSE_DEPTH)
        .toList()
    return chain.asReversed().firstNotNullOfOrNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
        ?: cause::class.java.simpleName
}

private const val MAX_TRANSPORT_CAUSE_DEPTH = 8

/**
 * Whether an ending is a *fault* - something went wrong - as opposed to a session that simply finished.
 *
 * This is the difference between [dev.eclipse.ssh.data.model.SessionConnectionState.ERROR] and
 * [dev.eclipse.ssh.data.model.SessionConnectionState.DISCONNECTED], and it exists because the two used
 * to be one state wearing one colour. Typing `exit` and having the network die under a running job
 * both left an amber tab reading "Disconnected", and only one of them is something the user has to do
 * something about.
 *
 * A shell that ran and returned is not a fault at any exit status - `exit 1` is an ordinary thing for a
 * command to do. A shell that was *killed* is, because nobody asked for that. Everything else here
 * happened to the session rather than in it: a server that hung up, a transport that failed, a socket
 * that vanished, or a channel the pruner released after finding its session already dead - deliberate
 * closes never reach this, since the collector returns early on them.
 */
val SessionEnd.isFault: Boolean
    get() = when (this) {
        is SessionEnd.ShellEnded -> signal != null
        is SessionEnd.Disconnected -> true
        is SessionEnd.TransportFailed -> true
        SessionEnd.TransportClosed -> true
        SessionEnd.Released -> true
    }
