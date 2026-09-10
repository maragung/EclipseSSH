package dev.eclipse.ssh

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.documentfile.provider.DocumentFile
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.CONNECT_TIMEOUT_RANGE
import dev.eclipse.ssh.data.model.DEFAULT_CONNECT_TIMEOUT_SECONDS
import dev.eclipse.ssh.data.model.DEFAULT_SOCKS_PORT
import dev.eclipse.ssh.data.model.DEFAULT_SSH_PORT
import dev.eclipse.ssh.data.model.KEEP_ALIVE_RANGE
import dev.eclipse.ssh.data.model.PORT_RANGE
import dev.eclipse.ssh.data.model.ForwardEntry
import dev.eclipse.ssh.data.model.ForwardType
import dev.eclipse.ssh.data.model.decodeForwardRules
import dev.eclipse.ssh.data.model.HostKeyChallenge
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.ProxyType
import dev.eclipse.ssh.data.model.RemoteDesktopTarget
import dev.eclipse.ssh.data.model.decodeRemoteDesktop
import dev.eclipse.ssh.data.model.ServerStats
import dev.eclipse.ssh.data.model.SessionConnectionState
import dev.eclipse.ssh.data.model.isBusy
import dev.eclipse.ssh.data.model.isEnded
import dev.eclipse.ssh.data.model.isLive
import dev.eclipse.ssh.data.model.Snippet
import dev.eclipse.ssh.data.model.SessionTab
import dev.eclipse.ssh.data.model.SftpSessionState
import dev.eclipse.ssh.data.model.TransferDirection
import dev.eclipse.ssh.data.MAX_TRANSFER_RETRIES
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.data.fs.FsEntry
import dev.eclipse.ssh.data.fs.FileSystemProvider
import dev.eclipse.ssh.data.fs.SingleDocumentProvider
import dev.eclipse.ssh.data.model.TransferItem
import dev.eclipse.ssh.data.model.TransferStatus
import dev.eclipse.ssh.data.model.TerminalTheme
import dev.eclipse.ssh.data.model.SyncDirection
import dev.eclipse.ssh.background.EclipseSessionService
import dev.eclipse.ssh.feature.quickconnect.QuickConnectContract
import dev.eclipse.ssh.presentation.AdvancedHostOptions
import dev.eclipse.ssh.presentation.HostFormDraft
import dev.eclipse.ssh.presentation.MAX_LISTED_ENTRIES
import dev.eclipse.ssh.presentation.files.FilesExplorerController
import dev.eclipse.ssh.presentation.files.LOCAL_SESSION_ID
import dev.eclipse.ssh.presentation.files.ellipsizeCrumbs
import dev.eclipse.ssh.presentation.sessionDiagnostics
import dev.eclipse.ssh.presentation.transfersForDisplay
import dev.eclipse.ssh.presentation.AuthFailurePrompt
import dev.eclipse.ssh.presentation.ReconnectPrompt
import dev.eclipse.ssh.presentation.MainUiState
import dev.eclipse.ssh.presentation.MainViewModel
import dev.eclipse.ssh.ui.editor.EditorRequest
import dev.eclipse.ssh.ui.editor.EditorRequests
import dev.eclipse.ssh.ui.editor.TextEditorActivity
import dev.eclipse.ssh.ui.files.ExplorerFileActionsSheet
import dev.eclipse.ssh.ui.files.ExplorerList
import dev.eclipse.ssh.ui.files.ExplorerPropertiesDialog
import dev.eclipse.ssh.ui.files.ExplorerSelectionBar
import dev.eclipse.ssh.ui.files.ExplorerTopBar
import dev.eclipse.ssh.ui.preview.FilePreviewSheet
import dev.eclipse.ssh.ui.remotedesktop.RemoteDesktopActivity
import dev.eclipse.ssh.ui.remotedesktop.RemoteDesktopConfigDialog
import dev.eclipse.ssh.ui.remotedesktop.VncRequest
import dev.eclipse.ssh.ui.remotedesktop.VncRequests
import dev.eclipse.ssh.ui.AdvancedHostSection
import dev.eclipse.ssh.ui.EclipseSuccess
import dev.eclipse.ssh.ui.EclipseTheme
import dev.eclipse.ssh.ui.EclipseWarning
import dev.eclipse.ssh.ui.PortForwardManagerSheet
import dev.eclipse.ssh.ui.rememberDialogBodyMaxHeight
import dev.eclipse.ssh.ssh.GeneratedKeyPair
import dev.eclipse.ssh.ssh.PERMISSION_PRESETS
import dev.eclipse.ssh.ssh.SshKeyAlgorithm
import dev.eclipse.ssh.ssh.SshKeyProbe
import dev.eclipse.ssh.ssh.probeSshKey
import dev.eclipse.ssh.ssh.symbolicPermissions
import dev.eclipse.ssh.data.credentials.HostCredentialUpdate
import dev.eclipse.ssh.data.credentials.KeyEdit
import dev.eclipse.ssh.data.credentials.SecretEdit
import dev.eclipse.ssh.data.credentials.StoredCredentials
import dev.eclipse.ssh.data.credentials.describe
import dev.eclipse.ssh.data.saf.PickedKeyFile
import dev.eclipse.ssh.data.saf.readPickedKeyFile
import dev.eclipse.ssh.ssh.RemoteFile
import dev.eclipse.ssh.ssh.SessionDiagnosticEvent
import dev.eclipse.ssh.ssh.scrub
import dev.eclipse.ssh.data.saf.LocalFile
import androidx.compose.ui.focus.FocusRequester
import dev.eclipse.ssh.terminal.TerminalFrame
import dev.eclipse.ssh.terminal.TerminalKey
import dev.eclipse.ssh.terminal.TerminalSelection
import dev.eclipse.ssh.ui.terminal.TerminalInputBridge
import dev.eclipse.ssh.ui.terminal.TerminalKeyRow
import dev.eclipse.ssh.ui.terminal.TerminalView
import dev.eclipse.ssh.ui.terminal.minTerminalColumns
import dev.eclipse.ssh.ui.terminal.rememberTerminalCellMetrics
import dev.eclipse.ssh.ui.terminal.TerminalMonoFontFamily
import dev.eclipse.ssh.ui.terminal.rememberTerminalLatches
import dev.eclipse.ssh.ui.terminal.terminalTextInset
import dev.eclipse.ssh.terminal.TerminalExportRenderer
import dev.eclipse.ssh.security.BiometricUnlocker
import dev.eclipse.ssh.security.SecureClipboard
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    /**
     * Injected purely so [onResume] can finish a clipboard wipe the previous process did not live to
     * perform. See [SecureClipboard.resumePendingClear]: the clipboard is one of the few pieces of
     * state a killed process leaves behind, and only a focused activity is allowed to touch it.
     */
    @Inject
    lateinit var secureClipboard: SecureClipboard

    /**
     * The most recent deep link. `launchMode="singleTask"` means a second ssh:// intent is
     * delivered to [onNewIntent] on the existing instance rather than through [onCreate], so
     * without this the link was silently dropped whenever the app was already running.
     */
    private val deepLink = MutableStateFlow<HostProfile?>(null)

    /**
     * Set when this launch came from the "reconnect your sessions" notification, and consumed in
     * [onResume] once the activity is genuinely in the foreground.
     */
    private var restoreRequested = false

    /**
     * The one activity-scoped [MainViewModel] — the same instance the composition obtains through
     * `hiltViewModel()`, since both resolve against this activity's `ViewModelStore`. Held here so
     * [onCreate] and [onNewIntent] can hand a Quick Settings tile or widget tap straight to the view
     * model: resolving the last host needs the host repository, which lives on the view model, not
     * the activity. See [maybeQuickConnect].
     */
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate so the splash theme can hand over to
        // Theme.EclipseSSH via postSplashScreenTheme; the manifest declares the splash
        // theme, so skipping this left the splash background as the app's window theme.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLink.value = parseDeepLink(intent?.data)
        noteRestoreRequest(intent)
        maybeQuickConnect(intent)
        setContent {
            val pending by deepLink.collectAsStateWithLifecycle()
            EclipseWorkspace(deepLinkHost = pending, onDeepLinkConsumed = { deepLink.value = null })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseDeepLink(intent.data)?.let { deepLink.value = it }
        noteRestoreRequest(intent)
        maybeQuickConnect(intent)
    }

    override fun onResume() {
        super.onResume()
        // First, and unconditionally: a secret may be sitting on the system clipboard with nothing
        // left to wipe it. This is the first moment in the process's life where the app has window
        // focus, which is what the platform requires before it will let an app read or replace the
        // primary clip.
        runCatching { secureClipboard.resumePendingClear() }
        if (!restoreRequested) return
        // Cleared first so a second resume cannot start the service twice.
        restoreRequested = false
        // Deliberately here rather than in onCreate/onNewIntent: this is the whole point of routing
        // the "reconnect your sessions" notification through the activity. The platform refuses
        // background foreground-service starts from API 31, and an app is unambiguously exempt once
        // it has a resumed activity.
        runCatching {
            ContextCompat.startForegroundService(
                this,
                Intent(this, EclipseSessionService::class.java)
                    .setAction(EclipseSessionService.ACTION_RESTORE),
            )
        }
    }

    /**
     * Records that this launch came from an alert notification asking for the background sessions to
     * be restored. Acted on in [onResume]; see [EclipseSessionService.EXTRA_RESTORE_SESSIONS].
     */
    private fun noteRestoreRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(EclipseSessionService.EXTRA_RESTORE_SESSIONS, false) != true) return
        // Consumed from the intent so returning to an already-created activity later — the intent
        // sticks around via getIntent() — does not reconnect all over again.
        intent.removeExtra(EclipseSessionService.EXTRA_RESTORE_SESSIONS)
        restoreRequested = true
    }

    /**
     * Recognises a Quick Settings tile or home-screen widget tap — both launch this activity with
     * [QuickConnectContract.EXTRA_QUICK_CONNECT_LAST] — and asks the view model to resolve and offer
     * the most-recently-connected host. A launch without the extra falls straight through, which is
     * the ordinary "open the app" path.
     *
     * The extra is consumed from the intent, exactly as [noteRestoreRequest] does, so returning to
     * this `singleTask` activity later — `getIntent()` keeps handing back the launch intent — does
     * not silently redial. The lookup and the dial live on [MainViewModel] because only it holds the
     * host repository; this activity's whole job here is to spot the extra. See [QuickConnectContract].
     */
    private fun maybeQuickConnect(intent: Intent?) {
        if (!QuickConnectContract.isQuickConnect(intent)) return
        intent?.removeExtra(QuickConnectContract.EXTRA_QUICK_CONNECT_LAST)
        viewModel.requestQuickConnectLastHost()
    }

    /**
     * Handles ssh://user@host:port and sftp://user@host:port deep links (declared
     * in the manifest). The parsed profile is offered as a one-off "quick connect"
     * — it is not saved to the vault unless the user explicitly adds it.
     */
    private fun parseDeepLink(uri: Uri?): HostProfile? = parseSshDeepLink(uri)
}

/**
 * Parses an `ssh://` or `sftp://` deep link into a one-off [HostProfile], or null if the URI is not
 * one this app accepts.
 *
 * Top-level and `internal` rather than a private method so the parsing can be tested directly: the
 * manifest exposes these schemes to BROWSABLE intents, so every value here arrives from an
 * untrusted source (any web page can fire one) and each rejection below is load-bearing.
 *
 * Note that any password in the userInfo component is deliberately discarded — a link is never
 * allowed to smuggle in a credential.
 */
internal fun parseSshDeepLink(uri: Uri?): HostProfile? {
    if (uri == null || (uri.scheme != "ssh" && uri.scheme != "sftp")) return null
    val host = uri.host?.takeIf(String::isNotBlank) ?: return null
    val user = uri.userInfo?.substringBefore(':')?.takeIf(String::isNotBlank) ?: "root"
    // Uri.port is -1 when absent and can be any Int when malformed, so it is range-checked
    // rather than trusted.
    val port = if (uri.port in 1..65535) uri.port else 22
    return HostProfile(
        name = "$user@$host",
        host = host,
        username = user,
        port = port,
    )
}

/**
 * Keyboard options for any field that holds a secret.
 *
 * [androidx.compose.ui.text.input.PasswordVisualTransformation] only changes what is *drawn*. With
 * no password keyboard type the field's underlying input type stays ordinary text, so the IME treats
 * a typed passphrase as prose: autocorrect and word suggestions run over it, and it can be committed
 * to the keyboard's personal dictionary and suggestion history. That is a copy of the user's SSH
 * password in storage this app does not own, cannot read and cannot clear — and the next app with a
 * text field may be offered it as a suggestion. `KeyboardType.Password` maps to
 * `TYPE_TEXT_VARIATION_PASSWORD`, which turns all of it off.
 *
 * Shared rather than repeated at each call site so a fifth secret field cannot quietly appear
 * without it. The PIN fields use `NumberPassword` for the same reason over a numeric keypad.
 */
private val SecretFieldKeyboard = androidx.compose.foundation.text.KeyboardOptions(
    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
)

/**
 * The clipboard-paste affordance for any field created by [SecretFieldKeyboard] above.
 *
 * Long-press paste in a password field is unreliable in exactly the situation it is needed most:
 * the IME's toolbar over a `TYPE_TEXT_VARIATION_PASSWORD` field varies by keyboard, and a clip
 * copied by a password manager often carries a trailing newline a `singleLine` field cannot
 * accept. A button the user can see sidesteps both - and [onPaste] goes through the ViewModel, so
 * the read is subject to the same audited clipboard boundary and newline normalization as every
 * other clipboard access in the app.
 *
 * [what] only exists for the screen reader: the Add-host dialog can show a password, a passphrase
 * and a proxy password at once, and three buttons all announcing "Paste" would be a list nobody
 * can tell apart. The paste *replaces* the field's contents rather than appending to them - these
 * fields are never pre-filled from storage, so whatever is in one is either empty or a typo being
 * corrected.
 */
@Composable
private fun SecretPasteButton(what: String, onPaste: () -> String?, into: (String) -> Unit) {
    IconButton(onClick = { onPaste()?.let(into) }) {
        Icon(Icons.Default.ContentPaste, contentDescription = "Paste $what from clipboard")
    }
}

private enum class Destination(val label: String, val icon: ImageVector) {
    HOSTS("Hosts", Icons.Default.Computer),
    TERMINAL("Terminal", Icons.Default.Terminal),
    FILES("Files", Icons.Default.FolderOpen),
    TRANSFERS("Transfers", Icons.Default.SwapVert),
    SETTINGS("Settings", Icons.Default.Settings),
}

/**
 * Writes [bytes] to the SAF document at [uri], off the main thread.
 *
 * Activity-result callbacks are delivered on the main thread, and a document write is a binder
 * round trip into whichever provider owns the URI — which may be a cloud backend that syncs over
 * the network before the stream closes. Doing that inline drops frames at best and blocks long
 * enough to ANR at worst, and a revoked grant or a full volume would have thrown straight out of
 * the callback and taken the process down. The [Result] lets the caller report the failure.
 */
private suspend fun writeDocument(context: Context, uri: Uri, bytes: ByteArray): Result<Unit> =
    withContext(Dispatchers.IO) {
        runCatching {
            val stream = context.contentResolver.openOutputStream(uri, "w")
                ?: throw IOException("The selected location could not be opened for writing")
            stream.use { it.write(bytes) }
        }
    }

