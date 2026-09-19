package dev.eclipse.ssh.linux

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The install percentage: the one number the Ubuntu window, the host-list line and the foreground
 * notification all show, and therefore the one place a disagreement between them could be born.
 *
 * The three properties asserted here are the ones a progress figure has to have to be worth
 * showing at all — it starts at 0, it never walks backwards, and it reaches 100 exactly when the
 * work does. Everything else about it (how the shares are split) is a schedule, and a schedule is
 * a judgement, not a fact a test can pin.
 */
class LinuxInstallProgressTest {

    private fun percent(step: LinuxInstallStep, plan: InstallPlan = InstallPlan.FULL): Int =
        LinuxInstallProgress.percent(step, plan)

    @Test
    fun `a full install climbs from zero to a hundred without ever going back`() {
        val walk = buildList {
            // The download, in the ticks the installer reports it in.
            for (received in 0L..100L step 10L) add(LinuxInstallStep.Downloading(received, 100L))
            add(LinuxInstallStep.Verifying)
            // Extraction ticks, as the tarball's consumed fraction grows.
            for (step in 0..10) add(LinuxInstallStep.Extracting(entries = step * 200, fraction = step / 10f))
            // Every setup step, each with apt's own bar running through it.
            for (step in SetupStep.entries) {
                add(LinuxInstallStep.SettingUp(step))
                add(LinuxInstallStep.SettingUp(step, fraction = 0.5f))
                add(LinuxInstallStep.SettingUp(step, fraction = 1f))
            }
            add(LinuxInstallStep.VerifyingHealth)
        }

        val readings = walk.map { percent(it) }

        assertThat(readings.first()).isEqualTo(0)
        assertThat(readings.last()).isEqualTo(100)
        assertThat(readings.zipWithNext().filter { (before, after) -> after < before }).isEmpty()
    }

    @Test
    fun `a measured step moves inside its own share instead of jumping to its end`() {
        val start = percent(LinuxInstallStep.SettingUp(SetupStep.INSTALL_BASE_PACKAGES))
        val half = percent(LinuxInstallStep.SettingUp(SetupStep.INSTALL_BASE_PACKAGES, fraction = 0.5f))
        val end = percent(LinuxInstallStep.SettingUp(SetupStep.INSTALL_BASE_PACKAGES, fraction = 1f))
        val next = percent(LinuxInstallStep.SettingUp(SetupStep.VERIFY))

        assertThat(start).isLessThan(half)
        assertThat(half).isLessThan(end)
        // The last package step's 100% is the *step's*, and the step after it still has its own
        // share to spend: a measurement that ran the whole install to 100 would make the last
        // stretch of work look like no work at all.
        assertThat(end).isLessThan(next)
    }

    @Test
    fun `the download is measured in bytes, not in guesses`() {
        assertThat(percent(LinuxInstallStep.Downloading(received = 0, total = 0))).isEqualTo(0)
        assertThat(percent(LinuxInstallStep.Downloading(received = 0, total = 200)))
            .isLessThan(percent(LinuxInstallStep.Downloading(received = 100, total = 200)))
    }

    @Test
    fun `a repair over an extracted rootfs starts at zero, because the rootfs half is not work it has`() {
        // The step that opened the run — the first setup step — is 0%, not the third of the ladder
        // the rootfs half would have left it at.
        assertThat(percent(LinuxInstallStep.SettingUp(SetupStep.REGISTER_USER), InstallPlan.SETUP_ONLY))
            .isEqualTo(0)
        // And a rootfs step, which such a run never emits, reads 0 rather than throwing in a UI path.
        assertThat(percent(LinuxInstallStep.Downloading(50, 100), InstallPlan.SETUP_ONLY)).isEqualTo(0)
    }

    @Test
    fun `a repair still reaches a hundred when the health check runs`() {
        assertThat(percent(LinuxInstallStep.VerifyingHealth, InstallPlan.SETUP_ONLY)).isEqualTo(100)
    }

    @Test
    fun `the state's own percentage is the step's, qualified by the plan it carries`() {
        val state = LinuxUserspaceState.Installing(
            LinuxInstallStep.SettingUp(SetupStep.REGISTER_USER),
            InstallPlan.SETUP_ONLY,
        )

        assertThat(state.percent).isEqualTo(0)
        assertThat(
            LinuxUserspaceState.Installing(LinuxInstallStep.SettingUp(SetupStep.REGISTER_USER)).percent,
        ).isEqualTo(LinuxInstallProgress.percent(LinuxInstallStep.SettingUp(SetupStep.REGISTER_USER)))
    }

    @Test
    fun `apt's progress line is read as the fraction it announces`() {
        assertThat(aptProgressFraction("Progress: [ 45%]")).isEqualTo(0.45f)
        // apt right-aligns the number into three columns; the padding is not a fact to depend on.
        assertThat(aptProgressFraction("Progress: [  5%]")).isEqualTo(0.05f)
        assertThat(aptProgressFraction("Progress: [100%]")).isEqualTo(1f)
    }

    @Test
    fun `the newest redraw on a line is the one that counts`() {
        // One chunk of pty output can hold several redraws: apt separates them with a bare carriage
        // return, which the line tracker splits on `\n` and therefore never sees.
        val redrawn = "Progress: [ 10%]\rProgress: [ 45%]\rProgress: [ 62%]"
        assertThat(aptProgressFraction(redrawn)).isEqualTo(0.62f)
    }

    @Test
    fun `a line with no percentage reads as no measurement, not as zero`() {
        assertThat(aptProgressFraction("Unpacking libssl3:amd64 (3.0.13) ...")).isNull()
        assertThat(aptProgressFraction("")).isNull()
    }

    @Test
    fun `the detail beside a step drops the escapes and the bar apt draws for itself`() {
        // What dpkg writes when its output is a pty: colour escapes, a cursor save, the bar.
        val line = "7[42mProgress: [ 45%][49m ████░░░░8"
        assertThat(progressDetail(line)).isNull()

        val action = "[1mUnpacking[0m libssl3:amd64 (3.0.13) ...\rProgress: [ 45%] ████"
        assertThat(progressDetail(action)).isEqualTo("Unpacking libssl3:amd64 (3.0.13) ...")
    }

    @Test
    fun `a blank line reads as nothing to say, so the caller keeps the last line that did`() {
        assertThat(progressDetail("   ")).isNull()
        assertThat(progressDetail("\r8")).isNull()
    }
}
