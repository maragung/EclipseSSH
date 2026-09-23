package dev.eclipse.ssh.linux

import kotlin.math.roundToInt

/**
 * Which halves of an install a run will traverse.
 *
 * [LinuxUserspaceManager.install] and [LinuxUserspaceManager.repair] share one pipeline, but a
 * repair that finds an extracted rootfs skips the rootfs half entirely — so a ladder that always
 * began at the download would show a repair starting a third of the way through, which is a number
 * about nothing. The plan is what lets [LinuxInstallProgress] answer for what *this* run has left
 * to do.
 */
enum class InstallPlan {
    /** Download, verify and extract, then the setup pipeline, then the health check. */
    FULL,

    /** The rootfs is on disk and complete: the setup pipeline, then the health check. */
    SETUP_ONLY,
}

/**
 * How far through an install a [LinuxInstallStep] is, as a whole number of percent — the figure the
 * Ubuntu screen, the host-list summary and the foreground notification all show.
 *
 * ## What the number is, and what it is not
 *
 * An install is not one measurable quantity. The download has a known byte total, extraction
 * consumes a known tarball, and the two apt steps draw a progress bar of their own if they feel
 * like it — everything else is a step whose length nothing here can know in advance. So the figure
 * is a **schedule refined by measurement**: the pipeline's phases are given a share of the whole
 * from how long they actually take on a phone, and the phases that can measure themselves move
 * inside their share instead of jumping to its end. Reading it as a stopwatch would be wrong. It
 * is monotonic, it starts at 0, and it reaches 100 when the work is done — those are the three
 * things a progress number has to be, and they are why this is worth showing at all.
 *
 * ## Why a repair starts at 0 too
 *
 * The shares are renormalized to the phases [InstallPlan] says the run will traverse, so a repair
 * over an extracted rootfs is 0% at its first setup step rather than 36% — the rootfs half is not
 * late, it is not happening.
 *
 * ## Why the health check reads 100
 *
 * It is the last phase and the one that decides whether the install worked, and it is measured in
 * seconds. Its share is spent the moment it starts, so the bar reads 100% under "Running the health
 * check": the work is finished, and what remains is the verdict. A check that fails does not leave a
 * 100% standing — the screen replaces the whole row with the failure.
 */
object LinuxInstallProgress {

    /**
     * The share of a [InstallPlan.FULL] install each phase owns, in the order [position] numbers
     * them: download, verify, extract, setup, health.
     *
     * The setup phase's share is the largest because it is the longest by a wide margin — a package
     * list update and then the packages unpacked by dpkg over the network, twelve of them in the
     * base set and twenty-seven more if the user ticked every box. The download is next
     * and is the reason the figure moves at all on a slow connection. Verification is a hash over
     * the 30 MB that just arrived, which is under a second, and the health check is a handful of
     * short commands.
     */
    private val FULL_WEIGHTS = listOf(0.25f, 0.01f, 0.10f, 0.63f, 0.01f)

    /** The setup phase's index in [FULL_WEIGHTS]; [InstallPlan.SETUP_ONLY] starts here. */
    private const val SETUP_PHASE = 3

    /**
     * The share of the *setup* phase each [SetupStep] owns. The first four edit a file each and are
     * effectively instant; `UPDATE_PACKAGES`, `INSTALL_BASE_PACKAGES` and `INSTALL_EXTRA_PACKAGES`
     * are the pipeline's real cost; `VERIFY` runs `apt-get check` over the database.
     *
     * The order is [SetupStep]'s own declaration order, which is the order the pipeline emits them
     * in — [setupFraction] walks the enum, and the exhaustive `when` below is what makes a new step
     * a compile error here rather than a silently unweighted one.
     *
     * `INSTALL_EXTRA_PACKAGES`'s share is the one that can be spent on a step that never runs: it
     * takes the weight its package count deserves (twenty-seven packages at most, against the base
     * set's twelve, and npm globals on top) whether or not the user ticked anything. A run that
     * skips it does not
     * lose a fifth of the bar — the shares are cumulative positions inside the phase, so the next
     * step's percentage simply follows the previous one's, which is what "this step is not
     * happening" should look like.
     */
    private fun setupWeight(step: SetupStep): Float = when (step) {
        SetupStep.REGISTER_USER -> 0.02f
        SetupStep.PREPARE_WORKSPACE -> 0.03f
        SetupStep.CONFIGURE_DNS -> 0.02f
        SetupStep.CONFIGURE_APT -> 0.03f
        SetupStep.UPDATE_PACKAGES -> 0.28f
        SetupStep.INSTALL_BASE_PACKAGES -> 0.36f
        SetupStep.INSTALL_EXTRA_PACKAGES -> 0.19f
        SetupStep.VERIFY -> 0.07f
    }

    /**
     * The whole install's progress, 0 to 100, for a step of a run following [plan].
     *
     * Whole percent rather than a fraction so that the number beside the bar and the bar itself are
     * the same fact: a bar drawn from its own float would read "43%" beside a bar that is 43.4%
     * full, which is the kind of disagreement nobody can name but everybody notices.
     */
    fun percent(step: LinuxInstallStep, plan: InstallPlan = InstallPlan.FULL): Int {
        val (phase, within) = position(step)
        return percentAt(phase, within, plan)
    }

