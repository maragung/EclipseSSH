#!/usr/bin/env python3
"""The Ubuntu-installation E2E driver: drives the REAL app on an emulator through
the REAL user flow - launch, Settings, the "Ubuntu on this device" window,
Install, the confirmation dialog, the minutes-long download/extract/setup - and
proves the result the same way a user would have to: by using it.

Design contract (the pipeline's whole point):

  - No mocking anywhere. The app downloads the pinned Ubuntu Base rootfs over
    the network, verifies its SHA-256, extracts it, configures apt inside proot
    and installs the base packages. The driver only watches and pokes the UI.
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

# The UI strings the driver keys on. They are the app's real labels: the rows and
# buttons of the "Ubuntu on this device" window (UbuntuActivity, which the Settings
# row opens), and - where the same state is also on the Settings list - that list's
# one-line summary of it. A renamed label breaks the driver loudly rather than
# silently tapping the wrong thing, which is the failure mode that matters here.
LABEL_INSTALL_ROW = "Not installed"  # the NotInstalled row's subtitle prefix
LABEL_INSTALLING = "Installing"
LABEL_INSTALLED = "Installed and verified"
LABEL_NEEDS_REPAIR = "Needs repair"
LABEL_LAST_OPERATION = "Last operation"  # the app's own error row: why the install ended
LABEL_LOCAL_CARD = "Local Ubuntu"
# The install row's title in every settled state (UbuntuActivity's card); mid-install
# the title is "Installing <distro>" instead. On the Settings list the same row
# carries this title with the state folded into its summary line. Read as a title,
# not searched for as a word: the app's error row can begin with "Installing"
# ("Installing base packages failed (exit 100)"), so a substring search for the
# Installing label can read a dead install as a live one.
CARD_TITLE = "Ubuntu on this device"
# The window's card header, and the one string only the window draws: Settings labels
# the section over the row ("LINUX USERSPACE"), where the window titles its card with
# the screen's own name - and SettingsSection uppercases whatever it is given. This is
# how the driver knows the window opened. The window's top bar carries CARD_TITLE
# itself, which is exactly why the bar cannot be the discriminator (see install_state).
WINDOW_HEADER = CARD_TITLE.upper()
# The Settings row's only control, and the way into the window. The button carries the
# row's own name as its content-desc - the "setting, action" shape the View buttons on
# that list use - so the driver matches the pair rather than the bare verb.
BUTTON_OPEN = "Open"

# The states that mean the install is over and did not succeed. Seeing one of
# these mid-install is the install's own failure, which a disturbance merely
# revealed - not a lifecycle failure, which is what blaming the disturbance for
# it reads as.
DEAD_INSTALL_LABELS = (LABEL_NEEDS_REPAIR, LABEL_INSTALL_ROW)
BUTTON_INSTALL = "Install"
BUTTON_REPAIR = "Repair"
# The install trace's row and the two strings that reach it (UbuntuActivity's card /
# InstallLogDialog). The row's button carries the row's own name as its content-desc,
# because three "View" buttons now sit on that screen; the driver matches on that
# pair rather than on "View" alone.
LABEL_INSTALL_LOG = "Install log"
BUTTON_VIEW = "View"

# The bottom bar's tabs (MainActivity's NavigationBarItem) and the soft keyboard
# that can be drawn over them. A tab tap is the one navigation the driver cannot
# see fail on its own: the bar is the lowest row of an edge-to-edge window, and a
# raised keyboard is drawn OVER it - the IME does not resize this window, so the
# tabs stay at y=2190-2232 either way (run 35311046886's dumps, before and after).
# A tap there is delivered to the IME's window, `input tap` still reports success,
# and the screen simply never changes. That is how the whole interrupt-process
# phase was lost: the tap at 06:01:13 was swallowed, open_settings returned
# unverified, and 24 swipes and 100 seconds later the phase failed naming a
# settings section that was never on the wrong screen to be missing.
TAB_SETTINGS = "Settings"
# Every label the bar draws. The bar is on screen - and carries all five - on every
# destination the app has, so "is this the app's bottom bar" is answered by counting
# them rather than by trusting one word that another screen may also carry.
TAB_LABELS = ("Hosts", "Terminal", "Files", "Transfers", "Settings")
# A tab is a bottom-bar row: its top edge sits in the window's bottom band. The
# fraction is loose on purpose - the bar's own height is ~42px of a 2400px window
# in portrait and of a 1080px one in landscape.
TAB_BAND = 0.8
# How Android reports a raised soft keyboard, in the two dumps that carry it:
# InputMethodManagerService's `mInputShown` and InputMethodService's
# `mIsInputViewShown`. A dump that says neither reads as "not shown", which is the
# safe direction - the only thing a shown keyboard is answered with is a BACK
# press, and a BACK pressed on a keyboard that is not up goes to the app instead.
IME_SHOWN_RE = re.compile(r"m(InputShown|IsInputViewShown)\s*=\s*true")

# Exceptions that mean this driver is broken, not the device or the app. The
# failure report's stage column is a claim about WHERE a failure lives, and its
# unattributed fallback is "UI" - so a bug in this file would be published as an
# app UI failure, which is the one attribution a driver must never get wrong.
# Run 35106576845's storage gate is the worked example: a precedence slip in the
# filler cleanup (`"..." % x // 1024`) raised TypeError from the driver's own
# line, and the report read "fail at UI" while the device had in fact refused
# the install and named the missing megabytes.
DRIVER_BUG_KINDS = (TypeError, AttributeError, NameError, UnboundLocalError,
                    KeyError, IndexError, ZeroDivisionError)

# What a navigation failure appends to its own message: the app's words for the
# screen it was actually on (_screen_note). That text is evidence, not the message,
# and the stage scan below must not read a stage word out of it - a host named "apt
# mirror", or a row that mentions DNS, would otherwise decide the stage of a
# failure that is about a tap not landing.
EVIDENCE_MARKER = " | on screen: "

# The install screen's step subtitles (UbuntuActivity's describeInstallStep /
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
    "Running the health check",
)

POLL_SECONDS = 10
PROGRESS_SHOT_EVERY = 60

# The app's own failure line for a lifecycle action it ran (LinuxUserspaceController's
# act(), which writes it from the service the moment the action fails, whatever the
# screen is showing): "Linux userspace Install failed: <cause>". The install's cause
# is also the line its settings row shows, but the row can be below the fold - and a
# uiautomator dump only holds on-screen nodes - while this line is always readable.
# It is the log channel the E2E artifacts already collect, used here as the second
# opinion when the screen cannot say whether an install is alive or over.
LOG_ACTION_FAILED = "Linux userspace %s failed: "
LOG_INSTALL_ACTIONS = ("Install", "Repair")

# One line of the install trace as the dialog renders it (InstallLogDialog):
# UserspaceDiagnosticEvent.line() with its leading epoch millis dropped in favour
# of a clock, so "[apt] bulk base-package install failed exit=100 detail=...". The
# category is the whole discriminator: the dialog's own chrome ("Install log",
# "Copy", the header sentence) never matches it, so a dump of the dialog yields
# the events and nothing else.
INSTALL_LOG_LINE = re.compile(r"^\[(storage|rootfs|download|proot|dns|apt)\] ")


def _contains(outer, inner):
    """Whether rect `outer` encloses rect `inner`, edges included.

    Both are the dump's own (x1, y1, x2, y2) rects - never `Element.bounds`, which is
    the tap centre and the size. A tab's item box and the label inside it are flush at
    the bottom edge, so an exclusive test would read the bar's own item as not
    containing its label."""
    return (outer[0] <= inner[0] and outer[1] <= inner[1]
            and outer[2] >= inner[2] and outer[3] >= inner[3])


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
        # Set by _walk_linux_section: the settings list would not scroll, so what
        # is below the fold is unknowable from the screen.
        self._walk_stuck = False
        # The device's clock when the install was started: the log window the
        # install's own outcome is read from (see _install_log_verdict).
        self._install_log_mark = None
        # What the last read of the app's install-log row saw: the row's presence,
        # the dialog's own event count, and the event lines it rendered.
        self._install_log_read = {"opened": False, "header": None, "events": []}

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
        just the state it never left. Reads the row through install_state() so
        the 'Last operation' error row cannot pose as the install row.

        Both spellings are read, because a phase that interrupted the install is
        reading the Settings list rather than the window: there the title is the
        row's own name and the state and its step are folded into the summary line
        underneath it. Reading only the window's spelling left every interruption
        phase logging no progress at all - the log a wedge is diagnosed from."""
        title, line = self.install_state(elements)
        if title and title.startswith(LABEL_INSTALLING):
            return "%s | %s" % (title[:120], line[:120]) if line else title[:120]
        if line.startswith(LABEL_INSTALLING):
            return line[:200]
        return None

    def tap(self, element):
        x, y = element.center
        if x is None:
            return False
        return self.adb.tap(x, y)

    def tap_last(self, elements, needle, exact=False):
        """Tap the LAST match: dialogs render after the screen behind them, so the
        dialog's button is the trailing one when both are in the hierarchy.

        `exact` compares the whole text or content-desc instead of looking for a
        substring, and the action buttons need it: the Linux userspace section now
        carries an "Install log" row whose View button names its row, so a
        substring match for "Install" can tap that row instead of the action row
        - which is what E2E run 35061538315 did, opening the trace dialog, leaving
        the install never started, and reporting it as a missing confirmation
        dialog. The app's own labels are unchanged; the matcher was too loose."""
        matches = []
        for el in elements:
            for attr in (el.attrs.get("text", ""), el.attrs.get("content-desc", "")):
                if not attr:
                    continue
                if (attr == needle) if exact else (needle in attr):
                    matches.append(el)
                    break
        if not matches:
            return False
        return self.tap(matches[-1])

    def visible(self, *needles):
        return self.find(self.dump(), *needles) is not None

    def _texts(self, elements):
        """Every text and content-desc of the given dump, in dump order,
        deduplicated. The order is the screen's own order, which is what lets a
        row's title and its subtitle be read as a pair."""
        texts = []
        for el in elements:
            for attr in (el.attrs.get("text", ""), el.attrs.get("content-desc", "")):
                if attr and attr not in texts:
                    texts.append(attr)
        return texts

    def _screen_digest(self, limit=40):
        """Every text and content-desc on the current screen, deduplicated, for
        failure messages. The app's own words are the evidence a screenshot
        cannot carry: the install row's subtitle and the 'Last operation' error
        row are the self-identifying detail of a failed install, and uiautomator
        dumps are not otherwise uploaded."""
        return self._texts(self.dump())[:limit]

    def _screen_note(self, limit=12):
        """The screen's own words, as the tail of a failure message. A navigation
        failure is diagnosed from what was on screen, so the message carries it;
        nothing is appended when the dump holds nothing, so no message is padded
        with an empty list."""
        digest = self._screen_digest(limit)
        return ("%s%s" % (EVIDENCE_MARKER, digest)) if digest else ""

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
            result.stage = self._stage_from_error(str(err), kind)
            self.log("PHASE %s: FAIL at %s - %s" % (result.name, result.stage, result.detail))
            shot = self.screenshot("fail-" + result.name)
            if shot:
                result.evidence.append(shot)
            self._save_screen(result.name)
        self._scan_crashes(result)
        self._write_results()

    def _stage_from_error(self, message, kind=None):
        """The coarse stage attribution the failure report's table wants."""
        # Before the message is read: a driver bug's own text can mention
        # anything (that TypeError says "unsupported operand type(s) for //",
        # with no stage word at all), and no keyword in it may be allowed to
        # dress a driver bug as a device finding.
        if kind is not None and issubclass(kind, DRIVER_BUG_KINDS):
            return "DRIVER"
        # The evidence a navigation failure carries is the app's own text and is not
        # part of the claim about where the failure lives (see EVIDENCE_MARKER).
        message = message.split(EVIDENCE_MARKER)[0]
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
        """Select the Settings tab, and prove it: a bottom-bar tap is the one
        navigation the emulator can eat without a trace.

        A tab is a tap away from anywhere the app is on screen, which is why every
        phase comes through here - but the bar sits at the very bottom of an
        edge-to-edge window, and a raised soft keyboard is drawn over it. `input tap`
        reports success whether the app or the IME window received the tap, and when
        it is the IME nothing happens at all: no error, no state change, just the
        screen the driver was already on. Run 35311046886 lost the entire
        interrupt-process phase that way (see TAB_SETTINGS).

        So the keyboard is put away first, the tap is retried while it does not take,
        and the switch is required to be visible before returning. The app not being
        on screen at all is the other case, and it is the one that came first: several
        phases reach here with the app stopped (`pm clear`) or just torn down by an
        `am instrument` run, and the dump then shows whatever Android put in front -
        the launcher, in run 35100526297, where the interruption phases failed with
        "the Settings tab was not found on screen" while the app was simply not
        running. A relaunch is idempotent (`am start` on a live app only brings it
        forward), and a Settings tab that is genuinely gone still fails here, one
        relaunch later."""
        self.dismiss_ime()
        if self._open_tab(TAB_SETTINGS):
            return
        self.launch()
        self.dismiss_ime()
        if self._open_tab(TAB_SETTINGS):
            return
        raise RuntimeError(
            "the %s tab did not open: no bottom bar on screen carrying it, or every tap"
            " on it left another tab selected%s" % (TAB_SETTINGS, self._screen_note()))

    def _ime_shown(self):
        """Whether the soft keyboard is up, read from the input method's own dump.
        Anything unreadable answers False - see IME_SHOWN_MARKERS for why that is
        the safe direction."""
        rc, out = self.adb.shell("dumpsys input_method", timeout=30)
        if rc != 0:
            return False
        return bool(IME_SHOWN_RE.search(out))

    def dismiss_ime(self):
        """Put the soft keyboard away, so a tap on the bottom bar reaches the app.

        Two attempts: the first BACK is what normally dismisses a keyboard, and a
        second is only sent if the dump still says it is up. Nothing is sent when it
        is not, because a BACK with no keyboard on screen goes to the app and would
        navigate it out from under the caller."""
        for _ in range(2):
            if not self._ime_shown():
                return True
            self.log("the soft keyboard is up; dismissing it before touching the tab bar")
            self.adb.back()
            time.sleep(1)
        return not self._ime_shown()

    def _band_top(self, elements):
        """The y the window's bottom band starts at. The bar is the last row of an
        edge-to-edge window, so the band is measured from the window's own height
        rather than from a constant; a dump too sparse to measure (a failed one, or
        a screen with almost nothing on it) falls back to the emulator's portrait
        height, which is the orientation every phase runs in."""
        _, height = self._window_extent(elements)
        if height < 100:
            height = 2400
        return height * TAB_BAND

    def _bottom_bar(self, elements):
        """Which of the bar's own labels are drawn in the window's bottom band.

        The count is what says "this is the app's bottom bar": all five are drawn on
        every destination the app has, so a screen carrying fewer than three of them is
        not the app - or is not on the app's UI at all, which is the launcher and the
        `am instrument` case alike.

        Membership is the node's top edge, read from the dump as `rect` rather than
        derived from `bounds`: `bounds` is the tap centre and the size, and the two are
        not interchangeable - a tall node's centre can sit inside the band while its
        top is well above it."""
        band = self._band_top(elements)
        found = set()
        for el in elements:
            r = el.rect
            if not r or r[1] < band:
                continue
            for attr in (el.attrs.get("text", ""), el.attrs.get("content-desc", "")):
                if attr in TAB_LABELS:
                    found.add(attr)
        return found

    def _band_labels(self, elements, exclude=None):
        """The bar's label nodes in the bottom band, in dump order."""
        band = self._band_top(elements)
        found = []
        for el in elements:
            r = el.rect
            if not r or r[1] < band:
                continue
            for attr in (el.attrs.get("text", ""), el.attrs.get("content-desc", "")):
                if attr in TAB_LABELS and attr != exclude:
                    found.append(el)
                    break
        return found

    def _tab_label_node(self, elements, label):
        """The node in the bottom band whose whole text or content-desc is `label`,
        or None."""
        band = self._band_top(elements)
        for el in elements:
            r = el.rect
            if not r or r[1] < band:
                continue
            for attr in (el.attrs.get("text", ""), el.attrs.get("content-desc", "")):
                if attr == label:
                    return el
        return None

    def _tab_active(self, elements, label):
        """Whether `label`'s tab is the one showing, read from the tab's own
        `selected` semantics.

        This is the only proof of the active destination that does not depend on what
        that destination happens to be drawing, and the alternatives all fail here.
        Settings has no top bar of its own, so it carries no title to read; the word
        "Settings" appears exactly once on it (the tab) and equally once on every
        other destination; and there is no marker string that is at once unique to
        Settings and always on screen, because MainActivity.kt:2362 builds one
        `rememberScrollState()` outside the destination `when` - one list offset
        shared by every tab, so a list left scrolled down opens scrolled down on the
        next tab, and even Settings' own first row is not guaranteed to be in the dump.

        `selected` is scroll-independent, and every destination sets it -
        `NavigationBarItem(selected = destination == item)` puts Compose's
        Modifier.selectable on the tab, and uiautomator writes a `selected` attribute
        on every node it dumps. What that node *carries* is the part this reads
        carefully, because on this app's bar the label and the selected node are not
        the same node.

        Run 35314746158 is the worked example, and it cost four phases. The dump it
        left behind draws the bar as five label nodes bearing the label's own box -
        `text='Settings' bounds=[917,2190][1043,2232]`, 126x42 px, against a tab that
        is a fifth of a 1080 px bar and spans icon and label alike, ~y 2074-2232. So
        the node carrying the text is the label's own Text, not the selectable parent
        Compose would merge it into, and a rule wanting `selected` and the label on
        one node can never be satisfied: the app really was on Settings (the dump
        shows its rows), three taps had been delivered, and the proof said otherwise.

        So the container is what proves it: the tab's selectable node is the one in
        the bottom band whose box contains the label's box. The band test is what
        keeps that honest - a node that is selected, is in the band, and does not also
        carry another bar label is the tab's own item, whereas a selected root or
        whole window would contain every label and prove nothing."""
        node = self._tab_label_node(elements, label)
        if node is None:
            return False
        if node.attrs.get("selected") == "true":
            return True
        box = node.rect
        if not box:
            return False
        band = self._band_top(elements)
        others = [el.rect for el in self._band_labels(elements, exclude=label)]
        for el in elements:
            if el is node or el.attrs.get("selected") != "true":
                continue
            container = el.rect
            if not container or container[1] < band:
                continue
            if not _contains(container, box):
                continue
            if any(o and _contains(container, o) for o in others):
                continue
            return True
        return False

    def _log_tab_proof(self, elements, label):
        """What a failed tab proof read, node by node.

        The screen digest cannot carry this: it prints only the nodes that have text
        or a content-desc, and the node Compose marks `selected` on this bar is
        exactly one of the unlabelled ones. So a proof that fails has to leave its own
        reading behind, or the next run guesses at the node layout again - which is
        what run 35314746158 cost, a diagnosis made from a digest that had already
        thrown the answer away."""
        band = self._band_top(elements)
        in_band = [el for el in elements if el.rect and el.rect[1] >= band]
        selected = [el for el in elements if el.attrs.get("selected") == "true"]
        self.log("tab proof for %s: %d node(s) in the bottom band (y >= %d), %d selected"
                 " in the whole dump" % (label, len(in_band), band, len(selected)))
        for el in in_band:
            self.log("  band: class=%s selected=%r clickable=%r text=%r desc=%r bounds=%s"
                     % (el.attrs.get("class", ""), el.attrs.get("selected"),
                        el.attrs.get("clickable"), el.attrs.get("text", ""),
                        el.attrs.get("content-desc", ""), el.attrs.get("bounds", "")))
        for el in selected:
            self.log("  selected elsewhere: class=%s text=%r desc=%r bounds=%s"
                     % (el.attrs.get("class", ""), el.attrs.get("text", ""),
                        el.attrs.get("content-desc", ""), el.attrs.get("bounds", "")))

    def _open_tab(self, label, attempts=3):
        """Tap the bottom bar's tab for `label` until the bar itself says it took.
        False means the app's bar is not on this screen (the caller's relaunch case)
        or that every tap was eaten - `tap_last` matches the last node carrying the
        label, which on this bar is the bar's own node, because the bar is dumped
        after the screen above it."""
        els = self.dump()
        bar = self._bottom_bar(els)
        if label not in bar or len(bar) < 3:
            return False
        for _ in range(attempts):
            if self._tab_active(els, label):
                return True
            if not self.tap_last(els, label):
                return False
            time.sleep(2)
            els = self.dump()
        if self._tab_active(els, label):
            return True
        self.log("the %s tab is still not the selected one after %d taps" % (label, attempts))
        self._log_tab_proof(els, label)
        return False

    def _window_extent(self, els):
        """The window's extent as the current dump reports it: the largest right
        and bottom edge any node reaches. The root node of a uiautomator dump
        spans the window, rotation included, so this IS the current display
        size - which is why the swipe below is sized from a dump rather than
        from a constant (portrait-fitting y=1400 is past the screen edge in
        landscape, and an injected swipe off-screen scrolls nothing)."""
        width = height = 0
        for el in els:
            b = el.bounds
            if b:
                width = max(width, b[2])
                height = max(height, b[3])
        return width, height

    def _scroll_swipe(self, els, span=(0.58, 0.21)):
        """One upward scroll swipe across the current screen's own extent,
        from `span`'s top fraction to its bottom one."""
        width, height = self._window_extent(els)
        if width < 100 or height < 100:
            # A sparse or failed dump has nothing to anchor on; the portrait
            # default matches the emulator's natural orientation, which is what
            # every other phase of the run is in.
            width, height = 1080, 2400
        x = width // 2
        self.adb.swipe(x, int(height * span[0]), x, int(height * span[1]), 400)

    def scroll_to_linux_section(self, max_swipes=24):
        """The Linux userspace section sits far down the settings list; swipe until
        its row is on screen. Swiping is content-anchored: stop the moment the
        row is visible, so over-scrolling never skips past it. Landscape shows
        less of the list per screen, so the cap is generous - the loop exits on
        first sight and a higher cap costs nothing when the section is near.

        This is the Settings LIST, and on it the section is one row: its title, the
        state as a summary line, and the button that opens the window. The section
        header the app draws over it is uppercased ("LINUX USERSPACE"), so the match
        below lands on the row itself rather than on the header."""
        for _ in range(max_swipes):
            els = self.dump()
            if self.find(els, "Ubuntu on this device", "Linux userspace"):
                return True
            self._scroll_swipe(els)
            time.sleep(1.2)
        return self.visible("Ubuntu on this device", "Linux userspace")

    def require_linux_section(self, max_swipes=24):
        """scroll_to_linux_section, or the failure the phases reported blind.

        The message names the screen the walk was actually on, because a walk that
        finds nothing is a navigation failure far more often than it is a list that
        would not scroll - and only the screen says which. Run 35311046886 is the
        worked example: the Settings tab's tap was eaten by the soft keyboard, so the
        walk spent its 24 swipes and 100 seconds on the HOSTS list and the phase
        failed with "the Linux userspace settings section was never visible", which
        reads as a list that would not scroll and names a section that was never on
        that screen to be missing. The first screen, captured before the first swipe,
        is the whole diagnosis."""
        first = self._screen_digest(12)
        if self.scroll_to_linux_section(max_swipes):
            return
        raise RuntimeError(
            "the Linux userspace settings section was never visible on the Settings"
            " list%s | and after %d swipes: %s"
            % (("%s%s" % (EVIDENCE_MARKER, first)) if first else "",
               max_swipes, self._screen_digest(12)))

    def _open_window_button(self, elements):
        """The Linux userspace row's own control - the Settings list's way into the
        window - or None.

        Matched on the row's content-desc, which is the row's own name: that row's
        title and its button carry the same string, and the title is not tappable,
        so matching the text alone would be a tap the driver could lose silently.
        The trailing "Open" label is preferred but not required, the same way the
        install-log row's "View" is; a merged node that carries the name taps
        through to the same window."""
        candidates = [el for el in elements
                      if el.attrs.get("content-desc", "") == CARD_TITLE]
        for el in candidates:
            if el.attrs.get("text") == BUTTON_OPEN:
                return el
        return candidates[0] if candidates else None

    def open_ubuntu_window(self, max_swipes=24):
        """From the Settings list, open the window that holds the userspace's
        controls: scroll to the Linux userspace row and tap its Open button.

        This is the step the promotion added, and the reason every acting phase
        comes through here. The control panel used to BE the settings section - one
        scroll and the Install button was there - and since a30b276 the section is a
        single row that reports the state, with the window behind it as the only
        place anything is done. A driver that still scrolled the list for an action
        button would fail at "the Install button was not found" on a device where
        everything is in fact fine.

        The wait is on the window's card header, not on its bar: the bar's title is
        the same string as the row just tapped, so its presence proves nothing about
        which screen is showing. A tap the emulator ate therefore fails here, naming
        the tap, instead of surfacing later as a list that would not scroll."""
        self.require_linux_section(max_swipes)
        button = self._open_window_button(self.dump())
        if not button:
            raise RuntimeError(
                "the Ubuntu row's Open button was not found - the row is not on screen"
                " or this device is unsupported, and an unsupported device draws no button")
        if not self.tap(button):
            raise RuntimeError("the Ubuntu row's Open button could not be tapped")
        if not self.wait_visible((WINDOW_HEADER,), 30, poll=2):
            raise RuntimeError(
                "the Ubuntu on this device window did not open: no '%s' on screen"
                % WINDOW_HEADER)
        time.sleep(1)

    def action_button_in_window(self, button):
        """Open the controls window and tap `button` in its action row, returning
        False at either step. The callers own the wording of what a button that was
        not offered means for the recovery they are testing - the interruption
        phases are the ones that read this, and for them an absent Repair button is
        the finding, not the navigation."""
        try:
            self.open_ubuntu_window()
        except RuntimeError as exc:
            self.log("the controls window could not be opened: %s" % exc)
            return False
        if not self.scroll_to_action_button(button):
            return False
        return self.tap_last(self.dump(), button, exact=True)

    def scroll_to_action_button(self, button=BUTTON_INSTALL, max_swipes=8):
        """Swipe until the window's action-row button (`button`) is on screen.

        The action row sits below the state row, and in every installed state
        three more rows (Storage used, Workspace, Health check) sit between them -
        so it lands below the fold, and a uiautomator dump only contains on-screen
        nodes. scroll_to_linux_section stops at the Settings row, which is not
        enough for that: on a fresh install it made a tap fail with 'the Install
        button was not found' (E2E run 34869834710), and in NeedsRepair - the
        tallest the screen ever gets, and the state both interruption phases land
        in - it made "the Repair button was not offered after recovery" (run
        35106576845), while the screen behind that failure read "Needs repair - a
        previous install was interrupted". This walk knows Repair as well as
        Install now, because only knowing "Install" is how that phase failed.

        The match must be exact: for Install, the "Install log" row's View button
        and the "Installing ..." title both contain the word, so a substring match
        finds something that cannot be tapped. Repair's own row ("Needs repair -
        ...") differs from the button only by case today, which is not a
        guarantee worth resting a tap on.

        Call this after open_ubuntu_window: the walk only ever swipes forward, so
        it has to start above the button it is looking for, and the window is where
        that button lives."""
        for _ in range(max_swipes):
            els = self.dump()
            if self.find(els, button, exact=True):
                return True
            self._scroll_swipe(els)
            time.sleep(1.2)
        return self.find(self.dump(), button, exact=True) is not None

    def start_install_via_ui(self):
        """Settings -> the Ubuntu on this device window -> Install -> the
        confirmation dialog's Install. Returns once the Installing state is on
        screen, with the window left open on it: this is the screen a user watches
        an install on, and it is where the step lines and the progress bar are."""
        self.open_settings()
        self.require_linux_section()
        if not self.find(self.dump(), LABEL_INSTALL_ROW, LABEL_NEEDS_REPAIR, LABEL_INSTALLED):
            raise RuntimeError("no Ubuntu row to install from - unexpected section state")
        self.open_ubuntu_window()
        if not self.scroll_to_action_button():
            raise RuntimeError("the Install button was not found")
        if not self.tap_last(self.dump(), BUTTON_INSTALL, exact=True):
            raise RuntimeError("the Install row's Install button could not be tapped")
        time.sleep(1.5)
        # The confirmation dialog. Its Install button is the trailing match - the
        # row's button is still in the hierarchy behind the dialog.
        if not self.find(self.dump(), "root filesystem", LABEL_NEEDS_REPAIR):
            raise RuntimeError("the install confirmation dialog did not appear")
        if not self.tap_last(self.dump(), BUTTON_INSTALL, exact=True):
            raise RuntimeError("the confirmation dialog's Install button was not found")
        time.sleep(2)

    def wait_install_done(self, timeout_min, on_progress=None):
        """Polls whichever screen is showing until the install finishes. Returns
        the ending state's label evidence: the row's subtitle. Raises on repair,
        and on the row going back to Not installed - the install ended, and waiting
        out the remaining timeout for a state that will not change again is how a
        finished failure gets reported as a stall.

        Both surfaces the run can be on are read by the same code: the window the
        install was started from (SMOKE, and every phase that never left it) and the
        Settings list a lifecycle exercise relaunches the app onto (STANDARD/FULL,
        because `am start` on a singleTask MainActivity clears the window above it).
        They spell the state differently - a title over a step line, or one summary
        line - and install_state reads the pair for both."""
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
            if self.find(els, LABEL_INSTALL_ROW):
                raise RuntimeError(
                    "INSTALL stage: the install ended and the row is back to Not"
                    " installed (last progress: %s)" % (last_label or "nothing observed"))
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
        # Before the first tap: everything the app logs from here on is this
        # install's, which is what makes the log a usable second opinion on
        # whether it is still running (see _install_log_verdict).
        self._install_log_mark = self._device_time()
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
        self.log("the userspace row reports Installed and verified")

    def install_state(self, elements):
        """The install row's state as (title, line under it), read structurally:
        the row is either the card title 'Ubuntu on this device' (every settled
        state) or 'Installing <distro>' (mid-install), and its state line is the
        text that follows it in dump order. On the Settings list the same row
        carries the title with the state in its summary line, so one reading
        serves both surfaces.

        Two things rule out the obvious keyword match, and both were seen in E2E
        run 35051269460: the row's state line can be below the fold while its
        title is on screen (a dump holds only on-screen nodes), and the app's
        'Last operation' error row can read 'Installing base packages failed
        (exit 100)' - text that begins with the Installing label, which would
        report a dead install as alive.

        And in the window the name is drawn twice: the bar above the card renders it
        because the screen is named after the row that opens it, and the card's own
        row renders it again as the row's title. A candidate above the card's header
        is therefore the bar, and the row carrying the state is below that header -
        which is also why the pairing is built on the raw dump rather than on
        _texts(): that one deduplicates, so the two identical titles collapse into
        the bar's occurrence and the card's own row becomes unreachable. Reading the
        bar as the row loses the step label and the periodic progress shots for an
        install watched in the window, which is the evidence a hung install is
        diagnosed from (see STEP_SUBTITLES) - and the verdicts below are `find`-based,
        so nothing else would notice."""
        texts = self._texts(elements)
        values = {texts[i + 1] for i, t in enumerate(texts)
                  if t == LABEL_LAST_OPERATION and i + 1 < len(texts)}
        # Every text in dump order, duplicates kept: a row's title and its line are a
        # pair, and the same string can legitimately stand twice on one screen.
        seen = [attr for el in elements
                for attr in (el.attrs.get("text", ""), el.attrs.get("content-desc", ""))
                if attr]
        header = seen.index(WINDOW_HEADER) if WINDOW_HEADER in seen else -1
        for i, text in enumerate(seen):
            if text in values:
                continue
            if text == CARD_TITLE or text.startswith("Installing "):
                if i < header:
                    continue
                line = seen[i + 1] if i + 1 < len(seen) else ""
                return text, line
        return None, ""

    def install_state_kind(self, title, line):
        """'alive', 'ended' or 'unknown' for an install_state() reading. Only a
        recognized reading ends the section walk: an unrecognized line is the row
        half-visible or a screen the section has scrolled away from, not a
        verdict about the install.

        The Installing word is looked for in the line as well as in the title,
        because the two surfaces put it in different places: the window's row sets
        it as the title over the step, and the Settings list folds both into one
        summary line ("Installing Ubuntu 22.04 LTS · Downloading ..."). Reading
        only the title made every lifecycle exercise fired during an install see no
        state at all, walk a list that had nothing left to reveal, and report the
        install as lost - the false failure this reading exists to prevent."""
        if title and title.startswith(LABEL_INSTALLING):
            return "alive"
        if line.startswith(LABEL_INSTALLING):
            return "alive"
        if line.startswith(LABEL_INSTALLED):
            return "alive"
        if line.startswith(DEAD_INSTALL_LABELS):
            return "ended"
        return "unknown"

    def _walk_linux_section(self, max_swipes=10):
        """Read the Linux userspace row by scrolling through the Settings list, not
        from one snapshot. The state row and the app's 'Last operation' error row sit
        at different scroll offsets, and a uiautomator dump only holds on-screen
        nodes: a single dump after scroll_to_linux_section stopped at the section
        title found no state word and the check blamed the disturbance that
        revealed the dead install (E2E run 35051269460 - the install had exited at
        the dpkg repair pass two minutes earlier). Stops at the first recognized
        state reading; returns every text seen, in order, and that dump.

        This is where the interruption phases read, because that is where they
        land: they relaunch the app, which puts the Settings list in front. The
        row's summary carries the same states the window's row does - including
        "Installing <distro> · <step>" while an install is running, and
        "Needs repair · <detail>" when it ended badly - and install_state_kind
        knows both spellings, while _install_failure_detail reads the detail out
        of the summary. The window's separate 'Last operation' error row is not
        drawn on the list, so a reading that comes back inconclusive falls through
        to the app's own log (see _install_log_verdict) rather than guessing.

        A swipe that reveals nothing is retried across the window's full height
        before it is believed, and a run of three leaves [self._walk_stuck] set:
        a row that is below the fold because the list will not move is a
        different fault from a row that is not there, and the caller's failure
        message has to be able to tell a reader which one it hit."""
        seen = []
        self._walk_stuck = False
        no_ops = 0
        els = self.dump()
        for _ in range(max_swipes + 1):
            for text in self._texts(els):
                if text not in seen:
                    seen.append(text)
            if self.install_state_kind(*self.install_state(els)) != "unknown":
                break
            before = set(self._texts(els))
            self._scroll_swipe(els, span=(0.85, 0.15) if no_ops else (0.58, 0.21))
            time.sleep(1.2)
            els = self.dump()
            after = set(self._texts(els))
            if after and after == before:
                no_ops += 1
                if no_ops >= 3:
                    self._walk_stuck = True
                    break
            else:
                no_ops = 0
        return seen, els

    def _install_failure_detail(self, seen):
        """The app's own words for why an install ended: the 'Last operation'
        row's text, which the pipeline fills with the failing step and its exit
        code. Without it the report can only name the disturbance that happened
        to be the first to notice."""
        if LABEL_LAST_OPERATION in seen:
            index = seen.index(LABEL_LAST_OPERATION)
            if index + 1 < len(seen):
                return seen[index + 1]
        for text in seen:
            if text.startswith(LABEL_NEEDS_REPAIR):
                return text
        return None

    def _device_time(self):
        """The device's own clock in logcat's stamp format, for scoping a log
        read to one install. The emulator shares the host's clock, but asking
        the device is what makes the comparison true on any device at any skew."""
        ok, out = self.adb.shell("date '+%m-%d %H:%M:%S'")
        out = (out or "").strip()
        return out if ok and out else None

    @staticmethod
    def _stamp(line):
        """A logcat line's own stamp as a comparable tuple, or None when the line
        has none (a continuation line, or a header like 'beginning of main')."""
        m = re.match(r"(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})", line)
        return tuple(int(g) for g in m.groups()) if m else None

    @staticmethod
    def _after(stamp, mark):
        """Whether a log stamp is at or after the mark. The year is not in either
        stamp; a different day is read as the wrap past midnight, because the
        buffer cannot hold a previous day's line (the E2E clears logcat before
        the app is launched and the install follows within minutes), so crossing
        into another day is the only way to see one. A stamped-less line is not
        evidence about this install either way."""
        if stamp is None:
            return False
        m = re.match(r"(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})", mark or "")
        if not m:
            return True
        limit = tuple(int(g) for g in m.groups())
        if stamp[:2] == limit[:2]:
            return stamp >= limit
        return True

    def _install_log_verdict(self):
        """What the app's own log says about the install started at
        [self._install_log_mark]: the cause string when an install or repair
        action failed, else None.

        This is the check's second opinion, used only when the screen is
        inconclusive. In E2E run 35056615874 the install died at the base
        packages (apt exit 100) and the app's log said so at 05:08:01, while the
        driver's screen reading of the same minute saw only the section title:
        the row's state line was below the fold and the settings list would not
        scroll, so the run blamed the rotation disturbance that happened to be
        the exercise in flight. The app's log has no such blind spot."""
        mark = self._install_log_mark
        if not mark:
            return None
        log = self.adb.logcat(lines=4000)
        verdict = None
        for line in log.splitlines():
            stamp = self._stamp(line)
            if stamp is None or not self._after(stamp, mark):
                continue
            for action in LOG_INSTALL_ACTIONS:
                needle = LOG_ACTION_FAILED % action
                if needle in line:
                    # Newest wins: an install that failed, was repaired and failed
                    # again reports the failure that ended it.
                    verdict = line.split(needle, 1)[1].strip() or action + " failed"
        return verdict

    def _scroll_to_install_log_row(self, max_swipes=8):
        """Swipe the window's list until the "Install log" row's View button is on
        screen. The row sits below the action row and below the version chooser,
        and a uiautomator dump only holds on-screen nodes, so reaching it needs a
        walk of its own - the same reason scroll_to_action_button exists. The
        button is matched on its (text, content-desc) pair: three rows on this
        screen end in a "View" button, and only this one names its row."""
        for _ in range(max_swipes):
            els = self.dump()
            button = self._install_log_button(els)
            if button:
                return button
            self._scroll_swipe(els)
            time.sleep(1.2)
        return self._install_log_button(self.dump())

    def _install_log_button(self, elements):
        """The "Install log" row's tappable node, or None.

        The row's button carries the row's name as its content-desc - the app
        gave it one because three "View" buttons now sit on that screen (Connection
        diagnostics, About, Install log) and a screen reader heard all three as
        just "View" - so content-desc is what identifies this row. A merged a11y
        node for the whole row is accepted too, because it carries the same name
        and taps through to the same dialog; what must not happen is tapping
        another row's View button, which matching on the label alone rules out.
        The "View"-labelled candidate wins when both are present."""
        candidates = [el for el in elements
                      if LABEL_INSTALL_LOG in el.attrs.get("content-desc", "")]
        for el in candidates:
            if el.attrs.get("text") == BUTTON_VIEW:
                return el
        return candidates[0] if candidates else None

    def _read_install_log(self, tag):
        """The install trace the app itself kept, read through the row that shows
        it: Settings -> the Ubuntu on this device window -> "Install log" -> View.

        This is the channel the ring was built for and, until this reader, the
        only one nobody had exercised. `UserspaceDiagnostics` writes to logcat
        as well, but logcat is a wrapping buffer read after the fact by whoever
        has `adb` - and the person holding a failed install has neither. The
        dialog is the app's own answer to "why did it fail", so the pipeline
        drives it the way a user would and keeps the text as the run's evidence.

        Never raises: a repair of the trace UI is not what this run is testing,
        and a failure here must not become the reported cause of an install that
        failed for its own reasons. It records what it saw in
        [self._install_log_read] - `opened`, `header`, `events` - so the caller
        that IS testing the row (exercise_install_log) can tell a row that is
        missing from a row that opened onto an empty trace. The events come back
        newest first, the order the dialog renders them in; a LazyColumn dump
        holds only the newest screenful, which for a failed install is the part
        that says why.
        """
        events = []
        self._install_log_read = {"opened": False, "header": None, "events": events}
        try:
            # launch() first, and not only as a "make sure the app is up": MainActivity
            # is singleTask, so starting it brings the app forward and clears the
            # window on top of it. This reader therefore always begins from the
            # Settings list, whether the phase before it left the window open or not.
            self.launch()
            self.open_settings()
            if not self.scroll_to_linux_section():
                self.log("install log: the Linux userspace section was never visible")
                return events
            self.open_ubuntu_window()
            button = self._scroll_to_install_log_row()
            if not button:
                self.log("install log: the row's View button was not on screen")
                return events
            if not self.tap(button):
                self.log("install log: the View button could not be tapped")
                return events
            time.sleep(2)
            texts = self._texts(self.dump())
            header = next((t for t in texts if "event(s) ·" in t), None)
            events.extend(t for t in texts if INSTALL_LOG_LINE.match(t))
            self._install_log_read["opened"] = True
            self._install_log_read["header"] = header
            path = os.path.join(self.out, "install-log-%s.txt" % tag)
            with open(path, "w") as fh:
                fh.write("# %s\n# opened from Settings -> the Ubuntu on this device window -> %s\n" %
                         (time.strftime("%Y-%m-%d %H:%M:%S"), LABEL_INSTALL_LOG))
                if header:
                    fh.write("# the app reports: %s\n" % header)
                fh.write("\n".join(events) + "\n")
            if self.results:
                self.results[-1].evidence.append(path)
            self.log("install log (%s): %d line(s) on screen, %s"
                     % (tag, len(events), header or "no header"))
            # The dialog, then the window it was opened from: this reader entered
            # both, and a window left stacked would still be in front for the next
            # phase's first dump. Guarded on the card header, so a tap that never
            # opened the dialog cannot make this press Back out of a screen the
            # driver is not on.
            self.adb.back()
            time.sleep(1)
            if self.visible(WINDOW_HEADER):
                self.adb.back()
                time.sleep(1)
        except Exception as exc:  # never the reported cause of the phase
            self.log("install log (%s) unreadable: %s" % (tag, exc))
        return events

    def exercise_install_log(self):
        """The row as its own check, on a device, after a successful install.

        The trace exists so that a user whose install failed can send the reason
        without `adb`, and a trace nobody can open is worth nothing: this is the
        end-to-end proof that the row is reachable, that the dialog reads the ring
        back, and that the ring holds the install that just ran - the app's own
        count and its own event lines, not an empty state.

        Two verdicts, kept apart because they mean different things: a row that was
        never reached (the UI does not offer the trace), and a dialog that opened
        onto nothing (the ring was never written - the failure this whole channel
        is supposed to make impossible). The categories actually seen are logged
        rather than asserted one by one: the dialog shows the newest screenful
        only, so which subsystems appear depends on how far back a screenful
        reaches, and an assertion that named one would fail on a trace that is
        perfectly good."""
        events = self._read_install_log("installed")
        read = self._install_log_read
        if not read["opened"]:
            raise RuntimeError(
                "the install log row could not be opened after a successful install -"
                " the trace is unreachable from the UI")
        if not events:
            raise RuntimeError(
                "the install log dialog opened onto an empty trace (%s) - nothing the"
                " install did reached the ring" % (read["header"] or "no header"))
        categories = sorted({t[1:t.index("]")] for t in events})
        self.log("the install log row reads back %d event(s) from the install: %s"
                 % (len(events), ", ".join(categories)))

    def _inconclusive_note(self):
        """Why the screen could not answer, appended to the failure it caused."""
        if self._walk_stuck:
            return " (the settings list would not scroll, so the rows below the fold" \
                   " could not be read)"
        return ""

    def _reenter_settings_during_install(self, disturbance):
        """After any disturbance the activity may have been recreated on the host
        list; get back to the Settings list and read what the install row says
        now. Three outcomes, kept apart because they call for different
        responses: alive (Installing or Installed), ended (the row is back to Not
        installed, or Needs repair - the install's own failure, which the
        disturbance only surfaced), or nothing about the install on screen at all
        (the lifecycle failure this check exists to catch). The disturbance's name
        rides along into the failure so the report says which survival check did
        not pass.

        The row, not the window: relaunching the app lands on the Settings list -
        MainActivity is singleTask, so starting it clears the window the install
        was started from - and that row's summary is the app's own statement of the
        same state, step included. Going back into the window would test the
        driver's navigation a second time, not the feature."""
        if self.visible(LABEL_INSTALLED):
            return
        self.launch()
        self.open_settings()
        # Required, not discarded: everything below reads the install row, and a walk
        # that never reached the section would read it off whatever screen is actually
        # showing - which is how a lost tap becomes "nothing about the install on
        # screen at all", a lifecycle failure this phase never had.
        self.require_linux_section()
        seen, els = self._walk_linux_section()
        title, line = self.install_state(els)
        kind = self.install_state_kind(title, line)
        if kind == "alive":
            return
        detail = self._install_failure_detail(seen)
        if kind == "ended" or detail:
            # Either the state row itself says the install is over, or the app's
            # own error row is on screen (the app sets it only when an operation
            # failed). Both mean the install ended and this check merely got
            # there after it did - which is the install's failure to report, not
            # the lifecycle loss the disturbance's name would imply.
            raise RuntimeError(
                "INSTALL stage: the install had already ended when the %s disturbance was"
                " checked: %s%s. On screen: %s"
                % (disturbance,
                   ("the row reads %s" % line[:120]) if kind == "ended" else "the app reports",
                   (": %s" % detail) if detail and detail != line else "",
                   " | ".join(seen[:40])))
        # The screen could not say. The app's own log can: it records the failure
        # the moment it happens, from the service, whatever is on screen.
        logged = self._install_log_verdict()
        if logged:
            raise RuntimeError(
                "INSTALL stage: the install had already ended when the %s disturbance was"
                " checked - the app's log reports: %s%s. On screen: %s"
                % (disturbance, logged[:200], self._inconclusive_note(),
                   " | ".join(seen[:40])))
        raise RuntimeError(
            "LIFECYCLE stage: the install did not survive the %s disturbance"
            " - neither Installing nor Installed was on screen%s%s. "
            "On screen: %s"
            % (disturbance,
               (": %s" % detail) if detail else "",
               self._inconclusive_note(),
               " | ".join(seen[:40])))

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
        # Parenthesised, and never allowed to raise. This runs from storage_gate's
        # finally, so an exception here REPLACES the phase's own verdict - and it
        # did: `"..." % x // 1024` binds as `("..." % x) // 1024` (a str // int
        # TypeError), which turned run 35106576845's storage gate into "fail at UI"
        # even though the device had just refused the install and named the need.
        try:
            free_mb = (self._data_avail_kb() or 0) // 1024
            self.log("filler removed, /data free again: %dMB" % free_mb)
        except Exception as exc:  # cleanup must not mask what the phase found
            self.log("filler removed, but reading /data back failed: %s" % exc)

    def _data_avail_kb(self):
        rc, out = self.adb.shell("df /data", timeout=30)
        if rc != 0:
            return 0
        # The row is found by shape, not by its mount point. `df /data` reports
        # the mount the path resolves to, and on API 35 that is not "/data":
        #
        #   Filesystem       1K-blocks   Used Available Use% Mounted on
        #   /dev/block/dm-43   6082144 319032   5763112   6% /mnt/pass_through/0/emulated
        #
        # The first version anchored the match on "/data" as the last field, so
        # it never matched on this image and the whole storage-failure phase
        # aborted with "could not read /data free space" (run 35100526297) --
        # on every FULL run, since FULL is the only mode that reaches it.
        # toybox prints plain 1K-blocks here but human units ("57G") elsewhere,
        # so the unit is read rather than assumed.
        for line in out.strip().splitlines()[1:]:
            fields = line.split()
            if len(fields) < 6 or not fields[0].startswith("/"):
                continue
            match = re.match(r"(\d+(?:\.\d+)?)([KMGT]?)$", fields[3])
            if not match:
                continue
            try:
                value = float(match.group(1))
            except ValueError:
                return 0
            factor = {"": 1.0, "K": 1.0, "M": 1024.0, "G": 1024.0 * 1024.0, "T": 1024.0 ** 3}[match.group(2)]
            return int(value * factor)
        return 0

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
        # Required, not discarded - the reason this phase cannot be fooled by the
        # screen it is not on: the state it reads next is the Settings list's own row,
        # and every other screen the app has answers "no recognizable state" two
        # minutes later, which is how a navigation failure gets published as a
        # userspace that never settled.
        self.require_linux_section()
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
        # The retry: whatever honest state it landed in offers a way forward, and
        # that way is in the controls window now - the Settings row reports the
        # state and the button that acts on it is one tap behind it. NeedsRepair is
        # the tallest the window gets - three installed-state rows sit above the
        # action row - so the button is below the fold, and the walk inside
        # action_button_in_window is the whole difference between "the button was
        # not offered" and "the button is not in this dump".
        button = BUTTON_REPAIR if state == "needs-repair" else BUTTON_INSTALL
        if not self.action_button_in_window(button):
            raise RuntimeError(
                "INTERRUPT stage: the %s button was not offered after recovery" % button)
        time.sleep(1.5)
        # The repair path re-runs setup; the fresh install path asks for
        # confirmation again. Either way, wait it out.
        self.tap_last(self.dump(), BUTTON_INSTALL, exact=True)
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
        if not self.action_button_in_window(button):
            raise RuntimeError("NETWORK stage: the %s button was not offered after the failure" % button)
        time.sleep(1.5)
        self.tap_last(self.dump(), BUTTON_INSTALL, exact=True)
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
            try:
                self.install_via_ui(self.install_timeout_min,
                                    exercises=self.mode in ("STANDARD", "FULL"))
            except Exception:
                # The trace before the re-raise: this is the evidence a user with
                # a failed install would send, collected by the driver doing what
                # that user would do - opening the row and reading the dialog.
                self._read_install_log("install-failed")
                raise

        with self.phase("install-log", requires=("install",)):
            self.exercise_install_log()

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
        self._write_device_state()
        self._write_results()
        failed = [r for r in self.results if r.status == "fail"]
        return 1 if failed else 0

    def _write_device_state(self):
        """The device's own answer to the two questions a failed install raises and
        the log cannot settle afterwards: how much storage was left, and which
        syscalls this API level lets the app make at all.

        Both are cheap to ask and expensive to reconstruct. An install that dies
        with apt exiting 100 is either out of space or making a call the platform
        traps, and the two look identical in apt's own output - the storage
        reading only holds at the moment it is taken, and the seccomp policy is a
        property of the API level the run happened on, which no later run on a
        different image reproduces (E2E run 35056615874: an apt failure whose
        cause could not be told apart from either).
        """
        commands = (
            ("date", "date"),
            ("model", "getprop ro.product.model"),
            ("sdk", "getprop ro.build.version.sdk"),
            ("df /data", "df /data"),
            ("df /data/local/tmp", "df /data/local/tmp"),
            ("seccomp app policy", "cat /system/etc/seccomp_policy/app.seccomp-policy 2>&1 | head -400"),
            ("seccomp app allowlist", "cat /system/etc/seccomp_policy/app.seccomp-allowlist 2>&1 | head -400"),
            ("seccomp common policy", "cat /system/etc/seccomp_policy/common.seccomp-policy 2>&1 | head -400"),
        )
        lines = []
        for label, command in commands:
            rc, out = self.adb.shell(command, timeout=30)
            lines.append("## %s (rc=%d)" % (label, rc))
            lines.append((out or "").strip())
            lines.append("")
        with open(os.path.join(self.out, "device-state.txt"), "w") as fh:
            fh.write("\n".join(lines))
        self.log("device state written: %s" % ", ".join(label for label, _ in commands))

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
    else:
        # A run with failures ends "Tests run: 14,  Failures: 1" instead of
        # "OK (14 tests)", so reading only the OK line reported a failing suite
        # as "0 tests, 1 failed" and hid how much had actually run.
        m = re.search(r"^Tests run: (\d+)", stdout_text, flags=re.M)
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
