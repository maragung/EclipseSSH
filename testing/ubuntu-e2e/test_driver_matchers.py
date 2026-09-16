#!/usr/bin/env python3
"""The driver's element matchers, tested without a device.

These are the driver's eyes, and the way they break is not by crashing: the
substring match for "Install" that a uiautomator dump satisfies with the
"Install log" row's View button opened the trace dialog instead of the action
row (E2E run 35061538315), so the install never started and the run reported a
missing confirmation dialog - a UI regression in the *driver* reported as an app
failure. Every case here is one that a 20-minute emulator run would otherwise be
the first to find.

Run: python3 testing/ubuntu-e2e/test_driver_matchers.py
"""

import os
import re
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "universal"))

import driver  # noqa: E402
from adbutil import Element  # noqa: E402

DRIVER_PATH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "driver.py")

# The Linux userspace section as a uiautomator dump renders it in the NotInstalled
# state, in dump order, with the app's real strings (MainActivity's
# LinuxUserspaceSection). Bounds are portrait values; only their ordering matters.
SECTION = [
    Element({"text": "Linux userspace", "bounds": "[40,700][1040,760]"}),
    Element({"text": "Ubuntu on this device", "bounds": "[160,820][700,880]"}),
    Element({"text": "Not installed", "bounds": "[160,880][500,930]"}),
    Element({"text": "20.04 LTS", "bounds": "[160,950][400,1000]"}),
    Element({"text": "Install", "bounds": "[700,1350][900,1420]"}),
    Element({"text": "Install log", "bounds": "[220,1560][520,1620]"}),
    Element({"text": "Records every install and repair step · no secrets",
             "bounds": "[220,1620][900,1740]"}),
    Element({"text": "View", "content-desc": "Install log",
             "bounds": "[820,1560][980,1620]"}),
]


class ActionButtonMatchers(unittest.TestCase):
    def setUp(self):
        self.driver = driver.E2eDriver.__new__(driver.E2eDriver)
        self.tapped = []
        self.driver.adb = _RecordingAdb(self.tapped)

    def test_exact_match_finds_the_action_row_not_the_trace_row(self):
        hit = self.driver.find(SECTION, driver.BUTTON_INSTALL, exact=True)
        self.assertIsNotNone(hit)
        self.assertEqual("Install", hit.attrs["text"])
        self.assertNotIn("Install log", hit.attrs.get("content-desc", ""))

    def test_the_loose_match_is_what_the_trace_row_satisfies(self):
        # Not an endorsement: this is the trap the exact match exists to avoid, and
        # it is pinned so that a future loosening of the matcher is deliberate.
        # find() returns the FIRST match and tap_last() the LAST, and the action
        # row sits above the trace row - so the walk found the right button and the
        # tap hit the wrong one, which is exactly what run 35061538315 did.
        self.assertEqual("Install", self.driver.find(SECTION, driver.BUTTON_INSTALL).attrs["text"])
        self.assertTrue(self.driver.tap_last(SECTION, driver.BUTTON_INSTALL))
        x, y = self.tapped[0]
        self.assertTrue(1560 <= y <= 1620, "the loose tap must land on the trace row: %d" % y)

    def test_tap_last_exact_taps_the_action_button(self):
        self.assertTrue(self.driver.tap_last(SECTION, driver.BUTTON_INSTALL, exact=True))
        self.assertEqual(1, len(self.tapped))
        x, y = self.tapped[0]
        # The action row's own bounds, not the trace row's (which is what the
        # loose match tapped: the run's screenshot showed the dialog open).
        self.assertTrue(700 <= x <= 900, x)
        self.assertTrue(1350 <= y <= 1420, y)

    def test_tap_last_exact_prefers_the_trailing_declaration(self):
        # A confirmation dialog renders after the screen behind it: both Install
        # buttons are in the hierarchy and the dialog's is the last one.
        dialog = SECTION + [
            Element({"text": "Install Ubuntu?", "bounds": "[180,900][900,980]"}),
            Element({"text": "root filesystem", "bounds": "[180,1000][900,1100]"}),
            Element({"text": "Cancel", "bounds": "[600,1300][780,1370]"}),
            Element({"text": "Install", "bounds": "[800,1300][980,1370]"}),
        ]
        self.assertTrue(self.driver.tap_last(dialog, driver.BUTTON_INSTALL, exact=True))
        x, y = self.tapped[0]
        self.assertTrue(1300 <= y <= 1370, y)

    def test_the_install_log_row_is_tapped_by_its_own_name(self):
        hit = self.driver._install_log_button(SECTION)
        self.assertIsNotNone(hit)
        self.assertEqual("View", hit.attrs["text"])
        self.assertEqual(driver.LABEL_INSTALL_LOG, hit.attrs["content-desc"])

    def test_a_merged_row_node_is_accepted(self):
        # Compose may fold the row into one node; it carries the same name and
        # taps through to the same dialog.
        merged = [Element({"text": "Install log", "content-desc": "Install log",
                           "bounds": "[220,1560][980,1620]"})]
        self.assertIsNotNone(self.driver._install_log_button(merged))

    def test_another_rows_view_button_is_never_taken(self):
        others = [
            Element({"text": "Connection diagnostics", "bounds": "[220,300][700,360]"}),
            Element({"text": "View", "content-desc": "Connection diagnostics",
                     "bounds": "[820,300][980,360]"}),
            Element({"text": "About EclipseSSH", "bounds": "[220,400][700,460]"}),
            Element({"text": "View", "content-desc": "About EclipseSSH",
                     "bounds": "[820,400][980,460]"}),
        ]
        self.assertIsNone(self.driver._install_log_button(others))

    def test_no_call_site_matches_an_action_button_loosely(self):
        """The source-level guard, because this is a matcher that is only wrong on
        a screen no unit test can build: every find/tap_last that names the action
        buttons - by the constant or through the state-chosen variable - must ask
        for an exact match."""
        with open(DRIVER_PATH) as fh:
            source = fh.read()
        calls = re.findall(r"(?:tap_last|self\.find)\([^\n]{0,160}?"
                           r"(?:BUTTON_(?:INSTALL|REPAIR)|\bbutton\b)[^\n]{0,80}?\)", source)
        self.assertGreaterEqual(len(calls), 6, calls)
        loose = [c for c in calls if "exact=True" not in c]
        self.assertEqual([], loose, "these action-button matchers would satisfy "
                                    "'Install log': %s" % loose)


