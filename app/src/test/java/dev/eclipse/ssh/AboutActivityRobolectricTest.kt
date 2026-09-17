package dev.eclipse.ssh

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertWithMessage
import dev.eclipse.ssh.feature.about.OPEN_SOURCE_LICENSES
import dev.eclipse.ssh.ui.about.AboutActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The About screen, in its own window.
 *
 * Separate from [SettingsAboutRobolectricTest] because the subject is different: that class pins the
 * wiring from Settings, this one pins what the window actually shows. It is possible to hold both in
 * one class only if the activity is launched for real, and launching it here rather than recording a
 * `startActivity` is the whole point — an intent that names the right class proves nothing about
 * whether the screen behind it composes.
 *
 * This is also where the assertions the dialog could not make now live. Under Robolectric an
 * `AlertDialog` never settles `waitForIdle`, which is why the old suite had to read its text through
 * hand-pumped `fetchSemanticsNodes` and could reach only the rows that happened to compose above the
 * fold. A real activity window has none of those limits: `assertIsDisplayed` idles normally, and the
 * licence list can be *scrolled*, which is the specific thing the change was for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
class AboutActivityRobolectricTest {

    @get:Rule
    val compose = createAndroidComposeRule<AboutActivity>()

    /**
     * The screen answers the three questions an About screen exists for: what version is this, who
     * made it, and where is the source.
     *
     * The version line is read from the PackageManager in the test, the same source the screen reads
     * — so the assertion is that About reports what Android reports, not that it spells a version the
     * test also hardcoded.
     */
    @Test
    fun theScreenAnswersVersionAuthorAndSource() {
        compose.waitForIdle()

        val info = compose.activity.packageManager.getPackageInfo(compose.activity.packageName, 0)
        compose.onNodeWithText("About EclipseSSH").assertIsDisplayed()
        compose.onNodeWithText("Version ${info.versionName} (${info.longVersionCode})").assertIsDisplayed()
        compose.onNodeWithText("Created by Maragung").assertIsDisplayed()
        compose.onNodeWithText("Source code · github.com/maragung/EclipseSSH").assertIsDisplayed()
        compose.onNodeWithText("Libraries").assertIsDisplayed()
    }

    /**
     * The licence list scrolls, all the way to its last row.
     *
     * This is the assertion the dialog made impossible. Its body was capped at 70% of the screen
     * height, so most of the list never composed, and the suite said so in a comment rather than
     * asserting the rows it could not reach. Here the list is driven to its final entry and that
     * entry is asserted *displayed* — so a regression that re-capped the height, or replaced the
     * `LazyColumn` with a non-scrolling `Column`, fails here instead of passing quietly.
     *
     * The list is read from [OPEN_SOURCE_LICENSES] rather than written out, for the same reason the
     * screen renders it from there: a hardcoded name would pass after the entry was renamed, which is
     * exactly when the screen would be showing something stale.
     */
    @Test
    fun theLicenceListScrollsToItsLastRow() {
        compose.waitForIdle()

        // A one-entry list would satisfy everything below without ever scrolling, which would make
        // the test pass in precisely the case it is supposed to catch.
        assertWithMessage("a list this short makes the scroll assertion vacuous")
            .that(OPEN_SOURCE_LICENSES.size).isGreaterThan(1)

        val last = OPEN_SOURCE_LICENSES.last()
        val lastLabel = "${last.name} ${last.version}"
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(lastLabel))
        compose.onNodeWithText(lastLabel).assertIsDisplayed()
    }
}