@Composable
private fun EclipseWorkspace(
    viewModel: MainViewModel = hiltViewModel(),
    deepLinkHost: HostProfile? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activeHost = state.hosts.firstOrNull { it.id == state.selectedHostId } ?: state.hosts.firstOrNull()
    /**
     * The host a Files-tab transfer is addressed to: the one the explorer is browsing when it is
     * browsing one, and otherwise the host selected elsewhere in the app.
     *
     * The explorer can be pointed at a server the Hosts list never selected - that is the point of
     * its session chips - so a transfer launched from it must not assume [activeHost] or it would
     * land on the wrong server, silently, with the progress row naming the right one. Read from the
     * controller's current value at the moment of the tap rather than captured on recomposition,
     * because the chip the user tapped is state the composition has already left behind.
     */
    val transferHost: () -> HostProfile? = {
        val explorer = viewModel.filesExplorer.state.value
        val hostId = explorer.activeSessionId.takeIf { !explorer.isLocal }?.removePrefix("sftp:")
        state.hosts.firstOrNull { it.id == hostId } ?: activeHost
    }
    /**
     * The directory the explorer is browsing, when it is browsing a server's - the destination a
     * picked upload should land in. Null while the local session is active, so the pickers fall
     * back to the path the ViewModel last listed.
     */
    val explorerRemoteDirectory: () -> String? = {
        val explorer = viewModel.filesExplorer.state.value
        explorer.path.takeIf { !explorer.isLocal }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    // Ask once, and only when the permission is actually missing: launching the request
    // unconditionally re-prompted on every rotation and on API < 33 asked for a permission
    // that does not exist there.
    var notificationsRequested by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needed && !notificationsRequested) {
            notificationsRequested = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // Surfaces the ViewModel's error/status reports; without a host these were collected
    // by nothing and the user saw silence when an import or a transfer failed.
    val snackbarHostState = remember { SnackbarHostState() }
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    LaunchedEffect(statusMessage) {
        val message = statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeStatusMessage()
    }
    val scope = rememberCoroutineScope()
    var pickerActive by remember { mutableStateOf(false) }
    var destination by rememberSaveable { mutableStateOf(Destination.HOSTS) }
    /**
     * The session whose shell is on screen, or null while the Terminal destination is showing its list
     * of sessions.
     *
     * Hoisted out of [TerminalScreen] because it decides more than which tab is selected. With a
     * session open the shell takes the entire window - no top bar, no navigation bar or rail, no status
     * bar - so this is what all of that chrome is keyed on, and none of it lives inside the terminal.
     * [rememberSaveable], so a rotation or a restore after process death comes back to the shell the
     * user was in rather than dropping them into the list.
     */
    var openSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    /**
     * Follows the sessions: a new one takes the screen, and the one being watched gives it back when it
     * ends.
     *
     * Seeded from the sessions that already exist on the first composition rather than starting empty,
     * so returning to a process that still has sessions - a rotation, a restore after process death,
     * coming back from the background - does not count them as new and throw the user into a shell they
     * did not just ask for. Connect sets [openSessionId] itself so the tap feels immediate; this is
     * what gives every other route to a new session (the widget, a deep link, a reconnect from the list)
     * the same behaviour.
     *
     * Ids are dropped as they leave, not remembered forever: closing a session and connecting to the
     * same host again is a new session, and it should open like one.
     */
    val seenSessions = remember { mutableSetOf<String>() }
    var sessionsSeeded by remember { mutableStateOf(false) }
    LaunchedEffect(state.tabs) {
        // Tab ids, not host ids: with more than one shell on a host, each is its own session, and a
        // duplicate opening must take the screen without disturbing the tab already on it.
        val live = state.tabs.map { it.id }
        val appeared = if (sessionsSeeded) live.lastOrNull { it !in seenSessions } else null
        sessionsSeeded = true
        seenSessions.retainAll(live.toSet())
        seenSessions += live
        if (appeared != null) {
            openSessionId = appeared
            destination = Destination.TERMINAL
        } else if (openSessionId != null && openSessionId !in live) {
            // The session being watched ended - closed from the strip, disconnected by the server, or
            // never restored into this process - so the terminal falls back to the list instead of
            // drawing a shell for a session that is not there.
            openSessionId = null
        }
    }
    // The Files explorer keeps its own session list and its own listings, but it cannot see the
    // rest of the app move: a host renamed or deleted in Hosts, a session that has just come up or
    // dropped. Re-keyed on the hosts and the open sessions so its chips never offer a server that
    // is no longer there, and always offer one that has just connected.
    LaunchedEffect(state.hosts.map(HostProfile::id), state.tabs.map(SessionTab::hostId)) {
        viewModel.filesExplorer.refreshSessions()
    }
    var selectedKeyBytes by remember { mutableStateOf<ByteArray?>(null) }
    var selectedKeyName by remember { mutableStateOf<String?>(null) }
    /**
     * The one-off key for the connect prompt.
     *
     * Reads through [readPickedKeyFile], which bounds the read at [MAX_PRIVATE_KEY_BYTES]. The
     * previous `readBytes()` pulled an entire arbitrary document into a ByteArray on the strength of
     * one tap in the system file picker, so choosing a video by mistake was an OutOfMemoryError rather
     * than a message. It also took the name from `uri.lastPathSegment`, which on a `content://`
     * document is a provider-internal id, not a filename.
     */
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        pickerActive = false
        if (uri != null) scope.launch {
            readPickedKeyFile(context, uri)
                .onSuccess { picked ->
                    selectedKeyBytes = picked.bytes
                    selectedKeyName = picked.name
                }
                .onFailure { error ->
                    viewModel.reportUiMessage(error.message ?: "That key file could not be read")
                }
        }
    }
    /**
     * The key being attached in the Add / Edit Host form, held here rather than inside the dialog so a
     * recomposition triggered by the picker returning cannot discard it.
     */
    var formKey by remember { mutableStateOf<PickedKeyFile?>(null) }
    val formKeyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        pickerActive = false
        if (uri != null) scope.launch {
            readPickedKeyFile(context, uri)
                .onSuccess { picked -> formKey = picked }
                .onFailure { error ->
                    viewModel.reportUiMessage(error.message ?: "That key file could not be read")
                }
        }
    }
    var pendingDownload by remember { mutableStateOf<RemoteFile?>(null) }
    val downloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        pickerActive = false
        // The explorer's session, not the app's selected host: the file being downloaded was
        // picked in whichever server the Files tab was browsing - see [transferHost].
        val host = transferHost()
        val remote = pendingDownload
        pendingDownload = null
        if (uri != null && host != null && remote != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.startDownload(host, remote, uri)
        }
    }
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        pickerActive = false
        val host = transferHost()
        if (uris.isNotEmpty() && host != null) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                // Where the explorer is browsing, so the file lands in the folder on screen; the
                // ViewModel's own path only as a fallback, for uploads started outside the tab.
                val directory = explorerRemoteDirectory() ?: state.remotePath ?: viewModel.remoteDirectory(host)
                viewModel.startUpload(host, uri, directory)
            }
        }
    }
    val localFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        pickerActive = false
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.setLocalRoot(uri)
            // The explorer keeps its own root (persisted, so it survives restarts) and its own
            // listing of it; the ViewModel's copy above is what downloads and syncs read.
            viewModel.filesExplorer.setLocalRoot(uri)
        }
    }
    // The Files explorer's full-window surfaces. The preview is a sheet at this root because it is
    // still part of the workspace; the editor is only *triggered* here — it opens in its own
    // activity on top of the app (see the launch effect below), because a document being edited
    // deserves a window of its own rather than a layer over whatever the workspace was showing.
    var editorRequest by remember { mutableStateOf<EditorRequest?>(null) }
    var previewTarget by remember { mutableStateOf<PreviewTarget?>(null) }
    // The Transfers tab's per-item sheet, held here (rather than inside the screen) for the same
    // reason the preview target is: its file actions resolve against this workspace's context,
    // clipboard and overlays, none of which the screen should know about.
    var transferActionsFor by remember { mutableStateOf<TransferItem?>(null) }
    var pendingExportPassphrase by remember { mutableStateOf<String?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var pendingTextExport by remember { mutableStateOf<ByteArray?>(null) }
    var pendingScreenExport by remember { mutableStateOf<ByteArray?>(null) }
    var pendingAccountExportHost by remember { mutableStateOf<HostProfile?>(null) }
    var pendingAccountImportUri by remember { mutableStateOf<Uri?>(null) }
    var showAccountExportDialog by remember { mutableStateOf(false) }
    var showAccountImportDialog by remember { mutableStateOf(false) }
    var showKeyGenDialog by remember { mutableStateOf(false) }
    var pendingKeyPair by remember { mutableStateOf<GeneratedKeyPair?>(null) }
    val pubKeyPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        pickerActive = false
        val pair = pendingKeyPair
        pendingKeyPair = null
        if (uri != null && pair != null) scope.launch {
            writeDocument(context, uri, pair.publicLine.toByteArray(Charsets.UTF_8))
                .onFailure { viewModel.reportUiFailure("Could not save public key", it) }
        }
    }
    val privateKeyPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-pem-file")) { uri: Uri? ->
        pickerActive = false
        val pair = pendingKeyPair
        if (uri != null && pair != null) {
            // Hold pickerActive across the write so the PIN lock cannot re-arm in the gap before
            // the public-key picker opens, and only chain it once the private key has landed.
            pickerActive = true
            scope.launch {
                writeDocument(context, uri, pair.privatePem.toByteArray(Charsets.UTF_8))
                    .onSuccess { pubKeyPicker.launch("${pair.defaultPrivateName}.pub") }
                    .onFailure {
                        pickerActive = false
                        pendingKeyPair = null
                        viewModel.reportUiFailure("Could not save private key", it)
                    }
            }
        } else {
            pendingKeyPair = null
        }
    }
    val configImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        pickerActive = false
        if (uri != null) viewModel.importOpenSshConfig(uri)
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        pickerActive = false
        val pass = pendingExportPassphrase
        pendingExportPassphrase = null
        if (uri != null && pass != null) viewModel.exportVault(pass, uri)
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        pickerActive = false
        if (uri != null) { pendingImportUri = uri; showImportDialog = true }
    }
    val textExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        pickerActive = false
        val bytes = pendingTextExport
        pendingTextExport = null
        if (uri != null && bytes != null) scope.launch {
            writeDocument(context, uri, bytes)
                .onFailure { viewModel.reportUiFailure("Could not export terminal text", it) }
        }
    }
    val screenExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri: Uri? ->
        pickerActive = false
        val bytes = pendingScreenExport
        pendingScreenExport = null
        if (uri != null && bytes != null) scope.launch {
            writeDocument(context, uri, bytes)
                .onFailure { viewModel.reportUiFailure("Could not export screenshot", it) }
        }
    }
    var pendingAccountExportPassphrase by remember { mutableStateOf<String?>(null) }
    val accountExportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        pickerActive = false
        val host = pendingAccountExportHost
        val pass = pendingAccountExportPassphrase
        pendingAccountExportHost = null
        pendingAccountExportPassphrase = null
        if (uri != null && host != null && pass != null) viewModel.exportAccount(host, pass, uri)
    }
    val accountImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        pickerActive = false
        if (uri != null) { pendingAccountImportUri = uri; showAccountImportDialog = true }
    }
    fun connectAndStart(host: HostProfile, password: String?, passphrase: String?) {
        // The key material is handed over unparsed. `SshKeyLoader.load` used to run right here, inside
        // the Connect button's onClick: an encrypted OpenSSH key derives its wrapping key with
        // bcrypt-pbkdf, deliberately slow, and on a 16-round key that is seconds of work on the main
        // thread — a visible freeze on the tap that starts a connection, and an ANR on a slow device.
        // MainViewModel.resolveCredentials loads it on Dispatchers.Default instead, and reports a
        // failure to parse rather than silently connecting with no key as the `getOrNull()` here did.
        viewModel.connect(host, password, null, selectedKeyBytes, passphrase?.takeIf { it.isNotBlank() })
        // Guarded like the app's other two starts, which this one alone was not. Reaching here means
        // a resumed activity, and a resumed activity is the platform's own exemption from the API 31+
        // ban on background foreground-service starts — so a refusal should be impossible. "Should be
        // impossible" is not a reason to let an OEM's SecurityException land on the Connect tap and
        // take the process down with a session already connecting behind it, which is indistinguishable
        // from the app crashing on connect. Nothing about the session depends on the service; only
        // surviving minimise does, so the honest outcome is to say so and go to the terminal anyway.
        runCatching {
            ContextCompat.startForegroundService(
                context,
                // With an action, because an actionless intent is how the platform reports "your
                // process was killed and I restarted the service", and the service answers that by
                // restoring every registered session. Tapping Connect is not that: the UI is dialling
                // this host itself, right now, and a restore pass alongside it was a second dialler
                // racing the first. See [EclipseSessionService.ACTION_TRACK].
                Intent(context, EclipseSessionService::class.java).setAction(EclipseSessionService.ACTION_TRACK),
            )
        }.onFailure { viewModel.reportUiFailure("Sessions will not survive minimising", it) }
        // Straight into the shell, full screen: the list of sessions is somewhere to come back to, not
        // somewhere to pass through on the way in.
        //
        // The tab this dial will speak for, resolved here the same way connect() resolves its session
        // key: an existing tab of this host is the one a reconnect re-dials, and a host with no tab
        // yet gets one filed under the host's own id (the multi-session rule that keeps a lone shell
        // byte-identical with how it was keyed before). By the time the tab appears, the effect above
        // would hand it the screen anyway - setting it here is what makes the tap feel immediate.
        openSessionId = state.tabs.firstOrNull { it.hostId == host.id }?.id ?: host.id
        destination = Destination.TERMINAL
    }
    /**
     * Saves what the refused-login dialog collected, so the next connect is one tap again.
     *
     * The key is probed off the main thread because an encrypted OpenSSH key derives its wrapping
     * key with bcrypt-pbkdf, deliberately slow — see the comment in [connectAndStart] for why that
     * work must never run inside a click. The save also does not block the retry it follows: the
     * dial takes the typed credentials directly (caller-supplied beats stored in
     * `resolveCredentials`), so the two proceed in parallel.
     */
    fun saveAnsweredCredentials(host: HostProfile, password: String?, passphrase: String?) {
        val typedPassword = password?.takeIf(String::isNotBlank)
        val typedPassphrase = passphrase?.takeIf(String::isNotBlank)
        val keyBytes = selectedKeyBytes
        val keyName = selectedKeyName
        scope.launch {
            val keyEdit = if (keyBytes == null || keyName == null) {
                KeyEdit.Keep
            } else {
                // Only a key that parses is stored — the Add Host form holds the same line, for the
                // same reason: a saved key that cannot authenticate is indistinguishable from a
                // server refusing the account at the next connect. The retry itself still goes out
                // with the picked bytes and reports a parse failure in its own words.
                when (val probe = probeSshKey(keyBytes, keyName, typedPassphrase)) {
                    is SshKeyProbe.Ready -> KeyEdit.Replace(keyBytes, keyName, probe.type)
                    else -> KeyEdit.Keep
                }
            }
            viewModel.saveHost(
                host,
                HostCredentialUpdate(
                    password = typedPassword?.let(SecretEdit::Replace) ?: SecretEdit.Keep,
                    key = keyEdit,
                    passphrase = typedPassphrase?.let(SecretEdit::Replace) ?: SecretEdit.Keep,
                ),
            )
        }
    }
    // Applied here rather than once in onCreate because the setting can be toggled while the app is
    // open, and the flag has to follow it in both directions. Keyed on the setting, so a recomposition
    // for any other reason does not touch the window. Placed above the lock gate below, so the flag is
    // already set while the PIN screen is on top.
    val blockScreenshots = state.settings.blockScreenshots
    DisposableEffect(blockScreenshots) {
        val window = (context as? android.app.Activity)?.window
        if (blockScreenshots) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        // Nothing to undo: the next value of the setting decides the flag, and clearing it on dispose
        // would drop the protection for the instant between a recomposition and the effect re-running.
        onDispose { }
    }
    val unlocker = remember { BiometricUnlocker() }
    /**
     * Whether this device can actually satisfy a biometric prompt.
     *
     * Re-read on every resume rather than once: enrolling a fingerprint means leaving the app for
     * Settings and coming back, and a sensor can also become unavailable while the app is in the
     * background. This decides whether the lock screen offers "Use biometric" at all - a button that
     * can only ever fail is worse than no button, because the vault is locked and the user is left
     * guessing which of the two ways in is broken.
     */
    var biometricUsable by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val refresh = {
            biometricUsable = (context as? FragmentActivity)?.let(unlocker::canAuthenticate) == true
        }
        refresh()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Turning the switch on requires a successful prompt, so the setting can never claim a
    // protection the device will not actually give. Failure has to be *reported*, though: with the
    // error callback dropped, a device with nothing enrolled showed a switch that flicked back with
    // no explanation and no way to find out why, which is indistinguishable from a broken app.
    fun setBiometric(enabled: Boolean) {
        if (!enabled) {
            viewModel.setBiometricUnlock(false)
            return
        }
        val activity = context as? FragmentActivity
        if (activity == null) {
            viewModel.reportUiMessage("Biometric unlock is unavailable here")
            return
        }
        // Asked before the prompt is raised, so a device with no sensor or nothing enrolled says which
        // of those it is instead of flashing a sheet that immediately fails with "Authentication
        // failed" - see [BiometricUnlocker.unavailableReason].
        val unavailable = unlocker.unavailableReason(activity)
        if (unavailable != null) {
            viewModel.reportUiMessage("Biometric unlock not enabled: $unavailable")
            return
        }
        unlocker.authenticate(
            activity,
            { viewModel.setBiometricUnlock(true) },
            { reason -> viewModel.reportUiMessage("Biometric unlock not enabled: $reason") },
        )
    }
    var showAddHost by remember { mutableStateOf(false) }
    var showEditHost by remember { mutableStateOf<HostProfile?>(null) }
    var showHostDetails by remember { mutableStateOf<HostProfile?>(null) }
    // The host whose port-forwarding manager sheet is open, by id rather than by profile: the sheet
    // edits the host's saved rules, so it must read the *current* profile from the hosts flow every
    // recomposition - a snapshot taken at open time would keep showing the pre-save rules after its
    // own Save button, and would keep showing a host the user has since removed.
    var forwardManagerHostId by remember { mutableStateOf<String?>(null) }
    // The host whose remote-desktop endpoint dialog is open, by id for the same reason as the
    // forwarding manager above: the dialog saves into the live profile, and a snapshot taken at
    // open time would write a stale host back over a change made elsewhere while it was open.
    var remoteDesktopHostId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteHost by remember { mutableStateOf<HostProfile?>(null) }
    // The host whose duplication is waiting on the "also copy the forwarding rules?" answer. Null
    // when nothing is pending; a host with no rules never lands here at all, so the only dialog a
    // rules-free Duplicate can raise is one some other action opened.
    var duplicateAskHost by remember { mutableStateOf<HostProfile?>(null) }
    var duplicateForwards by remember { mutableStateOf(true) }
    var showAuthHost by remember { mutableStateOf<HostProfile?>(deepLinkHost) }
    // A deep link arriving while the app is already running updates deepLinkHost, so open
    // the auth prompt for it and let the activity clear the pending value.
    LaunchedEffect(deepLinkHost) {
        deepLinkHost?.let {
            showAuthHost = it
            onDeepLinkConsumed()
        }
    }
    // A duplicate with rules is a question, not a command: "same box, different purpose" usually
    // wants the tunnels too, but the copy that does not want them must not have to delete them one
    // by one afterwards. A rules-free host has nothing to ask about and duplicates straight away.
    val requestDuplicateHost: (HostProfile) -> Unit = { host ->
        if (host.savedForwards.isBlank()) {
            viewModel.duplicateHost(host)
        } else {
            duplicateForwards = true
            duplicateAskHost = host
        }
    }
    // The remote desktop opens in its own window (see [RemoteDesktopActivity]) through the same
    // single-shot token handoff the editor uses - the request carries a live session provider an
    // intent cannot parcel. Built per call rather than remembered: the provider must close over
    // the view model, never over one session, so a reconnect inside the viewer re-asks it.
    val openRemoteDesktop: (HostProfile, RemoteDesktopTarget) -> Unit = { host, target ->
        val token = VncRequests.put(VncRequest(host.name, target, viewModel.vncSessionProvider(host.id)))
        runCatching {
            context.startActivity(
                Intent(context, RemoteDesktopActivity::class.java)
                    .putExtra(RemoteDesktopActivity.EXTRA_REQUEST_TOKEN, token),
            )
        }.onFailure { error ->
            // The token was consumed by put; if the launch failed it is gone, and the next tap
            // mints a fresh one - so this is only ever a message, never a stuck state.
            viewModel.reportUiFailure("Could not open the remote desktop", error)
        }
    }
    // The menu's Remote desktop item: a saved, enabled target opens the viewer straight away;
    // anything else - never configured, or parked with "Offer in the menu" off - opens the
    // endpoint dialog, because the entry point exists precisely so a first use does not have to
    // hunt for a settings screen before it can type a port.
    val requestRemoteDesktop: (HostProfile) -> Unit = { host ->
        val target = decodeRemoteDesktop(host.remoteDesktop).vnc
        if (target != null && target.enabled) {
            openRemoteDesktop(host, target)
        } else {
            remoteDesktopHostId = host.id
        }
    }
    // A Quick Settings tile or home-screen widget tap resolves, on the view model, to the
    // most-recently-connected host and arrives here as a pending value. Route it through the same
    // auth prompt a deep link uses: a locked vault still gates it (showAuthHost is only shown in the
    // unlocked branch below, so a tap made while locked fires the instant the PIN is entered), and a
    // host with saved credentials still connects in one step. Null while nothing is pending, so this
    // sits idle on a normal open.
    val quickConnectHost by viewModel.pendingQuickConnect.collectAsStateWithLifecycle()
    LaunchedEffect(quickConnectHost) {
        quickConnectHost?.let {
            showAuthHost = it
            viewModel.consumeQuickConnect()
        }
    }
    // Deliberately `remember`, NOT `rememberSaveable`: this is a security gate, so it has to fail
    // closed. rememberSaveable persists into the saved-instance-state Bundle, which survives
    // system-initiated process death — and the ON_STOP re-lock below cannot clear it in time,
    // because ProcessLifecycleOwner debounces ON_STOP by 700ms while onSaveInstanceState runs
    // immediately after onStop on API 28+. The Bundle was therefore written with `true`, and
    // returning to a background-killed process skipped the lock screen entirely. `remember` still
    // survives rotation (MainActivity handles those configChanges itself, so it is never
    // recreated); anything that does recreate the activity now re-locks, which is the safe default.
    var unlocked by remember { mutableStateOf(false) }

    // Re-lock automatically when the app leaves the foreground, so an unlocked
    // vault is never left exposed in the background. A file picker (SAF) briefly
    // stops the activity too, so we skip re-locking while one is in flight.
    val pinEnabled by rememberUpdatedState(state.settings.pinEnabled)
    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && pinEnabled && !pickerActive) unlocked = false
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
        onDispose { ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) }
    }

    /**
     * True while a shell has the window to itself.
     *
     * The terminal is the one destination that is not a document. Every other screen is a column of
     * cards that scrolls; a terminal is a grid whose size is sent to the remote pty, so every row of
     * chrome around it is a row the shell does not get - and on a phone in landscape, the difference
     * between a usable `vim` and an unusable one. With a session open the app therefore drops the top
     * bar, the navigation bar or rail, and the system bars, which is what PuTTY and JuiceSSH do and what
     * full screen has to mean for a terminal.
     *
     * Never true behind the lock screen: the PIN gate composes instead of the workspace, so hiding the
     * system bars for it would leave a locked app with no navigation at all. And never true for a
     * session that is not there - a saved id whose process died takes the user to the list, not to a
     * chrome-less screen with nothing in it.
     */
    val terminalImmersive = destination == Destination.TERMINAL &&
        !(state.settings.pinEnabled && !unlocked) &&
        openSessionId?.let { id -> state.tabs.any { it.id == id } } == true
    // Back leaves the shell, not the app. A full-screen terminal has no navigation on screen, so
    // without this the only way out of a session is the gesture that closes the whole app - and the
    // session with it.
    BackHandler(enabled = terminalImmersive) { openSessionId = null }
    /**
     * Hides the status and navigation bars while the shell is on screen, and puts them back afterwards.
     *
     * [WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE] rather than a permanent
     * hide: a swipe from the edge brings the bars back over the terminal for as long as they are
     * wanted, so nothing becomes unreachable - which matters on a gesture-navigation device, where the
     * back gesture lives in that same edge.
     *
     * The restore is in `onDispose`, so leaving the session, the activity being destroyed and the
     * composition going away all put the bars back. Without that, a session left open would take the
     * system bars away from whatever the user went to next. `runCatching` because this is decoration:
     * an OEM window implementation that refuses an inset call must not take the process down with a
     * live session behind it, and the terminal is perfectly usable with the bars still showing.
     */
    val localView = LocalView.current
    DisposableEffect(terminalImmersive) {
        val window = (localView.context as? android.app.Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, localView) }
        if (terminalImmersive && controller != null) runCatching {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (terminalImmersive && controller != null) runCatching {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    EclipseTheme(darkTheme = state.settings.darkTheme) {
    if (state.settings.pinEnabled && !unlocked) {
        LockScreen(
            biometricEnabled = state.settings.biometricUnlock && biometricUsable,
            verifyPin = viewModel::verifyPin,
            onUnlocked = { unlocked = true },
            // Reported into the lock screen's own error slot rather than through
            // `statusMessage`: the snackbar host lives inside the workspace scaffold, which is the
            // branch *not* composed while the app is locked, so a snackbar raised from here would
            // never be seen. Before this, "Use biometric" on a device with nothing enrolled did
            // nothing whatsoever.
            onBiometricUnlock = { onFailure ->
                val activity = context as? FragmentActivity
                if (activity == null) onFailure("Biometric unlock is unavailable here")
                else unlocker.authenticate(activity, { unlocked = true }, onFailure)
            },
        )
    } else {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val isWide = maxWidth >= 700.dp
        if (isWide) {
            Row(Modifier.fillMaxSize()) {
                // Gone while a shell owns the window - see [terminalImmersive]. Back, or the strip's
                // own button, brings it straight back.
                if (!terminalImmersive) NavigationRail(
                    modifier = Modifier.fillMaxHeight().padding(start = 12.dp, top = 18.dp, bottom = 18.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    Text("E", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(18.dp))
                    Spacer(Modifier.height(20.dp))
                    Destination.entries.forEach { item ->
                        NavigationRailItem(
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = { Icon(item.icon, item.label) },
                            label = { Text(item.label, maxLines = 1) },
                        )
                    }
                }
                WorkspaceScaffold(
                    destination = destination,
                    state = state,
                    frames = viewModel.frames,
                    filesExplorer = viewModel.filesExplorer,
                    onPreviewFile = { entry, provider -> previewTarget = PreviewTarget(entry, provider) },
                    onEditFile = { entry, provider -> editorRequest = EditorRequest(entry, provider) },
                    onDestination = { destination = it },
                    onSearch = viewModel::setQuery,
                    onAddHost = { showAddHost = true },
                    onConnect = { host -> showAuthHost = host },
                    onShowDetails = { showHostDetails = it },
                    onEditHost = { showEditHost = it },
                    onRemoveHost = { pendingDeleteHost = it },
                    onToggleFavoriteHost = { viewModel.saveHost(it.copy(isFavorite = !it.isFavorite)) },
                    onExportAccount = { pendingAccountExportHost = it; showAccountExportDialog = true },
                    onDuplicateHost = requestDuplicateHost,
                    onManageForwards = { forwardManagerHostId = it.id },
                    onRemoteDesktop = requestRemoteDesktop,
                    onWakeOnLan = viewModel::wakeHost,
                    onCloseTab = viewModel::closeTab,
                    onDuplicateSession = viewModel::duplicateSession,
                    onDisconnectAll = viewModel::disconnectAll,
                    openSessionId = openSessionId,
                    onOpenSession = { openSessionId = it },
                    immersive = terminalImmersive,
                    onSendInput = viewModel::sendInput,
                    onSendText = viewModel::sendText,
                    onSendKey = viewModel::sendKey,
                    onSendChar = viewModel::sendChar,
                    onResizeTerminal = viewModel::resizeTerminal,
                    onScrollTerminal = viewModel::scrollTerminalBy,
                    onScrollTerminalTo = viewModel::scrollTerminal,
                    onCopySelection = { hostId, sel ->
                        val range = sel.toTextRange()
                        val text = viewModel.terminalSelectionText(hostId, range.fromLine, range.fromColumn, range.toLine, range.toColumn)
                        if (text.isNotEmpty()) viewModel.copyToClipboard(text)
                    },
                    onCopyTerminalText = viewModel::copyToClipboard,
                    onCopyTrace = viewModel::copyToClipboard,
                    onPasteTerminal = viewModel::pasteFromClipboard,
                    onSaveSnippet = viewModel::saveSnippet,
                    onDeleteSnippet = viewModel::deleteSnippet,
                    onSaveLogs = { hostId, text ->
                        pendingTextExport = ("Eclipse SSH session log\nHost: $hostId\nSaved: ${System.currentTimeMillis()}\n\n$text").toByteArray()
                        pickerActive = true
                        textExportPicker.launch("eclipse-$hostId.log")
                    },
                    onSaveText = { hostId, text ->
                        pendingTextExport = text.toByteArray()
                        pickerActive = true
                        textExportPicker.launch("eclipse-$hostId.txt")
                    },
                    onSaveScreen = { hostId, text ->
                        // Rendered off the main thread. The bitmap is clamped to 2048x4096, so this
                        // allocates up to ~33 MB and then PNG-compresses it — far too much to run in a
                        // click handler, where it janked and risked an ANR on a long scrollback.
                        // The picker is launched from inside the coroutine, after the bytes exist, so
                        // the result callback can never observe pendingScreenExport still null.
                        pickerActive = true
                        scope.launch {
                            pendingScreenExport = withContext(Dispatchers.Default) {
                                TerminalExportRenderer.renderScreenPng("Eclipse SSH · $hostId", text)
                            }
                            screenExportPicker.launch("eclipse-$hostId.png")
                        }
                    },
                    onClearCompleted = viewModel::clearCompletedTransfers,
                    onUpload = { pickerActive = true; uploadPicker.launch(arrayOf("*/*")) },
                    onDownloadFile = { remote ->
                        // The explorer's session, not merely the selected host - see [transferHost].
                        val host = transferHost()
                        if (host != null) {
                            if (state.localDirUri != null) viewModel.downloadToLocal(host, remote)
                            else { pendingDownload = remote; pickerActive = true; downloadPicker.launch(remote.name) }
                        }
                    },
                    onPickLocalFolder = { pickerActive = true; localFolderPicker.launch(null) },
                    onUploadLocal = { file -> transferHost()?.let { viewModel.uploadLocal(it, file) } },
                    onScheduleDownload = { files, scheduledAt, repeatMinutes -> transferHost()?.let { host -> files.forEach { viewModel.scheduleDownload(host, it, scheduledAt, repeatMinutes) } } },
                    onScheduleUpload = { files, scheduledAt, repeatMinutes -> transferHost()?.let { host -> files.forEach { viewModel.scheduleUpload(host, it, scheduledAt, repeatMinutes) } } },
                    onSync = { direction -> transferHost()?.let { host -> if (direction == SyncDirection.LOCAL_TO_REMOTE) viewModel.syncToRemote(host) else viewModel.syncFromRemote(host) } },
                    onSendToHost = { file, destHost, destPath -> transferHost()?.let { viewModel.sendRemoteTo(it, file, destHost, destPath) } },
                    onPauseTransfer = viewModel::pauseTransfer,
                    onResumeTransfer = viewModel::resumeTransfer,
                    onCancelTransfer = viewModel::cancelTransfer,
                    onPauseAllTransfers = viewModel::pauseAllTransfers,
                    onResumeAllTransfers = viewModel::resumeAllTransfers,
                    onCancelAllTransfers = viewModel::cancelAllTransfers,
                    onRunTransferNow = viewModel::runTransferNow,
                    onOpenTransferActions = { transferActionsFor = it },
                    onAddForward = { type, localPort, remoteHost, remotePort ->
                        activeHost?.let { host ->
                            when (type) {
                                ForwardType.LOCAL -> viewModel.startLocalForward(host, localPort, remoteHost ?: "127.0.0.1", remotePort ?: localPort)
                                ForwardType.REMOTE -> viewModel.startRemoteForward(host, remotePort ?: localPort, localPort)
                                ForwardType.DYNAMIC -> viewModel.startDynamicForward(host, localPort)
                            }
                        }
                    },
                    onStopForward = viewModel::stopForwarding,
                    onExportVault = { showExportDialog = true },
                    onImportVault = { pickerActive = true; importPicker.launch(arrayOf("*/*")) },
                    onImportAccount = { pickerActive = true; accountImportPicker.launch(arrayOf("*/*")) },
                    onGenerateKey = { showKeyGenDialog = true },
                    onImportSshConfig = { pickerActive = true; configImportPicker.launch(arrayOf("text/plain", "*/*")) },
                    onBiometric = ::setBiometric,
                    onDarkTheme = viewModel::setDarkTheme,
                    onKeepAlive = viewModel::setKeepAliveSeconds,
                    onReconnectBase = viewModel::setReconnectBaseSeconds,
                    onClipboard = viewModel::setClipboardSeconds,
                    onTerminalFontSize = viewModel::setTerminalFontSize,
                    onTerminalKeyRow = viewModel::setTerminalKeyRowVisible,
                    onTerminalMinColumns = viewModel::setTerminalMinColumns,
                    onLegacyAlgorithms = viewModel::setLegacyAlgorithms,
                    onBlockScreenshots = viewModel::setBlockScreenshots,
                    onReconnectAskFirst = viewModel::setReconnectAskFirst,
                    onTerminalTheme = viewModel::setTerminalTheme,
                    onSetPin = viewModel::setPin,
                    onClearPin = viewModel::clearPin,
                    verifyPin = viewModel::verifyPin,
                    onForgetKnownHost = viewModel::forgetKnownHost,
                    onClearKnownHosts = viewModel::clearKnownHosts,
                    onForgetAllCredentials = viewModel::forgetAllCredentials,
                    onCopyDiagnostics = { viewModel.copyToClipboard(viewModel.exportDiagnostics()) },
                    onSaveDiagnostics = {
                        pendingTextExport = viewModel.exportDiagnostics().toByteArray()
                        pickerActive = true
                        textExportPicker.launch("eclipse-diagnostics.log")
                    },
                    onClearDiagnostics = viewModel::clearDiagnostics,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Scaffold(
                // Placed by the Scaffold rather than overlaid on the whole window, which is what the
                // single host at the bottom of the outer Box used to do: the snackbar covered the
                // navigation bar, so for as long as one was showing — four seconds, and every status
                // and error message raises one — the five tabs were untappable, and a tap during that
                // window was swallowed with no visible effect. The Scaffold offsets its snackbar above
                // bottomBar and the system insets, which is what Material 3 specifies. The wide
                // layout keeps the overlay: its navigation is a rail down the left side, so nothing
                // is underneath.
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    // Gone while a shell owns the window - see [terminalImmersive].
                    if (!terminalImmersive) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        Destination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item,
                                onClick = { destination = item },
                                icon = { Icon(item.icon, item.label) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                },
                containerColor = MaterialTheme.colorScheme.background,
            ) { padding ->
                WorkspaceScaffold(
                    destination = destination,
                    state = state,
                    frames = viewModel.frames,
                    filesExplorer = viewModel.filesExplorer,
                    onPreviewFile = { entry, provider -> previewTarget = PreviewTarget(entry, provider) },
                    onEditFile = { entry, provider -> editorRequest = EditorRequest(entry, provider) },
                    onDestination = { destination = it },
                    onSearch = viewModel::setQuery,
                    onAddHost = { showAddHost = true },
                    onConnect = { host -> showAuthHost = host },
                    onShowDetails = { showHostDetails = it },
                    onEditHost = { showEditHost = it },
                    onRemoveHost = { pendingDeleteHost = it },
                    onToggleFavoriteHost = { viewModel.saveHost(it.copy(isFavorite = !it.isFavorite)) },
                    onExportAccount = { pendingAccountExportHost = it; showAccountExportDialog = true },
                    onDuplicateHost = requestDuplicateHost,
                    onManageForwards = { forwardManagerHostId = it.id },
                    onRemoteDesktop = requestRemoteDesktop,
                    onWakeOnLan = viewModel::wakeHost,
                    onCloseTab = viewModel::closeTab,
                    onDuplicateSession = viewModel::duplicateSession,
                    onDisconnectAll = viewModel::disconnectAll,
                    openSessionId = openSessionId,
                    onOpenSession = { openSessionId = it },
                    immersive = terminalImmersive,
                    onSendInput = viewModel::sendInput,
                    onSendText = viewModel::sendText,
                    onSendKey = viewModel::sendKey,
                    onSendChar = viewModel::sendChar,
                    onResizeTerminal = viewModel::resizeTerminal,
                    onScrollTerminal = viewModel::scrollTerminalBy,
                    onScrollTerminalTo = viewModel::scrollTerminal,
                    onCopySelection = { hostId, sel ->
                        val range = sel.toTextRange()
                        val text = viewModel.terminalSelectionText(hostId, range.fromLine, range.fromColumn, range.toLine, range.toColumn)
                        if (text.isNotEmpty()) viewModel.copyToClipboard(text)
                    },
                    onCopyTerminalText = viewModel::copyToClipboard,
                    onCopyTrace = viewModel::copyToClipboard,
                    onPasteTerminal = viewModel::pasteFromClipboard,
                    onSaveSnippet = viewModel::saveSnippet,
                    onDeleteSnippet = viewModel::deleteSnippet,
                    onSaveLogs = { hostId, text ->
                        pendingTextExport = ("Eclipse SSH session log\nHost: $hostId\nSaved: ${System.currentTimeMillis()}\n\n$text").toByteArray()
                        pickerActive = true
                        textExportPicker.launch("eclipse-$hostId.log")
                    },
                    onSaveText = { hostId, text ->
                        pendingTextExport = text.toByteArray()
                        pickerActive = true
                        textExportPicker.launch("eclipse-$hostId.txt")
                    },
                    onSaveScreen = { hostId, text ->
                        // Rendered off the main thread. The bitmap is clamped to 2048x4096, so this
                        // allocates up to ~33 MB and then PNG-compresses it — far too much to run in a
                        // click handler, where it janked and risked an ANR on a long scrollback.
                        // The picker is launched from inside the coroutine, after the bytes exist, so
                        // the result callback can never observe pendingScreenExport still null.
                        pickerActive = true
                        scope.launch {
                            pendingScreenExport = withContext(Dispatchers.Default) {
                                TerminalExportRenderer.renderScreenPng("Eclipse SSH · $hostId", text)
                            }
                            screenExportPicker.launch("eclipse-$hostId.png")
                        }
                    },
                    onClearCompleted = viewModel::clearCompletedTransfers,
                    onUpload = { pickerActive = true; uploadPicker.launch(arrayOf("*/*")) },
                    onDownloadFile = { remote ->
                        // The explorer's session, not merely the selected host - see [transferHost].
                        val host = transferHost()
                        if (host != null) {
                            if (state.localDirUri != null) viewModel.downloadToLocal(host, remote)
                            else { pendingDownload = remote; pickerActive = true; downloadPicker.launch(remote.name) }
                        }
                    },
                    onPickLocalFolder = { pickerActive = true; localFolderPicker.launch(null) },
                    onUploadLocal = { file -> transferHost()?.let { viewModel.uploadLocal(it, file) } },
                    onScheduleDownload = { files, scheduledAt, repeatMinutes -> transferHost()?.let { host -> files.forEach { viewModel.scheduleDownload(host, it, scheduledAt, repeatMinutes) } } },
                    onScheduleUpload = { files, scheduledAt, repeatMinutes -> transferHost()?.let { host -> files.forEach { viewModel.scheduleUpload(host, it, scheduledAt, repeatMinutes) } } },
                    onSync = { direction -> transferHost()?.let { host -> if (direction == SyncDirection.LOCAL_TO_REMOTE) viewModel.syncToRemote(host) else viewModel.syncFromRemote(host) } },
                    onSendToHost = { file, destHost, destPath -> transferHost()?.let { viewModel.sendRemoteTo(it, file, destHost, destPath) } },
                    onPauseTransfer = viewModel::pauseTransfer,
                    onResumeTransfer = viewModel::resumeTransfer,
                    onCancelTransfer = viewModel::cancelTransfer,
                    onPauseAllTransfers = viewModel::pauseAllTransfers,
                    onResumeAllTransfers = viewModel::resumeAllTransfers,
                    onCancelAllTransfers = viewModel::cancelAllTransfers,
                    onRunTransferNow = viewModel::runTransferNow,
                    onOpenTransferActions = { transferActionsFor = it },
                    onAddForward = { type, localPort, remoteHost, remotePort ->
                        activeHost?.let { host ->
                            when (type) {
                                ForwardType.LOCAL -> viewModel.startLocalForward(host, localPort, remoteHost ?: "127.0.0.1", remotePort ?: localPort)
                                ForwardType.REMOTE -> viewModel.startRemoteForward(host, remotePort ?: localPort, localPort)
                                ForwardType.DYNAMIC -> viewModel.startDynamicForward(host, localPort)
                            }
                        }
                    },
                    onStopForward = viewModel::stopForwarding,
                    onExportVault = { showExportDialog = true },
                    onImportVault = { pickerActive = true; importPicker.launch(arrayOf("*/*")) },
                    onImportAccount = { pickerActive = true; accountImportPicker.launch(arrayOf("*/*")) },
                    onGenerateKey = { showKeyGenDialog = true },
                    onImportSshConfig = { pickerActive = true; configImportPicker.launch(arrayOf("text/plain", "*/*")) },
                    onBiometric = ::setBiometric,
                    onDarkTheme = viewModel::setDarkTheme,
                    onKeepAlive = viewModel::setKeepAliveSeconds,
                    onReconnectBase = viewModel::setReconnectBaseSeconds,
                    onClipboard = viewModel::setClipboardSeconds,
                    onTerminalFontSize = viewModel::setTerminalFontSize,
                    onTerminalKeyRow = viewModel::setTerminalKeyRowVisible,
                    onTerminalMinColumns = viewModel::setTerminalMinColumns,
                    onLegacyAlgorithms = viewModel::setLegacyAlgorithms,
                    onBlockScreenshots = viewModel::setBlockScreenshots,
                    onReconnectAskFirst = viewModel::setReconnectAskFirst,
                    onTerminalTheme = viewModel::setTerminalTheme,
                    onSetPin = viewModel::setPin,
                    onClearPin = viewModel::clearPin,
                    verifyPin = viewModel::verifyPin,
                    onForgetKnownHost = viewModel::forgetKnownHost,
                    onClearKnownHosts = viewModel::clearKnownHosts,
                    onForgetAllCredentials = viewModel::forgetAllCredentials,
                    onCopyDiagnostics = { viewModel.copyToClipboard(viewModel.exportDiagnostics()) },
                    onSaveDiagnostics = {
                        pendingTextExport = viewModel.exportDiagnostics().toByteArray()
                        pickerActive = true
                        textExportPicker.launch("eclipse-diagnostics.log")
                    },
                    onClearDiagnostics = viewModel::clearDiagnostics,
                    // The shell is the one screen that must not be inset by this Scaffold. Its own
                    // padding comes from `safeDrawingPadding` inside the terminal, and applying both
                    // would inset the grid twice - once for a navigation bar that is not there and
                    // again for the window - costing rows the pty was told it had.
                    modifier = if (terminalImmersive) Modifier else Modifier.padding(padding),
                )
            }
        }
        if (isWide) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
    }

    if (showAddHost) {
        AddHostDialog(
            pickedKey = formKey,
            onPickKey = { pickerActive = true; formKeyPicker.launch(KEY_FILE_MIME_TYPES) },
            onForgetPickedKey = { formKey = null },
            onDismiss = { formKey = null; showAddHost = false },
            onSave = { profile, credentials ->
                viewModel.saveHost(profile, credentials)
                formKey = null
                showAddHost = false
            },
            onPasteSecret = viewModel::pasteSecret,
        )
    }
    showHostDetails?.let { host ->
        HostDetailsSheet(
            host = host,
            stats = state.serverStats[host.id],
            credentials = state.savedCredentials[host.id] ?: StoredCredentials(),
            onDismiss = { showHostDetails = null },
            onForgetCredentials = { viewModel.forgetCredentials(host) },
            onRefreshStats = { viewModel.refreshStats(host) },
        )
    }
    forwardManagerHostId?.let { hostId ->
        // Resolved from the live hosts flow rather than a snapshot taken when the sheet opened: the
        // sheet's own saves rewrite the host's rules, and a host removed while its manager was open
        // closes the sheet rather than editing a profile that no longer exists.
        state.hosts.firstOrNull { it.id == hostId }?.let { host ->
            PortForwardManagerSheet(
                host = host,
                statuses = state.forwardStatuses,
                runningForwards = state.forwardings,
                onDismiss = { forwardManagerHostId = null },
                onStartRule = { viewModel.startForwardRule(hostId, it) },
                onStopRule = viewModel::stopForwardRule,
                onSaveRules = { viewModel.saveForwardRules(hostId, it) },
            )
        }
    }
    remoteDesktopHostId?.let { hostId ->
        // Resolved from the live hosts flow, like the forwarding manager above: the dialog's own
        // saves rewrite this profile, and a host removed while the dialog was open closes it
        // rather than offering to save a profile that no longer exists.
        state.hosts.firstOrNull { it.id == hostId }?.let { host ->
            RemoteDesktopConfigDialog(
                host = host,
                onSave = { viewModel.saveRemoteDesktopTarget(hostId, it) },
                onOpen = { target ->
                    remoteDesktopHostId = null
                    openRemoteDesktop(host, target)
                },
                onDismiss = { remoteDesktopHostId = null },
            )
        }
    }
    showEditHost?.let { host ->
        AddHostDialog(
            initialHost = host,
            storedCredentials = state.savedCredentials[host.id] ?: StoredCredentials(),
            pickedKey = formKey,
            onPickKey = { pickerActive = true; formKeyPicker.launch(KEY_FILE_MIME_TYPES) },
            onForgetPickedKey = { formKey = null },
            onDismiss = { formKey = null; showEditHost = null },
            onSave = { profile, credentials ->
                viewModel.saveHost(profile, credentials)
                formKey = null
                showEditHost = null
            },
            onPasteSecret = viewModel::pasteSecret,
        )
    }
    pendingDeleteHost?.let { host ->
        AlertDialog(
            onDismissRequest = { pendingDeleteHost = null },
            title = { Text("Remove ${host.name}?") },
            text = { Text("This removes the saved connection profile and its stored credentials. Active sessions are not affected until disconnected.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteHost(host); pendingDeleteHost = null }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteHost = null }) { Text("Cancel") } },
        )
    }
    duplicateAskHost?.let { host ->
        // Counted here rather than carried in from the kebab click: the count in the sentence has to
        // match the rules this dialog will actually copy, and decoding the same column twice is what
        // the engine itself does to keep its ids honest.
        val ruleCount = remember(host) { decodeForwardRules(host.savedForwards, host.id).size }
        AlertDialog(
            onDismissRequest = { duplicateAskHost = null },
            title = { Text("Duplicate ${host.name}?") },
            text = {
                Column {
                    Text("A copy with the same settings and saved credentials, under a name of its own.")
                    // A row-wide toggle, not just the checkbox: the label is the sentence the user is
                    // agreeing to, so tapping it anywhere is agreeing to it.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                            .toggleable(
                                value = duplicateForwards,
                                role = Role.Checkbox,
                                onValueChange = { duplicateForwards = it },
                            ),
                    ) {
                        Checkbox(checked = duplicateForwards, onCheckedChange = null)
                        Text(
                            if (ruleCount == 1) "Also duplicate its 1 port forwarding rule"
                            else "Also duplicate its $ruleCount port forwarding rules",
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.duplicateHost(host, duplicateForwards); duplicateAskHost = null }) {
                    Text("Duplicate")
                }
            },
            dismissButton = { TextButton(onClick = { duplicateAskHost = null }) { Text("Cancel") } },
        )
    }
    showAuthHost?.let { host ->
        AuthenticationDialog(
            host = host,
            onDismiss = { showAuthHost = null },
            onPickKey = { pickerActive = true; keyPicker.launch(KEY_FILE_MIME_TYPES) },
            selectedKeyName = selectedKeyName,
            onConnect = { password, passphrase ->
                showAuthHost = null
                connectAndStart(host, password, passphrase)
            },
            onPasteSecret = viewModel::pasteSecret,
        )
    }
    state.hostKeyChallenge?.let { challenge ->
        HostKeyDialog(
            challenge = challenge,
            onAccept = viewModel::acceptHostKey,
            onReject = viewModel::rejectHostKey,
        )
    }
    // Looked up rather than carried on the prompt so a host deleted while the dialog was open cannot
    // be resurrected by its own Retry button — see [AuthFailurePrompt].
    state.authFailure?.let { prompt ->
        val failedHost = state.hosts.firstOrNull { it.id == prompt.hostId }
        if (failedHost != null) {
            AuthFailureDialog(
                prompt = prompt,
                host = failedHost,
                onDismiss = viewModel::consumeAuthFailure,
                onPickKey = { pickerActive = true; keyPicker.launch(KEY_FILE_MIME_TYPES) },
                selectedKeyName = selectedKeyName,
                onEditHost = {
                    viewModel.consumeAuthFailure()
                    showEditHost = failedHost
                },
                onRetry = { password, passphrase, save ->
                    viewModel.consumeAuthFailure()
                    if (save) saveAnsweredCredentials(failedHost, password, passphrase)
                    connectAndStart(failedHost, password, passphrase)
                },
                onPasteSecret = viewModel::pasteSecret,
            )
        }
    }
    // Ask-first reconnect: the tab is already parked at Disconnected with the reason, and this is
    // the question. Looked up like the auth-failure prompt above so a host deleted while the dialog
    // was open simply takes its question with it.
    state.reconnectPrompt?.let { prompt ->
        ReconnectDialog(prompt = prompt, onReconnect = viewModel::answerReconnectPrompt)
    }
    if (showExportDialog) {
        PassphraseDialog(
            title = "Export encrypted backup",
            confirmLabel = "Export",
            onDismiss = { showExportDialog = false },
            onConfirm = { pass -> showExportDialog = false; pendingExportPassphrase = pass; pickerActive = true; exportPicker.launch("eclipse-backup.enc") },
            onPasteSecret = viewModel::pasteSecret,
        )
    }
    if (showImportDialog) {
        PassphraseDialog(
            title = "Import backup",
            confirmLabel = "Import",
            onDismiss = { showImportDialog = false; pendingImportUri = null },
            onConfirm = { pass -> showImportDialog = false; pendingImportUri?.let { viewModel.importVault(pass, it) }; pendingImportUri = null },
            onPasteSecret = viewModel::pasteSecret,
        )
    }
    if (showAccountExportDialog) {
        PassphraseDialog(
            title = "Export account profile",
            confirmLabel = "Choose file",
            onDismiss = { showAccountExportDialog = false; pendingAccountExportHost = null },
            onConfirm = { pass ->
                showAccountExportDialog = false
                pendingAccountExportPassphrase = pass
                pickerActive = true
                accountExportPicker.launch("${safeFileName(pendingAccountExportHost?.name ?: "account")}.eclipse-account")
            },
            onPasteSecret = viewModel::pasteSecret,
        )
    }
    if (showAccountImportDialog) {
        PassphraseDialog(
            title = "Import account profile",
            confirmLabel = "Import",
            onDismiss = { showAccountImportDialog = false; pendingAccountImportUri = null },
            onConfirm = { pass ->
                showAccountImportDialog = false
                pendingAccountImportUri?.let { viewModel.importAccount(pass, it) }
                pendingAccountImportUri = null
            },
            onPasteSecret = viewModel::pasteSecret,
        )
    }
    if (showKeyGenDialog) {
        KeyGenDialog(
            onDismiss = { showKeyGenDialog = false },
            onConfirm = { algorithm ->
                showKeyGenDialog = false
                // Generated off the main thread. RSA key generation searches for primes, so its cost
                // is unbounded rather than merely large: 4096-bit generation is seconds on a fast
                // phone and tens of seconds on a slow one, and this ran inside the dialog's click
                // handler. That is a main-thread block well past the 5 s ANR threshold — the tap
                // froze the whole UI and Android offered to kill the app, with the key that was
                // being generated lost when the user accepted.
                //
                // pickerActive is set before the coroutine starts, exactly as the screen export
                // does, so the PIN lock cannot re-arm during the wait and then swallow the file
                // picker that opens at the end of it.
                pickerActive = true
                scope.launch {
                    val generated = withContext(Dispatchers.Default) { runCatching { algorithm.generate() } }
                    generated
                        .onSuccess { pair ->
                            pendingKeyPair = pair
                            privateKeyPicker.launch(pair.defaultPrivateName)
                        }
                        .onFailure { error ->
                            // Reachable: the generator rejects a coordinate it cannot encode, and a
                            // provider can refuse an algorithm outright. Thrown from the click
                            // handler this crashed the activity; here it is a message.
                            pickerActive = false
                            viewModel.reportUiFailure("Could not generate the key pair", error)
                        }
                }
            },
        )
    }
    // The editor opens in its own window (see [TextEditorActivity]) so it sits fully on top of the
    // workspace instead of floating over it; the request itself travels through the process-wide
    // [EditorRequests] handoff because it carries a live provider an intent cannot parcel. Cleared
    // as soon as the launch is issued, so every place that sets `editorRequest` — the explorer, the
    // preview sheet, the New File dialog — keeps working unchanged.
    LaunchedEffect(editorRequest) {
        val request = editorRequest ?: return@LaunchedEffect
        editorRequest = null
        val token = EditorRequests.put(request)
        context.startActivity(
            Intent(context, TextEditorActivity::class.java).putExtra(TextEditorActivity.EXTRA_REQUEST_TOKEN, token),
        )
    }
    previewTarget?.let { target ->
        FilePreviewSheet(
            entry = target.entry,
            provider = target.provider,
            onDismiss = { previewTarget = null },
            onEdit = { entry ->
                previewTarget = null
                editorRequest = EditorRequest(entry, target.provider)
            },
        )
    }
    // The Transfers sheet closes before each action runs, exactly as the Files sheet does — the
    // preview and the editor that some rows open are their own windows, and none of them should
    // have to fight this sheet for the bottom of the screen.
    transferActionsFor?.let { item ->
        TransferActionsSheet(
            item = item,
            onDismiss = { transferActionsFor = null },
            onPause = { id -> transferActionsFor = null; viewModel.pauseTransfer(id) },
            onResume = { id -> transferActionsFor = null; viewModel.resumeTransfer(id) },
            onCancel = { id -> transferActionsFor = null; viewModel.cancelTransfer(id) },
            onRunNow = { id -> transferActionsFor = null; viewModel.runTransferNow(id) },
            onViewFile = { transfer ->
                transferActionsFor = null
                transferLocalTarget(context, transfer)?.let { previewTarget = it }
                    ?: viewModel.reportUiMessage("${transfer.name} has no local file to view")
            },
            onEditFile = { transfer ->
                transferActionsFor = null
                transferLocalTarget(context, transfer)?.let { editorRequest = EditorRequest(it.entry, it.provider) }
                    ?: viewModel.reportUiMessage("${transfer.name} has no local file to edit")
            },
            onOpenFile = { transfer ->
                transferActionsFor = null
                openTransferFileExternally(context, transfer, choose = false, onNoApp = viewModel::reportUiMessage)
            },
            onOpenFileWith = { transfer ->
                transferActionsFor = null
                openTransferFileExternally(context, transfer, choose = true, onNoApp = viewModel::reportUiMessage)
            },
            onCopyDetails = { transfer ->
                transferActionsFor = null
                viewModel.copyToClipboard(transferDetails(transfer))
            },
        )
    }
    }
    }
}

@Composable
private fun LockScreen(
    biometricEnabled: Boolean,
    verifyPin: suspend (String) -> Boolean,
    onUnlocked: () -> Unit,
    onBiometricUnlock: (onFailure: (String) -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(68.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)) }
            }
            Spacer(Modifier.height(18.dp))
            Text("Eclipse SSH is locked", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text("Enter your PIN to unlock", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit).take(6); error = null },
                label = { Text("PIN") },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth(),
            )
            error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = {
                    if (pin.isBlank()) return@Button
                    verifying = true
                    // Cleared so a retry with the same PIN does not sit under "Verifying…" still
                    // showing the previous attempt's message.
                    error = null
                    scope.launch {
                        if (verifyPin(pin)) onUnlocked() else { error = "Incorrect PIN"; verifying = false }
                    }
                },
                enabled = pin.isNotBlank() && !verifying,
                modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth(),
            ) { Text(if (verifying) "Verifying…" else "Unlock") }
            if (biometricEnabled) {
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { onBiometricUnlock { reason -> error = reason } }) { Icon(Icons.Default.Security, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Use biometric") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceScaffold(
    destination: Destination,
    state: MainUiState,
    /**
     * The terminal viewports, as a flow rather than a value.
     *
     * Passed uncollected on purpose. A frame arrives up to thirty times a second, and reading it here
     * would recompose this scaffold - its top bar, its snackbar host, its whole content lambda - at
     * that rate on every destination. Collected inside [TerminalScreen] instead, which is both the only
     * thing that draws a frame and the only place the collection can stop when the terminal is not on
     * screen: no collector means [dev.eclipse.ssh.presentation.MainViewModel] skips building frames
     * altogether while the user is on Hosts, Files, Transfers or Settings, or while the app is in the
     * background. The pty keeps being drained the whole time; only the copies stop.
     */
    frames: StateFlow<Map<String, TerminalFrame>>,
    /**
     * The Files tab's own state and actions. Passed whole rather than as a bag of collected values,
     * for the same reason [frames] is: the explorer's state changes on every listing, and reading it
     * here would recompose this scaffold for the sake of a destination that is not on screen.
     */
    filesExplorer: FilesExplorerController,
    /** Opens a file in the full-window preview - see the overlay state in [EclipseWorkspace]. */
    onPreviewFile: (FsEntry, FileSystemProvider) -> Unit = { _, _ -> },
    /** Opens a file in the full-window editor - see the overlay state in [EclipseWorkspace]. */
    onEditFile: (FsEntry, FileSystemProvider) -> Unit = { _, _ -> },
    onDestination: (Destination) -> Unit,
    onSearch: (String) -> Unit,
    onAddHost: () -> Unit,
    onConnect: (HostProfile) -> Unit,
    onShowDetails: (HostProfile) -> Unit,
    onEditHost: (HostProfile) -> Unit,
    onRemoveHost: (HostProfile) -> Unit,
    /** Toggles the star on one host - the card menu's Favorite item. */
    onToggleFavoriteHost: (HostProfile) -> Unit = {},
    /** Opens the per-host account export dialog - the card menu's Export account item. */
    onExportAccount: (HostProfile) -> Unit = {},
    /** Saves a copy of one host under a new id - the card menu's Duplicate item. */
    onDuplicateHost: (HostProfile) -> Unit = {},
    /**
     * Opens one host's port-forwarding manager sheet - the card menu's Port forwarding item, and the
     * session row's forward note. The manager lives above this scaffold with the other sheets, so it
     * survives the destination changing underneath it and keeps its state through a rule edit.
     */
    onManageForwards: (HostProfile) -> Unit = {},
    /**
     * Opens one host's remote desktop - the card menu's Remote desktop item. A host with a saved,
     * enabled VNC target goes straight to the viewer window; the rest get the endpoint dialog
     * first. Named `onRemoteDesktop` rather than `onOpenRemoteDesktop` because both of those are
     * "open": which one depends on the host's saved target, and the caller does not care.
     */
    onRemoteDesktop: (HostProfile) -> Unit = {},
    /**
     * Sends one host's Wake-on-LAN packet - the card menu's Wake on LAN item. An injectable no-op
     * default like the other per-host actions, so previews and the destinations that never show a
     * host card do not have to name it.
     */
    onWakeOnLan: (HostProfile) -> Unit = {},
    onCloseTab: (SessionTab) -> Unit,
    /** Long-press on a terminal tab: opens a second shell on the same host. */
    onDuplicateSession: (SessionTab) -> Unit = {},
    onDisconnectAll: () -> Unit,
    /** The session whose shell is on screen; null shows the list of sessions instead. */
    openSessionId: String?,
    /** Opens a session's shell full-screen, or returns to the list with null. */
    onOpenSession: (String?) -> Unit,
    /** True when that shell owns the window, so this scaffold draws no chrome around it. */
    immersive: Boolean,
    modifier: Modifier = Modifier,
    onSendInput: (String, String) -> Unit = { _, _ -> },
    onSendText: (String, String) -> Unit = { _, _ -> },
    onSendKey: (String, TerminalKey, Boolean, Boolean, Boolean) -> Unit = { _, _, _, _, _ -> },
    onSendChar: (String, Char, Boolean, Boolean) -> Unit = { _, _, _, _ -> },
    onResizeTerminal: (String, Int, Int) -> Unit = { _, _, _ -> },
    onScrollTerminal: (String, Int) -> Unit = { _, _ -> },
    onScrollTerminalTo: (String, Int) -> Unit = { _, _ -> },
    onCopySelection: (String, TerminalSelection) -> Unit = { _, _ -> },
    onCopyTerminalText: (String) -> Unit = {},
    /** Puts one session's own diagnostic trace on the clipboard - see [SessionWhySheet]. */
    onCopyTrace: (String) -> Unit = {},
    onPasteTerminal: (String) -> Unit = {},
    onSaveSnippet: (String, String) -> Unit = { _, _ -> },
    onDeleteSnippet: (String) -> Unit = {},
    onSaveLogs: (String, String) -> Unit = { _, _ -> },
    onSaveText: (String, String) -> Unit = { _, _ -> },
    onSaveScreen: (String, String) -> Unit = { _, _ -> },
    onClearCompleted: () -> Unit = {},
    onUpload: () -> Unit = {},
    onDownloadFile: (RemoteFile) -> Unit = {},
    onPickLocalFolder: () -> Unit = {},
    onUploadLocal: (LocalFile) -> Unit = {},
    onScheduleDownload: (List<RemoteFile>, Long, Long?) -> Unit = { _, _, _ -> },
    onScheduleUpload: (List<LocalFile>, Long, Long?) -> Unit = { _, _, _ -> },
    onSync: (SyncDirection) -> Unit = {},
    onSendToHost: (RemoteFile, HostProfile, String) -> Unit = { _, _, _ -> },
    onPauseTransfer: (String) -> Unit = {},
    onCancelTransfer: (String) -> Unit = {},
    onResumeTransfer: (String) -> Unit = {},
    onPauseAllTransfers: () -> Unit = {},
    onResumeAllTransfers: () -> Unit = {},
    onCancelAllTransfers: () -> Unit = {},
    onRunTransferNow: (String) -> Unit = {},
    /** Long-press on a transfer card: opens the per-item action sheet held above this scaffold. */
    onOpenTransferActions: (TransferItem) -> Unit = {},
    onAddForward: (ForwardType, Int, String?, Int?) -> Unit = { _, _, _, _ -> },
    onStopForward: (String) -> Unit = {},
    onExportVault: () -> Unit = {},
    onImportVault: () -> Unit = {},
    onImportAccount: () -> Unit = {},
    onGenerateKey: () -> Unit = {},
    onImportSshConfig: () -> Unit = {},
    onBiometric: (Boolean) -> Unit,
    onDarkTheme: (Boolean) -> Unit,
    onKeepAlive: (Int) -> Unit = {},
    onReconnectBase: (Int) -> Unit = {},
    onClipboard: (Int) -> Unit = {},
    onTerminalFontSize: (Int) -> Unit = {},
    onTerminalKeyRow: (Boolean) -> Unit = {},
    onTerminalMinColumns: (Int) -> Unit = {},
    onLegacyAlgorithms: (Boolean) -> Unit = {},
    onBlockScreenshots: (Boolean) -> Unit = {},
    onReconnectAskFirst: (Boolean) -> Unit = {},
    onTerminalTheme: (String) -> Unit = {},
    onSetPin: (String) -> Unit = {},
    onClearPin: () -> Unit = {},
    verifyPin: suspend (String) -> Boolean = { false },
    onForgetKnownHost: (String) -> Unit = {},
    onClearKnownHosts: () -> Unit = {},
    onForgetAllCredentials: () -> Unit = {},
    onCopyDiagnostics: () -> Unit = {},
    onSaveDiagnostics: () -> Unit = {},
    onClearDiagnostics: () -> Unit = {},
) {
    // A shell owns the whole window, so it composes outside the Scaffold entirely: no top bar, no
    // Scaffold insets, nothing above the grid but the session strip. This is the branch the app enters
    // the moment a login succeeds, and what makes the terminal full screen rather than merely large.
    val openSession = state.tabs.firstOrNull { it.id == openSessionId }
    if (immersive && openSession != null) {
        TerminalScreen(
            state = state,
            frames = frames,
            onCloseTab = onCloseTab,
            onDisconnectAll = onDisconnectAll,
            // The same authentication sheet any other connection opens: a reconnect is a
            // connection, and it should ask for whatever a connection asks for.
            onReconnect = { hostId -> state.hosts.firstOrNull { it.id == hostId }?.let(onConnect) },
            onSendInput = onSendInput,
            onSendText = onSendText,
            onSendKey = onSendKey,
            onSendChar = onSendChar,
            onResize = onResizeTerminal,
            onScroll = onScrollTerminal,
            onScrollTo = onScrollTerminalTo,
            onCopySelection = onCopySelection,
            onCopyText = onCopyTerminalText,
            onCopyTrace = onCopyTrace,
            onPaste = onPasteTerminal,
            onSaveSnippet = onSaveSnippet,
            onDeleteSnippet = onDeleteSnippet,
            onSaveLogs = onSaveLogs,
            onSaveText = onSaveText,
            onSaveScreen = onSaveScreen,
            fontSize = state.settings.terminalFontSize,
            onFontSize = onTerminalFontSize,
            onKeyRowVisible = onTerminalKeyRow,
            activeTab = openSession,
            onSelectSession = { onOpenSession(it.id) },
            onDuplicateSession = onDuplicateSession,
            onLeaveSession = { onOpenSession(null) },
            modifier = modifier,
        )
        return
    }
    Scaffold(
        modifier = modifier,
        // No top bar at all except on Hosts — and no title even there. Every destination already
        // names itself in the navigation bar at the bottom, so the bar above repeated that name (and
        // the global-search icon beside it) in vertical space the document on screen could use; the
        // screens felt noticeably tighter for it. Hosts is the one destination with actions that
        // belong to no row on it (Add, Import account), and those stay, actions-only.
        topBar = {
            if (destination == Destination.HOSTS) {
                TopAppBar(
                    title = {},
                    actions = {
                        IconButton(onClick = onImportAccount) { Icon(Icons.Default.CloudDownload, "Import account") }
                        IconButton(onClick = onAddHost) { Icon(Icons.Default.Add, "Add host") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        // The terminal is measured, not scrolled, and it takes the whole window.
        //
        // Every other destination is a document: a column of cards that is taller than the screen and
        // scrolls. A terminal is the opposite - it is a grid that has to know how many rows and columns
        // fit, because that number is sent to the remote pty. Inside the scrolling Column below it was
        // measured with an infinite maximum height, which is also why putting a scrollable terminal
        // there threw out of `checkScrollableContainerConstraints`. Branching before that Column is what
        // lets the terminal fill the window and report an honest size; the navigation bar and rail are
        // outside this scaffold, so Hosts, Files, Transfers and Settings stay exactly where they were.
        // The Terminal destination without a shell open: the sessions themselves, as a list.
        //
        // Not inside the scrolling Column below, for the same reason the shell is not - a list that
        // manages its own scrolling cannot be measured with an infinite maximum height. And
        // deliberately not a terminal: nothing here collects a frame, so the view model keeps building
        // none while the user is choosing a session, and a screenful of live shells costs nothing.
        if (destination == Destination.TERMINAL) {
            Box(Modifier.padding(padding).fillMaxSize()) {
                TerminalSessionsScreen(
                    state = state,
                    onOpenSession = { onOpenSession(it.id) },
                    onCloseTab = onCloseTab,
                    onDisconnectAll = onDisconnectAll,
                    // The same authentication sheet the Hosts list opens, deliberately: a reconnect is
                    // a connection, and it should ask for whatever a connection asks for.
                    onReconnect = { hostId -> state.hosts.firstOrNull { it.id == hostId }?.let(onConnect) },
                    // The session row knows its host's id; the manager above this scaffold wants the
                    // profile, which only the hosts flow can answer for.
                    onManageForwards = { hostId -> state.hosts.firstOrNull { it.id == hostId }?.let(onManageForwards) },
                    onCopyTrace = onCopyTrace,
                    onGoToHosts = { onDestination(Destination.HOSTS) },
                )
            }
            return@Scaffold
        }
        // Files is measured rather than scrolled, for the same reason the terminal is.
        //
        // Two directory listings that scroll themselves cannot live inside a scrolling Column: there they
        // are measured with an infinite maximum height, so `weight(1f)` and `fillMaxHeight()` collapse to
        // "as tall as your contents" and a `LazyColumn` throws out of `checkScrollableContainerConstraints`
        // outright. That is the whole reason the server's files came out squeezed - the pane could not be
        // told to fill the window, because nothing it sat in had a height to fill, so both listings drew
        // themselves at full length and the *page* scrolled past the server's to reach the phone's.
        // Branching here gives Files a bounded window, which is what lets one tab own the whole of it.
        if (destination == Destination.FILES) {
            // The horizontal padding is deliberately thin: a phone screen is the scarce resource here,
            // and 8dp is enough to keep a card's ripple from touching the screen edge. (The 1280dp
            // widthIn above still caps a tablet or desktop window at a readable column.)
            Column(Modifier.padding(padding).fillMaxSize().widthIn(max = 1280.dp).padding(horizontal = 8.dp)) {
                FilesScreen(
                    state,
                    filesExplorer,
                    onPreviewFile,
                    onEditFile,
                    onUpload,
                    onDownloadFile,
                    onPickLocalFolder,
                    onUploadLocal,
                    onScheduleDownload,
                    onScheduleUpload,
                    onSync,
                    onSendToHost,
                )
            }
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().widthIn(max = 1280.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            when (destination) {
                Destination.HOSTS -> HostsScreen(
                    state, onSearch, onAddHost, onConnect, onShowDetails, onEditHost, onRemoveHost,
                    onToggleFavoriteHost, onExportAccount, onDuplicateHost, onManageForwards,
                    onRemoteDesktop, onWakeOnLan,
                )
                // Both handled above, outside the scrolling column, because both are measured.
                Destination.TERMINAL, Destination.FILES -> Unit
                Destination.TRANSFERS -> TransfersScreen(
                    state.transfers, onClearCompleted, onPauseTransfer, onResumeTransfer, onCancelTransfer,
                    onPauseAllTransfers, onResumeAllTransfers, onCancelAllTransfers, onRunTransferNow,
                    onOpenTransferActions,
                )
                Destination.SETTINGS -> SettingsScreen(
                    state, onBiometric, onDarkTheme, onAddForward, onStopForward, onExportVault,
                    onImportVault, onKeepAlive, onClipboard, onTerminalFontSize,
                    onTerminalMinColumns = onTerminalMinColumns,
                    onReconnectBase = onReconnectBase,
                    onLegacyAlgorithms = onLegacyAlgorithms,
                    onBlockScreenshots = onBlockScreenshots,
                    onReconnectAskFirst = onReconnectAskFirst,
                    onTerminalTheme = onTerminalTheme,
                    onSetPin = onSetPin,
                    onClearPin = onClearPin,
                    verifyPin = verifyPin,
                    onForgetKnownHost = onForgetKnownHost,
                    onClearKnownHosts = onClearKnownHosts,
                    onGenerateKey = onGenerateKey,
                    onImportSshConfig = onImportSshConfig,
                    onForgetAllCredentials = onForgetAllCredentials,
                    onCopyDiagnostics = onCopyDiagnostics,
                    onSaveDiagnostics = onSaveDiagnostics,
                    onClearDiagnostics = onClearDiagnostics,
                )
            }
        }
    }
}

@Composable
private fun HostsScreen(
    state: MainUiState,
    onSearch: (String) -> Unit,
    onAddHost: () -> Unit,
    onConnect: (HostProfile) -> Unit,
    onShowDetails: (HostProfile) -> Unit,
    onEditHost: (HostProfile) -> Unit,
    onRemoveHost: (HostProfile) -> Unit,
    onToggleFavoriteHost: (HostProfile) -> Unit,
    onExportAccount: (HostProfile) -> Unit,
    onDuplicateHost: (HostProfile) -> Unit,
    onManageForwards: (HostProfile) -> Unit,
    onRemoteDesktop: (HostProfile) -> Unit,
    onWakeOnLan: (HostProfile) -> Unit,
) {
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = state.query,
        onValueChange = onSearch,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search hosts, tags, or usernames") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
    )
    Spacer(Modifier.height(18.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("All hosts", style = MaterialTheme.typography.titleMedium)
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
            Text("${state.hosts.size}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
        }
        Spacer(Modifier.weight(1f))
        FilterChip(selected = !favoritesOnly, onClick = { favoritesOnly = false }, label = { Text("All") })
        FilterChip(selected = favoritesOnly, onClick = { favoritesOnly = true }, label = { Text("Favorites") })
    }
    Spacer(Modifier.height(14.dp))
    val visibleHosts = state.filteredHosts.filter { !favoritesOnly || it.isFavorite }
    if (visibleHosts.isEmpty()) {
        EmptyState("No hosts found", "Try another search or add your first connection.", onAddHost)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            visibleHosts.forEach { host ->
                HostCard(host, onConnect, onShowDetails, onEditHost, onRemoveHost, onToggleFavoriteHost, onExportAccount, onDuplicateHost, onManageForwards, onRemoteDesktop, onWakeOnLan)
            }
        }
    }
}

/**
 * One saved host: who it is, how it authenticates, and a single overflow menu holding everything it
 * can do.
 *
 * The card itself is the Connect control - one tap on the row opens the login, the same thing the
 * menu's Connect item does. There is deliberately no primary button on the card for that: a
 * full-width Connect under every host was the largest control on the screen repeated once per row —
 * it added some 60dp to each card, so a phone showed three hosts where it now shows six, and it
 * spent all that emphasis on one of three equally ordinary actions while Edit and Remove sat two
 * taps deep inside the details sheet. The row carrying the connect keeps every card the same shape
 * whatever the host, which is the property a list needs and a per-row button cannot have.
 *
 * One trailing control carries the host's name in its content description. With one card per host,
 * "More actions" alone is ambiguous to a screen reader and to a test: it names the control but not the
 * row it belongs to, and there are as many of them as there are hosts. The arrow that used to sit
 * beside it went away when everything it opened moved into this menu, so the menu is now the one way
 * into everything except connect - which is also why it no longer needs a second control competing
 * for the row's trailing edge.
 */
@Composable
private fun HostCard(
    host: HostProfile,
    onConnect: (HostProfile) -> Unit,
    onDetails: (HostProfile) -> Unit,
    onEdit: (HostProfile) -> Unit,
    onRemove: (HostProfile) -> Unit,
    onToggleFavorite: (HostProfile) -> Unit,
    onExportAccount: (HostProfile) -> Unit,
    onDuplicate: (HostProfile) -> Unit,
    onPortForwarding: (HostProfile) -> Unit,
    onRemoteDesktop: (HostProfile) -> Unit,
    onWakeOnLan: (HostProfile) -> Unit,
) {
    // Keyed on the host id so a list that reorders (a favourite toggled, a search narrowed) cannot
    // leave the menu open over a different host than the one it was opened on.
    var menuOpen by remember(host.id) { mutableStateOf(false) }
    Card(
        // The row is the connect: one tap opens the login, exactly what the menu's Connect item does.
        // selectHost is not needed here because connect() selects the host itself, so the transfer
        // target and dialog defaults follow the tap either way.
        modifier = Modifier.fillMaxWidth().clickable { onConnect(host) },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Computer, null, tint = host.accentColor?.let { Color(it) } ?: MaterialTheme.colorScheme.primary) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // weight(1f, fill = false) so the star stays beside a short name instead of
                        // being pushed to the far edge, and a long one still ellipsizes rather than
                        // pushing the star off the card.
                        Text(host.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (host.isFavorite) { Spacer(Modifier.width(6.dp)); Icon(Icons.Default.Star, "Favorite", tint = EclipseWarning, modifier = Modifier.size(17.dp)) }
                    }
                    Text("${host.username}@${host.host}:${host.port}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More actions for ${host.name}") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Connect") },
                            leadingIcon = { Icon(Icons.Default.Wifi, null) },
                            onClick = { menuOpen = false; onConnect(host) },
                        )
                        // Directly under Connect because it is the other thing a connected host is
                        // opened for: the manager works whether or not the session is up, but it is
                        // the running tunnels a user comes here hunting.
                        DropdownMenuItem(
                            text = { Text("Port forwarding") },
                            leadingIcon = { Icon(Icons.Default.SwapVert, null) },
                            onClick = { menuOpen = false; onPortForwarding(host) },
                        )
                        // Beside the tunnels, because it is built from the same parts - a desktop
                        // rides an ad-hoc local forward - and it is the third thing a connected
                        // host is opened for. A host with no saved endpoint gets the config
                        // dialog rather than nothing: the menu item is the entry point, not the
                        // reminder that a settings screen exists.
                        DropdownMenuItem(
                            text = { Text("Remote desktop") },
                            leadingIcon = { Icon(Icons.Default.DesktopWindows, null) },
                            onClick = { menuOpen = false; onRemoteDesktop(host) },
                        )
                        // Beside the things that use a *running* session, because it is the one action
                        // that cannot: the whole point of waking the machine is that nothing is
                        // listening yet. Not gated on a saved address - the item is the discoverable
                        // half of the feature, and "no address saved" is an answer the snackbar can
                        // give, pointing at Edit, rather than an item that quietly is not there.
                        DropdownMenuItem(
                            text = { Text("Wake on LAN") },
                            leadingIcon = { Icon(Icons.Default.Bolt, null) },
                            onClick = { menuOpen = false; onWakeOnLan(host) },
                        )
                        // The arrow this item replaced used to sit beside the kebab as a second way
                        // into the details sheet; now this is the way in, so it sits high in the
                        // menu, where the eye lands first.
                        DropdownMenuItem(
                            text = { Text("Details") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.ArrowForward, null) },
                            onClick = { menuOpen = false; onDetails(host) },
                        )
                        DropdownMenuItem(
                            text = { Text(if (host.isFavorite) "Unfavorite" else "Favorite") },
                            leadingIcon = { Icon(if (host.isFavorite) Icons.Default.Star else Icons.Default.StarBorder, null) },
                            onClick = { menuOpen = false; onToggleFavorite(host) },
                        )
                        DropdownMenuItem(
                            text = { Text("Export account") },
                            leadingIcon = { Icon(Icons.Default.Upload, null) },
                            onClick = { menuOpen = false; onExportAccount(host) },
                        )
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menuOpen = false; onEdit(host) },
                        )
                        DropdownMenuItem(
                            text = { Text("Duplicate") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = { menuOpen = false; onDuplicate(host) },
                        )
                        // Confirmed by the caller, never here: the menu closes on the tap, so a
                        // confirmation owned by this composable would be dismissed with it.
                        DropdownMenuItem(
                            text = { Text("Remove", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onRemove(host) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                host.tags.take(3).forEach { tag ->
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) { Text(tag, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)) }
                }
                Spacer(Modifier.weight(1f))
                // The per-host SFTP switch, shown where the auth method is: both answer "what will
                // happen when I connect to this?", and it is the only place the saved setting is
                // visible without opening the form.
                if (host.autoLoginSftp) {
                    Icon(Icons.Default.FolderOpen, "SFTP auto login on", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(2.dp))
                }
                Icon(Icons.Default.Key, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Text(host.authMethod.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun TerminalScreen(
    state: MainUiState,
    frames: StateFlow<Map<String, TerminalFrame>>,
    onCloseTab: (SessionTab) -> Unit,
    onDisconnectAll: () -> Unit,
    onReconnect: (String) -> Unit,
    onSendInput: (String, String) -> Unit,
    onSendText: (String, String) -> Unit,
    onSendKey: (String, TerminalKey, Boolean, Boolean, Boolean) -> Unit,
    onSendChar: (String, Char, Boolean, Boolean) -> Unit,
    onResize: (String, Int, Int) -> Unit,
    onScroll: (String, Int) -> Unit,
    onScrollTo: (String, Int) -> Unit,
    onCopySelection: (String, TerminalSelection) -> Unit,
    onCopyText: (String) -> Unit,
    onCopyTrace: (String) -> Unit,
    onPaste: (String) -> Unit,
    onSaveSnippet: (String, String) -> Unit,
    onDeleteSnippet: (String) -> Unit,
    onSaveLogs: (String, String) -> Unit,
    onSaveText: (String, String) -> Unit,
    onSaveScreen: (String, String) -> Unit,
    /**
     * The session being shown, resolved by the caller.
     *
     * Passed in rather than picked here because which shell is on screen decides whether the app has
     * any chrome at all, and that decision belongs where the chrome is - see
     * [EclipseWorkspace]'s `openSessionId`. It also means this screen has no empty state: it is
     * only ever composed for a session that exists, and the list is what handles having none.
     */
    activeTab: SessionTab,
    onSelectSession: (SessionTab) -> Unit,
    /** Long-press on the strip's tab: opens a second shell on the same host. */
    onDuplicateSession: (SessionTab) -> Unit,
    onLeaveSession: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: Int = 13,
    /** Persists a pinch-to-zoom result, so the size the user settled on survives leaving the screen. */
    onFontSize: (Int) -> Unit = {},
    /** Persists the shortcut bar's collapsed state, for the same reason. */
    onKeyRowVisible: (Boolean) -> Unit = {},
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showCommandBar by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    /**
     * The half-typed command line.
     *
     * `remember`, deliberately not `rememberSaveable`: a command is where a secret is most likely to
     * be typed by hand - a `mysql -p`, an `export TOKEN=`, a `curl -H "Authorization: ..."` - and
     * `rememberSaveable` writes its value into the saved-instance-state `Bundle`, which the platform
     * persists to disk for a process it intends to kill and restore. That put a password into unsafe
     * storage as a side effect of typing it.
     *
     * Nothing is lost by making it plain `remember`. The activity declares `configChanges` for
     * orientation and friends (see the manifest) so a rotation never recreates it and never recreates
     * this composition, which is what would otherwise clear the field - the same reason the lock
     * gate's `unlocked` flag can be a plain `remember`. Leaving the session drops the draft either
     * way, because the screen leaves composition with it.
     */
    var command by remember { mutableStateOf("") }
    var showSnippets by remember { mutableStateOf(false) }
    var showSaveSnippet by remember { mutableStateOf(false) }
    var snippetLabel by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf<TerminalSelection?>(null) }
    // Dropped when the session changes: the coordinates are buffer lines, so keeping them would
    // highlight an unrelated stretch of the other session's scrollback.
    LaunchedEffect(activeTab.id) { selection = null }
    val theme = TerminalTheme.named(state.settings.terminalTheme)
    val termBg = Color(theme.background)
    val termFg = Color(theme.foreground)
    val history = state.commandHistory[activeTab.hostId].orEmpty()
    var historyIndex by remember { mutableIntStateOf(-1) }
    val recall: (Int) -> Unit = { delta ->
        if (history.isNotEmpty()) {
            historyIndex = (historyIndex + delta).coerceIn(-1, history.lastIndex)
            command = if (historyIndex < 0) "" else history[historyIndex]
        }
    }
    // This tab's own scrollback - keyed by the tab id, which is the session key: a host with two
    // shells keeps two independent transcripts.
    val terminalText = state.terminalOutput[activeTab.id].orEmpty()
    // The geometry this host has chosen, or zeroes for "match the screen". Looked up here rather than
    // carried on the tab because it is a stored setting: editing it and coming back has to take effect.
    val hostGeometry = remember(state.hosts, activeTab.hostId) {
        state.hosts.firstOrNull { it.id == activeTab.hostId }
            ?.let { it.terminalColumns to it.terminalRows } ?: (0 to 0)
    }
    // Collected here, at the one composable that draws it, and with the lifecycle: the collection ends
    // when this screen leaves composition or the app stops, which is the signal the view model uses to
    // stop producing frames nobody can see.
    val currentFrames by frames.collectAsStateWithLifecycle()
    val frame = currentFrames[activeTab.id] ?: TerminalFrame.EMPTY
    val latches = rememberTerminalLatches()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    /**
     * The size a live pinch is showing, or null when no pinch has happened since the setting caught up.
     *
     * A pinch has to change the text under the fingers *while they move*, and the persisted setting
     * cannot do that: it is written through DataStore, so it arrives a frame or more later and only
     * once. So the gesture drives this overlay for immediate feedback and writes the setting once, when
     * the fingers lift; the overlay then stays in charge until [fontSize] reports the same number,
     * which is what stops the text from snapping back to the old size for the frames in between.
     */
    var pinchSize by remember { mutableStateOf<Int?>(null) }
    /** The size the current pinch started from; zero when no pinch is in progress. */
    var pinchBase by remember { mutableIntStateOf(0) }
    LaunchedEffect(fontSize) { if (pinchSize == fontSize) pinchSize = null }
    val liveFontSize = pinchSize ?: fontSize
    val textStyle = remember(liveFontSize) { TextStyle(fontFamily = TerminalMonoFontFamily, fontSize = liveFontSize.sp) }
    val metrics = rememberTerminalCellMetrics(textStyle)
    // Measured against the screen rather than against this box, deliberately. The box loses height to
    // the software keyboard, and a margin derived from it would shrink every time the keyboard opened -
    // the text would step towards the top edge as the user typed. The screen does not change under an
    // IME, so the gap stays where the user last saw it. See [terminalTextInset].
    val configuration = LocalConfiguration.current
    val textInset = remember(configuration.screenWidthDp, configuration.screenHeightDp) {
        terminalTextInset(configuration.screenWidthDp, configuration.screenHeightDp)
    }
    /**
     * Whether the invisible IME host actually holds focus, reported by the field itself.
     *
     * Observed rather than assumed because a focus *request* can fail - the node has to be attached
     * and placed before it can take focus, and on the first composition of this screen it is neither -
     * and a failed request is indistinguishable, from the outside, from a terminal that swallows every
     * keystroke.
     */
    var inputFocused by remember { mutableStateOf(false) }
    val showKeyboard = {
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }
    /**
     * Tabs whose keyboard has already been offered, so it is offered once and not fought over.
     *
     * A plain [remember] set, not state: it is read inside an effect and never drives a recomposition.
     * Keyed on the tab rather than the host so a reconnect on the same tab does not reopen a keyboard
     * the user had put away, while a new session gets one.
     */
    val keyboardOffered = remember { mutableSetOf<String>() }
    /**
     * Connects the keyboard to the shell as soon as the shell exists.
     *
     * This is the difference between a terminal and a picture of one. Focus was only ever requested
     * from a tap on the grid, so after logging in the app sat with an unfocused IME host: the software
     * keyboard stayed shut, and - because [onPreviewKeyEvent] only sees events routed to the focused
     * node - a hardware keyboard's letters, Enter, Backspace and arrows all went nowhere. The user had
     * to know to tap the screen first, and tapping is also what dismisses a selection, so on a session
     * with anything selected it took two.
     *
     * The rules it follows, each of which is a way this can be got wrong:
     *
     *  - **Only when there is a shell to type into.** Gated on [SessionConnectionState.CONNECTED], so
     *    a keyboard does not open over a failed connection or in front of a handshake.
     *  - **Retried until it takes.** A [FocusRequester] cannot focus a node that has not been placed,
     *    and on the frame this screen first composes it has not been; requesting once and hoping was
     *    the version that worked on a fast device and failed on a slow one. It asks again on each of
     *    the next few frames and stops as soon as the field reports focus.
     *  - **The keyboard is offered once per session.** Focus is re-established freely - it is
     *    invisible, and without it nothing works - but the IME is only *shown* the first time, so a
     *    user who put the keyboard away to read output does not have it thrown back at them by a
     *    reconnect, a resize, or a rotation.
     *  - **Never over another field.** The command bar, the search box and the transcript view all own
     *    the keyboard while they are open, and stealing focus from the search box mid-query would make
     *    it impossible to type in.
     */
    LaunchedEffect(activeTab.id, activeTab.state, showCommandBar, showSearch, showHistory) {
        if (!activeTab.state.isLive) return@LaunchedEffect
        if (showCommandBar || showSearch || showHistory) return@LaunchedEffect
        var attempts = 0
        while (!inputFocused && attempts < TERMINAL_FOCUS_ATTEMPTS) {
            // One frame per attempt: the bridge is composed in the same pass as this effect, so the
            // first thing to wait for is the layout that places it.
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
            attempts++
        }
    }
    /**
     * Offers the software keyboard once the field can actually receive it, and only once per session.
     *
     * Separate from the effect above, and keyed on the focus itself, because the two questions have
     * different answers at different times. Showing the IME is only meaningful once something holds
     * focus - `show()` against an unfocused window does nothing at all - and focus does not always
     * arrive inside the retry budget: it can be taken a moment later by the key row's own fallback, by
     * a tap, or by a layout that finally settled. Deciding at the end of the retry loop meant that
     * whenever the budget ran out first, the app ended up in the worst of the two states - a terminal
     * wired to the shell, so keystrokes worked, with no keyboard on screen to produce any, and the user
     * tapping to find out why.
     *
     * [keyboardOffered] still holds it to one offer per tab, so a keyboard the user dismissed to read
     * output is not thrown back at them by the next reconnect, resize or rotation.
     */
    LaunchedEffect(activeTab.id, activeTab.state, inputFocused, showCommandBar, showSearch, showHistory) {
        if (!activeTab.state.isLive) return@LaunchedEffect
        if (showCommandBar || showSearch || showHistory) return@LaunchedEffect
        if (inputFocused && keyboardOffered.add(activeTab.id)) keyboard?.show()
    }

    // Painted to the edges of the screen, laid out inside them.
    //
    // `background` before `safeDrawingPadding` on purpose: the shell's own colour reaches under the
    // display cutout and behind the soft keyboard, while the grid, the key row and the strip stay
    // somewhere they can be read and tapped. The padding has to be here because this screen composes
    // outside the Scaffold - the activity is edge-to-edge, so nothing else is applying window insets to
    // it. It includes the IME, which is what lifts the terminal above the keyboard and gets the pty
    // told the smaller size, exactly as it is told about a rotation. With the system bars hidden there
    // is nothing left in the safe area but the cutout, which is what makes the grid as tall as the
    // screen.
    Column(modifier.fillMaxSize().background(termBg).safeDrawingPadding()) {
        TerminalTabStrip(
            tabs = state.tabs,
            activeTab = activeTab,
            frame = frame,
            onSelect = onSelectSession,
            onDuplicate = onDuplicateSession,
            onLeaveSession = onLeaveSession,
            onCloseTab = onCloseTab,
            onDisconnectAll = onDisconnectAll,
            onReconnect = { onReconnect(activeTab.hostId) },
            onToggleSearch = { showSearch = !showSearch },
            onToggleCommandBar = { showCommandBar = !showCommandBar },
            onToggleHistory = { showHistory = !showHistory },
            onSnippets = { showSnippets = true },
            onSaveLogs = { onSaveLogs(activeTab.hostId, terminalText) },
            onSaveText = { onSaveText(activeTab.hostId, terminalText) },
            onSaveScreen = { onSaveScreen(activeTab.hostId, terminalText) },
            onCopyAll = { onCopyText(terminalText) },
            onPaste = { onPaste(activeTab.id) },
            onScrollToBottom = { onScrollTo(activeTab.id, 0) },
            keyRowVisible = state.settings.terminalKeyRowVisible,
            onToggleKeyRow = { onKeyRowVisible(!state.settings.terminalKeyRowVisible) },
            searchQuery = searchQuery,
            onSearchQuery = { searchQuery = it },
            showSearch = showSearch,
            showCommandBar = showCommandBar,
            showHistory = showHistory,
            terminalText = terminalText,
            // Filtered to this session before it reaches the strip, so nothing on this screen can show
            // one host another host's trace. See [sessionDiagnostics].
            trace = sessionDiagnostics(state.diagnostics, state.diagnosticsLabels[activeTab.hostId]),
            onCopyTrace = onCopyTrace,
        )
        // The terminal takes every pixel that is left, and it is the only thing in this Column that
        // does. That is what makes it a screen rather than a card: the weight is why a full-screen
        // program's own layout - vim's status line at the bottom, top's header at the top - lands where
        // the remote side thinks the window edges are.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (showHistory) {
                // The plain-text view, kept because it is the one that can be selected with the
                // platform's own text handles and read by a screen reader. Bounded scroll, its own
                // state, and no relation to the pty - it is a transcript, not a terminal.
                SelectionContainer {
                    Text(
                        terminalText.ifBlank { "Waiting for remote shell…" },
                        color = termFg,
                        fontFamily = FontFamily.Monospace,
                        fontSize = liveFontSize.sp,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            // The same gap as the live grid, so switching between the two does not
                            // move the text.
                            .padding(textInset),
                    )
                }
            } else {
                TerminalView(
                    frame = frame,
                    style = textStyle,
                    background = termBg,
                    foreground = termFg,
                    metrics = metrics,
                    modifier = Modifier.fillMaxSize().padding(textInset),
                    // The host's own width is a floor here as well as an argument to the pty: a
                    // viewport report resizes the pty, and the first one arrives before any output does.
                    minColumns = minTerminalColumns(state.settings.terminalMinColumns, hostGeometry.first),
                    hostRows = hostGeometry.second,
                    selection = selection,
                    onSelectionChange = { selection = it },
                    onSelectionFinished = { finished ->
                        // A drag that never left its cell is a tap that Compose reported as a drag;
                        // treating it as a selection would copy one character and swallow the tap.
                        if (finished.isSingleCell) {
                            selection = null
                            showKeyboard()
                        } else {
                            onCopySelection(activeTab.id, finished)
                        }
                    },
                    onScroll = { delta -> onScroll(activeTab.id, delta) },
                    onTap = {
                        if (selection != null) selection = null else showKeyboard()
                    },
                    onViewportChange = { columns, rows -> onResize(activeTab.id, columns, rows) },
                    onZoom = { scale, ended ->
                        // The gesture reports a factor against its own start, so the start size has to
                        // be captured once: reading the current size every step would compound the
                        // factor against a size it already changed and run away.
                        val base = if (pinchBase > 0) pinchBase else fontSize.also { pinchBase = it }
                        val next = (base * scale).roundToInt()
                            .coerceIn(SettingsRepository.TERMINAL_FONT_SIZE_RANGE)
                        pinchSize = next
                        if (ended) {
                            pinchBase = 0
                            // Written once, on lift. Writing per step would put a DataStore commit
                            // behind every frame of the gesture.
                            if (next == fontSize) pinchSize = null else onFontSize(next)
                        }
                    },
                    onLongPressCell = { line, column ->
                        val text = state.terminalLine(activeTab.id, line)
                        selection = TerminalSelection.wordAt(line, column, text)
                            // The line's own length, not the grid's: scrollback printed at a wider
                            // terminal keeps every character, and a whole-line selection that stopped
                            // at the current width would copy a truncated line.
                            ?: TerminalSelection.wholeLine(line, maxOf(frame.columns, text.length))
                        selection?.let { onCopySelection(activeTab.id, it) }
                    },
                )
                if (frame.totalLines > 0 && frame.firstLine + frame.lines.size < frame.totalLines) {
                    // Scrolled back, so the live output is no longer on screen. Without this the only
                    // clue is that nothing moves, which reads as a hung session.
                    ScrollbackBadge(
                        lines = frame.totalLines - (frame.firstLine + frame.lines.size),
                        onJump = { onScrollTo(activeTab.id, 0) },
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                    )
                }
            }
        }
        // Collapsible, and collapsed state persisted: the row is what makes a phone keyboard usable
        // against a shell - ESC for vi, TAB for completion, CTRL for ^C - but it is also two rows of
        // chrome above the software keyboard, and on a short screen running `less` there is nothing
        // left to read. Whoever wants the height back gets it, and keeps it across sessions and
        // restarts, without losing the way back: the handle stays on screen in both states.
        val keyRowVisible = state.settings.terminalKeyRowVisible
        TerminalKeyRowHandle(
            expanded = keyRowVisible,
            onToggle = { onKeyRowVisible(!keyRowVisible) },
        )
        if (keyRowVisible) {
            TerminalKeyRow(
                latches = latches,
                onKey = { key, ctrl, alt, shift ->
                    onSendKey(activeTab.id, key, ctrl, alt, shift)
                    // Every cap is a clickable surface, and a clickable surface is focusable: a tap can
                    // leave the IME host unfocused, after which the software keyboard's characters and a
                    // hardware keyboard's keys both have nowhere to go while the row itself still works.
                    // A no-op when the field already has focus, which is the usual case.
                    if (!inputFocused) runCatching { focusRequester.requestFocus() }
                },
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
            )
        }
        if (showCommandBar) {
            TerminalCommandBar(
                command = command,
                onCommand = { command = it; historyIndex = -1 },
                onRecall = recall,
                onSend = {
                    onSendInput(activeTab.id, command + "\n")
                    command = ""
                    historyIndex = -1
                },
                foreground = termFg,
                background = termBg,
                // The live size, not the persisted one: the bar is where the user types the line the
                // grid will echo, and letting it lag a pinch by a frame made it jump on lift.
                fontSize = liveFontSize,
            )
        }
        // The IME host. One pixel, transparent, always in the tree while the terminal is: an
        // InputConnection cannot be established for a view that is not composed, so a field created
        // only when the keyboard was wanted would arrive after the request to open it.
        TerminalInputBridge(
            focusRequester = focusRequester,
            latches = latches,
            onText = { text -> onSendText(activeTab.id, text) },
            onKey = { key, ctrl, alt, shift -> onSendKey(activeTab.id, key, ctrl, alt, shift) },
            onChar = { char, ctrl, alt -> onSendChar(activeTab.id, char, ctrl, alt) },
            onFocusChanged = { focused -> inputFocused = focused },
        )
    }

    if (showSnippets) {
        SnippetsSheet(
            snippets = state.snippets,
            onDismiss = { showSnippets = false },
            // Typed into the remote shell rather than into a form, so the shell's own line editing
            // applies: the snippet arrives on the command line where it can be corrected before Enter,
            // which is what a snippet is for. Deliberately not sent with a newline.
            onInsert = { snippet -> onSendText(activeTab.id, snippet.command); showSnippets = false; showKeyboard() },
            onSaveCurrent = { showSnippets = false; showSaveSnippet = true },
            onDelete = onDeleteSnippet,
        )
    }
    if (showSaveSnippet) {
        SaveSnippetDialog(
            initialCommand = command,
            label = snippetLabel,
            onLabelChange = { snippetLabel = it },
            onDismiss = { showSaveSnippet = false },
            onConfirm = { label -> onSaveSnippet(label, command); showSaveSnippet = false; snippetLabel = "" },
        )
    }
}

/**
 * How much of the tail of a transcript is scanned for a session's last line of output.
 *
 * A screenful of sessions recomposes whenever any of their shells prints anything, and the transcripts
 * run to thousands of lines each. A kilobyte from the end is more than enough to find the last
 * non-blank line and is bounded work per row however long the session has been open.
 */
private const val SESSION_PREVIEW_SCAN_CHARS = 1_000

/**
 * How many frames the terminal spends trying to put focus on its IME host before giving up.
 *
 * A focus request can only land on a node that is attached *and placed*, and on the frame the terminal
 * first composes its one-pixel input field is neither - so a single request is lost on any device slow
 * enough to need a second frame for the first layout, and the user is left with a shell that ignores
 * the keyboard.
 *
 * Sixty frames is about a second at 60Hz. It was five - 80ms - which is a fair estimate of how long a
 * first layout takes and a poor budget for one, because the frames this competes with are the frames
 * of a cold start: the session was dialled, authenticated and given a pty in the same breath, Room and
 * DataStore are reading, and the first composition of a full-screen terminal is measuring a glyph. On a
 * slow device the field can easily still be unplaced after five of those, and the cost of guessing low
 * is the exact complaint this exists to answer. A focus request that finds nothing to focus is cheap;
 * asking sixty times over a second is not something a user can perceive, and it is still bounded, so a
 * screen where focus genuinely cannot be taken - something else holding it, a harness with no window -
 * stops asking rather than spinning for the life of the session.
 */
private const val TERMINAL_FOCUS_ATTEMPTS = 60

/**
 * The Terminal destination with no shell open: every live session, as a list.
 *
 * A terminal app is a session manager as much as it is a terminal - a phone can hold several shells at
 * once, and a strip of chips along the top of a full-screen grid is a poor way to find one of them.
 * This is the screen that answers "what am I connected to", the way PuTTY's session list and JuiceSSH's
 * connections list do: what each session is, whether it is alive, when it started, and the last thing
 * it printed. Tapping one hands it the whole window.
 *
 * Nothing here collects a frame. The preview comes from the throttled transcript the view model
 * already keeps for search and export, so choosing between sessions costs no terminal rendering at all
 * and [dev.eclipse.ssh.presentation.MainViewModel] goes on building no frames until a shell is on
 * screen. That is the same gate the navigation destinations rely on, and this screen must not be the
 * thing that quietly holds it open.
 */
@Composable
private fun TerminalSessionsScreen(
    state: MainUiState,
    onOpenSession: (SessionTab) -> Unit,
    onCloseTab: (SessionTab) -> Unit,
    onDisconnectAll: () -> Unit,
    onReconnect: (String) -> Unit,
    /** Opens this session's host's port-forwarding manager - the forward note on its row. */
    onManageForwards: (String) -> Unit,
    onCopyTrace: (String) -> Unit,
    onGoToHosts: () -> Unit,
) {
    if (state.tabs.isEmpty()) {
        // With a route out rather than a dead end: this is where a restored process, a closed session
        // or a widget tap with nothing connected lands, and Hosts is the only place to go from here.
        EmptyState(
            "No active sessions",
            "Connect to a host to open a secure terminal session.",
            onGoToHosts,
            actionLabel = "Go to hosts",
            actionIcon = Icons.Default.Computer,
        )
        return
    }
    val context = LocalContext.current
    // The platform's own time format, so a 24-hour device shows 24-hour times.
    val timeFormat = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (state.tabs.size == 1) "1 active session" else "${state.tabs.size} active sessions",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDisconnectAll) { Text("Disconnect all") }
        }
        // LazyColumn rather than a scrolling Column: the length of this screen is decided by how many
        // sessions the user opened, and only the rows on screen should be composed.
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Keyed on the session, not the index, so a row keeps its identity - and its state - when
            // another session above it is closed.
            items(state.tabs, key = { it.id }) { tab ->
                SessionRow(
                    tab = tab,
                    host = state.hosts.firstOrNull { it.id == tab.hostId },
                    startedAt = remember(tab.startedAt, timeFormat) {
                        timeFormat.format(java.util.Date(tab.startedAt))
                    },
                    lastOutput = state.terminalOutput[tab.id],
                    // This session's own lines only - see [sessionDiagnostics]. Computed per row and
                    // not remembered: the ring changes while a ladder runs, which is exactly when the
                    // sheet is open and reading it.
                    trace = sessionDiagnostics(state.diagnostics, state.diagnosticsLabels[tab.hostId]),
                    onOpen = { onOpenSession(tab) },
                    onReconnect = { onReconnect(tab.hostId) },
                    onManageForwards = { onManageForwards(tab.hostId) },
                    onCopyTrace = onCopyTrace,
                    onClose = { onCloseTab(tab) },
                )
            }
        }
    }
}

/**
 * The one sentence a session's state is worth.
 *
 * Both places that report a session - the row in the sessions list and the status line above a
 * full-screen shell - read from here, because a session that says "Reconnecting…" in one and
 * "Disconnected" in the other is worse than either. [compact] is the difference between them: the row
 * can afford two lines and a start time, the status line above the terminal is sharing a phone-width
 * row with the Reconnect button and the pty size.
 *
 * Every state says something specific. Before this there were four states and one of them, "Connecting…",
 * covered both dialling and a PAM stack taking fifteen seconds to answer - which is indistinguishable
 * from a hang unless the app says which one it is waiting for.
 *
 * Module-visible rather than private because the bug that made this function famous was invisible from
 * outside: the compact form silently dropped the reason, which reads as a UI detail and was in fact the
 * app throwing away every diagnosis it had. A pure function that decides what the user is told about a
 * failure is worth asserting directly rather than through a rendered screen. See
 * [dev.eclipse.ssh.presentation.retryNotice].
 */
internal fun statusLine(
    state: SessionConnectionState,
    startedAt: String?,
    lastError: String?,
    compact: Boolean,
    networkHeld: Boolean = false,
): String = if (networkHeld && state.isLive) {
    // Said as a connected session that is waiting, because that is what it is: nothing has been closed,
    // and if the network returns to the same address the shell carries on mid-command. Saying
    // "Reconnecting…" here would be a lie in the one direction that matters, since it is the word the
    // user has learnt to read as "your session is gone".
    if (compact) "Connected · waiting for network" else "Connected · waiting for the network to come back"
} else {
    when (state) {
        SessionConnectionState.IDLE -> "Not connected"
        // A working phase with something already written against it is the connect ladder retrying, and
        // its own sentence is better than the phase word: [dev.eclipse.ssh.presentation.retryNotice]
        // says which half of the login failed and which attempt this is, where "Connecting…" says only
        // that the app is busy. Nothing else puts a message on a working state - a fresh connect clears
        // it, and a reconnect stays RECONNECTING - so there is no stale text to leak in here.
        SessionConnectionState.CONNECTING -> lastError ?: "Connecting…"
        SessionConnectionState.AUTHENTICATING -> lastError ?: "Authenticating…"
        // Not "Connecting…": the login is done by this point, and a user watching a slow server open a
        // pty is entitled to know the difference between a host that will not let them in and one that
        // has.
        SessionConnectionState.CHANNEL_PTY_INITIALIZING -> lastError ?: "Opening shell…"
        SessionConnectionState.CONNECTED ->
            if (compact || startedAt == null) "Connected · encrypted" else "Connected · since $startedAt"
        // The reason travels with the state, in compact rows as much as roomy ones. `takeIf { !compact }`
        // used to drop it here, and the terminal screen - the only caller that asks for compact, and the
        // one screen a user is looking at while this happens - was therefore the single place in the app
        // that showed the bare word. Every other ended state keeps its reason when compact; this one
        // deleted the app's entire account of what went wrong, and the two-line row below the call site
        // exists precisely to carry it. What that cost was not cosmetic: the ladder writes "attempt 2 of
        // 5 · The server disconnected: Timeout, your session not responding" onto the tab and keeps it
        // there for the whole recovery, and for several releases none of it reached the screen, so a
        // session that would not stay up could only ever be described as "it keeps reconnecting" - by
        // the user, and by anyone trying to fix it from that report.
        SessionConnectionState.RECONNECTING ->
            lastError?.let { if (compact) "Reconnecting · $it" else it } ?: "Reconnecting…"
        SessionConnectionState.DISCONNECTED -> lastError ?: "Disconnected"
        SessionConnectionState.ERROR -> lastError ?: "Connection failed"
    }
}

/**
 * The colour that state should be said in: green while it is up, red when something failed, amber while
 * it is working on it, and plain body text once it is simply over.
 *
 * ERROR is worth its own colour. A shell that exited and a password that was refused both used to be
 * amber "Disconnected", and only one of those is something the user has to do something about.
 */
@Composable
private fun statusColor(state: SessionConnectionState, networkHeld: Boolean = false): Color = when {
    // A held session is up but unusable until the network is back, which is exactly what amber says
    // everywhere else in this app. Green would invite the user to type into it.
    networkHeld && state.isLive -> EclipseWarning
    state.isLive -> EclipseSuccess
    state == SessionConnectionState.ERROR -> MaterialTheme.colorScheme.error
    state.isBusy -> EclipseWarning
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * One session in [TerminalSessionsScreen]: what it is, whether it is alive, and the way into it.
 *
 * The whole row is the target that opens the shell, with Close and Reconnect as the only smaller ones
 * inside it - both at the far end, where Material's 48dp expansion of a touch target cannot reach back
 * across the text and turn "open this session" into "close it".
 */
@Composable
private fun SessionRow(
    tab: SessionTab,
    host: HostProfile?,
    startedAt: String,
    lastOutput: String?,
    trace: List<SessionDiagnosticEvent>,
    onOpen: () -> Unit,
    onReconnect: () -> Unit,
    /** Opens the host's port-forwarding manager; the forward note is the smaller target inside the row. */
    onManageForwards: () -> Unit,
    onCopyTrace: (String) -> Unit,
    onClose: () -> Unit,
) {
    var showWhy by remember { mutableStateOf(false) }
    val preview = remember(lastOutput) {
        lastOutput?.takeLast(SESSION_PREVIEW_SCAN_CHARS)
            ?.lineSequence()?.lastOrNull { it.isNotBlank() }?.trim()?.take(120)
    }
    // One line about the file browser, and whether it is a problem. The reason sentence comes from
    // describeSftpFailure, so what the snackbar said and what the row keeps saying are the same text.
    val sftpNote: Pair<String, Boolean>? = when (tab.sftpState) {
        SftpSessionState.IDLE -> null
        SftpSessionState.DISABLED -> "SFTP auto login off · SSH only" to false
        SftpSessionState.CONNECTING -> "Signing in to SFTP…" to false
        SftpSessionState.READY -> "SFTP ready" to false
        SftpSessionState.FAILED -> "SFTP: ${tab.sftpError ?: "unavailable"}" to true
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(9.dp).clip(RoundedCornerShape(50))
                    .background(statusColor(tab.state, tab.networkHeld)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    tab.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The profile is looked up rather than stored on the tab so an edited host reads
                // correctly here; a deleted one falls back to the title the session opened with.
                host?.let {
                    Text(
                        "${it.username}@${it.host}:${it.port}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    statusLine(tab.state, startedAt, tab.lastError, compact = false, networkHeld = tab.networkHeld),
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor(tab.state, tab.networkHeld),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Where an SFTP problem lives once the snackbar that first reported it has gone. A
                // failed file browser does not stop the shell, so it must not be announced as a
                // failed session - but it cannot be silent either, or the Files tab looks broken for
                // no reason. IDLE says nothing: nothing has been asked of SFTP yet.
                sftpNote?.let { (note, isProblem) ->
                    Text(
                        note,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isProblem) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // The tunnels this host's rules have up, beside the SFTP note and for the same
                // reason: both are a fact about the session that is not the session's own state, so
                // neither belongs on the status line. Only shown when the host has rules at all -
                // "0 Forwardings Active" on every plain shell is noise wearing an indicator.
                if (tab.forwardsTotal > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        // A smaller target inside a row that is itself the open-the-shell target:
                        // tapping the note wants the tunnels, not the terminal.
                        modifier = Modifier.clickable(onClick = onManageForwards),
                    ) {
                        // Green while any tunnel is up, amber when none are: the ratio against the
                        // total is the manager's first screen, this only has to say "worth a look".
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (tab.forwardsOpen > 0) EclipseSuccess else EclipseWarning),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${tab.forwardsOpen} Forwardings Active",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Why a tunnel is not up, in the same styling the SFTP note uses for its problems -
                // see [SessionTab.forwardError] for why this is deliberately not [SessionTab.lastError].
                tab.forwardError?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // The last thing the shell said, monospaced because it is terminal output. It is the
                // difference between a list of names and a list of sessions.
                if (!preview.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        preview,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Offered here as well as inside the shell, because this is where a dropped session is
            // discovered: the list is what the app falls back to when one ends.
            if (tab.state.isEnded) {
                TextButton(onClick = onReconnect, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Default.Wifi, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reconnect", style = MaterialTheme.typography.labelMedium)
                }
            }
            // Here as well as inside the shell, and for the same reason Reconnect is: this list is where
            // a session that dropped while the app was elsewhere gets discovered.
            if (tab.state.explainable) {
                TextButton(
                    onClick = { showWhy = true },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    // Named by session, because there is one of these per row.
                    modifier = Modifier.semantics { contentDescription = "Why ${tab.title} is ${tab.state.name}" },
                ) {
                    Text("Why?", style = MaterialTheme.typography.labelMedium)
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, "Close ${tab.title} session", modifier = Modifier.size(18.dp))
            }
        }
    }
    if (showWhy) {
        SessionWhySheet(
            tab = tab,
            trace = trace,
            onCopy = onCopyTrace,
            onDismiss = { showWhy = false },
        )
    }
}

/**
 * The text of buffer line [line], read out of the plain-text transcript.
 *
 * Long-press word selection needs the characters around the tap, and the frame only holds the
 * viewport. The transcript is the whole buffer in the same line order, so indexing it gives the line
 * even when the tap landed on scrollback. Out of range returns empty, which selects nothing.
 */
private fun MainUiState.terminalLine(sessionKey: String, line: Int): String {
    val text = terminalOutput[sessionKey] ?: return ""
    return text.lineSequence().elementAtOrNull(line).orEmpty()
}

/** The scrolled-back indicator: how far behind the live output the view is, and a way back. */
@Composable
private fun ScrollbackBadge(lines: Int, onJump: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clickable(onClick = onJump).semantics { contentDescription = "Jump to live output" },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ArrowDownward, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("$lines lines below", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * The one row of chrome above a full-screen terminal: which session, and everything that is not
 * typing.
 *
 * Every action the card interface had is still here - search, snippets, the three exports, the
 * transcript toggle, the command bar - because none of them was replaced by the pty; they were only
 * competing with it for the screen. Collapsing them into an overflow menu is what freed the rest of
 * the window for the terminal itself.
 */
/**
 * The one-tap collapse/expand affordance for [TerminalKeyRow], drawn as a drag handle.
 *
 * Deliberately visible in both states rather than only when collapsed. A control that disappears once
 * used leaves the user with no way back except a menu they have no reason to open, and the same handle
 * in both directions is the pattern every bottom sheet on the platform already teaches. It is slim on
 * purpose - the whole point of collapsing is to give the height to the shell - and the full-size,
 * screen-reader-friendly path to the same setting is the terminal menu's "Shortcut keys" item.
 */
@Composable
private fun TerminalKeyRowHandle(expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(
                onClickLabel = if (expanded) "Hide shortcut keys" else "Show shortcut keys",
                onClick = onToggle,
            )
            .padding(vertical = if (expanded) 2.dp else 5.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
            // The row itself carries the label; naming the arrow too would have TalkBack read the
            // action twice.
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(if (expanded) 16.dp else 18.dp),
        )
        if (!expanded) {
            // Only when collapsed. An empty terminal bottom edge with a bare chevron on it does not
            // say what the chevron brings back, and the row is the app's answer to "where is ESC?".
            Spacer(Modifier.width(4.dp))
            Text(
                "Shortcut keys",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TerminalTabStrip(
    tabs: List<SessionTab>,
    activeTab: SessionTab,
    frame: TerminalFrame,
    onSelect: (SessionTab) -> Unit,
    /** Long-press on a tab (or its menu item): a second, independent shell on the same host. */
    onDuplicate: (SessionTab) -> Unit,
    onLeaveSession: () -> Unit,
    onCloseTab: (SessionTab) -> Unit,
    onDisconnectAll: () -> Unit,
    onReconnect: () -> Unit,
    onToggleSearch: () -> Unit,
    onToggleCommandBar: () -> Unit,
    onToggleHistory: () -> Unit,
    onSnippets: () -> Unit,
    onSaveLogs: () -> Unit,
    onSaveText: () -> Unit,
    onSaveScreen: () -> Unit,
    onCopyAll: () -> Unit,
    onPaste: () -> Unit,
    onScrollToBottom: () -> Unit,
    keyRowVisible: Boolean,
    onToggleKeyRow: () -> Unit,
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    showSearch: Boolean,
    showCommandBar: Boolean,
    showHistory: Boolean,
    terminalText: String,
    trace: List<SessionDiagnosticEvent>,
    onCopyTrace: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showWhy by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 2.dp, end = 4.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The way back to the list of sessions, and the only visible one: a full-screen shell has no
        // navigation bar behind it. The system back gesture does the same thing.
        IconButton(onClick = onLeaveSession) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Show sessions")
        }
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                Surface(
                    // Long-press duplicates: a second shell on the same host, in its own tab, with its
                    // own session. Same gesture as the Files list uses to reach an item's actions, and
                    // the strip's overflow menu carries the same command for anyone who finds gestures
                    // easier to hit than to remember.
                    modifier = Modifier.combinedClickable(
                        onClick = { onSelect(tab) },
                        onLongClick = { onDuplicate(tab) },
                    ),
                    shape = RoundedCornerShape(12.dp),
                    color = if (tab == activeTab) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(Modifier.padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(7.dp).clip(RoundedCornerShape(50))
                                .background(statusColor(tab.state, tab.networkHeld)),
                        )
                        Spacer(Modifier.width(8.dp))
                        // The label reserves its own width so the close button cannot eat the chip's
                        // tap. Material expands any interactive component's *touch target* to 48dp
                        // regardless of its visual size, so a 26dp × reached 11dp past its own edges —
                        // on a chip titled "pi" that expanded target covered the label too, and tapping
                        // the tab to switch to it closed the session instead. A 48dp minimum label plus
                        // a close button that is already 48dp keeps the two targets disjoint at every
                        // title length. The max and the ellipsis are the other end of the same problem:
                        // an unbounded title made a single chip wider than the strip.
                        Text(
                            // The remote window title when a program set one, which is how a session
                            // running `top` or an editor identifies itself in the strip.
                            if (tab == activeTab && !frame.title.isNullOrBlank()) frame.title!! else tab.title,
                            modifier = Modifier.widthIn(min = 48.dp, max = 168.dp),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { onCloseTab(tab) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close ${tab.title} session", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        IconButton(onClick = onToggleSearch) { Icon(Icons.Default.Search, "Search terminal") }
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Terminal actions") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Duplicate terminal") },
                    onClick = { menuOpen = false; onDuplicate(activeTab) },
                )
                DropdownMenuItem(
                    text = { Text(if (showCommandBar) "Hide command bar" else "Show command bar") },
                    onClick = { menuOpen = false; onToggleCommandBar() },
                )
                DropdownMenuItem(
                    text = { Text(if (showHistory) "Live terminal" else "Transcript") },
                    onClick = { menuOpen = false; onToggleHistory() },
                )
                DropdownMenuItem(
                    text = { Text(if (keyRowVisible) "Hide shortcut keys" else "Show shortcut keys") },
                    onClick = { menuOpen = false; onToggleKeyRow() },
                )
                DropdownMenuItem(text = { Text("Snippets") }, onClick = { menuOpen = false; onSnippets() })
                DropdownMenuItem(text = { Text("Paste") }, onClick = { menuOpen = false; onPaste() })
                DropdownMenuItem(text = { Text("Copy all output") }, onClick = { menuOpen = false; onCopyAll() })
                DropdownMenuItem(text = { Text("Jump to live output") }, onClick = { menuOpen = false; onScrollToBottom() })
                DropdownMenuItem(text = { Text("Save logs") }, onClick = { menuOpen = false; onSaveLogs() })
                DropdownMenuItem(text = { Text("Save text") }, onClick = { menuOpen = false; onSaveText() })
                DropdownMenuItem(text = { Text("Save screen") }, onClick = { menuOpen = false; onSaveScreen() })
                DropdownMenuItem(text = { Text("Disconnect all") }, onClick = { menuOpen = false; onDisconnectAll() })
            }
        }
    }
    // The session's own state, on one line. A full-screen terminal has no card header to carry it, and
    // it is the only thing that distinguishes a shell waiting for input from a session that dropped.
    //
    // Its height is reserved rather than fitted, and that is not a cosmetic choice: this row sits
    // directly above the terminal, and the terminal takes what is left, so every dp this row gains is a
    // row of pty the shell loses. Fitted, it grew the moment a session dropped - a second line for the
    // reason, a Why? button that exists only while there is something to explain - and the consequences
    // ran all the way to the far end. The grid shrank by two rows mid-outage, the composable dutifully
    // reported the smaller viewport, [MainViewModel.resizeTerminal] remembered it as the size the user
    // was working at, and the reconnected pty was opened at a geometry that had existed only while the
    // banner was up - then resized again the moment it cleared. A reconnected `top` came back drawn for
    // a window that had already stopped existing, and every recovery cost two spurious window-changes.
    //
    // Reserved for its tallest form, the terminal's geometry changes only when the user's viewport
    // does: a rotation, the keyboard opening, a zoom. A session ending is not one of those.
    Row(
        // Padding first, so the reserved height belongs to the content and the insets are a constant on
        // top of it. The other order would let a two-line reason add the padding twice.
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
            .heightIn(min = TERMINAL_STATUS_ROW_MIN_HEIGHT),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            statusLine(
                activeTab.state,
                startedAt = null,
                lastError = activeTab.lastError,
                compact = true,
                networkHeld = activeTab.networkHeld,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = statusColor(activeTab.state, activeTab.networkHeld),
            modifier = Modifier.weight(1f),
            // Two lines, because this line stopped being a label and became the diagnostic. A session
            // that ends now says which of the six ways it ended - "The server disconnected: Timeout,
            // your session not responding.", "The remote shell was ended by SIGKILL", "Connection
            // lost: Connection reset" - and on a phone-width row shared with the Reconnect button and
            // the pty size, one line ellipsised every one of those to "The server disconnected: T…".
            // The reason a session ended is the only thing on screen that can tell the user whether
            // to look at their network, their account or their server, so it is worth a row that is
            // one line taller. Taller *always*, not only while there is something to say: `minLines`
            // is what makes the second line reserved space rather than growth, and reserving it here -
            // in the text itself - is what makes the guarantee hold at a 200% font scale, where two
            // lines are taller than any touch target and no outer minimum could stand in for them.
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // The way back, next to the reason. A session can end without the user asking - the shell
        // exited, the server went down, the network moved - and until this was here the only route to a
        // working shell again was to close the tab and start over from Hosts, throwing away the
        // scrollback on the way. It goes through the same authentication sheet as any other connection,
        // so a host whose password was never saved asks for it again rather than failing silently.
        if (activeTab.state.isEnded) {
            TextButton(onClick = onReconnect, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Default.Wifi, null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Reconnect", style = MaterialTheme.typography.labelMedium)
            }
        }
        // Offered while a recovery is still running as well as after one has given up, because a ladder
        // that is halfway through its five attempts is exactly when a user wants to know what it is
        // answering - and the two-line status above can only ever show the latest sentence. See
        // [SessionWhySheet].
        if (activeTab.state.explainable) {
            TextButton(
                onClick = { showWhy = true },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.semantics { contentDescription = "Why this session is ${activeTab.state.name}" },
            ) {
                Text("Why?", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (frame.columns > 0) {
            Text(
                "${frame.columns}x${frame.rows}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showSearch) {
        OutlinedTextField(
            searchQuery,
            onSearchQuery,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            placeholder = { Text("Search terminal output") },
            singleLine = true,
        )
        if (searchQuery.isNotBlank()) {
            // Splitting a 2 000-line scrollback on every keystroke is not free, and this recomposes
            // with each of them.
            val matches = remember(terminalText, searchQuery) {
                terminalText.lines().count { it.contains(searchQuery, ignoreCase = true) }
            }
            Text(
                "$matches matches",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
    if (showWhy) {
        SessionWhySheet(
            tab = activeTab,
            trace = trace,
            onCopy = onCopyTrace,
            onDismiss = { showWhy = false },
        )
    }
}

/**
 * The floor under the terminal's status row, which is what keeps the grid a constant size.
 *
 * 48dp because that is what Material lays an interactive component out at: a `TextButton` draws 40dp
 * tall and then reports 48dp, since its touch target is expanded regardless of its visual size - the
 * same rule that made a 26dp close button cover the tab label above. Reconnect and Why? come and go
 * with the session's state, so without a floor at least as tall as one of them the row is one height
 * while a shell is healthy and another while it is not, and the terminal below pays the difference.
 *
 * A floor rather than a fixed height: [Text]'s own `minLines` already reserves the second line, so at a
 * large font scale the two lines outgrow this and the row must be allowed to follow them - clipping the
 * reason to protect the grid would throw away the sentence the row exists for. Both states grow by the
 * same amount, so the guarantee survives.
 */
private val TERMINAL_STATUS_ROW_MIN_HEIGHT = 48.dp

/**
 * Whether there is anything worth explaining about this state.
 *
 * The three states a user asks *why* about: one that gave up, one that finished without being asked to,
 * and one that is in the middle of a recovery. The working states are not included - a session that is
 * still dialling has nothing to account for yet - and CONNECTED is not either, since the answer to "why
 * is it connected" is on the screen behind the sheet.
 */
private val SessionConnectionState.explainable: Boolean
    get() = isEnded || this == SessionConnectionState.RECONNECTING

/**
 * The optional one-line command box.
 *
 * Off by default, because a terminal that requires a form is the thing this screen stopped being. It
 * stays available because two things it does are not reachable from the pty: recalling the *app's*
 * history across reconnects, and editing a long command with a phone's own text handles before any of
 * it reaches the shell.
 */
@Composable
private fun TerminalCommandBar(
    command: String,
    onCommand: (String) -> Unit,
    onRecall: (Int) -> Unit,
    onSend: () -> Unit,
    foreground: Color,
    background: Color,
    fontSize: Int,
) {
    OutlinedTextField(
        value = command,
        onValueChange = onCommand,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown) {
                when (event.key) {
                    Key.DirectionUp -> { onRecall(1); true }
                    Key.DirectionDown -> { onRecall(-1); true }
                    else -> false
                }
            } else false
        },
        placeholder = { Text("Type a command…", color = foreground.copy(alpha = 0.5f)) },
        singleLine = true,
        textStyle = TextStyle(color = foreground, fontFamily = TerminalMonoFontFamily, fontSize = fontSize.sp),
        trailingIcon = { Button(onClick = onSend, enabled = command.isNotBlank()) { Text("Send") } },
        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = background, focusedContainerColor = background),
    )
}

/**
 * How long the form waits after the last passphrase keystroke before trying to read the picked key.
 *
 * Reading is not free — an encrypted OpenSSH key runs bcrypt-pbkdf on purpose — so probing on every
 * keystroke would queue one derivation per character and report verdicts for passphrase prefixes the
 * user was still in the middle of typing.
 */
private const val KEY_PROBE_DEBOUNCE_MS = 300L

/**
 * What the key picker will accept. The wildcard is last and is what actually matters: private keys
 * have no registered MIME type, so providers report them as anything from `text/plain` to
 * `application/octet-stream` to nothing at all, and a narrower filter greys out the very file the
 * user came to pick. The named types come first so pickers that group by type put text files on top.
 */
private val KEY_FILE_MIME_TYPES = arrayOf("application/octet-stream", "text/plain", "*/*")


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnippetsSheet(
    snippets: List<Snippet>,
    onDismiss: () -> Unit,
    onInsert: (Snippet) -> Unit,
    onSaveCurrent: () -> Unit,
    onDelete: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 22.dp).navigationBarsPadding().padding(bottom = 18.dp)) {
            Text("Snippets", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onSaveCurrent, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Save current command") }
            Spacer(Modifier.height(12.dp))
            if (snippets.isEmpty()) {
                Text("No snippets yet. Save a command to reuse it later.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                snippets.forEach { snippet ->
                    Row(Modifier.fillMaxWidth().clickable { onInsert(snippet) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(snippet.label, fontWeight = FontWeight.SemiBold)
                            Text(snippet.command, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
                        }
                        IconButton(onClick = { onDelete(snippet.id) }) { Icon(Icons.Default.Close, "Delete snippet", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveSnippetDialog(initialCommand: String, label: String, onLabelChange: (String) -> Unit, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save snippet") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(label, onLabelChange, label = { Text("Label") }, singleLine = true)
                Text(initialCommand, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onConfirm(label) }, enabled = label.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The Files Explorer: every place files live, behind one interface.
 *
 * A `ColumnScope` member rather than a plain composable, because the listing fills the window: the
 * bar above it is a fixed height, the selection bar below it appears only when something is
 * selected, and everything left over belongs to the files. `weight(1f)` is how that is said, and
 * it can only be said by something that knows it is in a Column.
 *
 * The screen owns almost nothing. Which sessions exist, which one is active, the listing, the
 * selection, the sort — all of it is [FilesExplorerController]'s, collected here. What this
 * composable adds is the dialogs and banners that turn one tap into one operation, and the routing
 * into the two full-window overlays (preview and editor) that [EclipseWorkspace] draws.
 */
@Composable
private fun ColumnScope.FilesScreen(
    state: MainUiState,
    filesExplorer: FilesExplorerController,
    onPreviewFile: (FsEntry, FileSystemProvider) -> Unit,
    onEditFile: (FsEntry, FileSystemProvider) -> Unit,
    onUpload: () -> Unit,
    onDownloadFile: (RemoteFile) -> Unit,
    onPickLocalFolder: () -> Unit,
    onUploadLocal: (LocalFile) -> Unit,
    onScheduleDownload: (List<RemoteFile>, Long, Long?) -> Unit,
    onScheduleUpload: (List<LocalFile>, Long, Long?) -> Unit,
    onSync: (SyncDirection) -> Unit,
    onSendToHost: (RemoteFile, HostProfile, String) -> Unit,
) {
    val explorer by filesExplorer.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // The active session's provider, for the two actions that must hand one to a full-window
    // overlay. Rebuilt when the session changes, not on every recomposition.
    val provider = remember(explorer.activeSessionId) { filesExplorer.providerFor(explorer.activeSessionId) }
    // The selection resolved against the listing it was made in — the controller guarantees the
    // two belong together by clearing the selection on every listing.
    val selectedEntries = explorer.entries.filter { it.path in explorer.selection }

    // The explorer's own first listing: local, always, granted folder or not. Keyed on the session
    // list rather than run once, because the sessions arrive from the database a moment after the
    // tab does. Gated on `path == null` rather than on a flag, so a process death — which resets
    // the controller but restores this screen — lands back in the local session rather than on a
    // permanently blank listing.
    LaunchedEffect(explorer.sessions) {
        if (explorer.sessions.isNotEmpty() && explorer.path == null) filesExplorer.openSession(LOCAL_SESSION_ID)
    }

    var actionEntry by remember { mutableStateOf<FsEntry?>(null) }
    var renameEntry by remember { mutableStateOf<FsEntry?>(null) }
    var chmodEntry by remember { mutableStateOf<FsEntry?>(null) }
    var propertiesEntry by remember { mutableStateOf<FsEntry?>(null) }
    // Confirmed before it happens, like every deletion in this app. Nothing on either backend has
    // a trash can, and the action sits one row above "Properties" on a bottom sheet.
    var deleteEntries by remember { mutableStateOf<List<FsEntry>>(emptyList()) }
    var sendEntry by remember { mutableStateOf<FsEntry?>(null) }
    var showNewFile by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showSync by remember { mutableStateOf(false) }
    var scheduleRemote by remember { mutableStateOf(false) }
    var scheduleLocal by remember { mutableStateOf(false) }
    // A copy or move whose destination is still being chosen: the entries wait while the user
    // navigates. Browsing to the destination is the one honest way to name a folder on both a
    // POSIX server and a SAF document tree — neither can be typed as a path by someone who does
    // not already know it, and the old typed-path dialog could not address the local one at all.
    var pendingRelocate by remember { mutableStateOf<PendingRelocate?>(null) }

    Spacer(Modifier.height(8.dp))
    ExplorerTopBar(
        state = explorer,
        crumbs = ellipsizeCrumbs(explorer.crumbs),
        onSession = filesExplorer::openSession,
        onCrumb = { crumb -> filesExplorer.navigate(crumb.path, crumb.name) },
        onUp = filesExplorer::goUp,
        onRefresh = filesExplorer::refresh,
        onNewFolder = { showNewFolder = true },
        onNewFile = { showNewFile = true },
        onSearch = filesExplorer::search,
        onClearSearch = filesExplorer::clearSearch,
        onSort = filesExplorer::setSort,
        onViewMode = filesExplorer::setViewMode,
        onPickFolder = onPickLocalFolder,
        onUpload = onUpload.takeIf { !explorer.isLocal },
        onSync = { showSync = true }.takeIf { !explorer.isLocal },
    )
    Spacer(Modifier.height(12.dp))

    pendingRelocate?.let { pending ->
        // "Here" is the folder on screen — disabled during a search, whose results span
        // subfolders and so have no single destination.
        Surface(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    "${pending.action} ${pending.entries.size} item(s) — open the destination folder",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(6.dp))
                Row {
                    Button(
                        onClick = {
                            val destination = explorer.path ?: return@Button
                            pendingRelocate = null
                            filesExplorer.run(pending.action) { p ->
                                pending.entries.forEach {
                                    if (pending.copy) p.copy(it.path, destination) else p.move(it.path, destination)
                                }
                            }
                        },
                        enabled = explorer.path != null && explorer.searchQuery == null,
                    ) { Text("${pending.action} here") }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { pendingRelocate = null }) { Text("Cancel") }
                }
            }
        }
    }

    Box(Modifier.fillMaxWidth().weight(1f)) {
        ExplorerList(
            state = explorer,
            onOpen = { entry ->
                if (entry.isDirectory) filesExplorer.navigate(entry.path, entry.name) else provider?.let { onPreviewFile(entry, it) }
            },
            onToggleSelect = filesExplorer::toggleSelected,
            onOpenActions = { actionEntry = it },
        )
    }

    if (explorer.selection.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        ExplorerSelectionBar(
            count = explorer.selection.size,
            onClear = filesExplorer::clearSelection,
            onDownload = if (!explorer.isLocal) {
                // The bar works on the explorer's FsEntry rows; the transfer queue wants the
                // backend-shaped RemoteFile those rows stand for.
                { selectedEntries.map { it.toRemoteFile() }.forEach(onDownloadFile); filesExplorer.clearSelection() }
            } else null,
            onUpload = if (explorer.isLocal) {
                { selectedEntries.map { it.toLocalFile() }.forEach(onUploadLocal); filesExplorer.clearSelection() }
            } else null,
            onSchedule = if (explorer.isLocal) ({ scheduleLocal = true }) else ({ scheduleRemote = true }),
            onCopy = { pendingRelocate = PendingRelocate(copy = true, entries = selectedEntries); filesExplorer.clearSelection() },
            onMove = { pendingRelocate = PendingRelocate(copy = false, entries = selectedEntries); filesExplorer.clearSelection() },
            onDelete = { deleteEntries = selectedEntries },
        )
    }

    actionEntry?.let { entry ->
        ExplorerFileActionsSheet(
            entry = entry,
            isLocal = explorer.isLocal,
            supportsPermissions = explorer.supportsPermissions,
            onDismiss = { actionEntry = null },
            // The sheet closes so the batch bar it summons is visible; the entry joins whatever
            // selection is already active, which is the old long-press behaviour one tap deeper.
            onSelect = { actionEntry = null; filesExplorer.toggleSelected(entry.path) },
            onPreview = { actionEntry = null; provider?.let { onPreviewFile(entry, it) } },
            onEdit = { actionEntry = null; provider?.let { onEditFile(entry, it) } },
            onRename = { actionEntry = null; renameEntry = entry },
            onCopy = { actionEntry = null; pendingRelocate = PendingRelocate(copy = true, entries = listOf(entry)) },
            onMove = { actionEntry = null; pendingRelocate = PendingRelocate(copy = false, entries = listOf(entry)) },
            onDelete = { actionEntry = null; deleteEntries = listOf(entry) },
            onProperties = { actionEntry = null; propertiesEntry = entry },
            onChmod = if (explorer.supportsPermissions) ({ actionEntry = null; chmodEntry = entry }) else null,
            onTransfer = {
                actionEntry = null
                if (explorer.isLocal) onUploadLocal(entry.toLocalFile()) else onDownloadFile(entry.toRemoteFile())
            },
            onSendToHost = if (!explorer.isLocal) ({ actionEntry = null; sendEntry = entry }) else null,
        )
    }

    if (showNewFile) {
        NewFileDialog(
            onDismiss = { showNewFile = false },
            onConfirm = { name ->
                showNewFile = false
                // Created before it is edited, on both backends: a SAF document cannot be
                // addressed until it exists, and a remote path cannot be written through before
                // something makes it. The editor opens on the entry the provider handed back.
                scope.launch {
                    val entry = filesExplorer.createFileHere(name) ?: return@launch
                    provider?.let { onEditFile(entry, it) }
                }
            },
        )
    }
    if (showNewFolder) {
        // The parent is captured at the tap, not inside the operation: by the time the operation
        // runs, the user may have navigated and "the folder on screen" would have moved.
        val parent = explorer.path
        NewFolderDialog(
            onDismiss = { showNewFolder = false },
            onConfirm = { name ->
                showNewFolder = false
                if (parent != null) filesExplorer.run("New folder") { it.createDirectory(parent, name) }
            },
        )
    }
    renameEntry?.let { entry ->
        RenameDialog(
            name = entry.name,
            onDismiss = { renameEntry = null },
            onConfirm = { name ->
                renameEntry = null
                filesExplorer.run("Rename") { it.rename(entry.path, name) }
            },
        )
    }
    chmodEntry?.let { entry ->
        ChmodDialog(
            name = entry.name,
            permissions = entry.permissions,
            onDismiss = { chmodEntry = null },
            onConfirm = { mode ->
                chmodEntry = null
                filesExplorer.run("Permissions") { it.setPermissions(entry.path, mode) }
            },
        )
    }
    propertiesEntry?.let { entry ->
        ExplorerPropertiesDialog(entry = entry, isLocal = explorer.isLocal, onDismiss = { propertiesEntry = null })
    }
    deleteEntries.takeIf { it.isNotEmpty() }?.let { entries ->
        AlertDialog(
            onDismissRequest = { deleteEntries = emptyList() },
            title = {
                Text(if (entries.size == 1) "Delete \"${entries[0].name}\"?" else "Delete ${entries.size} selected item(s)?")
            },
            text = {
                Text(
                    if (entries.size == 1 && entries[0].isDirectory) {
                        "This permanently removes the folder and everything in it."
                    } else {
                        "This permanently removes the selection. Neither backend has a trash can."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val items = entries
                    deleteEntries = emptyList()
                    filesExplorer.run("Delete") { p -> items.forEach { p.delete(it.path) } }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteEntries = emptyList() }) { Text("Cancel") } },
        )
    }
    sendEntry?.let { entry ->
        SendToHostDialog(
            file = entry.toRemoteFile(),
            hosts = state.hosts,
            onDismiss = { sendEntry = null },
            onConfirm = { destHost, destPath ->
                sendEntry = null
                onSendToHost(entry.toRemoteFile(), destHost, destPath)
            },
        )
    }
    if (showSync) {
        SyncDialog(onDismiss = { showSync = false }, onConfirm = { direction -> showSync = false; onSync(direction) })
    }
    if (scheduleRemote) {
        val files = selectedEntries.map { it.toRemoteFile() }
        ScheduleTransferDialog(
            count = files.size,
            direction = "download",
            onDismiss = { scheduleRemote = false },
            onConfirm = { scheduledAt, repeatMinutes ->
                scheduleRemote = false
                onScheduleDownload(files, scheduledAt, repeatMinutes)
                filesExplorer.clearSelection()
            },
        )
    }
    if (scheduleLocal) {
        val files = selectedEntries.map { it.toLocalFile() }
        ScheduleTransferDialog(
            count = files.size,
            direction = "upload",
            onDismiss = { scheduleLocal = false },
            onConfirm = { scheduledAt, repeatMinutes ->
                scheduleLocal = false
                onScheduleUpload(files, scheduledAt, repeatMinutes)
                filesExplorer.clearSelection()
            },
        )
    }
}

/** The preview overlay's subject: an entry plus the provider it must be read through. */
private data class PreviewTarget(
    val entry: FsEntry,
    val provider: FileSystemProvider,
)

/**
 * A transfer's local file as the preview overlay's subject (and, through [PreviewTarget.entry],
 * the editor's). Built at tap time rather than when the sheet opened, and resolved through SAF
 * rather than from anything cached: a grant revoked in between reads as a failure inside the
 * preview, which is where a person can see it, instead of a crash here.
 *
 * The document's own answers are preferred but every one has a fallback, because providers vary
 * in what they will report for a single document — the transfer's name and size are facts the row
 * already knows.
 */
private fun transferLocalTarget(context: Context, item: TransferItem): PreviewTarget? {
    val uri = item.localUri?.let(Uri::parse) ?: return null
    val document = runCatching { DocumentFile.fromSingleUri(context, uri) }.getOrNull()
    return PreviewTarget(
        entry = FsEntry(
            name = document?.name ?: item.name,
            path = uri.toString(),
            isDirectory = false,
            size = document?.length()?.takeIf { it >= 0 } ?: item.totalBytes,
            modifiedEpochMillis = document?.lastModified()?.takeIf { it > 0 },
            permissions = null,
            mimeType = document?.type,
        ),
        provider = SingleDocumentProvider(context, uri),
    )
}

/**
 * Offers the transfer's local file to another app — straight to the handler the resolver picks,
 * or through the chooser when [choose] is set. The read grant rides the intent, the same way the
 * preview sheet's own open-with does; a device where nothing handles the type is a message, not a
 * crash.
 */
private fun openTransferFileExternally(
    context: Context,
    item: TransferItem,
    choose: Boolean,
    onNoApp: (String) -> Unit,
) {
    val uri = item.localUri?.let(Uri::parse) ?: return onNoApp("${item.name} has no local file")
    val mime = runCatching { DocumentFile.fromSingleUri(context, uri)?.type }.getOrNull() ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val target = if (choose) Intent.createChooser(intent, "Open ${item.name} with") else intent
    runCatching { context.startActivity(target) }
        .onFailure { onNoApp("No app on this device can open ${item.name}") }
}

/** The transfer's facts as text, for the clipboard: what it is, where it sits, how far it got. */
private fun transferDetails(item: TransferItem): String = listOfNotNull(
    item.name,
    "${item.direction.label} · ${item.hostName}",
    "Status: ${item.status.name.lowercase()} (${(item.progress * 100).toInt()}%)",
    item.remotePath?.let { "Remote: $it" },
    item.localUri?.let { "Local: $it" },
).joinToString("\n")

/** A copy or move waiting on the user to browse to its destination — see [FilesScreen]. */
private data class PendingRelocate(
    val copy: Boolean,
    val entries: List<FsEntry>,
) {
    val action: String get() = if (copy) "Copy" else "Move"
}

/**
 * An [FsEntry] as the transfer pipeline's remote file, for the operations that still speak that
 * type. The unknowns become the pipeline's own "not reported" values rather than guesses a
 * transfer row would then present as fact.
 */
private fun FsEntry.toRemoteFile() = RemoteFile(
    name = name,
    path = path,
    isDirectory = isDirectory,
    size = size ?: 0L,
    modifiedEpochSeconds = (modifiedEpochMillis ?: 0L) / 1000L,
    permissions = permissions ?: "",
)

/** An [FsEntry] as the transfer pipeline's local file; the provider path *is* the document URI. */
private fun FsEntry.toLocalFile() = LocalFile(
    name = name,
    uri = Uri.parse(path),
    isDirectory = isDirectory,
    size = size ?: 0L,
)

@Composable
private fun NewFileDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New file") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("File name") }, singleLine = true) },
        confirmButton = { Button(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NewFolderDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New folder") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Folder name") }, singleLine = true) },
        confirmButton = { Button(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(name: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var newName by remember { mutableStateOf(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename $name") },
        text = { OutlinedTextField(newName, { newName = it }, label = { Text("New name") }, singleLine = true) },
        confirmButton = { Button(onClick = { onConfirm(newName.trim()) }, enabled = newName.isNotBlank()) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SyncDialog(onDismiss: () -> Unit, onConfirm: (SyncDirection) -> Unit) {
    var direction by remember { mutableStateOf(SyncDirection.LOCAL_TO_REMOTE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sync folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Compares the local folder with the remote directory recursively and transfers missing or changed files. Existing files that are newer on the destination are left untouched.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SyncDirection.entries.forEach { option ->
                        FilterChip(selected = direction == option, onClick = { direction = option }, label = { Text(option.label) })
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(direction) }) { Text("Start sync") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SendToHostDialog(file: RemoteFile, hosts: List<HostProfile>, onDismiss: () -> Unit, onConfirm: (HostProfile, String) -> Unit) {
    var selectedHost by remember { mutableStateOf(hosts.firstOrNull()) }
    var path by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send ${file.name} to another server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("The file is streamed server-to-server without downloading to this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    hosts.forEach { host ->
                        FilterChip(selected = selectedHost?.id == host.id, onClick = { selectedHost = host }, label = { Text(host.name) })
                    }
                }
                OutlinedTextField(path, { path = it }, label = { Text("Destination directory") }, placeholder = { Text("/home/user/archive") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = { selectedHost?.let { onConfirm(it, path.trim()) } }, enabled = selectedHost != null && path.isNotBlank()) { Text("Send") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ScheduleTransferDialog(count: Int, direction: String, onDismiss: () -> Unit, onConfirm: (Long, Long?) -> Unit) {
    val presets = listOf(
        5 * 60_000L to "5 min",
        15 * 60_000L to "15 min",
        60 * 60_000L to "1 hour",
        6 * 60 * 60_000L to "6 hours",
        24 * 60 * 60_000L to "Tomorrow",
    )
    val repeats = listOf(
        null to "Once",
        60L to "Hourly",
        60 * 24L to "Daily",
        60 * 24 * 7L to "Weekly",
    )
    var selectedDelay by remember { mutableLongStateOf(presets[1].first) }
    var repeatMinutes by remember { mutableStateOf<Long?>(null) }
    val scheduledAt = System.currentTimeMillis() + selectedDelay
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schedule $direction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("$count item(s) will be queued and transferred when the network is available. Keep this host connected or reconnect it before the scheduled time.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { (delay, label) ->
                        FilterChip(selected = selectedDelay == delay, onClick = { selectedDelay = delay }, label = { Text(label) })
                    }
                }
                Text("Repeat", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeats.forEach { (interval, label) ->
                        FilterChip(selected = repeatMinutes == interval, onClick = { repeatMinutes = interval }, label = { Text(label) })
                    }
                }
                if (repeatMinutes != null) Text("Repeats every ${formatRepeat(repeatMinutes)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("Starts around ${formatScheduledAt(scheduledAt)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = { Button(onClick = { onConfirm(scheduledAt, repeatMinutes) }) { Text("Schedule") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatRepeat(minutes: Long?): String = when (minutes) {
    null -> ""
    60L -> "hour"
    60 * 24L -> "day"
    60 * 24 * 7L -> "week"
    else -> "$minutes minutes"
}

private fun formatScheduledAt(epochMillis: Long): String = DateTimeFormatter.ofPattern("MMM d, HH:mm")
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(epochMillis))

/**
 * Picks new permissions for [file]. Tapping a row applies it, so there is no confirm button.
 *
 * The rows come from [PERMISSION_PRESETS], which carries the bits; both the octal digits and the
 * `rw-r--r--` reading are derived from them rather than typed alongside them. What the list used to
 * hold instead, and what it did to people's files, is written up there.
 */
@Composable
private fun ChmodDialog(name: String, permissions: String?, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    // Shown so the change can be judged against what is already there — and, on the first release
    // where these presets do what they say, so a file left mis-set by an earlier one is visible.
    val current = permissions
        ?.toIntOrNull(radix = 8)
        ?.let { "$permissions · ${symbolicPermissions(it)}" }
        ?: permissions
        ?: "unknown"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Permissions · $name") },
        text = {
            Column {
                Text(
                    "Currently $current",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                PERMISSION_PRESETS.forEach { preset ->
                    TextButton(onClick = { onConfirm(preset.mode) }, modifier = Modifier.fillMaxWidth()) {
                        Text(preset.label, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Accent choices, each paired with a name. The name is the swatch's accessibility label: the
 * swatches carry no text, so without it TalkBack announces six identical unlabelled buttons and
 * the colour — the only thing distinguishing them — is unavailable to anyone who cannot see it.
 */
private val ACCENT_COLORS = listOf(
    "Red" to 0xFFE53935L,
    "Blue" to 0xFF1E88E5L,
    "Green" to 0xFF43A047L,
    "Orange" to 0xFFFB8C00L,
    "Purple" to 0xFF8E24AAL,
    "Cyan" to 0xFF00ACC1L,
)

private fun safeFileName(value: String): String = value.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_").ifBlank { "account" }

@Composable
private fun TruncatedListingNotice(total: Int, what: String, advice: String) {
    if (total <= MAX_LISTED_ENTRIES) return
    Text(
        "Showing the first $MAX_LISTED_ENTRIES of $total $what. $advice",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
private fun TransfersScreen(
    transfers: List<TransferItem>,
    onClearCompleted: () -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onCancelAll: () -> Unit,
    onRunNow: (String) -> Unit,
    onOpenActions: (TransferItem) -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    val running = transfers.count { it.status == TransferStatus.RUNNING }
    val scheduled = transfers.count { it.scheduledAt != null && it.status == TransferStatus.QUEUED }
    val failed = transfers.count { it.status == TransferStatus.FAILED }
    val complete = transfers.count { it.status == TransferStatus.COMPLETE }
    val unfinished = transfers.size - complete
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Transfer queue", style = MaterialTheme.typography.titleLarge)
            // Only the counts that carry information. A queue with nothing scheduled showing
            // "0 scheduled" is answering a question nobody asked, and the fixed two-count line this
            // replaced could not say the one thing that most needs saying — that something failed.
            Text(
                listOfNotNull(
                    "$running active",
                    scheduled.takeIf { it > 0 }?.let { "$it scheduled" },
                    failed.takeIf { it > 0 }?.let { "$it failed" },
                    "${transfers.size} total",
                ).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (transfers.isEmpty()) {
        EmptyState("No transfers", "Upload or download files to see them here.", null)
        return
    }
    // The bulk actions that are applicable right now, and only those. A screen with one running
    // transfer offering "Pause all" beside "Resume all" beside "Cancel all" is three buttons wide to
    // reach one meaning, so each appears only when there is something for it to act on.
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (running > 0) OutlinedButton(onClick = onPauseAll) { Text("Pause all") }
        if (unfinished > 0) OutlinedButton(onClick = onResumeAll) { Text("Resume all") }
        if (unfinished > 0) OutlinedButton(onClick = onCancelAll) { Text("Cancel all") }
        if (complete > 0) OutlinedButton(onClick = onClearCompleted) { Text("Clear completed") }
    }
    Spacer(Modifier.height(12.dp))
    // Filters over the same list, shown only where they would separate something. A filter whose
    // count is zero filters nothing, and five chips on a queue of two transfers is most of a row
    // spent on ways to see fewer items.
    var filter by remember { mutableStateOf(TransferFilter.ALL) }
    val filterable = TransferFilter.entries.filter { f ->
        f == TransferFilter.ALL || transfers.any { it.matches(f) }
    }
    if (filterable.size > 1) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            filterable.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }) }
        }
        Spacer(Modifier.height(12.dp))
    }
    // Bounded for the same reason the file listings are — see [MAX_LISTED_ENTRIES] — and this is
    // the screen where the count actually runs away: a directory sync writes one row per file and
    // never deletes them, so syncing a few thousand files leaves a few thousand cards to compose
    // on every visit to this tab, each heavier than a file row. Which rows survive the cut is a
    // rule with a test, in [transfersForDisplay].
    val visible = remember(transfers) { transfersForDisplay(transfers) }.filter { it.matches(filter) }
    if (visible.isEmpty()) {
        EmptyState(filter.emptyTitle, "Nothing in this state right now.", null)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            visible.forEach { TransferCard(it, onPause, onResume, onCancel, onRunNow, onOpenActions) }
            TruncatedListingNotice(
                transfers.size,
                "transfers",
                "Clear completed to see the rest.",
            )
        }
    }
}

/** The Transfers tab's filter chips. Everything unfinished and unscheduled reads as "active". */
private enum class TransferFilter(val label: String, val emptyTitle: String) {
    ALL("All", "No transfers"),
    ACTIVE("Active", "Nothing active"),
    SCHEDULED("Scheduled", "Nothing scheduled"),
    FAILED("Failed", "Nothing failed"),
    DONE("Done", "Nothing completed"),
}

private fun TransferItem.matches(filter: TransferFilter): Boolean = when (filter) {
    TransferFilter.ALL -> true
    // PAUSED belongs here rather than nowhere: it is a transfer part-way through, one Resume away
    // from running, and a filter list that hid it would make a paused queue look empty.
    TransferFilter.ACTIVE -> status == TransferStatus.RUNNING || status == TransferStatus.PAUSED ||
        (status == TransferStatus.QUEUED && scheduledAt == null)
    TransferFilter.SCHEDULED -> status == TransferStatus.QUEUED && scheduledAt != null
    TransferFilter.FAILED -> status == TransferStatus.FAILED
    TransferFilter.DONE -> status == TransferStatus.COMPLETE
}

/**
 * One transfer as a card.
 *
 * The card's own buttons stay (pause, resume, cancel) because they are the one-tap answers to the
 * states a watched transfer cycles through; the long-press sheet is everything else — the file the
 * transfer is about, copied details, removal — which is why the gesture is on the whole card and
 * not just its chrome. Tap stays inert: unlike a file row there is nothing a transfer "opens".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransferCard(item: TransferItem, onPause: (String) -> Unit, onResume: (String) -> Unit, onCancel: (String) -> Unit, onRunNow: (String) -> Unit, onOpenActions: (TransferItem) -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = { onOpenActions(item) }),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(40.dp)) { Box(contentAlignment = Alignment.Center) { Icon(if (item.direction == TransferDirection.DOWNLOAD) Icons.Default.CloudDownload else Icons.Default.CloudUpload, null, tint = MaterialTheme.colorScheme.primary) } }
                Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${item.hostName} · ${item.sizeLabel}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                when (item.status) {
                    TransferStatus.COMPLETE -> Icon(Icons.Default.CheckCircle, "Complete", tint = EclipseSuccess)
                    TransferStatus.RUNNING -> IconButton(onClick = { onPause(item.id) }) { Icon(Icons.Default.Pause, "Pause", tint = MaterialTheme.colorScheme.primary) }
                    // A retry is a resume with a different name, and the icon says which: the arrow
                    // asks to continue what was interrupted, the refresh says start over because the
                    // last try did not work.
                    else -> IconButton(onClick = { onResume(item.id) }) {
                        Icon(
                            if (item.status == TransferStatus.FAILED) Icons.Default.Refresh else Icons.Default.PlayArrow,
                            if (item.status == TransferStatus.FAILED) "Retry ${item.name}" else "Resume",
                        )
                    }
                }
                if (item.status != TransferStatus.COMPLETE) {
                    IconButton(onClick = { onCancel(item.id) }) { Icon(Icons.Default.Close, "Cancel transfer", tint = MaterialTheme.colorScheme.error) }
                }
            }
            if (item.status == TransferStatus.QUEUED && item.scheduledAt != null) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Scheduled for ${formatScheduledAt(item.scheduledAt)}" + if (item.repeatMinutes != null) " · repeats ${formatRepeat(item.repeatMinutes)}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    // The schedule's own start time is a suggestion this replaces: run-now cancels the
                    // pending schedule and starts immediately, so a transfer queued for tonight does
                    // not have to wait out the afternoon for it.
                    TextButton(onClick = { onRunNow(item.id) }) { Text("Run now") }
                }
            }
            if (item.status == TransferStatus.RUNNING || item.status == TransferStatus.PAUSED || item.status == TransferStatus.FAILED) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.height(5.dp))
                Text(
                    listOfNotNull(
                        "${(item.progress * 100).toInt()}%",
                        item.status.name.lowercase(),
                        item.retryCount.takeIf { it > 0 }?.let { "retry $it/${MAX_TRANSFER_RETRIES}" },
                        // The byte pair when the size is known, because a percentage alone rounds
                        // everything below 1% to "0%" — a transfer 40 MB into a 4 GB file looks
                        // stalled at "0%" while the bytes beside it say otherwise.
                        item.totalBytes?.takeIf { it > 0 }?.let { "${formatTransferBytes(item.transferredBytes)} / ${formatTransferBytes(it)}" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Why it failed, in the server's own words when it gave any. A FAILED row without
                // this asks the user to guess between a dead network, a full disk and a permission
                // the transfer never had.
                if (item.status == TransferStatus.FAILED && !item.errorMessage.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(item.errorMessage, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** Bytes in the units transfers actually reach, for the "4.2 GB / 1.8 GB" progress line. */
private fun formatTransferBytes(bytes: Long): String {
    var value = bytes.toDouble()
    var unit = "B"
    for (next in listOf("KB", "MB", "GB", "TB")) {
        if (value < 1024) break
        value /= 1024
        unit = next
    }
    return if (unit == "B") "$bytes B" else "${"%.1f".format(value)} $unit"
}

/**
 * The per-transfer action sheet: everything one transfer can do, opened by long-pressing its card.
 *
 * The card's own buttons remain the one-tap answers to the states a watched transfer cycles
 * through; this is the complete list, and like the Files explorer's action sheet it offers only
 * what the item's own state can serve — a control action for its current status, and the file
 * actions only when the local file actually exists in full.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferActionsSheet(
    item: TransferItem,
    onDismiss: () -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRunNow: (String) -> Unit,
    onViewFile: (TransferItem) -> Unit,
    onEditFile: (TransferItem) -> Unit,
    onOpenFile: (TransferItem) -> Unit,
    onOpenFileWith: (TransferItem) -> Unit,
    onCopyDetails: (TransferItem) -> Unit,
) {
    // The local file exists in full once a download completes, and from the very start for an
    // upload — it is the source the bytes come from. A download in any other state has only a
    // prefix on disk, and previewing or editing a prefix would show content the user would take
    // for the whole file.
    val hasLocalFile = item.localUri != null &&
        (item.status == TransferStatus.COMPLETE || item.direction == TransferDirection.UPLOAD)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 18.dp)) {
            Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${item.direction.label} · ${item.hostName} · ${item.status.name.lowercase()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            // The control the status asks for, in the card's own vocabulary: a retry is a resume
            // the user chose to name differently, exactly as the card's icon does.
            when (item.status) {
                TransferStatus.RUNNING -> TransferActionRow("Pause") { onPause(item.id) }
                TransferStatus.PAUSED -> TransferActionRow("Resume") { onResume(item.id) }
                TransferStatus.FAILED -> TransferActionRow("Retry") { onResume(item.id) }
                TransferStatus.QUEUED -> if (item.scheduledAt != null) {
                    TransferActionRow("Run now") { onRunNow(item.id) }
                } else {
                    TransferActionRow("Resume") { onResume(item.id) }
                }
                TransferStatus.COMPLETE -> Unit
            }
            if (item.status != TransferStatus.COMPLETE) {
                TransferActionRow("Cancel transfer", destructive = true) { onCancel(item.id) }
            }
            if (hasLocalFile) {
                TransferActionRow("View file") { onViewFile(item) }
                // Editing only a finished file: overwriting the source of a running upload, or a
                // half-written download target, races the transfer that is still writing it.
                if (item.status == TransferStatus.COMPLETE) {
                    TransferActionRow("Edit as text") { onEditFile(item) }
                }
                TransferActionRow("Open") { onOpenFile(item) }
                TransferActionRow("Open with") { onOpenFileWith(item) }
            }
            TransferActionRow("Copy details") { onCopyDetails(item) }
            if (item.status == TransferStatus.COMPLETE) {
                // Cancel for a finished transfer stops nothing — it only drops the row, so the
                // sheet names it for what it does here.
                TransferActionRow("Remove from list", destructive = true) { onCancel(item.id) }
            }
        }
    }
}

/** One row of the transfer action sheet, red where the action removes something. */
// The click goes last so every row reads as `TransferActionRow(label) { ... }` — with a trailing
// Boolean the trailing lambda would have nothing to bind to.
@Composable
private fun TransferActionRow(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    Text(
        label,
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun SettingsScreen(
    state: MainUiState,
    onBiometric: (Boolean) -> Unit,
    onDarkTheme: (Boolean) -> Unit,
    onAddForward: (ForwardType, Int, String?, Int?) -> Unit,
    onStopForward: (String) -> Unit,
    onExportVault: () -> Unit,
    onImportVault: () -> Unit,
    onKeepAlive: (Int) -> Unit,
    onClipboard: (Int) -> Unit,
    onTerminalFontSize: (Int) -> Unit,
    onTerminalMinColumns: (Int) -> Unit = {},
    // After the last parameter its only caller passes positionally, so adding it did not shift
    // onTerminalFontSize onto a different slot.
    onReconnectBase: (Int) -> Unit = {},
    onLegacyAlgorithms: (Boolean) -> Unit,
    onBlockScreenshots: (Boolean) -> Unit,
    onReconnectAskFirst: (Boolean) -> Unit = {},
    onTerminalTheme: (String) -> Unit,
    onSetPin: (String) -> Unit,
    onClearPin: () -> Unit,
    verifyPin: suspend (String) -> Boolean,
    onForgetKnownHost: (String) -> Unit,
    onClearKnownHosts: () -> Unit,
    onGenerateKey: () -> Unit,
    onImportSshConfig: () -> Unit,
    onForgetAllCredentials: () -> Unit = {},
    onCopyDiagnostics: () -> Unit = {},
    onSaveDiagnostics: () -> Unit = {},
    onClearDiagnostics: () -> Unit = {},
) {
    var showForwardDialog by remember { mutableStateOf(false) }
    var showKeepAliveDialog by remember { mutableStateOf(false) }
    var showClipboardDialog by remember { mutableStateOf(false) }
    var showReconnectDialog by remember { mutableStateOf(false) }
    var showFontDialog by remember { mutableStateOf(false) }
    var showWidthDialog by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showKnownHosts by remember { mutableStateOf(false) }
    var confirmForgetCredentials by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    // Hosts with at least one secret saved. `savedCredentials` only ever contains entries the store
    // actually wrote, but an entry whose secrets were all forgotten individually can still be present
    // with nothing in it, so the count filters rather than reading `size`.
    val savedCredentialCount = state.savedCredentials.count { (_, saved) -> !saved.isEmpty }
    Spacer(Modifier.height(8.dp))
    SettingsSection("Security") {
        SettingRow(Icons.Default.Lock, "Biometric vault lock", "Protect passwords and private keys") { Switch(checked = state.settings.biometricUnlock, onCheckedChange = onBiometric) }
        SettingRow(Icons.Default.Key, "Generate SSH key pair", "RSA 2048/4096 or ECDSA P-256, exported as PEM") { TextButton(onClick = onGenerateKey) { Text("Generate") } }
        SettingRow(Icons.Default.Lock, "PIN lock", if (state.settings.pinEnabled) "Enabled · PIN fallback at launch" else "Set a PIN for quick unlock") { TextButton(onClick = { showPinDialog = true }) { Text(if (state.settings.pinEnabled) "Change" else "Set") } }
        SettingRow(Icons.Default.Security, "Encrypted vault", "AES-256-GCM · Android Keystore") { Text("Protected", color = EclipseSuccess, style = MaterialTheme.typography.labelMedium) }
        SettingRow(Icons.Default.Key, "Known hosts", "${state.knownHosts.size} trusted fingerprint(s)") { TextButton(onClick = { showKnownHosts = true }) { Text("Manage") } }
        SettingRow(
            Icons.Default.Security,
            "Saved credentials",
            // Counted, not listed: the names belong on the host that owns them, and a count is what
            // answers "did I leave a password on something I have forgotten about".
            if (savedCredentialCount == 0) {
                "No passwords or keys are saved"
            } else {
                "$savedCredentialCount host(s) · passwords and keys in the vault"
            },
        ) {
            TextButton(
                onClick = { confirmForgetCredentials = true },
                enabled = savedCredentialCount > 0,
            ) { Text("Forget all") }
        }
    }
    if (confirmForgetCredentials) {
        AlertDialog(
            onDismissRequest = { confirmForgetCredentials = false },
            title = { Text("Forget every saved credential?") },
            text = {
                Text(
                    "Passwords, private keys and passphrases saved for all $savedCredentialCount host(s) " +
                        "are deleted from the vault. The hosts themselves are kept, and each will ask for " +
                        "credentials again at the next connect. Sessions already open are unaffected.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onForgetAllCredentials(); confirmForgetCredentials = false },
                ) { Text("Forget all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmForgetCredentials = false }) { Text("Cancel") } },
        )
    }
    Spacer(Modifier.height(14.dp))
    SettingsSection("Workspace") {
        SettingRow(Icons.Default.Settings, "Dark appearance", "Optimized for terminal work") { Switch(checked = state.settings.darkTheme, onCheckedChange = onDarkTheme) }
        SettingRow(Icons.Default.Wifi, "Keep-alive interval", "Every ${state.settings.keepAliveSeconds} seconds") { TextButton(onClick = { showKeepAliveDialog = true }) { Text("Change") } }
        SettingRow(Icons.Default.Security, "Clipboard auto-clear", if (state.settings.clearClipboardAfterSeconds == 0) "Never clear copied secrets automatically" else "Clear secrets after ${state.settings.clearClipboardAfterSeconds} seconds") { TextButton(onClick = { showClipboardDialog = true }) { Text("Change") } }
        SettingRow(Icons.Default.Terminal, "Terminal font size", "${state.settings.terminalFontSize} sp JetBrains Mono") { TextButton(onClick = { showFontDialog = true }) { Text("Change") } }
        SettingRow(
            Icons.Default.Terminal,
            "Terminal width",
            if (state.settings.terminalMinColumns <= 0) {
                "Exactly what fits on screen, so the server wraps long lines"
            } else {
                "At least ${state.settings.terminalMinColumns} columns; drag sideways for the rest"
            },
        ) { TextButton(onClick = { showWidthDialog = true }) { Text("Change") } }
        SettingRow(Icons.Default.Terminal, "Terminal theme", "Colours the grid and its background") {
            SettingDropdown(
                label = "Terminal theme",
                options = TerminalTheme.entries,
                selected = TerminalTheme.named(state.settings.terminalTheme),
                optionLabel = { it.label },
                onSelect = { theme -> onTerminalTheme(theme.name) },
            )
        }
        SettingRow(Icons.Default.Security, "Legacy algorithms", "Also offer CBC, SHA-1 and dh-group1 to reach older servers") { Switch(checked = state.settings.legacyAlgorithms, onCheckedChange = onLegacyAlgorithms) }
        SettingRow(Icons.Default.Lock, "Block screenshots", "Hides this app from screenshots, screen recording and the recents preview") { Switch(checked = state.settings.blockScreenshots, onCheckedChange = onBlockScreenshots) }
    }
    Spacer(Modifier.height(14.dp))
    SettingsSection("Port forwarding") {
        if (state.forwardings.isEmpty()) {
            SettingRow(Icons.Default.SwapVert, "No active forwards", "Local, remote, and dynamic (SOCKS5)") { TextButton(onClick = { showForwardDialog = true }) { Text("Add") } }
        } else {
            state.forwardings.forEach { entry ->
                val hostName = entry.hostId?.let { id -> state.hosts.firstOrNull { it.id == id }?.name }
                SettingRow(
                    Icons.Default.SwapVert,
                    "${entry.type.label} · 127.0.0.1:${entry.localPort}",
                    listOfNotNull(
                        hostName,
                        entry.remoteHost?.let { "→ $it:${entry.remotePort}" }
                            ?: if (entry.type == ForwardType.DYNAMIC) "SOCKS5 proxy" else "→ local:${entry.remotePort}",
                    ).joinToString(" · "),
                ) { TextButton(onClick = { onStopForward(entry.id) }) { Text("Stop") } }
            }
            TextButton(onClick = { showForwardDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Add forward") }
        }
    }
    Spacer(Modifier.height(14.dp))
    SettingsSection("Backup & restore") {
        SettingRow(Icons.Default.CloudUpload, "Export encrypted backup", "Hosts and settings, passphrase-protected") { TextButton(onClick = onExportVault) { Text("Export") } }
        SettingRow(Icons.Default.CloudDownload, "Import backup", "Restore hosts and settings") { TextButton(onClick = onImportVault) { Text("Import") } }
        SettingRow(Icons.Default.Computer, "Import OpenSSH config", "Parse Host blocks from ssh_config") { TextButton(onClick = onImportSshConfig) { Text("Import") } }
    }
    Spacer(Modifier.height(14.dp))
    SettingsSection("Background processing") {
        SettingRow(
            Icons.Default.SwapVert,
            "Session manager",
            "Foreground service ready for active SSH, SFTP and forwards",
        ) { Icon(Icons.Default.CheckCircle, "Ready", tint = EclipseSuccess) }
        SettingRow(
            Icons.Default.HelpOutline,
            "Ask before reconnecting",
            "Dropped sessions wait for your answer instead of reconnecting on their own",
        ) { Switch(checked = state.settings.reconnectAskFirst, onCheckedChange = onReconnectAskFirst) }
        SettingRow(
            Icons.Default.Refresh,
            "Reconnect delay",
            "First retry after ${state.settings.reconnectBaseSeconds} s, then doubling · auto reconnect on network recovery",
        ) { TextButton(onClick = { showReconnectDialog = true }) { Text("Change") } }
        SettingRow(
            Icons.Default.Terminal,
            "Connection diagnostics",
            if (state.diagnostics.isEmpty()) {
                "Records every connect, drop and reconnect · no secrets"
            } else {
                "${state.diagnostics.size} event(s) recorded · no secrets"
            },
        ) { TextButton(onClick = { showDiagnostics = true }) { Text("View") } }
    }

    if (showDiagnostics) {
        DiagnosticsDialog(
            events = state.diagnostics,
            onDismiss = { showDiagnostics = false },
            onCopy = onCopyDiagnostics,
            onSave = { showDiagnostics = false; onSaveDiagnostics() },
            onClear = onClearDiagnostics,
        )
    }

    if (showForwardDialog) {
        ForwardDialog(
            onDismiss = { showForwardDialog = false },
            onConfirm = { type, localPort, remoteHost, remotePort -> showForwardDialog = false; onAddForward(type, localPort, remoteHost, remotePort) },
        )
    }
    if (showKeepAliveDialog) {
        IntervalDialog(
            title = "Keep-alive interval",
            subtitle = "Seconds between SSH keep-alive signals",
            current = state.settings.keepAliveSeconds,
            options = listOf(15, 30, 60, 120, 300),
            onDismiss = { showKeepAliveDialog = false },
            onConfirm = { onKeepAlive(it); showKeepAliveDialog = false },
        )
    }
    if (showReconnectDialog) {
        IntervalDialog(
            title = "Reconnect delay",
            subtitle = "Wait before the first reconnect attempt; each further attempt doubles it",
            current = state.settings.reconnectBaseSeconds,
            options = SettingsRepository.RECONNECT_BASE_CHOICES,
            onDismiss = { showReconnectDialog = false },
            onConfirm = { onReconnectBase(it); showReconnectDialog = false },
        )
    }
    if (showClipboardDialog) {
        IntervalDialog(
            title = "Clipboard auto-clear",
            subtitle = "Clear copied secrets after (0 = never)",
            current = state.settings.clearClipboardAfterSeconds,
            options = listOf(0, 15, 30, 60, 120),
            onDismiss = { showClipboardDialog = false },
            onConfirm = { onClipboard(it); showClipboardDialog = false },
        )
    }
    if (showFontDialog) {
        FontSizeDialog(
            current = state.settings.terminalFontSize,
            onDismiss = { showFontDialog = false },
            onConfirm = { onTerminalFontSize(it); showFontDialog = false },
        )
    }
    if (showWidthDialog) {
        TerminalWidthDialog(
            current = state.settings.terminalMinColumns,
            onDismiss = { showWidthDialog = false },
            onConfirm = { onTerminalMinColumns(it); showWidthDialog = false },
        )
    }
    if (showPinDialog) {
        PinDialog(
            enabled = state.settings.pinEnabled,
            onDismiss = { showPinDialog = false },
            verifyPin = verifyPin,
            onSetPin = onSetPin,
            onClearPin = onClearPin,
        )
    }
    if (showKnownHosts) {
        KnownHostsDialog(
            hosts = state.knownHosts,
            onDismiss = { showKnownHosts = false },
            onForget = onForgetKnownHost,
            onClearAll = { onClearKnownHosts(); showKnownHosts = false },
        )
    }
}

/**
 * Why *this* session is in the state it is in: its own reason, and its own slice of the trace.
 *
 * The app has recorded all of this since the diagnostics work landed, and every word of it was two
 * screens and four taps away - Settings, Background processing, Connection diagnostics, View - in a
 * five-hundred-line ring shared by every host, at the moment when the user is looking at a tab that
 * will not stay connected. So the single most-reported problem with this app arrived as "it keeps
 * reconnecting", not because the app did not know why, but because nothing put the answer where the
 * question is asked.
 *
 * [trace] is already filtered to this session and ordered newest first by [sessionDiagnostics], and
 * every line is already scrubbed and carries an opaque `s1`/`s2` label rather than a host name - so
 * what Copy puts on the clipboard is safe to paste into a bug report as it stands. The reason line goes
 * through [scrub] as well: it is passed through from the transport library, which is the one string
 * here this app did not compose itself.
 */
@Composable
private fun SessionWhySheet(
    tab: SessionTab,
    trace: List<SessionDiagnosticEvent>,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val clock = remember {
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    }
    val reason = remember(tab.lastError) { tab.lastError?.let(::scrub) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                when {
                    tab.state == SessionConnectionState.RECONNECTING -> "Why it is reconnecting"
                    tab.state.isBusy -> "What it is waiting for"
                    else -> "Why it ended"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                reason ?: "No reason was recorded for this session.",
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor(tab.state, tab.networkHeld),
            )
            if (trace.isEmpty()) {
                Text(
                    "Nothing has been recorded for this session yet.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "This session's last ${trace.size} event(s), newest first. No password, key or host " +
                        "name is recorded, so this is safe to attach to a bug report.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    // 0.45 of the screen: a trace a page tall is a trace worth scrolling, and the
                    // LazyColumn is the scroller. Replaces a fixed 320dp that assumed one phone.
                    Modifier.heightIn(max = rememberDialogBodyMaxHeight(0.45f)),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(trace, key = { it.sequence }) { entry ->
                        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text(
                                    clock.format(Instant.ofEpochMilli(entry.atMs)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    // The epoch millis at the front of `line()` is for the exported
                                    // file; the clock above says the same thing to a reader.
                                    entry.line().substringAfter(' '),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        // Oldest first in the copy, the opposite of the display: on screen the answer
                        // wanted is the last thing that happened, and in a pasted report the reader
                        // needs to follow the session forwards.
                        onCopy(
                            buildString {
                                append(tab.state.name)
                                reason?.let { append(" · ").append(it) }
                                trace.asReversed().forEach { append('\n').append(it.line()) }
                            },
                        )
                    },
                ) { Text("Copy") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

@Composable
private fun DiagnosticsDialog(
    events: List<SessionDiagnosticEvent>,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    // Newest first. A trace is read to answer "what just happened", and the answer is at the end of a
    // ring that holds up to five hundred entries.
    val ordered = remember(events) { events.asReversed() }
    // Fixed 24-hour with seconds, not the locale's time format: the interval between two events is the
    // whole point of reading this, and half the locales drop seconds entirely.
    val clock = remember {
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connection diagnostics") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(max = rememberDialogBodyMaxHeight(0.60f))) {
                if (events.isEmpty()) {
                    Text(
                        "Nothing recorded yet. Connect a host and this becomes a timestamped trace of every connect, disconnect, reconnect and network change.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "${events.size} event(s) · hosts are labelled s1, s2… and the number after the dot counts that host's connections, so s2.3 is its third. No password, key or host name is recorded, so this is safe to attach to a bug report.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // The line() form starts with the epoch millis, which the exported text needs and
                        // a reader does not; the row shows a clock and drops the raw number.
                        items(ordered, key = { it.sequence }) { entry ->
                            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                                    Text(
                                        clock.format(Instant.ofEpochMilli(entry.atMs)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        entry.line().substringAfter(' '),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onCopy) { Text("Copy") }
                        TextButton(onClick = onSave) { Text("Save") }
                        TextButton(onClick = onClear) { Text("Clear", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun KnownHostsDialog(
    hosts: Map<String, String>,
    onDismiss: () -> Unit,
    onForget: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    val entries = hosts.toList().sortedBy { it.first }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Known hosts") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(max = rememberDialogBodyMaxHeight(0.60f))) {
                if (entries.isEmpty()) {
                    Text("No trusted hosts yet. You'll be asked to verify a host's fingerprint the first time you connect.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Fingerprints you've trusted. Removing one will prompt you to verify again on next connect.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(entries) { index, (key, fingerprint) ->
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(key, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(fingerprint, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    TextButton(onClick = { onForget(key) }) { Text("Forget") }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = if (entries.isNotEmpty()) {
            { TextButton(onClick = onClearAll) { Text("Forget all", color = MaterialTheme.colorScheme.error) } }
        } else null,
    )
}

@Composable
private fun IntervalDialog(title: String, subtitle: String, current: Int, options: List<Int>, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var selected by remember { mutableIntStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    options.forEach { option ->
                        FilterChip(
                            selected = selected == option,
                            onClick = { selected = option },
                            label = { Text(if (option == 0) "Off" else "$option s") },
                        )
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(selected) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Picks the narrowest grid the pty may be given. See [dev.eclipse.ssh.data.model.AppSettings.terminalMinColumns].
 *
 * Its own dialog rather than an [IntervalDialog], only because that one labels every chip in seconds.
 * The choices come from the repository that clamps them, so a chip cannot offer a width that would be
 * stored as a different number.
 */
@Composable
private fun TerminalWidthDialog(current: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var selected by remember { mutableIntStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Terminal width") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "80 columns is what command-line output is formatted for. Ask for fewer than the " +
                        "server needs and it breaks paths, URLs and tables itself, which nothing here " +
                        "can undo; ask for more than the screen fits and the grid pans sideways.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    SettingsRepository.TERMINAL_MIN_COLUMN_CHOICES.forEach { option ->
                        FilterChip(
                            selected = selected == option,
                            onClick = { selected = option },
                            label = { Text(if (option == 0) "Fit screen" else "$option cols") },
                        )
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(selected) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FontSizeDialog(current: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    // One range, named once. The repository clamps to it on read and on write, this dialog offers it,
    // and the terminal's pinch-to-zoom gesture rounds into it - three places that have to agree about
    // what a legal font size is, and did not while each carried its own literal.
    val range = SettingsRepository.TERMINAL_FONT_SIZE_RANGE
    var size by remember { mutableFloatStateOf(SettingsRepository.normalizeFontSize(current).toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Terminal font size") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${size.toInt()} sp", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Pinch the terminal with two fingers to zoom without opening this dialog.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = size,
                    onValueChange = { size = it },
                    valueRange = range.first.toFloat()..range.last.toFloat(),
                    steps = range.last - range.first - 1,
                )
            }
        },
        confirmButton = { Button(onClick = { onConfirm(size.toInt()) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PinDialog(
    enabled: Boolean,
    onDismiss: () -> Unit,
    verifyPin: suspend (String) -> Boolean,
    onSetPin: (String) -> Unit,
    onClearPin: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var stage by remember { mutableIntStateOf(if (enabled) 0 else 1) }
    var current by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var verifying by remember { mutableStateOf(false) }

    fun verifyCurrent(onOk: () -> Unit) {
        // Guarded the same way the lock screen is: verification is PBKDF2 at 60k iterations, so
        // it now suspends for a noticeable moment, and without this a double-tap — or a tap on
        // "Disable" followed by "Change" — runs two verifications whose callbacks both fire.
        if (verifying) return
        verifying = true
        error = null
        scope.launch {
            if (verifyPin(current)) onOk() else error = "Incorrect PIN"
            verifying = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (enabled) "Change PIN" else "Set PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (stage == 0) {
                    OutlinedTextField(
                        current,
                        { current = it.filter(Char::isDigit).take(6) },
                        label = { Text("Current PIN") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { verifyCurrent { onClearPin(); onDismiss() } },
                            enabled = current.isNotBlank() && !verifying,
                        ) { Text("Disable") }
                        Button(
                            onClick = { verifyCurrent { stage = 1; error = null } },
                            enabled = current.isNotBlank() && !verifying,
                        ) { Text(if (verifying) "Verifying…" else "Change") }
                    }
                } else {
                    OutlinedTextField(
                        newPin,
                        { newPin = it.filter(Char::isDigit).take(6) },
                        label = { Text("New PIN (4+ digits)") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                    )
                    OutlinedTextField(
                        confirm,
                        { confirm = it.filter(Char::isDigit).take(6) },
                        label = { Text("Confirm PIN") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }
                }
            }
        },
        confirmButton = {
            if (stage == 1) {
                Button(
                    onClick = {
                        when {
                            newPin.length < 4 -> error = "PIN must be at least 4 digits"
                            newPin != confirm -> error = "PINs do not match"
                            else -> { onSetPin(newPin); onDismiss() }
                        }
                    },
                    enabled = newPin.isNotBlank(),
                ) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ForwardDialog(onDismiss: () -> Unit, onConfirm: (ForwardType, Int, String?, Int?) -> Unit) {
    var type by remember { mutableStateOf(ForwardType.LOCAL) }
    var localPort by remember { mutableStateOf("8080") }
    var remoteHost by remember { mutableStateOf("") }
    var remotePort by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add port forward") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForwardType.entries.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) }) }
                }
                OutlinedTextField(localPort, { localPort = it }, label = { Text("Local port") }, singleLine = true)
                when (type) {
                    ForwardType.LOCAL -> {
                        OutlinedTextField(remoteHost, { remoteHost = it }, label = { Text("Remote host") }, singleLine = true)
                        OutlinedTextField(remotePort, { remotePort = it }, label = { Text("Remote port") }, singleLine = true)
                    }
                    ForwardType.REMOTE -> OutlinedTextField(remotePort, { remotePort = it }, label = { Text("Remote bind port") }, singleLine = true)
                    ForwardType.DYNAMIC -> Text("Creates a SOCKS5 proxy on the local port for on-demand tunneling.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(type, localPort.toIntOrNull() ?: return@Button, remoteHost.trim().takeIf(String::isNotBlank), remotePort.toIntOrNull()) },
                enabled = localPort.toIntOrNull() != null,
            ) { Text("Start") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), content = content)
}

/**
 * One settings line: an icon, a title, a subtitle, and a control on the right.
 *
 * The [trailing] slot is width-capped, and that cap is the fix for a layout that broke on narrow
 * screens. A `Row` measures its unweighted children first and gives the weighted one whatever is
 * left, so a trailing control that wanted more than the row had — the terminal-theme picker used to be
 * a horizontally scrolling strip of one chip per theme, which asks for the width of all of them —
 * consumed nearly the whole line and left the title column a few dozen dp. The title then wrapped one
 * word per line, or clipped, and the taller the theme list grew the worse it got. Capped, the labels
 * always keep the rest of the row, and every control the app actually puts here (a `Switch`, a
 * `TextButton`, a compact dropdown) fits inside the cap on any screen this app supports.
 *
 * The title is one line with an ellipsis for the same reason. The subtitle is allowed to wrap: it is
 * prose describing the setting, not a value, and some of them genuinely need two lines.
 */
@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.widthIn(max = SETTING_TRAILING_MAX_WIDTH), contentAlignment = Alignment.CenterEnd) { trailing() }
    }
}

/** How much of a settings row its control may take. The rest belongs to the title and subtitle. */
private val SETTING_TRAILING_MAX_WIDTH = 156.dp

/**
 * A compact single-choice control for a settings row: the current value, a caret, and a menu.
 *
 * Replaces a row of chips, one per option, and it is not only a matter of taste — a chip strip grows
 * with the option list, so adding a theme silently made the Settings screen worse, and a scrolling
 * strip hides the options that do not fit behind a gesture nobody knows is there. A dropdown is a
 * fixed width whatever the list length, shows the current value where a value belongs, and puts every
 * option one tap away with the selected one ticked.
 *
 * The trigger's label is one line with an ellipsis and the button is bounded by
 * [SETTING_TRAILING_MAX_WIDTH] from the row around it, so no option name can push the row out of shape
 * however long it is. Menu items are single-line for the same reason.
 *
 * The content description carries both the setting and its value ("Terminal theme, Amber"), because
 * the visible label alone says only "Amber" — which names the value and not what it sets, and is the
 * one thing a screen reader user cannot recover from the surrounding row.
 */
@Composable
private fun <T> SettingDropdown(
    label: String,
    options: List<T>,
    selected: T?,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = selected?.let(optionLabel) ?: ""
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            shape = RoundedCornerShape(12.dp),
            contentPadding = PaddingValues(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            modifier = Modifier.semantics { contentDescription = "$label, $selectedLabel" },
        ) {
            Text(
                selectedLabel,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(20.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { expanded = false; onSelect(option) },
                    trailingIcon = {
                        if (option == selected) Icon(Icons.Default.Check, "Selected", modifier = Modifier.size(18.dp))
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    message: String,
    action: (() -> Unit)?,
    // Named, because an empty state that offers a button has to say what the button does. It said
    // "Add host" whatever it was wired to, which is wrong the moment anything but the Hosts list has
    // somewhere to send the user.
    actionLabel: String = "Add host",
    actionIcon: ImageVector = Icons.Default.Add,
) {
    Column(Modifier.fillMaxWidth().padding(top = 90.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(68.dp)) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Dashboard, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp)) } }
        Spacer(Modifier.height(18.dp)); Text(title, style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(6.dp)); Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let { Spacer(Modifier.height(18.dp)); Button(onClick = it) { Icon(actionIcon, null); Spacer(Modifier.width(7.dp)); Text(actionLabel) } }
    }
}

@Composable
private fun AuthenticationDialog(
    host: HostProfile,
    onDismiss: () -> Unit,
    onPickKey: () -> Unit,
    selectedKeyName: String?,
    onConnect: (String?, String?) -> Unit,
    onPasteSecret: () -> String?,
) {
    var password by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Authenticate to ${host.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Credentials are used only for this session and are never stored as plaintext.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (optional for SSH key)") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = SecretFieldKeyboard,
                    trailingIcon = { SecretPasteButton("password", onPasteSecret) { password = it } },
                )
                Text("Auth method: ${host.authMethod.label}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                OutlinedButton(onClick = onPickKey) { Icon(Icons.Default.Key, null); Spacer(Modifier.width(8.dp)); Text(selectedKeyName ?: "Choose SSH private key") }
                if (selectedKeyName != null) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Key passphrase (optional)") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = SecretFieldKeyboard,
                        trailingIcon = { SecretPasteButton("passphrase", onPasteSecret) { passphrase = it } },
                    )
                }
            }
        },
        confirmButton = { Button(onClick = { onConnect(password.ifBlank { null }, passphrase) }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The answer to a login the server refused.
 *
 * The failure used to land only on the terminal's status line, in the server's own words
 * ("No more authentication methods available"), with the re-prompt a Reconnect tap away — so the
 * user read "no methods available" and had to *know* it meant "wrong password" before knowing what
 * to do about it. This dialog says which credential was refused and puts the field to fix it on
 * screen: the password again, a different private key, or both — plus the choice to update what is
 * stored on the host so the correction outlives this attempt.
 */
/**
 * The ask-first reconnect question: a session dropped, and the user said never to bring their
 * sessions back without asking. "Reconnect" answers through the same dial the automatic ladder
 * would have made; "Not now" leaves the tab parked at Disconnected, where its own Reconnect action
 * still works whenever they change their mind.
 */
@Composable
private fun ReconnectDialog(prompt: ReconnectPrompt, onReconnect: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onReconnect(false) },
        icon = { Icon(Icons.Default.Refresh, null, tint = MaterialTheme.colorScheme.tertiary) },
        title = { Text("Reconnect to ${prompt.hostName}?") },
        text = { Text(prompt.reason, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        confirmButton = { Button(onClick = { onReconnect(true) }) { Text("Reconnect") } },
        dismissButton = { TextButton(onClick = { onReconnect(false) }) { Text("Not now") } },
    )
}

@Composable
private fun AuthFailureDialog(
    prompt: AuthFailurePrompt,
    host: HostProfile,
    onDismiss: () -> Unit,
    onPickKey: () -> Unit,
    selectedKeyName: String?,
    onEditHost: () -> Unit,
    onRetry: (password: String?, passphrase: String?, save: Boolean) -> Unit,
    onPasteSecret: () -> String?,
) {
    var password by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    // On by default: the credential the user is about to type is the corrected one, and leaving it
    // off would have them retype it at every connect until they open the host form themselves.
    var saveCredentials by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Login to ${prompt.hostName} failed") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(prompt.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${host.username}@${host.host}:${host.port}", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password — type it again") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = SecretFieldKeyboard,
                    trailingIcon = { SecretPasteButton("password", onPasteSecret) { password = it } },
                )
                OutlinedButton(onClick = onPickKey, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Key, null)
                    Spacer(Modifier.width(8.dp))
                    Text(selectedKeyName ?: "Import a different private key")
                }
                if (selectedKeyName != null) {
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Key passphrase (optional)") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = SecretFieldKeyboard,
                        trailingIcon = { SecretPasteButton("passphrase", onPasteSecret) { passphrase = it } },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = saveCredentials, onCheckedChange = { saveCredentials = it })
                    Text(
                        "Save these credentials to the host",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { onRetry(password.ifBlank { null }, passphrase.ifBlank { null }, saveCredentials) }) {
                Text("Retry")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onEditHost) { Text("Edit host") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun HostKeyDialog(challenge: HostKeyChallenge, onAccept: () -> Unit, onReject: () -> Unit) {
    AlertDialog(
        onDismissRequest = onReject,
        icon = { Icon(Icons.Default.Security, null, tint = if (challenge.changed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) },
        title = { Text(if (challenge.changed) "Host key changed" else "Verify host fingerprint") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (challenge.changed) "The server key changed. Do not continue unless you have verified this change out-of-band." else "This server is not in Known Hosts yet. Verify the fingerprint before trusting it.")
                Text("${challenge.host}:${challenge.port}", fontWeight = FontWeight.SemiBold)
                Text(challenge.fingerprint, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = { Button(onClick = onAccept) { Text("Trust and connect") } },
        dismissButton = { TextButton(onClick = onReject) { Text("Reject") } },
    )
}

@Composable
private fun KeyGenDialog(onDismiss: () -> Unit, onConfirm: (SshKeyAlgorithm) -> Unit) {
    var selected by remember { mutableStateOf(SshKeyAlgorithm.RSA_2048) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Generate SSH key pair") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("A private key (PEM) and a public key (OpenSSH format) will be saved as separate files. Keep the private key secret and add the public key to your server's authorized_keys.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SshKeyAlgorithm.entries.forEach { algorithm ->
                        FilterChip(selected = selected == algorithm, onClick = { selected = algorithm }, label = { Text(algorithm.label) })
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(selected) }) { Text("Generate & save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PassphraseDialog(
    title: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onPasteSecret: () -> String?,
) {
    var value by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value,
                { value = it },
                label = { Text("Passphrase") },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = SecretFieldKeyboard,
                trailingIcon = { SecretPasteButton("passphrase", onPasteSecret) { value = it } },
            )
        },
        confirmButton = { Button(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddHostDialog(
    initialHost: HostProfile? = null,
    /** What is already saved for this profile. Metadata only — no secret ever reaches this dialog. */
    storedCredentials: StoredCredentials = StoredCredentials(),
    /** A key file picked during this editing session, held by the caller that owns the launcher. */
    pickedKey: PickedKeyFile? = null,
    onPickKey: () -> Unit = {},
    onForgetPickedKey: () -> Unit = {},
    onDismiss: () -> Unit,
    onSave: (HostProfile, HostCredentialUpdate) -> Unit,
    onPasteSecret: () -> String?,
) {
    val editing = initialHost != null
    var name by remember(initialHost?.id) { mutableStateOf(initialHost?.name.orEmpty()) }
    var host by remember(initialHost?.id) { mutableStateOf(initialHost?.host.orEmpty()) }
    var username by remember(initialHost?.id) { mutableStateOf(initialHost?.username.orEmpty()) }
    var port by remember(initialHost?.id) { mutableStateOf((initialHost?.port ?: 22).toString()) }
    var authMethod by remember(initialHost?.id) { mutableStateOf(initialHost?.authMethod ?: AuthMethod.PASSWORD) }
    var group by remember(initialHost?.id) { mutableStateOf(initialHost?.group ?: "Personal") }
    var tags by remember(initialHost?.id) { mutableStateOf(initialHost?.tags?.joinToString(", ").orEmpty()) }
    var favorite by remember(initialHost?.id) { mutableStateOf(initialHost?.isFavorite ?: false) }
    var proxyType by remember(initialHost?.id) { mutableStateOf(initialHost?.proxyType ?: ProxyType.NONE) }
    var proxyJump by remember(initialHost?.id) { mutableStateOf(initialHost?.proxyJump.orEmpty()) }
    var socksHost by remember(initialHost?.id) { mutableStateOf(initialHost?.socksHost.orEmpty()) }
    var socksPort by remember(initialHost?.id) { mutableStateOf((initialHost?.socksPort ?: 1080).toString()) }
    var socksUsername by remember(initialHost?.id) { mutableStateOf(initialHost?.socksUsername.orEmpty()) }
    var socksPassword by remember(initialHost?.id) { mutableStateOf(initialHost?.socksPassword.orEmpty()) }
    var accentColor by remember(initialHost?.id) { mutableStateOf(initialHost?.accentColor) }
    var timeout by remember(initialHost?.id) { mutableStateOf((initialHost?.connectTimeoutSeconds ?: DEFAULT_CONNECT_TIMEOUT_SECONDS).toString()) }
    // Empty means "follow the global keep-alive", which is what a null column means. Kept as a string
    // so clearing the field is expressible at all — a numeric field cannot represent "unset".
    var keepAlive by remember(initialHost?.id) { mutableStateOf(initialHost?.keepAliveSeconds?.toString().orEmpty()) }
    var fingerprint by remember(initialHost?.id) { mutableStateOf(initialHost?.fingerprint.orEmpty()) }
    // The MAC as typed, blank for none - the same convention the profile column uses.
    var wakeOnLanMac by remember(initialHost?.id) { mutableStateOf(initialHost?.wakeOnLanMac.orEmpty()) }
    // Seeded from the profile, and from the shipped default for a new one, so the box reflects what
    // this host will actually do rather than a hardcoded position.
    var autoLoginSftp by remember(initialHost?.id) {
        mutableStateOf(initialHost?.autoLoginSftp ?: HostProfile.DEFAULT_AUTO_LOGIN_SFTP)
    }
    // The fourteen per-host engine settings, as one value. See [AdvancedHostOptions] for why none of
    // their rules live in this file.
    var advanced by remember(initialHost?.id) { mutableStateOf(AdvancedHostOptions.from(initialHost)) }
    // The credential fields. All four start empty on every open, including when editing: a saved
    // secret is never rendered back into the field it came from, not even masked, because a field
    // that holds it can be read out by an accessibility service, offered to an autofill provider, or
    // simply revealed by the next person holding an unlocked phone. What is stored is reported as
    // "saved" and can be replaced or forgotten, which is all a user needs and nothing an onlooker
    // can use.
    var password by remember(initialHost?.id) { mutableStateOf("") }
    var passphrase by remember(initialHost?.id) { mutableStateOf("") }
    var forgetPassword by remember(initialHost?.id) { mutableStateOf(false) }
    var forgetKey by remember(initialHost?.id) { mutableStateOf(false) }
    // Reading a private key costs a deliberate key derivation — bcrypt-pbkdf, for the OpenSSH
    // format — so it runs off the main thread, and not until the user has stopped typing. `value` is
    // cleared first so the form cannot report a stale verdict for the passphrase now in the field;
    // HostFormDraft.keyReadable treats "picked but not read yet" as not-yet-saveable.
    val probe by produceState<SshKeyProbe?>(null, pickedKey, passphrase) {
        val picked = pickedKey
        value = null
        if (picked == null) return@produceState
        delay(KEY_PROBE_DEBOUNCE_MS)
        value = withContext(Dispatchers.Default) { probeSshKey(picked.bytes, picked.name, passphrase) }
    }
    // Read into a local because smart casts do not see through a delegated property, and the three
    // branches below all need the narrowed type.
    val keyProbe = probe
    // Every validity rule lives in HostFormDraft, which is a plain data class with plain tests.
    // Robolectric cannot idle a Compose dialog window, so rules left inline here would be
    // permanently unverifiable on the JVM.
    val draft = HostFormDraft(
        host = host,
        username = username,
        port = port,
        timeout = timeout,
        keepAlive = keepAlive,
        fingerprint = fingerprint,
        storedFingerprint = initialHost?.fingerprint,
        wakeOnLanMac = wakeOnLanMac,
        proxyType = proxyType,
        proxyJump = proxyJump,
        socksHost = socksHost,
        socksPort = socksPort,
        passphrase = passphrase,
        keyPicked = pickedKey != null,
        pickedKey = keyProbe,
        storedCredentials = storedCredentials,
        forgetKey = forgetKey,
    )
    /**
     * One line describing the key situation, and whether it is a problem. Null when there is nothing
     * to say — no key picked, none saved.
     */
    val keyStatus: Pair<String, Boolean>? = when {
        pickedKey != null && keyProbe == null -> "Reading ${pickedKey.name}…" to false
        keyProbe is SshKeyProbe.Ready -> "${pickedKey?.name.orEmpty()} · ${keyProbe.type}" to false
        keyProbe is SshKeyProbe.PassphraseRequired -> "That key is encrypted — enter its passphrase below" to false
        keyProbe is SshKeyProbe.Unreadable -> keyProbe.reason to true
        forgetKey && storedCredentials.hasKey -> "The saved key will be removed when you save" to false
        storedCredentials.hasKey ->
            listOfNotNull(storedCredentials.keyLabel, storedCredentials.keyType).joinToString(" · ") to false
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editing) "Edit host" else "Add host") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Connection details are stored in the encrypted vault. Credentials are optional — save them for one-tap connects, or leave them blank to be asked each time.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, label = { Text("Profile name") }, singleLine = true)
                OutlinedTextField(host, { host = it }, label = { Text("Hostname or IP") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text("SSH port") }, singleLine = true, isError = draft.showPortError, supportingText = { if (draft.showPortError) Text("${PORT_RANGE.first}–${PORT_RANGE.last}") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.weight(2f))
                }
                Text("Authentication", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AuthMethod.entries.forEach { method ->
                        FilterChip(selected = authMethod == method, onClick = { authMethod = method }, label = { Text(method.label) })
                    }
                }
                // Both a password and a key are offered whatever the method above says, and on purpose:
                // a server can want a key *and* a password, `KEYBOARD_INTERACTIVE` is usually answered
                // with the account password, and hiding a field would take away a combination that
                // works. The method chips say what to try first; these say what the app has to try with.
                Text(
                    "Saved credentials (optional)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "Encrypted with the device keystore and used automatically when you connect. Leave blank to be asked each time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    password,
                    {
                        password = it
                        // Typing a replacement is a clearer statement of intent than the pending
                        // "forget", so it wins rather than fighting it.
                        if (it.isNotEmpty()) forgetPassword = false
                    },
                    label = { Text(if (storedCredentials.hasPassword) "Replace saved password" else "Password") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = SecretFieldKeyboard,
                    modifier = Modifier.fillMaxWidth(),
                    // The paste button sets the state directly, which bypasses the onValueChange
                    // above — so the same "a replacement beats a pending forget" rule is restated
                    // here. Without it, a pasted replacement would lose to a "Forget" the user
                    // ticked before pasting, and the save would drop the password they just fixed.
                    trailingIcon = {
                        SecretPasteButton("password", onPasteSecret) {
                            password = it
                            if (it.isNotEmpty()) forgetPassword = false
                        }
                    },
                )
                if (storedCredentials.hasPassword && password.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (forgetPassword) "The saved password will be removed when you save" else "A password is saved for this host",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { forgetPassword = !forgetPassword }) {
                            Text(if (forgetPassword) "Keep" else "Forget")
                        }
                    }
                }
                OutlinedButton(onClick = onPickKey, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Key, null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            pickedKey != null -> "Choose a different key"
                            storedCredentials.hasKey && !forgetKey -> "Replace private key"
                            else -> "Attach private key"
                        },
                    )
                }
                keyStatus?.let { (message, isProblem) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (pickedKey != null) {
                            // Drops the pick and falls back to whatever was already saved, so
                            // picking the wrong file is not a one-way door.
                            TextButton(onClick = onForgetPickedKey) { Text("Remove") }
                        } else if (storedCredentials.hasKey) {
                            TextButton(onClick = { forgetKey = !forgetKey }) { Text(if (forgetKey) "Keep" else "Forget") }
                        }
                    }
                }
                if (draft.keyAttached || pickedKey != null) {
                    OutlinedTextField(
                        passphrase,
                        { passphrase = it },
                        label = {
                            Text(if (storedCredentials.hasPassphrase) "Replace key passphrase" else "Key passphrase")
                        },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        keyboardOptions = SecretFieldKeyboard,
                        isError = !draft.keyReadable && keyProbe is SshKeyProbe.Unreadable,
                        trailingIcon = { SecretPasteButton("passphrase", onPasteSecret) { passphrase = it } },
                        supportingText = {
                            Text(
                                when {
                                    keyProbe is SshKeyProbe.PassphraseRequired -> "Required to unlock this key"
                                    storedCredentials.hasPassphrase -> "A passphrase is already saved for this key"
                                    else -> "Only if the key is encrypted"
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else if (passphrase.isNotBlank()) {
                    // Unreachable through the fields above (the passphrase field is only shown when a
                    // key is attached), but reachable by attaching a key, typing a passphrase and then
                    // forgetting the key — which would otherwise leave Save disabled with nothing on
                    // screen explaining why.
                    Text(
                        "Attach a private key, or clear the passphrase.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(group, { group = it }, label = { Text("Group") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(tags, { tags = it }, label = { Text("Tags (comma separated)") }, singleLine = true, modifier = Modifier.weight(2f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = favorite, onCheckedChange = { favorite = it })
                    Text("Favorite host")
                }
                Text("Accent color", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = accentColor == null, onClick = { accentColor = null }, label = { Text("Default") })
                    ACCENT_COLORS.forEach { (name, color) ->
                        Surface(
                            // selectable (not clickable) so the swatch reports its checked state:
                            // TalkBack reads "Red, selected" instead of just "Red".
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(50))
                                .selectable(
                                    selected = accentColor == color,
                                    onClick = { accentColor = color },
                                )
                                .semantics { contentDescription = name },
                            color = Color(color),
                        ) {
                            // Decorative: `selectable` above already announces the selected state,
                            // so labelling the tick too would make TalkBack say it twice.
                            if (accentColor == color) Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.CheckCircle, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                        }
                    }
                }
                Text("Connection options", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        timeout,
                        { timeout = it.filter(Char::isDigit).take(3) },
                        label = { Text("Timeout (s)") },
                        singleLine = true,
                        isError = !draft.timeoutValid,
                        supportingText = { Text(if (draft.timeoutValid) "Connect and auth" else "${CONNECT_TIMEOUT_RANGE.first}–${CONNECT_TIMEOUT_RANGE.last}") },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        keepAlive,
                        { keepAlive = it.filter(Char::isDigit).take(3) },
                        label = { Text("Keep-alive (s)") },
                        singleLine = true,
                        isError = !draft.keepAliveValid,
                        supportingText = { Text(if (draft.keepAliveValid) "Blank = use global" else "${KEEP_ALIVE_RANGE.first}–${KEEP_ALIVE_RANGE.last}") },
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    fingerprint,
                    { fingerprint = it.trim() },
                    label = { Text("Pin host key fingerprint (optional)") },
                    placeholder = { Text("SHA256:…") },
                    singleLine = true,
                    isError = !draft.fingerprintValid,
                    supportingText = {
                        Text(
                            if (!draft.fingerprintValid) "Expected SHA256:<base64>"
                            // Pinning is strictly stronger than the trust-on-first-use prompt: paste the
                            // fingerprint from a channel you already trust and the very first connection
                            // is verified instead of asking the user to accept an unseen key.
                            else "Verifies the first connection instead of prompting",
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    wakeOnLanMac,
                    { wakeOnLanMac = it },
                    label = { Text("Wake-on-LAN MAC (optional)") },
                    placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                    singleLine = true,
                    isError = !draft.wakeOnLanMacValid,
                    supportingText = {
                        Text(
                            if (!draft.wakeOnLanMacValid) "Six pairs of hex digits — AA:BB:CC:DD:EE:FF"
                            // The same-LAN limit is stated here, in the field's own helper line, rather
                            // than left to a failure to explain: a wake sent from another network stops
                            // at the first router and nothing on screen would say why.
                            else "Wakes the machine from the host menu — phone and machine must be on the same network",
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                // A switch rather than a chip row: it is one binary choice whose off state has to be
                // as visible as its on state. The whole row is the target — `toggleable` puts the
                // label, the explanation and the switch in a single accessible node, so TalkBack
                // reads "Auto Login SFTP, on" once instead of announcing an unlabelled switch, and a
                // thumb lands on it anywhere along the line.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = autoLoginSftp,
                            role = Role.Switch,
                            onValueChange = { autoLoginSftp = it },
                        ),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto Login SFTP")
                        // Worded as what happens on connect, not as a protocol name, and switched on
                        // the current value so the consequence is readable without toggling it first.
                        Text(
                            if (autoLoginSftp) "Signs in to the file browser as soon as the shell connects"
                            else "Connects the shell only — the Files tab opens on demand",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    // Null handler: the row above owns the input, and a second clickable node here
                    // would swallow taps on the switch itself and be announced twice.
                    Switch(checked = autoLoginSftp, onCheckedChange = null)
                }
                Text("Connection route", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProxyType.entries.forEach { type ->
                        FilterChip(selected = proxyType == type, onClick = { proxyType = type }, label = { Text(type.label) })
                    }
                }
                when (proxyType) {
                    ProxyType.PROXY_JUMP -> OutlinedTextField(proxyJump, { proxyJump = it }, label = { Text("Jump host (user@host:port)") }, placeholder = { Text("gateway@bastion.example.com:22") }, singleLine = true)
                    ProxyType.SOCKS5, ProxyType.HTTP_CONNECT -> {
                        OutlinedTextField(socksHost, { socksHost = it }, label = { Text(if (proxyType == ProxyType.HTTP_CONNECT) "HTTP proxy host" else "SOCKS5 host") }, singleLine = true)
                        OutlinedTextField(socksPort, { socksPort = it.filter(Char::isDigit).take(5) }, label = { Text(if (proxyType == ProxyType.HTTP_CONNECT) "HTTP proxy port" else "SOCKS5 port") }, singleLine = true)
                        OutlinedTextField(socksUsername, { socksUsername = it }, label = { Text("Proxy username (optional)") }, singleLine = true)
                        OutlinedTextField(socksPassword, { socksPassword = it }, label = { Text("Proxy password (optional)") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), keyboardOptions = SecretFieldKeyboard, trailingIcon = { SecretPasteButton("proxy password", onPasteSecret) { socksPassword = it } })
                    }
                    ProxyType.NONE -> Unit
                }
                AdvancedHostSection(advanced, onChange = { advanced = it })
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        HostProfile(
                            id = initialHost?.id ?: java.util.UUID.randomUUID().toString(),
                            name = name.trim().ifBlank { host.trim() },
                            host = host.trim(),
                            username = username.trim(),
                            port = draft.portNumber ?: DEFAULT_SSH_PORT,
                            authMethod = authMethod,
                            group = group.trim().ifBlank { "Personal" },
                            tags = tags.split(',').map(String::trim).filter(String::isNotBlank).distinct(),
                            isFavorite = favorite,
                            lastConnectedAt = initialHost?.lastConnectedAt,
                            fingerprint = fingerprint.trim().takeIf(String::isNotBlank) ?: initialHost?.fingerprint,
                            proxyType = proxyType,
                            proxyJump = proxyJump.trim().takeIf(String::isNotBlank),
                            socksHost = socksHost.trim().takeIf(String::isNotBlank),
                            socksPort = draft.socksPortNumber ?: DEFAULT_SOCKS_PORT,
                            socksUsername = socksUsername.trim().takeIf(String::isNotBlank),
                            socksPassword = socksPassword.takeIf(String::isNotBlank),
                            accentColor = accentColor,
                            connectTimeoutSeconds = draft.timeoutNumber ?: DEFAULT_CONNECT_TIMEOUT_SECONDS,
                            keepAliveSeconds = draft.keepAliveNumber,
                            autoLoginSftp = autoLoginSftp,
                            // Trimmed rather than normalised to one spelling: the text as typed is
                            // what the profile shows the next time the form opens, and parseMac takes
                            // every spelling at the moment the address is used.
                            wakeOnLanMac = wakeOnLanMac.trim(),
                        ).let(advanced::applyTo),
                        HostCredentialUpdate(
                            // A typed replacement beats a pending forget; a pending forget beats
                            // leaving it alone. Anything else leaves what is stored untouched, which
                            // is what an untouched empty field has to mean — see the fields above.
                            password = when {
                                password.isNotEmpty() -> SecretEdit.Replace(password)
                                forgetPassword -> SecretEdit.Forget
                                else -> SecretEdit.Keep
                            },
                            // `draft.canSave` is false unless a picked key reached
                            // SshKeyProbe.Ready, so this never stores a key the app could not read.
                            key = when {
                                pickedKey != null && keyProbe is SshKeyProbe.Ready ->
                                    KeyEdit.Replace(pickedKey.bytes, pickedKey.name, keyProbe.type)
                                forgetKey -> KeyEdit.Forget
                                else -> KeyEdit.Keep
                            },
                            // Forgetting the key drops its passphrase in the same write, so this only
                            // has to handle a passphrase changing on a key that stays.
                            passphrase = when {
                                passphrase.isNotEmpty() -> SecretEdit.Replace(passphrase)
                                forgetKey -> SecretEdit.Forget
                                else -> SecretEdit.Keep
                            },
                        ),
                    )
                },
                // Both halves of the form gate the button: the identity fields through
                // [HostFormDraft], the engine settings through [AdvancedHostOptions]. A collapsed
                // section can still hold an out-of-range number typed before it was closed.
                enabled = draft.canSave && advanced.isValid,
            ) { Text(if (editing) "Save changes" else "Save securely") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostDetailsSheet(
    host: HostProfile,
    stats: ServerStats?,
    credentials: StoredCredentials,
    onDismiss: () -> Unit,
    onForgetCredentials: () -> Unit,
    onRefreshStats: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 22.dp).navigationBarsPadding().padding(bottom = 18.dp)) {
            Text(host.name, style = MaterialTheme.typography.headlineSmall); Text("${host.username}@${host.host}:${host.port}", color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(18.dp))
            DetailLine("Authentication", host.authMethod.label)
            DetailLine("Group", host.group)
            DetailLine("Route", host.proxyType.label)
            if (host.proxyType == ProxyType.PROXY_JUMP) DetailLine("Jump host", host.proxyJump ?: "—")
            if (host.proxyType == ProxyType.SOCKS5 || host.proxyType == ProxyType.HTTP_CONNECT) {
                DetailLine(if (host.proxyType == ProxyType.HTTP_CONNECT) "HTTP proxy" else "SOCKS5", "${host.socksHost}:${host.socksPort}")
                if (!host.socksUsername.isNullOrBlank()) DetailLine("Proxy auth", host.socksUsername)
            }
            if (host.accentColor != null) DetailLine("Accent", "Custom")
            DetailLine("Fingerprint", host.fingerprint ?: "Not verified yet")
            DetailLine("Reconnect", "Automatic on network recovery")
            // Shown for every host, including those with nothing saved: "Asked at every connect" is
            // the answer to the question this line exists to answer, and leaving the row out when the
            // answer is "nothing" makes its absence indistinguishable from the app not tracking it.
            DetailLine("Credentials", credentials.describe())
            // Only what the card's own menu does not already offer. Connect, Details, Port
            // forwarding, Favorite, Export account, Edit and Remove are all one tap away on every
            // row through the kebab, so repeating them here meant two paths to each act. What is
            // left is the one action only this sheet can do: dropping the saved secrets, which
            // lives here because it belongs with the Credentials line above it rather than in a menu
            // the user opens for other reasons.
            if (!credentials.isEmpty) {
                OutlinedButton(onClick = onForgetCredentials) { Text("Forget credentials") }
            }
            Spacer(Modifier.height(18.dp))
            Text("Monitoring".uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.2.sp)
            if (stats == null) {
                TextButton(onClick = onRefreshStats) { Text("Load server stats") }
            } else {
                DetailLine("Hostname", stats.hostname)
                DetailLine("Load average", stats.loadAverage)
                DetailLine("Memory", "${stats.memoryUsed} / ${stats.memoryTotal}")
                DetailLine("Disk /", "${stats.diskUsed} / ${stats.diskTotal}")
                TextButton(onClick = onRefreshStats) { Text("Refresh stats") }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) { Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) { Text(label, Modifier.width(115.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium); Text(value, Modifier.weight(1f), fontWeight = FontWeight.SemiBold) } }
