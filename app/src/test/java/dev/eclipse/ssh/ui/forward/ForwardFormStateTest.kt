package dev.eclipse.ssh.ui.forward

import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.data.model.ForwardType
import org.junit.Test

/**
 * The Add-forward form as a value: which combinations of fields are a coherent request.
 *
 * The dialog this replaced checked one field and defaulted the rest, and each default was a wrong
 * answer wearing a helpful face. A blank "Remote port" on a Local forward became the local port, so
 * `8080 → 127.0.0.1:8080` was opened for a user who had typed a host and no port — a tunnel they
 * never asked for, connecting to whatever listened on the phone's own port. A mistyped destination
 * host fell back to `127.0.0.1`. And nothing checked the port range at all: `70000` parsed, the
 * forward was attempted, and the server refused it a second later with a message about a socket,
 * which is a sentence about the transport rather than about the number the user typed.
 *
 * These tests are per type, because the three types do not share fields — the interesting question is
 * never "is this port a number" but "is this *set* of fields a request that can be opened".
 */
class ForwardFormStateTest {

    private val hostId = "host-1"

    @Test
    fun `a blank form requests nothing`() {
        // The state the window opens in. Start is disabled for exactly this, and it has to be: an
        // empty form is not a forward with defaults, it is a question nobody has answered.
        assertThat(ForwardFormState().requestFor(hostId)).isNull()
        assertThat(ForwardFormState().valid).isFalse()
    }

    @Test
    fun `a local forward needs a listener, a destination and a port`() {
        val complete = ForwardFormState(
            type = ForwardType.LOCAL,
            localPort = "8080",
            remoteHost = "db.internal",
            remotePort = "5432",
        )

        assertThat(complete.valid).isTrue()
        assertThat(complete.requestFor(hostId)).isEqualTo(
            ForwardRequest(
                hostId = hostId,
                type = ForwardType.LOCAL,
                localPort = 8080,
                remoteHost = "db.internal",
                remotePort = 5432,
            ),
        )
    }

    @Test
    fun `a local forward without a destination port is refused, not defaulted`() {
        // The dialog defaulted this to the local port, which silently opened a tunnel to the phone's
        // own 8080 on a host the user had named. Refusing it is the whole point of the change.
        val missingPort = ForwardFormState(
            type = ForwardType.LOCAL,
            localPort = "8080",
            remoteHost = "db.internal",
            remotePort = "",
        )

        assertThat(missingPort.valid).isFalse()
        assertThat(missingPort.remotePortError).isNotNull()
        assertThat(missingPort.requestFor(hostId)).isNull()
    }

    @Test
    fun `a local forward without a destination host is refused, not loopback`() {
        val missingHost = ForwardFormState(
            type = ForwardType.LOCAL,
            localPort = "8080",
            remoteHost = "",
            remotePort = "5432",
        )

        assertThat(missingHost.valid).isFalse()
        assertThat(missingHost.remoteHostError).isNotNull()
    }

    @Test
    fun `a destination host with a space in it is refused`() {
        // Shape only: what a name resolves to is the server's business, but a name that would be
        // re-parsed into a different rule on the way back is this form's. The saved-rules sheet uses
        // the same predicate, so the same mistake is refused in the same words wherever it is made.
        val spaced = ForwardFormState(
            type = ForwardType.LOCAL,
            localPort = "8080",
            remoteHost = "db internal",
            remotePort = "5432",
        )

        assertThat(spaced.valid).isFalse()
        assertThat(spaced.remoteHostError).isNotNull()
    }

    @Test
    fun `a port outside the range the pty accepts is refused before the server sees it`() {
        // 70000 is a number, which is all the dialog ever asked of it. It reaches the server, the
        // server refuses it, and the failure the user reads is about a socket rather than a port.
        listOf("0", "70000", "-1", "80a").forEach { value ->
            val state = ForwardFormState(
                type = ForwardType.DYNAMIC,
                localPort = value,
            )
            assertThat(state.valid).isFalse()
            assertThat(state.localPortError).isNotNull()
        }

        assertThat(ForwardFormState(type = ForwardType.DYNAMIC, localPort = "1").valid).isTrue()
        assertThat(ForwardFormState(type = ForwardType.DYNAMIC, localPort = "65535").valid).isTrue()
    }

    @Test
    fun `a dynamic forward needs nothing but its listener`() {
        // Its destination is chosen per connection by whoever speaks SOCKS to it, so a second field
        // would be a question with no answer.
        val dynamic = ForwardFormState(type = ForwardType.DYNAMIC, localPort = "1080", remoteHost = "example.com", remotePort = "22")

        assertThat(dynamic.valid).isTrue()
        val request = requireNotNull(dynamic.requestFor(hostId))
        assertThat(request.remoteHost).isNull()
        assertThat(request.remotePort).isNull()
        assertThat(request.localPort).isEqualTo(1080)
    }

