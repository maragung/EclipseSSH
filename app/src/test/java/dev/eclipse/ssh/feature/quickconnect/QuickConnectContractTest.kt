package dev.eclipse.ssh.feature.quickconnect

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import dev.eclipse.ssh.feature.quickconnect.QuickConnectContract.EXTRA_QUICK_CONNECT_LAST
import dev.eclipse.ssh.feature.quickconnect.QuickConnectContract.isQuickConnect
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the intent→request contract the Quick Settings tile, the home-screen widget and the main
 * activity share.
 *
 * The tile and the widget put [EXTRA_QUICK_CONNECT_LAST] on the launch intent; the activity's
 * `maybeQuickConnect` recognises it through [isQuickConnect] and dials the last host. This is the
 * quick-connect analogue of [dev.eclipse.ssh.parseSshDeepLink]'s coverage: extraction pinned as a
 * pure function, at the same level. If the recognition ever regressed — the extra renamed on one
 * side only, the boolean default flipped — every tile and widget tap would fall through to an
 * ordinary "open the app", which is exactly the original bug and a bug with no visible symptom.
 *
 * Runs under Robolectric because android.content.Intent is a stubbed platform class on the host JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QuickConnectContractTest {

    @Test
    fun `an intent carrying the extra is recognized as a quick connect`() {
        val intent = Intent().putExtra(EXTRA_QUICK_CONNECT_LAST, true)
        assertThat(isQuickConnect(intent)).isTrue()
    }

    @Test
    fun `a tile or widget style launch intent is recognized`() {
        // Built exactly as QuickConnectTileService and QuickConnectWidget build it. The flags do not
        // affect recognition, but constructing the real intent shape shows the extraction the
        // activity performs on a genuine tap behaves.
        val intent = Intent().apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_QUICK_CONNECT_LAST, true)
        }
        assertThat(isQuickConnect(intent)).isTrue()
    }

    @Test
    fun `a plain launcher intent is not a quick connect`() {
        // What tapping the app icon delivers: ACTION_MAIN with no quick-connect extra. It must fall
        // through to a normal open.
        assertThat(isQuickConnect(Intent(Intent.ACTION_MAIN))).isFalse()
    }

    @Test
    fun `the extra set to false is not a quick connect`() {
        val intent = Intent().putExtra(EXTRA_QUICK_CONNECT_LAST, false)
        assertThat(isQuickConnect(intent)).isFalse()
    }

    @Test
    fun `a null intent is not a quick connect`() {
        assertThat(isQuickConnect(null)).isFalse()
    }
}
