package dev.eclipse.ssh.ui.remotedesktop

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Portrait
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.eclipse.ssh.data.credentials.RdpCredentials
import dev.eclipse.ssh.rdp.RdpFrame
import dev.eclipse.ssh.rdp.RdpTunnel
import dev.eclipse.ssh.rdp.RdpTunnelState
import dev.eclipse.ssh.security.SecureClipboard
import dev.eclipse.ssh.ssh.PortForwardingManager
import dev.eclipse.ssh.vnc.VncFrame
import dev.eclipse.ssh.vnc.VncTunnel
import dev.eclipse.ssh.vnc.VncTunnelState
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * How the desktop is fitted into the screen. The fit modes own the scale until the user takes it
 * over - a pinch, a zoom button or a double-tap switches to free zoom, which is the only mode
 * whose scale survives across frames (a fit mode is recomputed every time, because "width" and
 * "height" mean the screen's, which rotation can change).
 */
private enum class DisplayMode(val label: String) {
    FIT_SCREEN("Fit screen"),
    FIT_WIDTH("Fit width"),
    FIT_HEIGHT("Fit height"),
    ACTUAL("Actual size"),
    FREE("Free zoom"),
}

/** Zoom is clamped so no gesture can throw the desktop out to a sub-pixel or a single giant pixel. */
private const val MIN_SCALE = 0.1f
private const val MAX_SCALE = 12f

/** What a double-tap zooms to when there is nothing to zoom back out to. */
private const val DOUBLE_TAP_SCALE = 2.5f

/** How long the toolbar waits after the last touch before hiding itself. */
private const val TOOLBAR_HIDE_DELAY_MS = 4_000L

/** The desktop sizes the RDP resolution menu offers, beyond "whatever fits this screen". */
private val RDP_RESOLUTION_CHOICES = listOf(1280 to 720, 1920 to 1080)

/**
 * What the shell - the protocol-agnostic half of the viewer - renders. Both tunnels publish the
 * same shape, so the shell gets one vocabulary instead of two: it never asks *which* protocol is
 * waiting for a secret, only that the session is parked on the user rather than the network,
 * because the prompt itself is protocol UI and stays with the protocol's half below.
 */
private sealed interface DesktopState {
    /** Constructed but never started. */
    data object Idle : DesktopState

    /** Binding the tunnel and negotiating with the server. */
    data object Connecting : DesktopState

    /** The server asked for something only the user can supply; the protocol half owns the form. */
    data object AwaitingInput : DesktopState

    /** Session live; frames begin delivering as updates arrive. */
    data class Connected(val width: Int, val height: Int) : DesktopState

    /** The session ended against the user's will; [reason] is one line for the screen. */
    data class Failed(val reason: String) : DesktopState

    /** The session ended because the viewer left it. */
    data object Closed : DesktopState
}

/** One delivered frame, in the shape the shell renders regardless of which engine drew it. */
private data class DesktopFrame(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
)

/** The VNC engine's state as the shell's. */
private fun VncTunnelState.asDesktopState(): DesktopState = when (this) {
    VncTunnelState.Idle -> DesktopState.Idle
    VncTunnelState.Connecting -> DesktopState.Connecting
    VncTunnelState.AwaitingPassword -> DesktopState.AwaitingInput
    is VncTunnelState.Connected -> DesktopState.Connected(width, height)
    is VncTunnelState.Failed -> DesktopState.Failed(reason)
    VncTunnelState.Closed -> DesktopState.Closed
}

/** The RDP engine's state as the shell's. */
private fun RdpTunnelState.asDesktopState(): DesktopState = when (this) {
    RdpTunnelState.Idle -> DesktopState.Idle
    RdpTunnelState.Connecting -> DesktopState.Connecting
    RdpTunnelState.AwaitingCredentials -> DesktopState.AwaitingInput
    is RdpTunnelState.Connected -> DesktopState.Connected(width, height)
    is RdpTunnelState.Failed -> DesktopState.Failed(reason)
    RdpTunnelState.Closed -> DesktopState.Closed
}

/** One VNC frame as the shell's. */
private fun VncFrame.asDesktopFrame(): DesktopFrame = DesktopFrame(bitmap, width, height)

/** One RDP frame as the shell's. */
private fun RdpFrame.asDesktopFrame(): DesktopFrame = DesktopFrame(bitmap, width, height)

/**
 * The input surface the shell drives, wired per protocol from that protocol's tunnel - the two
 * tunnels' input methods already agree on names and shapes, so the wiring is method references
 * and the shell never learns which class it is calling.
 */
private class DesktopInput(
    val moveMouse: (x: Int, y: Int) -> Unit,
    val mouseButton: (button: Int, pressed: Boolean) -> Unit,
    val scroll: (up: Boolean) -> Unit,
    val type: (text: String) -> Unit,
    val requestResolution: (width: Int, height: Int) -> Unit,
)

/**
 * The clipboard surface the shell syncs, wired per protocol the same way [DesktopInput] is:
 * one direction to call (the phone's clipboard pushed to the desktop) and one to collect (the
 * desktop's clipboard, as text, published whenever the server cuts). The two tunnels expose
 * exactly this pair - [VncTunnel.copyText]/`remoteClipboard` and [RdpTunnel.copyText]/
 * `remoteClipboard` - so the wiring is method references and a flow reference, and the shell
 * never learns which protocol is holding the clipboard.
 */
private class DesktopClipboard(
    val push: (text: String) -> Unit,
    val remote: StateFlow<String?>,
)

/**
 * The phone's half of the same sync: the two operations the viewer performs on the phone's own
 * clipboard, as opposed to the desktop's.
 *
 * It exists so that the shell touches no `ClipboardManager` of its own. The app keeps exactly one
 * clipboard route - [SecureClipboard] - and that route is also what puts a deadline on a copy, so
 * this is where the viewer's direction from desktop to phone stops being a special case: a remote
 * clipboard can hold a password as easily as it holds a URL, and it therefore lands under the same
 * auto-clear setting as every other copy the app makes. A user who wants it kept indefinitely sets
 * that setting to 0, which is the same answer the setting already gives everywhere else.
 *
 * The delay is carried alongside the clipboard because it is a property of the copy rather than of
 * the caller: keeping them together is what lets the three composables between the window and the
 * shell carry one value instead of two.
 */
private class PhoneClipboard(
    private val clipboard: SecureClipboard,
    private val clearAfterSeconds: Int,
) {
    /** The desktop's clipboard, landed on the phone's, under the app's own deadline. */
    fun put(text: String) = clipboard.copy(text, clearAfterSeconds)

    /** The phone's clipboard as text, or null - including when the platform refuses the read. */
    fun get(): String? = clipboard.paste()
}

/**
 * The remote desktop, fullscreen and immersive: the frames the tunnel delivers, zoomed and
 * panned, with a floating toolbar over them.
 *
 * The protocol halves ([VncViewer], [RdpViewer]) each own a tunnel and its protocol-specific
 * moments - how a session starts, what a challenge asks for - and hand everything else to
 * [ViewerShell], which owns the parts that are the same desktop either way: the zoom and pan,
 * the gestures, the toolbar, the failure panel. VNC keeps exactly the behaviour it had; RDP
 * rides the same shell and adds the three affordances its tunnel offers and the VNC one does
 * not show - a keyboard strip that types Unicode, a resolution menu that asks the server to
 * resize, and wheel events forwarded as remote scrolls.
 *
 * The toolbar hides itself a few seconds after the last touch and comes back from a small handle
 * in the corner - the desktop beneath it is what the user came to touch, and a toolbar that
 * stayed would be a permanent hazard zone: every remote click near the top edge would hit a
 * button of ours instead. It only hides while [DesktopState.Connected], never in the states
 * where the user is about to act on the viewer rather than the desktop (asking for a password,
 * reporting a failure).
 *
 * Input is mapped by position: a tap on the screen is a remote click wherever it landed, in
 * desktop coordinates after the current zoom is undone. A one-finger drag pans the view
 * rather than the remote pointer - on a phone screen the finger *is* the pointer's position, so
 * dragging it would have to mean drag-and-drop, and a viewer that cannot scroll a zoomed desktop
 * is the more broken half of that pair. View-only targets skip the remote half entirely; the
 * local half (pan, zoom, rotate) stays, because none of it reaches the wire.
 *
 * The tunnel is one per connection and the viewer owns it: minted on entry, stopped on exit (or
 * on a Reconnect, which mints the next one - re-keying the tunnel on the reconnect counter is
 * what makes that true, and the disposal stops the one being replaced). The VNC password and the
 * RDP credentials typed into a prompt are kept for the life of this screen only - in memory,
 * never persisted - so a reconnect does not ask for them again while the same viewer is open,
 * and closing the viewer forgets them.
 */
@Composable
fun RemoteDesktopScreen(
    request: RemoteDesktopRequest,
    secureClipboard: SecureClipboard,
    clearClipboardAfterSeconds: Int,
    onClose: () -> Unit,
) {
    // Built here, from the two values the hosting window read once at startup, so the protocol
    // halves below never see the clipboard itself - only the two operations they perform on it.
    val phoneClipboard = remember(secureClipboard, clearClipboardAfterSeconds) {
        PhoneClipboard(secureClipboard, clearClipboardAfterSeconds)
    }
    when (request) {
        is RemoteDesktopRequest.Vnc -> VncViewer(request, phoneClipboard, onClose)
        is RemoteDesktopRequest.Rdp -> RdpViewer(request, phoneClipboard, onClose)
    }
}

/**
 * The VNC half: a [VncTunnel] per connection, the password prompt when the server asks, and the
 * remembered password a reconnect re-answers with.
 */
@Composable
private fun VncViewer(
    request: RemoteDesktopRequest.Vnc,
    phoneClipboard: PhoneClipboard,
    onClose: () -> Unit,
) {
    var reconnects by remember { mutableStateOf(0) }
    val tunnel = remember(reconnects) { VncTunnel(PortForwardingManager()) }
    var rememberedPassword by remember { mutableStateOf<String?>(null) }

    DisposableEffect(tunnel) {
        onDispose { tunnel.stop() }
    }

    // (Re)connect. Keyed on `reconnects` so the Reconnect button is a state bump, not a captured
    // call. The session provider is asked here and now every time: a reconnect that reused the
    // session it started with would dial the tunnel through a transport the reconnect ladder may
    // already have replaced.
    LaunchedEffect(reconnects) {
        val session = request.sessionProvider()
        if (session == null) {
            // Not the tunnel's own failure - the host has no session to ride, and that is the
            // whole story, told in one line the user can act on.
            tunnel.abandon("${request.hostName} is not connected - connect the host first")
        } else {
            tunnel.start(session, request.target, password = rememberedPassword)
        }
    }
    val state by tunnel.state.collectAsStateWithLifecycle()
    val frame by tunnel.frames.collectAsStateWithLifecycle()

    ViewerShell(
        request = request,
        state = state.asDesktopState(),
        frame = frame?.asDesktopFrame(),
        input = remember(tunnel) {
            DesktopInput(
                moveMouse = tunnel::moveMouse,
                mouseButton = tunnel::mouseButton,
                scroll = tunnel::scroll,
                type = tunnel::type,
                requestResolution = tunnel::requestResolution,
            )
        },
        clipboard = remember(tunnel) { DesktopClipboard(tunnel::copyText, tunnel.remoteClipboard) },
        phoneClipboard = phoneClipboard,
        onReconnect = { reconnects++ },
        onClose = onClose,
    )

    when (state) {
        VncTunnelState.AwaitingPassword -> PasswordDialog(
            hostName = request.hostName,
            onSubmit = { password ->
                rememberedPassword = password
                tunnel.submitPassword(password)
            },
            onDisconnect = onClose,
        )
        else -> {}
    }
}

/**
 * The RDP half: an [RdpTunnel] per connection, the NLA sign-in form when the server asks, and
 * the credentials that answer it - the saved ones the request carried when they were complete,
 * the remembered ones once the user has typed an answer here.
 *
 * The saved credential rides along for two different moments: a complete one is handed to
 * [RdpTunnel.start] so the negotiation answers NLA without asking, and whatever the request
 * carried pre-fills the sign-in form for the challenge that still arrives - a server that
 * wants different credentials than were saved, or none that were.
 */
@Composable
private fun RdpViewer(
    request: RemoteDesktopRequest.Rdp,
    phoneClipboard: PhoneClipboard,
    onClose: () -> Unit,
) {
    // Application context: FreeRDP's engine reads its certificate store location from it and
    // holds it for the session's lifetime, which outlives this composition's activity anyway.
    val appContext = LocalContext.current.applicationContext
    var reconnects by remember { mutableStateOf(0) }
    val tunnel = remember(reconnects) { RdpTunnel(PortForwardingManager()) }
    var rememberedCredentials by remember { mutableStateOf<RdpCredentials?>(null) }

    DisposableEffect(tunnel) {
        onDispose { tunnel.stop() }
    }

    LaunchedEffect(reconnects) {
        val session = request.sessionProvider()
        if (session == null) {
            tunnel.abandon("${request.hostName} is not connected - connect the host first")
        } else {
            val credentials = rememberedCredentials
                ?: request.credentials?.takeIf { request.credentialsComplete }
            tunnel.start(
                appContext,
                session,
                request.target,
                username = credentials?.username,
                domain = credentials?.domain,
                password = credentials?.password,
            )
        }
    }
    val state by tunnel.state.collectAsStateWithLifecycle()
    val frame by tunnel.frames.collectAsStateWithLifecycle()

    ViewerShell(
        request = request,
        state = state.asDesktopState(),
        frame = frame?.asDesktopFrame(),
        input = remember(tunnel) {
            DesktopInput(
                moveMouse = tunnel::moveMouse,
                mouseButton = tunnel::mouseButton,
                scroll = tunnel::scroll,
                type = tunnel::type,
                requestResolution = tunnel::requestResolution,
            )
        },
        clipboard = remember(tunnel) { DesktopClipboard(tunnel::copyText, tunnel.remoteClipboard) },
        phoneClipboard = phoneClipboard,
        onReconnect = { reconnects++ },
        onClose = onClose,
    )

    when (state) {
        RdpTunnelState.AwaitingCredentials -> RdpCredentialsDialog(
            hostName = request.hostName,
            prefill = rememberedCredentials ?: request.credentials,
            onSubmit = { username, domain, password ->
                rememberedCredentials = RdpCredentials(
                    username = username,
                    domain = domain.ifBlank { null },
                    password = password,
                )
                tunnel.submitCredentials(username, domain, password)
            },
            onDisconnect = onClose,
        )
        else -> {}
    }
}

/**
 * The protocol-agnostic half of the viewer: the desktop surface, the zoom and pan, the toolbar,
 * the failure panel and the connecting overlay. It renders whatever [state] and [frame] the
 * protocol half collected and drives whatever [input] that half wired - nothing here knows
 * whether the desktop at the other end of the tunnel speaks RFB or RDP, except the three RDP-only
 * affordances (keyboard, resolution, wheel scrolling), which appear exactly when the request is
 * the RDP one because the VNC half arrived without them and leaves unchanged. The clipboard is
 * the shell's one new shared surface: the remote half arrives on [DesktopClipboard.remote] and
 * lands on the phone's clipboard automatically, while the push back is a toolbar button, because
 * the automatic version of that direction would ship everything the user copies to the desktop
 * without asking (see the comment where the button is wired).
 */
@Composable
private fun ViewerShell(
    request: RemoteDesktopRequest,
    state: DesktopState,
    frame: DesktopFrame?,
    input: DesktopInput,
    clipboard: DesktopClipboard,
    phoneClipboard: PhoneClipboard,
    onReconnect: () -> Unit,
    onClose: () -> Unit,
) {
    val activity = LocalActivity.current

    // The orientation the toolbar last asked for. The activity's own orientation (whatever the
    // user's system setting is) is restored on dispose, so leaving the viewer leaves the phone
    // as it was.
    var orientation by remember { mutableStateOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) }
    LaunchedEffect(orientation) { activity?.requestedOrientation = orientation }
    DisposableEffect(Unit) {
        val original = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity?.requestedOrientation = original }
    }

    // Zoom and pan. `scale` is only authoritative in FREE; every other mode recomputes it from
    // the frame and the container, which is what makes display-mode switching live rather than a
    // setting applied on the next connection.
    var mode by remember { mutableStateOf(DisplayMode.FIT_SCREEN) }
    var freeScale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    fun fitScale(forMode: DisplayMode): Float {
        val f = frame ?: return 1f
        if (containerSize == IntSize.Zero || f.width == 0 || f.height == 0) return 1f
        val cw = containerSize.width.toFloat()
        val ch = containerSize.height.toFloat()
        return when (forMode) {
            DisplayMode.FIT_SCREEN -> minOf(cw / f.width, ch / f.height)
            DisplayMode.FIT_WIDTH -> cw / f.width
            DisplayMode.FIT_HEIGHT -> ch / f.height
            DisplayMode.ACTUAL, DisplayMode.FREE -> 1f
        }
    }

    val scale = if (mode == DisplayMode.FREE) freeScale else fitScale(mode)

    /**
     * Keeps the desktop reachable: when it is larger than the screen, its edge may not pass the
     * screen's edge (no scrolling into empty black beyond it); when it is smaller, it may not
     * leave the screen entirely. Both limits are |screen - desktop| / 2, measured from the
     * centred position, and Zero is always inside them - which is why a mode switch can simply
     * drop the offset.
     */
    fun clampOffset(candidate: Offset): Offset {
        val f = frame ?: return candidate
        if (containerSize == IntSize.Zero) return candidate
        val limitX = abs(containerSize.width - f.width * scale) / 2f
        val limitY = abs(containerSize.height - f.height * scale) / 2f
        return Offset(
            candidate.x.coerceIn(-limitX, limitX),
            candidate.y.coerceIn(-limitY, limitY),
        )
    }

    fun takeOverScale(newScale: Float) {
        mode = DisplayMode.FREE
        freeScale = newScale.coerceIn(MIN_SCALE, MAX_SCALE)
    }

    // A mode change recentres: the previous mode's offset belongs to that mode's scale, and
    // keeping it would park the interesting part of the desktop off-screen.
    LaunchedEffect(mode, frame?.width, frame?.height, containerSize) { offset = Offset.Zero }

    // Bumped by every touch, so the toolbar's hide clock restarts on the touch that made it
    // visible again - and on the ones that follow while it is.
    var interaction by remember { mutableStateOf(0) }
    var toolbarVisible by remember { mutableStateOf(true) }

    // The RDP keyboard strip: open only on purpose, and only while there is a desktop to type
    // into that accepts input.
    val rdpControls = request is RemoteDesktopRequest.Rdp && !request.target.viewOnly
    var keyboardOpen by remember { mutableStateOf(false) }

    // The clipboard sync. Remote-to-phone is automatic - a copy on the desktop lands on the
    // phone's clipboard the moment the server announces it, because "copy there, paste here"
    // with no step in between is what sync means. Phone-to-remote is a button instead of a
    // listener, on purpose: an Android clipboard listener fires for *everything* the user
    // copies while the viewer is open, and shipping each of those to a remote machine
    // silently - passwords copied from a manager included - is exfiltration dressed as a
    // feature. The button is the consent: one tap, the clipboard that is on the phone right
    // now goes to the desktop, and nothing else ever does.
    /**
     * The last text this viewer pushed to the desktop. Some servers announce a client's own
     * paste back through the clipboard channel, and the phone's clipboard already holds that
     * text - re-writing it would reset the paste timestamp (and any "copied just now" toast
     * the system shows) on every push, so the echo is dropped instead of round-tripped.
     */
    var lastPushed by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(clipboard, phoneClipboard) {
        clipboard.remote.collect { text ->
            if (!text.isNullOrBlank() && text != lastPushed) {
                // The app's one clipboard route rather than the framework's, which is what puts the
                // configured deadline on this copy and marks it sensitive where the platform can.
                phoneClipboard.put(text)
            }
        }
    }

    // Auto-hide, armed only once there is a desktop to use without a toolbar in the way.
    LaunchedEffect(interaction, state, toolbarVisible) {
        if (toolbarVisible && state is DesktopState.Connected) {
            delay(TOOLBAR_HIDE_DELAY_MS)
            toolbarVisible = false
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { containerSize = it },
    ) {
        when (val s = state) {
            is DesktopState.Failed -> FailurePanel(
                reason = s.reason,
                onReconnect = onReconnect,
                onClose = onClose,
            )
            DesktopState.Closed -> Box(Modifier.fillMaxSize()) // dispose is imminent; panels would only flash
            else -> DesktopSurface(
                frame = frame,
                scale = scale,
                offset = offset,
                onScroll = if (request is RemoteDesktopRequest.Rdp) input.scroll else null,
                onTap = { imagePos ->
                    interaction++
                    val f = frame
                    if (!request.target.viewOnly && state is DesktopState.Connected && f != null) {
                        // Where this position lands in the desktop: undo the zoom. The position
                        // arrives in the image's own coordinates, so this is the whole mapping -
                        // the centring and the pan are already spent. Clamped so a tap on a
                        // rounded pixel at the edge is an edge click, not a click at -3.
                        val fx = (imagePos.x / scale).roundToInt().coerceIn(0, f.width - 1)
                        val fy = (imagePos.y / scale).roundToInt().coerceIn(0, f.height - 1)
                        input.moveMouse(fx, fy)
                        // Positional, because `mouseButton` is a function-type property and
                        // those take no named arguments - the tunnel's own method does.
                        input.mouseButton(1, true)
                        input.mouseButton(1, false)
                    }
                },
                onDoubleTap = { imagePos ->
                    interaction++
                    val f = frame
                    if (f == null) {
                        return@DesktopSurface
                    } else if (mode == DisplayMode.FREE && freeScale > 1.01f) {
                        // Zoomed in: the way back is the fit, not another stop on the way out.
                        mode = DisplayMode.FIT_SCREEN
                    } else {
                        // Zooming in on the tap means the point under the finger stays under the
                        // finger: the offset moves by how much that point's distance from the
                        // centre grows, which is (scale - newScale) per desktop unit.
                        val newScale = (scale * DOUBLE_TAP_SCALE).coerceAtMost(MAX_SCALE)
                        val vX = imagePos.x / scale - f.width / 2f
                        val vY = imagePos.y / scale - f.height / 2f
                        offset = clampOffset(
                            Offset(
                                offset.x + vX * (scale - newScale),
                                offset.y + vY * (scale - newScale),
                            ),
                        )
                        takeOverScale(newScale)
                    }
                },
                onGesture = { centroid, pan, zoomChange ->
                    interaction++
                    // Keep the pinch's focal point stationary: the fingers already moved to
                    // where the desktop should stay, and the pan the detector reports on top of
                    // that is the drag, not the growth. The centroid is in container
                    // coordinates, so the centring and the offset are undone by hand.
                    val centre = Offset(containerSize.width / 2f, containerSize.height / 2f)
                    val fromCentre = centroid - centre - offset
                    val newScale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
                    val grown = offset + fromCentre * (1f - newScale / scale) + pan
                    takeOverScale(newScale)
                    offset = clampOffset(grown)
                },
            )
        }

        if (state is DesktopState.Connecting) {
            Surface(color = Color.Black.copy(alpha = 0.6f), modifier = Modifier.fillMaxSize()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }

        ViewerToolbar(
            visible = toolbarVisible &&
                state !is DesktopState.Closed &&
                state !is DesktopState.Failed,
            hostName = request.hostName,
            viewOnly = request.target.viewOnly,
            mode = mode,
            orientation = orientation,
            onMode = { mode = it },
            onZoomIn = {
                interaction++
                val newScale = scale * 1.25f
                offset = clampOffset(offset * (newScale / scale))
                takeOverScale(newScale)
            },
            onZoomOut = {
                interaction++
                val newScale = scale / 1.25f
                offset = clampOffset(offset * (newScale / scale))
                takeOverScale(newScale)
            },
            onOrientation = {
                interaction++
                orientation = when (orientation) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                    else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            },
            onKeyboard = if (rdpControls) {
                { interaction++; keyboardOpen = !keyboardOpen }
            } else {
                null
            },
            // The clipboard push is offered to both protocols - VNC's copyText and RDP's
            // /clipboard channel speak the same shape - and hidden for a view-only target, the
            // same rule the input controls follow: a desktop that cannot be typed into cannot
            // be pasted into either.
            onClipboard = if (!request.target.viewOnly) {
                {
                    interaction++
                    // A clipboard holding only whitespace has nothing to paste on the far side.
                    val text = phoneClipboard.get()?.takeIf { it.isNotBlank() }
                    if (text != null) {
                        lastPushed = text
                        clipboard.push(text)
                    }
                }
            } else {
                null
            },
            onResolution = if (request is RemoteDesktopRequest.Rdp) input.requestResolution else null,
            matchScreen = containerSize,
            onClose = onClose,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // The keyboard strip the RDP toolbar's keyboard button drops: characters typed here are
        // typed on the desktop, one key press per codepoint.
        if (keyboardOpen && rdpControls && state is DesktopState.Connected) {
            KeyboardStrip(
                onType = input.type,
                onClose = { keyboardOpen = false },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
            )
        }

        // The handle that brings a hidden toolbar back. A plain transparent strip would eat the
        // remote clicks it sits on; a button eats exactly its own size, and says what it does.
        if (!toolbarVisible && !keyboardOpen && state is DesktopState.Connected) {
            IconButton(
                onClick = { interaction++; toolbarVisible = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(40.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
            ) {
                Icon(Icons.Default.ZoomIn, "Show toolbar", tint = Color.White)
            }
        }
    }
}

/**
 * The desktop itself: the frame, scaled and panned, with the gestures.
 *
 * Tap positions are reported in the *image's* coordinates - the position within the drawn
 * desktop, whatever the zoom and pan did to it - so the caller's desktop mapping is a division
 * by the scale and nothing else. The transform gesture, in contrast, reports in the container's
 * coordinates, because a pinch that starts on the letterbox is still a pinch, and confining it
 * to the image would make the black bars dead zones for no reason.
 *
 * The pointerInput modifiers stack, because the gesture detectors each want the whole stream:
 * [detectTransformGestures] consumes drags and pinches, [detectTapGestures] the taps, and - when
 * [onScroll] is wired, the RDP case - a third awaits the wheel events a mouse or trackpad sends
 * and forwards them as remote scrolls, one notch per event.
 */
@Composable
private fun DesktopSurface(
    frame: DesktopFrame?,
    scale: Float,
    offset: Offset,
    onScroll: ((up: Boolean) -> Unit)? = null,
    onTap: (Offset) -> Unit,
    onDoubleTap: (Offset) -> Unit,
    onGesture: (centroid: Offset, pan: Offset, zoomChange: Float) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        if (frame != null) {
            val image = remember(frame.bitmap) { frame.bitmap.asImageBitmap() }
            val density = LocalDensity.current
            val drawWidth = with(density) { (frame.width * scale).toDp() }
            val drawHeight = with(density) { (frame.height * scale).toDp() }
            Image(
                bitmap = image,
                contentDescription = "Remote desktop",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                    .size(drawWidth, drawHeight)
                    .pointerInput(frame.width, frame.height) {
                        detectTapGestures(
                            onTap = onTap,
                            onDoubleTap = onDoubleTap,
                        )
                    },
            )
        }
        // On top of the image, and after it in composition so it is: the whole container is the
        // pinch surface.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ -> onGesture(centroid, pan, zoom) }
                },
        )
        if (onScroll != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type != PointerEventType.Scroll) continue
                                // A wheel's y is the direction Compose's scroll conventions use:
                                // positive is the wheel rolled down, which scrolls the remote
                                // view down - the same direction the desktop under it must go.
                                val wheelY = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                when {
                                    wheelY > 0f -> onScroll(false)
                                    wheelY < 0f -> onScroll(true)
                                }
                            }
                        }
                    },
            )
        }
    }
}