    @Test
    fun `a remote forward wants the server's bind port and the phone-side port`() {
        // The pair most easily swapped, and the reason the two labels differ: for a Remote forward the
        // server listens on the bind port and is connected to the *local* destination. Both are
        // required — the dialog defaulted the bind port to the local port, which opened the server's
        // port on the phone's number.
        val remote = ForwardFormState(
            type = ForwardType.REMOTE,
            localPort = "3000",
            remotePort = "9000",
        )

        assertThat(remote.valid).isTrue()
        assertThat(remote.requestFor(hostId)).isEqualTo(
            ForwardRequest(
                hostId = hostId,
                type = ForwardType.REMOTE,
                localPort = 3000,
                remoteHost = null,
                remotePort = 9000,
            ),
        )

        val missingBind = ForwardFormState(type = ForwardType.REMOTE, localPort = "3000", remotePort = "")
        assertThat(missingBind.valid).isFalse()
        assertThat(missingBind.remotePortError).isNotNull()
    }

    @Test
    fun `a remote forward ignores a destination host it is not given`() {
        // The dialog showed the field and ignored it. The form hides it, and the request carries no
        // host for the two types that have nowhere to forward to.
        val remote = ForwardFormState(type = ForwardType.REMOTE, localPort = "3000", remotePort = "9000", remoteHost = "ignored")

        assertThat(remote.remoteHostError).isNull()
        assertThat(requireNotNull(remote.requestFor(hostId)).remoteHost).isNull()
    }

    @Test
    fun `switching type re-reads the fields it does not use`() {
        // A Local forward with a bad destination host is invalid; the same fields as a Dynamic one are
        // valid, because a dynamic forward has no destination to be wrong. Reading the errors from the
        // type rather than storing them is what makes that automatic.
        val local = ForwardFormState(type = ForwardType.LOCAL, localPort = "8080", remoteHost = "", remotePort = "5432")
        assertThat(local.valid).isFalse()

        val dynamic = local.copy(type = ForwardType.DYNAMIC)
        assertThat(dynamic.valid).isTrue()
        assertThat(dynamic.remoteHostError).isNull()
    }

    @Test
    fun `a port with surrounding space is accepted, as the saved-rules sheet accepts it`() {
        // `toPortOrNull` trims, so a paste that brought a trailing space is not an error the user has
        // to hunt for. The value that reaches the view model is the parsed number either way.
        val padded = ForwardFormState(type = ForwardType.DYNAMIC, localPort = " 1080 ")

        assertThat(padded.valid).isTrue()
        assertThat(requireNotNull(padded.requestFor(hostId)).localPort).isEqualTo(1080)
    }
}

/**
 * The one-shot handoff, and the property that makes it safe: an answer can be acted on once.
 *
 * The form is a separate window from the workspace that opens the forward, so the two meet in a
 * process-wide slot. Every way this can go wrong is a *second* read of the same answer — a rotation
 * that resumes the workspace twice, an intent re-delivered from the recents screen, a process that
 * comes back to a saved slot - and each of them would be a second bind of the same port, failing with
 * a message about a socket rather than about what the user did.
 */
class ForwardRequestsTest {

    private val request = ForwardRequest(
        hostId = "host-1",
        type = ForwardType.DYNAMIC,
        localPort = 1080,
        remoteHost = null,
        remotePort = null,
    )

    @Test
    fun `an answer is taken once and then gone`() {
        ForwardRequests.confirm(request)

        assertThat(ForwardRequests.takeConfirmed()).isEqualTo(request)
        assertThat(ForwardRequests.takeConfirmed()).isNull()
    }

    @Test
    fun `an unanswered form leaves nothing to take`() {
        // Cancelling is the common case, and it is the one that must not open anything.
        assertThat(ForwardRequests.takeConfirmed()).isNull()
    }

    @Test
    fun `a second confirmation replaces the first`() {
        // The user pressed Add again before the workspace came back for the first answer. Acting on
        // the older one would open a port they have already changed their mind about, and the slot
        // holds one answer for the same reason the form is one window.
        val later = request.copy(localPort = 1081)
        ForwardRequests.confirm(request)
        ForwardRequests.confirm(later)

        assertThat(ForwardRequests.takeConfirmed()).isEqualTo(later)
        assertThat(ForwardRequests.takeConfirmed()).isNull()
    }
}
