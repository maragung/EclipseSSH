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

# The app's bottom bar as run 35311046886's dump draws it: five labels in the window's
# bottom band, with the active destination's own tab marked selected. The bounds are
# that dump's, verbatim.
BAR_BOUNDS = {"Hosts": (55, 144), "Terminal": (254, 387), "Files": (504, 577),
              "Transfers": (688, 833), "Settings": (917, 1043)}


def _window_root():
    """The hierarchy's own root node, which every real dump carries: it spans the
    window, and it is the node the bottom band is measured from (the driver's
    _window_extent takes the widest and tallest node as the window, which the root
    is). A fixture without it is measured against its own tallest node instead, and
    a synthetic bar that has drifted from the device's geometry stops being caught."""
    return Element({"class": "hierarchy", "bounds": "[0,0][1080,2400]"})


def _bar(active, selected_attr=True):
    """The bottom bar, on `active`. With selected_attr=False the dump carries no
    selected attribute at all, which is the shape a stricter dumper would emit."""
    els = [_window_root()]
    for label, (left, right) in BAR_BOUNDS.items():
        attrs = {"text": label, "content-desc": label,
                 "bounds": "[%d,2190][%d,2232]" % (left, right)}
        if selected_attr:
            attrs["selected"] = "true" if label == active else "false"
        els.append(Element(attrs))
    return els


# The bar as run 35314746158's dump really draws it, which is the shape the device
# emits and the shape the first version of this proof could not read: five label Texts
# bearing the label's own box, and the `selected` state on five UNLABELLED item nodes
# above them - the node Compose's Modifier.selectable puts the semantics on. A tab is a
# fifth of a 1080 px bar and spans icon and label alike (~y 2074-2232), which is why the
# label's 42-px-tall box is not the item's.
ITEM_BOUNDS = {label: (216 * i, 216 * (i + 1)) for i, label in enumerate(BAR_BOUNDS)}


def _bar_with_containers(active):
    """The device's own shape: `selected` on the item node, never on the label's."""
    els = [_window_root()]
    for label, (left, right) in ITEM_BOUNDS.items():
        els.append(Element({"class": "android.view.View", "clickable": "true",
                            "selected": "true" if label == active else "false",
                            "bounds": "[%d,2074][%d,2232]" % (left, right)}))
    els.extend(_bar(None, selected_attr=False)[1:])
    return els


# The same five destinations as the app draws them in a WIDE window: a NavigationRail down
# the leading edge INSTEAD of the bottom bar (MainActivity.kt:1217 `isWide = maxWidth >=
# 700.dp`, :1222 NavigationRail). The label bounds are run 35317843457's landscape dump
# verbatim - `text='Hosts' bounds=[92,480][181,522]` … `text='Settings' bounds=[74,1031][200,1033]`
# - against a 2400x1080 window, where the rail overflows the window and clips its last two
# labels to a sliver. That clipping is the app's own shape in landscape, not the fixture's.
RAIL_BOUNDS = {"Hosts": (92, 181, 480, 522), "Terminal": (70, 203, 638, 680),
               "Files": (100, 173, 796, 838), "Transfers": (64, 209, 954, 957),
               "Settings": (74, 200, 1031, 1033)}

# The rail's item boxes, which are what a tap actually lands on: one per label, spanning
# the label with the margin a NavigationRailItem gives it, and the last one running past
# the window's own bottom edge the way the real rail does.
RAIL_ITEM_BOUNDS = {label: (0, 240, 390 + 158 * i, 548 + 158 * i)
                    for i, label in enumerate(RAIL_BOUNDS)}


def _rail(active, containers=True):
    """The app's landscape navigation: a 2400x1080 window, five labels stacked down the
    leading edge, and - as on the device - `selected` on the unlabelled item node each
    label sits in, never on the label's own node."""
    els = [Element({"class": "hierarchy", "bounds": "[0,0][2400,1080]"})]
    if containers:
        for label, (left, right, top, bottom) in RAIL_ITEM_BOUNDS.items():
            els.append(Element({"class": "android.view.View", "clickable": "true",
                                "selected": "true" if label == active else "false",
                                "bounds": "[%d,%d][%d,%d]" % (left, top, right, bottom)}))
    for label, (left, right, top, bottom) in RAIL_BOUNDS.items():
        attrs = {"text": label, "content-desc": label,
                 "bounds": "[%d,%d][%d,%d]" % (left, top, right, bottom)}
        if not containers:
            attrs["selected"] = "true" if label == active else "false"
        els.append(Element(attrs))
    return els

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


