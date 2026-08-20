package dev.eclipse.ssh

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ArrowDownward
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import dev.eclipse.ssh.data.model.HostKeyChallenge
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.matchesQuery
import dev.eclipse.ssh.data.model.ProxyType
import dev.eclipse.ssh.data.model.ServerStats
import dev.eclipse.ssh.data.model.SessionConnectionState
import dev.eclipse.ssh.data.model.Snippet
import dev.eclipse.ssh.data.model.SessionTab
import dev.eclipse.ssh.data.model.SftpSessionState
import dev.eclipse.ssh.data.model.TransferDirection
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.data.model.TransferItem
import dev.eclipse.ssh.data.model.TransferStatus
import dev.eclipse.ssh.data.model.TerminalTheme
import dev.eclipse.ssh.data.model.SyncDirection
import dev.eclipse.ssh.background.EclipseSessionService
import dev.eclipse.ssh.presentation.HostFormDraft
import dev.eclipse.ssh.presentation.MAX_LISTED_ENTRIES
import dev.eclipse.ssh.presentation.transfersForDisplay
import dev.eclipse.ssh.presentation.MainUiState
import dev.eclipse.ssh.presentation.MainViewModel
import dev.eclipse.ssh.ui.EclipseSuccess
import dev.eclipse.ssh.ui.EclipseTheme
import dev.eclipse.ssh.ui.EclipseWarning
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
import dev.eclipse.ssh.ssh.fallbackHome
import dev.eclipse.ssh.data.saf.LocalFile
import androidx.compose.ui.focus.FocusRequester
import dev.eclipse.ssh.terminal.TerminalFrame
import dev.eclipse.ssh.terminal.TerminalKey
import dev.eclipse.ssh.terminal.TerminalSelection
import dev.eclipse.ssh.ui.terminal.TerminalInputBridge
import dev.eclipse.ssh.ui.terminal.TerminalKeyRow
import dev.eclipse.ssh.ui.terminal.TerminalView
import dev.eclipse.ssh.ui.terminal.rememberTerminalCellMetrics
import dev.eclipse.ssh.ui.terminal.rememberTerminalLatches
import dev.eclipse.ssh.terminal.TerminalExportRenderer
import dev.eclipse.ssh.security.BiometricUnlocker
import dev.eclipse.ssh.security.SecureClipboard
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

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate so the splash theme can hand over to
        // Theme.EclipseSSH via postSplashScreenTheme; the manifest declares the splash
        // theme, so skipping this left the splash background as the app's window theme.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLink.value = parseDeepLink(intent?.data)
        noteRestoreRequest(intent)
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
    var openSessionHostId by rememberSaveable { mutableStateOf<String?>(null) }
    /**
     * Follows the sessions: a new one takes the screen, and the one being watched gives it back when it
     * ends.
     *
     * Seeded from the sessions that already exist on the first composition rather than starting empty,
     * so returning to a process that still has sessions - a rotation, a restore after process death,
     * coming back from the background - does not count them as new and throw the user into a shell they
     * did not just ask for. Connect sets [openSessionHostId] itself so the tap feels immediate; this is
     * what gives every other route to a new session (the widget, a deep link, a reconnect from the list)
     * the same behaviour.
     *
     * Ids are dropped as they leave, not remembered forever: closing a session and connecting to the
     * same host again is a new session, and it should open like one.
     */
    val seenSessions = remember { mutableSetOf<String>() }
    var sessionsSeeded by remember { mutableStateOf(false) }
    LaunchedEffect(state.tabs) {
        val live = state.tabs.map { it.hostId }
        val appeared = if (sessionsSeeded) live.lastOrNull { it !in seenSessions } else null
        sessionsSeeded = true
        seenSessions.retainAll(live.toSet())
        seenSessions += live
        if (appeared != null) {
            openSessionHostId = appeared
            destination = Destination.TERMINAL
        } else if (openSessionHostId != null && openSessionHostId !in live) {
            // The session being watched ended - closed from the strip, disconnected by the server, or
            // never restored into this process - so the terminal falls back to the list instead of
            // drawing a shell for a session that is not there.
            openSessionHostId = null
        }
    }
    LaunchedEffect(destination, state.selectedHostId) {
        // announce = false: this fires because the user navigated here, not because they asked to
        // talk to the server, and on a clean install no host is connected. See refreshFiles.
        if (destination == Destination.FILES) activeHost?.let { viewModel.refreshFiles(it, announce = false) }
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
        val host = activeHost
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
        val host = activeHost
        if (uris.isNotEmpty() && host != null) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val directory = state.remotePath ?: viewModel.remoteDirectory(host)
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
        }
    }
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
            ContextCompat.startForegroundService(context, Intent(context, EclipseSessionService::class.java))
        }.onFailure { viewModel.reportUiFailure("Sessions will not survive minimising", it) }
        // Straight into the shell, full screen: the list of sessions is somewhere to come back to, not
        // somewhere to pass through on the way in.
        openSessionHostId = host.id
        destination = Destination.TERMINAL
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
    var pendingDeleteHost by remember { mutableStateOf<HostProfile?>(null) }
    var showAuthHost by remember { mutableStateOf<HostProfile?>(deepLinkHost) }
    // A deep link arriving while the app is already running updates deepLinkHost, so open
    // the auth prompt for it and let the activity clear the pending value.
    LaunchedEffect(deepLinkHost) {
        deepLinkHost?.let {
            showAuthHost = it
            onDeepLinkConsumed()
        }
    }
    var showGlobalSearch by remember { mutableStateOf(false) }
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
        openSessionHostId?.let { id -> state.tabs.any { it.hostId == id } } == true
    // Back leaves the shell, not the app. A full-screen terminal has no navigation on screen, so
    // without this the only way out of a session is the gesture that closes the whole app - and the
    // session with it.
    BackHandler(enabled = terminalImmersive) { openSessionHostId = null }
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
                    onDestination = { destination = it },
                    onSearch = viewModel::setQuery,
                    onAddHost = { showAddHost = true },
                    onConnect = { host -> showAuthHost = host },
                    onSelectHost = viewModel::selectHost,
                    onShowDetails = { showHostDetails = it },
                    onEditHost = { showEditHost = it },
                    onRemoveHost = { pendingDeleteHost = it },
                    onCloseTab = viewModel::closeTab,
                    onDisconnectAll = viewModel::disconnectAll,
                    openSessionHostId = openSessionHostId,
                    onOpenSession = { openSessionHostId = it },
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
                    onRefreshFiles = { activeHost?.let(viewModel::refreshFiles) },
                    onUpload = { pickerActive = true; uploadPicker.launch(arrayOf("*/*")) },
                    onDownloadFile = { remote ->
                        if (state.localDirUri != null) activeHost?.let { viewModel.downloadToLocal(it, remote) }
                        else { pendingDownload = remote; pickerActive = true; downloadPicker.launch(remote.name) }
                    },
                    onNavigateRemote = { path -> activeHost?.let { viewModel.navigateRemote(it, path) } },
                    onCreateFolder = { name -> activeHost?.let { viewModel.createDirectory(it, name) } },
                    onDeleteFile = { file -> activeHost?.let { viewModel.deleteRemote(it, file) } },
                    onDeleteFiles = { files -> activeHost?.let { viewModel.deleteRemoteFiles(it, files) } },
                    onCopyFiles = { files, path -> activeHost?.let { viewModel.copyRemoteFiles(it, files, path) } },
                    onMoveFiles = { files, path -> activeHost?.let { viewModel.moveRemoteFiles(it, files, path) } },
                    onRenameFile = { file, name -> activeHost?.let { viewModel.renameRemote(it, file, name) } },
                    onChmodFile = { file, mode -> activeHost?.let { viewModel.chmodRemote(it, file, mode) } },
                    onCopyFile = { file, path -> activeHost?.let { viewModel.copyRemote(it, file, path) } },
                    onMoveFile = { file, path -> activeHost?.let { viewModel.moveRemote(it, file, path) } },
                    onPickLocalFolder = { pickerActive = true; localFolderPicker.launch(null) },
                    onNavigateLocal = viewModel::navigateLocal,
                    onNavigateLocalUp = viewModel::navigateLocalUp,
                    onUploadLocal = { file -> activeHost?.let { viewModel.uploadLocal(it, file) } },
                    onScheduleDownload = { files, scheduledAt, repeatMinutes -> activeHost?.let { host -> files.forEach { viewModel.scheduleDownload(host, it, scheduledAt, repeatMinutes) } } },
                    onScheduleUpload = { files, scheduledAt, repeatMinutes -> activeHost?.let { host -> files.forEach { viewModel.scheduleUpload(host, it, scheduledAt, repeatMinutes) } } },
                    onSync = { direction -> activeHost?.let { host -> if (direction == SyncDirection.LOCAL_TO_REMOTE) viewModel.syncToRemote(host) else viewModel.syncFromRemote(host) } },
                    onSendToHost = { file, destHost, destPath -> activeHost?.let { viewModel.sendRemoteTo(it, file, destHost, destPath) } },
                    onPauseTransfer = viewModel::pauseTransfer,
                    onResumeTransfer = viewModel::resumeTransfer,
                    onCancelTransfer = viewModel::cancelTransfer,
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
                    onGlobalSearch = { showGlobalSearch = true },
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
                    onLegacyAlgorithms = viewModel::setLegacyAlgorithms,
                    onBlockScreenshots = viewModel::setBlockScreenshots,
                    onTerminalTheme = viewModel::setTerminalTheme,
                    onSetPin = viewModel::setPin,
                    onClearPin = viewModel::clearPin,
                    verifyPin = viewModel::verifyPin,
                    onForgetKnownHost = viewModel::forgetKnownHost,
                    onClearKnownHosts = viewModel::clearKnownHosts,
                    onForgetAllCredentials = viewModel::forgetAllCredentials,
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
                    onDestination = { destination = it },
                    onSearch = viewModel::setQuery,
                    onAddHost = { showAddHost = true },
                    onConnect = { host -> showAuthHost = host },
                    onSelectHost = viewModel::selectHost,
                    onShowDetails = { showHostDetails = it },
                    onEditHost = { showEditHost = it },
                    onRemoveHost = { pendingDeleteHost = it },
                    onCloseTab = viewModel::closeTab,
                    onDisconnectAll = viewModel::disconnectAll,
                    openSessionHostId = openSessionHostId,
                    onOpenSession = { openSessionHostId = it },
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
                    onRefreshFiles = { activeHost?.let(viewModel::refreshFiles) },
                    onUpload = { pickerActive = true; uploadPicker.launch(arrayOf("*/*")) },
                    onDownloadFile = { remote ->
                        if (state.localDirUri != null) activeHost?.let { viewModel.downloadToLocal(it, remote) }
                        else { pendingDownload = remote; pickerActive = true; downloadPicker.launch(remote.name) }
                    },
                    onNavigateRemote = { path -> activeHost?.let { viewModel.navigateRemote(it, path) } },
                    onCreateFolder = { name -> activeHost?.let { viewModel.createDirectory(it, name) } },
                    onDeleteFile = { file -> activeHost?.let { viewModel.deleteRemote(it, file) } },
                    onDeleteFiles = { files -> activeHost?.let { viewModel.deleteRemoteFiles(it, files) } },
                    onCopyFiles = { files, path -> activeHost?.let { viewModel.copyRemoteFiles(it, files, path) } },
                    onMoveFiles = { files, path -> activeHost?.let { viewModel.moveRemoteFiles(it, files, path) } },
                    onRenameFile = { file, name -> activeHost?.let { viewModel.renameRemote(it, file, name) } },
                    onChmodFile = { file, mode -> activeHost?.let { viewModel.chmodRemote(it, file, mode) } },
                    onCopyFile = { file, path -> activeHost?.let { viewModel.copyRemote(it, file, path) } },
                    onMoveFile = { file, path -> activeHost?.let { viewModel.moveRemote(it, file, path) } },
                    onPickLocalFolder = { pickerActive = true; localFolderPicker.launch(null) },
                    onNavigateLocal = viewModel::navigateLocal,
                    onNavigateLocalUp = viewModel::navigateLocalUp,
                    onUploadLocal = { file -> activeHost?.let { viewModel.uploadLocal(it, file) } },
                    onScheduleDownload = { files, scheduledAt, repeatMinutes -> activeHost?.let { host -> files.forEach { viewModel.scheduleDownload(host, it, scheduledAt, repeatMinutes) } } },
                    onScheduleUpload = { files, scheduledAt, repeatMinutes -> activeHost?.let { host -> files.forEach { viewModel.scheduleUpload(host, it, scheduledAt, repeatMinutes) } } },
                    onSync = { direction -> activeHost?.let { host -> if (direction == SyncDirection.LOCAL_TO_REMOTE) viewModel.syncToRemote(host) else viewModel.syncFromRemote(host) } },
                    onSendToHost = { file, destHost, destPath -> activeHost?.let { viewModel.sendRemoteTo(it, file, destHost, destPath) } },
                    onPauseTransfer = viewModel::pauseTransfer,
                    onResumeTransfer = viewModel::resumeTransfer,
                    onCancelTransfer = viewModel::cancelTransfer,
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
                    onGlobalSearch = { showGlobalSearch = true },
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
                    onLegacyAlgorithms = viewModel::setLegacyAlgorithms,
                    onBlockScreenshots = viewModel::setBlockScreenshots,
                    onTerminalTheme = viewModel::setTerminalTheme,
                    onSetPin = viewModel::setPin,
                    onClearPin = viewModel::clearPin,
                    verifyPin = viewModel::verifyPin,
                    onForgetKnownHost = viewModel::forgetKnownHost,
                    onClearKnownHosts = viewModel::clearKnownHosts,
                    onForgetAllCredentials = viewModel::forgetAllCredentials,
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
        )
    }
    showHostDetails?.let { host ->
        HostDetailsSheet(
            host = host,
            stats = state.serverStats[host.id],
            credentials = state.savedCredentials[host.id] ?: StoredCredentials(),
            onDismiss = { showHostDetails = null },
            onConnect = {
                showHostDetails = null
                showAuthHost = host
            },
            onEdit = {
                showHostDetails = null
                showEditHost = host
            },
            onDelete = {
                showHostDetails = null
                pendingDeleteHost = host
            },
            onToggleFavorite = { viewModel.saveHost(host.copy(isFavorite = !host.isFavorite)) },
            onExportAccount = {
                showHostDetails = null
                pendingAccountExportHost = host
                showAccountExportDialog = true
            },
            onForgetCredentials = { viewModel.forgetCredentials(host) },
            onRefreshStats = { viewModel.refreshStats(host) },
        )
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
        )
    }
    state.hostKeyChallenge?.let { challenge ->
        HostKeyDialog(
            challenge = challenge,
            onAccept = viewModel::acceptHostKey,
            onReject = viewModel::rejectHostKey,
        )
    }
    if (showGlobalSearch) {
        GlobalSearchDialog(
            state = state,
            onDismiss = { showGlobalSearch = false },
            onSelectHost = { host -> viewModel.selectHost(host); destination = Destination.HOSTS; showGlobalSearch = false },
            onCopySnippet = { command -> viewModel.copyToClipboard(command); showGlobalSearch = false },
        )
    }
    if (showExportDialog) {
        PassphraseDialog(
            title = "Export encrypted backup",
            confirmLabel = "Export",
            onDismiss = { showExportDialog = false },
            onConfirm = { pass -> showExportDialog = false; pendingExportPassphrase = pass; pickerActive = true; exportPicker.launch("eclipse-backup.enc") },
        )
    }
    if (showImportDialog) {
        PassphraseDialog(
            title = "Import backup",
            confirmLabel = "Import",
            onDismiss = { showImportDialog = false; pendingImportUri = null },
            onConfirm = { pass -> showImportDialog = false; pendingImportUri?.let { viewModel.importVault(pass, it) }; pendingImportUri = null },
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
    onDestination: (Destination) -> Unit,
    onSearch: (String) -> Unit,
    onAddHost: () -> Unit,
    onConnect: (HostProfile) -> Unit,
    onSelectHost: (HostProfile) -> Unit,
    onShowDetails: (HostProfile) -> Unit,
    onEditHost: (HostProfile) -> Unit,
    onRemoveHost: (HostProfile) -> Unit,
    onCloseTab: (SessionTab) -> Unit,
    onDisconnectAll: () -> Unit,
    /** The session whose shell is on screen; null shows the list of sessions instead. */
    openSessionHostId: String?,
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
    onPasteTerminal: (String) -> Unit = {},
    onSaveSnippet: (String, String) -> Unit = { _, _ -> },
    onDeleteSnippet: (String) -> Unit = {},
    onSaveLogs: (String, String) -> Unit = { _, _ -> },
    onSaveText: (String, String) -> Unit = { _, _ -> },
    onSaveScreen: (String, String) -> Unit = { _, _ -> },
    onClearCompleted: () -> Unit = {},
    onRefreshFiles: () -> Unit = {},
    onUpload: () -> Unit = {},
    onDownloadFile: (RemoteFile) -> Unit = {},
    onNavigateRemote: (String) -> Unit = {},
    onCreateFolder: (String) -> Unit = {},
    onDeleteFile: (RemoteFile) -> Unit = {},
    onDeleteFiles: (List<RemoteFile>) -> Unit = {},
    onRenameFile: (RemoteFile, String) -> Unit = { _, _ -> },
    onChmodFile: (RemoteFile, Int) -> Unit = { _, _ -> },
    onCopyFile: (RemoteFile, String) -> Unit = { _, _ -> },
    onCopyFiles: (List<RemoteFile>, String) -> Unit = { _, _ -> },
    onMoveFile: (RemoteFile, String) -> Unit = { _, _ -> },
    onMoveFiles: (List<RemoteFile>, String) -> Unit = { _, _ -> },
    onPickLocalFolder: () -> Unit = {},
    onNavigateLocal: (Uri) -> Unit = {},
    onNavigateLocalUp: () -> Unit = {},
    onUploadLocal: (LocalFile) -> Unit = {},
    onScheduleDownload: (List<RemoteFile>, Long, Long?) -> Unit = { _, _, _ -> },
    onScheduleUpload: (List<LocalFile>, Long, Long?) -> Unit = { _, _, _ -> },
    onSync: (SyncDirection) -> Unit = {},
    onSendToHost: (RemoteFile, HostProfile, String) -> Unit = { _, _, _ -> },
    onPauseTransfer: (String) -> Unit = {},
    onCancelTransfer: (String) -> Unit = {},
    onResumeTransfer: (String) -> Unit = {},
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
    onLegacyAlgorithms: (Boolean) -> Unit = {},
    onBlockScreenshots: (Boolean) -> Unit = {},
    onTerminalTheme: (String) -> Unit = {},
    onSetPin: (String) -> Unit = {},
    onClearPin: () -> Unit = {},
    verifyPin: suspend (String) -> Boolean = { false },
    onGlobalSearch: () -> Unit = {},
    onForgetKnownHost: (String) -> Unit = {},
    onClearKnownHosts: () -> Unit = {},
    onForgetAllCredentials: () -> Unit = {},
) {
    // A shell owns the whole window, so it composes outside the Scaffold entirely: no top bar, no
    // Scaffold insets, nothing above the grid but the session strip. This is the branch the app enters
    // the moment a login succeeds, and what makes the terminal full screen rather than merely large.
    val openSession = state.tabs.firstOrNull { it.hostId == openSessionHostId }
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
            onPaste = onPasteTerminal,
            onSaveSnippet = onSaveSnippet,
            onDeleteSnippet = onDeleteSnippet,
            onSaveLogs = onSaveLogs,
            onSaveText = onSaveText,
            onSaveScreen = onSaveScreen,
            fontSize = state.settings.terminalFontSize,
            activeTab = openSession,
            onSelectSession = { onOpenSession(it.hostId) },
            onLeaveSession = { onOpenSession(null) },
            modifier = modifier,
        )
        return
    }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(destination.label)
                        if (destination == Destination.HOSTS) Text("Your secure workspace", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (destination == Destination.HOSTS) {
                        IconButton(onClick = onImportAccount) { Icon(Icons.Default.CloudDownload, "Import account") }
                        IconButton(onClick = onAddHost) { Icon(Icons.Default.Add, "Add host") }
                    }
                    IconButton(onClick = onGlobalSearch) { Icon(Icons.Default.Search, "Global search") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
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
                    onOpenSession = { onOpenSession(it.hostId) },
                    onCloseTab = onCloseTab,
                    onDisconnectAll = onDisconnectAll,
                    // The same authentication sheet the Hosts list opens, deliberately: a reconnect is
                    // a connection, and it should ask for whatever a connection asks for.
                    onReconnect = { hostId -> state.hosts.firstOrNull { it.id == hostId }?.let(onConnect) },
                    onGoToHosts = { onDestination(Destination.HOSTS) },
                )
            }
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().widthIn(max = 1280.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            when (destination) {
                Destination.HOSTS -> HostsScreen(state, onSearch, onAddHost, onConnect, onSelectHost, onShowDetails, onEditHost, onRemoveHost)
                // Handled above, outside the scrolling column.
                Destination.TERMINAL -> Unit
                Destination.FILES -> FilesScreen(state, onRefreshFiles, onUpload, onDownloadFile, onNavigateRemote, onCreateFolder, onDeleteFile, onDeleteFiles, onRenameFile, onChmodFile, onCopyFile, onCopyFiles, onMoveFile, onMoveFiles, onPickLocalFolder, onNavigateLocal, onNavigateLocalUp, onUploadLocal, onScheduleDownload, onScheduleUpload, onSync, onSendToHost)
                Destination.TRANSFERS -> TransfersScreen(state.transfers, onClearCompleted, onPauseTransfer, onResumeTransfer, onCancelTransfer)
                Destination.SETTINGS -> SettingsScreen(
                    state, onBiometric, onDarkTheme, onAddForward, onStopForward, onExportVault,
                    onImportVault, onKeepAlive, onClipboard, onTerminalFontSize,
                    onReconnectBase = onReconnectBase,
                    onLegacyAlgorithms = onLegacyAlgorithms,
                    onBlockScreenshots = onBlockScreenshots,
                    onTerminalTheme = onTerminalTheme,
                    onSetPin = onSetPin,
                    onClearPin = onClearPin,
                    verifyPin = verifyPin,
                    onForgetKnownHost = onForgetKnownHost,
                    onClearKnownHosts = onClearKnownHosts,
                    onGenerateKey = onGenerateKey,
                    onImportSshConfig = onImportSshConfig,
                    onForgetAllCredentials = onForgetAllCredentials,
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
    onSelectHost: (HostProfile) -> Unit,
    onShowDetails: (HostProfile) -> Unit,
    onEditHost: (HostProfile) -> Unit,
    onRemoveHost: (HostProfile) -> Unit,
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
                HostCard(host, onConnect, onSelectHost, onShowDetails, onEditHost, onRemoveHost)
            }
        }
    }
}

