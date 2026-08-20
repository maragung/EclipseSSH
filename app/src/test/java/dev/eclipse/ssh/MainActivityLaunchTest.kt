package dev.eclipse.ssh

import android.content.Intent
import android.os.Looper
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * First-run smoke test: launches the real MainActivity with the real Hilt
 * application (EclipseApp), so the DI graph, manifest, theme, onCreate and the
 * first Compose composition all execute. Any crash that would prevent the app
 * from opening on a fresh install shows up here as a test failure.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = EclipseApp::class, sdk = [35])
class MainActivityLaunchTest {

    @Test
    fun `fresh install opens and reaches resumed state with rendered UI`() {
        val controller = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
        )
        val activity = controller.setup().get()
        // Let Compose composition frames and viewModelScope init run.
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(activity.isFinishing).isFalse()
        assertThat(activity.lifecycle.currentState)
            .isAtLeast(Lifecycle.State.RESUMED)

        // setContent ran: the window content view is attached.
        val content = activity.findViewById<android.view.View>(android.R.id.content)
        assertThat(content).isNotNull()
    }

    @Test
    fun `recreating the activity on rotation does not crash`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.setup()
        shadowOf(Looper.getMainLooper()).idle()

        controller.recreate()
        shadowOf(Looper.getMainLooper()).idle()

        val activity = controller.get()
        assertThat(activity.isFinishing).isFalse()
        assertThat(activity.lifecycle.currentState)
            .isAtLeast(Lifecycle.State.RESUMED)
    }

    @Test
    fun `launching from an ssh deep link opens without crashing`() {
        val controller = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(Intent.ACTION_VIEW, "ssh://deploy@example.com:2222".toUri()),
        )
        val activity = controller.setup().get()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(activity.isFinishing).isFalse()
        assertThat(activity.lifecycle.currentState).isAtLeast(Lifecycle.State.RESUMED)
    }

    @Test
    fun `hostile deep links delivered to onCreate and onNewIntent never crash the activity`() {
        // The manifest exports ssh/sftp to BROWSABLE intents, so any web page can deliver these.
        // singleTask means a second link arrives through onNewIntent on the live instance, so both
        // entry points are exercised.
        val hostile = listOf(
            "ssh://",
            "ssh://@",
            "ssh://example.com:notaport",
            "ssh://example.com:99999",
            "ssh://" + "a".repeat(5_000),
            "http://example.com",
            "sftp://root@[2001:db8::1]:22",
        )

        hostile.forEach { raw ->
            val controller = Robolectric.buildActivity(
                MainActivity::class.java,
                Intent(Intent.ACTION_VIEW, raw.toUri()),
            )
            val activity = controller.setup().get()
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(activity.isFinishing).isFalse()

            controller.newIntent(Intent(Intent.ACTION_VIEW, raw.toUri()))
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(activity.isFinishing).isFalse()

            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `a view intent with no data does not crash`() {
        // ACTION_VIEW with a null Uri is reachable from a badly built external intent.
        val controller = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(Intent.ACTION_VIEW),
        )
        val activity = controller.setup().get()
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(activity.isFinishing).isFalse()
        assertThat(activity.lifecycle.currentState).isAtLeast(Lifecycle.State.RESUMED)
    }
}
