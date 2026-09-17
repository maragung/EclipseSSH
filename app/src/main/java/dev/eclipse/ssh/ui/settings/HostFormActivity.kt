package dev.eclipse.ssh.ui.settings

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.data.HostRepository
import dev.eclipse.ssh.data.credentials.HostCredentialStore
import dev.eclipse.ssh.data.credentials.HostCredentialUpdate
import dev.eclipse.ssh.data.credentials.KeyEdit
import dev.eclipse.ssh.data.credentials.SecretEdit
import dev.eclipse.ssh.data.credentials.StoredCredentials
import dev.eclipse.ssh.data.model.AuthMethod
import dev.eclipse.ssh.data.model.CONNECT_TIMEOUT_RANGE
import dev.eclipse.ssh.data.model.DEFAULT_CONNECT_TIMEOUT_SECONDS
import dev.eclipse.ssh.data.model.DEFAULT_SOCKS_PORT
import dev.eclipse.ssh.data.model.DEFAULT_SSH_PORT
import dev.eclipse.ssh.data.model.HostProfile
import dev.eclipse.ssh.data.model.KEEP_ALIVE_RANGE
import dev.eclipse.ssh.data.model.PORT_RANGE
import dev.eclipse.ssh.data.model.ProxyType
import dev.eclipse.ssh.data.saf.PickedKeyFile
import dev.eclipse.ssh.data.saf.readPickedKeyFile
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.feature.vault.VaultUnlockGate
import dev.eclipse.ssh.presentation.AdvancedHostOptions
import dev.eclipse.ssh.presentation.HostFormDraft
import dev.eclipse.ssh.security.SecureClipboard
import dev.eclipse.ssh.security.normalizePastedSecret
import dev.eclipse.ssh.ssh.SshConnectionManager
import dev.eclipse.ssh.ssh.SshKeyProbe
import dev.eclipse.ssh.ssh.probeSshKey
import dev.eclipse.ssh.ui.AdvancedHostSection
import dev.eclipse.ssh.ui.SecretFieldKeyboard
import dev.eclipse.ssh.ui.SecretPasteButton
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * How long the form waits after the last passphrase keystroke before trying to read the picked key.
 *
 * Reading is not free — an encrypted OpenSSH key runs bcrypt-pbkdf on purpose — so probing on every
 * keystroke would queue one derivation per character and report verdicts for passphrase prefixes the
 * user was still in the middle of typing.
 */
private const val KEY_PROBE_DEBOUNCE_MS = 300L

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

/** What the key picker will accept. The wildcard is last and is what actually matters. */
private val KEY_FILE_MIME_TYPES = arrayOf("application/octet-stream", "text/plain", "*/*")

/**
 * The Add / Edit host form, in a window of its own.
 *
 * This was `AddHostDialog`, an `AlertDialog` over the Hosts list, and it is the last of the Settings
 * and Host entries to be promoted. It is the one that needed it most: a `AlertDialog`'s body is
 * capped at a fraction of the screen height, and this form is the longest in the app — identity,
 * authentication, four credential fields, engine settings, route, and the whole of
 * [AdvancedHostSection] below them. Inside the dialog that was a letterboxed column the user
 * scrolled by centimetres, with the Save button pinned outside it.
 *
 * Two things about the move are worth stating because they are not obvious from the code below.
 *
 * **Which host, and how it is named.** [EXTRA_HOST_ID] absent is Add, present is Edit. The extra is
 * the host's *id* and not the profile: a profile in an intent is a snapshot that stops matching the
 * store the moment anything else writes to it, and this form reads the profile a second time anyway
 * for the credentials. There is no result — [HostRepository] and [HostCredentialStore] are both
 * Flows, so the save this window makes is in the Hosts list's state by the time it resumes. Nothing
 * here calls `setResult`.
 *
 * **Where the key picker's gate went.** The picker used to be owned by `MainActivity` and its
 * `pickerActive` flag with it, because a dialog cannot own an activity result. A window can, so the
 * launcher and the [VaultUnlockGate] hold moved here together — and the gate still has to be the
 * process-wide singleton, not a `remember`: while the picker is up this window is stopped too, and
 * the auto-lock countdown is decided by an observer in `MainActivity`.
 */
