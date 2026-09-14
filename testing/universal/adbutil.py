#!/usr/bin/env python3
"""Shared adb and UI-dump plumbing for the universal black-box APK test engine.

Nothing in this module knows anything about a specific app: every fact about
the app under test comes from the APK (aapt2) or from the live device (adb).
The two things every caller needs are here so the engine files stay about
policy, not plumbing:

  - Adb: a guarded adb wrapper. Every call has a timeout, every failure is a
    return value, never an unhandled exception - a dead emulator mid-journey
    must degrade into a recorded failure, not a lost report.
  - Element / screen model: parsing `uiautomator dump` XML into elements,
    classifying them (button, text field, switch, tab, ...), and hashing a
    screen into a stable signature so "have I been here?" is answerable.

Run on the GitHub runner (python3 stdlib only, no pip).
"""
import hashlib
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET

# ---------------------------------------------------------------------------
# Policy tables (data, not code, so they are auditable at a glance)
# ---------------------------------------------------------------------------

# Runtime (dangerous) permissions as of API 23..35. Anything declared here is
# exercised through the grant/revoke cycle by the permission journey; the
# classification is a starting point - at runtime `pm grant` failing with a
# non-security error is the authoritative "not user-grantable" answer.
DANGEROUS_PERMISSIONS = {
    "android.permission.READ_CALENDAR",
    "android.permission.WRITE_CALENDAR",
    "android.permission.CAMERA",
    "android.permission.READ_CONTACTS",
    "android.permission.WRITE_CONTACTS",
    "android.permission.GET_ACCOUNTS",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.ACCESS_BACKGROUND_LOCATION",
    "android.permission.RECORD_AUDIO",
    "android.permission.READ_PHONE_STATE",
    "android.permission.READ_PHONE_NUMBERS",
    "android.permission.CALL_PHONE",
    "android.permission.ANSWER_PHONE_CALLS",
    "android.permission.READ_CALL_LOG",
    "android.permission.WRITE_CALL_LOG",
    "android.permission.ADD_VOICEMAIL",
    "android.permission.USE_SIP",
    "android.permission.PROCESS_OUTGOING_CALLS",
    "android.permission.BODY_SENSORS",
    "android.permission.BODY_SENSORS_BACKGROUND",
    "android.permission.SEND_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.READ_SMS",
    "android.permission.RECEIVE_WAP_PUSH",
    "android.permission.RECEIVE_MMS",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.ACTIVITY_RECOGNITION",
    "android.permission.BLUETOOTH_SCAN",
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.BLUETOOTH_ADVERTISE",
    "android.permission.UWB_RANGING",
    "android.permission.UWB_BACKGROUND_RANGING",
    "android.permission.POST_NOTIFICATIONS",
    "android.permission.NEARBY_WIFI_DEVICES",
    "android.permission.READ_MEDIA_IMAGES",
    "android.permission.READ_MEDIA_VIDEO",
    "android.permission.READ_MEDIA_AUDIO",
    "android.permission.READ_MEDIA_USER_SELECTED",
}

# Read-only media/health permissions were split into their own group; treat
# them as runtime-grantable too (the runtime probe decides for real).
DANGEROUS_PERMISSIONS |= {
    "android.permission.READ_HEART_RATE",
    "android.permission.READ_STEPS",
    "android.permission.READ_SLEEP",
    "android.permission.READ_EXERCISE",
    "android.permission.READ_HEALTH_DATA_IN_BACKGROUND",
}

# Words that mark an element as the entry point of a destructive, irreversible
# or monetary action. The safe-interaction engine NEVER confirms these; it
# opens the flow, verifies a confirmation UI (when one exists), then cancels.
DESTRUCTIVE_KEYWORDS = [
    "delete", "remove", "clear", "erase", "wipe", "destroy",
    "buy", "purchase", "pay", "payment", "checkout", "subscribe",
    "send", "share", "publish", "post",
    "logout", "log out", "sign out", "signout",
    "reset", "uninstall", "format", "factory",
    "revoke", "disconnect", "forget",
]

