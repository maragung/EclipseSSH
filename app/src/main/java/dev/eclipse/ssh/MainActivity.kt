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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Keyboard
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
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import dev.eclipse.ssh.data.model.DEFAULT_FORWARD_LISTEN_HOST
import dev.eclipse.ssh.data.model.DEFAULT_SOCKS_PORT
import dev.eclipse.ssh.data.model.DEFAULT_SSH_PORT
import dev.eclipse.ssh.data.model.KEEP_ALIVE_RANGE
import dev.eclipse.ssh.data.model.PORT_RANGE
import dev.eclipse.ssh.data.model.ForwardEntry
import dev.eclipse.ssh.data.model.ForwardType
import dev.eclipse.ssh.data.model.decodeForwardRules
import dev.eclipse.ssh.data.model.HostKeyChallenge
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.RemoteDesktopTarget
import dev.eclipse.ssh.data.model.decodeRemoteDesktop
import dev.eclipse.ssh.data.model.SessionConnectionState
import dev.eclipse.ssh.data.model.isEnded
import dev.eclipse.ssh.data.model.isLive
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
import dev.eclipse.ssh.feature.vault.shouldRelockVault
import dev.eclipse.ssh.presentation.AdvancedHostOptions
import dev.eclipse.ssh.presentation.HostFormDraft
import dev.eclipse.ssh.presentation.MAX_LISTED_ENTRIES
import dev.eclipse.ssh.presentation.files.FilesExplorerController
import dev.eclipse.ssh.presentation.linux.LinuxUserspaceController
import dev.eclipse.ssh.presentation.linux.LinuxUserspaceUiState
import dev.eclipse.ssh.linux.LinuxInstallStep
import dev.eclipse.ssh.linux.LinuxUserspaceState
import dev.eclipse.ssh.linux.LocalLinuxHost
import dev.eclipse.ssh.linux.SetupStep
import dev.eclipse.ssh.linux.percent
import dev.eclipse.ssh.presentation.files.LOCAL_SESSION_ID
import dev.eclipse.ssh.presentation.files.ellipsizeCrumbs
import dev.eclipse.ssh.presentation.transfersForDisplay
import dev.eclipse.ssh.presentation.AuthFailurePrompt
import dev.eclipse.ssh.presentation.ReconnectPrompt
import dev.eclipse.ssh.presentation.MainUiState
import dev.eclipse.ssh.presentation.MainViewModel
import dev.eclipse.ssh.archive.ArchiveBrowserState
import dev.eclipse.ssh.archive.ArchiveEntry
import dev.eclipse.ssh.archive.ArchiveExtractor
import dev.eclipse.ssh.archive.ArchiveTarget
import dev.eclipse.ssh.archive.ArchiveReader
import dev.eclipse.ssh.archive.ArchiveUiState
import dev.eclipse.ssh.ui.about.AboutActivity
import dev.eclipse.ssh.ui.archive.ArchiveEntryActionsActivity
import dev.eclipse.ssh.ui.archive.ArchiveEntryPreviewActivity
import dev.eclipse.ssh.ui.archive.ArchiveEntryPropertiesDialog
import dev.eclipse.ssh.ui.archive.ArchivePropertiesDialog
import dev.eclipse.ssh.ui.archive.ArchiveActions
import dev.eclipse.ssh.ui.archive.ArchiveBrowserScreen
import dev.eclipse.ssh.ui.archive.SafArchiveDestination
import dev.eclipse.ssh.ui.editor.EditorRequest
import dev.eclipse.ssh.ui.editor.EditorRequests
import dev.eclipse.ssh.ui.editor.TextEditorActivity
import dev.eclipse.ssh.ui.files.FileActionsActivity
import dev.eclipse.ssh.ui.files.ExplorerList
import dev.eclipse.ssh.ui.files.ExplorerPropertiesDialog
import dev.eclipse.ssh.ui.files.ExplorerSelectionBar
import dev.eclipse.ssh.ui.files.ExplorerTopBar
import dev.eclipse.ssh.ui.preview.FilePreviewActivity
import dev.eclipse.ssh.ui.remotedesktop.RemoteDesktopActivity
import dev.eclipse.ssh.ui.remotedesktop.RemoteDesktopConfigDialog
import dev.eclipse.ssh.ui.remotedesktop.RemoteDesktopRequest
import dev.eclipse.ssh.ui.remotedesktop.RemoteDesktopRequests
import dev.eclipse.ssh.ui.remotedesktop.RdpConfigDialog
import dev.eclipse.ssh.ui.settings.ClipboardClearActivity
import dev.eclipse.ssh.ui.settings.DiagnosticsActivity
import dev.eclipse.ssh.ui.settings.ExportBackupActivity
import dev.eclipse.ssh.ui.hosts.HostDetailsActivity
import dev.eclipse.ssh.ui.settings.HostFormActivity
import dev.eclipse.ssh.ui.settings.KeepAliveActivity
import dev.eclipse.ssh.ui.settings.KeyGenActivity
import dev.eclipse.ssh.ui.settings.KnownHostsActivity
import dev.eclipse.ssh.ui.settings.PinLockActivity
import dev.eclipse.ssh.ui.settings.ReconnectDelayActivity
import dev.eclipse.ssh.ui.settings.SavedCredentialsActivity
import dev.eclipse.ssh.ui.settings.SettingDropdown
import dev.eclipse.ssh.ui.settings.SettingRow
import dev.eclipse.ssh.ui.settings.SettingsSection
import dev.eclipse.ssh.ui.settings.ShortcutBarActivity
import dev.eclipse.ssh.ui.settings.TerminalFontSizeActivity
import dev.eclipse.ssh.ui.settings.TerminalHeightActivity
import dev.eclipse.ssh.ui.settings.TerminalWidthActivity
import dev.eclipse.ssh.ui.settings.UbuntuActivity
import dev.eclipse.ssh.ui.settings.VaultAutoLockActivity
import dev.eclipse.ssh.ui.AdvancedHostSection
import dev.eclipse.ssh.ui.EclipseSuccess
import dev.eclipse.ssh.ui.EclipseTheme
import dev.eclipse.ssh.ui.EclipseWarning
import dev.eclipse.ssh.ui.forward.ForwardFormActivity
import dev.eclipse.ssh.ui.forward.ForwardRequests
import dev.eclipse.ssh.ui.forward.PortForwardManagerActivity
import dev.eclipse.ssh.ui.SecretFieldKeyboard
import dev.eclipse.ssh.ui.SecretPasteButton
import dev.eclipse.ssh.ui.actions.ActionAnswer
import dev.eclipse.ssh.ui.actions.ActionRequests
import dev.eclipse.ssh.ui.actions.ArchiveEntryActionKind
import dev.eclipse.ssh.ui.actions.FileActionKind
import dev.eclipse.ssh.ui.actions.ForwardRuleKind
import dev.eclipse.ssh.ui.actions.HostDetailsKind
import dev.eclipse.ssh.ui.actions.SnippetActionKind
import dev.eclipse.ssh.ui.actions.TransferActionKind
import dev.eclipse.ssh.ui.sessions.SessionWhyActivity
import dev.eclipse.ssh.ui.snippets.SnippetsActivity
import dev.eclipse.ssh.ui.statusColor
import dev.eclipse.ssh.ui.transfers.TransferActionsActivity
import dev.eclipse.ssh.ssh.PERMISSION_PRESETS
import dev.eclipse.ssh.ssh.SshKeyProbe
import dev.eclipse.ssh.ssh.probeSshKey
import dev.eclipse.ssh.ssh.symbolicPermissions
import dev.eclipse.ssh.data.credentials.HostCredentialUpdate
import dev.eclipse.ssh.data.credentials.KeyEdit
import dev.eclipse.ssh.data.credentials.SecretEdit
import dev.eclipse.ssh.data.saf.readPickedKeyFile
import dev.eclipse.ssh.ssh.RemoteFile
import dev.eclipse.ssh.data.saf.LocalFile
import androidx.compose.ui.focus.FocusRequester
import dev.eclipse.ssh.terminal.TerminalFrame
import dev.eclipse.ssh.terminal.TerminalKey
import dev.eclipse.ssh.terminal.TerminalSelection
import dev.eclipse.ssh.terminal.TerminalViewport
import dev.eclipse.ssh.ui.terminal.KeyBarPrefsCodec
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
     * The most recent answer from one of the windows that used to be bottom sheets over the
     * workspace - see [ActionAnswer].
     *
     * A flow rather than a plain field for the reason [deepLink] is one: [onResume] can run before the
     * composition exists (the very first resume does), so the answer has to be somewhere the
     * composition will *collect* rather than somewhere it has to have been watching. The composition
     * clears it to null once it has acted, which is what makes `null` mean "nothing waiting" instead
     * of "no answer was ever given".
     */
    private val pendingAction = MutableStateFlow<ActionAnswer?>(null)

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
            val pendingAnswer by pendingAction.collectAsStateWithLifecycle()
            EclipseWorkspace(
                deepLinkHost = pending,
                onDeepLinkConsumed = { deepLink.value = null },
                pendingAction = pendingAnswer,
                onActionHandled = { pendingAction.value = null },
            )
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
        openConfirmedForward()
        // And the same shape one more time: a window the user just came back from may have left an
        // answer behind. Taken unconditionally, because nothing but a window ever writes one.
        ActionRequests.takeAnswer()?.let { pendingAction.value = it }
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
     * Opens a forward the Add-forward form confirmed, if one is waiting.
     *
     * [onResume] rather than a result callback, for the reason every Settings window works the same
     * way: the form's answer is *intent* - a request to open a port - and the only safe number of
     * times to act on it is once. [ForwardRequests.takeConfirmed] empties the slot as it reads it, so
     * the second resume of a rotation, or a process that comes back to a re-delivered intent, finds
     * nothing and opens nothing. The alternative - a result code replayed into a fresh composition -
     * is a second bind of the same port, which fails with a message about a socket rather than about
     * what the user actually did.
     *
     * The host is looked up by the id the form was launched with, not by "whatever is selected now":
     * a forward that changes server because the user browsed elsewhere in the meantime is a tunnel
     * opened on the wrong machine, and nothing downstream could tell. A host deleted while the form
     * was open is said out loud rather than dropped.
     */
    private fun openConfirmedForward() {
        val request = ForwardRequests.takeConfirmed() ?: return
        val host = viewModel.uiState.value.hosts.firstOrNull { it.id == request.hostId }
        if (host == null) {
            viewModel.reportUiMessage("That host is no longer configured, so no forward was opened")
            return
        }
        when (request.type) {
            ForwardType.LOCAL -> viewModel.startLocalForward(
                host,
                request.localPort,
                // Unreachable by construction - the form will not confirm a Local forward without a
                // destination - and kept as the dialog's own fallbacks so that a request built by
                // anything else still opens a tunnel rather than throwing on the way to one.
                request.remoteHost ?: DEFAULT_FORWARD_LISTEN_HOST,
                request.remotePort ?: request.localPort,
            )
            ForwardType.REMOTE -> viewModel.startRemoteForward(
                host,
                request.remotePort ?: request.localPort,
                request.localPort,
            )
            ForwardType.DYNAMIC -> viewModel.startDynamicForward(host, request.localPort)
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
    /**
     * What a window that has just been closed chose, if it chose anything - see [ActionAnswer].
     *
     * Handed down rather than read from [ActionRequests] here, because the one moment a returning
     * window is noticed is `onResume` and a composition cannot observe that on its own. Null means
     * nothing is waiting; [onActionHandled] clears it once the answer has been acted on.
     */
    pendingAction: ActionAnswer? = null,
    onActionHandled: () -> Unit = {},
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
        // Only an SFTP session has a host to address. The Ubuntu session is *this* device, so a
        // transfer there is a local copy and never reaches this lambda's answer.
        val hostId = explorer.activeSessionId.takeIf { explorer.isSftp }?.removePrefix("sftp:")
        state.hosts.firstOrNull { it.id == hostId } ?: activeHost
    }
    /**
     * The directory the explorer is browsing, when it is browsing a server's - the destination a
     * picked upload should land in. Null while the local session is active, so the pickers fall
     * back to the path the ViewModel last listed.
     */
    val explorerRemoteDirectory: () -> String? = {
        val explorer = viewModel.filesExplorer.state.value
        explorer.path.takeIf { explorer.isSftp }
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
    var pendingDownload by remember { mutableStateOf<RemoteFile?>(null) }
    /**
     * The userspace rows a "Copy to this device" is copying, held while its picker is up.
     *
     * A different slot from [pendingDownload] rather than a reuse of it, because the two carry
     * different subjects: that one is a `RemoteFile` for the transfer pipeline, and these are the guest
     * entries a direct copy streams — carrying an entry as a `RemoteFile` and converting it back would
     * be lossy in exactly the field this copy needs, its guest path. A list because both entry points
     * land here: one row offered a name, a selection offered a folder.
     */
    var pendingUbuntuCopyOut by remember { mutableStateOf<List<FsEntry>>(emptyList()) }
    val downloadPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        pickerActive = false
        // Read and cleared before anything else can look at them: a second resume must not re-run
        // this copy, and the explorer may have moved on by the time the picker answered.
        val ubuntuEntry = pendingUbuntuCopyOut.firstOrNull()
        val remote = pendingDownload
        pendingUbuntuCopyOut = emptyList()
        pendingDownload = null
        if (uri == null) return@rememberLauncherForActivityResult
        // The explorer's session, not the app's selected host: the file being downloaded was
        // picked in whichever server the Files tab was browsing - see [transferHost].
        val host = transferHost()
        if (ubuntuEntry != null && viewModel.filesExplorer.state.value.isUbuntu) {
            // The userspace is this device, so the document the picker just made *is* the copy: a
            // direct stream from the rootfs into it, with no host and no queue to file it under.
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.filesExplorer.copyOutOfUbuntu(ubuntuEntry, uri)
            return@rememberLauncherForActivityResult
        }
        if (host != null && remote != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.startDownload(host, remote, uri)
        }
    }
    val ubuntuCopyOutPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        pickerActive = false
        val entries = pendingUbuntuCopyOut
        pendingUbuntuCopyOut = emptyList()
        if (uri != null && entries.isNotEmpty()) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.filesExplorer.copySelectionOutOfUbuntu(entries, uri)
        }
    }
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        pickerActive = false
        val host = transferHost()
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        if (viewModel.filesExplorer.state.value.isUbuntu) {
            // Uploading *into* the userspace: the destination is the guest folder on screen, and the
            // copy is a local stream rather than a queued transfer - see [UbuntuTransfers].
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            viewModel.filesExplorer.copyIntoUbuntu(uris)
            return@rememberLauncherForActivityResult
        }
        if (host != null) {
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
    // The Files explorer's full-window surfaces, all three of them now. The preview used to be a
    // sheet at this root, on the theory that looking at a file was part of the workspace; reading is
    // not, and a sheet letterboxes what it shows, so it opens in a window of its own like the editor
    // — see [FilePreviewActivity]. Both are *triggered* here rather than composed here, which is why
    // the launch sites below are `startActivity` calls and there is no target state to hold.
    var editorRequest by remember { mutableStateOf<EditorRequest?>(null) }
    // The View Archive browser target: the scan runs against this workspace's provider, and the
    // browser is one archive at a time by design - opening a second archive closes the first,
    // because each one holds an SFTP channel for as long as it is open.
    var archiveTarget by remember { mutableStateOf<ArchiveTarget?>(null) }
    /**
     * Opens one remote archive in the View Archive browser — the Files sheet's View Archive row.
     *
     * The archive's host is found from the explorer's session rather than the workspace's selected
     * host, because the sheet can be open on a session the Hosts list never selected; getting the
     * host wrong here would silently scan the wrong server's file. Format detection is by name
     * only, before anything is read: it costs nothing and a name that suggests no browsable
     * container means the row was offered in error — reported, not guessed around.
     */
    fun openArchive(entry: FsEntry, provider: FileSystemProvider) {
        val format = ArchiveReader.formatFor(entry.name)
            ?: return viewModel.reportUiMessage("\"${entry.name}\" is not an archive View Archive can browse")
        val explorer = viewModel.filesExplorer.state.value
        val sessionId = explorer.activeSessionId
        val size = entry.size
            ?: return viewModel.reportUiMessage("\"${entry.name}\" has no size to read by range")
        val source = viewModel.filesExplorer.archiveSourceFor(sessionId, entry.path, size)
            ?: return viewModel.reportUiMessage("View Archive needs a connected server session")
        // Opening a second archive closes the first: each browser holds an SFTP channel for as
        // long as it is open, and two of them is a leak the user never asked for.
        archiveTarget?.browser?.close()
        archiveTarget = ArchiveTarget(entry, ArchiveBrowserState(
            archiveName = entry.name,
            format = format,
            source = source,
            provider = provider,
            remotePath = entry.path,
            scope = scope,
        ))
    }

    /**
     * Turns an extract's per-entry outcomes into the one-line report a person can act on, without
     * a stack trace in sight. The honest summary is tiered: all good says how many and how much;
     * anything refused or failed says so, because a silent skip is how a hostile member hides.
     */
    fun reportExtractOutcome(archiveName: String, outcomes: List<ArchiveExtractor.Outcome>) {
        val extracted = outcomes.filterIsInstance<ArchiveExtractor.Outcome.Extracted>()
        if (outcomes.size == extracted.size) {
            val total = extracted.sumOf { it.bytes }
            viewModel.reportUiMessage("Extracted ${extracted.size} item(s) from \"$archiveName\" ($total bytes)")
            return
        }
        val refused = outcomes.count { it is ArchiveExtractor.Outcome.Refused }
        val destinationRefused = outcomes.count { it is ArchiveExtractor.Outcome.DestinationRefused }
        val failed = outcomes.filterIsInstance<ArchiveExtractor.Outcome.Failed>()
        val parts = buildList {
            if (extracted.isNotEmpty()) add("${extracted.size} extracted")
            if (refused > 0) add("$refused skipped for safety")
            if (destinationRefused > 0) add("$destinationRefused could not be created")
            failed.firstOrNull()?.let { add("first failure: ${it.reason}") }
        }
        viewModel.reportUiMessage("Extract from \"$archiveName\": ${parts.joinToString("; ")}")
    }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var pendingTextExport by remember { mutableStateOf<ByteArray?>(null) }
    var pendingScreenExport by remember { mutableStateOf<ByteArray?>(null) }
    var pendingAccountExportHost by remember { mutableStateOf<HostProfile?>(null) }
    var pendingAccountImportUri by remember { mutableStateOf<Uri?>(null) }
    var showAccountExportDialog by remember { mutableStateOf(false) }
    var showAccountImportDialog by remember { mutableStateOf(false) }
    val configImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        pickerActive = false
        if (uri != null) viewModel.importOpenSshConfig(uri)
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
     * A tab's own Reconnect action. The dial speaks for that tab's session - see
     * [MainViewModel.reconnectSession] - and the tab it was asked from is the one opened, where a
     * reconnect used to land on the host's first tab for the same host-routed reason the dial did.
     */
    fun reconnectAndOpen(sessionKey: String) {
        viewModel.reconnectSession(sessionKey)
        openSessionId = sessionKey
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
    // Re-read on every resume, for the same reason the biometric capability above is: the known-hosts
    // window writes through `SshConnectionManager`, which this view model is not subscribed to - the
    // store is an in-memory map plus a file and announces nothing - so the copy in `knownHostsState`
    // is a snapshot that only a fresh read can move. Without this, forgetting a fingerprint in that
    // window would leave the Settings row counting one that is gone, and the row is where a user
    // checks whether the thing they just did took effect. Cheap enough to do unconditionally: one
    // copy of a map that holds a host key per trusted host.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshKnownHosts()
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
    // The host whose remote-desktop endpoint dialog is open, by id for the same reason the dialogs
    // that save into a live profile all are: a snapshot taken at open time would keep showing a host
    // the user has since removed.
    var remoteDesktopHostId by remember { mutableStateOf<String?>(null) }
    // The RDP endpoint dialog's host, by id for the same reason as the VNC one beside it.
    var rdpDesktopHostId by remember { mutableStateOf<String?>(null) }
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
    // Both protocol items mint through it, because both tunnels ride a ClientSession the same way.
    val launchViewer: (RemoteDesktopRequest) -> Unit = { request ->
        val token = RemoteDesktopRequests.put(request)
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
    val openRemoteDesktop: (HostProfile, RemoteDesktopTarget) -> Unit = { host, target ->
        launchViewer(
            RemoteDesktopRequest.Vnc(
                hostName = host.name,
                target = target,
                sessionProvider = viewModel.remoteDesktopSessionProvider(host.id),
            ),
        )
    }
    // The RDP handoff reads the saved NLA credential first, because the request it mints carries
    // it: a complete one lets the tunnel answer NLA without asking, and whatever is saved
    // pre-fills the sign-in form when the server asks anyway. The read is a callback (it
    // decrypts off the main thread), so the viewer opens a moment after the tap rather than in
    // it - the one difference from the VNC item's instant hop.
    val openRdpDesktop: (HostProfile, RemoteDesktopTarget) -> Unit = { host, target ->
        viewModel.rdpCredentials(host.id) { credentials ->
            launchViewer(
                RemoteDesktopRequest.Rdp(
                    hostName = host.name,
                    target = target,
                    sessionProvider = viewModel.remoteDesktopSessionProvider(host.id),
                    credentials = credentials,
                    credentialsComplete = credentials != null,
                ),
            )
        }
    }
    // The menu's VNC Viewer item: a saved, enabled target opens the viewer straight away;
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
    // The menu's RDP Viewer item: the VNC item's rule, now that the viewer it routes to exists.
    // A saved, enabled target goes straight to the viewer window through the token handoff;
    // never configured or parked opens the endpoint dialog, which is also where a parked target
    // gets re-enabled.
    val requestRdpDesktop: (HostProfile) -> Unit = { host ->
        val target = decodeRemoteDesktop(host.remoteDesktop).rdp
        if (target != null && target.enabled) {
            openRdpDesktop(host, target)
        } else {
            rdpDesktopHostId = host.id
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
    // system-initiated process death — and the ON_START re-lock below cannot undo that, because
    // the timestamp it needs is exactly as ephemeral: restored to `unlocked = true` with no
    // backgrounded-at to compare against, a background-killed process would come back unlocked.
    // `remember` still survives rotation (MainActivity handles those configChanges itself, so it is
    // never recreated); anything that does recreate the activity now re-locks, which is the safe
    // default.
    var unlocked by remember { mutableStateOf(false) }

    // Re-lock the vault after the app has been in the background past the auto-lock delay, so a
    // phone left on a desk is not an open vault while an unlocked one stays usable through the
    // glance-away-and-back that a zero-tolerance rule would punish. The countdown starts at ON_STOP
    // and the decision — pure, and unit-tested in [shouldRelockVault] — runs at ON_START, the first
    // moment the elapsed time is known. Clearing `unlocked` here is what makes the re-lock real:
    // the same `pinEnabled && !unlocked` branch below that gates a cold launch then composes the
    // same LockScreen, biometric button and all, before anything else is reachable.
    // A file picker (SAF) briefly stops the activity too, so one in flight suspends the countdown
    // rather than starting it. With no PIN set there is no lock to re-arm and the setting is inert.
    val pinEnabled by rememberUpdatedState(state.settings.pinEnabled)
    val vaultAutoLockMinutes by rememberUpdatedState(state.settings.vaultAutoLockMinutes)
    var backgroundedAtMs by remember { mutableStateOf<Long?>(null) }
    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP ->
                    // Two populations of picker hold the countdown off, and both have to be asked.
                    // `pickerActive` is this composition's own flag, set by the launchers in this
                    // file. The gate is the process-wide one, held by the windows that own their
                    // pickers now - a generated key pair, an exported backup, a saved install log -
                    // and a window cannot reach a `remember` owned here, which is exactly why the
                    // gate exists. A picker either of them opened reports the process as
                    // backgrounded just the same, so either one alone is a reason not to start.
                    if (!pickerActive && !viewModel.vaultUnlockGate.pickerActive) {
                        backgroundedAtMs = System.currentTimeMillis()
                    }
                Lifecycle.Event.ON_START -> {
                    val wentAwayAtMs = backgroundedAtMs
                    backgroundedAtMs = null
                    if (
                        wentAwayAtMs != null &&
                        shouldRelockVault(
                            autoLockMinutes = vaultAutoLockMinutes,
                            lockConfigured = pinEnabled,
                            backgroundedAtMs = wentAwayAtMs,
                            nowMs = System.currentTimeMillis(),
                        )
                    ) unlocked = false
                }
                else -> Unit
            }
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
    val terminalSessionOpen = destination == Destination.TERMINAL &&
        !(state.settings.pinEnabled && !unlocked) &&
        openSessionId?.let { id -> state.tabs.any { it.id == id } } == true
    /**
     * The same moment, minus the system bars, when the user chose to keep them.
     *
     * [AppSettings.terminalKeepSystemBars] is the one opt-out from immersive: some users want the
     * navigation bar's back gesture visibly marked, or the clock to survive a long session. The app's
     * own chrome still goes - the shell keeps the space either way - but the system's bars stay on
     * screen, which is what the setting says and all it says.
     */
    val terminalImmersive = terminalSessionOpen && !state.settings.terminalKeepSystemBars
    // Back leaves the shell, not the app. A full-screen terminal has no navigation on screen, so
    // without this the only way out of a session is the gesture that closes the whole app - and the
    // session with it.
    BackHandler(enabled = terminalSessionOpen) { openSessionId = null }
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
                // Gone while a shell owns the window - see [terminalSessionOpen]. Back, or the
                // strip's own button, brings it straight back.
                if (!terminalSessionOpen) NavigationRail(
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
                    linuxUserspace = viewModel.linuxUserspace,
                    localLinuxCard = viewModel.localLinuxCard,
                    pendingAction = pendingAction,
                    onActionHandled = onActionHandled,
                    onPreviewFile = { entry, provider ->
                        context.startActivity(FilePreviewActivity.intent(context, entry, provider))
                    },
                    onEditFile = { entry, provider -> editorRequest = EditorRequest(entry, provider) },
                    onOpenArchive = ::openArchive,
                    onDestination = { destination = it },
                    onSearch = viewModel::setQuery,
                    onAddHost = { openHostForm(context) },
                    onConnect = { host ->
                        // The local Ubuntu card never opens the login: there is nothing to
                        // authenticate (the session is the app's own uid), so the tap goes straight
                        // to the terminal. Every other host gets the auth dialog as before.
                        if (LocalLinuxHost.isLocalHost(host.id)) viewModel.connect(host) else showAuthHost = host
                    },
                    onReconnectSession = { reconnectAndOpen(it) },
                    onShowDetails = {
                        context.startActivity(
                            HostDetailsActivity.intent(context, it, state.serverStats[it.id]),
                        )
                    },
                    onEditHost = { openHostForm(context, it) },
                    onRemoveHost = { pendingDeleteHost = it },
                    onToggleFavoriteHost = { viewModel.saveHost(it.copy(isFavorite = !it.isFavorite)) },
                    onExportAccount = { pendingAccountExportHost = it; showAccountExportDialog = true },
                    onDuplicateHost = requestDuplicateHost,
                    // A window, and handed the runtime half of its own subject here: the state
                    // of every rule and the forwards actually bound are this workspace's, which is
                    // what the manager window cannot read for itself. See ActionSubject.ForwardManager.
                    onManageForwards = {
                        context.startActivity(
                            PortForwardManagerActivity.intent(context, it, state.forwardStatuses, state.forwardings),
                        )
                    },
                    onRemoteDesktop = requestRemoteDesktop,
                    onWakeOnLan = viewModel::wakeHost,
                    onRdpDesktop = requestRdpDesktop,
                    onCloseTab = viewModel::closeTab,
                    onDuplicateSession = viewModel::duplicateSession,
                    onDisconnectAll = viewModel::disconnectAll,
                    openSessionId = openSessionId,
                    onOpenSession = { openSessionId = it },
                    // The session owns the window whatever the bars do; the immersive flag is only
                    // the system-bars half of full screen.
                    immersive = terminalSessionOpen,
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
                    onSaveLogs = { hostId, text ->
                        pendingTextExport = ("Eclipse SSH session log\nHost: $hostId\nSaved: ${System.currentTimeMillis()}\n\n$text").toByteArray()
                        pickerActive = true
                        textExportPicker.launch("eclipse-$hostId.log")
                    },
                    // The raw log comes straight from the view model's per-session transcript -
                    // keyed by session, so the shell on screen is the one saved - and rides the
                    // same SAF launcher and pending-bytes slot the diagnostics save uses. Nothing
                    // is written unless a log exists, which the menu item already ensured.
                    onSaveSessionLog = { sessionKey, hostName ->
                        val text = viewModel.sessionLogText(sessionKey)
                        if (text.isNullOrEmpty()) {
                            viewModel.reportUiMessage("This session has no output to save yet")
                        } else {
                            pendingTextExport = text.toByteArray()
                            pickerActive = true
                            textExportPicker.launch("$hostName-session.log")
                        }
                    },
                    onNotifyWhenDone = viewModel::notifyWhenDone,
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
                    onCopyToDevice = { entries ->
                        // The explorer's session again, because a copy out of the userspace is only a
                        // copy while the userspace is what is on screen. One file is offered a name -
                        // the same `CreateDocument` answer a server's download gets - and a selection
                        // is offered a folder, the one destination that serves any number of them,
                        // since `CreateDocument` can only answer with a single document.
                        pendingUbuntuCopyOut = entries
                        pickerActive = true
                        if (entries.size == 1) downloadPicker.launch(entries.first().name)
                        else ubuntuCopyOutPicker.launch(null)
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
                    onOpenTransferActions = { transfer ->
                        // A window of its own rather than a sheet over the card it acts on: the card
                        // is a live progress row, and covering it to offer Pause is backwards. See
                        // [TransferActionsActivity].
                        context.startActivity(TransferActionsActivity.intent(context, transfer))
                    },
                    // The host the Add-forward form will name at launch. It used to be reached for at
                    // *confirmation* time by the `onAddForward` lambda that stood here, which is how a
                    // forward ended up on whichever server the user had switched to while the form was
                    // open — see [openConfirmedForward].
                    activeHost = activeHost,
                    onStopForward = viewModel::stopForwarding,
                    onImportVault = { pickerActive = true; importPicker.launch(arrayOf("*/*")) },
                    onImportAccount = { pickerActive = true; accountImportPicker.launch(arrayOf("*/*")) },
                    onImportSshConfig = { pickerActive = true; configImportPicker.launch(arrayOf("text/plain", "*/*")) },
                    onBiometric = ::setBiometric,
                    onDarkTheme = viewModel::setDarkTheme,
                    onTerminalKeyRow = viewModel::setTerminalKeyRowVisible,
                    onTerminalFontSize = viewModel::setTerminalFontSize,
                    onLegacyAlgorithms = viewModel::setLegacyAlgorithms,
                    onBlockScreenshots = viewModel::setBlockScreenshots,
                    onReconnectAskFirst = viewModel::setReconnectAskFirst,
                    onTerminalTheme = viewModel::setTerminalTheme,
                    onTerminalKeepSystemBars = viewModel::setTerminalKeepSystemBars,
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
                    // Gone while a shell owns the window - see [terminalSessionOpen].
                    if (!terminalSessionOpen) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
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
                    linuxUserspace = viewModel.linuxUserspace,
                    localLinuxCard = viewModel.localLinuxCard,
                    pendingAction = pendingAction,
                    onActionHandled = onActionHandled,
                    onPreviewFile = { entry, provider ->
                        context.startActivity(FilePreviewActivity.intent(context, entry, provider))
                    },
                    onEditFile = { entry, provider -> editorRequest = EditorRequest(entry, provider) },
                    onOpenArchive = ::openArchive,
                    onDestination = { destination = it },
                    onSearch = viewModel::setQuery,
                    onAddHost = { openHostForm(context) },
                    onConnect = { host ->
                        // The local Ubuntu card never opens the login: there is nothing to
                        // authenticate (the session is the app's own uid), so the tap goes straight
                        // to the terminal. Every other host gets the auth dialog as before.
                        if (LocalLinuxHost.isLocalHost(host.id)) viewModel.connect(host) else showAuthHost = host
                    },
                    onReconnectSession = { reconnectAndOpen(it) },
                    onShowDetails = {
                        context.startActivity(
                            HostDetailsActivity.intent(context, it, state.serverStats[it.id]),
                        )
                    },
                    onEditHost = { openHostForm(context, it) },
                    onRemoveHost = { pendingDeleteHost = it },
                    onToggleFavoriteHost = { viewModel.saveHost(it.copy(isFavorite = !it.isFavorite)) },
                    onExportAccount = { pendingAccountExportHost = it; showAccountExportDialog = true },
                    onDuplicateHost = requestDuplicateHost,
                    // A window, and handed the runtime half of its own subject here: the state
                    // of every rule and the forwards actually bound are this workspace's, which is
                    // what the manager window cannot read for itself. See ActionSubject.ForwardManager.
                    onManageForwards = {
                        context.startActivity(
                            PortForwardManagerActivity.intent(context, it, state.forwardStatuses, state.forwardings),
                        )
                    },
                    onRemoteDesktop = requestRemoteDesktop,
                    onWakeOnLan = viewModel::wakeHost,
                    onRdpDesktop = requestRdpDesktop,
                    onCloseTab = viewModel::closeTab,
                    onDuplicateSession = viewModel::duplicateSession,
                    onDisconnectAll = viewModel::disconnectAll,
                    openSessionId = openSessionId,
                    onOpenSession = { openSessionId = it },
                    // The session owns the window whatever the bars do; the immersive flag is only
                    // the system-bars half of full screen.
                    immersive = terminalSessionOpen,
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
                    onSaveLogs = { hostId, text ->
                        pendingTextExport = ("Eclipse SSH session log\nHost: $hostId\nSaved: ${System.currentTimeMillis()}\n\n$text").toByteArray()
                        pickerActive = true
                        textExportPicker.launch("eclipse-$hostId.log")
                    },
                    // The raw log comes straight from the view model's per-session transcript -
                    // keyed by session, so the shell on screen is the one saved - and rides the
                    // same SAF launcher and pending-bytes slot the diagnostics save uses. Nothing
                    // is written unless a log exists, which the menu item already ensured.
                    onSaveSessionLog = { sessionKey, hostName ->
                        val text = viewModel.sessionLogText(sessionKey)
                        if (text.isNullOrEmpty()) {
                            viewModel.reportUiMessage("This session has no output to save yet")
                        } else {
                            pendingTextExport = text.toByteArray()
                            pickerActive = true
                            textExportPicker.launch("$hostName-session.log")
                        }
                    },
                    onNotifyWhenDone = viewModel::notifyWhenDone,
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
                    onCopyToDevice = { entries ->
                        // The explorer's session again, because a copy out of the userspace is only a
                        // copy while the userspace is what is on screen. One file is offered a name -
                        // the same `CreateDocument` answer a server's download gets - and a selection
                        // is offered a folder, the one destination that serves any number of them,
                        // since `CreateDocument` can only answer with a single document.
                        pendingUbuntuCopyOut = entries
                        pickerActive = true
                        if (entries.size == 1) downloadPicker.launch(entries.first().name)
                        else ubuntuCopyOutPicker.launch(null)
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
                    onOpenTransferActions = { transfer ->
                        // A window of its own rather than a sheet over the card it acts on: the card
                        // is a live progress row, and covering it to offer Pause is backwards. See
                        // [TransferActionsActivity].
                        context.startActivity(TransferActionsActivity.intent(context, transfer))
                    },
                    // The host the Add-forward form will name at launch. It used to be reached for at
                    // *confirmation* time by the `onAddForward` lambda that stood here, which is how a
                    // forward ended up on whichever server the user had switched to while the form was
                    // open — see [openConfirmedForward].
                    activeHost = activeHost,
                    onStopForward = viewModel::stopForwarding,
                    onImportVault = { pickerActive = true; importPicker.launch(arrayOf("*/*")) },
                    onImportAccount = { pickerActive = true; accountImportPicker.launch(arrayOf("*/*")) },
                    onImportSshConfig = { pickerActive = true; configImportPicker.launch(arrayOf("text/plain", "*/*")) },
                    onBiometric = ::setBiometric,
                    onDarkTheme = viewModel::setDarkTheme,
                    onTerminalKeyRow = viewModel::setTerminalKeyRowVisible,
                    onTerminalFontSize = viewModel::setTerminalFontSize,
                    onLegacyAlgorithms = viewModel::setLegacyAlgorithms,
                    onBlockScreenshots = viewModel::setBlockScreenshots,
                    onReconnectAskFirst = viewModel::setReconnectAskFirst,
                    onTerminalTheme = viewModel::setTerminalTheme,
                    onTerminalKeepSystemBars = viewModel::setTerminalKeepSystemBars,
                    // The shell is the one screen that must not be inset by this Scaffold. Its own
                    // padding comes from `safeDrawingPadding` inside the terminal, and applying both
                    // would inset the grid twice - once for a navigation bar that is not there and
                    // again for the window - costing rows the pty was told it had.
                    modifier = if (terminalSessionOpen) Modifier else Modifier.padding(padding),
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

    // The host details sheet and the port-forwarding manager that stood here are windows of their
    // own now ([HostDetailsActivity] and [PortForwardManagerActivity]), and what they answer comes
    // back through [ActionRequests] on the workspace's next resume rather than through this
    // composition - see [onResume]. Both subjects carry a snapshot of the half of themselves the
    // workspace owns, which is why they are opened with a token rather than an id.
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
    rdpDesktopHostId?.let { hostId ->
        // Same live-profile resolution as the VNC dialog above, for the same reasons. Connect is
        // the VNC dialog's Connect exactly: the save through the view model, then the jump into
        // the viewer window through the same token handoff every RDP desktop opens by.
        state.hosts.firstOrNull { it.id == hostId }?.let { host ->
            RdpConfigDialog(
                host = host,
                onSave = { viewModel.saveRdpTarget(hostId, it) },
                onOpen = { target ->
                    rdpDesktopHostId = null
                    openRdpDesktop(host, target)
                },
                onDismiss = { rdpDesktopHostId = null },
            )
        }
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
                    openHostForm(context, failedHost)
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
    // The key-generation dialog stood here, and with it the two chained pickers that wrote the pair
    // out. All of it is [KeyGenActivity]'s now: the algorithm chooser, the off-main-thread generation
    // the comment used to justify, and the hold across the private-key write that kept the PIN lock
    // from re-arming in the gap before the public-key picker opened. The two pickers went with the
    // dialog rather than staying behind, because nothing else ever launched them - the private-key
    // picker was the dialog's only caller and the public-key one only ran from inside its callback.
    //
    // The editor request below is a different flow and stays: it travels through the process-wide
    // handoff for reasons of its own.
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
    // The file preview's sheet stood here, and its Edit button with it. Both are the preview
    // window's now: it is opened where the file was, and its Edit hands the editor the same entry
    // and provider through the same one-shot handoff this workspace uses. Nothing is reported back
    // either way — a file the editor saves is read again by whoever lists it.
    // The View Archive browser, a full-window layer above the workspace for the same reason the
    // editor is one: browsing an archive is a task of its own, and a sheet over the explorer would
    // both fight the explorer's own bottom sheets and show one folder's worth of a 1M-entry tree in
    // a window measured for a file list. The layer reads the browser's state; dismissal is the
    // close above, which cancels the scan/watcher and releases the channel.
    // The per-entry window was a sheet held here, above the browser, because it acts on this
    // workspace's clipboard and extract destination rather than on anything the browser knows. It
    // is a window of its own now (see [ArchiveEntryActionsActivity]) and the window holds nothing;
    // what stays is the *target* - which row it was opened on - because the answer carries no entry
    // (see [ActionAnswer]) and this is the only place that knows which row the user pressed.
    var archiveEntryAction by remember { mutableStateOf<ArchiveEntry?>(null) }
    var archiveEntryProperties by remember { mutableStateOf<ArchiveEntry?>(null) }
    var showArchiveProperties by remember { mutableStateOf(false) }
    // The extract that is waiting on the user to pick a destination folder. The entries are held
    // here - outside the browser, like every other per-entry action target - because the picker
    // is an activity result that lands in this workspace's scope, not the browser's; when it
    // returns, the extract runs against the browser's byte source, which is why the target holds
    // both. Null entry list = nothing pending.
    var pendingExtract by remember { mutableStateOf<Pair<ArchiveTarget, List<ArchiveEntry>>?>(null) }
    // The SAF folder picker for extraction. Unlike the explorer's Local root picker, this one
    // takes no persistable grant and changes no setting: the destination is a one-shot answer to
    // "where should these files land", and remembering it silently would make the second extract
    // write somewhere the user forgot they picked weeks ago.
    val archiveExtractPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val pending = pendingExtract
        pendingExtract = null
        if (uri == null) return@rememberLauncherForActivityResult
        val (target, entries) = pending ?: return@rememberLauncherForActivityResult
        val browser = target.browser
        scope.launch {
            val destination = runCatching { SafArchiveDestination(context, uri) }
                .getOrElse {
                    viewModel.reportUiMessage("The picked folder cannot be written")
                    return@launch
                }
            viewModel.reportUiMessage("Extracting ${entries.size} item(s) from \"${browser.archiveName}\"…")
            val outcomes = ArchiveExtractor.extract(
                browser.format,
                browser.sourceForReading(),
                entries,
                destination,
            )
            reportExtractOutcome(browser.archiveName, outcomes)
        }
    }
    archiveTarget?.let { target ->
        val browser = target.browser
        // The preview used to be a sheet in this layer, held as a target like the sheets that act on
        // an entry. It is a window of its own now (see [ArchiveEntryPreviewActivity]), so opening
        // one is a launch rather than a state change — and the reader it is launched with closes
        // over *this* browser, so the bytes still come from the archive the user has open.
        val openEntryPreview: (ArchiveEntry) -> Unit = { entry ->
            context.startActivity(
                ArchiveEntryPreviewActivity.intent(
                    context = context,
                    entry = entry,
                    // Only ZIP can fetch one entry's bytes by range; a TAR entry gets no reader,
                    // and the window says so rather than streaming the whole archive to show it.
                    readEntry = if (ArchiveReader.supportsRandomAccess(browser.format)) {
                        { ArchiveReader.readEntry(browser.format, browser.sourceForReading(), entry) }
                    } else null,
                ),
            )
        }
        ArchiveBrowserScreen(
            archiveName = browser.archiveName,
            state = browser.state,
            actions = ArchiveActions(
                onClose = { browser.close(); archiveTarget = null },
                onCancelScan = browser::cancelScan,
                onRetry = browser::retry,
                onUnlock = browser::unlock,
                // Opening a file is the preview; only the ZIP format can fetch one entry's bytes
                // by range, so a TAR entry falls to the action window's honest Extract verb rather
                // than a preview that would secretly stream the whole archive.
                onOpenEntry = { entry ->
                    if (ArchiveReader.supportsRandomAccess(browser.format)) {
                        openEntryPreview(entry)
                    } else {
                        archiveEntryAction = entry
                        context.startActivity(
                            ArchiveEntryActionsActivity.intent(context, entry, canReadEntry = false),
                        )
                    }
                },
                onEntryActions = { entry ->
                    // The same verdict the sheet was given, and the same one the preview row above
                    // was decided by: a range read exists only for ZIP, and only for a file.
                    archiveEntryAction = entry
                    context.startActivity(
                        ArchiveEntryActionsActivity.intent(
                            context = context,
                            entry = entry,
                            canReadEntry = ArchiveReader.supportsRandomAccess(browser.format) && !entry.isDirectory,
                        ),
                    )
                },
                // The batch extract from the browser's selection bar: the same pendingExtract +
                // picker the per-entry sheet's Extract row feeds, so there is one extract path,
                // not two. The browser keeps its selection behind the picker; a cancelled pick
                // returns to it intact for a retry.
                onExtractEntries = { entries ->
                    pendingExtract = target to entries
                    archiveExtractPicker.launch(null)
                },
                onReload = browser::reload,
                onDismissServerChange = browser::dismissServerChange,
                onShowProperties = { showArchiveProperties = true },
            ),
        )
        // The entry preview's sheet stood here, and so did the action sheet's. Both are windows now:
        // the preview reads through the same ranged source the scan did - one entry's bytes, never
        // the archive around it - and the actions are answered back into this layer, below.
        // What the entry action window answered, acted on here rather than at the workspace's own
        // drain below, because everything it needs - the browser's format, this workspace's clipboard,
        // the extract picker and the properties dialog - is in scope in this layer and nowhere else.
        // Two effects keyed on the same answer is safe: this one consumes only its own kind, and the
        // other ignores this kind, so exactly one of them acts and exactly one clears the slot.
        LaunchedEffect(pendingAction) {
            val answer = pendingAction as? ActionAnswer.ArchiveEntryAction ?: return@LaunchedEffect
            val entry = archiveEntryAction ?: return@LaunchedEffect
            when (answer.action) {
                // Preview only where the range read exists - the same condition the window drew its
                // Preview row by, resolved here because a TAR entry has no reader to hand it.
                ArchiveEntryActionKind.PREVIEW ->
                    if (ArchiveReader.supportsRandomAccess(browser.format) && !entry.isDirectory) {
                        openEntryPreview(entry)
                    } else {
                        viewModel.reportUiMessage("${entry.path} cannot be previewed in this archive format")
                    }
                // Extract, and single-entry Download under its other name: hand the entry to the
                // destination picker, and the extract itself runs when the picker answers. The
                // archive stays remote throughout - what moves is the entry's own bytes.
                ArchiveEntryActionKind.EXTRACT -> {
                    pendingExtract = target to listOf(entry)
                    archiveExtractPicker.launch(null)
                }
                // The path *inside* the archive, which is the archive's own coordinate system and
                // what somebody pasting it beside the archive in a shell is addressing.
                ArchiveEntryActionKind.COPY_PATH -> viewModel.copyToClipboard(entry.path)
                ArchiveEntryActionKind.PROPERTIES -> archiveEntryProperties = entry
            }
            archiveEntryAction = null
            onActionHandled()
        }
        archiveEntryProperties?.let { entry ->
            ArchiveEntryPropertiesDialog(
                entry = entry,
                formatLabel = browser.format.label,
                onDismiss = { archiveEntryProperties = null },
            )
        }
        val readyState = browser.state
        if (showArchiveProperties && readyState is ArchiveUiState.Ready) {
            ArchivePropertiesDialog(
                state = readyState,
                remotePath = browser.remotePath,
                onDismiss = { showArchiveProperties = false },
            )
        }
    }
    // What the windows that used to be sheets over this workspace answered, acted on here for the
    // same reason the deep link above is: the answer needs something only this composition has. The
    // view-model calls are the easy half; View/Edit/Open resolve a provider from the transfer and then
    // open a window with it, and `editorRequest` and the preview launcher are this workspace's own.
    //
    // Two kinds of answer are deliberately *not* acted on here. `FileAction` and `SnippetAction` are
    // handled by the screen that opened the window - the explorer and the terminal, each of which is
    // the only place holding the row the answer is about - and they consume themselves, so this drain
    // must leave the slot alone rather than clear it out from under them. That is safe rather than
    // racy: the workspace is *stopped* while one of these windows is in front of it, so the screen
    // that launched it is still composed when its answer arrives, and its own `LaunchedEffect` fires
    // on the same recomposition this one does.
    //
    // Consumed at the end, exactly as the deep link is, so a recomposition cannot run one answer
    // twice. `takeAnswer` has already emptied the slot, so the worst a lost frame can do is drop an
    // action the user would have seen had they waited - which is the right way round for a command.
    LaunchedEffect(pendingAction) {
        val answer = pendingAction ?: return@LaunchedEffect
        if (answer is ActionAnswer.TransferAction) {
            // Resolved from the list the window was opened from rather than trusted as an id: the item
            // is live, and one that has since left the list has nothing left to act on.
            val transfer = state.transfers.firstOrNull { it.id == answer.transferId }
            if (transfer == null) {
                viewModel.reportUiMessage("That transfer is no longer in the list")
            } else when (answer.action) {
                TransferActionKind.PAUSE -> viewModel.pauseTransfer(transfer.id)
                TransferActionKind.RESUME -> viewModel.resumeTransfer(transfer.id)
                TransferActionKind.CANCEL -> viewModel.cancelTransfer(transfer.id)
                TransferActionKind.RUN_NOW -> viewModel.runTransferNow(transfer.id)
                // The five below are why these answers come back here at all - see [ActionAnswer].
                TransferActionKind.VIEW_FILE ->
                    transferLocalTarget(context, transfer)?.let { target ->
                        context.startActivity(FilePreviewActivity.intent(context, target.entry, target.provider))
                    } ?: viewModel.reportUiMessage("${transfer.name} has no local file to view")
                TransferActionKind.EDIT_FILE ->
                    transferLocalTarget(context, transfer)?.let { editorRequest = EditorRequest(it.entry, it.provider) }
                        ?: viewModel.reportUiMessage("${transfer.name} has no local file to edit")
                TransferActionKind.OPEN_FILE ->
                    openTransferFileExternally(context, transfer, choose = false, onNoApp = viewModel::reportUiMessage)
                TransferActionKind.OPEN_FILE_WITH ->
                    openTransferFileExternally(context, transfer, choose = true, onNoApp = viewModel::reportUiMessage)
                TransferActionKind.COPY_DETAILS -> viewModel.copyToClipboard(transferDetails(transfer))
            }
        }
        // The forwarding manager's own two halves. Both resolve the host from the live list first,
        // for the reason the transfer above does: a host removed while its manager was open has no
        // rules left to start, and the save path would otherwise write a column for a host that is
        // gone. The rules themselves are the window's whole new list rather than a delta it computed
        // - that is the engine's own contract, and it is what makes its stop-the-removed behaviour
        // apply to this window exactly as it applies to a connect.
        if (answer is ActionAnswer.ForwardRuleAction) {
            val host = state.hosts.firstOrNull { it.id == answer.hostId }
            if (host == null) {
                viewModel.reportUiMessage("That host is no longer configured, so no rule was changed")
            } else when (answer.action) {
                ForwardRuleKind.START -> viewModel.startForwardRule(host.id, answer.ruleId)
                // No host id: the engine's stop is keyed on the rule, because a tunnel is stopped
                // where it was started and the rule already knows which host it belongs to.
                ForwardRuleKind.STOP -> viewModel.stopForwardRule(answer.ruleId)
            }
        }
        if (answer is ActionAnswer.ForwardRules) {
            if (state.hosts.none { it.id == answer.hostId }) {
                viewModel.reportUiMessage("That host is no longer configured, so no rule was saved")
            } else {
                viewModel.saveForwardRules(answer.hostId, answer.rules)
            }
        }
        // The host details window's two rows. Both are view-model calls and neither is a read, which
        // is the whole reason they are answers: forgetting the credentials also unregisters the
        // host's live session, and a stats refresh is a connection this workspace owns.
        if (answer is ActionAnswer.HostDetailsAction) {
            val host = state.hosts.firstOrNull { it.id == answer.hostId }
            if (host == null) {
                viewModel.reportUiMessage("That host is no longer configured")
            } else when (answer.action) {
                HostDetailsKind.FORGET_CREDENTIALS -> viewModel.forgetCredentials(host)
                HostDetailsKind.REFRESH_STATS -> viewModel.refreshStats(host)
            }
        }
        // Cleared for every kind this drain owns, and for the archive's too, which is drained in its
        // own layer above. Left alone for the two the explorer and the terminal consume themselves -
        // clearing it here would race the screen whose answer it is.
        if (answer !is ActionAnswer.FileAction && answer !is ActionAnswer.SnippetAction) onActionHandled()
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
    /**
     * The Linux userspace's own state and actions, for the Settings section and the host-list card.
     * Passed whole for the same reason [filesExplorer] is: its state changes on every install step
     * and every lifecycle verb, and reading it here would recompose this scaffold for the sake of a
     * section only the Settings destination shows.
     */
    linuxUserspace: LinuxUserspaceController,
    /**
     * The host list's "Local Ubuntu 22.04" card, as a flow for the same reason [frames] is: it is
     * null until the userspace is installed and healthy, and reading it here would recompose this
     * scaffold for a card only the Hosts destination shows.
     */
    localLinuxCard: StateFlow<HostProfile?>,
    /**
     * The answer a window just handed back, and the way to say it has been acted on.
     *
     * Passed down rather than acted on here because the two screens below are the only places that
     * hold the row their window was opened on: `FileAction` names a verb and nothing else, and only
     * the explorer knows which entry was long-pressed, exactly as only the terminal knows which
     * session a snippet is typed into. Both screens ignore an answer that is not theirs and consume
     * the one that is - see the drain in [EclipseWorkspace] for why that is safe rather than racy.
     */
    pendingAction: ActionAnswer? = null,
    onActionHandled: () -> Unit = {},
    /** Opens a file in the full-window preview - see the overlay state in [EclipseWorkspace]. */
    onPreviewFile: (FsEntry, FileSystemProvider) -> Unit = { _, _ -> },
    /** Opens a file in the full-window editor - see the overlay state in [EclipseWorkspace]. */
    onEditFile: (FsEntry, FileSystemProvider) -> Unit = { _, _ -> },
    /**
     * Opens a remote archive in the View Archive browser - see the overlay state in
     * [EclipseWorkspace]. Null-when-unsupported is decided by the caller (local session, unknown
     * extension) so this scaffold and the Files screen below it stay format-agnostic.
     */
    onOpenArchive: ((FsEntry, FileSystemProvider) -> Unit)? = null,
    onDestination: (Destination) -> Unit,
    onSearch: (String) -> Unit,
    onAddHost: () -> Unit,
    onConnect: (HostProfile) -> Unit,
    /**
     * Re-dials one session, by its key. The Reconnect action on a terminal tab and on a session
     * row - the two callers that already know which session they mean, where resolving by host
     * would land the dial on the host's *first* tab. [onConnect] is the host card and the host
     * list's kebab menu, which have no tab of their own and keep the first-tab resolution.
     */
    onReconnectSession: (String) -> Unit,
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
     * Opens one host's VNC desktop - the card menu's VNC Viewer item. A host with a saved,
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
    /**
     * Opens one host's RDP desktop - the card menu's RDP Viewer item, with the same routing the
     * VNC one has: a saved, enabled target goes straight to the viewer window, the rest get the
     * endpoint dialog first. Named `onRdpDesktop` rather than `onOpenRdpDesktop` for the same
     * reason [onRemoteDesktop] is.
     */
    onRdpDesktop: (HostProfile) -> Unit = {},
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
    onResizeTerminal: (String, TerminalViewport) -> Unit = { _, _ -> },
    onScrollTerminal: (String, Int) -> Unit = { _, _ -> },
    onScrollTerminalTo: (String, Int) -> Unit = { _, _ -> },
    onCopySelection: (String, TerminalSelection) -> Unit = { _, _ -> },
    onCopyTerminalText: (String) -> Unit = {},
    onPasteTerminal: (String) -> Unit = {},
    onSaveSnippet: (String, String) -> Unit = { _, _ -> },
    onSaveLogs: (String, String) -> Unit = { _, _ -> },
    /** Saves a session's raw output log - session key and the host name to name the file after. */
    onSaveSessionLog: (String, String) -> Unit = { _, _ -> },
    /** Arms the finished-command notification for a session key. */
    onNotifyWhenDone: (String) -> Unit = {},
    onSaveText: (String, String) -> Unit = { _, _ -> },
    onSaveScreen: (String, String) -> Unit = { _, _ -> },
    onClearCompleted: () -> Unit = {},
    onUpload: () -> Unit = {},
    onDownloadFile: (RemoteFile) -> Unit = {},
    /**
     * Copies userspace rows out to the device's own storage.
     *
     * A list rather than one entry, because the two entry points into it disagree about how many rows
     * they are moving — the selection bar's Download carries the whole selection, and a row's own
     * Transfer carries exactly one. Which picker answers is a list's question, not this screen's: see
     * the call site, where one file is offered a name and several are offered a folder.
     */
    onCopyToDevice: (List<FsEntry>) -> Unit = {},
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
    /** Long-press on a transfer card: opens [TransferActionsActivity]. */
    onOpenTransferActions: (TransferItem) -> Unit = {},
    /**
     * The host the Settings section's Add-forward form will open its tunnel through.
     *
     * Passed rather than reached for, because the form is a window of its own now: it has to be *named*
     * the host at the moment the user taps Add, and the name has to survive the trip out and back. The
     * alternative - resolving "whatever is active" when the user returns - is what the dialog did, and
     * it silently re-points a forward at a server the user switched to while the form was open.
     */
    activeHost: HostProfile? = null,
    onStopForward: (String) -> Unit = {},
    onImportVault: () -> Unit = {},
    onImportAccount: () -> Unit = {},
    onImportSshConfig: () -> Unit = {},
    onBiometric: (Boolean) -> Unit,
    onDarkTheme: (Boolean) -> Unit,
    onTerminalKeyRow: (Boolean) -> Unit = {},
    onLegacyAlgorithms: (Boolean) -> Unit = {},
    onBlockScreenshots: (Boolean) -> Unit = {},
    onReconnectAskFirst: (Boolean) -> Unit = {},
    /**
     * Pinch-to-zoom on the immersive terminal, which is *not* the Settings row of the same name.
     *
     * Six sibling callbacks - keep-alive, clipboard, font size's own row, terminal width, reconnect
     * delay and vault auto-lock - were deleted from this list when those rows became Activities, and
     * this one is the exception that shows why the deletion was per-callback rather than per-setting:
     * the terminal persists a pinch-to-zoom result through [TerminalScreen], from a window that has
     * no Activity of its own and no way to start one mid-gesture. The Settings row and the gesture
     * write the same field, so the Activity and this callback are two doors into one setting.
     */
    onTerminalFontSize: (Int) -> Unit = {},
    onTerminalTheme: (String) -> Unit = {},
    // The keep-system-bars switch was wired into SettingsScreen and both scaffold call sites, but
    // never into the scaffold's own parameter list, so all three references failed to resolve.
    onTerminalKeepSystemBars: (Boolean) -> Unit = {},
    // The shortcut bar's configuration blob and the diagnostics copy/save/clear trio stood here for
    // the same reason as the six above - to carry a dialog's answer back out to the clipboard and the
    // SAF picker this scaffold owns. All four rows have a window of their own now, and each of those
    // windows holds its own clipboard handle and launches its own picker, so this list has nothing
    // left to forward.
    // Six more callbacks stood here - onSetPin, onClearPin, verifyPin, onForgetKnownHost,
    // onClearKnownHosts and onForgetAllCredentials - and they existed for the same reason the six
    // above did: to carry a dialog's answer back to the view model. PIN, known hosts and saved
    // credentials are windows of their own now, and each writes to its store directly, so there is
    // nothing left here to forward. `verifyPin` still exists on the view model and is still passed to
    // the lock screen that gates app startup - that one is a different caller with a live need for it,
    // and this list was only ever one of its two.
) {
    // A shell owns the whole window, so it composes outside the Scaffold entirely: no top bar, no
    // Scaffold insets, nothing above the grid but the session strip. This is the branch the app enters
    // the moment a login succeeds, and what makes the terminal full screen rather than merely large.
    val openSession = state.tabs.firstOrNull { it.id == openSessionId }
    if (immersive && openSession != null) {
        TerminalScreen(
            state = state,
            frames = frames,
            pendingAction = pendingAction,
            onActionHandled = onActionHandled,
            onCloseTab = onCloseTab,
            onDisconnectAll = onDisconnectAll,
            // The tab's own key, straight to the dial rather than through the host: a host with two
            // shells resolves a host-routed reconnect to its *first* tab, which re-dialled the
            // wrong session and left the tab on screen parked at Disconnected. The dial asks for
            // whatever a resuming connection asks for, and a refusal still raises the retry prompt.
            onReconnectSession = onReconnectSession,
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
            onSaveLogs = onSaveLogs,
            onSaveSessionLog = onSaveSessionLog,
            onNotifyWhenDone = onNotifyWhenDone,
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
                    // The filter sits in the bar rather than as the first row of the column below,
                    // so it stays reachable while the host list scrolls - it filters that list, it
                    // is not an entry in it. It takes the title slot, which is the space the bar
                    // has going spare, and leaves the two actions where they were.
                    //
                    // Single-line by contract: the bar is one row tall, so a field that grew would
                    // either clip or push the actions out of it. The placeholder carries the
                    // ellipsis rather than the field, because on a narrow screen the placeholder is
                    // what gets truncated - the text the user types is theirs to scroll.
                    title = {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = onSearch,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    "Search hosts, tags, or usernames",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            shape = RoundedCornerShape(16.dp),
                            // Transparent so the field reads as part of the bar instead of as a
                            // second surface stacked on it; the outline still marks the hit area.
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = Color.Transparent,
                                focusedContainerColor = Color.Transparent,
                            ),
                        )
                    },
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
                    // The row's own session key, for the same reason the shell above routes by tab:
                    // the row tapped is the session that must come back, not the host's first one.
                    onReconnectSession = onReconnectSession,
                    // The session row knows its host's id; the manager above this scaffold wants the
                    // profile, which only the hosts flow can answer for.
                    onManageForwards = { hostId -> state.hosts.firstOrNull { it.id == hostId }?.let(onManageForwards) },
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
                    onOpenArchive,
                    onUpload,
                    onDownloadFile,
                    onCopyToDevice,
                    onPickLocalFolder,
                    onUploadLocal,
                    onScheduleDownload,
                    onScheduleUpload,
                    onSync,
                    onSendToHost,
                    // Last on purpose, and named: what came back from a window is not part of the
                    // explorer's own vocabulary of actions, and it is the caller's to supply.
                    pendingAction = pendingAction,
                    onActionHandled = onActionHandled,
                )
            }
            return@Scaffold
        }
        Column(Modifier.padding(padding).fillMaxSize().widthIn(max = 1280.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            when (destination) {
                Destination.HOSTS -> HostsScreen(
                    state, localLinuxCard, onAddHost, onConnect, onShowDetails, onEditHost,
                    onRemoveHost, onToggleFavoriteHost, onExportAccount, onDuplicateHost,
                    onManageForwards, onRemoteDesktop, onWakeOnLan, onRdpDesktop,
                )
                // Both handled above, outside the scrolling column, because both are measured.
                Destination.TERMINAL, Destination.FILES -> Unit
                Destination.TRANSFERS -> TransfersScreen(
                    state.transfers, onClearCompleted, onPauseTransfer, onResumeTransfer, onCancelTransfer,
                    onPauseAllTransfers, onResumeAllTransfers, onCancelAllTransfers, onRunTransferNow,
                    onOpenTransferActions,
                )
                Destination.SETTINGS -> SettingsScreen(
                    state, activeHost, linuxUserspace, onBiometric, onDarkTheme, onStopForward,
                    onImportVault,
                    onLegacyAlgorithms = onLegacyAlgorithms,
                    onBlockScreenshots = onBlockScreenshots,
                    onReconnectAskFirst = onReconnectAskFirst,
                    onTerminalTheme = onTerminalTheme,
                    onTerminalKeepSystemBars = onTerminalKeepSystemBars,
                    onImportSshConfig = onImportSshConfig,
                )
            }
        }
    }
}

@Composable
private fun HostsScreen(
    state: MainUiState,
    localLinuxCard: StateFlow<HostProfile?>,
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
    onRdpDesktop: (HostProfile) -> Unit,
) {
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
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
    // The local Ubuntu card, when the userspace is installed and healthy. Collected here rather
    // than above so the flow's WhileSubscribed window opens exactly when the Hosts destination is
    // on screen; the flow is null in every state that cannot keep the card's promise (not
    // installed, mid-install, broken, never probed), so there is no removal code anywhere — the
    // card is derived, never registered.
    val localCard by localLinuxCard.collectAsStateWithLifecycle()
    if (localCard != null) {
        LocalUbuntuCard(localCard!!, onConnect)
        Spacer(Modifier.height(12.dp))
    }
    if (visibleHosts.isEmpty() && localCard == null) {
        EmptyState("No hosts found", "Try another search or add your first connection.", onAddHost)
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            visibleHosts.forEach { host ->
                HostCard(host, onConnect, onShowDetails, onEditHost, onRemoveHost, onToggleFavoriteHost, onExportAccount, onDuplicateHost, onManageForwards, onRemoteDesktop, onWakeOnLan, onRdpDesktop)
            }
        }
    }
}

/**
 * The "Local Ubuntu 22.04" card: the userspace's front door, rendered only while the environment
 * is installed and healthy (the flow feeding it is null in every other state).
 *
 * Shaped exactly like a [HostCard] — the same corner radii, the same paddings, the same one-tap
 * connect — because the promise is the same: one tap, one terminal. What it deliberately does not
 * have is the kebab menu; there is nothing to edit or remove here, and the environment's controls
 * live in Settings → Linux userspace where its whole lifecycle is visible. The trailing play
 * affordance carries the card's name for the same screen-reader reason every HostCard control
 * does: "Open terminal" alone describes the action, and the row names the environment.
 */
@Composable
private fun LocalUbuntuCard(host: HostProfile, onConnect: (HostProfile) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onConnect(host) },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(host.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Ubuntu 22.04 on this device · tap to open a terminal", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.PlayArrow, "Open terminal on ${host.name}", tint = MaterialTheme.colorScheme.primary)
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
    onRdpDesktop: (HostProfile) -> Unit,
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
                        // Named for the protocol it dials, not for the umbrella: "Remote desktop"
                        // was unambiguous while VNC was the only protocol, and became the parent
                        // of the item below the day RDP landed beside it - two entries whose
                        // labels read as one thing offered twice.
                        DropdownMenuItem(
                            text = { Text("VNC Viewer") },
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
                        // Beside the VNC item, and un-gated the way that one is now that the
                        // viewer exists: a host with no saved RDP target gets the config dialog
                        // rather than nothing, so this item is the protocol's first-use entry
                        // point too - a saved, enabled target goes straight to the viewer, and
                        // the routing for both lives with [requestRdpDesktop].
                        DropdownMenuItem(
                            text = { Text("RDP Viewer") },
                            leadingIcon = { Icon(Icons.Default.DesktopWindows, null) },
                            onClick = { menuOpen = false; onRdpDesktop(host) },
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
    /** Re-dials one session - the key is a tab id, never a host id. */
    onReconnectSession: (String) -> Unit,
    onSendInput: (String, String) -> Unit,
    onSendText: (String, String) -> Unit,
    onSendKey: (String, TerminalKey, Boolean, Boolean, Boolean) -> Unit,
    onSendChar: (String, Char, Boolean, Boolean) -> Unit,
    onResize: (String, TerminalViewport) -> Unit,
    onScroll: (String, Int) -> Unit,
    onScrollTo: (String, Int) -> Unit,
    onCopySelection: (String, TerminalSelection) -> Unit,
    onCopyText: (String) -> Unit,
    onPaste: (String) -> Unit,
    onSaveSnippet: (String, String) -> Unit,
    onSaveLogs: (String, String) -> Unit,
    /** Saves the raw session log - see MainViewModel.sessionLogText. */
    onSaveSessionLog: (String, String) -> Unit,
    /** Arms the finished-command notification - see MainViewModel.notifyWhenDone. */
    onNotifyWhenDone: (String) -> Unit,
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
    /**
     * The answer a window just handed back, and the way to say it has been acted on.
     *
     * Only [ActionAnswer.SnippetAction] is this screen's, and it is this screen's because an insert
     * is two things only the terminal has: the session on screen, which decides which shell the
     * command is typed into, and the focus host the keyboard goes up over afterwards. Every other
     * kind is ignored here and left for whoever owns it.
     */
    pendingAction: ActionAnswer? = null,
    onActionHandled: () -> Unit = {},
    /** Persists a pinch-to-zoom result, so the size the user settled on survives leaving the screen. */
    onFontSize: (Int) -> Unit = {},
    /** Persists the shortcut bar's collapsed state, for the same reason. */
    onKeyRowVisible: (Boolean) -> Unit = {},
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showCommandBar by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    // For the one thing this screen launches rather than draws: the snippets window.
    val context = LocalContext.current
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
            // The session's own key, not its host's: with two shells on one host, the tab on screen
            // is the one that must come back, and a host id would resolve to the host's first tab.
            onReconnect = { onReconnectSession(activeTab.id) },
            onToggleSearch = { showSearch = !showSearch },
            onToggleCommandBar = { showCommandBar = !showCommandBar },
            onToggleHistory = { showHistory = !showHistory },
            // A window rather than a sheet over this grid: the saved commands are only worth offering
            // while the command line they will land on is legible, and a sheet is a slot at the
            // bottom of the screen - exactly where the grid's own last lines are. It carries nothing:
            // the list is the snippet store, which that activity injects and watches directly.
            onSnippets = { context.startActivity(SnippetsActivity.intent(context)) },
            onSaveLogs = { onSaveLogs(activeTab.hostId, terminalText) },
            // The session's own key, not the host: a host with two shells has two logs, and the
            // tab's title is the host name the file should be called after.
            onSaveSessionLog = { onSaveSessionLog(activeTab.id, activeTab.title) },
            onNotifyWhenDone = { onNotifyWhenDone(activeTab.id) },
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
            // The strip draws on the terminal's own background; these two are where every chrome
            // colour on it is derived from. See TerminalTabStrip's parameter docs.
            termBg = termBg,
            termFg = termFg,
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
                    // The app-wide height, which is a floor the view scrolls through — see [atLeastRows].
                    // A taller terminal is a taller pty, not a taller screen: the shell prints without
                    // paging and the window follows the cursor.
                    minRows = state.settings.terminalRows,
                    // The host's height over it, which can only shorten — see [atMostRows]. This is the
                    // one place a per-host value beats the app-wide one, and the only way to tell a single
                    // server that the window is short.
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
                    onViewportChange = { viewport -> onResize(activeTab.id, viewport) },
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
            // Decoded here rather than in the ViewModel so the terminal never waits on a settings
            // read to know what its bar looks like: the blob is in the UI state, the codec is pure
            // and never throws, and a damaged blob renders the default bar.
            val keyBarPrefs = remember(state.settings.terminalKeyBarJson) {
                KeyBarPrefsCodec.decode(state.settings.terminalKeyBarJson).withMissingStandardCaps()
            }
            TerminalKeyRow(
                latches = latches,
                prefs = keyBarPrefs,
                onKey = { key, ctrl, alt, shift ->
                    onSendKey(activeTab.id, key, ctrl, alt, shift)
                    // Every cap is a clickable surface, and a clickable surface is focusable: a tap can
                    // leave the IME host unfocused, after which the software keyboard's characters and a
                    // hardware keyboard's keys both have nowhere to go while the row itself still works.
                    // A no-op when the field already has focus, which is the usual case.
                    if (!inputFocused) runCatching { focusRequester.requestFocus() }
                },
                onText = { text -> onSendText(activeTab.id, text) },
                onChar = { char, ctrl, alt -> onSendChar(activeTab.id, char, ctrl, alt) },
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

    // What the snippets window answered. Drained here rather than in the workspace because both of
    // these rows need something only this screen has: an insert is typed into the session on screen
    // and then has to raise the keyboard over this screen's own focus host, and the save row names
    // the command this screen's command bar is holding. Delete is the one row that does *not* come
    // through here - the window makes that write itself, which is what lets the row it removed go
    // immediately rather than when the workspace comes back to the front.
    LaunchedEffect(pendingAction) {
        val answer = pendingAction as? ActionAnswer.SnippetAction ?: return@LaunchedEffect
        // Read from the list this screen is showing - the same store the window drew its rows from -
        // rather than from the answer, which carries an id and nothing more. The row cannot have gone
        // between the tap and this line: the workspace is stopped while the window is in front of it.
        val snippet = answer.snippetId?.let { id -> state.snippets.firstOrNull { it.id == id } }
        when (answer.action) {
            SnippetActionKind.INSERT -> snippet?.let {
                // Typed into the remote shell rather than into a form, so the shell's own line
                // editing applies: the snippet arrives on the command line where it can be
                // corrected before Enter, which is what a snippet is for. Deliberately no newline.
                onSendText(activeTab.id, it.command)
                showKeyboard()
            }
            // The naming dialog stays here rather than moving into the window, and this is why the
            // row is an answer at all: it names the command the user has typed, which is live text
            // in this composition - a copy of it taken at launch would be a name for the wrong line
            // if the user typed anything after opening the window.
            SnippetActionKind.SAVE_CURRENT -> showSaveSnippet = true
        }
        onActionHandled()
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
    /** Re-dials one session - the key is a tab id, never a host id. */
    onReconnectSession: (String) -> Unit,
    /** Opens this session's host's port-forwarding manager - the forward note on its row. */
    onManageForwards: (String) -> Unit,
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
                    onOpen = { onOpenSession(tab) },
                    // The row's own session key, for the same reason the shell above routes by tab.
                    onReconnect = { onReconnectSession(tab.id) },
                    onManageForwards = { onManageForwards(tab.hostId) },
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
    /** Opens the host's port-forwarding manager; the forward note is the smaller target inside the row. */
    onManageForwards: () -> Unit,
    onClose: () -> Unit,
) {
    // The "why?" window is an Activity now, so this row needs a context and nothing else: the trace it
    // used to be handed, and the copy callback it used to forward, both belong to that window.
    val context = LocalContext.current
    // This row's X kills a session exactly as the tab strip's does, so it asks exactly as the strip's
    // does. The list is where a session that dropped while the app was elsewhere gets discovered, which
    // is also where a close tap is most likely to be aimed at the wrong row.
    var confirmClose by remember { mutableStateOf(false) }
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
                    onClick = { context.startActivity(SessionWhyActivity.intent(context, tab)) },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    // Named by session, because there is one of these per row.
                    modifier = Modifier.semantics { contentDescription = "Why ${tab.title} is ${tab.state.name}" },
                ) {
                    Text("Why?", style = MaterialTheme.typography.labelMedium)
                }
            }
            IconButton(onClick = { confirmClose = true }) {
                Icon(Icons.Default.Close, "Close ${tab.title} session", modifier = Modifier.size(18.dp))
            }
        }
    }
    if (confirmClose) {
        ConfirmCloseSessionDialog(
            tab = tab,
            onConfirm = { confirmClose = false; onClose() },
            onDismiss = { confirmClose = false },
        )
    }
}

/**
 * The confirmation both close buttons in the Terminal tab ask.
 *
 * Shared rather than written twice, because the tab strip's X and the session row's X are one decision
 * made in two places: each kills a live shell, each sits a thumb-width from the control the user was
 * aiming for, and the session list — where a session that dropped while the app was elsewhere gets
 * discovered — is where a close tap is most likely to land on the wrong row. Two copies of that
 * wording would drift the moment either was reworded.
 *
 * App-themed on purpose, unlike the strip's own controls: an `AlertDialog` is a modal surface with its
 * own scrim, not a control drawn on the terminal's background, so it follows the palette every other
 * dialog in the app follows.
 */
@Composable
private fun ConfirmCloseSessionDialog(
    tab: SessionTab,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Close \"${tab.title}\"?") },
        text = {
            Text(
                if (tab.state.isLive) {
                    "The session is still running. Closing it ends the shell on the server; nothing is saved on the way out."
                } else {
                    "Close this session's tab?"
                }
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Close session") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
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
    /** Saves the raw session log; the item is hidden until the session has output to save. */
    onSaveSessionLog: () -> Unit,
    /** Arms the finished-command notification for the session on screen. */
    onNotifyWhenDone: () -> Unit,
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
    /**
     * The terminal's own background and foreground, the pair the strip sits on. Every chrome colour
     * here is derived from these rather than from the app theme because the strip is drawn on top of
     * [termBg]: an icon that follows the app theme is invisible on any terminal whose brightness
     * disagrees with it - the app's dark scheme over the Light terminal theme is white icons on a
     * near-white bar.
     */
    termBg: Color,
    termFg: Color,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // The tab whose X was tapped, while the confirmation below is on screen. Closing a session kills
    // a live shell, and the X sits a thumb-width from the chip a user is aiming for - the same reason
    // Remove on a host card asks first.
    var confirmClose by remember { mutableStateOf<SessionTab?>(null) }
    Row(
        Modifier.fillMaxWidth().padding(start = 2.dp, end = 4.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The way back to the list of sessions, and the only visible one: a full-screen shell has no
        // navigation bar behind it. The system back gesture does the same thing.
        IconButton(onClick = onLeaveSession) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Show sessions", tint = termFg)
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
                    // Terminal-derived, like everything else on this strip: the app theme's
                    // primaryContainer is a fixed mid-blue that vanishes on both a black terminal
                    // (the High-contrast theme) and a pale one (Light, Solarized light). The active
                    // chip is a blend of the terminal's own pair - distinct from the background on
                    // every theme because it leans on the foreground, which the theme chose for
                    // exactly that - and an inactive chip is a whisper of the foreground over the
                    // background.
                    color = if (tab == activeTab) blend(termBg, termFg, 0.22f) else blend(termBg, termFg, 0.08f),
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
                            // The chip's own text follows the terminal pair too - see the Surface
                            // colour above for why the app theme cannot decide this.
                            color = termFg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { confirmClose = tab }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close ${tab.title} session", tint = termFg, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        IconButton(onClick = onToggleSearch) { Icon(Icons.Default.Search, "Search terminal", tint = termFg) }
        Box {
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Terminal actions", tint = termFg) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Duplicate terminal") },
                    onClick = { menuOpen = false; onDuplicate(activeTab) },
                )
                // Only on a live session: the wait is for a command to *finish*, and a session
                // that has already ended cannot finish anything. The notification the arming
                // eventually posts is one-shot - the detector disarms itself when it fires.
                if (activeTab.state.isLive) {
                    DropdownMenuItem(
                        text = { Text("Notify when done") },
                        onClick = { menuOpen = false; onNotifyWhenDone() },
                    )
                }
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
                // Only once there is something to save: the session log is created with the
                // session, so on a shell that has not spoken yet the item would offer an empty
                // file. `terminalText` is the observable proxy for "this session has output" -
                // it is what the transcript view and the other saves read.
                if (terminalText.isNotBlank()) {
                    DropdownMenuItem(text = { Text("Save session log") }, onClick = { menuOpen = false; onSaveSessionLog() })
                }
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
            TextButton(
                onClick = onReconnect,
                contentPadding = PaddingValues(horizontal = 8.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = termFg),
            ) {
                Icon(Icons.Default.Wifi, null, tint = termFg, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Reconnect", style = MaterialTheme.typography.labelMedium)
            }
        }
        // Offered while a recovery is still running as well as after one has given up, because a ladder
        // that is halfway through its five attempts is exactly when a user wants to know what it is
        // answering - and the two-line status above can only ever show the latest sentence. See
        // [SessionWhyActivity].
        if (activeTab.state.explainable) {
            TextButton(
                onClick = { context.startActivity(SessionWhyActivity.intent(context, activeTab)) },
                contentPadding = PaddingValues(horizontal = 8.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = termFg),
                modifier = Modifier.semantics { contentDescription = "Why this session is ${activeTab.state.name}" },
            ) {
                Text("Why?", style = MaterialTheme.typography.labelMedium)
            }
        }
        if (frame.columns > 0) {
            Text(
                "${frame.columns}x${frame.rows}",
                style = MaterialTheme.typography.labelSmall,
                // The terminal pair, dimmed, not the app theme's onSurfaceVariant - this sits on
                // [termBg] like everything else in the strip.
                color = termFg.copy(alpha = 0.7f),
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
            // Drawn on the terminal's own background like the rest of the strip: the app theme's
            // field colours assume the app surface and produce a pale field on a black terminal or
            // an invisible outline on a light one.
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = termFg,
                unfocusedTextColor = termFg,
                cursorColor = termFg,
                focusedBorderColor = termFg,
                unfocusedBorderColor = termFg.copy(alpha = 0.5f),
                focusedPlaceholderColor = termFg.copy(alpha = 0.6f),
                unfocusedPlaceholderColor = termFg.copy(alpha = 0.6f),
            ),
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
                color = termFg,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
    confirmClose?.let { tab ->
        ConfirmCloseSessionDialog(
            tab = tab,
            onConfirm = { confirmClose = null; onCloseTab(tab) },
            onDismiss = { confirmClose = null },
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
 * [base] leaned [fraction] towards [toward]: the colour a terminal-derived chrome element uses when
 * it needs to sit on the terminal's background yet differ from it, on every terminal theme.
 *
 * The terminal themes are pairs the theme chose for maximum mutual contrast, so any blend of the two
 * is legible against both ends - which is what makes this safer than the app palette, whose fixed
 * values cannot know whether they landed on a black or a pale terminal.
 */
private fun blend(base: Color, toward: Color, fraction: Float): Color = Color(
    red = base.red + (toward.red - base.red) * fraction,
    green = base.green + (toward.green - base.green) * fraction,
    blue = base.blue + (toward.blue - base.blue) * fraction,
    alpha = 1f,
)

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
 * What the key picker will accept. The wildcard is last and is what actually matters: private keys
 * have no registered MIME type, so providers report them as anything from `text/plain` to
 * `application/octet-stream` to nothing at all, and a narrower filter greys out the very file the
 * user came to pick. The named types come first so pickers that group by type put text files on top.
 */
private val KEY_FILE_MIME_TYPES = arrayOf("application/octet-stream", "text/plain", "*/*")


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
    /** Null when View Archive cannot serve this entry (local session, unknown extension). */
    onOpenArchive: ((FsEntry, FileSystemProvider) -> Unit)?,
    onUpload: () -> Unit,
    onDownloadFile: (RemoteFile) -> Unit,
    /** Rows of the on-device userspace to copy out to this device; never a server's. */
    onCopyToDevice: (List<FsEntry>) -> Unit,
    onPickLocalFolder: () -> Unit,
    onUploadLocal: (LocalFile) -> Unit,
    onScheduleDownload: (List<RemoteFile>, Long, Long?) -> Unit,
    onScheduleUpload: (List<LocalFile>, Long, Long?) -> Unit,
    onSync: (SyncDirection) -> Unit,
    onSendToHost: (RemoteFile, HostProfile, String) -> Unit,
    /**
     * The answer a window just handed back, and the way to say it has been acted on.
     *
     * Only [ActionAnswer.FileAction] is this screen's. The answer names a verb and carries no entry,
     * because an entry is a row of the listing this screen holds and nothing an intent can carry -
     * so `actionEntry`, which is what the window was opened on, is what the verb is applied to. Every
     * other kind is ignored here and left for whoever owns it.
     */
    pendingAction: ActionAnswer? = null,
    onActionHandled: () -> Unit = {},
) {
    val explorer by filesExplorer.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // For the one thing this screen launches rather than draws: the per-entry actions window.
    val context = LocalContext.current
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
        // Upload is offered wherever the "other side" is somewhere a picked device file can land —
        // a server's SFTP tree or the userspace. Sync is not: it is a two-way reconciliation between
        // the device's shared storage and one host's tree, and the userspace has no host to
        // reconcile against.
        onUpload = onUpload.takeIf { !explorer.isLocal },
        onSync = { showSync = true }.takeIf { explorer.isSftp },
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
            onOpenActions = { entry ->
                // The row the window was opened on, kept here because the answer carries a verb and
                // no subject - see [ActionAnswer]. Held across the launch for the same reason the
                // extract target is: the window cannot be opened on one row and answer for another,
                // because the workspace has to come back to the front for a second one to open.
                actionEntry = entry
                context.startActivity(
                    FileActionsActivity.intent(
                        context = context,
                        entry = entry,
                        isLocal = explorer.isLocal,
                        isUbuntu = explorer.isUbuntu,
                        supportsPermissions = explorer.supportsPermissions,
                        // The row exists only when the session and the name can both be served: a
                        // local document tree has no ranged reads to browse with, the userspace has
                        // no SFTP channel to range-read over, and an unknown extension has no engine
                        // to browse with - hiding the verb beats offering it and failing.
                        canOpenArchive = onOpenArchive != null && explorer.isSftp &&
                            ArchiveReader.formatFor(entry.name) != null,
                    ),
                )
            },
        )
    }

    if (explorer.selection.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        ExplorerSelectionBar(
            count = explorer.selection.size,
            onClear = filesExplorer::clearSelection,
            // Three destinations, three mechanisms, and the bar has to know which it is showing:
            // the userspace copies to a folder the user picks (both ends are this device, so there
            // is no transfer to queue), a server goes through the SFTP pipeline one row at a time,
            // and the device's own storage has nothing to download *to* — its rows are already here.
            onDownload = when {
                explorer.isSftp -> {
                    // The bar works on the explorer's FsEntry rows; the transfer queue wants the
                    // backend-shaped RemoteFile those rows stand for.
                    { selectedEntries.map { it.toRemoteFile() }.forEach(onDownloadFile); filesExplorer.clearSelection() }
                }

                explorer.isUbuntu -> {
                    { onCopyToDevice(selectedEntries); filesExplorer.clearSelection() }
                }

                else -> null
            },
            onUpload = if (explorer.isLocal) {
                { selectedEntries.map { it.toLocalFile() }.forEach(onUploadLocal); filesExplorer.clearSelection() }
            } else null,
            // Scheduled transfers are a WorkManager queue keyed on a host. The userspace has no host
            // and both ends of its copy are the same disk, so there is nothing to schedule.
            onSchedule = when {
                explorer.isUbuntu -> null
                explorer.isLocal -> ({ scheduleLocal = true })
                else -> ({ scheduleRemote = true })
            },
            onCopy = { pendingRelocate = PendingRelocate(copy = true, entries = selectedEntries); filesExplorer.clearSelection() },
            onMove = { pendingRelocate = PendingRelocate(copy = false, entries = selectedEntries); filesExplorer.clearSelection() },
            onDelete = { deleteEntries = selectedEntries },
        )
    }

    // What the file actions window answered. The window itself is launched from
    // `onOpenActions` above - see [FileActionsActivity] for why it carries a token and not the
    // entry - and what comes back here is a verb with no subject, applied to the row this screen
    // opened it on.
    LaunchedEffect(pendingAction) {
        val answer = pendingAction as? ActionAnswer.FileAction ?: return@LaunchedEffect
        val entry = actionEntry
        if (entry != null) when (answer.action) {
            // The batch bar this summons is why the window closes first: the row joins whatever
            // selection is already active, which is the old long-press behaviour one tap deeper.
            FileActionKind.SELECT -> filesExplorer.toggleSelected(entry.path)
            FileActionKind.PREVIEW -> provider?.let { onPreviewFile(entry, it) }
            FileActionKind.EDIT -> provider?.let { onEditFile(entry, it) }
            FileActionKind.RENAME -> renameEntry = entry
            FileActionKind.COPY -> pendingRelocate = PendingRelocate(copy = true, entries = listOf(entry))
            FileActionKind.MOVE -> pendingRelocate = PendingRelocate(copy = false, entries = listOf(entry))
            FileActionKind.DELETE -> deleteEntries = listOf(entry)
            FileActionKind.PROPERTIES -> propertiesEntry = entry
            FileActionKind.CHMOD -> chmodEntry = entry
            FileActionKind.TRANSFER -> when {
                explorer.isLocal -> onUploadLocal(entry.toLocalFile())
                // The userspace's Transfer copies out, like a server's downloads, but it is this
                // device on both ends: the list goes to the copy, not to the transfer queue.
                explorer.isUbuntu -> onCopyToDevice(listOf(entry))
                else -> onDownloadFile(entry.toRemoteFile())
            }
            FileActionKind.SEND_TO_HOST -> sendEntry = entry
            // The row is drawn only when the session and the name can both be served - see the
            // window - so the only thing left to check is whether an opener was supplied at all.
            FileActionKind.OPEN_ARCHIVE -> onOpenArchive?.let { open -> provider?.let { open(entry, it) } }
        }
        actionEntry = null
        onActionHandled()
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

@Composable
private fun SettingsScreen(
    state: MainUiState,
    activeHost: HostProfile?,
    linuxUserspace: LinuxUserspaceController,
    onBiometric: (Boolean) -> Unit,
    onDarkTheme: (Boolean) -> Unit,
    onStopForward: (String) -> Unit,
    onImportVault: () -> Unit,
    // Six `onXxx: (Int) -> Unit` callbacks used to sit here - keep-alive, clipboard, font size,
    // terminal width, reconnect delay and vault auto-lock. They existed only to carry a dialog's
    // answer back up to the ViewModel; those six answers are now written directly to the settings
    // DataStore by the window that collects them, so there is nothing left for this screen to
    // forward and no parameter to forward it through.
    onLegacyAlgorithms: (Boolean) -> Unit,
    onBlockScreenshots: (Boolean) -> Unit,
    onReconnectAskFirst: (Boolean) -> Unit = {},
    onTerminalTheme: (String) -> Unit,
    onTerminalKeepSystemBars: (Boolean) -> Unit = {},
    // Six more `onXxx` callbacks stood here for the same reason and went the same way, with one worth
    // naming: `verifyPin` was never only this screen's. The lock screen that gates app startup takes
    // its own, and it still does - PIN lock's Settings window calls `SettingsRepository.verifyPin`
    // itself now, so this screen has no remaining use for any of the six.
    onImportSshConfig: () -> Unit,
    // Eight more went the other way: not into a store, but into a window. `onExportVault`,
    // `onGenerateKey`, `onTerminalKeyBarJson`, the diagnostics copy/save/clear trio and the install
    // log's own copy/save pair each carried a dialog's answer up to the scaffold, which owned the
    // clipboard and the SAF picker. Those rows open an Activity now, and an Activity reaches both of
    // those itself, so there is nothing left for this screen to hand down.
) {
    // The six rows below that open a window of their own start an Activity from here, which is the
    // one thing this screen now needs from the platform that a callback could not give it.
    val context = LocalContext.current
    // Launches the forward form on whichever host the workspace is pointed at. Null when there is no
    // host at all, which is the state the old dialog handled by quietly doing nothing after the user
    // had filled it in: the buttons below are disabled rather than accepting a form that cannot be
    // acted on.
    val openForwardForm = activeHost?.let { host ->
        {
            context.startActivity(
                ForwardFormActivity.intent(context, hostId = host.id, hostName = host.name),
            )
        }
    }
    // Hosts with at least one secret saved. `savedCredentials` only ever contains entries the store
    // actually wrote, but an entry whose secrets were all forgotten individually can still be present
    // with nothing in it, so the count filters rather than reading `size`.
    val savedCredentialCount = state.savedCredentials.count { (_, saved) -> !saved.isEmpty }
    Spacer(Modifier.height(8.dp))
    SettingsSection("Security") {
        SettingRow(Icons.Default.Lock, "Biometric vault lock", "Protect passwords and private keys") { Switch(checked = state.settings.biometricUnlock, onCheckedChange = onBiometric) }
        // Building the pair is the window's own work now, not this list's: generating one is seconds
        // to tens of seconds of prime searching, and the dialog that used to hold the tap froze for it.
        SettingRow(Icons.Default.Key, "Generate SSH key pair", "RSA 2048/4096 or ECDSA P-256, exported as PEM") { TextButton(onClick = { context.startActivity(Intent(context, KeyGenActivity::class.java)) }, modifier = Modifier.semantics { contentDescription = "Generate SSH key pair" }) { Text("Generate") } }
        SettingRow(Icons.Default.Lock, "PIN lock", if (state.settings.pinEnabled) "Enabled · PIN fallback at launch" else "Set a PIN for quick unlock") { TextButton(onClick = { context.startActivity(Intent(context, PinLockActivity::class.java)) }, modifier = Modifier.semantics { contentDescription = "PIN lock" }) { Text(if (state.settings.pinEnabled) "Change" else "Set") } }
        // The subtitle says so when there is no lock to re-arm: with no PIN set the vault has no
        // lock screen at all, so the delay would be a setting over nothing.
        SettingRow(
            Icons.Default.Lock,
            "Auto-lock vault",
            when {
                !state.settings.pinEnabled -> "No PIN is set, so there is no lock to re-arm"
                state.settings.vaultAutoLockMinutes == 0 -> "Never re-locks while the app is in the background"
                else -> "Re-locks after ${state.settings.vaultAutoLockMinutes} minutes in the background"
            },
        ) { TextButton(onClick = { context.startActivity(Intent(context, VaultAutoLockActivity::class.java)) }, modifier = Modifier.semantics { contentDescription = "Auto-lock vault" }) { Text("Change") } }
        SettingRow(Icons.Default.Security, "Encrypted vault", "AES-256-GCM · Android Keystore") { Text("Protected", color = EclipseSuccess, style = MaterialTheme.typography.labelMedium) }
        SettingRow(Icons.Default.Key, "Known hosts", "${state.knownHosts.size} trusted fingerprint(s)") { TextButton(onClick = { context.startActivity(Intent(context, KnownHostsActivity::class.java)) }, modifier = Modifier.semantics { contentDescription = "Known hosts" }) { Text("Manage") } }
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
            // "Manage" rather than "Forget all". The row's one action used to be its most destructive
            // one because there was nowhere to look before deciding; the screen behind it is the list
            // this count always implied, so the thing to offer here is the going-and-looking. It also
            // means forgetting one host no longer costs forgetting every other host to reach it.
            TextButton(onClick = { context.startActivity(Intent(context, SavedCredentialsActivity::class.java)) }, modifier = Modifier.semantics { contentDescription = "Saved credentials" }) { Text("Manage") }
        }
    }
    Spacer(Modifier.height(14.dp))
    SettingsSection("Workspace") {
        SettingRow(Icons.Default.Settings, "Dark appearance", "Optimized for terminal work") { Switch(checked = state.settings.darkTheme, onCheckedChange = onDarkTheme) }
        SettingRow(Icons.Default.Wifi, "Keep-alive interval", "Every ${state.settings.keepAliveSeconds} seconds") { TextButton(onClick = { context.startActivity(Intent(context, KeepAliveActivity::class.java)) }, modifier = Modifier.semantics { contentDescription = "Keep-alive interval" }) { Text("Change") } }
        SettingRow(Icons.Default.Security, "Clipboard auto-clear", if (state.settings.clearClipboardAfterSeconds == 0) "Never clear copied secrets automatically" else "Clear secrets after ${state.settings.clearClipboardAfterSeconds} seconds") { TextButton(onClick = { context.startActivity(Intent(context, ClipboardClearActivity::class.java)) }, modifier = Modifier.semantics { contentDescription = "Clipboard auto-clear" }) { Text("Change") } }
        SettingRow(Icons.Default.Terminal, "Terminal font size", "${state.settings.terminalFontSize} sp JetBrains Mono") { TextButton(onClick = { context.startActivity(Intent(context, TerminalFontSizeActivity::class.java)) }, modifier = Modifier.semantics { contentDescription = "Terminal font size" }) { Text("Change") } }
        // Next to the font size because both shape the terminal screen. The subtitle names what the
        // window edits rather than listing the caps - the count changes with the user's own setup.
        // The window rather than a dialog because the editor can now be left unsaved: it is a screen
        // the user can back out of by accident, so it asks before dropping an arrangement.
        SettingRow(
            Icons.Default.Keyboard,
            "Shortcut bar",
            "Choose the keys on the bar, add custom buttons, set the rows",
        ) { TextButton(onClick = { context.startActivity(Intent(context, ShortcutBarActivity::class.java)) }, modifier = Modifier.semantics { contentDescription = "Shortcut bar" }) { Text("Customize") } }
        SettingRow(
            Icons.Default.Terminal,
            "Terminal width",
            if (state.settings.terminalMinColumns <= 0) {
                "Exactly what fits on screen, so the server wraps long lines"
            } else {
                "At least ${state.settings.terminalMinColumns} columns; drag sideways for the rest"
            },
        ) { TextButton(onClick = { context.startActivity(Intent(context, TerminalWidthActivity::class.java)) }, modifier = Modifier.semantics { contentDescription = "Terminal width" }) { Text("Change") } }
        // Directly under the width, because they are the two halves of one question - how big is the
        // terminal - and the subtitles have to be read together to make sense: both are floors the
        // server is told, one reached by panning and the other by scrolling. A row that said "Terminal
        // height" with no explanation would read as the same kind of number as the one above it.
        SettingRow(
            Icons.Default.Terminal,
            "Terminal height",
            if (state.settings.terminalRows <= 0) {
                "As many rows as the screen fits; the window scrolls the rest"
            } else {
                "At least ${state.settings.terminalRows} rows; the window scrolls the rest"
            },
        ) { TextButton(onClick = { context.startActivity(Intent(context, TerminalHeightActivity::class.java)) }, modifier = Modifier.semantics { contentDescription = "Terminal height" }) { Text("Change") } }
        SettingRow(Icons.Default.Terminal, "Terminal theme", "Colours the grid and its background") {
            SettingDropdown(
                label = "Terminal theme",
                options = TerminalTheme.entries,
                selected = TerminalTheme.named(state.settings.terminalTheme),
                optionLabel = { it.label },
                onSelect = { theme -> onTerminalTheme(theme.name) },
            )
        }
        // Next to the theme because both decide what the terminal screen looks like. The subtitle
        // states the default so an untouched row explains what the app does on its own.
        SettingRow(
            Icons.Default.Terminal,
            "Keep system bars during sessions",
            "Off by default: sessions take the whole screen. On, the status and navigation bars stay visible over the terminal",
        ) { Switch(checked = state.settings.terminalKeepSystemBars, onCheckedChange = onTerminalKeepSystemBars) }
        SettingRow(Icons.Default.Security, "Legacy algorithms", "Also offer CBC, SHA-1 and dh-group1 to reach older servers") { Switch(checked = state.settings.legacyAlgorithms, onCheckedChange = onLegacyAlgorithms) }
        SettingRow(Icons.Default.Lock, "Block screenshots", "Hides this app from screenshots, screen recording and the recents preview") { Switch(checked = state.settings.blockScreenshots, onCheckedChange = onBlockScreenshots) }
    }
    Spacer(Modifier.height(14.dp))
    SettingsSection("Port forwarding") {
        // The list of what is running stays a row — a status, not a screen — and only the *asking*
        // moved into a window. The subtitle under "No active forwards" therefore has to say what the
        // button will do when there is no host to do it through, rather than offering a form whose
        // Start button could never work.
        if (state.forwardings.isEmpty()) {
            SettingRow(
                Icons.Default.SwapVert,
                "No active forwards",
                if (openForwardForm == null) {
                    "Local, remote, and dynamic (SOCKS5) — connect to a host to add one"
                } else {
                    "Local, remote, and dynamic (SOCKS5)"
                },
            ) {
                TextButton(
                    onClick = { openForwardForm?.invoke() },
                    enabled = openForwardForm != null,
                    modifier = Modifier.semantics { contentDescription = "Add port forward" },
                ) { Text("Add") }
            }
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
            TextButton(
                onClick = { openForwardForm?.invoke() },
                enabled = openForwardForm != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Add port forward" },
            ) { Text("Add forward") }
        }
    }
    Spacer(Modifier.height(14.dp))
    SettingsSection("Backup & restore") {
        // The window owns the passphrase field and the SAF picker that follows it, because the two
        // have to be one motion: the picker leaves the app, and the passphrase must not be left
        // behind in a list that has already scrolled on.
        SettingRow(Icons.Default.CloudUpload, "Export encrypted backup", "Hosts and settings, passphrase-protected") { TextButton(onClick = { context.startActivity(Intent(context, ExportBackupActivity::class.java)) }, modifier = Modifier.semantics { contentDescription = "Export encrypted backup" }) { Text("Export") } }
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
        ) { TextButton(onClick = { context.startActivity(Intent(context, ReconnectDelayActivity::class.java)) }, modifier = Modifier.semantics { contentDescription = "Reconnect delay" }) { Text("Change") } }
        SettingRow(
            Icons.Default.Terminal,
            "Connection diagnostics",
            if (state.diagnostics.isEmpty()) {
                "Records every connect, drop and reconnect · no secrets"
            } else {
                "${state.diagnostics.size} event(s) recorded · no secrets"
            },
        // "View" alone is ambiguous on this list - the Ubuntu row below carries a control too, and
        // the About row at the end of this block records that a screen reader once heard two rows
        // as the same word.
        ) { TextButton(onClick = { context.startActivity(Intent(context, DiagnosticsActivity::class.java)) }, modifier = Modifier.semantics { contentDescription = "Connection diagnostics" }) { Text("View") } }
    }
    Spacer(Modifier.height(14.dp))
    // The userspace control panel is a window of its own now. Everything that acts on the
    // environment - install, uninstall, start, stop, repair, the version chooser, and the install
    // log with its two exports - needed more room than a Settings row has, and the log in
    // particular was a capped dialog body. What stays on this list is the one fact the list is
    // for: whether there is an environment, and what it is doing.
    val linuxUi by linuxUserspace.uiState.collectAsStateWithLifecycle()
    SettingsSection("Linux userspace") {
        SettingRow(
            Icons.Default.Computer,
            "Ubuntu on this device",
            linuxUserspaceSummary(linuxUi),
        ) {
            // Absent, not disabled, on a device that cannot run it - the rule the section kept: an
            // unsupported device cannot be offered an install that cannot finish, but it also
            // should not look like a feature that went missing.
            if (linuxUi.supported) {
                TextButton(
                    onClick = { context.startActivity(Intent(context, UbuntuActivity::class.java)) },
                    modifier = Modifier.semantics { contentDescription = "Ubuntu on this device" },
                ) { Text("Open") }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    SettingsSection("About") {
        SettingRow(Icons.Default.Info, "About EclipseSSH", "Version, libraries and credits") {
            val context = LocalContext.current
            // Two "View" buttons sit on this screen once diagnostics is counted, and a screen reader
            // hears both of them as just "View" — so the button carries the row it belongs to, the
            // same "setting, action" shape the theme picker's content description uses.
            //
            // It opens a window rather than a dialog: the licence list is longer than a capped
            // dialog body can show, and the rows past the fold were unreachable inside one.
            TextButton(
                onClick = { context.startActivity(Intent(context, AboutActivity::class.java)) },
                modifier = Modifier.semantics { contentDescription = "About EclipseSSH" },
            ) { Text("View") }
        }
    }

    // The Add-forward dialog that used to sit here is a window of its own now
    // ([ForwardFormActivity]), and what it produces comes back through [ForwardRequests] on the
    // workspace's next resume rather than through this composition - see [MainActivity.onResume].
    // The six choice dialogs that used to sit here - keep-alive, reconnect delay, clipboard
    // auto-clear, auto-lock vault, font size, terminal width - are now windows of their own, opened
    // from the rows above. Their values are written straight to the settings DataStore, which this
    // screen reads through its own flow, so the row's subtitle is already correct by the time the
    // user is back here. Nothing is returned through the intent because nothing needs to be.
    //
    // The three security dialogs that sat beside them - PIN, known hosts, saved credentials - went the
    // same way with one difference worth naming: each of those wrote to a store this screen does not
    // collect, so coming back here is not enough on its own. The known-hosts count in particular is a
    // snapshot the view model holds, and a fingerprint forgotten in that window would leave the row
    // above counting one that is gone; see the ON_RESUME refresh in the workspace.
}

/**
 * The one line the Settings list keeps about the userspace, for every state it can be in.
 *
 * This replaced a section of up to six rows and a progress bar, and that is now the window's own
 * business: [UbuntuActivity] shows the same facts with the room to act on them, which the section
 * never had - the install log alone was a capped dialog body. What the list still needs is the
 * question that decides whether the row is worth tapping: is there an environment, and what is it
 * doing.
 *
 * Exhaustive over [LinuxUserspaceState] deliberately. A `when` with a fallback line would quietly
 * stop reporting a state added later, which is the silence the section's one-state-object rule
 * existed to prevent.
 */
private fun linuxUserspaceSummary(ui: LinuxUserspaceUiState): String {
    if (!ui.supported) return "Not supported on this device's processor"
    return when (val state = ui.state) {
        // `supported` implies a graph and a graph always has a state, so this branch is the
        // compiler's proof of that invariant rather than a state any device can reach.
        null -> "Not installed"
        is LinuxUserspaceState.Installing ->
            "Installing ${ui.distro?.displayName ?: "…"} · ${describeInstallStep(state.step)} · ${state.percent}%"
        is LinuxUserspaceState.NotInstalled ->
            if (ui.hasPendingWorkspaceBackup) {
                "Not installed · a saved workspace will be restored"
            } else {
                "Not installed · real bash, apt and git, on the device"
            }
        is LinuxUserspaceState.Stopped -> "Installed and verified · stopped"
        is LinuxUserspaceState.Starting -> "Starting…"
        // Held open, not merely alive: closing the last terminal does not end this, and the count
        // is what tells the user there are shells to come back to.
        is LinuxUserspaceState.Running -> "Running · ${ui.sessionCount} terminal session(s) held open"
        is LinuxUserspaceState.Stopping -> "Stopping…"
        is LinuxUserspaceState.NeedsRepair -> "Needs repair · ${state.detail}"
    }
}

/**
 * One install phase as the progress line renders it. The percentage beside it is the state's own,
 * so this is the phase alone — the install's overall figure is what the user is watching, and a
 * phase-local one would start over at every step.
 */
private fun describeInstallStep(step: LinuxInstallStep): String = when (step) {
    is LinuxInstallStep.Downloading ->
        "Downloading · ${formatTransferBytes(step.received)} of ${formatTransferBytes(step.total)}"
    LinuxInstallStep.Verifying -> "Verifying the download"
    is LinuxInstallStep.Extracting -> "Extracting · ${step.entries} files"
    is LinuxInstallStep.SettingUp -> describeSetupStep(step.step, step.detail)
    LinuxInstallStep.VerifyingHealth -> "Running the health check"
}

private fun describeSetupStep(step: SetupStep, detail: String?): String {
    val label = when (step) {
        SetupStep.REGISTER_USER -> "Creating the ubuntu account"
        SetupStep.PREPARE_WORKSPACE -> "Preparing the workspace"
        SetupStep.CONFIGURE_DNS -> "Configuring DNS"
        SetupStep.CONFIGURE_APT -> "Configuring package sources"
        SetupStep.UPDATE_PACKAGES -> "Updating package lists"
        SetupStep.INSTALL_BASE_PACKAGES -> "Installing the base packages"
        SetupStep.VERIFY -> "Verifying"
    }
    // The newest command output beside the step's label: a slow-but-alive `apt-get update` shows
    // "Get: 47 …" crawling instead of a label that could be wedged for all the user can tell.
    // Capped because an apt line is unbounded prose, and this sits in a settings row's subtitle.
    return if (detail.isNullOrBlank()) label else "$label · ${detail.trim().take(80)}"
}

// The three settings building blocks - SettingsSection, SettingRow, SettingDropdown - moved to
// dev.eclipse.ssh.ui.settings when each Settings row became a window of its own. They are imported
// at the top of this file rather than duplicated here, so the promoted screens and the list they
// came from cannot drift apart.

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

/**
 * Opens the Add / Edit host form in a window of its own.
 *
 * [host] absent is Add, present is Edit. The form is told *which* host by id and loads the profile
 * itself rather than being handed one: the id is a parcelable string, and a profile passed through
 * an intent would be a snapshot that stops matching the store the moment anything else edits it.
 * That is also why nothing comes back — [HostRepository] and [HostCredentialStore] are both Flows,
 * so a save made in that window is in this one's state by the time it resumes.
 *
 * The three callers are the Hosts list's Add button, a card's Edit item, and the Edit host button on
 * the failed-login prompt. The last of those is why the form owns its own key picker: the prompt's
 * picker returns a key for a *retry*, not for the form, and the two used to share one launcher.
 */
private fun openHostForm(context: Context, host: HostProfile? = null) {
    context.startActivity(
        Intent(context, HostFormActivity::class.java).apply {
            if (host != null) putExtra(HostFormActivity.EXTRA_HOST_ID, host.id)
        }
    )
}

