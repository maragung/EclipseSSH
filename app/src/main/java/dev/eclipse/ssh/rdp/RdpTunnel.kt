package dev.eclipse.ssh.rdp

import android.content.Context
import android.graphics.Bitmap
import com.freerdp.freerdpcore.services.LibFreeRDP
import dev.eclipse.ssh.data.model.RemoteDesktopTarget
import dev.eclipse.ssh.ssh.ForwardingHandle
import dev.eclipse.ssh.ssh.PortForwardingManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
 * The states of one RDP viewing session, in the order they happen.
 *
 * [Connecting] covers everything before the server answers: the forward binding and the RDP
 * negotiation. [AwaitingCredentials] is that negotiation paused on an NLA challenge - a
 * *separate* state from Connecting because the session is now waiting on the user, not the
 * network, and the screen that shows a spinner for one must show a credentials form for the
 * other.
 */
sealed interface RdpTunnelState {
    /** Constructed but never started. */
    data object Idle : RdpTunnelState

    /** Binding the tunnel and negotiating with the server. */
    data object Connecting : RdpTunnelState

    /** The server asked for credentials; the session resumes when [RdpTunnel.submitCredentials] answers. */
    data object AwaitingCredentials : RdpTunnelState

    /** Session live; [RdpTunnel.frames] begins delivering as updates arrive. */
    data class Connected(val width: Int, val height: Int) : RdpTunnelState

    /** The session ended against the user's will; [reason] is one line for the screen. */
    data class Failed(val reason: String) : RdpTunnelState

    /** The session ended because the viewer left it. */
    data object Closed : RdpTunnelState
}

/** One delivered frame: the bitmap to draw, its size, and a counter that grows with every frame. */
data class RdpFrame(val bitmap: Bitmap, val width: Int, val height: Int, val sequence: Long)

/** The user's answer to an NLA challenge, as typed into the viewer's credentials form. */
private data class RdpCredentials(val username: String?, val domain: String?, val password: String?)

/**
 * One RDP viewing session over an SSH connection: an ad-hoc local forward this tunnel owns,
 * and the FreeRDP engine pointed at it. Where the VNC engine hands a plain socket to a Java
 * RFB client, here the whole protocol lives behind LibFreeRDP's JNI bridge, and this class
 * is reduced to the jobs that bridge cannot do for itself: owning the forward, owning the
 * frame bitmap, and translating its native-thread callbacks into the same state-and-frames
 * shape the viewer already renders - which is what lets the Compose viewer swap a VncTunnel
 * for an RdpTunnel mechanically.
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
 * on a terminal's transport, so it lives and dies with it. When that transport dies the RDP
 * session ends underneath the engine, the engine reports [RdpTunnelState.Failed], and the
 * screen's reconnect is a fresh [RdpTunnel] on whatever session the host has by then - this
 * object is single-shot, because every part of it (the port, the native instance, the
 * credentials challenge) belongs to exactly one connection.
 *
 * Threading: FreeRDP delivers every callback - connection events, graphics updates, the
 * authentication challenge - on its own native threads, so every flow in this class is
 * written from those threads and every read happens on whatever thread the UI collects
 * from - which is what the flows being [kotlinx.coroutines.flow.StateFlow]s buys. connect()
 * blocks until the session is over, so it runs on a dedicated thread of ours. OnAuthenticate
 * parks *a native thread* until the user answers: FreeRDP owns that thread and is lending it
 * to us for exactly as long as the challenge waits, so the answer is a deferred completed
 * from the UI rather than a coroutine - suspending there would have meant making the native
 * thread wait on a dispatcher we do not control.
 *
 * Frames are the opposite arrangement from the VNC engine's, by FreeRDP's design rather than
 * ours: Java owns the Bitmap and hands it to updateGraphics, and the *native* side blits the
 * dirty rect into it. The bitmap is still one reused instance rather than an allocation per
 * update. A torn frame - the draw reading pixels while the next update is still being
 * blitted - is possible in that window and accepted: the alternative is a full-screen bitmap
 * allocation per update at whatever rate the server pushes, and a torn frame is invisible
 * while a GC pause mid-desktop is not.
 */