class InstallStateAcrossSurfaces(unittest.TestCase):
    """One install state, two spellings: the window's row (a title over the step) and
    the Settings list's row (title and state folded into one summary line). Since
    a30b276 the controls sit behind a row, so an install can be watched from either
    screen, and the readings are not interchangeable:

    - the window draws the row's own name twice - in the bar, and again as the card's
      row - and _texts() deduplicates, so a pairing built on it sees the name once,
      followed by the card's header, and the row that owns the state is unreachable;
    - on the list the state is in the summary line rather than the title, so a reader
      that only looks at titles sees no state at all - which made every lifecycle
      exercise fired during an install walk a list with nothing left to reveal and
      report the install as lost, the false failure install_state_kind's second
      spelling exists to prevent."""

    # The window mid-install, in dump order, with the app's real strings
    # (UbuntuActivity's card): the bar, its back arrow, the card's header, then the
    # card's row - "Installing <distro>" over the step - and the trace row below.
    WINDOW_INSTALLING = [
        Element({"text": "Ubuntu on this device", "bounds": "[155,190][588,250]"}),
        Element({"content-desc": "Back", "bounds": "[24,190][120,286]"}),
        Element({"text": "UBUNTU ON THIS DEVICE", "bounds": "[32,340][600,382]"}),
        Element({"text": "Installing Ubuntu 22.04 LTS", "bounds": "[155,470][700,530]"}),
        Element({"text": "Downloading · 512.0 MB of 1.2 GB", "bounds": "[155,530][881,572]"}),
        Element({"text": "Install log", "bounds": "[220,900][520,960]"}),
    ]

    # The same window once the install has settled: the bar and the card's row now
    # carry the same string, which is the case the dedup collapses.
    WINDOW_SETTLED = [
        Element({"text": "Ubuntu on this device", "bounds": "[155,190][588,250]"}),
        Element({"content-desc": "Back", "bounds": "[24,190][120,286]"}),
        Element({"text": "UBUNTU ON THIS DEVICE", "bounds": "[32,340][600,382]"}),
        Element({"text": "Ubuntu on this device", "bounds": "[155,470][588,530]"}),
        Element({"text": "Installed and verified · stopped", "bounds": "[155,530][881,572]"}),
    ]

    # The Settings list mid-install (MainActivity's LinuxUserspaceSection and
    # linuxUserspaceSummary): the section header, the row, its summary line, and the
    # row's control - a merged button node, labelled "Open" and named after its row.
    LIST = [
        Element({"text": "LINUX USERSPACE", "bounds": "[32,1177][345,1219]"}),
        Element({"text": "Ubuntu on this device", "bounds": "[155,1277][588,1340]"}),
    ]
    LIST_INSTALLING = LIST + [
        Element({"text": "Installing Ubuntu 22.04 LTS · Downloading · 512.0 MB of 1.2 GB",
                 "bounds": "[155,1340][881,1382]"}),
        Element({"text": "Open", "content-desc": "Ubuntu on this device",
                 "bounds": "[888,1330][1043,1390]"}),
    ]

    def setUp(self):
        self.driver = driver.E2eDriver.__new__(driver.E2eDriver)

    def test_the_window_names_the_step_while_it_installs(self):
        # What the progress log and the periodic install-progress screenshots are
        # read from: the driver logs the pair, so a hung install names its step.
        self.assertEqual(
            ("Installing Ubuntu 22.04 LTS", "Downloading · 512.0 MB of 1.2 GB"),
            self.driver.install_state(self.WINDOW_INSTALLING))
        self.assertEqual("Installing Ubuntu 22.04 LTS | Downloading · 512.0 MB of 1.2 GB",
                         self.driver.install_label(self.WINDOW_INSTALLING))

    def test_the_windows_bar_is_not_read_as_the_row(self):
        # The bar carries the row's own name, so the reader has to return the card's
        # row - the one below the card's header - and not the bar above it.
        self.assertEqual(("Ubuntu on this device", "Installed and verified · stopped"),
                         self.driver.install_state(self.WINDOW_SETTLED))

    def test_the_deduped_view_is_why_the_pairing_is_built_on_the_raw_dump(self):
        # Pinned as the trap it is, not endorsed: _texts() collapses the bar and the
        # card's row into a single entry, and that entry is the bar's - so a pairing
        # built on it can never reach the row that owns the state.
        texts = self.driver._texts(self.WINDOW_SETTLED)
        self.assertEqual(1, texts.count(driver.CARD_TITLE))
        self.assertLess(texts.index(driver.CARD_TITLE), texts.index(driver.WINDOW_HEADER))

    def test_the_list_folds_the_state_into_its_summary_line(self):
        title, line = self.driver.install_state(self.LIST_INSTALLING)
        self.assertEqual("Ubuntu on this device", title)
        self.assertEqual("alive", self.driver.install_state_kind(title, line))
        self.assertEqual("Installing Ubuntu 22.04 LTS · Downloading · 512.0 MB of 1.2 GB",
                         self.driver.install_label(self.LIST_INSTALLING))

    def test_a_settled_row_is_not_read_as_an_install_that_died(self):
        # "ended" means the install died, and it is what makes a lifecycle exercise
        # declare the install lost - so an intact userspace must read "alive" on
        # whichever screen the exercise relaunched the app onto.
        stopped_on_the_list = self.LIST + [
            Element({"text": "Installed and verified · stopped", "bounds": "[155,1340][881,1382]"})]
        for els in (self.WINDOW_SETTLED, stopped_on_the_list):
            self.assertEqual("alive",
                             self.driver.install_state_kind(*self.driver.install_state(els)))
        self.assertEqual("ended", self.driver.install_state_kind(
            *self.driver.install_state(stopped_on_the_list[:2] + [
                Element({"text": "Needs repair · a previous install was interrupted",
                         "bounds": "[155,1340][881,1382]"})])))

    def test_the_apps_own_error_row_never_reads_as_a_live_install(self):
        # The row that records why an operation ended is named after what failed, so
        # it begins with the Installing label (E2E run 35051269460) - and reading it
        # as a live install reports a dead one as alive.
        els = [Element({"text": "Last operation", "bounds": "[155,700][500,760]"}),
               Element({"text": "Installing base packages failed (exit 100)",
                        "bounds": "[155,760][900,802]"})]
        self.assertEqual((None, ""), self.driver.install_state(els))
        self.assertIsNone(self.driver.install_label(els))


