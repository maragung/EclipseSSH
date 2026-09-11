package dev.eclipse.ssh.data.model

/**
 * STOPGAP for the RDP target line, to be deleted whole when the RDP viewer branch lands. What
 * is left here is only the two names the endpoint dialog and its callers were written against;
 * the codec that shipped beside it ([decodeRemoteDesktop] reading the `R:` line) now does the
 * work, so each function is a delegation and the duplicate `DEFAULT_RDP_PORT` that broke
 * main's build after the two branches merged is gone - the codec's is the one constant.
 *
 * The follow-up swaps each call to `decodeRemoteDesktop(...).rdp` /
 * `encodeRemoteDesktop(config.copy(rdp = ...))` directly and deletes this file.
 */

/**
 * The `R:` line in [column] as a target, or null when the host has no RDP endpoint saved.
 * Exactly the codec's read of the same line - kept as a name so the dialog's call sites do not
 * have to change before the viewer branch does the swap.
 */
fun decodeRdpTarget(column: String): RemoteDesktopTarget? =
    decodeRemoteDesktop(column).rdp

/**
 * [column] with its RDP line replaced by [target], or with none at all when [target] is null,
 * leaving every other protocol's line untouched - the codec's own write, V before R.
 */
fun withRdpTarget(column: String, target: RemoteDesktopTarget?): String =
    encodeRemoteDesktop(decodeRemoteDesktop(column).copy(rdp = target))