class InstallLogLines(unittest.TestCase):
    def test_a_failure_line_matches_the_event_pattern(self):
        line = ('[apt] bulk base-package install failed exit=100 '
                'detail="update-alternatives: error: alternative link is not absolute')
        self.assertTrue(driver.INSTALL_LOG_LINE.match(line))

    def test_every_category_matches(self):
        for category in ("storage", "rootfs", "download", "proot", "dns", "apt"):
            self.assertTrue(driver.INSTALL_LOG_LINE.match("[%s] something happened" % category))

    def test_the_dialogs_own_chrome_does_not_match(self):
        for text in ("Install log",
                     "3 event(s) · every line is a subsystem step or an error's own text,"
                     " scrubbed before it was recorded, so this is safe to attach to a bug report.",
                     "Nothing recorded yet. Install or repair Ubuntu on this device and this "
                     "becomes a timestamped trace of every download, extraction and apt step.",
                     "06:19:24",
                     "Copy", "Save", "Clear", "Close"):
            self.assertIsNone(driver.INSTALL_LOG_LINE.match(text), text)


class DataAvailParsing(unittest.TestCase):
    """What `df /data` actually prints, exactly as run 35100526297's evidence
    captured it. The storage-failure phase aborted on this line, on every FULL
    run, because the parse anchored on a "/data" mount point that this image
    never prints."""

    # device-state.txt from run 35100526297, verbatim.
    REAL = ("Filesystem       1K-blocks   Used Available Use% Mounted on\n"
            "/dev/block/dm-43   6082144 319032   5763112   6% /mnt/pass_through/0/emulated")

    def _avail(self, out, rc=0):
        d = driver.E2eDriver.__new__(driver.E2eDriver)
        d.adb = _DfAdb(out, rc)
        return d._data_avail_kb()

    def test_the_emulators_own_output_is_read(self):
        self.assertEqual(5763112, self._avail(self.REAL))

    def test_a_data_mount_point_still_reads(self):
        # An image that resolves /data to itself must not stop working.
        self.assertEqual(5763112, self._avail(
            "Filesystem     1K-blocks    Used Available Use% Mounted on\n"
            "/dev/block/dm-43  6082144 319032   5763112   6% /data"))

    def test_human_units_are_converted(self):
        self.assertEqual(int(5.6 * 1024 * 1024), self._avail(
            "Filesystem  1K-blocks  Used Available Use% Mounted on\n"
            "/dev/block/dm-43  6G  310M  5.6G  6% /data"))

    def test_an_unreadable_df_is_zero_not_a_wrong_number(self):
        self.assertEqual(0, self._avail("", rc=1))
        self.assertEqual(0, self._avail("df: /data: No such file or directory"))
        # The Available column itself, not the 1K-blocks one: corrupting the
        # wrong column is exactly the mistake this case first made.
        self.assertEqual(0, self._avail(self.REAL.replace("5763112", "abc")))