class OpenWindowButton(unittest.TestCase):
    """The Settings row's own control, and the step the promotion added: every acting
    phase now goes list -> Open -> window -> action row. The row's title and its
    button carry the same string and only the button is tappable, so the match is on
    the row's own name - the same shape as the install-log row's View."""

    # The list's userspace row, in dump order, as the merged node Compose draws: the
    # button's label as the text, the row's name as its content-desc.
    LIST = InstallStateAcrossSurfaces.LIST + [
        Element({"text": "Not installed · real bash, apt and git, on the device",
                 "bounds": "[155,1340][881,1382]"}),
        Element({"text": "Open", "content-desc": "Ubuntu on this device",
                 "bounds": "[888,1330][1043,1390]"}),
    ]

    # The window that button opens: the same name in the bar, the card's header, and
    # the card's row under it.
    WINDOW = InstallStateAcrossSurfaces.WINDOW_SETTLED

    def setUp(self):
        self.driver = driver.E2eDriver.__new__(driver.E2eDriver)
        self.driver.lines = []
        self.driver.log = self.driver.lines.append
        # wait_visible() runs against a wall-clock deadline, so a no-op sleep alone
        # would spin for the real thirty seconds: the clock has to move with it.
        self.addCleanup(setattr, driver, "time", driver.time)
        driver.time = _AdvancingTime()

    def test_the_rows_own_button_is_taken_by_its_name(self):
        hit = self.driver._open_window_button(self.LIST)
        self.assertIsNotNone(hit)
        self.assertEqual(driver.BUTTON_OPEN, hit.attrs["text"])
        self.assertEqual(driver.CARD_TITLE, hit.attrs["content-desc"])

    def test_the_titles_own_row_is_never_tapped(self):
        # A dump whose button has scrolled out of view, or a device that is not
        # supported and draws none: the title is not tappable, so taking it would be
        # a tap the driver could lose silently.
        self.assertIsNone(self.driver._open_window_button(self.LIST[:2]))

    def test_another_rows_button_is_never_taken(self):
        others = [Element({"text": "Terminal font size", "bounds": "[155,300][700,360]"}),
                  Element({"text": "Change", "content-desc": "Terminal font size",
                           "bounds": "[888,300][1043,360]"})]
        self.assertIsNone(self.driver._open_window_button(others))

    def test_the_walk_opens_the_window_through_the_merged_button(self):
        adb = _WindowAdb(self.LIST, window=self.WINDOW)
        self.driver.adb = adb
        self.driver.open_ubuntu_window()
        self.assertEqual([], adb.swipes)  # the row was already on screen
        self.assertEqual(1, len(adb.taps))
        x, y = adb.taps[0]
        self.assertTrue(888 <= x <= 1043, x)  # the button's own bounds, not the row's
        self.assertTrue(1330 <= y <= 1390, y)

    def test_a_button_that_is_really_absent_is_named(self):
        self.driver.adb = _WindowAdb(self.LIST[:2])
        with self.assertRaises(RuntimeError) as caught:
            self.driver.open_ubuntu_window()
        self.assertIn("Open button was not found", str(caught.exception))

    def test_a_tap_the_emulator_ate_is_named_as_the_tap(self):
        self.driver.adb = _WindowAdb(self.LIST, window=self.WINDOW, tap_ok=False)
        with self.assertRaises(RuntimeError) as caught:
            self.driver.open_ubuntu_window()
        self.assertIn("could not be tapped", str(caught.exception))

    def test_the_rows_own_name_cannot_prove_the_window_opened(self):
        # Pinned, not endorsed: the list satisfies a wait on the name the window is
        # named after - on the very screen the driver is trying to leave. The wait has
        # to be on the card's header, which is the only string the window draws and
        # the list does not; otherwise a tap the emulator ate leaves the driver
        # hunting for action buttons on a screen they are not on.
        self.assertIsNotNone(self.driver.find(self.LIST, driver.CARD_TITLE))
        self.assertIsNone(self.driver.find(self.LIST, driver.WINDOW_HEADER))

    def test_a_window_that_never_opens_fails_on_its_own_header(self):
        self.driver.adb = _WindowAdb(self.LIST, window=None)
        with self.assertRaises(RuntimeError) as caught:
            self.driver.open_ubuntu_window()
        self.assertIn(driver.WINDOW_HEADER, str(caught.exception))
        self.assertIn("did not open", str(caught.exception))


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
        # One tap, on the bar the relaunched app draws - and the launch is what the
        # tap follows: a screen with no bottom bar at all is not the app.
        self.assertEqual(1, len(self.adb.taps))
        self.assertEqual("Settings", self.adb.active)

    def test_a_settings_tab_that_is_really_gone_still_fails(self):
        self.adb.shows_settings = False
        with self.assertRaises(RuntimeError) as caught:
            self.driver.open_settings()
        self.assertIn("Settings tab did not open", str(caught.exception))
        # Relaunched once, not in a loop: a real UI regression must stay a failure.
        self.assertEqual(1, len(self.adb.starts))
        # And nothing was tapped on the screen that is not the app's.
        self.assertEqual([], self.adb.taps)


