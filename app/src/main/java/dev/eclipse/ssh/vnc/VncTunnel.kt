package dev.eclipse.ssh.vnc

import android.graphics.Bitmap
import com.shinyhut.vernacular.client.VernacularClient
import com.shinyhut.vernacular.client.VernacularConfig
import com.shinyhut.vernacular.client.rendering.ImageBuffer
import dev.eclipse.ssh.data.model.RemoteDesktopTarget
import dev.eclipse.ssh.ssh.ForwardingHandle
import dev.eclipse.ssh.ssh.PortForwardingManager
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.apache.sshd.client.session.ClientSession

/**
 * The states of one VNC viewing session, in the order they happen.
 *
 * [Connecting] covers everything before the RFB handshake answers: the forward binding, the
 * socket dial, the protocol version exchange. [AwaitingPassword] is the handshake paused on
 * a server that wants VNC authentication - a *separate* state from Connecting because the
 * session is now waiting on the user, not the network, and the screen that shows a spinner
 * for one must show a password field for the other.
 */
sealed interface VncTunnelState {
    /** Constructed but never started. */
    data object Idle : VncTunnelState

    /** Binding the tunnel and shaking hands. */
    data object Connecting : VncTunnelState

    /** The server asked for a VNC password; the session resumes when [VncTunnel.submitPassword] answers. */
    data object AwaitingPassword : VncTunnelState

    /** Handshake complete; [VncTunnel.frames] begins delivering as the first update arrives. */
    data class Connected(val width: Int, val height: Int) : VncTunnelState

    /** The session ended against the user's will; [reason] is one line for the screen. */
    data class Failed(val reason: String) : VncTunnelState

    /** The session ended because the viewer left it. */
    data object Closed : VncTunnelState
}

/** One delivered frame: the bitmap to draw, its size, and a counter that grows with every frame. */
data class VncFrame(val bitmap: Bitmap, val width: Int, val height: Int, val sequence: Long)

/**
 * One VNC viewing session over an SSH connection: an ad-hoc local forward this tunnel owns,
 * a plain socket through it, and the vernacular RFB client on the socket.
 *
 * The forward is deliberately *not* one of the host's saved rules: it binds an ephemeral
 * loopback port the moment the viewer opens, it is closed the moment the viewer closes, and
 * it never appears in the forwarding sheet - a desktop the user is looking at is not a tunnel
 * the user should also have to manage. Binding port 0 and reading the assigned port back
 * from the tracker is what makes the port ephemeral *without* a race: predicting a free port
 * and racing another forward to it is exactly the "already in use" family of failure the
 * saved rules had to grow a pre-check for.
 *
 * The session the forward rides is handed in, never dialed here: the viewer is a passenger
 * on a terminal's transport, so it lives and dies with it. When that transport dies the RFB
 * reader hits end-of-stream, the session reports [VncTunnelState.Failed], and the screen's
 * reconnect is a fresh [VncTunnel] on whatever session the host has by then - this object
 * is single-shot, because every part of it (the port, the socket, the RFB handshake) belongs
 * to exactly one connection.
 *
 * Threading: vernacular runs the RFB reader and writer on its own Java threads and delivers
 * frames and errors on them, so every flow in this class is written from those threads and
 * every read happens on whatever thread the UI collects from - which is what the flows being
 * [kotlinx.coroutines.flow.StateFlow]s buys. The handshake itself runs on a dedicated thread
 * of ours, which is also where the password supplier blocks: a server that wants a password
 * parks the handshake until [submitPassword] answers, and parking a coroutine would have
 * meant parking a dispatcher thread for as long as the user takes to type.
 *
 * Frames are copied into one reused [Bitmap]. A torn frame - the draw reading pixels while
 * the next update is still being written - is possible in that window and accepted: the
 * alternative is a full-screen bitmap allocation per update at up to the target frame rate,
 * and a torn frame is invisible while a GC pause at 30 fps is not.
 */
class VncTunnel(private val forwarding: PortForwardingManager) {

    private val _state = MutableStateFlow<VncTunnelState>(VncTunnelState.Idle)
    val state: StateFlow<VncTunnelState> = _state

    private val _frames = MutableStateFlow<VncFrame?>(null)
    val frames: StateFlow<VncFrame?> = _frames

    /**
     * The last text the *server* cut, or null when it never has. A StateFlow rather than a
     * SharedFlow because the viewer collects it from composition, where "the current value on
     * arrival, then every change" is exactly the shape wanted - and because vernacular delivers
     * the listener on its own reader thread, which a StateFlow absorbs without a buffer policy.
     */
    private val _remoteClipboard = MutableStateFlow<String?>(null)
    val remoteClipboard: StateFlow<String?> = _remoteClipboard

