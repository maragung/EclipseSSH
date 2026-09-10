package dev.eclipse.ssh.ui.remotedesktop

import android.content.pm.ActivityInfo
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
import androidx.compose.material.icons.filled.DesktopWindows
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.eclipse.ssh.ssh.PortForwardingManager
import dev.eclipse.ssh.vnc.VncFrame
import dev.eclipse.ssh.vnc.VncTunnel
import dev.eclipse.ssh.vnc.VncTunnelState
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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

/**
 * The remote desktop, fullscreen and immersive: the frames the tunnel delivers, zoomed and
 * panned, with a floating toolbar over them.
 *
 * The toolbar hides itself a few seconds after the last touch and comes back from a small handle
 * in the corner - the desktop beneath it is what the user came to touch, and a toolbar that
 * stayed would be a permanent hazard zone: every remote click near the top edge would hit a
 * button of ours instead. It only hides while [VncTunnelState.Connected], never in the states
 * where the user is about to act on the viewer rather than the desktop (asking for a password,
 * reporting a failure).
 *
 * Input is mapped by position: a tap on the screen is a remote click wherever it landed, in
 * framebuffer coordinates after the current zoom is undone. A one-finger drag pans the view
 * rather than the remote pointer - on a phone screen the finger *is* the pointer's position, so
 * dragging it would have to mean drag-and-drop, and a viewer that cannot scroll a zoomed desktop
 * is the more broken half of that pair. View-only targets skip the remote half entirely; the
 * local half (pan, zoom, rotate) stays, because none of it reaches the wire.
 *
 * The tunnel is one per connection and the viewer owns it: minted on entry, stopped on exit (or
 * on a Reconnect, which mints the next one). The VNC password typed into the prompt is kept for
 * the life of this screen only - in memory, never persisted - so a reconnect does not ask for
 * it again while the same viewer is open, and closing the viewer forgets it.
 */
@Composable
fun RemoteDesktopScreen(request: VncRequest, onClose: () -> Unit) {
    val activity = LocalActivity.current
    val tunnel = remember { VncTunnel(PortForwardingManager()) }
    var reconnects by remember { mutableStateOf(0) }
    var rememberedPassword by remember { mutableStateOf<String?>(null) }

    // The orientation the toolbar last asked for. The activity's own orientation (whatever the
    // user's system setting is) is restored on dispose, so leaving the viewer leaves the phone
    // as it was.
    var orientation by remember { mutableStateOf(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) }
    DisposableEffect(Unit) {
        val original = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose {
            tunnel.stop()
            activity?.requestedOrientation = original
        }
    }
    LaunchedEffect(orientation) { activity?.requestedOrientation = orientation }

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

    // Auto-hide, armed only once there is a desktop to use without a toolbar in the way.
    LaunchedEffect(interaction, state, toolbarVisible) {
        if (toolbarVisible && state is VncTunnelState.Connected) {
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
            is VncTunnelState.Failed -> FailurePanel(
                reason = s.reason,
                onReconnect = { reconnects++ },
                onClose = onClose,
            )
            VncTunnelState.Closed -> Box(Modifier.fillMaxSize()) // dispose is imminent; panels would only flash
            else -> DesktopSurface(
                frame = frame,
                scale = scale,
                offset = offset,
                onTap = { imagePos ->
                    interaction++
                    val f = frame
                    if (!request.target.viewOnly && state is VncTunnelState.Connected && f != null) {
                        // Where this position lands in the framebuffer: undo the zoom. The
                        // position arrives in the image's own coordinates, so this is the whole
                        // mapping - the centring and the pan are already spent. Clamped so a tap
                        // on a rounded pixel at the edge is an edge click, not a click at -3.
                        val fx = (imagePos.x / scale).roundToInt().coerceIn(0, f.width - 1)
                        val fy = (imagePos.y / scale).roundToInt().coerceIn(0, f.height - 1)
                        tunnel.moveMouse(fx, fy)
                        tunnel.mouseButton(1, pressed = true)
                        tunnel.mouseButton(1, pressed = false)
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
                        // centre grows, which is (scale - newScale) per framebuffer unit.
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

        if (state is VncTunnelState.AwaitingPassword) {
            PasswordDialog(
                hostName = request.hostName,
                onSubmit = { password ->
                    rememberedPassword = password
                    tunnel.submitPassword(password)
                },
                onDisconnect = onClose,
            )
        }

        if (state is VncTunnelState.Connecting) {
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
                state !is VncTunnelState.Closed &&
                state !is VncTunnelState.Failed,
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
            onClose = onClose,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // The handle that brings a hidden toolbar back. A plain transparent strip would eat the
        // remote clicks it sits on; a button eats exactly its own size, and says what it does.
        if (!toolbarVisible && state is VncTunnelState.Connected) {
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
 * desktop, whatever the zoom and pan did to it - so the caller's framebuffer mapping is a
 * division by the scale and nothing else. The transform gesture, in contrast, reports in the
 * container's coordinates, because a pinch that starts on the letterbox is still a pinch, and
 * confining it to the image would make the black bars dead zones for no reason.
 *
 * Two pointerInput modifiers, because the two gesture detectors each want the whole stream:
 * [detectTransformGestures] consumes drags and pinches, [detectTapGestures] the taps.
 */
@Composable
private fun DesktopSurface(
    frame: VncFrame?,
    scale: Float,
    offset: Offset,
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
    }
}

/**
 * The floating toolbar: title, display mode, zoom, orientation, close. Transparent to input
 * except for its own buttons - it is a Row in a Surface, not a full-width bar, so the desktop
 * beside it stays touchable.
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