class OpenSettingsTabSwitch(unittest.TestCase):
    """The Settings tab, and the two ways its tap disappears without a trace. The bar
    is the bottom row of an edge-to-edge window, and a raised soft keyboard is drawn
    OVER it - the IME does not resize this window - so the tap is delivered to the
    keyboard's own window and the screen never changes. Run 35311046886 lost the whole
    interrupt-process phase that way: the tap at 06:01:13 was swallowed, the old
    open_settings tapped once and returned without looking, and the phase failed 100
    seconds later blaming a settings section that was never missing. The version here
    puts the keyboard away first, taps, and requires the bar's own `selected` to say
    the switch happened."""

    def setUp(self):
        self.driver = driver.E2eDriver.__new__(driver.E2eDriver)
        self.driver.package = "dev.eclipse.ssh"
        self.driver.lines = []
        self.driver.log = self.driver.lines.append
        self.adb = _LauncherThenAppAdb()
        self.adb.launched = True  # the app is already on screen
        self.driver.adb = self.adb
        self._sleep = driver.time.sleep
        driver.time.sleep = lambda *_: None

    def tearDown(self):
        driver.time.sleep = self._sleep

    def test_the_switch_is_proved_by_the_bars_own_selected_state(self):
        self.driver.open_settings()
        self.assertEqual("Settings", self.adb.active)
        self.assertEqual(1, len(self.adb.taps))
        self.assertEqual(0, self.adb.backs)

    def test_the_switch_is_proved_on_the_shape_the_device_emits(self):
        # open_settings() end to end against the dump the emulator really produces:
        # labels with their own boxes, `selected` on the unlabelled item above each.
        # The fixture used to put `selected` on the label node itself, so the suite
        # stayed green while the device failed four phases - this is that gap closed.
        self.adb.containers = True
        self.driver.open_settings()
        self.assertEqual("Settings", self.adb.active)
        self.assertEqual(1, len(self.adb.taps))
        self.assertEqual(0, self.adb.backs)

    def test_a_keyboard_over_the_bar_is_put_away_before_the_tap(self):
        self.adb.ime = True
        self.driver.open_settings()
        self.assertEqual(1, self.adb.backs)
        self.assertEqual("Settings", self.adb.active)

    def test_a_tap_the_emulator_ate_is_retried(self):
        # Not the keyboard this time: the emulator's own flakiness, which the Ubuntu
        # row's Open button already gets a second chance for.
        self.adb.swallow = 1
        self.driver.open_settings()
        self.assertEqual(2, len(self.adb.taps))
        self.assertEqual("Settings", self.adb.active)

    def test_taps_that_are_all_eaten_fail_naming_the_screen(self):
        self.adb.swallow = 99
        with self.assertRaises(RuntimeError) as caught:
            self.driver.open_settings()
        message = str(caught.exception)
        self.assertIn("Settings tab did not open", message)
        # The screen it was really on, which is what a lost tap costs to work out
        # from a screenshot: this is run 35311046886's failure mode, named where it
        # happened instead of 100 seconds later on another phase's behalf.
        self.assertIn("Search hosts, tags, or usernames", message)
        # Three taps per screen and one relaunch between the two: bounded, so a tab
        # that is genuinely unreachable fails here rather than looping.
        self.assertEqual(6, len(self.adb.taps))
        self.assertEqual(1, len(self.adb.starts))

    def test_a_retried_tap_does_not_cost_a_relaunch(self):
        self.adb.swallow = 1
        self.driver.open_settings()
        self.assertEqual(2, len(self.adb.taps))
        self.assertEqual("Settings", self.adb.active)
        self.assertEqual(0, len(self.adb.starts))

    def test_a_single_label_is_not_the_apps_bar(self):
        # The navigation carries five labels on every destination the app has. One is not
        # it, and tapping that guess is how a driver ends up in the system Settings app
        # (the launcher's own icon) instead of on its own Settings tab.
        els = [Element({"text": "Settings", "content-desc": "Settings",
                        "bounds": "[900,2272][1080,2400]"})]
        self.assertEqual(set(), self.driver._nav_label_set(els))
        self.assertIsNone(self.driver._nav_region(els))

    def test_a_wide_window_opens_settings_through_the_rail(self):
        # The app's other navigation: a landscape window draws a NavigationRail down the
        # leading edge and no bottom bar at all, so a driver that knows only the bottom
        # band finds two of the five labels there and gives up. Run 35317843457's rotation
        # disturbance rotates first and navigates second, which is how that cost the whole
        # install phase.
        self.adb.wide = True
        self.driver.open_settings()
        self.assertEqual("Settings", self.adb.active)
        self.assertEqual(1, len(self.adb.taps))
        # The tap went to the rail item, not to a point the bottom-bar rule would pick:
        # the label is clipped to a 2px sliver at the window's own bottom edge.
        self.assertEqual([(137, 1032)], self.adb.taps)


