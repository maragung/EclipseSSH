package dev.eclipse.ssh.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The height cap for a dialog or sheet body, as a fraction of the screen.
 *
 * The fixed caps these replace (320-620dp) were sized for one phone: on a tall screen they left
 * half of it unused, and on a short one they were unreachable anyway. A fraction keeps the same
 * intent at every height - the body may take this much of the screen, and no more - and because
 * every body it caps ends in its own `LazyColumn` or `verticalScroll`, raising the ceiling can
 * only give the scroller more room; it can never push content off screen.
 *
 * `heightIn(max = ...)`, never `fillMaxHeight(fraction)`: `fillMaxHeight` sets the size rather
 * than capping it, so a three-line dialog would stretch into a mostly empty giant one.
 *
 * The arithmetic lives in a plain function so a unit test can pin it without Compose; the
 * composable wrapper only reads the screen height. [LocalConfiguration] propagates into the
 * windows Compose creates for dialogs and bottom sheets, so the screen is what it reports there
 * too.
 */
internal fun dialogBodyMaxHeight(screenHeightDp: Float, fraction: Float): Dp = (screenHeightDp * fraction).dp

/** [dialogBodyMaxHeight] against the current screen, re-read on configuration change. */
@Composable
internal fun rememberDialogBodyMaxHeight(fraction: Float): Dp {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    return remember(screenHeightDp, fraction) { dialogBodyMaxHeight(screenHeightDp.toFloat(), fraction) }
}