class RdpTunnel(private val forwarding: PortForwardingManager) {

    private val _state = MutableStateFlow<RdpTunnelState>(RdpTunnelState.Idle)
    val state: StateFlow<RdpTunnelState> = _state

    private val _frames = MutableStateFlow<RdpFrame?>(null)
    val frames: StateFlow<RdpFrame?> = _frames

    /** Set by the first deliberate [stop], [abandon] or error, whichever comes first. */
    private val finished = AtomicBoolean(false)

    private val credentialsRequest = AtomicReference<CompletableDeferred<RdpCredentials?>?>(null)

    /** The native instance handle, or 0 before start and after free; input methods no-op on 0. */
    @Volatile private var inst: Long = 0

    @Volatile private var forward: ForwardingHandle? = null

    /** Last desktop size OnSettingsChanged reported; the source of Connected's dimensions. */
    @Volatile private var desktopWidth = 0
    @Volatile private var desktopHeight = 0

    /**
     * Written only from the graphics callbacks, which FreeRDP delivers serialized on its own
     * threads - volatile because those threads are not necessarily the same one over the life
     * of the session, and the input path never touches it.
     */
    @Volatile private var frameBitmap: Bitmap? = null
    private var sequence = 0L

    /** For closing the native instance and forward from the UI thread; never runs protocol code. */
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Opens the tunnel: forward, native instance, connect.
     *
     * [context] is the Application context, first and required where the VNC engine needs no
     * Context at all: FreeRDP's freerdp_new reads Context.getFilesDir() and setenv()s HOME
     * from it before the engine can so much as load its certificate store, so there is no
     * moment to create an instance without one. Application rather than an Activity because
     * the engine holds it for the session's lifetime, which outlives any screen rotation.
     *
     * [username], [domain] and [password] are what the tunnel connects *with*, without asking
     * anyone - remembered credentials, or ones a screen chose to ask for up front. A wrong
     * password still ends the session [RdpTunnelState.Failed], because OnAuthenticate only
     * fires for a challenge the engine could not answer, not for one it answered badly; the
     * caller's retry is a new tunnel. Withholding them means an NLA challenge goes to
     * [RdpTunnelState.AwaitingCredentials] and waits for [submitCredentials].
     *
     * [width] and [height], when non-zero, are the desktop size asked of the server; zero
     * leaves the server's own default in charge.
     */
    fun start(
        context: Context,
        session: ClientSession,
        target: RemoteDesktopTarget,
        username: String? = null,
        domain: String? = null,
        password: String? = null,
        width: Int = 0,
        height: Int = 0,
    ) {
        check(_state.value == RdpTunnelState.Idle) { "A tunnel is single-shot; start a new one" }
        _state.value = RdpTunnelState.Connecting
        thread(name = "rdp-tunnel") {
            try {
                val handle = runBlocking {
                    forwarding.startLocal(session, LOOPBACK, 0, target.host, target.port)
                }
                forward = handle
                val instance = LibFreeRDP.newInstance(context, eventListener, uiEventListener)
                check(instance != 0L) { "FreeRDP would not create an instance" }
                inst = instance
                // The engine dials the forward's loopback port, never the target directly:
                // the only route to the desktop is the SSH transport the viewer rode in on.
                check(
                    LibFreeRDP.setConnectionInfo(
                        instance, LOOPBACK, handle.boundPort,
                        username.orEmpty(), domain.orEmpty(), password.orEmpty(),
                        width, height,
                    )
                ) { "FreeRDP rejected the connection arguments" }
                // Blocks until the session is over - success is reported by OnConnectionSuccess
                // while we are still in here, so a return with no callback behind it is itself
                // the failure. Either way the callbacks below have already written the state
                // unless the engine died without delivering one, which is what the last check
                // here is for.
                LibFreeRDP.connect(instance)
                val settled = _state.value is RdpTunnelState.Connected || finished.get()
                if (!settled) failSession(instance)
            } catch (error: Throwable) {
                onTunnelError(error)
            }
        }
    }