class TabActiveProof(unittest.TestCase):
    """What proves which destination is showing, and what does not. Settings has no
    top bar of its own, the word "Settings" appears exactly once on it and equally
    once on every other destination (the tab), and MainActivity builds one
    rememberScrollState() outside the destination `when` - one list offset shared by
    every tab, so even Settings' own first row is not guaranteed to be in the dump.
    The tab's `selected` semantics is the one scroll-independent answer.

    Where that semantics sits is the whole of run 35314746158: the app's bar marks the
    ITEM node selected, and the label is a node of its own inside it, so a proof that
    wants both on one node can never fire however many times the tap lands."""

    def setUp(self):
        self.driver = driver.E2eDriver.__new__(driver.E2eDriver)

    def test_the_selected_tab_is_the_active_one(self):
        els = _bar("Settings")
        self.assertTrue(self.driver._tab_active(els, "Settings"))
        self.assertFalse(self.driver._tab_active(els, "Hosts"))

    def test_the_item_node_carrying_the_state_proves_it_not_the_label(self):
        # The device's own shape, and the case four phases died on: the label node
        # carries no `selected` at all, and the state is on the unlabelled item above
        # it that contains the label's box.
        els = _bar_with_containers("Settings")
        self.assertTrue(self.driver._tab_active(els, "Settings"))
        self.assertFalse(self.driver._tab_active(els, "Hosts"))

    def test_a_container_of_the_whole_bar_is_not_a_tab(self):
        # Containment alone is not enough: the bar's own row, the Scaffold, or the
        # window root would contain every label, and a dump with nothing selected
        # would then read as every tab being active - the unsafe direction, a phase
        # walking a screen it never opened.
        els = [Element({"selected": "true", "bounds": "[0,2074][1080,2232]"})] \
            + _bar(None, selected_attr=False)
        self.assertFalse(self.driver._tab_active(els, "Settings"))

    def test_a_selected_node_above_the_bar_proves_nothing(self):
        # A Settings row can itself be `selected` - a switch's semantics - and one
        # stretched over the bar would satisfy containment from outside it, which is
        # what the band test is for.
        els = [Element({"selected": "true", "text": "Dark appearance",
                        "bounds": "[864,1500][1080,2232]"})] \
            + _bar(None, selected_attr=False)
        self.assertFalse(self.driver._tab_active(els, "Settings"))

    def test_the_word_settings_proves_nothing(self):
        # Pinned, not endorsed: this IS the screen run 35311046886's dumps show - the
        # bar drawn, the Settings tab not the selected one, the search field's own row
        # on screen. A check that matched the word would call this the Settings screen,
        # which is what the old open_settings did when it tapped and returned.
        els = _bar("Hosts")
        self.assertIsNotNone(self.driver.find(els, "Settings"))
        self.assertFalse(self.driver._tab_active(els, "Settings"))

    def test_a_dump_without_the_attribute_is_not_a_proof(self):
        # The safe direction: a dump that carries no `selected` (an unexpected format)
        # must never be read as "the switch happened" - the retry then fails the phase
        # at the tap, which is the truth, instead of letting it walk the wrong screen.
        els = _bar("Settings", selected_attr=False)
        self.assertFalse(self.driver._tab_active(els, "Settings"))

    def test_a_failed_proof_leaves_behind_what_it_read(self):
        # The digest in the failure message cannot carry this - it prints only labelled
        # nodes, and the selected node is unlabelled - so the proof logs the band itself.
        # Without it, run 35314746158's diagnosis had to be inferred from a digest that
        # had already discarded the answer.
        self.driver.lines = []
        self.driver.log = self.driver.lines.append
        self.driver._log_tab_proof(_bar_with_containers("Hosts"), "Settings")
        logged = "\n".join(self.driver.lines)
        self.assertIn("selected='true'", logged)
        self.assertIn("[864,2074][1080,2232]", logged)

    def test_the_destinations_prove_themselves_in_a_wide_window_too(self):
        # The same proof against the app's other navigation. The rail's own lowest items
        # reach down into the bottom band, so a region chosen by count has to keep the two
        # apart - and the three labels above that band are what name the container.
        els = _rail("Settings")
        self.assertEqual(("rail", 600), self.driver._nav_region(els))
        self.assertTrue(self.driver._tab_active(els, "Settings"))
        self.assertFalse(self.driver._tab_active(els, "Hosts"))

    def test_a_destination_label_in_the_bottom_band_does_not_make_it_a_bar(self):
        # Two of the five rail labels sit below the bar band's own threshold, because the
        # rail overflows the window and clips them. Counting them as bar labels would put
        # the container in the wrong place and the proof would look for the tab there.
        els = _rail("Hosts")
        below = [el for el in els if el.rect and el.rect[1] >= 864
                 and self.driver._dest_label(el)]
        self.assertEqual(["Transfers", "Settings"],
                         [self.driver._dest_label(el) for el in below])
        self.assertEqual(("rail", 600), self.driver._nav_region(els))

    def test_a_container_of_the_whole_rail_is_not_a_tab(self):
        # The rail's own column would contain every label, exactly as the bar's row does.
        els = [Element({"selected": "true", "bounds": "[0,390][240,1180]"})] \
            + _rail(None, containers=False)
        self.assertFalse(self.driver._tab_active(els, "Settings"))

    def test_a_declined_proof_leaves_behind_what_it_read_too(self):
        # The path that cost run 35317843457 its diagnosis: the container was not
        # recognised, so the proof was never attempted and nothing was written down. The
        # run's evidence had to be rebuilt from a screenshot hours later.
        self.driver.lines = []
        self.driver.log = self.driver.lines.append
        els = [Element({"text": "Settings", "content-desc": "Settings",
                        "bounds": "[900,2272][1080,2400]"})]
        self.driver.adb = _ScrollingAdb([els])
        self.assertFalse(self.driver._open_tab("Settings", attempts=1))
        # Nothing was tapped at the guess: a lone label in a corner is not a tab.
        self.assertEqual([], self.driver.adb.taps)
        logged = "\n".join(self.driver.lines)
        self.assertIn("the Settings tab is not on screen", logged)
        self.assertIn("no destination container on screen", logged)