    /** Set by the first deliberate [stop] or the first error, whichever comes first. */
    private val finished = AtomicBoolean(false)

    private val passwordRequest = AtomicReference<CompletableDeferred<String>?>(null)

    /** Written once by the start thread; read by input methods guarded by the tunnel not being finished. */
    @Volatile private var client: VernacularClient? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var forward: ForwardingHandle? = null

    /** Owned by the RFB reader thread alone - the one thread that ever writes pixels. */
    private var frameBitmap: Bitmap? = null
    private var sequence = 0L

    /** For closing resources from the UI thread; never runs protocol code. */
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Opens the tunnel: forward, socket, handshake.
     *
     * [password] is what the tunnel answers the *first* VNC-auth challenge with, without
     * asking anyone - the remembered password from the last attempt, or one a screen chose
     * to ask for up front. A wrong one ends the session [VncTunnelState.Failed] with the
     * authentication message, and the caller's retry is a new tunnel with a better password.
     * Null means a challenged handshake goes to [VncTunnelState.AwaitingPassword] and waits
     * for [submitPassword].
     */
    fun start(
        session: ClientSession,
        target: RemoteDesktopTarget,
        password: String? = null,
        framesPerSecond: Int = DEFAULT_FRAMES_PER_SECOND,
    ) {
        check(_state.value == VncTunnelState.Idle) { "A tunnel is single-shot; start a new one" }
        _state.value = VncTunnelState.Connecting
        thread(name = "vnc-tunnel") {
            try {
                val handle = runBlocking {
                    forwarding.startLocal(session, LOOPBACK, 0, target.host, target.port)
                }
                forward = handle
                val socket = Socket()
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(LOOPBACK, handle.boundPort), CONNECT_TIMEOUT_MS)
                this.socket = socket
                val config = VernacularConfig().apply {
                    targetFramesPerSecond = framesPerSecond
                    // The one thing a viewer on a phone screen cannot leave to the server: cursor
                    // feedback. A remote-drawn cursor arrives one round trip after the tap that moved
                    // it, which reads as lag on every interaction; drawing it locally from the
                    // server's cursor-shape notifications is instantaneous and pixel-identical.
                    isUseLocalMousePointer = true
                    // So a resize request can be honoured at all; without the pseudo-encoding the
                    // server never learns we understand desktop-size changes, and [requestResolution]
                    // would be a no-op everywhere.
                    isEnableExtendedDesktopSize = true
                    // The server's cut text, announced as it happens: the clipboard-sync half of
                    // the feature, whose other direction is [copyText]. Deliberately the *plain*
                    // cut-text messages and not the extended pseudo-encoding - which this
                    // library's config defaults ON, so the off has to be said out loud. The
                    // plain messages are the base protocol every RFB server answers; the
                    // extended encoding is opt-in on the server side, and the moment the flag is
                    // on the library switches its client-to-server wire format to it - a server
                    // that never opted in would read the extended header as a text length and
                    // stall. Text-only, too: the clipboard formats a desktop user actually
                    // moves are text, and the image listener stays unwired for the same reason
                    // RDP's does not.
                    isEnableExtendedClipboard = false
                    setRemoteClipboardListener { text -> _remoteClipboard.value = text }
                    setPasswordSupplier(Supplier { answerPasswordChallenge(password) })
                    setErrorListener { error -> onVncError(error) }
                    setScreenUpdateListener { image -> onScreenUpdate(image) }
                }
                val vernacular = VernacularClient(config)
                client = vernacular
                // Handshake included: start() only returns once the protocol version, security and
                // ServerInit are exchanged, so the session below has real dimensions. A failure
                // anywhere in there does not throw out of start() - it routes to the error listener,
                // which is why Connected is only written when no error got there first.
                vernacular.start(socket)
                val fb = vernacular.session
                if (_state.value is VncTunnelState.Connecting || _state.value is VncTunnelState.AwaitingPassword) {
                    _state.value = VncTunnelState.Connected(fb.framebufferWidth, fb.framebufferHeight)
                }
            } catch (error: Throwable) {
                onVncError(error)
            }
        }
    }

    /** Answers a VNC-auth challenge: the caller's password if it has one, or the user's if not. */
    private fun answerPasswordChallenge(remembered: String?): String? {
        if (remembered != null) return remembered
        val deferred = CompletableDeferred<String>()
        passwordRequest.set(deferred)
        _state.value = VncTunnelState.AwaitingPassword
        // A challenge nobody answers must not park its thread forever: the handshake thread is
        // blocked for exactly as long as this wait, and a viewer closed without answering would
        // otherwise leak it for the life of the process.
        val answer = runBlocking { withTimeoutOrNull(TimeUnit.SECONDS.toMillis(PASSWORD_WAIT_SECONDS)) { deferred.await() } }
        passwordRequest.compareAndSet(deferred, null)
        return answer
    }

    /** Supplies the password the user typed into the viewer's prompt, if anyone is waiting for one. */
    fun submitPassword(password: String) {
        passwordRequest.getAndSet(null)?.complete(password)
    }

    private fun onScreenUpdate(image: ImageBuffer) {
        // The same ImageBuffer instance comes back every time, repainted in place - holding it
        // would show the user a live mutating object and racing the reader for its pixels. The
        // copy is the frame; the bitmap is reused because it is the same size almost always.
        val bitmap = frameBitmap?.takeIf { it.width == image.width && it.height == image.height }
            ?: Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                .also { frameBitmap = it }
        bitmap.setPixels(image.buffer, 0, image.width, 0, 0, image.width, image.height)
        sequence += 1
        _frames.value = VncFrame(bitmap, image.width, image.height, sequence)
    }

    private fun onVncError(error: Throwable) {
        if (finished.getAndSet(true)) return
        val reason = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
        _state.value = VncTunnelState.Failed(reason)
        release()
    }

    /**
     * Ends the session before it began, with the caller's reason. For the viewer's precondition
     * failures - no SSH session to ride - which are not the tunnel's own errors and so deserve
     * a message the caller writes, not whatever a null session would have thrown.
     */
    fun abandon(reason: String) {
        if (finished.getAndSet(true)) return
        _state.value = VncTunnelState.Failed(reason)
        release()
    }

    /**
     * Ends the session. Safe to call from any thread, any number of times, and from the UI's
     * disposal path - the closes run on [closeScope] because a tracker close is a network write,
     * and disposal runs on the main thread.
     */
    fun stop() {
        if (finished.getAndSet(true)) return
        _state.value = VncTunnelState.Closed
        release()
    }

    private fun release() {
        // Cancelled from the close job's own completion rather than a second launch, which could
        // win the race and drop the close it was meant to follow.
        val close = closeScope.launch {
            // Vernacular's stop() joins its own reader and writer threads, so it is never called
            // from either of them (the error listener runs on the reader) - a join from a thread
            // on itself is the one-second timeout spent on nothing. Closing the socket reaches
            // both threads the same way, without the join.
            runCatching { socket?.close() }
            runCatching { client?.stop() }
            runCatching { forward?.close() }
        }
        close.invokeOnCompletion { runCatching { closeScope.cancel() } }
    }

    // ---------------------------------------------------------------------------------------------
    // Input. Every method is a no-op before the handshake completes or after the session ends:
    // vernacular would drop them anyway (no writer), and the viewer's controls are hidden in
    // exactly those states - a no-op here is the belt to that.
    // ---------------------------------------------------------------------------------------------

    /** Moves the remote pointer to ([x], [y]) in framebuffer coordinates. */
    fun moveMouse(x: Int, y: Int) {
        client?.moveMouse(x, y)
    }

    /** Presses or releases remote mouse button [button] (1 left, 2 middle, 3 right). */
    fun mouseButton(button: Int, pressed: Boolean) {
        client?.updateMouseButton(button, pressed)
    }

    /** One notch of the remote wheel, up or down. */
    fun scroll(up: Boolean) {
        if (up) client?.scrollUp() else client?.scrollDown()
    }

    /** Presses or releases the key with X11 keysym [keySym]. */
    fun key(keySym: Int, pressed: Boolean) {
        client?.updateKey(keySym, pressed)
    }

    /** Types [text] as a run of key presses, printable ASCII only. */
    fun type(text: String) {
        client?.type(text)
    }

    /**
     * Sends [text] as the client's cut text - the phone's clipboard, pasted onto the remote
     * desktop. The same no-op-before-connected rule as the input methods above: vernacular
     * would drop it without a writer, and the viewer's clipboard control is hidden until
     * Connected - a no-op here is the belt to that.
     */
    fun copyText(text: String) {
        client?.copyText(text)
    }

    /**
     * Asks the server for a different framebuffer size. A server that does not support the
     * extended desktop-size pseudo-encoding ignores it, which is why the viewer treats the
     * reply, not the request, as the truth about the new size.
     */
    fun requestResolution(width: Int, height: Int) {
        client?.resize(width, height)
    }

    private companion object {
        /** The interface the tunnel's listener and socket live on - never anything routable. */
        const val LOOPBACK = "127.0.0.1"

        const val CONNECT_TIMEOUT_MS = 10_000

        /** How long a VNC password challenge waits for an answer before giving up on the session. */
        const val PASSWORD_WAIT_SECONDS = 120L

        const val DEFAULT_FRAMES_PER_SECOND = 30
    }
}
