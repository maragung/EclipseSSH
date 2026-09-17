package dev.eclipse.ssh.ui.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.eclipse.ssh.data.model.AppSettings
import dev.eclipse.ssh.data.settings.SettingsRepository
import dev.eclipse.ssh.ui.EclipseTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * How a destination tells the user that the thing they just did did not save.
 *
 * This exists because of a specific regression the promotion would otherwise have introduced. The
 * Settings view model reports a failed write to the user - `writeSetting` and the known-hosts
 * callbacks all surface their failure - and several of those writes are *security* writes: a
 * fingerprint that failed to be forgotten, a PIN that failed to be stored. A destination that calls
 * the repository directly, as every screen here now does, owns that report itself, and the honest
 * default is not "say nothing".
 *
 * A `staticCompositionLocalOf` rather than another parameter on [SettingsDestination]'s `content`:
 * the reporter is only reachable from a handful of screens, and threading a parameter through every
 * signature to serve those would make the common case carry the uncommon one's argument. The default
 * is a no-op, so a destination that never reports still gets a working screen.
 */
val LocalSettingsReport = staticCompositionLocalOf<(String) -> Unit> { {} }

/**
 * Runs a settings write and reports the failure the way the view model used to.
 *
 * [SettingsRepository]'s setters are DataStore `edit` calls: a write that fails does so by *throwing*
 * - an `IOException` for a full or read-only data directory - and returns no status to test. The view
 * model's `writeSetting` caught exactly that and turned it into a sentence through
 * `report(prefix, error)`, which is why a full disk used to produce a message. Once a destination
 * calls the repository directly it becomes the only thing between that exception and the crash it
 * would otherwise be, and an uncaught throw inside a `rememberCoroutineScope().launch` is not a
 * caught one: it reaches the thread's handler and takes the process down. Tapping a chip is not
 * allowed to be able to kill the app because a disk filled up.
 *
 * The wording is the view model's, `"$prefix: $detail"` with the same prefix strings, so a failure
 * that moved out of `MainViewModel` still reads to the user exactly as it did before the move.
 */
internal fun reportWriteFailure(report: (String) -> Unit, what: String, error: Throwable) {
    // Cancellation is the window going away mid-write, not a failed write, and it must keep
    // propagating - swallowing it here would leave this coroutine running in a scope already torn
    // down, and would hide a cancellation the caller may be waiting on.
    if (error is CancellationException) throw error
    val detail = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
    report("Could not save $what: $detail")
}

/**
 * The shell every settings destination shares: theme, window chrome, and the settings snapshot.
 *
 * This is the chokepoint the whole "one Activity per Settings row" change runs through. Each
 * destination used to be an `AlertDialog` inside `SettingsScreen`, and one property of a dialog is
 * what decided the rest of this design: its body is capped at roughly 70% of the screen height, so
 * anything longer than a few rows lived in a letterbox and the rows below the fold could not be
 * scrolled to at all. Promoting a destination to a window costs a `Scaffold`, a back arrow and a
 * read of the app's dark-theme setting - about twenty lines, sixteen times over. This is those
 * twenty lines, once.
 *
 * [content] receives the settings snapshot so a destination can render the value it edits without
 * collecting the flow itself.
 *
 * The read is `produceState` rather than a collected `Flow`, deliberately: this is a destination
 * reached from Settings and left again, not a screen that stays live while the value changes behind
 * it. A destination that *writes* a setting and must show its own write keeps the chosen value in
 * its own `remember` - the same way the dialogs this replaces did, via their local `selected` state
 * - so the freshly written value is on screen the moment it is chosen rather than a DataStore
 * round-trip later.
 */
@Composable
fun SettingsDestination(
    settingsRepository: SettingsRepository,
    title: String,
    onClose: () -> Unit,
    content: @Composable (AppSettings) -> Unit,
) {
    // The app's own dark-theme setting rather than the system's, so a settings screen does not flip
    // on a user who pinned one - the same reason About reads it this way.
    // One read of the store, not two: the theme and the snapshot the body renders come from the same
    // emission, so a body can never be drawn in one theme against another theme's values.
    val loaded by produceState(initialValue = null as AppSettings?, settingsRepository) {
        value = runCatching { settingsRepository.settings.first() }.getOrNull()
    }
    val darkTheme = loaded?.darkTheme ?: isSystemInDarkTheme()
    val settings = loaded ?: AppSettings()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    CompositionLocalProvider(
        LocalSettingsReport provides { message ->
            // Relaunched rather than shown in sequence: two failures in quick succession are two
            // facts, and `showSnackbar` would queue the second behind the first's full duration.
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(message)
            }
        },
    ) {
        EclipseTheme(darkTheme = darkTheme) {
            SettingsWindow(title = title, onClose = onClose, snackbarHostState = snackbarHostState) { content(settings) }
        }
    }
}

/**
 * The window chrome alone, for a destination that has no settings to read.
 *
 * The host form is the one screen that needs this: it is reached from the Hosts list rather than
 * from Settings, and its subject is a host, not a preference. Everything about the window - the bar,
 * the back arrow, the inset padding, the width cap - should still be identical, and sharing the
 * chrome is what keeps it identical.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsWindow(
    title: String,
    onClose: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    // A visible way back, not just the system gesture: this window is a destination
                    // reached from the app, and the arrow is what says so.
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        // Defaulted so a caller that has nothing to report - the host form is the one - does not have
        // to know this exists. The host still has to be *mounted* for [LocalSettingsReport] to have
        // anywhere to put a message, which is why [SettingsDestination] always passes its own.
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        SettingsBody(padding, content)
    }
}

/**
 * The scrolling body under the bar: the app's content column, in the same shape Settings uses.
 *
 * The horizontal padding goes *inside* the scroll rather than around it, which is the difference
 * between a list whose last row can be reached and one whose last row stops at the inset. The width
 * cap is the shell's own (`MainActivity` caps its content column at 1280dp), so a settings
 * destination reads as the same app on a tablet rather than as an edge-to-edge outlier.
 */
@Composable
private fun SettingsBody(padding: PaddingValues, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .padding(padding)
            .fillMaxSize()
            .widthIn(max = 1280.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        content = content,
    )
}