class LinuxSectionWalkEvidence(unittest.TestCase):
    """The walk that finds the userspace row, and what its failure says. Run
    35311046886's walk spent its 24 swipes and 100 seconds on the HOSTS list and
    reported "the Linux userspace settings section was never visible" - a section that
    was never on that screen to be missing, which reads as a list that would not
    scroll. The first screen the walk was handed is the whole diagnosis."""

    HOSTS = [Element({"text": "All hosts", "bounds": "[21,477][192,540]"}),
             Element({"text": "Search hosts, tags, or usernames",
                      "bounds": "[180,308][785,371]"}),
             Element({"text": "Production edge", "bounds": "[211,650][530,713]"})] + _bar("Hosts")

    def setUp(self):
        self.driver = driver.E2eDriver.__new__(driver.E2eDriver)
        self.driver.lines = []
        self.driver.log = self.driver.lines.append
        self._sleep = driver.time.sleep
        driver.time.sleep = lambda *_: None

    def tearDown(self):
        driver.time.sleep = self._sleep

    def test_the_failure_names_the_screen_the_walk_was_on(self):
        self.driver.adb = _ScrollingAdb([self.HOSTS])
        with self.assertRaises(RuntimeError) as caught:
            self.driver.require_linux_section(max_swipes=2)
        message = str(caught.exception)
        self.assertIn("never visible", message)
        self.assertIn("Production edge", message)
        self.assertEqual(2, len(self.driver.adb.swipes))

    def test_a_section_that_is_there_is_not_a_failure(self):
        self.driver.adb = _ScrollingAdb([OpenWindowButton.LIST])
        self.driver.require_linux_section()
        self.assertEqual([], self.driver.adb.swipes)


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

    def test_the_evidence_a_navigation_failure_carries_is_not_a_stage(self):
        # A navigation failure appends the screen it was on, and that text is the app's
        # own: a host named "apt mirror" must not decide where a lost tap is published.
        message = ("the Linux userspace settings section was never visible on the Settings"
                   " list%s%s" % (driver.EVIDENCE_MARKER, ["apt mirror", "DNS server"]))
        self.assertEqual("UI", self._stage(message, RuntimeError))

    def test_without_the_marker_the_screen_would_decide_the_stage(self):
        # Pinned, not endorsed: the same digest, unmarked. This is why EVIDENCE_MARKER
        # exists - and why every message that appends a screen must use it.
        message = "the Linux userspace settings section was never visible: ['apt mirror']"
        self.assertEqual("APT", self._stage(message, RuntimeError))


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
               Element({"text": "Not installed · real bash, apt and git",
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
    run 35100526297's interruption phase (Search, Gallery, Phone, ... no app), and
    the app's own screen - bottom bar and all - once it is. The app lands on Hosts,
    which is where a relaunched MainActivity starts, and a tap on a tab moves the
    bar's `selected` to it the way the app does.

    `ime` is the soft keyboard raised over the bar: every tap is then delivered to
    the keyboard's own window and the screen never changes, which is the state run
    35311046886's dumps show. `swallow` is the same outcome without the keyboard -
    taps the emulator itself ate. `wide` is the same app in a landscape window, where
    it draws the leading rail instead of the bottom bar (run 35317843457)."""

    LAUNCHER = [Element({"text": "Phone", "content-desc": "Phone", "bounds": "[23,2106][230,2272]"}),
                Element({"text": "Camera", "content-desc": "Camera", "bounds": "[851,2106][1057,2272]"})]

    def __init__(self):
        self.starts = []
        self.taps = []
        self.backs = 0
        self.launched = False
        self.shows_settings = True
        self.active = "Hosts"
        self.ime = False
        self.swallow = 0
        self.containers = False
        self.wide = False

    def shell(self, cmd, timeout=None):
        if "input_method" not in cmd:
            return 1, ""
        return 0, "    mInputShown=%s" % ("true" if self.ime else "false")

    def ui_dump(self):
        if self.launched and self.shows_settings:
            if self.wide:
                nav = _rail(self.active)
                search = Element({"text": "Search hosts, tags, or usernames",
                                  "bounds": "[300,120][1500,180]"})
            else:
                nav = _bar_with_containers(self.active) if self.containers else _bar(self.active)
                search = Element({"text": "Search hosts, tags, or usernames",
                                  "bounds": "[180,308][785,371]"})
            return [search] + nav
        return self.LAUNCHER

    def am_start(self, package, activity):
        self.starts.append((package, activity))
        self.launched = True
        return True, "Status: ok"

    def tap(self, x, y):
        self.taps.append((x, y))
        if self.swallow:
            self.swallow -= 1
            return True
        if self.ime:
            return True
        if self.wide:
            # The rail ITEM is the tap target, not the label inside it: the window's own
            # bottom edge clips the last two labels to a sliver, which is the shape run
            # 35317843457's dump has.
            for label, (left, right, top, bottom) in RAIL_ITEM_BOUNDS.items():
                if left <= x <= right and top <= y <= bottom:
                    self.active = label
            return True
        for label, (left, right) in BAR_BOUNDS.items():
            if left <= x <= right and 2190 <= y <= 2232:
                self.active = label
        return True

    def back(self):
        self.backs += 1
        self.ime = False
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


class _WindowAdb:
    """The Settings list, and - once a tap has been sent - the window it opens.
    With `window` None the tap is eaten by the emulator and the screen never
    changes, which is the failure the window's own header exists to catch."""

    def __init__(self, settings, window=None, tap_ok=True):
        self.settings = settings
        self.window = window
        self.tap_ok = tap_ok
        self.taps = []
        self.swipes = []

    def ui_dump(self):
        if self.taps and self.window:
            return self.window
        return self.settings

    def tap(self, x, y):
        self.taps.append((x, y))
        return self.tap_ok

    def swipe(self, x1, y1, x2, y2, duration):
        self.swipes.append((x1, y1, x2, y2, duration))
        return True


class _AdvancingTime:
    """A stand-in for the time module whose clock moves per call. wait_visible()
    compares against a wall-clock deadline, so patching only sleep would leave it
    spinning for the real timeout the test is trying not to wait out."""

    STEP = 10.0

    def __init__(self):
        self.now = 0.0

    def monotonic(self):
        self.now += self.STEP
        return self.now

    def sleep(self, _seconds):
        return None


if __name__ == "__main__":
    unittest.main(verbosity=2)
