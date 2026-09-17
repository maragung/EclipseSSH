package dev.eclipse.ssh.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.data.model.AppSettings
import dev.eclipse.ssh.data.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Every settings destination Activity: a window whose whole content is one [SettingsDestination].
 *
 * This is a base *class* rather than a shared composable because the boilerplate it removes is
 * exactly the boilerplate a composable cannot carry: the injected repository, `enableEdgeToEdge()`
 * and `setContent`. The injection is a real `@Inject` field on a real `@AndroidEntryPoint` concrete
 * class, not a lookup through the Hilt graph - the field form is what Hilt documents, it fails at
 * build time when the type is unbound rather than at runtime, and the concrete subclasses here are
 * annotated for it.
 *
 * Nothing is handed over through the intent of any of these screens. Every one of them edits a value
 * that lives in `SettingsRepository`'s DataStore, and the Settings list reads that store through its
 * own Flow, so the caller (Settings, inside `MainActivity`) sees the change when it resumes without
 * a result code, a token, or a `ViewModel` shared between two windows. That is the whole reason this
 * change is as cheap as it is - see [SettingsDestination].
 */
abstract class SettingsDestinationActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    /** The bar's title, and the screen's subject. */
    protected abstract val title: String

    /** The screen's body. Receives the settings snapshot read when the window opened. */
    @Composable
    protected abstract fun Body(settings: AppSettings, repository: SettingsRepository)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SettingsDestination(
                settingsRepository = settingsRepository,
                title = title,
                onClose = { finish() },
            ) { settings -> Body(settings, settingsRepository) }
        }
    }
}

/**
 * A settings destination whose setting is a single number, chosen from a fixed list of choices.
 *
 * Six of the sixteen destinations are this shape, and were six near-identical `AlertDialog`s over
 * Settings: one chip per option, the current one selected, Apply and Cancel. Two things about a
 * dialog were wrong for that. Its body is capped at a fraction of the screen height, so a long
 * choice list had to scroll inside a letterbox; and choosing a value is a place you go and come back
 * from, not a question asked before you may continue. Promoting them costs a class each - and this
 * base is most of what that class would have been, so what is left per destination is the title, the
 * subtitle, the icon, the choices, and the one line that writes the choice down.
 */
abstract class ChoiceDestinationActivity : SettingsDestinationActivity() {

    /** The one line under the bar explaining what the number means. */
    protected abstract val subtitle: String

    /** The icon the row carried in the Settings list, so the promoted screen reads as the same row. */
    protected abstract val icon: ImageVector

    /** The values on offer, in the order they are shown. */
    protected abstract val options: List<Int>

    /** Which of the settings this screen edits. */
    protected abstract fun stored(settings: AppSettings): Int

    /**
     * Write the chosen value down. Suspends: a DataStore write is disk I/O.
     *
     * Declared `Unit`-returning, and every override below therefore writes a block body rather than
     * `= repository.setX(value)`. The setters on [SettingsRepository] return DataStore's
     * `Preferences`, so the expression form infers *that* as the override's return type and fails
     * with `RETURN_TYPE_MISMATCH_ON_OVERRIDE` against this declaration - five times, once per
     * destination. The block body discards the value, which is what a caller of this method wants:
     * the result of a settings write is not a value anyone here reads.
     */
    protected abstract suspend fun write(repository: SettingsRepository, value: Int)

    /**
     * How this setting is named when its write fails.
     *
     * These are the view model's own strings, lifted rather than reinvented: every one of them was
     * already the wording of a `writeSetting("the …")` call, and a failure that has moved out of
     * `MainViewModel` should not also change how it reads.
     */
    protected open val what: String get() = "the ${title.lowercase()}"

    /**
     * How a value is spelled on its chip. "Off" and "Fit screen" say more than a bare 0 does, and it
     * is the same wording the dialog used.
     */
    protected open fun optionLabel(option: Int): String = if (option == 0) "Off" else "$option s"