/**
 * The floating toolbar: title, display mode, zoom, orientation, close. Transparent to input
 * except for its own buttons - it is a Row in a Surface, not a full-width bar, so the desktop
 * beside it stays touchable.
 *
 * [onKeyboard] and [onResolution] are the RDP-only controls; null (the VNC case) leaves them out
 * entirely, so the VNC toolbar is exactly the one that shipped. [onClipboard] is the one control
 * both protocols share beyond the originals: the phone's clipboard pushed to whichever desktop
 * is behind the viewer, hidden for view-only targets the way every input is.
 */
@Composable
private fun ViewerToolbar(
    visible: Boolean,
    hostName: String,
    viewOnly: Boolean,
    mode: DisplayMode,
    orientation: Int,
    onMode: (DisplayMode) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onOrientation: () -> Unit,
    onKeyboard: (() -> Unit)? = null,
    onClipboard: (() -> Unit)? = null,
    onResolution: ((width: Int, height: Int) -> Unit)? = null,
    matchScreen: IntSize = IntSize.Zero,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val alpha by animateFloatAsState(if (visible) 1f else 0f, label = "toolbar")
    if (alpha <= 0.01f) return
    Surface(
        color = Color.Black.copy(alpha = 0.65f * alpha),
        contentColor = Color.White,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.padding(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Icon(Icons.Default.DesktopWindows, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text(hostName, style = MaterialTheme.typography.labelLarge)
                if (viewOnly) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Visibility, null, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("view only", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))

            var menuOpen by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { menuOpen = true }) { Text(mode.label) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DisplayMode.entries
                        // FREE is a state the gestures put the viewer in, not one to ask for.
                        .filter { it != DisplayMode.FREE }
                        .forEach { candidate ->
                            DropdownMenuItem(
                                text = { Text(candidate.label) },
                                onClick = {
                                    menuOpen = false
                                    onMode(candidate)
                                },
                            )
                        }
                }
            }
            // Asking the *server* for a different desktop size, which only the RDP display
            // channel can do. The choices are the screen the user is looking at plus the common
            // laptop panel sizes; a server that cannot resize ignores the ask, and the frame's
            // next size report - not this menu - is the truth about what happened.
            if (onResolution != null) {
                var resolutionOpen by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { resolutionOpen = true }) { Text("Resolution") }
                    DropdownMenu(
                        expanded = resolutionOpen,
                        onDismissRequest = { resolutionOpen = false },
                    ) {
                        if (matchScreen != IntSize.Zero) {
                            DropdownMenuItem(
                                text = { Text("Match this screen") },
                                onClick = {
                                    resolutionOpen = false
                                    onResolution(matchScreen.width, matchScreen.height)
                                },
                            )
                        }
                        RDP_RESOLUTION_CHOICES.forEach { (width, height) ->
                            DropdownMenuItem(
                                text = { Text("$width x $height") },
                                onClick = {
                                    resolutionOpen = false
                                    onResolution(width, height)
                                },
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onZoomOut) { Icon(Icons.Default.Remove, "Zoom out") }
            IconButton(onClick = onZoomIn) { Icon(Icons.Default.Add, "Zoom in") }
            IconButton(onClick = onOrientation) {
                val (icon, label) = when (orientation) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ->
                        Icons.Default.Portrait to "Portrait, tap to lock landscape"
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE ->
                        Icons.Default.Landscape to "Landscape, tap for auto-rotate"
                    else -> Icons.Default.ScreenRotation to "Auto-rotate, tap to lock portrait"
                }
                Icon(icon, label)
            }
            if (onKeyboard != null) {
                IconButton(onClick = onKeyboard) { Icon(Icons.Default.Keyboard, "Keyboard") }
            }
            if (onClipboard != null) {
                IconButton(onClick = onClipboard) {
                    Icon(Icons.Default.ContentPaste, "Send the phone's clipboard to the desktop")
                }
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close viewer") }
        }
    }
}