class SuiteParsing(unittest.TestCase):
    """The counts the driver prints for an instrumentation phase. A failing run
    ends "Tests run: 14,  Failures: 1" rather than "OK (14 tests)", so reading
    only the OK line reported it as "0 tests, 1 failed" - a phase that ran
    fourteen tests looked like one that ran none."""

    # instrument-write.txt from run 35100526297, abridged in the middle.
    FAILING = (
        "dev.eclipse.ssh.linux.UbuntuE2eVerificationTest:.....\n"
        "Error in hardLinksShareOneInode(dev.eclipse.ssh.linux.UbuntuE2eVerificationTest):\n"
        "java.lang.IllegalStateException: UBUNTU SHELL stage: 'test /tmp/link-probe/a "
        "-ef /tmp/link-probe/b' exited 1: \n"
        "\tat dev.eclipse.ssh.linux.UbuntuE2eVerificationTest.sessionSucceeds(unknown:66)\n"
        ".......\n\nTime: 5.356\nThere was 1 failure:\n"
        "1) hardLinksShareOneInode(dev.eclipse.ssh.linux.UbuntuE2eVerificationTest)\n"
        "java.lang.IllegalStateException: UBUNTU SHELL stage: 'test /tmp/link-probe/a "
        "-ef /tmp/link-probe/b' exited 1: \n"
        "\tat dev.eclipse.ssh.linux.UbuntuE2eVerificationTest.sessionSucceeds(unknown:66)\n"
        "\nFAILURES!!!\nTests run: 14,  Failures: 1\n")

    def test_a_failing_run_reports_the_tests_that_ran(self):
        total, failed, failures = driver._parse_suite(self.FAILING)
        self.assertEqual(14, total)
        self.assertEqual(1, failed)
        self.assertIn("hardLinksShareOneInode", failures[0]["test"])

    def test_a_passing_run_still_reads_the_ok_line(self):
        total, failed, failures = driver._parse_suite(
            "dev.eclipse.ssh.linux.UbuntuE2eVerificationTest:..............\n"
            "\nOK (14 tests)\n")
        self.assertEqual(14, total)
        self.assertEqual(0, failed)
        self.assertEqual([], failures)


class OpenSettingsRecovery(unittest.TestCase):
    """open_settings when the app is not on screen. The interruption phases call
    it right after pm clear, so the dump shows the launcher - which is what run
    35100526297 reported as "the Settings tab was not found on screen" while the
    app was simply not running."""

    def setUp(self):
        self.driver = driver.E2eDriver.__new__(driver.E2eDriver)
        self.driver.package = "dev.eclipse.ssh"
        self.adb = _LauncherThenAppAdb()
        self.driver.adb = self.adb
        self._sleep = driver.time.sleep
        # launch() waits four seconds and open_settings two; the pacing is not
        # what this test is about, and the waits are additive per case.
        driver.time.sleep = lambda *_: None

    def tearDown(self):
        driver.time.sleep = self._sleep

    def test_the_app_is_put_back_and_settings_opened(self):
        self.driver.open_settings()
        self.assertEqual([("dev.eclipse.ssh", ".MainActivity")], self.adb.starts)
        self.assertEqual(1, len(self.adb.taps))

    def test_a_settings_tab_that_is_really_gone_still_fails(self):
        self.adb.shows_settings = False
        with self.assertRaises(RuntimeError) as caught:
            self.driver.open_settings()
        self.assertIn("Settings tab was not found", str(caught.exception))
        # Relaunched once, not in a loop: a real UI regression must stay a failure.
        self.assertEqual(1, len(self.adb.starts))