# Safe fuzz inputs for discovered text fields. Bounded by design: the longest
# entry is 2000 chars - this is correctness fuzzing, not DoS testing.
SAFE_INPUTS = [
    ("empty", ""),
    ("short", "a"),
    ("spaces", "   "),
    ("numeric", "12345"),
    ("negative", "-42"),
    ("decimal", "3.14159"),
    ("special", "!@#$%^&*()_+-=[]{};':\",./<>?"),
    ("pathlike", "../../etc/passwd"),
    ("sqlish", "'; DROP TABLE--"),
    ("unicode", "héllo wörld ñ 漢字"),
    ("emoji", "clap \U0001F44F thumbs \U0001F44D"),
    ("long", "x" * 2000),
]

# The reduced set used in STANDARD mode; DEEP/RELEASE use SAFE_INPUTS in full.
BASIC_INPUTS = [v for v in SAFE_INPUTS if v[0] in
                ("empty", "short", "numeric", "special", "unicode")]

CRASH_PATTERNS = [
    ("jvm-fatal", re.compile(r"FATAL EXCEPTION")),
    ("anr", re.compile(r"ANR in |Input dispatching timed out|executing service timed out")),
    ("native-crash", re.compile(r"Fatal signal \d+|SIGSEGV|SIGABRT|crash_dump|tombstone written")),
    ("force-close", re.compile(r"Force finishing activity|Force stopping package|has died")),
]

# A UI dump whose element list is at or under this size is treated as a blank
# screen (a real screen always carries at least a status/nav residue plus
# content; uiautomator drops non-visible chrome from the count).
BLANK_SCREEN_ELEMENT_LIMIT = 1


def find_adb():
    """Locate adb: ANDROID_HOME first, then PATH. Empty string if neither."""
    home = os.environ.get("ANDROID_HOME", "")
    if home:
        candidate = os.path.join(home, "platform-tools", "adb")
        if os.path.exists(candidate):
            return candidate
    return "adb"