@AndroidEntryPoint
class HostFormActivity : ComponentActivity() {

    @Inject lateinit var hostRepository: HostRepository
    @Inject lateinit var credentialStore: HostCredentialStore
    @Inject lateinit var sshConnectionManager: SshConnectionManager
    @Inject lateinit var secureClipboard: SecureClipboard
    @Inject lateinit var vaultUnlockGate: VaultUnlockGate
    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Read once, from the intent that opened this window. Not state: the id cannot change while
        // the window lives, and a rotation that recreates the Activity re-reads the same intent.
        val hostId = intent.getStringExtra(EXTRA_HOST_ID)
        setContent {
            SettingsDestinationWindow(
                settingsRepository = settingsRepository,
                title = if (hostId == null) ADD_TITLE else EDIT_TITLE,
                onClose = { finish() },
            ) {
                HostForm(
                    hostId = hostId,
                    hostRepository = hostRepository,
                    credentialStore = credentialStore,
                    sshConnectionManager = sshConnectionManager,
                    secureClipboard = secureClipboard,
                    vaultUnlockGate = vaultUnlockGate,
                )
            }
        }
    }

    companion object {
        /**
         * The host to edit. Absent means Add.
         *
         * The id and not the profile — see this class's KDoc.
         */
        const val EXTRA_HOST_ID = "dev.eclipse.ssh.extra.HOST_ID"

        private const val ADD_TITLE = "Add host"
        private const val EDIT_TITLE = "Edit host"
    }
}

/**
 * What this window knows about the host it was opened for, before it can draw a form.
 *
 * A sealed type rather than a nullable profile, because "still reading" and "there is no such host"
 * are different screens and neither of them is the form. Collapsing them into a null profile would
 * mean an Edit for a host that is gone silently drawing an empty Add form — which looks like it
 * worked, and then saves a second host instead of changing the one the user meant.
 */
private sealed interface HostLoad {

    /** The stores have not answered yet. One frame, in practice. */
    data object Loading : HostLoad

    /** The id named no host: removed between the tap on Edit and this window opening. */
    data object Gone : HostLoad

    /** A store could not be read. Distinct from [Gone] because the two are not the same fact. */
    data class Failed(val reason: String) : HostLoad

    /** Ready to draw. [host] is null for Add. */
    data class Ready(val host: HostProfile?, val credentials: StoredCredentials) : HostLoad
}

/**
 * Reads the host and its stored credentials, or says why it could not.
 *
 * [hostId] null is Add, and skips both reads: there is nothing to look up and nothing saved for a
 * profile that does not exist yet.
 *
 * Cancellation is rethrown rather than reported. A `produceState` block runs in the composition's
 * scope, so leaving this window cancels the read, and `runCatching` over it would turn that into a
 * "could not be loaded" screen for a window that is already gone.
 */
private suspend fun loadHost(
    hostId: String?,
    hostRepository: HostRepository,
    credentialStore: HostCredentialStore,
): HostLoad {
    if (hostId == null) return HostLoad.Ready(null, StoredCredentials())
    return try {
        val host = hostRepository.hosts.first().firstOrNull { it.id == hostId }
        if (host == null) HostLoad.Gone else HostLoad.Ready(host, credentialStore.stored(hostId))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        HostLoad.Failed(error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName)
    }
}

