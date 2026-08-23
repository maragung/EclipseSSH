package dev.eclipse.ssh.ssh

/**
 * How long a session is held while the device has no network at all.
 *
 * A phone loses its network constantly and briefly: a lift, a tunnel, a platform, the second between
 * leaving one Wi-Fi cell and joining the next. None of those is a reason to end an SSH session, and
 * ending one costs the user their shell history, their working directory, whatever was half-typed at the
 * prompt, and - on a host that runs no multiplexer - the job that was running. A reconnect is not
 * equivalent to not having disconnected.
 *
 * So the app does nothing for a minute. The session stays [dev.eclipse.ssh.data.model.SessionConnectionState.CONNECTED]
 * because that is what it is: the socket is untouched, the pty is untouched, the buffer is untouched, and
 * if the same network comes back the connection resumes without a single packet having been sent about
 * it. Only the status line changes, to say the app is waiting.
 *
 * Sixty seconds because it is long enough to cover the ordinary gaps - a tunnel, a lift, a train
 * doorway - and short enough that a user who has walked out of the building is told inside a minute
 * rather than left looking at a terminal that has quietly stopped working. The number matches
 * ConnectBot's, which arrived at it the same way and over rather more years of use.
 *
 * Nothing is *lost* by holding, either: while the device has no route, a redial could not open a socket
 * and a probe could not reach the host, so the alternative to waiting is not a faster recovery but a
 * dead session plus a reconnect ladder burning its attempt budget against flight mode.
 */
internal const val NETWORK_GRACE_MS = 60_000L

/**
 * What to do with a held session once the network is back.
 *
 * Three answers rather than two, because "cannot tell" is a real and common answer and collapsing it
 * into either of the other two is a bug: into [DROP] it kills working sessions, into [RESUME] it leaves
 * a terminal that looks connected and swallows keystrokes.
 */
internal enum class GraceOutcome {
    /** At least one address survived, so the socket may have too. Resume silently; send nothing. */
    RESUME,

    /** Every address changed. The socket is bound to one that no longer exists; it is provably gone. */
    DROP,

    /** Not enough information. Ask the session directly instead of concluding anything. */
    UNKNOWN,
}

/**
 * Compares the local addresses from before a network gap with the ones after it.
 *
 * The test ConnectBot uses, and it works because of what an SSH session actually is: a TCP connection
 * bound to one local address. If that address is still assigned to a live interface, the kernel still
 * has the socket and the far end may never have noticed the gap - a Wi-Fi network that dropped for eight
 * seconds and came back with the same DHCP lease leaves connections intact, which is why resuming
 * silently is not optimism but the common case. If *no* address survived, the socket's address is gone
 * and nothing can be done with it; the session is over whatever the app's data structures say.
 *
 * An empty set on either side is [GraceOutcome.UNKNOWN] rather than [GraceOutcome.DROP], and that choice
 * is deliberate in the same direction as [dev.eclipse.ssh.background.NetworkMonitor.online] failing open.
 * An empty "before" means the addresses were never captured; an empty "after" means the network the
 * platform just announced has not finished configuring an address, or that `NetworkInterface` would not
 * answer on this device. Either way the honest answer is that the comparison did not run, and the correct
 * response to that is the question the app already has a way to ask - a liveness probe - not a verdict.
 *
 * Pure, so every case is a unit test rather than something to be reasoned about while holding a phone in
 * a lift.
 */
internal fun graceOutcome(before: Set<String>, after: Set<String>): GraceOutcome = when {
    before.isEmpty() || after.isEmpty() -> GraceOutcome.UNKNOWN
    before.any { it in after } -> GraceOutcome.RESUME
    else -> GraceOutcome.DROP
}

/**
 * Whether a network *replacement* is proof on its own that every live session is finished.
 *
 * The fast path for the case the user described as switching between Wi-Fi and mobile data. When not one
 * address is shared between the two networks, no socket bound to the old one can still work, and probing
 * is a formality that costs two six-second deadlines per session before reaching the conclusion already
 * available. Dropping straight away turns twelve seconds of a terminal that looks fine into none.
 *
 * The inverse is emphatically *not* true, which is why this returns a bare "proven" rather than a
 * verdict: an overlap does not mean the session survived. Re-joining the same access point, or a network
 * that hands back the same lease, produces an identical address set with sockets that are nonetheless
 * dead. That case is precisely what the probe exists to catch, so an overlap - and an unknown - both fall
 * through to probing.
 */
internal fun migrationProvesLoss(before: Set<String>, after: Set<String>): Boolean =
    graceOutcome(before, after) == GraceOutcome.DROP