class Adb:
    """Guarded adb. Every method returns data or None - never raises on device
    trouble - and every subprocess call is time-bounded."""

    def __init__(self, serial=None, adb_path=None, op_timeout=30):
        self.serial = serial
        self.adb_path = adb_path or find_adb()
        self.op_timeout = op_timeout
        self._seen_crash_lines = set()

    # -- process plumbing ---------------------------------------------------

    def _base(self):
        cmd = [self.adb_path]
        if self.serial:
            cmd += ["-s", self.serial]
        return cmd

    def run(self, args, timeout=None, check=False):
        """Run adb with args; returns (rc, stdout, stderr). Bounded always."""
        return self._run(args, timeout, check, binary=False)

    def run_bytes(self, args, timeout=None):
        """Run adb capturing raw bytes (exec-out screencap and friends).
        text-mode capture would corrupt binary streams; this never decodes."""
        return self._run(args, timeout, False, binary=True)

    def _run(self, args, timeout, check, binary):
        try:
            proc = subprocess.run(
                self._base() + args,
                capture_output=True,
                timeout=timeout or self.op_timeout,
                **({} if binary else {"text": True, "errors": "replace"}))
            if check and proc.returncode != 0:
                raise RuntimeError(
                    "adb %s failed: %s" % (args[0], proc.stderr.strip()))
            return proc.returncode, proc.stdout, proc.stderr
        except subprocess.TimeoutExpired:
            return 124, b"" if binary else "", "timeout"
        except OSError as exc:  # adb binary gone / not executable
            return 127, b"" if binary else "", str(exc)

    def shell(self, command, timeout=None):
        """Run `adb shell <command>`; returns (rc, combined stdout)."""
        rc, out, err = self.run(["shell", command], timeout=timeout)
        return rc, (out + err)

    def shell_out(self, command, timeout=None):
        rc, out = self.shell(command, timeout=timeout)
        return out.strip() if rc == 0 else ""

    # -- device state --------------------------------------------------------

    def devices(self):
        rc, out, _ = self.run(["devices"])
        if rc != 0:
            return []
        found = []
        for line in out.splitlines()[1:]:
            line = line.strip()
            if line.endswith("\tdevice"):
                found.append(line.split("\t")[0])
        return found

    def wait_for_device(self, seconds=120):
        """Bounded wait-for-device; polling instead of adb's own blocking wait
        so a wedged adb server cannot wedge the engine."""
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if self.shell_out("getprop sys.boot_completed") == "1":
                return True
            time.sleep(2)
        return False

    def getprop(self, prop):
        return self.shell_out("getprop %s" % prop)

    def api_level(self):
        try:
            return int(self.getprop("ro.build.version.sdk") or "0")
        except ValueError:
            return 0

    def device_info(self):
        return {
            "apiLevel": self.api_level(),
            "release": self.getprop("ro.build.version.release"),
            "model": self.getprop("ro.product.model"),
            "abi": self.getprop("ro.product.cpu.abi"),
        }

    # -- app state ------------------------------------------------------------

    def pid_of(self, package):
        out = self.shell_out("pidof %s" % package)
        return out.split()[0] if out.split() else None

    def current_focus(self):
        """The current focused window, e.g. 'dev.pkg/dev.pkg.MainActivity'."""
        out = self.shell_out(
            "dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp'",
            timeout=20)
        for line in out.splitlines():
            if "mCurrentFocus" in line:
                return line.split("mCurrentFocus")[-1].strip("= : \t")
        return out

    def is_foreground(self, package):
        focus = self.current_focus()
        return bool(focus) and package in focus and "u0" not in focus.split()

    def is_launcher_foreground(self):
        focus = self.current_focus() or ""
        # Launcher windows are the home shell; an app window names its package.
        return "Launcher" in focus or "home" in focus.lower()

    # -- interaction -----------------------------------------------------------

    def tap(self, x, y):
        rc, _ = self.shell("input tap %d %d" % (int(x), int(y)))
        return rc == 0

    def swipe(self, x1, y1, x2, y2, ms=300):
        rc, _ = self.shell(
            "input swipe %d %d %d %d %d" % (x1, y1, x2, y2, ms))
        return rc == 0

    def input_text(self, text):
        """Type text after a field is focused. adb shell input mangles quotes,
        spaces and non-ASCII; the only robust channel for arbitrary content is
        `adb shell input text` with minimal escaping for what it accepts, and
        the UTF-8 route via `am broadcast` is not universal. Keep values and
        escaping conservative and record what was actually typed."""
        # input text takes tokens; escape spaces, and strip characters the
        # shell layer would eat. Long values are chunked below the arg limit.
        escaped = (text.replace("\\", "\\\\").replace(" ", "%s")
                   .replace('"', '\\"').replace("'", "\\'")
                   .replace("&", "\\&").replace("<", "\\<").replace(">", "\\>"))
        ok = True
        # 1000 chars per shot keeps us far under the shell arg length limit.
        for i in range(0, len(escaped), 1000):
            chunk = escaped[i:i + 1000]
            rc, _ = self.shell("input text \"%s\"" % chunk)
            ok = ok and rc == 0
        return ok

    def keyevent(self, code):
        rc, _ = self.shell("input keyevent %s" % code)
        return rc == 0

    def back(self):
        return self.keyevent("KEYCODE_BACK")

    def home(self):
        return self.keyevent("KEYCODE_HOME")

    def screencap(self, local_path):
        """Capture the screen as PNG into local_path. exec-out carries raw
        bytes; the shell/pull fallback covers images where exec-out glitches."""
        rc, out, _ = self.run_bytes(["exec-out", "screencap", "-p"],
                                    timeout=60)
        if rc == 0 and out.startswith(b"\x89PNG"):
            with open(local_path, "wb") as fh:
                fh.write(out)
            return True
        rc2, _, _ = self.run(["shell", "screencap", "-p", "/sdcard/uni-cap.png"])
        if rc2 == 0:
            rc3, _, _ = self.run_bytes(
                ["pull", "/sdcard/uni-cap.png", local_path], timeout=60)
            return rc3 == 0
        return False

    # -- app management ----------------------------------------------------------

    def am_start(self, package, activity, wait=True, timeout=60):
        """Start an activity by component; returns (ok, raw output)."""
        component = "%s/%s" % (package, activity)
        flag = "-W" if wait else ""
        rc, out = self.shell("am start %s -n %s" % (flag, component),
                             timeout=timeout)
        ok = rc == 0 and "Status: ok" in out and "Error" not in out
        return ok, out.strip()

    def force_stop(self, package):
        rc, _ = self.shell("am force-stop %s" % package, timeout=30)
        return rc == 0

    def am_kill(self, package):
        """Kill the background process the way the OS would under memory
        pressure - the process keeps its saved state and restarts on relaunch."""
        rc, _ = self.shell("am kill %s" % package, timeout=30)
        return rc == 0

    def pm_grant(self, package, permission):
        rc, out = self.shell("pm grant %s %s" % (package, permission))
        return rc == 0 and "Error" not in out, out.strip()

    def pm_revoke(self, package, permission):
        rc, out = self.shell("pm revoke %s %s" % (package, permission))
        return rc == 0 and "Error" not in out, out.strip()

    def pm_perm_grantable(self, package, permission):
        """A runtime permission is grantable when pm grant succeeds; a
        non-runtime permission (or an undeclared one) is refused."""
        ok, out = self.pm_grant(package, permission)
        if ok:
            return True
        return "not a runtime permission" not in out and \
            "Unknown permission" not in out and "has not requested" not in out

    def set_rotation(self, degrees):
        """Pin the rotation (0=portrait, 1=landscape, 2, 3) by disabling the
        accelerometer first, exactly like a user toggling auto-rotate off."""
        self.shell("settings put system accelerometer_rotation 0")
        rc, _ = self.shell("settings put system user_rotation %d" % degrees)
        return rc == 0

    def set_auto_rotate(self, on):
        rc, _ = self.shell(
            "settings put system accelerometer_rotation %d" % (1 if on else 0))
        return rc == 0

    def airplane_mode(self, on):
        verb = "enable" if on else "disable"
        rc, out = self.shell("cmd connectivity airplane-mode %s" % verb)
        ok = rc == 0
        if not ok:
            # Older images only react to the broadcast after the setting flip.
            rc2, _ = self.shell(
                "settings put global airplane_mode_on %d" % (1 if on else 0))
            rc3, _ = self.shell(
                "am broadcast -a android.intent.action.AIRPLANE_MODE "
                "--ez state %s" % ("true" if on else "false"))
            ok = rc2 == 0 and rc3 == 0
        return ok

    # -- UI dump -----------------------------------------------------------------

    def ui_dump(self, retries=3, settle=0.5):
        """Dump the current window hierarchy via uiautomator and parse it.
        Returns a list of Element objects. None means the dump itself failed
        (device or uiautomator trouble - distinct from [] which means the
        hierarchy is legitimately empty, i.e. a blank screen)."""
        for attempt in range(retries):
            rc, out = self.shell(
                "uiautomator dump /sdcard/uni-dump.xml", timeout=40)
            if rc == 0 and "dumped to" in out:
                _, xml = self.shell("cat /sdcard/uni-dump.xml", timeout=20)
                elements = parse_ui_xml(xml)
                if elements is not None:
                    return elements
            # "could not get idle state" means animations are still running;
            # a short bounded settle and a retry is the documented remedy.
            time.sleep(settle + attempt)
        return None

    # -- logcat ------------------------------------------------------------------

    def logcat(self, lines=2000):
        rc, out, _ = self.run(
            ["logcat", "-d", "-v", "brief", "-t", str(lines)], timeout=30)
        return out if rc == 0 else ""

    def new_crash_lines(self):
        """Scan the tail of logcat for crash/ANR signatures not yet reported.
        Deduplication is by full line so a scan after every action does not
        re-report the same stack on each step. Note the scan is not filtered
        by package: a crash is attributed by the caller's context, and a
        system_server fatal that takes the app down is still a finding."""
        fresh = []
        for line in self.logcat().splitlines():
            for label, pattern in CRASH_PATTERNS:
                if pattern.search(line):
                    # A crash line is "ours" if it names the package, came from
                    # AndroidRuntime/system_server context, or is a bare fatal
                    # header - the surrounding lines are attached as evidence.
                    key = (label, line.strip())
                    if key not in self._seen_crash_lines:
                        self._seen_crash_lines.add(key)
                        fresh.append({"kind": label, "line": line.strip()})
                    break
        if fresh:
            # Pull the stacks for context: the 60 lines after each FATAL.
            full = self.logcat(6000).splitlines()
            for crash in fresh:
                if crash["kind"] in ("jvm-fatal", "native-crash"):
                    for i, line in enumerate(full):
                        if line.strip() == crash["line"]:
                            crash["stack"] = "\n".join(
                                full[i:i + 60])[:4000]
                            break
        return fresh