/**
 * The end of a session that was not the user's idea: the reason, one line, and the two things
 * there are to do about it - try again on whatever session the host has by then, or leave.
 */
@Composable
private fun FailurePanel(reason: String, onReconnect: () -> Unit, onClose: () -> Unit) {
    Surface(color = Color.Black.copy(alpha = 0.75f), modifier = Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "The desktop session ended",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                reason,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Spacer(Modifier.height(20.dp))
            Row {
                TextButton(onClick = onReconnect) { Text("Reconnect") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}

/**
 * The VNC authentication prompt. The tunnel is parked mid-handshake until an answer arrives, so
 * this is not a cancellable dialog: the choices are to answer or to disconnect, and dismissing
 * it by touching outside does neither, which is why it is not dismissible that way.
 */
@Composable
private fun PasswordDialog(hostName: String, onSubmit: (String) -> Unit, onDisconnect: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { /* answer or disconnect - there is no third thing to do */ },
        title = { Text("VNC password") },
        text = {
            Column {
                Text("$hostName's VNC server asked for a password.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(password) }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDisconnect) { Text("Disconnect") } },
    )
}

/**
 * The RDP sign-in prompt, the counterpart of [PasswordDialog]: the tunnel is parked mid-NLA
 * until an answer arrives, so this is not a cancellable dialog either - answer or disconnect.
 *
 * [prefill] is whatever the viewer already knows - the credential saved on the host, or the one
 * answered earlier in this viewer's life - so a challenge that arrives anyway starts from there
 * instead of a blank form. The domain is the one optional field, the same rule the credential
 * store lives by.
 */
@Composable
private fun RdpCredentialsDialog(
    hostName: String,
    prefill: RdpCredentials?,
    onSubmit: (username: String, domain: String, password: String) -> Unit,
    onDisconnect: () -> Unit,
) {
    var username by remember { mutableStateOf(prefill?.username ?: "") }
    var domain by remember { mutableStateOf(prefill?.domain ?: "") }
    var password by remember { mutableStateOf(prefill?.password ?: "") }
    AlertDialog(
        onDismissRequest = { /* answer or disconnect - there is no third thing to do */ },
        title = { Text("RDP sign-in") },
        text = {
            Column {
                Text("$hostName's RDP server asked you to sign in.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    singleLine = true,
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    singleLine = true,
                    label = { Text("Domain (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            // NLA is all three fields or nothing - the same rule the store lives by - so an
            // incomplete form cannot be sent to a challenge that would only fail on it.
            TextButton(
                enabled = username.isNotBlank() && password.isNotBlank(),
                onClick = { onSubmit(username.trim(), domain.trim(), password) },
            ) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDisconnect) { Text("Disconnect") } },
    )
}

/**
 * The RDP keyboard: a strip at the bottom of the viewer whose typed characters are typed on the
 * desktop, one key press per codepoint.
 *
 * Appends only, and that is the tunnel's shape, not an oversight: [RdpTunnel.type] speaks
 * Unicode and has no backspace, so a deletion edits the strip's text and nothing else - the
 * characters already sent are on the wire. The strip is a finger hazard the same way the
 * toolbar is, which is why it hides behind a button rather than sitting in the layout.
 */
@Composable
private fun KeyboardStrip(
    onType: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf("") }
    Surface(
        color = Color.Black.copy(alpha = 0.75f),
        contentColor = Color.White,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { next ->
                    if (next.length > text.length && next.startsWith(text)) {
                        onType(next.substring(text.length))
                    }
                    text = next
                },
                singleLine = true,
                placeholder = { Text("Type to the desktop") },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Hide keyboard") }
        }
    }
}