/**
 * One saved host: who it is, how it authenticates, and a single overflow menu holding everything it
 * can do.
 *
 * There is deliberately no primary button on the card any more. A full-width Connect under every host
 * was the largest control on the screen repeated once per row — it added some 60dp to each card, so a
 * phone showed three hosts where it now shows six, and it spent all that emphasis on one of three
 * equally ordinary actions while Edit and Remove sat two taps deep inside the details sheet. Collapsing
 * the three into a kebab menu is what makes every row the same shape whatever the host, which is the
 * property a list needs and a per-row button cannot have.
 *
 * Both trailing controls carry the host's name in their content description. With one card per host,
 * "More actions" alone is ambiguous to a screen reader and to a test: it names the control but not the
 * row it belongs to, and there are as many of them as there are hosts.
 */
@Composable
private fun HostCard(
    host: HostProfile,
    onConnect: (HostProfile) -> Unit,
    onSelect: (HostProfile) -> Unit,
    onDetails: (HostProfile) -> Unit,
    onEdit: (HostProfile) -> Unit,
    onRemove: (HostProfile) -> Unit,
) {
    // Keyed on the host id so a list that reorders (a favourite toggled, a search narrowed) cannot
    // leave the menu open over a different host than the one it was opened on.
    var menuOpen by remember(host.id) { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onSelect(host) },
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
                IconButton(onClick = { onDetails(host) }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Open ${host.name}") }
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "More actions for ${host.name}") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Connect") },
                            leadingIcon = { Icon(Icons.Default.Wifi, null) },
                            onClick = { menuOpen = false; onConnect(host) },
                        )
                        DropdownMenuItem(
                            text = { Text("Edit") },
                            leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menuOpen = false; onEdit(host) },
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
     * [EclipseWorkspace]'s `openSessionHostId`. It also means this screen has no empty state: it is
     * only ever composed for a session that exists, and the list is what handles having none.
     */
    activeTab: SessionTab,
    onSelectSession: (SessionTab) -> Unit,
    onLeaveSession: () -> Unit,
    modifier: Modifier = Modifier,
    fontSize: Int = 13,
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
    // highlight an unrelated stretch of the other host's scrollback.
    LaunchedEffect(activeTab.hostId) { selection = null }
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
    val terminalText = state.terminalOutput[activeTab.hostId].orEmpty()
    // Collected here, at the one composable that draws it, and with the lifecycle: the collection ends
    // when this screen leaves composition or the app stops, which is the signal the view model uses to
    // stop producing frames nobody can see.
    val currentFrames by frames.collectAsStateWithLifecycle()
    val frame = currentFrames[activeTab.hostId] ?: TerminalFrame.EMPTY
    val latches = rememberTerminalLatches()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val textStyle = remember(fontSize) { TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp) }
    val metrics = rememberTerminalCellMetrics(textStyle)
    val showKeyboard = {
        focusRequester.requestFocus()
        keyboard?.show()
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
            onPaste = { onPaste(activeTab.hostId) },
            onScrollToBottom = { onScrollTo(activeTab.hostId, 0) },
            searchQuery = searchQuery,
            onSearchQuery = { searchQuery = it },
            showSearch = showSearch,
            showCommandBar = showCommandBar,
            showHistory = showHistory,
            terminalText = terminalText,
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
                        fontSize = fontSize.sp,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                    )
                }
            } else {
                TerminalView(
                    frame = frame,
                    style = textStyle,
                    background = termBg,
                    foreground = termFg,
                    metrics = metrics,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
                    selection = selection,
                    onSelectionChange = { selection = it },
                    onSelectionFinished = { finished ->
                        // A drag that never left its cell is a tap that Compose reported as a drag;
                        // treating it as a selection would copy one character and swallow the tap.
                        if (finished.isSingleCell) {
                            selection = null
                            showKeyboard()
                        } else {
                            onCopySelection(activeTab.hostId, finished)
                        }
                    },
                    onScroll = { delta -> onScroll(activeTab.hostId, delta) },
                    onTap = {
                        if (selection != null) selection = null else showKeyboard()
                    },
                    onViewportChange = { columns, rows -> onResize(activeTab.hostId, columns, rows) },
                    onLongPressCell = { line, column ->
                        val text = state.terminalLine(activeTab.hostId, line)
                        selection = TerminalSelection.wordAt(line, column, text)
                            ?: TerminalSelection.wholeLine(line, frame.columns)
                        selection?.let { onCopySelection(activeTab.hostId, it) }
                    },
                )
                if (frame.totalLines > 0 && frame.firstLine + frame.lines.size < frame.totalLines) {
                    // Scrolled back, so the live output is no longer on screen. Without this the only
                    // clue is that nothing moves, which reads as a hung session.
                    ScrollbackBadge(
                        lines = frame.totalLines - (frame.firstLine + frame.lines.size),
                        onJump = { onScrollTo(activeTab.hostId, 0) },
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                    )
                }
            }
        }
        TerminalKeyRow(
            latches = latches,
            onKey = { key, ctrl, alt, shift -> onSendKey(activeTab.hostId, key, ctrl, alt, shift) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        )
        if (showCommandBar) {
            TerminalCommandBar(
                command = command,
                onCommand = { command = it; historyIndex = -1 },
                onRecall = recall,
                onSend = {
                    onSendInput(activeTab.hostId, command + "\n")
                    command = ""
                    historyIndex = -1
                },
                foreground = termFg,
                background = termBg,
                fontSize = fontSize,
            )
        }
        // The IME host. One pixel, transparent, always in the tree while the terminal is: an
        // InputConnection cannot be established for a view that is not composed, so a field created
        // only when the keyboard was wanted would arrive after the request to open it.
        TerminalInputBridge(
            focusRequester = focusRequester,
            latches = latches,
            onText = { text -> onSendText(activeTab.hostId, text) },
            onKey = { key, ctrl, alt, shift -> onSendKey(activeTab.hostId, key, ctrl, alt, shift) },
            onChar = { char, ctrl, alt -> onSendChar(activeTab.hostId, char, ctrl, alt) },
        )
    }

    if (showSnippets) {
        SnippetsSheet(
            snippets = state.snippets,
            onDismiss = { showSnippets = false },
            // Typed into the remote shell rather than into a form, so the shell's own line editing
            // applies: the snippet arrives on the command line where it can be corrected before Enter,
            // which is what a snippet is for. Deliberately not sent with a newline.
            onInsert = { snippet -> onSendText(activeTab.hostId, snippet.command); showSnippets = false; showKeyboard() },
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
                    lastOutput = state.terminalOutput[tab.hostId],
                    onOpen = { onOpenSession(tab) },
                    onReconnect = { onReconnect(tab.hostId) },
                    onClose = { onCloseTab(tab) },
                )
            }
        }
    }
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
    onOpen: () -> Unit,
    onReconnect: () -> Unit,
    onClose: () -> Unit,
) {
    val connected = tab.state == SessionConnectionState.CONNECTED
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
                    .background(if (connected) EclipseSuccess else EclipseWarning),
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
                    when (tab.state) {
                        SessionConnectionState.CONNECTED -> "Connected · since $startedAt"
                        SessionConnectionState.CONNECTING -> "Connecting…"
                        SessionConnectionState.RECONNECTING -> "Reconnecting…"
                        SessionConnectionState.DISCONNECTED -> tab.lastError ?: "Disconnected"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (connected) EclipseSuccess else EclipseWarning,
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
            if (tab.state == SessionConnectionState.DISCONNECTED) {
                TextButton(onClick = onReconnect, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Default.Wifi, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Reconnect", style = MaterialTheme.typography.labelMedium)
                }
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, "Close ${tab.title} session", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * The text of buffer line [line], read out of the plain-text transcript.
 *
 * Long-press word selection needs the characters around the tap, and the frame only holds the
 * viewport. The transcript is the whole buffer in the same line order, so indexing it gives the line
 * even when the tap landed on scrollback. Out of range returns empty, which selects nothing.
 */
private fun MainUiState.terminalLine(hostId: String, line: Int): String {
    val text = terminalOutput[hostId] ?: return ""
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
@Composable
private fun TerminalTabStrip(
    tabs: List<SessionTab>,
    activeTab: SessionTab,
    frame: TerminalFrame,
    onSelect: (SessionTab) -> Unit,
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
    searchQuery: String,
    onSearchQuery: (String) -> Unit,
    showSearch: Boolean,
    showCommandBar: Boolean,
    showHistory: Boolean,
    terminalText: String,
) {
    var menuOpen by remember { mutableStateOf(false) }
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
                    modifier = Modifier.clickable { onSelect(tab) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (tab == activeTab) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Row(Modifier.padding(start = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(if (tab.state == SessionConnectionState.CONNECTED) EclipseSuccess else EclipseWarning))
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
                    text = { Text(if (showCommandBar) "Hide command bar" else "Show command bar") },
                    onClick = { menuOpen = false; onToggleCommandBar() },
                )
                DropdownMenuItem(
                    text = { Text(if (showHistory) "Live terminal" else "Transcript") },
                    onClick = { menuOpen = false; onToggleHistory() },
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
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (activeTab.state) {
                SessionConnectionState.CONNECTED -> "Connected · encrypted"
                SessionConnectionState.CONNECTING -> "Connecting…"
                SessionConnectionState.RECONNECTING -> "Reconnecting…"
                SessionConnectionState.DISCONNECTED -> activeTab.lastError ?: "Disconnected"
            },
            style = MaterialTheme.typography.labelMedium,
            color = if (activeTab.state == SessionConnectionState.CONNECTED) EclipseSuccess else EclipseWarning,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // The way back, next to the reason. A session can end without the user asking - the shell
        // exited, the server went down, the network moved - and until this was here the only route to a
        // working shell again was to close the tab and start over from Hosts, throwing away the
        // scrollback on the way. It goes through the same authentication sheet as any other connection,
        // so a host whose password was never saved asks for it again rather than failing silently.
        if (activeTab.state == SessionConnectionState.DISCONNECTED) {
            TextButton(onClick = onReconnect, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Icon(Icons.Default.Wifi, null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Reconnect", style = MaterialTheme.typography.labelMedium)
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
}

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
        textStyle = TextStyle(color = foreground, fontFamily = FontFamily.Monospace, fontSize = fontSize.sp),
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

@Composable
private fun FilesScreen(
    state: MainUiState,
    onRefresh: () -> Unit,
    onUpload: () -> Unit,
    onDownloadFile: (RemoteFile) -> Unit,
    onNavigateRemote: (String) -> Unit,
    onCreateFolder: (String) -> Unit,
    onDeleteFile: (RemoteFile) -> Unit,
    onDeleteFiles: (List<RemoteFile>) -> Unit,
    onRenameFile: (RemoteFile, String) -> Unit,
    onChmodFile: (RemoteFile, Int) -> Unit,
    onCopyFile: (RemoteFile, String) -> Unit,
    onCopyFiles: (List<RemoteFile>, String) -> Unit,
    onMoveFile: (RemoteFile, String) -> Unit,
    onMoveFiles: (List<RemoteFile>, String) -> Unit,
    onPickLocalFolder: () -> Unit,
    onNavigateLocal: (Uri) -> Unit,
    onNavigateLocalUp: () -> Unit,
    onUploadLocal: (LocalFile) -> Unit,
    onScheduleDownload: (List<RemoteFile>, Long, Long?) -> Unit,
    onScheduleUpload: (List<LocalFile>, Long, Long?) -> Unit,
    onSync: (SyncDirection) -> Unit,
    onSendToHost: (RemoteFile, HostProfile, String) -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    val host = state.hosts.firstOrNull { it.id == state.selectedHostId } ?: state.hosts.firstOrNull()
    if (host == null) { EmptyState("No remote file system", "Connect to a host to browse SFTP files.", null); return }
    // Placeholder only until the first listing arrives and sets remotePath; fallbackHome
    // at least gets root (/root) and blank usernames right.
    val path = state.remotePath ?: fallbackHome(host.username)
    var showNewFolder by remember { mutableStateOf(false) }
    var actionFile by remember { mutableStateOf<RemoteFile?>(null) }
    var renameFile by remember { mutableStateOf<RemoteFile?>(null) }
    var chmodFile by remember { mutableStateOf<RemoteFile?>(null) }
    var propertiesFile by remember { mutableStateOf<RemoteFile?>(null) }
    // Confirmed before it happens, like the batch path already was. Deleting a remote file is
    // irreversible — there is no trash on the far side of SFTP — and the action sat on a bottom sheet
    // one row below "Send to host", so a mis-tap destroyed the file with nothing to undo it.
    var deleteFile by remember { mutableStateOf<RemoteFile?>(null) }
    var copyFile by remember { mutableStateOf<RemoteFile?>(null) }
    var moveFile by remember { mutableStateOf<RemoteFile?>(null) }
    var batchCopy by remember { mutableStateOf(false) }
    var batchMove by remember { mutableStateOf(false) }
    var batchDelete by remember { mutableStateOf(false) }
    var scheduleRemote by remember { mutableStateOf(false) }
    var scheduleLocal by remember { mutableStateOf(false) }
    var showSync by remember { mutableStateOf(false) }
    var sendFile by remember { mutableStateOf<RemoteFile?>(null) }
    var selectedRemote by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedLocal by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    // A selection belongs to the directory it was made in. Both sets hold absolute identifiers
    // (remote paths, local document URIs) while every batch action resolves them against the
    // *current* listing, so a selection carried across a navigation became a count the screen could
    // not act on: pick three files in one folder, open another, and the bar still claimed "3
    // selected" while Download and Upload had nothing to match and did nothing at all. The delete
    // confirmation, which counts the filtered list rather than the set, then disagreed with the bar
    // about how many items were about to go. Nothing was ever deleted from the wrong folder, since
    // absolute paths cannot collide, but a button that silently does nothing is indistinguishable
    // from a broken transfer, and that is what the user was left to interpret.
    LaunchedEffect(path) { selectedRemote = emptySet() }
    LaunchedEffect(state.localDirUri) { selectedLocal = emptySet() }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(path, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        IconButton(onClick = { showNewFolder = true }) { Icon(Icons.Default.CreateNewFolder, "New folder") }
        IconButton(onClick = onUpload) { Icon(Icons.Default.CloudUpload, "Upload file") }
        IconButton(onClick = { showSync = true }) { Icon(Icons.Default.SwapVert, "Sync folder") }
        IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Refresh") }
    }
    Spacer(Modifier.height(12.dp))

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val isWide = maxWidth >= 700.dp
        if (isWide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RemoteListing(
                    state,
                    path,
                    onNavigateRemote,
                    onDownloadFile,
                    onOpenActions = { actionFile = it },
                    selected = selectedRemote,
                    onToggleSelect = { file -> selectedRemote = if (file.path in selectedRemote) selectedRemote - file.path else selectedRemote + file.path },
                    modifier = Modifier.weight(1f),
                )
                LocalListing(
                    state,
                    onPickLocalFolder,
                    onNavigateLocal,
                    onNavigateLocalUp,
                    onUploadLocal,
                    selected = selectedLocal,
                    onToggleSelect = { file -> selectedLocal = if (file.uri in selectedLocal) selectedLocal - file.uri else selectedLocal + file.uri },
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                RemoteListing(
                    state,
                    path,
                    onNavigateRemote,
                    onDownloadFile,
                    onOpenActions = { actionFile = it },
                    selected = selectedRemote,
                    onToggleSelect = { file -> selectedRemote = if (file.path in selectedRemote) selectedRemote - file.path else selectedRemote + file.path },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                LocalListing(
                    state,
                    onPickLocalFolder,
                    onNavigateLocal,
                    onNavigateLocalUp,
                    onUploadLocal,
                    selected = selectedLocal,
                    onToggleSelect = { file -> selectedLocal = if (file.uri in selectedLocal) selectedLocal - file.uri else selectedLocal + file.uri },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (selectedRemote.isNotEmpty() || selectedLocal.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${selectedRemote.size + selectedLocal.size} selected", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(16.dp))
                TextButton(onClick = { selectedRemote = emptySet(); selectedLocal = emptySet() }) { Text("Clear") }
                if (selectedRemote.isNotEmpty()) {
                    Button(
                        onClick = {
                            state.remoteFiles.filter { it.path in selectedRemote }.forEach(onDownloadFile)
                            selectedRemote = emptySet()
                        },
                        enabled = state.localDirUri != null,
                    ) { Text("Download") }
                    TextButton(onClick = { scheduleRemote = true }, enabled = state.localDirUri != null) { Text("Schedule") }
                    TextButton(onClick = { batchCopy = true }) { Text("Copy") }
                    TextButton(onClick = { batchMove = true }) { Text("Move") }
                    TextButton(onClick = { batchDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                if (selectedLocal.isNotEmpty()) {
                    Button(
                        onClick = {
                            state.localFiles.filter { it.uri in selectedLocal }.forEach(onUploadLocal)
                            selectedLocal = emptySet()
                        },
                    ) { Text("Upload") }
                    TextButton(onClick = { scheduleLocal = true }) { Text("Schedule") }
                }
            }
        }
    }

    if (showNewFolder) {
        NewFolderDialog(onDismiss = { showNewFolder = false }, onConfirm = { name -> showNewFolder = false; onCreateFolder(name) })
    }
    actionFile?.let { file ->
        FileActionsSheet(
            file = file,
            onDismiss = { actionFile = null },
            onRename = { actionFile = null; renameFile = file },
            onProperties = { actionFile = null; propertiesFile = file },
            onChmod = { actionFile = null; chmodFile = file },
            onCopy = { actionFile = null; copyFile = file },
            onMove = { actionFile = null; moveFile = file },
            onSendToHost = { actionFile = null; sendFile = file },
            onDelete = { actionFile = null; deleteFile = file },
        )
    }
    deleteFile?.let { file ->
        AlertDialog(
            onDismissRequest = { deleteFile = null },
            title = { Text(if (file.isDirectory) "Delete folder \"${file.name}\"?" else "Delete \"${file.name}\"?") },
            text = {
                Text(
                    if (file.isDirectory) {
                        "This permanently removes the folder from the server. It must already be empty."
                    } else {
                        "This permanently removes the file from the server."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { deleteFile = null; onDeleteFile(file) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteFile = null }) { Text("Cancel") } },
        )
    }
    renameFile?.let { file ->
        RenameDialog(file, onDismiss = { renameFile = null }, onConfirm = { name -> renameFile = null; onRenameFile(file, name) })
    }
    chmodFile?.let { file ->
        ChmodDialog(file, onDismiss = { chmodFile = null }, onConfirm = { mode -> chmodFile = null; onChmodFile(file, mode) })
    }
    propertiesFile?.let { file ->
        FilePropertiesDialog(file, onDismiss = { propertiesFile = null })
    }
    sendFile?.let { file ->
        SendToHostDialog(
            file = file,
            hosts = state.hosts,
            onDismiss = { sendFile = null },
            onConfirm = { destHost, destPath -> sendFile = null; onSendToHost(file, destHost, destPath) },
        )
    }
    if (showSync) {
        SyncDialog(
            onDismiss = { showSync = false },
            onConfirm = { direction -> showSync = false; onSync(direction) },
        )
    }
    copyFile?.let { file ->
        PathDialog(file, "Copy", onDismiss = { copyFile = null }, onConfirm = { dest -> copyFile = null; onCopyFile(file, dest) })
    }
    moveFile?.let { file ->
        PathDialog(file, "Move", onDismiss = { moveFile = null }, onConfirm = { dest -> moveFile = null; onMoveFile(file, dest) })
    }
    val selectedRemoteFiles = state.remoteFiles.filter { it.path in selectedRemote }
    if (batchCopy) {
        BatchPathDialog(
            count = selectedRemoteFiles.size,
            action = "Copy",
            onDismiss = { batchCopy = false },
            onConfirm = { destination -> batchCopy = false; onCopyFiles(selectedRemoteFiles, destination); selectedRemote = emptySet() },
        )
    }
    if (batchMove) {
        BatchPathDialog(
            count = selectedRemoteFiles.size,
            action = "Move",
            onDismiss = { batchMove = false },
            onConfirm = { destination -> batchMove = false; onMoveFiles(selectedRemoteFiles, destination); selectedRemote = emptySet() },
        )
    }
    if (scheduleRemote) {
        ScheduleTransferDialog(
            count = selectedRemoteFiles.size,
            direction = "download",
            onDismiss = { scheduleRemote = false },
            onConfirm = { scheduledAt, repeatMinutes ->
                scheduleRemote = false
                onScheduleDownload(selectedRemoteFiles, scheduledAt, repeatMinutes)
                selectedRemote = emptySet()
            },
        )
    }
    if (scheduleLocal) {
        ScheduleTransferDialog(
            count = selectedLocal.size,
            direction = "upload",
            onDismiss = { scheduleLocal = false },
            onConfirm = { scheduledAt, repeatMinutes ->
                scheduleLocal = false
                onScheduleUpload(state.localFiles.filter { it.uri in selectedLocal }, scheduledAt, repeatMinutes)
                selectedLocal = emptySet()
            },
        )
    }
    if (batchDelete) {
        AlertDialog(
            onDismissRequest = { batchDelete = false },
            title = { Text("Delete ${selectedRemoteFiles.size} selected item(s)?") },
            text = { Text("This action permanently removes the selected remote files or empty directories.") },
            confirmButton = {
                TextButton(onClick = { batchDelete = false; onDeleteFiles(selectedRemoteFiles); selectedRemote = emptySet() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { batchDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RemoteListing(
    state: MainUiState,
    path: String,
    onNavigateRemote: (String) -> Unit,
    onDownloadFile: (RemoteFile) -> Unit,
    onOpenActions: (RemoteFile) -> Unit,
    selected: Set<String>,
    onToggleSelect: (RemoteFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(vertical = 8.dp)) {
            FileRow("..", "Parent directory", "—", true) { onNavigateRemote(parentOf(path)) }
            if (state.remoteFiles.isEmpty()) {
                // "Empty directory" is only true when there was a session to list one with. Without
                // one the contents are simply unknown, and calling them empty describes the server
                // instead of the connection — which on a clean install, where nothing is connected
                // yet, is the first thing this pane ever says and is wrong.
                val connected = state.tabs.any { it.hostId == state.selectedHostId && it.state == SessionConnectionState.CONNECTED }
                Text(
                    if (connected) "Empty directory" else "Not connected",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                // See [MAX_LISTED_ENTRIES] for why this is bounded and why the remainder is named.
                state.remoteFiles.take(MAX_LISTED_ENTRIES).forEach { file ->
                    FileRow(
                        file.name,
                        if (file.isDirectory) "Directory" else "${file.size} bytes",
                        file.permissions,
                        file.isDirectory,
                        onClick = { if (file.isDirectory) onNavigateRemote(file.path) else onDownloadFile(file) },
                        selected = file.path in selected,
                        onToggleSelect = if (file.isDirectory) null else fun() { onToggleSelect(file) },
                        trailing = {
                            IconButton(onClick = { onOpenActions(file) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.MoreVert, "Actions", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        },
                    )
                }
                TruncatedListingNotice(
                    state.remoteFiles.size,
                    "entries in this directory",
                    "Open a subfolder to narrow it down, or use Sync to transfer the whole folder.",
                )
            }
        }
    }
}

@Composable
private fun LocalListing(
    state: MainUiState,
    onPickFolder: () -> Unit,
    onNavigate: (Uri) -> Unit,
    onNavigateUp: () -> Unit,
    onUpload: (LocalFile) -> Unit,
    selected: Set<Uri>,
    onToggleSelect: (LocalFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text("Local files", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onPickFolder) { Icon(Icons.Default.Add, "Choose local folder") }
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(vertical = 8.dp)) {
                if (state.localDirUri == null) {
                    Text("Choose a local folder to browse and transfer files.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                    TextButton(onClick = onPickFolder, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Pick local folder") }
                } else {
                    FileRow("..", "Parent folder", "—", true) { onNavigateUp() }
                    if (state.localFiles.isEmpty()) {
                        Text("Empty folder", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                    } else {
                        state.localFiles.take(MAX_LISTED_ENTRIES).forEach { file ->
                            FileRow(
                                file.name,
                                if (file.isDirectory) "Directory" else "${file.size} bytes",
                                "Local",
                                file.isDirectory,
                                onClick = { if (file.isDirectory) onNavigate(file.uri) else onUpload(file) },
                                selected = file.uri in selected,
                                onToggleSelect = if (file.isDirectory) null else fun() { onToggleSelect(file) },
                            )
                        }
                        TruncatedListingNotice(
                            state.localFiles.size,
                            "files in this folder",
                            "Open a subfolder, or pick a narrower folder, to reach the rest.",
                        )
                    }
                }
            }
        }
    }
}

/**
 * Says how much of a list was left undrawn, and what to do about it — or nothing at all when the
 * whole list is on screen, which is every ordinary case.
 */
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

private fun parentOf(path: String): String = path.trimEnd('/').substringBeforeLast('/', "").ifBlank { "/" }

@Composable
private fun FileRow(
    name: String,
    metadata: String,
    modified: String,
    isDirectory: Boolean,
    onClick: () -> Unit = {},
    selected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onToggleSelect != null) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelect() }, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Icon(if (isDirectory) Icons.Default.FolderOpen else Icons.Default.Terminal, null, tint = if (isDirectory) EclipseWarning else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.SemiBold); Text(metadata, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(modified, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        trailing?.invoke()
    }
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
private fun RenameDialog(file: RemoteFile, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(file.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename ${file.name}") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("New name") }, singleLine = true) },
        confirmButton = { Button(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BatchPathDialog(count: Int, action: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var path by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$action $count selected item(s)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter the destination directory. Original file names will be preserved.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(path, { path = it }, label = { Text("Destination directory") }, placeholder = { Text("/home/user/archive") }, singleLine = true)
            }
        },
        confirmButton = { Button(onClick = { onConfirm(path.trim()) }, enabled = path.isNotBlank()) { Text(action) } },
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

@Composable
private fun PathDialog(file: RemoteFile, action: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var path by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$action ${file.name}") },
        text = {
            OutlinedTextField(
                path,
                { path = it },
                label = { Text("Destination path") },
                placeholder = { Text("/home/user/${file.name}") },
                singleLine = true,
            )
        },
        confirmButton = { Button(onClick = { onConfirm(path.trim()) }, enabled = path.isNotBlank()) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Picks new permissions for [file]. Tapping a row applies it, so there is no confirm button.
 *
 * The rows come from [PERMISSION_PRESETS], which carries the bits; both the octal digits and the
 * `rw-r--r--` reading are derived from them rather than typed alongside them. What the list used to
 * hold instead, and what it did to people's files, is written up there.
 */
@Composable
private fun ChmodDialog(file: RemoteFile, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    // Shown so the change can be judged against what is already there — and, on the first release
    // where these presets do what they say, so a file left mis-set by an earlier one is visible.
    val current = file.permissions.toIntOrNull(radix = 8)
        ?.let { "${file.permissions} · ${symbolicPermissions(it)}" }
        ?: file.permissions
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Permissions · ${file.name}") },
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

@Composable
private fun FilePropertiesDialog(file: RemoteFile, onDismiss: () -> Unit) {
    val modified = if (file.modifiedEpochSeconds > 0) {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochSecond(file.modifiedEpochSeconds))
    } else "Unknown"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Properties") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PropertyRow("Name", file.name)
                PropertyRow("Type", if (file.isDirectory) "Directory" else "File")
                PropertyRow("Size", if (file.isDirectory) "—" else formatFileSize(file.size))
                PropertyRow("Permissions", file.permissions)
                PropertyRow("Modified", modified)
                PropertyRow("Path", file.path)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
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

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.1f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileActionsSheet(file: RemoteFile, onDismiss: () -> Unit, onProperties: () -> Unit, onRename: () -> Unit, onChmod: () -> Unit, onCopy: () -> Unit, onMove: () -> Unit, onSendToHost: () -> Unit, onDelete: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 22.dp).navigationBarsPadding().padding(bottom = 18.dp)) {
            Text(file.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // formatFileSize, as the properties dialog has always used: "4294967296 bytes" is a
            // number the reader has to count digits in to understand.
            Text(
                if (file.isDirectory) "Directory · permissions ${file.permissions}"
                else "${formatFileSize(file.size)} · permissions ${file.permissions}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = onProperties, modifier = Modifier.fillMaxWidth()) { Text("Properties", modifier = Modifier.fillMaxWidth()) }
            TextButton(onClick = onRename, modifier = Modifier.fillMaxWidth()) { Text("Rename", modifier = Modifier.fillMaxWidth()) }
            TextButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) { Text("Copy to…", modifier = Modifier.fillMaxWidth()) }
            TextButton(onClick = onMove, modifier = Modifier.fillMaxWidth()) { Text("Move to…", modifier = Modifier.fillMaxWidth()) }
            TextButton(onClick = onSendToHost, modifier = Modifier.fillMaxWidth()) { Text("Send to another server…", modifier = Modifier.fillMaxWidth()) }
            // Offered for directories as well. It was files only, yet three of the five presets
            // (755, 700, 777) are the modes a directory needs and are meaningless on a data file —
            // the picker was built for both and then only reachable for one, leaving no way to fix a
            // directory's permissions from here.
            TextButton(onClick = onChmod, modifier = Modifier.fillMaxWidth()) { Text("Change permissions", modifier = Modifier.fillMaxWidth()) }
            TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) { Text("Delete", color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun TransfersScreen(
    transfers: List<TransferItem>,
    onClearCompleted: () -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Transfer queue", style = MaterialTheme.typography.titleLarge); Text("${transfers.count { it.status == TransferStatus.RUNNING }} active · ${transfers.size} total", color = MaterialTheme.colorScheme.onSurfaceVariant) }; OutlinedButton(onClick = onClearCompleted) { Text("Clear completed") } }
    Spacer(Modifier.height(16.dp))
    if (transfers.isEmpty()) {
        EmptyState("No transfers", "Upload or download files to see them here.", null)
    } else {
        // Bounded for the same reason the file listings are — see [MAX_LISTED_ENTRIES] — and this is
        // the screen where the count actually runs away: a directory sync writes one row per file and
        // never deletes them, so syncing a few thousand files leaves a few thousand cards to compose
        // on every visit to this tab, each heavier than a file row. Which rows survive the cut is a
        // rule with a test, in [transfersForDisplay].
        val visible = remember(transfers) { transfersForDisplay(transfers) }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            visible.forEach { TransferCard(it, onPause, onResume, onCancel) }
            TruncatedListingNotice(
                transfers.size,
                "transfers",
                "Clear completed to see the rest.",
            )
        }
    }
}

@Composable
private fun TransferCard(item: TransferItem, onPause: (String) -> Unit, onResume: (String) -> Unit, onCancel: (String) -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(40.dp)) { Box(contentAlignment = Alignment.Center) { Icon(if (item.direction == TransferDirection.DOWNLOAD) Icons.Default.CloudDownload else Icons.Default.CloudUpload, null, tint = MaterialTheme.colorScheme.primary) } }
                Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(item.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${item.hostName} · ${item.sizeLabel}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                when (item.status) {
                    TransferStatus.COMPLETE -> Icon(Icons.Default.CheckCircle, "Complete", tint = EclipseSuccess)
                    TransferStatus.RUNNING -> IconButton(onClick = { onPause(item.id) }) { Icon(Icons.Default.Pause, "Pause", tint = MaterialTheme.colorScheme.primary) }
                    else -> IconButton(onClick = { onResume(item.id) }) { Icon(Icons.Default.PlayArrow, "Resume") }
                }
                if (item.status != TransferStatus.COMPLETE) {
                    IconButton(onClick = { onCancel(item.id) }) { Icon(Icons.Default.Close, "Cancel transfer", tint = MaterialTheme.colorScheme.error) }
                }
            }
            if (item.status == TransferStatus.QUEUED && item.scheduledAt != null) {
                Spacer(Modifier.height(8.dp))
                Text("Scheduled for ${formatScheduledAt(item.scheduledAt)}" + if (item.repeatMinutes != null) " · repeats ${formatRepeat(item.repeatMinutes)}" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (item.status == TransferStatus.RUNNING || item.status == TransferStatus.PAUSED || item.status == TransferStatus.FAILED) {
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.height(5.dp))
                Text(
                    "${(item.progress * 100).toInt()}% · ${item.status.name.lowercase()}" + if (item.retryCount > 0) " · retry ${item.retryCount}/5" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
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
    // After the last parameter its only caller passes positionally, so adding it did not shift
    // onTerminalFontSize onto a different slot.
    onReconnectBase: (Int) -> Unit = {},
    onLegacyAlgorithms: (Boolean) -> Unit,
    onBlockScreenshots: (Boolean) -> Unit,
    onTerminalTheme: (String) -> Unit,
    onSetPin: (String) -> Unit,
    onClearPin: () -> Unit,
    verifyPin: suspend (String) -> Boolean,
    onForgetKnownHost: (String) -> Unit,
    onClearKnownHosts: () -> Unit,
    onGenerateKey: () -> Unit,
    onImportSshConfig: () -> Unit,
    onForgetAllCredentials: () -> Unit = {},
) {
    var showForwardDialog by remember { mutableStateOf(false) }
    var showKeepAliveDialog by remember { mutableStateOf(false) }
    var showClipboardDialog by remember { mutableStateOf(false) }
    var showReconnectDialog by remember { mutableStateOf(false) }
    var showFontDialog by remember { mutableStateOf(false) }
    var showPinDialog by remember { mutableStateOf(false) }
    var showKnownHosts by remember { mutableStateOf(false) }
    var confirmForgetCredentials by remember { mutableStateOf(false) }
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
        SettingRow(Icons.Default.Terminal, "Terminal font size", "${state.settings.terminalFontSize} sp monospace") { TextButton(onClick = { showFontDialog = true }) { Text("Change") } }
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
        SettingRow(Icons.Default.SwapVert, "Session manager", "Foreground service ready for active SSH, SFTP and forwards") { Icon(Icons.Default.CheckCircle, "Ready", tint = EclipseSuccess) }
        SettingRow(
            Icons.Default.Refresh,
            "Reconnect delay",
            "First retry after ${state.settings.reconnectBaseSeconds} s, then doubling · auto reconnect on network recovery",
        ) { TextButton(onClick = { showReconnectDialog = true }) { Text("Change") } }
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(max = 420.dp)) {
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

@Composable
private fun FontSizeDialog(current: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var size by remember { mutableFloatStateOf(current.coerceIn(10, 20).toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Terminal font size") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${size.toInt()} sp", style = MaterialTheme.typography.titleMedium)
                Slider(value = size, onValueChange = { size = it }, valueRange = 10f..20f, steps = 9)
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
private fun AuthenticationDialog(host: HostProfile, onDismiss: () -> Unit, onPickKey: () -> Unit, selectedKeyName: String?, onConnect: (String?, String?) -> Unit) {
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
                    )
                }
            }
        },
        confirmButton = { Button(onClick = { onConnect(password.ifBlank { null }, passphrase) }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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
private fun GlobalSearchDialog(state: MainUiState, onDismiss: () -> Unit, onSelectHost: (HostProfile) -> Unit, onCopySnippet: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    // Remembered rather than recomputed on every recomposition. Terminal scrollback runs to
    // MAX_TERMINAL_CHARS per host, so the last of these is a case-insensitive scan of a few hundred
    // kilobytes; a live session repaints this dialog while the user is still typing, and re-scanning
    // on each of those frames made the field itself feel slow.
    val hostResults = remember(state.hosts, query) {
        if (query.isBlank()) emptyList() else state.hosts.filter { it.matchesQuery(query) }
    }
    val snippetResults = remember(state.snippets, query) {
        if (query.isBlank()) emptyList() else state.snippets.filter { it.label.contains(query, ignoreCase = true) || it.command.contains(query, ignoreCase = true) }
    }
    val terminalMatches = remember(state.hosts, state.terminalOutput, query) {
        if (query.isBlank()) emptyList() else state.hosts.filter { host -> state.terminalOutput[host.id].orEmpty().contains(query, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Global search") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Search hosts, snippets, terminal output") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) })
                Spacer(Modifier.height(12.dp))
                Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (query.isBlank()) {
                        Text("Type to search across your workspace.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        if (hostResults.isNotEmpty()) {
                            Text("Hosts", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            hostResults.forEach { host ->
                                Row(Modifier.fillMaxWidth().clickable { onSelectHost(host) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Computer, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text("${host.name} · ${host.username}@${host.host}", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        if (snippetResults.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Snippets", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            snippetResults.forEach { snippet ->
                                Row(Modifier.fillMaxWidth().clickable { onCopySnippet(snippet.command) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Terminal, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Column { Text(snippet.label, style = MaterialTheme.typography.bodyMedium); Text(snippet.command, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace) }
                                }
                            }
                        }
                        if (terminalMatches.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text("Terminal output", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            terminalMatches.forEach { host ->
                                Row(Modifier.fillMaxWidth().clickable { onSelectHost(host) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Terminal, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp)); Text(host.name, style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                        if (hostResults.isEmpty() && snippetResults.isEmpty() && terminalMatches.isEmpty()) {
                            Text("No matches for \"$query\".", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
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
private fun PassphraseDialog(title: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
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
    // Seeded from the profile, and from the shipped default for a new one, so the box reflects what
    // this host will actually do rather than a hardcoded position.
    var autoLoginSftp by remember(initialHost?.id) {
        mutableStateOf(initialHost?.autoLoginSftp ?: HostProfile.DEFAULT_AUTO_LOGIN_SFTP)
    }
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
                        OutlinedTextField(socksPassword, { socksPassword = it }, label = { Text("Proxy password (optional)") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), keyboardOptions = SecretFieldKeyboard)
                    }
                    ProxyType.NONE -> Unit
                }
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
                        ),
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
                enabled = draft.canSave,
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
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
    onExportAccount: () -> Unit,
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
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onToggleFavorite) { Text(if (host.isFavorite) "Unfavorite" else "Favorite") }
                OutlinedButton(onClick = onExportAccount) { Text("Export account") }
                OutlinedButton(onClick = onEdit) { Text("Edit") }
                if (!credentials.isEmpty) {
                    OutlinedButton(onClick = onForgetCredentials) { Text("Forget credentials") }
                }
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
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
            Spacer(Modifier.height(18.dp)); Button(onClick = onConnect, Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.Wifi, null); Spacer(Modifier.width(8.dp)); Text("Connect securely") }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) { Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) { Text(label, Modifier.width(115.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium); Text(value, Modifier.weight(1f), fontWeight = FontWeight.SemiBold) } }
