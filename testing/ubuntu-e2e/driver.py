#!/usr/bin/env python3
"""The Ubuntu-installation E2E driver: drives the REAL app on an emulator through
the REAL user flow - launch, Settings, Install, the confirmation dialog, the
minutes-long download/extract/setup - and proves the result the same way a user
would have to: by using it.

Design contract (the pipeline's whole point):

  - No mocking anywhere. The app downloads the pinned Ubuntu Base rootfs over
    the network, verifies its SHA-256, extracts it, configures apt inside proot
    and installs the toolchain. The driver only watches and pokes the UI.
  - The UI says "Installed and verified" is NOT the pass criterion - it is the
    point where the deep verification starts. The instrumented
    UbuntuE2eVerificationTest then executes commands in the installed
    environment (shell, os-release, account, DNS, apt, HTTP, persistence).
  - A phase that fails records structured evidence and does not end the run:
    later phases that depend on it skip, everything else still executes, so one
    broken leg never costs the diagnostics of another.

Phases (mode selects which):

  SMOKE     preflight, install, verify (write), terminal-UI
  STANDARD  SMOKE + install-time lifecycle exercises (background, rotation,
            screen off) + persistence across app restart (force-stop + read)
  FULL      STANDARD + storage-failure gate + process-kill mid-install
            recovery + network-cut mid-install recovery

Usage (CI):
  python3 driver.py --out out --mode STANDARD --install-timeout 30
Local (after scripts/e2e-ubuntu.sh has installed a build):
  python3 driver.py --out out --mode SMOKE --keep-data
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
import traceback

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "universal"))
from adbutil import Adb  # noqa: E402  (shared guarded-adb layer)

VERIFICATION_CLASS = "dev.eclipse.ssh.linux.UbuntuE2eVerificationTest"

# The UI strings the driver keys on. They are the app's real labels (MainActivity's
# LinuxUserspaceSection); a renamed label breaks the driver loudly rather than
# silently tapping the wrong thing, which is the failure mode that matters here.
LABEL_INSTALL_ROW = "Not installed"  # the NotInstalled row's subtitle prefix
LABEL_INSTALLING = "Installing"
LABEL_INSTALLED = "Installed and verified"
LABEL_NEEDS_REPAIR = "Needs repair"
LABEL_LOCAL_CARD = "Local Ubuntu"
BUTTON_INSTALL = "Install"
BUTTON_REPAIR = "Repair"

# The install screen's step subtitles (MainActivity's describeInstallStep /
# describeSetupStep) - the line under the title that says WHERE the install is.
# Matched with startswith so the title ("Installing Ubuntu 22.04 LTS") never
# poses as a step; the driver logs the pair so a hang names its step
# ("Configuring DNS") instead of only the state it never left - the gap that
# made the 2026-09 hung run read as a title-only timeout.
STEP_SUBTITLES = (
    "Downloading",
    "Verifying the download",
    "Verifying",
    "Extracting",
    "Creating the ubuntu account",
    "Preparing the workspace",
    "Configuring DNS",
    "Configuring package sources",
    "Updating package lists",
    "Installing the base packages",
    "Installing Node.js",
    "Installing pnpm and the OpenCode CLI",
    "Running the health check",
)

POLL_SECONDS = 10
PROGRESS_SHOT_EVERY = 60


class PhaseResult:
    def __init__(self, name):
        self.name = name
        self.status = "pass"  # pass | fail | skip
        self.stage = ""       # the fine-grained break point, for the report
        self.detail = ""
        self.duration_s = 0.0
        self.evidence = []

    def to_dict(self):
        return {
            "phase": self.name,
            "status": self.status,
            "stage": self.stage,
            "detail": self.detail,
            "durationSeconds": round(self.duration_s, 1),
            "evidence": self.evidence,
        }


class E2eDriver:
    def __init__(self, adb, package, test_package, out_dir, mode, install_timeout_min,
                 keep_data=False, verbose=False):
        self.adb = adb
        self.package = package
        self.test_package = test_package
        self.out = out_dir
        self.mode = mode
        self.install_timeout_min = install_timeout_min
        self.keep_data = keep_data
        self.verbose = verbose
        self.results = []
        self.crash_findings = []
        self.shot_dir = os.path.join(out_dir, "screenshots")
        os.makedirs(self.shot_dir, exist_ok=True)
        self._last_progress_shot = 0.0

    # ------------------------------------------------------------ plumbing

    def log(self, message):
        stamp = time.strftime("%H:%M:%S")
        print("[%s] %s" % (stamp, message), flush=True)

    def screenshot(self, name):
        path = os.path.join(self.shot_dir, "%s-%s.png" % (name, time.strftime("%H%M%S")))
        if self.adb.screencap(path):
            return path
        return None

    def dump(self):
        return self.adb.ui_dump() or []

    def find(self, elements, *needles, exact=False):
        """First element whose text or content-desc contains (or equals) a needle."""
        for el in elements:
            for attr in (el.attrs.get("text", ""), el.attrs.get("content-desc", "")):
                if not attr:
                    continue
                for needle in needles:
                    if (attr == needle) if exact else (needle in attr):
                        return el
        return None

    def install_label(self, elements):
        """The Installing state's on-screen label: the title, plus the step
        subtitle when one is showing - so a stuck install names its step, not
        just the state it never left."""
        installing = self.find(elements, LABEL_INSTALLING)
        if not installing:
            return None
        title = installing.attrs.get("text", "")[:120]
        subtitle = None
        for el in elements:
            text = el.attrs.get("text", "")
            if text and any(text.startswith(prefix) for prefix in STEP_SUBTITLES):
                subtitle = text[:120]
                break
        return "%s | %s" % (title, subtitle) if subtitle else title

    def tap(self, element):
        x, y = element.center
        if x is None:
            return False
        return self.adb.tap(x, y)

    def tap_last(self, elements, needle):
        """Tap the LAST match: dialogs render after the screen behind them, so the
        dialog's button is the trailing one when both are in the hierarchy."""
        matches = [el for el in elements
                   if needle in (el.attrs.get("text", "") + el.attrs.get("content-desc", ""))]
        if not matches:
            return False
        return self.tap(matches[-1])

    def visible(self, *needles):
        return self.find(self.dump(), *needles) is not None

    def _screen_digest(self, limit=40):
        """Every text and content-desc on the current screen, deduplicated, for
        failure messages. The app's own words are the evidence a screenshot
        cannot carry: the install row's subtitle and the 'Last operation' error
        row are the self-identifying detail of a failed install, and uiautomator
        dumps are not otherwise uploaded."""
        texts = []
        for el in self.dump():
            for attr in (el.attrs.get("text", ""), el.attrs.get("content-desc", "")):
                if attr and attr not in texts:
                    texts.append(attr)
        return texts[:limit]

    def wait_visible(self, needles, timeout_s, poll=POLL_SECONDS):
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            els = self.dump()
            hit = self.find(els, *needles)
            if hit:
                return hit
            time.sleep(poll)
        return None

    def phase(self, name, requires=()):
        """Context manager: runs a phase, times it, records the result. `requires`
        names phases that must have passed - an unmet dependency skips, it does
        not fail: the dependency's failure is the root cause, not this one."""
        result = PhaseResult(name)
        self.results.append(result)
        missing = [r for r in self.results if r.name in requires and r.status != "pass"]
        if missing:
            result.status = "skip"
            result.detail = "skipped: %s did not pass" % ", ".join(m.name for m in missing)
            self.log("SKIP %s (%s)" % (name, result.detail))
            return _NullContext()
        started = time.monotonic()
        self.log("PHASE %s: start" % name)
        return _PhaseContext(self, result, started)

    def finish(self, result, started, exc_info):
        result.duration_s = time.monotonic() - started
        if exc_info is None:
            self.log("PHASE %s: PASS (%.0fs)" % (result.name, result.duration_s))
        else:
            result.status = "fail"
            kind, err, tb = exc_info
            result.detail = "%s: %s" % (kind.__name__, err)
            result.stage = self._stage_from_error(str(err))
            self.log("PHASE %s: FAIL at %s - %s" % (result.name, result.stage, result.detail))
            shot = self.screenshot("fail-" + result.name)
            if shot:
                result.evidence.append(shot)
            self._save_screen(result.name)
        self._scan_crashes(result)
        self._write_results()

    def _stage_from_error(self, message):
        """The coarse stage attribution the failure report's table wants."""
        m = re.search(r"([A-Z-]+(?: [A-Z-]+)*) stage", message)
        if m:
            return m.group(1)
        for key, stage in (("checksum", "VERIFY"), ("extract", "EXTRACT"),
                           ("download", "DOWNLOAD"), ("proot", "PROOT"),
                           ("apt", "APT"), ("dns", "DNS")):
            if key in message.lower():
                return stage.upper()
        return "UI"

    def _scan_crashes(self, result):
        try:
            fresh = self.adb.new_crash_lines()
        except Exception as exc:  # a broken scan must not mask the phase verdict
            self.log("crash scan failed: %s" % exc)
            return
        if fresh:
            self.crash_findings.extend(fresh)
            result.detail = (result.detail + " | " if result.detail else "") + \
                "crash signatures: %s" % ", ".join(f["kind"] for f in fresh)
            if result.status == "pass":
                # A passing phase that left a crash behind is a failed phase:
                # the point of the scan is the crashes the happy path misses.
                result.status = "fail"
                result.stage = "CRASH"
            self._dump_crash_context(fresh)

    def _dump_crash_context(self, fresh):
        path = os.path.join(self.out, "crashes-%s.txt" % time.strftime("%H%M%S"))
        with open(path, "w") as fh:
            for crash in fresh:
                fh.write("== %s ==\n%s\n\n" % (crash["kind"], crash.get("stack", crash["line"])))
        self.results[-1].evidence.append(path)

    def _save_screen(self, tag):
        """The dumped hierarchy's labelled nodes, as an artifact beside the
        failure screenshot. The screenshot shows pixels; this is the app's own
        text for what it was showing - what a diagnosis reads first."""
        try:
            els = self.dump()
        except Exception as exc:  # evidence collection must never mask the verdict
            self.log("screen capture failed: %s" % exc)
            return None
        path = os.path.join(self.out, "screen-%s-%s.txt" % (tag, time.strftime("%H%M%S")))
        try:
            with open(path, "w") as fh:
                for el in els:
                    text = el.attrs.get("text", "")
                    desc = el.attrs.get("content-desc", "")
                    if text or desc:
                        fh.write("text=%r desc=%r bounds=%s\n" %
                                 (text, desc, el.attrs.get("bounds", "")))
        except OSError as exc:
            self.log("screen capture failed: %s" % exc)
            return None
        self.results[-1].evidence.append(path)
        self.log("screen dump saved: %s" % path)
        return path

    def _write_results(self):
        with open(os.path.join(self.out, "phase-results.json"), "w") as fh:
            json.dump({
                "mode": self.mode,
                "phases": [r.to_dict() for r in self.results],
            }, fh, indent=2)

    # ------------------------------------------------------------ app navigation

    def launch(self):
        ok, out = self.adb.am_start(self.package, ".MainActivity")
        if not ok:
            raise RuntimeError("the app did not launch: %s" % out[:300])
        time.sleep(4)

    def open_settings(self):
        """Settings is a bottom tab; always one tap away from anywhere."""
        if not self.tap_last(self.dump(), "Settings"):
            raise RuntimeError("the Settings tab was not found on screen")
        time.sleep(2)

    def _scroll_swipe(self, els):
        """One upward scroll swipe sized to the current orientation. The dump's
        own geometry is the only coordinate system that is always right: the
        rotation exercise runs its still-alive check while the display is
        landscape, where the portrait-fitting y=1400 is past the screen edge
        and the injected swipe scrolls nothing - twelve no-op swipes left the
        Linux section off-screen and a live install was judged dead (E2E run
        34918470332). The root node of a uiautomator dump spans the window,
        rotation included, so the largest bounds ARE the current extent."""
        width = height = 0
        for el in els:
            b = el.bounds
            if b:
                width = max(width, b[0] + b[2] // 2)
                height = max(height, b[1] + b[3] // 2)
        if width < 100 or height < 100:
            # A sparse or failed dump has nothing to anchor on; the portrait
            # default matches the emulator's natural orientation, which is what
            # every other phase of the run is in.
            width, height = 1080, 2400
        x = width // 2
        self.adb.swipe(x, int(height * 0.58), x, int(height * 0.21), 400)

    def scroll_to_linux_section(self, max_swipes=24):
        """The Linux userspace section sits far down the settings list; swipe until
        its row is on screen. Swiping is content-anchored: stop the moment the
        row is visible, so over-scrolling never skips past it. Landscape shows
        less of the list per screen, so the cap is generous - the loop exits on
        first sight and a higher cap costs nothing when the section is near."""
        for _ in range(max_swipes):
            els = self.dump()
            if self.find(els, "Ubuntu on this device", "Linux userspace"):
                return True
            self._scroll_swipe(els)
            time.sleep(1.2)
        return self.visible("Ubuntu on this device", "Linux userspace")

    def scroll_to_install_button(self, max_swipes=6):
        """The section's action row sits below the NotInstalled row, and since the
        version chooser that row also carries a dropdown - so on a fresh install
        the section is taller than one screen and the Install button lands below
        the fold. scroll_to_linux_section stops at the section title, and a
        uiautomator dump only contains on-screen nodes, so tapping without this
        walk fails with 'the Install button was not found' (E2E run 34869834710).
        The section is followed only by About, whose rows never say Install, so
        the first visible Install belongs to the action row."""
        for _ in range(max_swipes):
            els = self.dump()
            if self.find(els, BUTTON_INSTALL):
                return True
            self._scroll_swipe(els)
            time.sleep(1.2)
        return self.visible(BUTTON_INSTALL)

    def start_install_via_ui(self):
        """Settings -> Linux userspace -> Install -> the confirmation dialog's
        Install. Returns once the Installing state is on screen."""
        self.open_settings()
        if not self.scroll_to_linux_section():
            raise RuntimeError("the Linux userspace settings section was never visible")
        if not self.find(self.dump(), LABEL_INSTALL_ROW, LABEL_NEEDS_REPAIR, LABEL_INSTALLED):
            raise RuntimeError("no Ubuntu row to install from - unexpected section state")
        if not self.scroll_to_install_button():
            raise RuntimeError("the Install button was not found")
        if not self.tap_last(self.dump(), BUTTON_INSTALL):
            raise RuntimeError("the Install row's Install button could not be tapped")
        time.sleep(1.5)
        # The confirmation dialog. Its Install button is the trailing match - the
        # row's button is still in the hierarchy behind the dialog.
        if not self.find(self.dump(), "root filesystem", LABEL_NEEDS_REPAIR):
            raise RuntimeError("the install confirmation dialog did not appear")
        if not self.tap_last(self.dump(), BUTTON_INSTALL):
            raise RuntimeError("the confirmation dialog's Install button was not found")
        time.sleep(2)

    def wait_install_done(self, timeout_min, on_progress=None):
        """Polls the settings screen until the install finishes. Returns the
        ending state's label evidence: the row's subtitle. Raises on repair."""
        deadline = time.monotonic() + timeout_min * 60
        last_label = ""
        while time.monotonic() < deadline:
            els = self.dump()
            repair = self.find(els, LABEL_NEEDS_REPAIR)
            if repair:
                raise RuntimeError(
                    "INSTALL stage: the install ended in Needs repair: %s" %
                    (repair.attrs.get("text", "")[:200]))
            if self.find(els, LABEL_INSTALLED):
                return LABEL_INSTALLED
            label = self.install_label(els)
            if label:
                if label != last_label:
                    self.log("install progress: %s" % label)
                    last_label = label
                now = time.monotonic()
                if now - self._last_progress_shot > PROGRESS_SHOT_EVERY:
                    self._last_progress_shot = now
                    self.screenshot("install-progress")
            if on_progress and on_progress():
                # The caller's interruption hook fired; it owns what happens next.
                return "interrupted"
            time.sleep(POLL_SECONDS)
        raise RuntimeError(
            "INSTALL stage: the install did not finish within %d minutes (last: %s)"
            % (timeout_min, last_label or "nothing observed"))

    def install_via_ui(self, timeout_min, exercises=False):
        """The full user install, optionally with the lifecycle exercises fired
        DURING it (background, rotation, screen off) - the moments a foreground
        service must survive, tested where a bug would actually show."""
        self.start_install_via_ui()
        if not self.wait_visible((LABEL_INSTALLING,), 120, poll=5):
            raise RuntimeError("INSTALL stage: the Installing state never appeared")
        self.log("installing started")

        if exercises:
            self._exercise_background_during_install()
            self._exercise_rotation_during_install()
            self._exercise_screen_off_during_install()

        self.wait_install_done(timeout_min)
        shot = self.screenshot("installed")
        if shot:
            self.results[-1].evidence.append(shot)
        self.log("the settings row reports Installed and verified")

    def _reenter_settings_during_install(self, disturbance):
        """After any disturbance the activity may have been recreated on the host
        list; get back to the settings section and confirm the install is still
        visibly alive (or already done). The disturbance's name rides along into
        the failure so the report says which survival check did not pass."""
        if self.visible(LABEL_INSTALLED):
            return
        self.launch()
        self.open_settings()
        self.scroll_to_linux_section()
        if not (self.visible(LABEL_INSTALLING) or self.visible(LABEL_INSTALLED)):
            raise RuntimeError(
                "LIFECYCLE stage: the install did not survive the %s disturbance"
                " - neither Installing nor Installed was on screen. "
                "On screen: %s" % (disturbance, " | ".join(self._screen_digest())))

    def _exercise_background_during_install(self):
        self.adb.home()
        time.sleep(20)
        self._reenter_settings_during_install("background")
        self.log("background round trip: install still alive")

    def _exercise_rotation_during_install(self):
        self.adb.set_rotation(1)
        time.sleep(6)
        self._reenter_settings_during_install("rotation")
        self.adb.set_rotation(0)
        time.sleep(3)
        self._reenter_settings_during_install("rotation-back")
        self.log("rotation round trip: install still alive")

    def _exercise_screen_off_during_install(self):
        self.adb.keyevent("KEYCODE_POWER")
        time.sleep(12)
        self.adb.keyevent("KEYCODE_POWER")
        time.sleep(3)
        # Waking lands on keyguard on some images; dismiss before dumping.
        self.adb.keyevent("KEYCODE_MENU")
        time.sleep(2)
        self._reenter_settings_during_install("screen-off")
        self.log("screen-off round trip: install still alive")

    # ------------------------------------------------------------ instrumentation

    def run_verification(self, phase_name):
        """Runs UbuntuE2eVerificationTest with the E2E args. Everything the
        pipeline calls 'deep verification' is asserted inside the app process,
        against the real installed userspace."""
        cmd = ("am instrument -w -e ubuntuE2e true -e e2ePhase %s -e class %s "
               "%s/androidx.test.runner.AndroidJUnitRunner"
               % (phase_name, VERIFICATION_CLASS, self.test_package))
        self.log("instrumenting (%s phase)" % phase_name)
        rc, out = self.adb.shell(cmd, timeout=1800)
        stdout_path = os.path.join(self.out, "instrument-%s.txt" % phase_name)
        with open(stdout_path, "w") as fh:
            fh.write(out)
        self.results[-1].evidence.append(stdout_path)
        total, failed, failures = _parse_suite(out)
        self.log("instrumentation %s: %d tests, %d failed" % (phase_name, total, failed))
        if rc != 0 or failed:
            first = failures[0]["test"] if failures else "(runner exited %d)" % rc
            raise RuntimeError(
                "VERIFY stage: instrumentation phase '%s' - %s" % (phase_name, first))

    # ------------------------------------------------------------ terminal through the UI

    def terminal_via_ui(self):
        """The front door a user actually has: the Local Ubuntu card in the host
        list (which only exists while the environment is installed AND healthy)
        opens a terminal session. The driver types a command into it; the output
        is a canvas the driver cannot read, so the assert is 'the session opened
        and accepted input without dying' plus a screenshot - the command's
        actual answer is the instrumentation's job, one phase earlier."""
        self.launch()
        if not self.wait_visible((LABEL_LOCAL_CARD,), 60, poll=5):
            raise RuntimeError(
                "UI stage: the Local Ubuntu card never appeared in the host list - "
                "the userspace is not installed-and-healthy as the card requires")
        card = self.find(self.dump(), LABEL_LOCAL_CARD)
        if not self.tap(card):
            raise RuntimeError("UI stage: could not tap the Local Ubuntu card")
        time.sleep(6)
        self.adb.input_text("echo TEST_OK")
        time.sleep(1)
        self.adb.keyevent("KEYCODE_ENTER")
        time.sleep(4)
        shot = self.screenshot("terminal")
        if shot:
            self.results[-1].evidence.append(shot)
        if self.adb.pid_of(self.package) is None:
            raise RuntimeError("UI stage: the app process died while driving the terminal")
        self.adb.back()
        time.sleep(1)

    # ------------------------------------------------------------ persistence

    def persistence_restart(self):
        """force-stop, relaunch, and prove two things: the card is still there
        (the userspace still healthy) and the markers written before the kill
        still read back the same bytes (instrumentation read phase)."""
        self.adb.force_stop(self.package)
        time.sleep(3)
        self.launch()
        if not self.wait_visible((LABEL_LOCAL_CARD,), 90, poll=5):
            raise RuntimeError(
                "PERSISTENCE stage: after force-stop + relaunch the Local Ubuntu "
                "card did not come back - the userspace did not survive the restart")
        self.log("the Local Ubuntu card is back after the restart")
        self.run_verification("read")

    # ------------------------------------------------------------ FULL-mode failure injections

    def _clean_state(self):
        """A fresh install cycle needs a fresh device state (test isolation).
        pm clear wipes filesDir - rootfs, state file, everything - which is
        exactly the NotInstalled the storage-gate and interruption tests need."""
        self.adb.force_stop(self.package)
        rc, out = self.adb.shell("pm clear %s" % self.package, timeout=60)
        if rc != 0:
            raise RuntimeError("pm clear failed: %s" % out[:200])
        # pm clear also revokes runtime permissions, so the app's first-launch
        # POST_NOTIFICATIONS prompt (MainActivity asks once, API 33+) would put
        # the system permission dialog in front of the UI this driver then
        # dumps - uiautomator captures the foreground window, which is the
        # GrantPermissionsActivity, and the very first open_settings failed on
        # exactly that (run 34854865510: "the Settings tab was not found on
        # screen"). Pre-grant instead, the way android-release-test.yml does.
        # On API < 33 the permission does not exist and pm grant would fail an
        # otherwise-passing leg (release-test run 34832807524, API 30), so the
        # grant is guarded on the device's own SDK level.
        rc, out = self.adb.shell("getprop ro.build.version.sdk", timeout=30)
        if rc == 0 and out.strip().isdigit() and int(out.strip()) >= 33:
            self.adb.shell(
                "pm grant %s android.permission.POST_NOTIFICATIONS" % self.package,
                timeout=30)

    def storage_gate(self):
        """The insufficient-storage failure mode, played for real: fill /data to
        below the installer's free-space budget, attempt the install, and assert
        it refuses - naming the storage need - WITHOUT leaving a half state.
        Then free the space again; the normal install phase that follows is the
        retry-works half of the contract."""
        # The root request goes through the adb server, not the device shell.
        rc, out, _ = self.adb.run(["root"], timeout=30)
        if rc != 0:
            raise RuntimeError(
                "STORAGE stage: could not adb root the emulator (rc=%d) - the "
                "storage-failure phase needs root to fill /data" % rc)
        time.sleep(3)
        avail_kb = self._data_avail_kb()
        if not avail_kb:
            raise RuntimeError("STORAGE stage: could not read /data free space")
        # The installer needs ~5x the 30MB tarball + 600MB headroom (~750MB).
        # Filling down to 300MB free is far below that budget with margin.
        fill_mb = max(0, avail_kb // 1024 - 300)
        if fill_mb < 100:
            raise RuntimeError(
                "STORAGE stage: only %dMB free to begin with - cannot run a "
                "meaningful storage-failure test" % (avail_kb // 1024))
        self.log("filling /data with a %dMB filler (%dMB free now)" % (fill_mb, avail_kb // 1024))
        rc, out = self.adb.shell(
            "dd if=/dev/zero of=/data/local/tmp/e2e-filler bs=1048576 count=%d" % fill_mb,
            timeout=600)
        if rc != 0:
            self._remove_filler()
            raise RuntimeError("STORAGE stage: the filler dd failed: %s" % out[:200])

        try:
            self.launch()
            self.start_install_via_ui()
            # The refusal is fast: the free-space gate runs before the download.
            # The observable contract: no Installed, and the app's own wording
            # about storage lands in logcat (the UI surfaces it transiently).
            deadline = time.monotonic() + 300
            refused = False
            while time.monotonic() < deadline:
                if self.visible(LABEL_INSTALLED):
                    raise RuntimeError(
                        "STORAGE stage: the install claimed success on a full disk - "
                        "the free-space gate did not fire")
                rc, out, _ = self.adb.run(["logcat", "-d", "-v", "brief", "-t", "400"])
                if "Ubuntu needs about" in out:
                    refused = True
                    break
                time.sleep(5)
            if not refused:
                raise RuntimeError(
                    "STORAGE stage: the install neither completed nor named the "
                    "storage need within 5 minutes on a full disk")
            shot = self.screenshot("storage-refused")
            if shot:
                self.results[-1].evidence.append(shot)
            self.log("the install refused on a full disk, naming the storage need")
        finally:
            self._remove_filler()

    def _remove_filler(self):
        self.adb.shell("rm -f /data/local/tmp/e2e-filler", timeout=120)
        self.log("filler removed, /data free again: %dMB" % (self._data_avail_kb() or 0) // 1024)

    def _data_avail_kb(self):
        rc, out = self.adb.shell("df /data", timeout=30)
        if rc != 0:
            return 0
        # toybox df prints human units ("57G", "512M"), so the available column
        # needs its unit read, not just its digits.
        match = re.search(r"/data\s+\S+\s+\S+\s+(\d+(?:\.\d+)?)([KMGT]?)\s+\d+%\s+/data\s*$",
                          out.strip(), re.M)
        if not match:
            return 0
        try:
            value = float(match.group(1))
        except ValueError:
            return 0
        factor = {"": 1.0, "K": 1.0, "M": 1024.0, "G": 1024.0 * 1024.0, "T": 1024.0 ** 3}[match.group(2)]
        return int(value * factor)

    def interrupt_process(self, install_timeout_min):
        """Kill the app mid-download, then prove recovery: the relaunched app
        never shows a fake ready state, and a retry install completes."""
        self._clean_state()
        self.start_install_via_ui()
        if not self.wait_visible((LABEL_INSTALLING,), 120, poll=5):
            raise RuntimeError("INTERRUPT stage: the install never started to interrupt")
        time.sleep(25)  # squarely inside the download
        self.adb.force_stop(self.package)
        self.log("app killed mid-install")
        time.sleep(3)
        self.launch()
        self.open_settings()
        self.scroll_to_linux_section()
        # The recovery contract: NotInstalled (clean) or NeedsRepair (named) -
        # never Installed, never a wedged Installing forever.
        state = None
        last_label = ""
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline and state is None:
            els = self.dump()
            if self.find(els, LABEL_INSTALLED):
                raise RuntimeError(
                    "INTERRUPT stage: the app reports Installed after being killed "
                    "mid-download - a fake ready state")
            if self.find(els, LABEL_NEEDS_REPAIR):
                state = "needs-repair"
            elif self.find(els, LABEL_INSTALL_ROW):
                state = "not-installed"
            else:
                # Still Installing for whatever reason - name the step, so a wedge
                # here is diagnosable from the log alone.
                label = self.install_label(els)
                if label and label != last_label:
                    self.log("post-kill screen: %s" % label)
                    last_label = label
            time.sleep(5)
        if state is None:
            raise RuntimeError(
                "INTERRUPT stage: after relaunch the userspace row settled on no "
                "recognizable state within 2 minutes (last: %s)"
                % (last_label or "nothing observed"))
        self.log("post-kill state: %s (the honest recovery state)" % state)
        shot = self.screenshot("interrupt-recovery")
        if shot:
            self.results[-1].evidence.append(shot)
        # The retry: whatever honest state it landed in offers a way forward.
        button = BUTTON_REPAIR if state == "needs-repair" else BUTTON_INSTALL
        if not self.tap_last(self.dump(), button):
            raise RuntimeError("INTERRUPT stage: the %s button was not offered after recovery" % button)
        time.sleep(1.5)
        # The repair path re-runs setup; the fresh install path asks for
        # confirmation again. Either way, wait it out.
        self.tap_last(self.dump(), BUTTON_INSTALL)
        self.wait_install_done(install_timeout_min)
        self.log("the retry install completed after the interruption")

    def interrupt_network(self, install_timeout_min):
        """Cut the network mid-download (airplane mode), let the install fail,
        restore the network, and prove the retry completes."""
        self._clean_state()
        self.start_install_via_ui()
        if not self.wait_visible((LABEL_INSTALLING,), 120, poll=5):
            raise RuntimeError("NETWORK stage: the install never started to interrupt")
        time.sleep(15)  # mid-download
        if not self.adb.airplane_mode(True):
            raise RuntimeError("NETWORK stage: could not enable airplane mode")
        self.log("airplane mode on, mid-download")
        # The download dies on its read timeout; the install must land on an
        # honest failure state within a bounded window - never wedge, never
        # claim success.
        state = None
        last_label = ""
        deadline = time.monotonic() + 900
        while time.monotonic() < deadline and state is None:
            els = self.dump()
            if self.find(els, LABEL_INSTALLED):
                raise RuntimeError(
                    "NETWORK stage: the install reported success with the network "
                    "cut - that cannot have verified anything")
            if self.find(els, LABEL_NEEDS_REPAIR):
                state = "needs-repair"
            elif self.find(els, LABEL_INSTALL_ROW):
                state = "not-installed"
            else:
                # Still Installing under a dead network - name the step it is stuck on.
                label = self.install_label(els)
                if label and label != last_label:
                    self.log("network-cut screen: %s" % label)
                    last_label = label
            time.sleep(POLL_SECONDS)
        shot = self.screenshot("network-cut")
        if shot:
            self.results[-1].evidence.append(shot)
        if state is None:
            raise RuntimeError(
                "NETWORK stage: with the network cut the install neither failed "
                "nor finished within 15 minutes (last: %s)"
                % (last_label or "nothing observed"))
        if not self.adb.airplane_mode(False):
            raise RuntimeError("NETWORK stage: could not disable airplane mode")
        self.log("airplane mode off; the install failed honestly as %s" % state)
        time.sleep(10)
        button = BUTTON_REPAIR if state == "needs-repair" else BUTTON_INSTALL
        self.launch()
        self.open_settings()
        self.scroll_to_linux_section()
        if not self.tap_last(self.dump(), button):
            raise RuntimeError("NETWORK stage: the %s button was not offered after the failure" % button)
        time.sleep(1.5)
        self.tap_last(self.dump(), BUTTON_INSTALL)
        self.wait_install_done(install_timeout_min)
        self.log("the retry install completed after the network cut")

    # ------------------------------------------------------------ entry

    def run(self):
        with self.phase("preflight"):
            if not self.adb.wait_for_device(60):
                raise RuntimeError("no booted device")
            if not self.keep_data:
                self._clean_state()
            self.launch()
            shot = self.screenshot("first-launch")
            if shot:
                self.results[-1].evidence.append(shot)

        if self.mode == "FULL":
            with self.phase("storage-gate"):
                self.storage_gate()

        with self.phase("install", requires=("preflight",)):
            self.install_via_ui(self.install_timeout_min, exercises=self.mode in ("STANDARD", "FULL"))

        with self.phase("verify", requires=("install",)):
            self.run_verification("write")

        with self.phase("terminal-ui", requires=("install",)):
            self.terminal_via_ui()

        if self.mode in ("STANDARD", "FULL"):
            with self.phase("persistence-restart", requires=("verify", "terminal-ui")):
                self.persistence_restart()

        if self.mode == "FULL":
            with self.phase("interrupt-process", requires=("preflight",)):
                self.interrupt_process(self.install_timeout_min)
            with self.phase("interrupt-network", requires=("preflight",)):
                self.interrupt_network(self.install_timeout_min)

        self._final_logcat()
        self._write_results()
        failed = [r for r in self.results if r.status == "fail"]
        return 1 if failed else 0

    def _final_logcat(self):
        rc, out, _ = self.adb.run(["logcat", "-d", "-v", "time"], timeout=60)
        with open(os.path.join(self.out, "logcat-full.txt"), "w") as fh:
            fh.write(out or "")
        for crash in self.crash_findings:
            self.log("crash signature on record: %s" % crash["kind"])


class _PhaseContext:
    def __init__(self, driver, result, started):
        self.driver = driver
        self.result = result
        self.started = started

    def __enter__(self):
        return self.driver

    def __exit__(self, kind, err, tb):
        exc_info = (kind, err, tb) if kind else None
        if kind:
            traceback.print_exception(kind, err, tb)
        self.driver.finish(self.result, self.started, exc_info)
        return True  # a failed phase is recorded, never fatal to the run


class _NullContext:
    def __enter__(self):
        return None

    def __exit__(self, *args):
        return True


def _parse_suite(stdout_text):
    """AndroidJUnitRunner stdout -> (total, failed, failure blocks). Same shape as
    the release pipeline's generate-report.parse_suite; duplicated here so the
    driver has no import dependency on another pipeline's script."""
    failures = []
    block = re.split(r"^(\d+)\) ", stdout_text, flags=re.M)
    it = iter(block[1:])
    for _num, rest in zip(it, it):
        header = rest.splitlines()[0] if rest else ""
        failures.append({"test": header, "stack": rest[:4000].strip()})
    total = 0
    m = re.search(r"^OK \((\d+) tests?\)", stdout_text, flags=re.M)
    if m:
        total = int(m.group(1))
    return total, len(failures), failures


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--package", default="dev.eclipse.ssh")
    parser.add_argument("--test-package", default="dev.eclipse.ssh.test")
    parser.add_argument("--out", default="out")
    parser.add_argument("--mode", choices=("SMOKE", "STANDARD", "FULL"), default="STANDARD")
    parser.add_argument("--install-timeout", type=int, default=30,
                        help="minutes to wait for one install cycle")
    parser.add_argument("--keep-data", action="store_true",
                        help="do not pm clear first (for local re-runs)")
    parser.add_argument("--serial", default=None)
    args = parser.parse_args()

    os.makedirs(args.out, exist_ok=True)
    adb = Adb(serial=args.serial)
    if not adb.devices():
        print("no connected device/emulator - is one booted?", file=sys.stderr)
        return 2

    driver = E2eDriver(adb, args.package, args.test_package, args.out,
                       args.mode, args.install_timeout, keep_data=args.keep_data)
    return driver.run()


if __name__ == "__main__":
    sys.exit(main())