@Composable
private fun HostForm(
    hostId: String?,
    hostRepository: HostRepository,
    credentialStore: HostCredentialStore,
    sshConnectionManager: SshConnectionManager,
    secureClipboard: SecureClipboard,
    vaultUnlockGate: VaultUnlockGate,
) {
    val loaded by produceState<HostLoad>(HostLoad.Loading, hostId) {
        value = loadHost(hostId, hostRepository, credentialStore)
    }
    when (val state = loaded) {
        // Nothing rather than a spinner: the read is one emission from an in-process Flow, and a
        // progress indicator that appears and vanishes within a frame reads as a flicker.
        HostLoad.Loading -> Unit
        HostLoad.Gone -> HostUnavailable("That host is no longer saved, so there is nothing to edit.")
        is HostLoad.Failed -> HostUnavailable("That host could not be loaded: ${state.reason}")
        is HostLoad.Ready -> HostFormFields(
            initialHost = state.host,
            storedCredentials = state.credentials,
            hostRepository = hostRepository,
            credentialStore = credentialStore,
            sshConnectionManager = sshConnectionManager,
            secureClipboard = secureClipboard,
            vaultUnlockGate = vaultUnlockGate,
        )
    }
}

/**
 * What an Edit shows when the host it was opened for cannot be drawn.
 *
 * A dead end that says so, rather than a close-and-vanish. Reporting through the shell's snackbar and
 * finishing would put the sentence in a window on its way out — the same trap
 * [ShortcutBarActivity]'s save avoids — so the message stays on screen with the way out beside it.
 */
@Composable
private fun HostUnavailable(message: String) {
    val activity = LocalActivity.current
    SettingsSection("Host") {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = { activity?.finish() }) { Text("Close") }
        }
    }
}

/**
 * The form itself: every field, every rule, and the save.
 *
 * The body is the dialog's own column, uncapped and without its inner `verticalScroll` — the shell's
 * [SettingsBody] scrolls it now, which is the whole point of the promotion. Its section headings are
 * the dialog's as well, and they were already doing the work a promotion would otherwise have to
 * redo: the fields below them are grouped by what they decide, so nothing here needed regrouping to
 * read as a screen.
 *
 * [initialHost] null is Add. Every field is keyed on `initialHost?.id` rather than on the host
 * object, which matters more here than it did in the dialog: this composable's first frame is
 * [HostLoad.Loading] and its second is the real host, and keying on the id means the form's state is
 * built once, from the loaded value, instead of being seeded from a default and then corrected.
 */