    /** Supplies the credentials the user typed into the viewer's form, if anyone is waiting for them. */
    fun submitCredentials(username: String?, domain: String?, password: String?) {
        credentialsRequest.getAndSet(null)?.complete(RdpCredentials(username, domain, password))
    }

    /**
     * Answers an NLA challenge: the user's credentials, or nobody's. Runs on the native thread
     * FreeRDP lent to the challenge, so the wait is a deferred completed by [submitCredentials]
     * from the UI - see the class threading note for why this is not a coroutine.
     */
    private fun answerAuthenticate(
        username: StringBuilder,
        domain: StringBuilder,
        password: StringBuilder,
    ): Boolean {
        // A session the viewer already ended cannot be answered back to life: stop() wrote
        // Closed, release() is tearing the engine down, and the challenge that arrives after
        // both must not overwrite that state with a dialog the viewer would show for a session
        // it no longer has.
        if (finished.get()) return false
        val deferred = CompletableDeferred<RdpCredentials?>()
        credentialsRequest.set(deferred)
        _state.value = RdpTunnelState.AwaitingCredentials
        // A challenge nobody answers must not park its thread forever: that thread is FreeRDP's,
        // lent for the challenge, and a viewer closed without answering would otherwise leak it
        // for the life of the process. The timeout returns null, which fails the session below.
        val answer = runBlocking {
            withTimeoutOrNull(TimeUnit.SECONDS.toMillis(CREDENTIALS_WAIT_SECONDS)) { deferred.await() }
        }
        credentialsRequest.compareAndSet(deferred, null)
        // Same guard on the way out: release() completes a parked challenge with null, and the
        // answer the user typed in the instant before the viewer closed is not one to act on.
        if (answer == null || finished.get()) return false
        // The three builders are mutated in place - that is the JNI contract - and copied out of
        // the data class and into them only now, so the answer's strings are not held anywhere
        // the tunnel can leak them from after the engine has consumed them.
        username.replace(0, username.length, credentials.username.orEmpty())
        domain.replace(0, domain.length, credentials.domain.orEmpty())
        password.replace(0, password.length, credentials.password.orEmpty())
        return true
    }

    // ---------------------------------------------------------------------------------------------
    // Native callbacks. LibFreeRDP dispatches into these on its own threads; every method
    // below is a translation of one callback into a state write or a frame publish, nothing
    // more. The interfaces are Java with no default methods, so the events the viewer does
    // not use yet are overridden as explicit no-ops rather than left to a stub class.
    // ---------------------------------------------------------------------------------------------

    private val eventListener = object : LibFreeRDP.EventListener {
        override fun OnPreConnect(instance: Long) {
            // The negotiation has begun; Connecting already says everything this can.
        }

        override fun OnConnectionSuccess(instance: Long) {
            if (finished.get()) return
            // OnSettingsChanged can land on either side of this callback, so the dimensions it
            // carries may still be 0 here; the first OnSettingsChanged to arrive afterwards
            // re-publishes Connected with the real ones. Publishing now rather than waiting is
            // what lets the viewer drop its spinner the moment the desktop is live.
            _state.value = RdpTunnelState.Connected(desktopWidth, desktopHeight)
        }

        override fun OnConnectionFailure(instance: Long) {
            failSession(instance)
        }

        override fun OnDisconnecting(instance: Long) {
            // The engine tears itself down next; nothing to release before OnDisconnected.
        }

        override fun OnDisconnected(instance: Long) {
            // A disconnect nobody asked for - the server hung up, or the SSH transport under
            // the forward died. A deliberate stop() has already set finished, which is what
            // keeps this from overwriting the Closed it wrote.
            failSession(instance)
        }
    }

