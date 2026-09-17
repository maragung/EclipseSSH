package dev.eclipse.ssh.ui.about

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.feature.about.OPEN_SOURCE_LICENSES
import dev.eclipse.ssh.ui.EclipseTheme
import javax.inject.Inject
import kotlinx.coroutines.flow.first

/**
 * About, in its own window.
 *
 * It used to be an `AlertDialog` over Settings. Two things were wrong with that. The licence list is
 * the longest thing the app has to say and a dialog is the one container that cannot say it well:
 * the body was capped at 70% of the screen height so the list lived in a letterbox, and the list
 * below the fold was *unreachable* - the JVM tests could not scroll it and neither, comfortably,
 * could a thumb inside a modal. And "About" is a place with a lot to read, not a question that wants
 * an answer before you continue; a dialog asks to be dismissed, a screen asks to be scrolled.
 *
 * As a screen it takes the whole window, gets a back arrow and its own back-stack entry, and the
 * list scrolls properly. Nothing is handed over through the intent - the content is entirely static
 * - so unlike [dev.eclipse.ssh.ui.editor.TextEditorActivity] and
 * [dev.eclipse.ssh.ui.remotedesktop.RemoteDesktopActivity] there is no request token and no
 * `finish()` on arrival.
 */
@AndroidEntryPoint
class AboutActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge is forced on Android 15 anyway; calling it here makes pre-35 devices behave
        // the same way. The Scaffold below supplies the inset padding that keeps the bar and the
        // text off the system bars.
        enableEdgeToEdge()
        setContent {
            // The app's own dark-theme setting rather than the system's, so About does not flip on a
            // user who pinned one in Settings. Read once, ahead of the first frame it can affect.
            val darkTheme by produceState(initialValue = isSystemInDarkTheme()) {
                value = runCatching { settingsRepository.settings.first().darkTheme }.getOrDefault(true)
            }
            EclipseTheme(darkTheme = darkTheme) {
                AboutScreen(onClose = { finish() })
            }
        }
    }
}

/**
 * The About screen itself: the same three answers the dialog gave - what version is this, who made
 * it, and what is inside it - laid out in a scrollable column instead of a capped dialog body.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val packageInfo = remember { context.packageManager.getPackageInfo(context.packageName, 0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About EclipseSSH") },
                navigationIcon = {
                    // A visible way back, not just the system gesture: this window is a destination
                    // reached from Settings, and the arrow is what says so.
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        // The margins live in `contentPadding`, not in a `Modifier.padding` around the list: padding
        // the list itself would clip its scroll range and cut the last licence row off at the inset.
        // The width cap is the shell's own (MainActivity caps its content column at 1280dp), so About
        // reads as the same app on a tablet rather than as an edge-to-edge outlier.
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().widthIn(max = 1280.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text("EclipseSSH", style = MaterialTheme.typography.titleMedium)
            }
            item {
                Text(
                    "Version ${packageInfo.versionName} (${packageInfo.longVersionCode})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Text(
                    "An SSH and SFTP client for Android: a full-screen VT/ANSI terminal, concurrent " +
                        "sessions in tabs, a two-pane SFTP browser with resumable transfers, port " +
                        "forwarding and remote desktop, with credentials kept in an Android " +
                        "Keystore-backed vault.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Text("Created by Maragung", style = MaterialTheme.typography.titleSmall)
            }
            item {
                // The repo is private, so this link serves the owner and contributors rather than the
                // public — anyone else lands on GitHub's sign-in, which is still the honest
                // destination for "where is the source".
                TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ABOUT_REPO_URL))) }) {
                    Text("Source code · github.com/maragung/EclipseSSH")
                }
            }
            item {
                Text(
                    "Libraries",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The one list the repo keeps: this screen renders OPEN_SOURCE_LICENSES as-is, so what
            // About says and what the repo claims cannot drift apart.
            items(OPEN_SOURCE_LICENSES) { library ->
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text("${library.name} ${library.version}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            // Bouncy Castle is the one entry with no purpose line - a transitive
                            // dependency nothing calls directly - so its row is the licence alone, not
                            // a sentence ending in a dangling dot.
                            library.purpose?.let { "${library.license} · $it" } ?: library.license,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private const val ABOUT_REPO_URL = "https://github.com/maragung/EclipseSSH"
