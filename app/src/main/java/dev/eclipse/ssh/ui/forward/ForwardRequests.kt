package dev.eclipse.ssh.ui.forward

import dev.eclipse.ssh.data.model.ForwardType
import dev.eclipse.ssh.data.model.PORT_RANGE
import dev.eclipse.ssh.data.model.isForwardHostName
import dev.eclipse.ssh.data.model.toPortOrNull
import java.util.concurrent.atomic.AtomicReference

/**
 * One forward the user has asked for by hand, waiting to be opened.
 *
 * It carries a host *id* rather than a `HostProfile` because the two live in different windows: the id
 * survives the trip (and the trip back), an object reference does not, and resolving it where the
 * forward is actually started is also the only way to notice that the host has since been deleted.
 *
 * [type] decides which of the other fields mean anything, and the form is what enforces that - see
 * [ForwardFormState]. The shape is the three view-model entry points' own arguments, so the caller
 * that opens the forward is a `when` over [type] and nothing more.
 */
data class ForwardRequest(
    val hostId: String,
    val type: ForwardType,
    val localPort: Int,
    val remoteHost: String?,
    val remotePort: Int?,
)

/**
 * The one-shot handoff of a confirmed [ForwardRequest] from the form back to the workspace.
 *
 * The form is its own Activity now, and the thing that acts on its answer - the view model that owns
 * the forwarding state - belongs to `MainActivity`: it is a `@HiltViewModel` with no scope of its own,
 * so a second window holding one would be holding a different instance with a different set of live
 * tunnels. The two therefore have to meet somewhere, and a process-wide slot is what this app already
 * uses for exactly this shape of problem (`EditorRequests`, and the previews' own registry).
 *
 * A slot rather than a result code, which is the same choice the Settings destinations make for the
 * same reason: what the form produces is *intent* - a request to open something - and a request that
 * is delivered twice is a second bind of the same port. [takeConfirmed] empties the slot as it reads
 * it, so a rotation, a re-resume or a stray re-delivery after the forward has started finds nothing
 * and does nothing, which is exactly what should happen. An unanswered form leaves the slot empty and
 * costs one null check.
 */
object ForwardRequests {

    private val confirmed = AtomicReference<ForwardRequest?>(null)

    /** Records the form's answer, replacing any earlier one the workspace never came back for. */
    fun confirm(request: ForwardRequest) {
        confirmed.set(request)
    }

    /** Reads and clears the answer, or null when the form was cancelled or never opened. */
    fun takeConfirmed(): ForwardRequest? = confirmed.getAndSet(null)
}

/**
 * The Add-forward form as a value: what the user has typed, and what is wrong with it.
 *
 * Extracted from the composable and pure, because the interesting part of a form is never its
 * widgets - it is which combinations of fields are a coherent request, and the three forward types
 * do not share fields at all. LOCAL wants a listener, a destination host and a destination port;
 * REMOTE wants the server's bind port and the phone-side port the server will be connected to, and
 * has no destination host; DYNAMIC wants nothing but the listener.
 *
 * The dialog this replaces validated one field - `localPort.toIntOrNull() != null` - and defaulted
 * the rest, so a Remote forward with a blank bind port silently became a forward of the local port,
 * and a Local forward with a mistyped destination quietly fell back to `127.0.0.1`. Both of those are
 * answers the user did not give, and the port range was never checked at all: `70000` parsed fine and
 * was refused by the server a second later with a message about a socket. Every field is now checked
 * here, per type, against the same helpers the saved-rules sheet uses, so the same mistake is refused
 * in the same words wherever it is made.
 */
internal data class ForwardFormState(
    val type: ForwardType = ForwardType.LOCAL,
    val localPort: String = "",
    val remoteHost: String = "",
    val remotePort: String = "",
) {

    private val localPortNum = localPort.toPortOrNull()
    private val remotePortNum = remotePort.toPortOrNull()
    private val remoteHostName = remoteHost.trim()

    /** A port, in range, with nothing else in the field. Null when the field is fine. */
    private fun portError(value: String, number: Int?): String? = when {
        value.isBlank() -> "Enter a port"
        number == null -> "A port between ${PORT_RANGE.first} and ${PORT_RANGE.last}"
        else -> null
    }

    val localPortError: String? get() = portError(localPort, localPortNum)

    /** Only a Local forward has a destination host; the other two leave this null and the field hidden. */
    val remoteHostError: String?
        get() = when {
            type != ForwardType.LOCAL -> null
            remoteHostName.isEmpty() -> "Enter the host to forward to"
            !remoteHostName.isForwardHostName() -> "A host name or address, with no spaces"
            else -> null
        }

    /** The destination port for a Local forward, the server's bind port for a Remote one. */
    val remotePortError: String?
        get() = if (type == ForwardType.DYNAMIC) null else portError(remotePort, remotePortNum)

    val valid: Boolean
        get() = localPortError == null && remoteHostError == null && remotePortError == null

    /**
     * The request these fields describe, or null while any of them is wrong.
     *
     * The non-null assertions are the ones [valid] has already established - the two are read from the
     * same computed properties, so a field cannot pass the check and then fail to build.
     */
    fun requestFor(hostId: String): ForwardRequest? {
        if (!valid) return null
        return ForwardRequest(
            hostId = hostId,
            type = type,
            localPort = localPortNum!!,
            // A Local forward needs a destination; the other two have none, and the view model's own
            // entry points take exactly that shape.
            remoteHost = if (type == ForwardType.LOCAL) remoteHostName else null,
            remotePort = if (type == ForwardType.DYNAMIC) null else remotePortNum!!,
        )
    }
}