    private val uiEventListener = object : LibFreeRDP.UIEventListener {
        override fun OnSettingsChanged(width: Int, height: Int, bpp: Int) {
            desktopWidth = width
            desktopHeight = height
            ensureBitmap(width, height)
            val state = _state.value
            // Correcting a Connected published before the dimensions were known - see
            // OnConnectionSuccess. Anything earlier stays as it is; success publishes from
            // these fields when it arrives.
            if (state is RdpTunnelState.Connected && (state.width != width || state.height != height)) {
                _state.value = RdpTunnelState.Connected(width, height)
            }
        }

        override fun OnAuthenticate(
            username: StringBuilder,
            domain: StringBuilder,
            password: StringBuilder,
        ): Boolean = answerAuthenticate(username, domain, password)

        override fun OnGatewayAuthenticate(
            username: StringBuilder,
            domain: StringBuilder,
            password: StringBuilder,
        ): Boolean = false // No RD Gateway in a tunneled session; the tunnel is the gateway.

        override fun OnVerifyCertificateEx(
            host: String,
            port: Long,
            commonName: String,
            subject: String,
            issuer: String,
            fingerprint: String,
            flags: Long,
        ): Int {
            // Accept unconditionally: the endpoint identity is the SSH host key that anchored
            // the tunnel, and setConnectionInfo already passes /cert:ignore, so this is a
            // belt-and-braces 1 in case the engine still asks.
            return 1
        }

        override fun OnVerifyChangedCertificateEx(
            host: String,
            port: Long,
            commonName: String,
            subject: String,
            issuer: String,
            fingerprint: String,
            oldSubject: String,
            oldIssuer: String,
            oldFingerprint: String,
            flags: Long,
        ): Int {
            // As above - the server's certificate is not the trust anchor here.
            return 1
        }

        override fun OnExperimentalFeature(feature: Int): Boolean = true

        override fun OnGraphicsUpdate(x: Int, y: Int, width: Int, height: Int) {
            val instance = inst
            val bitmap = frameBitmap ?: return
            // The blit runs on the native side, into our bitmap: updateGraphics is what makes
            // the dirty rect visible at all - skipping it would publish stale pixels.
            LibFreeRDP.updateGraphics(instance, bitmap, x, y, width, height)
            sequence += 1
            _frames.value = RdpFrame(bitmap, bitmap.width, bitmap.height, sequence)
        }

        override fun OnGraphicsResize(width: Int, height: Int, bpp: Int) {
            desktopWidth = width
            desktopHeight = height
            // Nothing to re-blit: the next update repaints the new desktop in full. Publishing
            // the resized (blank) bitmap anyway is what tells the viewer to re-lay out before
            // that repaint arrives.
            val bitmap = ensureBitmap(width, height)
            sequence += 1
            _frames.value = RdpFrame(bitmap, width, height, sequence)
        }

        // Clipboard and remote-app events: a full-desktop session over a phone-sized screen
        // has no use for either yet, and dropping them here is cheaper than a stub listener
        // class that would have to grow the same no-ops. Wiring the clipboard channel up is
        // a viewer feature, not an engine one.
        override fun OnRemoteClipboardChanged(data: String) {}
        override fun OnRemoteClipboardImageChanged(data: ByteArray) {}
        override fun OnPointerSet(pixels: IntArray, width: Int, height: Int, hotX: Int, hotY: Int) {
            // The viewer draws the platform cursor, as the VNC one does with its local
            // pointer; remote cursor shapes would arrive a round trip late.
        }
        override fun OnPointerSetNull() {}
        override fun OnPointerSetDefault() {}
        override fun OnRailWindowUpdate(windowId: Long, width: Int, height: Int, pixels: IntArray) {}
        override fun OnRailWindowMove(windowId: Long, x: Int, y: Int, w: Int, h: Int) {}
        override fun OnRailWindowHide(windowId: Long) {}
        override fun OnRailWindowDestroy(windowId: Long) {}
        override fun OnRailSessionEnd() {}
        override fun OnRailMonitoredDesktop(windowIds: LongArray, activeWindowId: Long) {}
    }

