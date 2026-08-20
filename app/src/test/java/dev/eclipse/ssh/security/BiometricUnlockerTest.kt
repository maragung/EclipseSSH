package dev.eclipse.ssh.security

import androidx.fragment.app.FragmentActivity
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

/**
 * Whether biometric unlock is usable is answered before a prompt is raised, not by one failing.
 *
 * The settings toggle and the lock screen both used to raise a prompt and wait for it to come back
 * with an error, which on a device with no sensor at all meant a dialog that could never succeed and
 * the message "Authentication failed" for something the user cannot do anything about. The four
 * unavailable cases - no hardware, temporarily unavailable, nothing enrolled, security update
 * required - are different situations and only one of them is worth retrying, so each has its own
 * sentence, and the answer is available without asking the user for anything.
 *
 * There is no biometric hardware under Robolectric and the emulator's answer is not something to
 * write assertions against, so what is pinned here is the contract rather than a particular verdict:
 * the two entry points always agree, an unavailable answer is always a real sentence a person can
 * read, and nothing about a missing sensor or a hostile system service can throw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BiometricUnlockerTest {

    private lateinit var controller: ActivityController<FragmentActivity>
    private val activity: FragmentActivity get() = controller.get()
    private val unlocker = BiometricUnlocker()

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(FragmentActivity::class.java).create()
    }

    @After
    fun tearDown() {
        controller.close()
    }

    @Test
    fun `the two entry points cannot disagree`() {
        assertThat(unlocker.canAuthenticate(activity)).isEqualTo(unlocker.unavailableReason(activity) == null)
    }

    @Test
    fun `an unavailable answer is a sentence, not a status code`() {
        val reason = unlocker.unavailableReason(activity) ?: return

        assertWithMessage("the reason shown to the user was blank").that(reason.trim()).isNotEmpty()
        // A status code leaking into the UI is the failure this guards against.
        assertThat(reason.trim().toIntOrNull()).isNull()
        assertThat(reason).isNotEqualTo("null")
    }

    @Test
    fun `the answer is stable across repeated calls`() {
        val first = unlocker.unavailableReason(activity)

        repeat(3) { assertThat(unlocker.unavailableReason(activity)).isEqualTo(first) }
    }

    @Test
    fun `asking costs the user nothing - no prompt is raised`() {
        unlocker.canAuthenticate(activity)
        unlocker.unavailableReason(activity)

        // A BiometricPrompt is a fragment; checking availability must not add one.
        assertThat(activity.supportFragmentManager.fragments).isEmpty()
    }
}