@Composable
private fun HostFormFields(
    initialHost: HostProfile?,
    storedCredentials: StoredCredentials,
    hostRepository: HostRepository,
    credentialStore: HostCredentialStore,
    sshConnectionManager: SshConnectionManager,
    secureClipboard: SecureClipboard,
    vaultUnlockGate: VaultUnlockGate,
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
    // The key picked during this visit to the form. Held here rather than by the caller that owns the
    // launcher, which is what it took in the dialog: this window owns both, so one `remember` does.
    var pickedKey by remember(initialHost?.id) { mutableStateOf<PickedKeyFile?>(null) }
    // True from the tap that starts a save until it lands, so a second tap cannot start a second
    // write through the same store.
    var saving by remember(initialHost?.id) { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = LocalActivity.current
    val report = LocalSettingsReport.current

    // Reading a private key costs a deliberate key derivation — bcrypt-pbkdf, for the OpenSSH
    // format — so it runs off the main thread, and not until the user has stopped typing. `value` is
    // cleared first so the form cannot report a stale verdict for the passphrase now in the field;
    // [HostFormDraft.keyReadable] treats "picked but not read yet" as not-yet-saveable.
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
    // Every validity rule lives in [HostFormDraft], which is a plain data class with plain tests.
    // Robolectric cannot idle a Compose dialog window, so rules left inline here would be permanently
    // unverifiable on the JVM.
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
        pickedKey != null && keyProbe == null -> "Reading ${pickedKey?.name}…" to false
        keyProbe is SshKeyProbe.Ready -> "${pickedKey?.name.orEmpty()} · ${keyProbe.type}" to false
        keyProbe is SshKeyProbe.PassphraseRequired -> "That key is encrypted — enter its passphrase below" to false
        keyProbe is SshKeyProbe.Unreadable -> keyProbe.reason to true
        forgetKey && storedCredentials.hasKey -> "The saved key will be removed when you save" to false
        storedCredentials.hasKey ->
            listOfNotNull(storedCredentials.keyLabel, storedCredentials.keyType).joinToString(" · ") to false
        else -> null
    }

    /**
     * The clipboard read behind every Paste button here.
     *
     * The view model's own body, moved: [secureClipboard]'s read and
     * [normalizePastedSecret]'s newline strip are the audited boundary every other clipboard access in
     * the app goes through, and the empty-clipboard sentence is what makes a Paste that did nothing
     * distinguishable from a broken one.
     */
    val pasteSecret: () -> String? = {
        val text = secureClipboard.paste()?.let(::normalizePastedSecret)
        if (text.isNullOrEmpty()) {
            report("There is nothing on the clipboard to paste")
            null
        } else {
            text
        }
    }

    // The key file picker, owned by this window. The gate is held from the launch until the callback
    // delivers a result — including a cancellation, which arrives as a null uri and is still the
    // picker coming back. See [VaultUnlockGate] for why a picker needs it at all.
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        vaultUnlockGate.release()
        if (uri != null) scope.launch {
            readPickedKeyFile(context, uri)
                .onSuccess { pickedKey = it }
                // Not [reportWriteFailure]: nothing was being saved, and that helper's sentence opens
                // with "Could not save", which would name the wrong thing.
                .onFailure { error -> report(error.message ?: "That key file could not be read") }
        }
    }

    /**
     * Writes the profile, then its credentials, then the pin — the view model's [saveHost] order.
     *
     * Replicated rather than called because the view model is unreachable from this window: it is a
     * `@HiltViewModel` with no scope, so a second owner would get a second instance whose `init`
     * reseeds the host list and adopts sessions. Every store this touches is a `@Singleton` the graph
     * hands to both windows, so the effect is the same; only the reporting differs, and each failure
     * below carries the view model's own wording.
     *
     * The window closes only when the *profile* landed. A credential write that failed is reported and
     * the window stays, because the user's profile was saved but their password was not, and leaving
     * would take away the field they would have to retype.
     *
     * The known-hosts refresh the view model does after pinning has no equivalent here: that state is
     * the view model's own, and the Known hosts screen re-reads the store when it is opened.
     */
    fun save(profile: HostProfile, credentials: HostCredentialUpdate) {
        saving = true
        scope.launch {
            try {
                hostRepository.save(profile)
            } catch (failure: Throwable) {
                saving = false
                // The view model's `launchGuarded("Could not save ${host.name}")` wording, which is
                // this helper's with the name as its subject.
                reportWriteFailure(report, profile.name, failure)
                return@launch
            }
            if (!credentials.isNoop) {
                runCatching { credentialStore.apply(profile.id, credentials) }
                    .onFailure { failure ->
                        reportFailure(report, "Saved ${profile.name}, but its credentials could not be stored", failure)
                    }
            }
            profile.fingerprint?.trim()?.takeIf(String::isNotBlank)?.let { pinned ->
                runCatching { sshConnectionManager.trustHost(profile, pinned) }
            }
            activity?.finish()
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Connection details are stored in the encrypted vault. Credentials are optional — save them for one-tap connects, or leave them blank to be asked each time.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        // Both a password and a key are offered whatever the method above says, and on purpose: a
        // server can want a key *and* a password, `KEYBOARD_INTERACTIVE` is usually answered with the
        // account password, and hiding a field would take away a combination that works. The method
        // chips say what to try first; these say what the app has to try with.
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
                // Typing a replacement is a clearer statement of intent than the pending "forget", so
                // it wins rather than fighting it.
                if (it.isNotEmpty()) forgetPassword = false
            },
            label = { Text(if (storedCredentials.hasPassword) "Replace saved password" else "Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = SecretFieldKeyboard,
            modifier = Modifier.fillMaxWidth(),
            // The paste button sets the state directly, which bypasses the onValueChange above — so
            // the same "a replacement beats a pending forget" rule is restated here. Without it, a
            // pasted replacement would lose to a "Forget" the user ticked before pasting, and the save
            // would drop the password they just fixed.
            trailingIcon = {
                SecretPasteButton("password", pasteSecret) {
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
        OutlinedButton(
            onClick = {
                vaultUnlockGate.hold()
                keyPicker.launch(KEY_FILE_MIME_TYPES)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
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
                    // Drops the pick and falls back to whatever was already saved, so picking the
                    // wrong file is not a one-way door.
                    TextButton(onClick = { pickedKey = null }) { Text("Remove") }
                } else if (storedCredentials.hasKey) {
                    TextButton(onClick = { forgetKey = !forgetKey }) { Text(if (forgetKey) "Keep" else "Forget") }
                }
            }
        }
        if (draft.keyAttached || pickedKey != null) {
            OutlinedTextField(
                passphrase,
                { passphrase = it },
                label = { Text(if (storedCredentials.hasPassphrase) "Replace key passphrase" else "Key passphrase") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = SecretFieldKeyboard,
                isError = !draft.keyReadable && keyProbe is SshKeyProbe.Unreadable,
                trailingIcon = { SecretPasteButton("passphrase", pasteSecret) { passphrase = it } },
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
            // Unreachable through the fields above (the passphrase field is only shown when a key is
            // attached), but reachable by attaching a key, typing a passphrase and then forgetting the
            // key — which would otherwise leave Save disabled with nothing on screen explaining why.
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
            ACCENT_COLORS.forEach { (colorName, color) ->
                Surface(
                    // selectable (not clickable) so the swatch reports its checked state: TalkBack
                    // reads "Red, selected" instead of just "Red".
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(50))
                        .selectable(
                            selected = accentColor == color,
                            onClick = { accentColor = color },
                        )
                        .semantics { contentDescription = colorName },
                    color = Color(color),
                ) {
                    // Decorative: `selectable` above already announces the selected state, so
                    // labelling the tick too would make TalkBack say it twice.
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
                    // fingerprint from a channel you already trust and the very first connection is
                    // verified instead of asking the user to accept an unseen key.
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
                    // The same-LAN limit is stated here, in the field's own helper line, rather than
                    // left to a failure to explain: a wake sent from another network stops at the
                    // first router and nothing on screen would say why.
                    else "Wakes the machine from the host menu — phone and machine must be on the same network",
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
        // A switch rather than a chip row: it is one binary choice whose off state has to be as
        // visible as its on state. The whole row is the target — `toggleable` puts the label, the
        // explanation and the switch in a single accessible node, so TalkBack reads "Auto Login SFTP,
        // on" once instead of announcing an unlabelled switch, and a thumb lands on it anywhere along
        // the line.
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
                // Worded as what happens on connect, not as a protocol name, and switched on the
                // current value so the consequence is readable without toggling it first.
                Text(
                    if (autoLoginSftp) "Signs in to the file browser as soon as the shell connects"
                    else "Connects the shell only — the Files tab opens on demand",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(10.dp))
            // Null handler: the row above owns the input, and a second clickable node here would
            // swallow taps on the switch itself and be announced twice.
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
                OutlinedTextField(socksPassword, { socksPassword = it }, label = { Text("Proxy password (optional)") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = SecretFieldKeyboard, trailingIcon = { SecretPasteButton("proxy password", pasteSecret) { socksPassword = it } })
            }
            ProxyType.NONE -> Unit
        }
        AdvancedHostSection(advanced, onChange = { advanced = it })
        // The dialog's two buttons, at the end of the body they now belong to. Cancel first, so the
        // emphasised action is the one nearer the thumb.
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { activity?.finish() }, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(
                onClick = {
                    save(
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
                                    KeyEdit.Replace(pickedKey!!.bytes, pickedKey!!.name, keyProbe.type)
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
                // section can still hold an out-of-range number typed before it was closed. `saving`
                // is the third gate, and the only one that is about this window rather than the
                // values: a second tap would start a second write through the same store.
                enabled = draft.canSave && advanced.isValid && !saving,
                modifier = Modifier.weight(1f),
            ) { Text(if (editing) "Save changes" else "Save securely") }
        }
    }
}
