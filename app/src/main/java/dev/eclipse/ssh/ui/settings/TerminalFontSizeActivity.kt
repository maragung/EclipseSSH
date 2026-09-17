package dev.eclipse.ssh.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dagger.hilt.android.AndroidEntryPoint
import dev.eclipse.ssh.data.model.AppSettings
import dev.eclipse.ssh.data.settings.SettingsRepository
import kotlinx.coroutines.launch

/**
 * Terminal font size, on a slider rather than a list of choices.
 *
 * It is the one settings number that is a *range* and not a menu - the terminal's text size has a
 * legible floor and a practical ceiling and everything between them is a real answer - so it cannot
 * ride the [ChoiceDestinationActivity] base. The slider is kept exactly as the dialog had it,
 * including its live "12 sp" readout, because the value is the thing being chosen and a slider with
 * no number on it asks the user to guess.
 *
 * The write happens on release rather than on every frame of the drag: `Slider` reports a continuous
 * stream of values while the thumb is held, and persisting each one would be a DataStore write per
 * pixel for a number the user has not finished choosing.
 */
@AndroidEntryPoint
class TerminalFontSizeActivity : SettingsDestinationActivity() {

    override val screenTitle = "Terminal font size"

    @Composable
    override fun Body(settings: AppSettings, repository: SettingsRepository) {
        val range = SettingsRepository.TERMINAL_FONT_SIZE_RANGE
        var size by remember(settings.terminalFontSize) {
            mutableFloatStateOf(SettingsRepository.normalizeFontSize(settings.terminalFontSize).toFloat())
        }
        val scope = rememberCoroutineScope()
        val report = LocalSettingsReport.current
        SettingsSection(screenTitle) {
            SettingRow(
                Icons.Default.Terminal,
                screenTitle,
                "Pinch the terminal with two fingers to zoom without opening this screen",
            ) {
                Text("${size.toInt()} sp", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = size,
                onValueChange = { size = it },
                onValueChangeFinished = {
                    val settled = SettingsRepository.normalizeFontSize(size.toInt())
                    scope.launch {
                        try {
                            repository.setTerminalFontSize(settled)
                        } catch (failure: Throwable) {
                            reportWriteFailure(report, "the terminal font size", failure)
                        }
                    }
                },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                // One stop per whole sp: the setting is an integer, and a slider that could land
                // between two of them would show a value the store cannot hold.
                steps = range.last - range.first - 1,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 14.dp),
            )
        }
    }
}