    /**
     * The one bitmap, allocated at the desktop's size and reallocated only when that size
     * actually changes - the same reuse rule the VNC engine applies to its ImageBuffer copy.
     */
    private fun ensureBitmap(width: Int, height: Int): Bitmap =
        frameBitmap?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                .also { frameBitmap = it }

    /**
     * The one failure path: a connection that never got going, a live session the server
     * dropped, or an engine that ended without delivering a callback. Whoever gets here first
     * wins - the [finished] flag is what keeps a deliberate [stop]'s Closed from being
     * overwritten by the OnDisconnected that the teardown itself provokes.
     */
    private fun failSession(instance: Long) {
        if (finished.getAndSet(true)) return
        val reason = if (instance != 0L) {
            LibFreeRDP.getLastErrorString(instance).takeIf { it.isNotBlank() } ?: "connection failed"
        } else {
            "connection failed"
        }
        _state.value = RdpTunnelState.Failed(reason)
        release()
    }

    /** For failures that surface as exceptions on the connect thread rather than callbacks. */
    private fun onTunnelError(error: Throwable) {
        if (finished.getAndSet(true)) return
        val reason = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
        _state.value = RdpTunnelState.Failed(reason)
        release()
    }

    /**
     * Ends the session before it began, with the caller's reason. For the viewer's precondition
     * failures - no SSH session to ride - which are not the tunnel's own errors and so deserve
     * a message the caller writes, not whatever a dead session would have reported.
     */
    fun abandon(reason: String) {
        if (finished.getAndSet(true)) return
        _state.value = RdpTunnelState.Failed(reason)
        release()
    }

    /**
     * Ends the session. Safe to call from any thread, any number of times, and from the UI's
     * disposal path - freeInstance blocks until the engine's disconnect callbacks have run,
     * so the closes run on [closeScope] rather than the caller's thread.
     */
    fun stop() {
        if (finished.getAndSet(true)) return
        _state.value = RdpTunnelState.Closed
        release()
    }

    private fun release() {
        // Unparks a challenge before anything blocking: a native thread sitting in
        // OnAuthenticate holds the engine's connect path, and freeInstance below waits on that
        // same path's callbacks - left waiting on each other, neither would ever finish.
        credentialsRequest.getAndSet(null)?.complete(null)
        // Cancelled from the close job's own completion rather than a second launch, which could
        // win the race and drop the close it was meant to follow.
        val close = closeScope.launch {
            val instance = inst
            if (instance != 0L) {
                runCatching { LibFreeRDP.disconnect(instance) }
                runCatching { LibFreeRDP.freeInstance(instance) }
                inst = 0
            }
            runCatching { forward?.close() }
        }
        close.invokeOnCompletion { runCatching { closeScope.cancel() } }
    }

    // ---------------------------------------------------------------------------------------------
    // Input. Every method is a no-op before the session is live or after it ends: the engine
    // would drop them anyway, and the viewer's controls are hidden in exactly those states -
    // a no-op here is the belt to that.
    // ---------------------------------------------------------------------------------------------

    /** Where the pointer was last left; RDP wants a position with every event, presses included. */
    @Volatile private var lastMouseX = 0
    @Volatile private var lastMouseY = 0

    /** Moves the remote pointer to ([x], [y]) in desktop coordinates. */
    fun moveMouse(x: Int, y: Int) {
        val instance = inst
        if (instance == 0L) return
        lastMouseX = x
        lastMouseY = y
        LibFreeRDP.sendCursorEvent(instance, x, y, PTR_FLAGS_MOVE)
    }

