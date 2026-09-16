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


class _RecordingAdb:
    """An Adb stand-in that records taps instead of sending them."""

    def __init__(self, taps):
        self.taps = taps

    def tap(self, x, y):
        self.taps.append((x, y))
        return True


if __name__ == "__main__":
    unittest.main(verbosity=2)