# ---------------------------------------------------------------------------
# UI dump parsing and the screen model
# ---------------------------------------------------------------------------

_BOUNDS_RE = re.compile(r"^\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]$")


def parse_bounds(text):
    """'[x1,y1][x2,y2]' -> (center_x, center_y, width, height) or None."""
    m = _BOUNDS_RE.match(text or "")
    if not m:
        return None
    x1, y1, x2, y2 = (int(v) for v in m.groups())
    if x2 <= x1 or y2 <= y1:
        return None
    return ((x1 + x2) // 2, (y1 + y2) // 2, x2 - x1, y2 - y1)


class Element:
    """One interactive node from a uiautomator dump."""

    def __init__(self, attrs):
        self.attrs = attrs

    def __getattr__(self, name):
        # resource-id etc. carry dashes; attribute-style access is sugar.
        try:
            return self.attrs[name]
        except KeyError:
            raise AttributeError(name)

    @property
    def bounds(self):
        return parse_bounds(self.attrs.get("bounds", ""))

    @property
    def center(self):
        b = self.bounds
        return (b[0], b[1]) if b else (None, None)

    @property
    def descriptor(self):
        """The stable identity used in screen signatures - deliberately
        bounds-free so a rotation (same nodes, new geometry) is the same
        screen, and text is truncated so ticking clocks do not fork screens."""
        return "|".join([
            self.attrs.get("class", ""),
            self.attrs.get("resource-id", ""),
            self.attrs.get("content-desc", "")[:60],
            self.attrs.get("text", "")[:60],
        ])

    def to_dict(self):
        return dict(self.attrs)


def parse_ui_xml(xml_text):
    """Parse a uiautomator XML dump into Elements. Returns None when the text
    is not parseable XML (device trouble), and [] for a legitimately empty
    hierarchy (blank screen)."""
    if not xml_text or "<hierarchy" not in xml_text:
        return None
    # Some images emit leading junk before <?xml; find the real start.
    start = xml_text.find("<?xml")
    if start < 0:
        start = xml_text.find("<hierarchy")
        if start < 0:
            return None
    try:
        root = ET.fromstring(xml_text[start:])
    except ET.ParseError:
        return None
    elements = []
    for node in root.iter("node"):
        attrs = dict(node.attrib)
        # Drop 'not available' markers some images emit for missing attrs.
        for key, value in list(attrs.items()):
            if value == "[NULL]" or value == "null":
                attrs[key] = ""
        elements.append(Element(attrs))
    return elements


def is_interactive(element):
    """Whether an element can be acted on at all."""
    return element.attrs.get("clickable") == "true" or \
        element.attrs.get("scrollable") == "true" or \
        element.attrs.get("checkable") == "true" or \
        element.attrs.get("long-clickable") == "true"


def classify_element(element):
    """Map an element onto the engine's interaction vocabulary. Order matters:
    the first true label wins, and the labels drive which journey cares."""
    cls = element.attrs.get("class", "")
    desc = (element.attrs.get("content-desc", "") or "").lower()
    text = (element.attrs.get("text", "") or "").lower()
    rid = element.attrs.get("resource-id", "") or ""
    if "WebView" in cls:
        return "webview"
    if "EditText" in cls or element.attrs.get("editable") == "true":
        return "text-field"
    if "Switch" in cls or "CheckBox" in cls or "ToggleButton" in cls or \
            element.attrs.get("checkable") == "true":
        return "switch"
    if "RadioButton" in cls:
        return "radio"
    if element.attrs.get("scrollable") == "true":
        return "scrollable"
    if "Spinner" in cls or "DatePicker" in cls or "TimePicker" in cls:
        return "picker"
    # Tabs and menus are named by content-desc far more often than by class.
    if "tab" in desc or "tab" in rid.lower() or "tab" in text:
        return "tab"
    if "menu" in desc or "menu" in rid.lower() or "more options" in desc:
        return "menu"
    if "ImageButton" in cls or "ImageView" in cls and \
            element.attrs.get("clickable") == "true":
        return "image-button"
    if "Button" in cls:
        return "button"
    if "TextView" in cls and element.attrs.get("clickable") == "true":
        return "link"
    if element.attrs.get("clickable") == "true":
        return "clickable"
    return "static"


def element_label(element):
    """The human-readable name of an element for traces and reports."""
    return (element.attrs.get("text") or element.attrs.get("content-desc")
            or element.attrs.get("resource-id") or
            element.attrs.get("class", "?")).strip()[:60]


def is_destructive(element):
    """True when any visible label of the element carries a destructive
    keyword. Used BEFORE any tap; matches are routed through the
    cancel-and-verify path, never a confirm."""
    haystacks = [
        element.attrs.get("text", ""),
        element.attrs.get("content-desc", ""),
        element.attrs.get("resource-id", ""),
    ]
    for hay in haystacks:
        hay = (hay or "").lower()
        for keyword in DESTRUCTIVE_KEYWORDS:
            # Word-ish matching: 'send' must not match 'resend'... but
            # 'resend' IS destructive; substring match is the safer side.
            if keyword in hay:
                return True
    return False


def screen_signature(elements):
    """Stable hash of a screen: sorted element descriptors. Two dumps of the
    same screen differ only in transient text/geometry noise we already
    excluded, so the signature is visit-stable."""
    descriptors = sorted(e.descriptor for e in elements)
    joined = "\n".join(descriptors)
    return hashlib.sha1(joined.encode("utf-8", "replace")).hexdigest()[:12]


def screen_summary(elements):
    """Count elements per class label - the 'what is on this screen' digest."""
    counts = {}
    for element in elements:
        kind = classify_element(element)
        counts[kind] = counts.get(kind, 0) + 1
    return counts


def is_blank_screen(elements):
    """A dump that parsed but carries (almost) nothing is a blank screen -
    the strongest black-box signal of a broken render pass."""
    interactive_or_text = [e for e in elements
                           if is_interactive(e) or e.attrs.get("text")]
    return len(interactive_or_text) <= BLANK_SCREEN_ELEMENT_LIMIT


def visible_elements(elements, app_package):
    """Elements belonging to the app's own windows. System chrome (status
    bar, IME overlays) is kept but flagged by the caller, not dropped here:
    a dialog's package may legitimately differ mid-flow."""
    if not app_package:
        return elements
    own = [e for e in elements
           if e.attrs.get("package", app_package) == app_package]
    # If literally nothing is ours the dump caught a system window (IME,
    # permission dialog); return everything so the engine can still see it.
    return own if own else elements