    /** Presses or releases remote mouse button [button] (1 left, 2 middle, 3 right). */
    fun mouseButton(button: Int, pressed: Boolean) {
        val instance = inst
        if (instance == 0L) return
        // The wire's button numbers and the viewer's are not the same numbers: RDP's flag for
        // button 2 is the RIGHT button and button 3 is MIDDLE (freerdp's own input.h says so),
        // where the viewer's convention - inherited from the VNC engine - is 1/2/3 =
        // left/middle/right. Remapped here so the middle one stays the middle one.
        val buttonFlag = when (button) {
            1 -> PTR_FLAGS_BUTTON1
            2 -> PTR_FLAGS_BUTTON3
            3 -> PTR_FLAGS_BUTTON2
            else -> return
        }
        // The position is remembered rather than passed in because the wire format wants one
        // with a press too - sending (0, 0) there would teleport the cursor to the corner on
        // every tap.
        val flags = if (pressed) PTR_FLAGS_DOWN or buttonFlag else buttonFlag
        LibFreeRDP.sendCursorEvent(instance, lastMouseX, lastMouseY, flags)
    }

    /** One notch of the remote wheel, up or down. */
    fun scroll(up: Boolean) {
        val instance = inst
        if (instance == 0L) return
        // The wheel encoding FreeRDP's own clients send (wf_event.c, sdl_touch.cpp): the wheel
        // flag, the direction bit for down, and the rotation count - one notch is 0x78, the
        // hundredths-of-a-notch unit the format shares with WM_MOUSEWHEEL. No PTR_FLAGS_DOWN:
        // that bit belongs to button presses and is not part of a wheel event.
        val flags = PTR_FLAGS_WHEEL or
            (if (up) 0 else PTR_FLAGS_WHEEL_NEGATIVE) or
            WHEEL_ROTATION_ONE_NOTCH
        LibFreeRDP.sendCursorEvent(instance, lastMouseX, lastMouseY, flags)
    }

    /** Types [text] as a run of Unicode key presses, one press-and-release per codepoint. */
    fun type(text: String) {
        val instance = inst
        if (instance == 0L) return
        // setConnectionInfo negotiates /kbd:unicode:on, so codepoints travel as themselves; the
        // support check is belt-and-braces for a server that refused the extension.
        if (!LibFreeRDP.isUnicodeInputSupported(instance)) return
        text.codePoints().forEach { code ->
            LibFreeRDP.sendUnicodeKeyEvent(instance, code, true)
            LibFreeRDP.sendUnicodeKeyEvent(instance, code, false)
        }
    }

    /**
     * Asks the server for a different desktop size, over the display channel that
     * setConnectionInfo enables. A server that cannot resize ignores it, which is why the
     * viewer treats OnGraphicsResize, not this request, as the truth about the new size.
     */
    fun requestResolution(width: Int, height: Int) {
        val instance = inst
        if (instance == 0L) return
        LibFreeRDP.sendMonitorLayout(instance, width, height)
    }

    private companion object {
        /** The interface the tunnel's forward lives on - never anything routable. */
        const val LOOPBACK = "127.0.0.1"

        /** How long an NLA challenge waits for an answer before giving up on the session. */
        const val CREDENTIALS_WAIT_SECONDS = 120L

        // No frame-rate constant on purpose: FreeRDP paces its own updates, where the VNC
        // engine had to be told one.

        // MS-RDPBCGR pointer-event flags (2.2.3.1.1.1.1), the same values freerdp's input.h
        // defines, passed through to sendCursorEvent verbatim - the engine wants the wire
        // encoding, not an abstraction over it. Note BUTTON2 is RIGHT and BUTTON3 is MIDDLE.
        const val PTR_FLAGS_MOVE = 0x0800
        const val PTR_FLAGS_DOWN = 0x8000
        const val PTR_FLAGS_BUTTON1 = 0x1000
        const val PTR_FLAGS_BUTTON2 = 0x2000
        const val PTR_FLAGS_BUTTON3 = 0x4000
        const val PTR_FLAGS_WHEEL = 0x0200
        const val PTR_FLAGS_WHEEL_NEGATIVE = 0x0100

        /** One wheel notch, in the hundredths units the rotation-count field counts in. */
        const val WHEEL_ROTATION_ONE_NOTCH = 0x78
    }
}