class StorageGateCleanup(unittest.TestCase):
    """_remove_filler runs from storage_gate's `finally`, so anything it raises
    REPLACES the phase's own verdict - and a precedence slip did exactly that:
    `"..." % x // 1024` parses as `("..." % x) // 1024`, a str // int TypeError
    (run 35106576845). The storage gate was published as "fail at UI" while
    logcat carried the device doing precisely what the phase asserts: "Ubuntu
    needs about 743 MB of free storage to install ... but only about 166 MB is
    free. Free up storage and try again." """

    # device-state.txt from run 35100526297, verbatim (as DataAvailParsing above).
    REAL = ("Filesystem       1K-blocks   Used Available Use% Mounted on\n"
            "/dev/block/dm-43   6082144 319032   5763112   6% /mnt/pass_through/0/emulated")

    def _driver(self, out, rc=0):
        d = driver.E2eDriver.__new__(driver.E2eDriver)
        d.adb = _DfAdb(out, rc)
        d.lines = []
        d.log = d.lines.append
        return d

    def test_the_cleanup_reports_the_free_space_in_megabytes(self):
        d = self._driver(self.REAL)
        d._remove_filler()
        self.assertEqual(1, len(d.lines))
        self.assertIn("5628MB", d.lines[0])  # 5763112 KB // 1024

    def test_a_failing_df_still_does_not_raise_from_the_cleanup(self):
        # The reason the try/except is there: this runs in a finally, so a raise
        # here would take the phase's real finding down with it.
        d = self._driver("", rc=1)
        d._remove_filler()
        self.assertIn("0MB", d.lines[0])


class StageAttribution(unittest.TestCase):
    """The failure report's stage column says WHERE a failure lives, so a bug in
    this driver must never be published as a device finding. The unattributed
    fallback is "UI" - which is how run 35106576845's TypeError (the precedence
    slip above, raised by the driver's own line) reached the report as a UI
    failure while the app had refused the install exactly as designed."""

    TYPE_ERROR = "unsupported operand type(s) for //: 'str' and 'int'"

    def _stage(self, message, kind=None):
        d = driver.E2eDriver.__new__(driver.E2eDriver)
        return d._stage_from_error(message, kind)

    def test_a_driver_bug_is_attributed_to_the_driver(self):
        self.assertEqual("DRIVER", self._stage(self.TYPE_ERROR, TypeError))

    def test_without_the_kind_the_blind_fallback_would_have_called_it_ui(self):
        # Pinned, not endorsed: this is what the run published, and the reason
        # the kind is now passed in.
        self.assertEqual("UI", self._stage(self.TYPE_ERROR))

    def test_the_stages_the_phases_name_are_unchanged(self):
        for message, expected in (
            ("INTERRUPT stage: the Repair button was not offered after recovery", "INTERRUPT"),
            ("NETWORK stage: could not enable airplane mode", "NETWORK"),
            ("STORAGE stage: could not adb root the emulator (rc=1)", "STORAGE"),
            ("VERIFY stage: instrumentation phase 'write' - hardLinksShareOneInode", "VERIFY"),
        ):
            self.assertEqual(expected, self._stage(message, RuntimeError))