    /**
     * Which choice the screen opens on, when the stored value is not one of [options].
     *
     * Only `clearClipboardAfterSeconds` needs this: its list deliberately omits 300 and above, so a
     * value configured before the list was trimmed would otherwise select no chip at all and the
     * screen would look as though the setting had no value. Falling back to the nearest offered
     * choice is the honest reading of a stored number that is no longer on the menu.
     */
    protected open fun initialChoice(stored: Int): Int = stored

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    final override fun Body(settings: AppSettings, repository: SettingsRepository) {
        val storedValue = stored(settings)
        // The chosen value is held here rather than re-read from the store: the write below is
        // asynchronous, and a screen that showed the old number for a frame after the tap would read
        // as a dropped tap. This is the same local `selected` state the dialogs this replaces kept.
        var chosen by remember(storedValue) { mutableIntStateOf(initialChoice(storedValue)) }
        val scope = rememberCoroutineScope()
        val report = LocalSettingsReport.current
        SettingsSection(title) {
            SettingRow(icon, title, subtitle) {
                // The row's own control is now the value it currently holds, which is the one thing
                // a dialog had no place to put: the chips below say it too, but a user who scrolled
                // past them should not have to scroll back to read what is set.
                Text(
                    optionLabel(chosen),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            // The choices fill the body a dialog had to letterbox. `FlowRow`, not the dialog's
            // horizontally scrolling row: with the whole window to spend, a wrapping grid shows
            // every choice at once instead of hiding the ones that did not fit behind a gesture
            // nobody knows is there.
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                options.forEach { option ->
                    FilterChip(
                        selected = option == chosen,
                        onClick = {
                            chosen = option
                            // The chosen value is shown before the write lands, so a failure cannot
                            // leave the screen claiming a value the store does not hold: the report
                            // below is what says so, and the chip stays where the user put it so a
                            // retry is one tap rather than a re-selection of the same chip.
                            scope.launch {
                                try {
                                    write(repository, option)
                                } catch (failure: Throwable) {
                                    reportWriteFailure(report, what, failure)
                                }
                            }
                        },
                        label = { Text(optionLabel(option)) },
                    )
                }
            }
        }
    }
}

/** Keep-alive interval: how often the transport is prodded so a NAT does not silently drop it. */
@AndroidEntryPoint
class KeepAliveActivity : ChoiceDestinationActivity() {
    override val title = "Keep-alive interval"
    override val what = "the keep-alive interval"
    override val subtitle = "Seconds between SSH keep-alive signals"
    override val icon = Icons.Default.Wifi
    override val options = listOf(15, 30, 60, 120, 300)
    override fun stored(settings: AppSettings) = settings.keepAliveSeconds
    override suspend fun write(repository: SettingsRepository, value: Int) { repository.setKeepAliveSeconds(value) }
}

/** Clipboard auto-clear: how long a copied secret is allowed to sit in the system clipboard. */
@AndroidEntryPoint
class ClipboardClearActivity : ChoiceDestinationActivity() {
    override val title = "Clipboard auto-clear"
    override val what = "the clipboard timeout"
    override val subtitle = "Clear copied secrets after (0 = never)"
    override val icon = Icons.Default.Security
    override val options = listOf(0, 15, 30, 60, 120)
    override fun stored(settings: AppSettings) = settings.clearClipboardAfterSeconds
    override suspend fun write(repository: SettingsRepository, value: Int) { repository.setClipboardSeconds(value) }

    // The offered list stops at 120 s. Nothing in the app writes more than that, but a value set by
    // an older build could still be in the store, and a screen with no chip selected would read as
    // a setting that has no value rather than one that is simply off this menu.
    override fun initialChoice(stored: Int) = if (stored in options) stored else 120
}

/** Reconnect delay: the wait before the first retry, which doubles on each further attempt. */
@AndroidEntryPoint
class ReconnectDelayActivity : ChoiceDestinationActivity() {
    override val title = "Reconnect delay"
    override val what = "the reconnect delay"
    override val subtitle = "Wait before the first reconnect attempt; each further attempt doubles it"
    override val icon = Icons.Default.Refresh
    override val options = SettingsRepository.RECONNECT_BASE_CHOICES
    override fun stored(settings: AppSettings) = settings.reconnectBaseSeconds
    override suspend fun write(repository: SettingsRepository, value: Int) { repository.setReconnectBaseSeconds(value) }
}

/** Auto-lock vault: how long the app may sit in the background before the PIN is asked for again. */
@AndroidEntryPoint
class VaultAutoLockActivity : ChoiceDestinationActivity() {
    override val title = "Auto-lock vault"
    override val what = "the vault auto-lock delay"
    override val subtitle = "Starts counting when you leave the app; the vault re-locks when you come back"
    override val icon = Icons.Default.Lock
    override val options = SettingsRepository.VAULT_AUTO_LOCK_CHOICES
    override fun stored(settings: AppSettings) = settings.vaultAutoLockMinutes
    override suspend fun write(repository: SettingsRepository, value: Int) { repository.setVaultAutoLockMinutes(value) }
    override fun optionLabel(option: Int) = if (option == 0) "Never" else "$option min"
}

/** Terminal width: the narrowest terminal the server is told it has, whatever the screen fits. */
@AndroidEntryPoint
class TerminalWidthActivity : ChoiceDestinationActivity() {
    override val title = "Terminal width"
    override val what = "the terminal width"
    override val subtitle = "The narrowest the server is told it has; drag the grid sideways for the rest"
    override val icon = Icons.Default.Terminal
    override val options = SettingsRepository.TERMINAL_MIN_COLUMN_CHOICES
    override fun stored(settings: AppSettings) = settings.terminalMinColumns
    override suspend fun write(repository: SettingsRepository, value: Int) { repository.setTerminalMinColumns(value) }
    override fun optionLabel(option: Int) = if (option == 0) "Fit screen" else "$option cols"
}