    /**
     * Where a step sits: which phase, and how far inside it.
     *
     * `Verifying` is placed mid-phase rather than at its start because it is a single hash with no
     * interior to report on, and a bar that moves once during it is a better account of a phase
     * than a bar that does not move at all.
     */
    private fun position(step: LinuxInstallStep): Pair<Int, Float> = when (step) {
        is LinuxInstallStep.Downloading -> 0 to downloadFraction(step)
        LinuxInstallStep.Verifying -> 1 to 0.5f
        is LinuxInstallStep.Extracting -> 2 to (step.fraction ?: 0f)
        is LinuxInstallStep.SettingUp -> SETUP_PHASE to setupFraction(step.step, step.fraction)
        LinuxInstallStep.VerifyingHealth -> 4 to 1f
    }

    private fun percentAt(phase: Int, within: Float, plan: InstallPlan): Int {
        // A SETUP_ONLY run renormalizes the phases it will actually traverse: the rootfs half is
        // not a quarter of the work skipped, it is work that does not exist for this run.
        val weights = when (plan) {
            InstallPlan.FULL -> FULL_WEIGHTS
            InstallPlan.SETUP_ONLY -> FULL_WEIGHTS.drop(SETUP_PHASE)
        }
        val index = when (plan) {
            InstallPlan.FULL -> phase
            InstallPlan.SETUP_ONLY -> phase - SETUP_PHASE
        }
        // Unreachable in practice — a SETUP_ONLY run never emits a rootfs step — and worth a
        // defined answer rather than an exception in a UI path: 0 is the honest reading of "this
        // run has nothing to show for that step".
        if (index < 0) return 0
        var done = 0f
        for (i in 0 until index) done += weights[i]
        done += weights[index] * within.coerceIn(0f, 1f)
        val total = weights.sum()
        return ((done / total) * 100f).roundToInt().coerceIn(0, 100)
    }

    /** The download's own fraction: bytes arrived of bytes announced, or 0 when no total is known. */
    private fun downloadFraction(step: LinuxInstallStep.Downloading): Float =
        if (step.total > 0L) (step.received.toFloat() / step.total.toFloat()) else 0f

    /** Where a setup step's own progress places it inside the setup phase. */
    private fun setupFraction(step: SetupStep, within: Float?): Float {
        var done = 0f
        for (candidate in SetupStep.entries) {
            if (candidate == step) break
            done += setupWeight(candidate)
        }
        return done + setupWeight(step) * (within ?: 0f).coerceIn(0f, 1f)
    }
}

/**
 * The share of the current step apt's own progress line reports, or null for a line that carries
 * none.
 *
 * apt draws `Progress: [ 45%]` on the last row of the pty while dpkg works, from a counter of
 * completed package steps over the run's total — so it climbs across the whole `apt-get install`,
 * not per package, and it is the one honest measurement the setup pipeline offers: the package list
 * is not known here, and guessing it would be a worse number than the schedule.
 *
 * The *last* match wins because one chunk of output can hold several redraws (apt separates them
 * with `\r`, and the line tracker splits on `\n`), and the last one is the newest. A line apt draws
 * in its "pulse" style carries no percentage and matches nothing, which leaves the caller's
 * previous reading standing — the same answer as a step nothing can measure.
 */
internal fun aptProgressFraction(line: String): Float? =
    APT_PROGRESS.findAll(line).lastOrNull()
        ?.groupValues
        ?.get(1)
        ?.toFloatOrNull()
        ?.div(100f)
        ?.coerceIn(0f, 1f)

/**
 * The readable part of a command's output line: the escapes and the redraw's own bar dropped, or
 * null when nothing readable is left.
 *
 * Under proot every command runs on a pty, so dpkg colours its text and repaints a progress bar
 * with cursor save/restore rather than plain lines — shown raw, the detail beside the step would
 * read `ESC7Progress: [ 45%] ████░░░░ESC8`, which names neither what is happening nor how far along
 * it is, the two things the percentage is there to say. Null lets the caller keep the last line
 * that did say something instead of blanking the row.
 */
internal fun progressDetail(line: String): String? =
    stripEscapes(line)
        .substringBefore(APT_PROGRESS_LABEL)
        .filter { it.code >= 0x20 && it.code != 0x7F }
        .trim()
        .takeIf { it.isNotEmpty() }

/**
 * apt's own progress line, as it draws it on a pty: `Progress: [ 45%]`, the number right-aligned
 * into three columns by apt's own format string. Matched a little loosely around the padding,
 * which is apt's business and may be spaced differently by a future version.
 */
private val APT_PROGRESS = Regex("Progress:\\s*\\[\\s*(\\d{1,3})\\s*%\\]")

/** The label [APT_PROGRESS] matches from; the text before it is the part worth showing. */
private const val APT_PROGRESS_LABEL = "Progress:"