class ActionButtonWalk(unittest.TestCase):
    """The walk that brings the action row into the dump. scroll_to_linux_section
    stops at the section TITLE, and the action row sits below it - furthest below
    in NeedsRepair, where three installed-state rows stand between them. Both
    interruption phases tapped straight after the section walk, so in run
    35106576845 the Repair button the screen was showing was never in the dump
    they searched, and the phase reported "the Repair button was not offered
    after recovery"."""

    # screen-interrupt-process-144052.txt, verbatim for the Linux userspace
    # section: title and state row on screen, no action row anywhere in the dump.
    TOP = [
        Element({"text": "LINUX USERSPACE", "bounds": "[32,1177][345,1219]"}),
        Element({"text": "Ubuntu on this device", "bounds": "[155,1277][588,1340]"}),
        Element({"text": "Needs repair · a previous install was interrupted",
                 "bounds": "[155,1340][881,1382]"}),
        Element({"text": "Storage used", "bounds": "[155,1456][421,1519]"}),
        Element({"text": "394.9 MB", "bounds": "[155,1519][302,1561]"}),
        Element({"text": "Health check", "bounds": "[155,1867][417,1930]"}),
        Element({"text": "healthy", "bounds": "[155,1930][265,1938]"}),
        Element({"text": "Verify", "bounds": "[888,1893][985,1938]"}),
        Element({"text": "Settings", "bounds": "[917,2190][1043,2232]"}),
    ]

    # One swipe further up the same list: the action row has arrived.
    SWIPED = TOP + [
        Element({"text": "Repair", "bounds": "[153,1990][452,2110]"}),
        Element({"text": "Uninstall", "bounds": "[845,1990][1063,2110]"}),
    ]

    def setUp(self):
        self.driver = driver.E2eDriver.__new__(driver.E2eDriver)
        self._sleep = driver.time.sleep
        # Each swipe in the walk waits 1.2s, and this class swipes several times.
        driver.time.sleep = lambda *_: None

    def tearDown(self):
        driver.time.sleep = self._sleep

    def test_the_dump_that_failed_holds_no_repair_button(self):
        self.assertIsNone(self.driver.find(self.TOP, driver.BUTTON_REPAIR, exact=True))

    def test_the_section_walk_stops_at_the_title_without_swiping(self):
        # The precondition both interrupt phases satisfied, and the reason the
        # button was never in the dump they searched: the section walk is not
        # the action walk.
        self.driver.adb = _ScrollingAdb([self.TOP])
        self.assertTrue(self.driver.scroll_to_linux_section())
        self.assertEqual([], self.driver.adb.swipes)

    def test_the_walk_brings_repair_into_the_dump(self):
        self.driver.adb = _ScrollingAdb([self.TOP, self.SWIPED])
        self.assertTrue(self.driver.scroll_to_action_button(driver.BUTTON_REPAIR))
        self.assertEqual(1, len(self.driver.adb.swipes))

    def test_the_walk_still_finds_install_the_way_it_always_did(self):
        top = [Element({"text": "Ubuntu on this device", "bounds": "[155,700][588,760]"}),
               Element({"text": "Not installed · real bash, apt, Node.js and Python",
                        "bounds": "[155,760][991,844]"}),
               Element({"text": "Settings", "bounds": "[917,2190][1043,2232]"})]
        self.driver.adb = _ScrollingAdb([top, top + [
            Element({"text": "Install", "bounds": "[153,1635][400,1755]"})]])
        self.assertTrue(self.driver.scroll_to_action_button())
        self.assertEqual(1, len(self.driver.adb.swipes))

    def test_a_button_that_is_really_gone_stays_a_failure(self):
        # A real regression must not be swallowed by the walk: it gives up.
        self.driver.adb = _ScrollingAdb([self.TOP])
        self.assertFalse(self.driver.scroll_to_action_button(driver.BUTTON_REPAIR, max_swipes=3))
        self.assertEqual(3, len(self.driver.adb.swipes))

    def test_the_exact_match_taps_the_button_not_the_row_that_names_it(self):
        # The state row says "Needs repair - ..." and the button says "Repair":
        # only the exact match is tappable, and it must land on the button.
        self.driver.adb = _ScrollingAdb([self.SWIPED])
        self.assertTrue(self.driver.tap_last(self.SWIPED, driver.BUTTON_REPAIR, exact=True))
        x, y = self.driver.adb.taps[0]
        self.assertTrue(1990 <= y <= 2110, y)


class _DfAdb:
    def __init__(self, out, rc=0):
        self.out = out
        self.rc = rc

    def shell(self, cmd, timeout=30):
        return self.rc, self.out


class _LauncherThenAppAdb:
    """A screen that is the launcher until the app is started, like the dump from
    run 35100526297's interruption phase (Search, Gallery, Phone, ... no app)."""

    LAUNCHER = [Element({"text": "Phone", "content-desc": "Phone", "bounds": "[23,2106][230,2272]"}),
                Element({"text": "Camera", "content-desc": "Camera", "bounds": "[851,2106][1057,2272]"})]
    APP = [Element({"text": "Settings", "content-desc": "Settings", "bounds": "[900,2272][1080,2400]"})]

    def __init__(self):
        self.starts = []
        self.taps = []
        self.launched = False
        self.shows_settings = True

    def ui_dump(self):
        if self.launched and self.shows_settings:
            return self.APP
        return self.LAUNCHER

    def am_start(self, package, activity):
        self.starts.append((package, activity))
        self.launched = True
        return True, "Status: ok"

    def tap(self, x, y):
        self.taps.append((x, y))
        return True


class _RecordingAdb:
    """An Adb stand-in that records taps instead of sending them."""

    def __init__(self, taps):
        self.taps = taps

    def tap(self, x, y):
        self.taps.append((x, y))
        return True


class _ScrollingAdb:
    """A settings list that hands over the next screenful only when swiped, like
    the LazyColumn behind the app's settings tab: a uiautomator dump holds
    on-screen nodes, so the action row below the fold is absent until a swipe
    brings it up. The frames are the dumps, in swipe order."""

    def __init__(self, frames):
        self.frames = frames
        self.swipes = []
        self.taps = []

    def ui_dump(self):
        return self.frames[min(len(self.swipes), len(self.frames) - 1)]

    def swipe(self, x1, y1, x2, y2, duration):
        self.swipes.append((x1, y1, x2, y2, duration))
        return True

    def tap(self, x, y):
        self.taps.append((x, y))
        return True


if __name__ == "__main__":
    unittest.main(verbosity=2)
