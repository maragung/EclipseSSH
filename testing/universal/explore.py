#!/usr/bin/env python3
"""The universal black-box exploration engine.

Drives ANY installed Android app over adb - no source, no instrumentation,
no app-specific knowledge - through a set of journeys gated by mode:

  SMOKE     cold start, first-screen discovery, one rotation, one
            background/foreground cycle, crash scan                 (~5 min)
  STANDARD  + navigation discovery, back-stack sanity, dialogs,
            basic form inputs, permission grant/revoke cycle        (~15 min)
  DEEP      + input fuzzing, lifecycle chaos (rotation matrix, process
            death, force-stop, keyboard), randomized exploration,
            state persistence                                        (~30 min)
  RELEASE   + network failure suite (airplane mode)                  (~45 min)

Every action is observed (crash? new screen? same screen? blank?), every
wait is bounded, and randomization is seeded and recorded so a failure is
replayable exactly. Safe-interaction rules are enforced centrally: elements
whose labels carry destructive keywords are never confirmed - the engine
opens the flow, verifies a confirmation UI when one exists, cancels, and
checks the app survived.

Determinism note: given the same seed, same mode and same app screens, the
action sequence is the same. uiautomator dump order is stable per screen,
and candidate ordering is by (kind priority, descriptor), never by wall
clock.

Usage:
  explore.py --model application-model.json --out artifacts --mode DEEP \
      [--seed 20260914] [--serial X] [--max-actions N] [--max-minutes N]

Writes: traces/action-trace.json, traces/journeys.json,
        crashes/crash-events.json, screenshots/*.png
Exit code: 0 when every journey passed and no crash was observed.
"""
import argparse
import json
import os
import random
import sys
import time

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from adbutil import (Adb, BASIC_INPUTS, SAFE_INPUTS, classify_element,  # noqa: E402
                     element_label, is_blank_screen, is_destructive,
                     is_interactive, screen_signature, screen_summary,
                     visible_elements)

MODE_RANK = {"SMOKE": 1, "STANDARD": 2, "DEEP": 3, "RELEASE": 4}
MODE_BUDGETS = {
    "SMOKE": {"minutes": 5, "actions": 40},
    "STANDARD": {"minutes": 15, "actions": 150},
    "DEEP": {"minutes": 30, "actions": 400},
    "RELEASE": {"minutes": 45, "actions": 600},
}
# Candidate priority for the navigation walk: structure first (tabs reveal
# sibling screens), then explicit buttons, then text links, then bare
# clickables. Same priority order = same walk for the same screen.
KIND_PRIORITY = [
    "tab", "menu", "button", "link", "image-button", "picker",
    "switch", "radio", "clickable", "scrollable", "text-field", "webview",
]
CANCEL_WORDS = ("cancel", "no", "dismiss", "keep", "not now", "maybe later",
                "close", "back", "abort", "stop", "discard")
SCREENSHOT_CAP = 80  # evidence budget; a release leg does not need 500 PNGs


class Engine:
    def __init__(self, model, out_dir, mode, seed, serial="",
                 max_actions=None, max_minutes=None):
        self.model = model
        self.pkg = model.get("package", "")
        self.activity = model.get("launchableActivity", "")
        if not self.pkg:
            raise SystemExit("the model has no package - discovery failed")
        self.out = out_dir
        self.mode = mode
        self.rank = MODE_RANK[mode]
        self.adb = Adb(serial=serial)
        self.rng = random.Random(seed)
        self.seed = seed
        budget = MODE_BUDGETS[mode]
        self.max_actions = max_actions or budget["actions"]
        self.deadline = time.monotonic() + \
            60 * (max_minutes or budget["minutes"])

        self.traces_dir = os.path.join(out_dir, "traces")
        self.shots_dir = os.path.join(out_dir, "screenshots")
        self.crashes_dir = os.path.join(out_dir, "crashes")
        for path in (self.traces_dir, self.shots_dir, self.crashes_dir):
            os.makedirs(path, exist_ok=True)

        self.n = 0                       # action counter
        self.actions = []                # action-trace.json body
        self.journeys = []               # per-journey results
        self.crashes = []                # observed crash/ANR events
        self.visited = set()             # screen signatures seen
        self.screens_discovered = 1
        self.any_failure = False
        self._shots = 0
        self._last_obs = None            # last observation, reused as state

    # -- budget -----------------------------------------------------------

    def budget_left(self):
        return self.n < self.max_actions and time.monotonic() < self.deadline

    def time_left(self):
        return self.deadline - time.monotonic()

    # -- recording ----------------------------------------------------------

    def act(self, action, target=None, ok=True, screen=None, ms=0):
        self.n += 1
        entry = {
            "n": self.n,
            "action": action,
            "target": target,
            "screen": screen,
            "ok": ok,
            "ms": int(ms),
        }
        self.actions.append(entry)
        return entry

    def shoot(self, label):
        """Screenshot with an action-anchored name; capped so a deep run
        cannot balloon the artifact beyond usefulness."""
        if self._shots >= SCREENSHOT_CAP:
            return ""
        self._shots += 1
        name = "%04d-%s.png" % (self.n, label)
        path = os.path.join(self.shots_dir, name)
        ok = self.adb.screencap(path)
        return os.path.relpath(path, self.out) if ok else ""

    def observe(self, label, expect_kill=False, shot=False):
        """The single observation primitive: dump, signature, blank check,
        crash scan, foreground check. expect_kill suppresses the force-close
        signature class for journeys that intentionally stop the process
        (force-stop, process death) - a JVM fatal or ANR is never expected."""
        obs = {"label": label, "ts": round(time.time())}
        elements = self.adb.ui_dump()
        obs["dumpFailed"] = elements is None
        if elements is None:
            elements = []
        obs["elements"] = elements
        obs["signature"] = screen_signature(elements)
        obs["blank"] = is_blank_screen(elements)
        obs["summary"] = screen_summary(elements)
        crash = self.adb.new_crash_lines(package=self.pkg)
        if expect_kill:
            crash = [c for c in crash if c["kind"] != "force-close"]
        if crash:
            shot_path = self.shoot(label + "-crash")
            for c in crash:
                c["screenshot"] = shot_path
                c["afterAction"] = self.n
                c["screen"] = obs["signature"]
                c["label"] = label
            self.crashes.extend(crash)
            self.crashes.sort(key=lambda c: c["afterAction"])
            self._flush_crashes()
        obs["crashes"] = crash
        obs["foreground"] = self.adb.is_foreground(self.pkg)
        obs["pid"] = self.adb.pid_of(self.pkg)
        if shot or obs["signature"] not in self.visited:
            self.visited.add(obs["signature"])
            self.screens_discovered = len(self.visited)
            obs["screenshot"] = self.shoot(label)
        self._last_obs = obs
        return obs

    def _flush_crashes(self):
        with open(os.path.join(self.crashes_dir, "crash-events.json"),
                  "w") as fh:
            json.dump(self.crashes, fh, indent=2)
            fh.write("\n")

    # -- app control -----------------------------------------------------------

    def launch(self, cold=False, timeout=60):
        """Start the launchable activity. A cold launch force-stops first so
        the journey measures real startup, not a warm-up."""
        if cold:
            self.adb.force_stop(self.pkg)
            time.sleep(1)
        ok, out = self.adb.am_start(self.pkg, self.activity, timeout=timeout)
        self.act("launch" + ("-cold" if cold else ""),
                 target={"component": "%s/%s" % (self.pkg, self.activity)},
                 ok=ok, ms=0)
        return ok, out

    def ensure_app(self):
        """Recover to a running, foreground app after an exit or a kill.
        Best-effort by design: if this fails, the next observe() records the
        truth and the journey fails on evidence."""
        if self.adb.pid_of(self.pkg) and self.adb.is_foreground(self.pkg):
            return True
        ok, _ = self.launch()
        time.sleep(2)
        return ok and self.adb.is_foreground(self.pkg)

    # -- interaction helpers ------------------------------------------------

    def tap_element(self, element, action_label="tap"):
        (x, y) = element.center
        if x is None:
            self.act(action_label, target=element_label(element), ok=False)
            return False
        start = time.monotonic()
        ok = self.adb.tap(x, y)
        time.sleep(0.8)  # bounded settle for the click ripple/animation
        self.act(action_label, target={
            "label": element_label(element),
            "kind": classify_element(element),
            "resourceId": element.attrs.get("resource-id", ""),
            "at": [x, y],
        }, ok=ok, ms=(time.monotonic() - start) * 1000,
            screen=self._last_obs["signature"] if self._last_obs else None)
        return ok

    def find_elements(self, obs, kinds=None, interactive_only=True,
                      app_only=True):
        elements = obs.get("elements", [])
        if app_only:
            elements = visible_elements(elements, self.pkg)
        if kinds:
            elements = [e for e in elements
                        if classify_element(e) in kinds]
        elif interactive_only:
            elements = [e for e in elements if is_interactive(e)]
        # Deterministic order: priority by kind, then stable descriptor.
        elements.sort(key=lambda e: (
            KIND_PRIORITY.index(classify_element(e))
            if classify_element(e) in KIND_PRIORITY else 99,
            e.descriptor))
        return elements

    def find_cancel_button(self, obs):
        """A button that declines a dialog; matched on its label only - the
        engine never taps a positive/confirm button inside a destructive
        flow."""
        for e in self.find_elements(obs, kinds=["button", "link", "clickable",
                                                "image-button"]):
            label = element_label(e).lower()
            if any(w in label for w in CANCEL_WORDS):
                return e
        return None

    def has_dialog(self, obs):
        """A dialog is a small bounds-cluster of new elements, typically from
        a different window; class names are the cheap reliable signal."""
        for e in obs.get("elements", []):
            cls = e.attrs.get("class", "")
            if any(k in cls for k in ("Dialog", "Alert", "PopupWindow",
                                      "BottomSheetDialog")):
                return True
        return False

    def fatal_in(self, obs):
        return [c for c in obs.get("crashes", [])
                if c["kind"] in ("jvm-fatal", "native-crash", "anr")]

    # ------------------------------------------------------------------
    # Journeys. Each returns (status, detail) where status is one of
    # pass / fail / skip / warning. A fail fails the run.
    # ------------------------------------------------------------------

    def j_cold_start(self):
        ok, out = self.launch(cold=True)
        time.sleep(3)
        obs = self.observe("cold-start", shot=True)
        if not ok:
            return "fail", "launch did not report ok: %s" % out[-300:]
        if self.fatal_in(obs):
            return "fail", "startup crash (see crash-events.json)"
        if obs["blank"]:
            return "fail", "first screen is blank after launch"
        if not obs["foreground"]:
            return "fail", "app is not in the foreground after launch"
        return "pass", "cold start ok, first screen has %d elements" \
            % len(obs["elements"])

    def j_first_screen(self):
        obs = self.observe("first-screen", shot=True)
        if obs["dumpFailed"]:
            return "fail", "uiautomator could not dump the first screen"
        if obs["blank"]:
            return "fail", "blank first screen"
        interactive = [e for e in obs["elements"] if is_interactive(e)]
        return "pass", {
            "signature": obs["signature"],
            "elements": len(obs["elements"]),
            "interactive": len(interactive),
            "summary": obs["summary"],
        }

    def j_rotation(self):
        self.adb.set_rotation(1)
        time.sleep(1.5)
        obs = self.observe("rotation-landscape", shot=True)
        if obs["dumpFailed"]:
            return "fail", "uiautomator could not dump in landscape"
        ok_land = not self.fatal_in(obs) and not obs["blank"] and \
            self.adb.pid_of(self.pkg)
        self.adb.set_rotation(0)
        time.sleep(1.5)
        obs2 = self.observe("rotation-portrait")
        ok_port = not self.fatal_in(obs2) and not obs2["blank"]
        if not ok_land:
            return "fail", "app broke in landscape (crash or blank screen)"
        if not ok_port:
            return "fail", "app broke after rotating back to portrait"
        return "pass", "rotation survived in both orientations"

    def j_background_foreground(self):
        self.adb.home()
        time.sleep(1.5)
        behind = not self.adb.is_foreground(self.pkg)
        # Warm start: the process is alive, only the activity is brought back.
        ok, _ = self.launch()
        time.sleep(2)
        obs = self.observe("warm-start", shot=True)
        if not behind:
            return "warning", "HOME did not move the app to the background"
        if not ok or not obs["foreground"]:
            return "fail", "app did not return to the foreground"
        if self.fatal_in(obs):
            return "fail", "crash on background/foreground cycle"
        if obs["blank"]:
            return "fail", "blank screen after returning from background"
        return "pass", "background/foreground cycle ok (warm start)"

    def j_navigation(self):
        """Walk the app's screens by tapping discovered, non-destructive
        elements. Bounded by screens and budget; the visited-signature set
        is what 'discovered' means here."""
        max_screens = 6 if self.rank <= 2 else 12
        tried = {}   # signature -> set of descriptors already attempted
        destructive_probes = 0
        screens = 1
        self.ensure_app()
        while self.budget_left() and screens < max_screens:
            obs = self.observe("nav-step")
            sig = obs["signature"]
            if self.fatal_in(obs):
                return "fail", "crash while navigating (after action %d)" \
                    % self.n
            if obs["blank"] and not obs["dumpFailed"]:
                return "fail", "blank screen after action %d" % self.n
            if not obs["foreground"]:
                # We left the app (a link opened the browser, or back
                # exited). Recover and count the screen as done.
                self.act("recover-relaunch")
                self.ensure_app()
                continue
            tried.setdefault(sig, set())
            candidates = [e for e in self.find_elements(obs)
                          if e.descriptor not in tried[sig]]
            if not candidates:
                # Everything on this screen was tried: step back, bounded.
                self.adb.back()
                time.sleep(0.6)
                self.act("back", screen=sig)
                if not self.adb.is_foreground(self.pkg):
                    self.ensure_app()
                continue
            target = candidates[0]
            tried[sig].add(target.descriptor)
            if is_destructive(target):
                if destructive_probes < 2:
                    destructive_probes += 1
                    status, detail = self.destructive_probe(target, sig)
                    if status == "fail":
                        return "fail", detail
                else:
                    # Budget for destructive-flow probes is spent; do not
                    # tap, just record that one more was seen and declined.
                    self.act("decline-destructive", target={
                        "label": element_label(target)}, ok=True, screen=sig)
                continue
            if classify_element(target) == "text-field":
                # Text fields are exercised by the form/fuzz journeys, not
                # by navigation; typing while walking distorts the walk.
                tried[sig].add(target.descriptor)
                continue
            self.tap_element(target, action_label="nav-tap")
            time.sleep(1.2)
            new_obs = self.observe("nav-after-tap")
            if new_obs["signature"] != sig and \
                    new_obs["signature"] not in self.visited:
                screens = len(self.visited)
            if self.has_dialog(new_obs):
                # A dialog blocks the walk; dismiss with back (never a
                # positive button) and keep going.
                self.adb.back()
                time.sleep(0.6)
                self.act("dialog-dismiss-back", screen=new_obs["signature"])
        return "pass", {
            "screensVisited": len(self.visited),
            "actionsUsed": self.n,
            "destructiveProbes": destructive_probes,
        }

    def destructive_probe(self, element, sig_before):
        """The safe-interaction core: open a destructive flow, verify a
        confirmation UI (when the app offers one), CANCEL it, verify the app
        survived. The destructive action itself is never performed."""
        self.act("destructive-open", target={
            "label": element_label(element),
        }, screen=sig_before)
        self.tap_element(element, action_label="destructive-open-tap")
        time.sleep(1.5)
        obs = self.observe("destructive-flow", shot=True)
        if self.fatal_in(obs):
            return "fail", "crash while opening a destructive flow (%s)" \
                % element_label(element)
        confirmation = self.has_dialog(obs) or self.find_cancel_button(obs)
        cancel = self.find_cancel_button(obs)
        if cancel is not None:
            self.act("destructive-cancel-tap", target={
                "label": element_label(cancel)}, screen=obs["signature"])
            self.tap_element(cancel, action_label="destructive-cancel-tap")
        else:
            self.adb.back()
            self.act("destructive-cancel-back", screen=obs["signature"])
        time.sleep(1.2)
        after = self.observe("destructive-after-cancel")
        if self.fatal_in(after):
            return "fail", "crash after cancelling a destructive flow"
        if not (after["foreground"] or self.adb.pid_of(self.pkg)):
            return "fail", "app died after cancelling %s" \
                % element_label(element)
        state_intact = after["signature"] == sig_before or \
            after["foreground"]
        detail = {
            "flow": element_label(element),
            "confirmationUiSeen": bool(confirmation),
            "stateIntact": bool(state_intact),
            "cancelledVia": "button" if cancel is not None else "back",
        }
        if confirmation and not state_intact:
            return "warning", detail
        return "pass", detail

    def j_back_stack(self):
        """Back-stack sanity: returning to the top-level screen, one back
        must not crash or blank; repeated back must land somewhere clean
        (in-app or the launcher), never in a wedged state."""
        self.ensure_app()
        # Bounded walk back toward the first screen of the session.
        for _ in range(8):
            obs = self.observe("backstack-check")
            if obs["signature"] in self.visited and not obs["blank"] and \
                    obs["foreground"]:
                break
            self.adb.back()
            time.sleep(0.6)
            self.act("back")
        obs = self.observe("backstack-top", shot=True)
        if not obs["foreground"]:
            self.ensure_app()
            obs = self.observe("backstack-top")
        self.adb.back()
        time.sleep(1.2)
        self.act("back-first")
        obs2 = self.observe("after-first-back")
        exited = not obs2["foreground"]
        if self.fatal_in(obs2):
            return "fail", "crash on back from the top-level screen"
        if obs2["blank"] and not obs2["dumpFailed"]:
            return "fail", "blank screen after back from the top-level screen"
        # Rapid backs: 4 in quick succession, then the app must be somewhere
        # sane (in-app, or the launcher with no crash/blank residue).
        for _ in range(4):
            self.adb.back()
            time.sleep(0.3)
        self.act("back-rapid")
        obs3 = self.observe("after-rapid-back")
        if self.fatal_in(obs3):
            return "fail", "crash during rapid back navigation"
        if obs3["blank"] and not obs3["dumpFailed"] and obs3["foreground"]:
            return "fail", "blank in-app screen after rapid back navigation"
        self.ensure_app()
        return "pass", {
            "exitedToLauncherOnFirstBack": bool(exited),
            "note": "clean exit on back is recorded, not failed - only "
                    "crashes and blank screens fail the journey",
        }

    def j_form_inputs(self):
        return self._input_journey("form", BASIC_INPUTS, max_fields=3)

    def j_input_fuzz(self):
        return self._input_journey("fuzz", SAFE_INPUTS, max_fields=2)

    def _input_journey(self, name, values, max_fields):
        """Type safe values into discovered text fields and observe. Values
        are bounded (max 2000 chars) - this is correctness fuzzing, not a
        resource-exhaustion exercise."""
        self.ensure_app()
        fields = []
        # Walk a couple of screens to find fields; keep it bounded.
        for _ in range(3):
            obs = self.observe("%s-locate" % name)
            fields = self.find_elements(obs, kinds=["text-field"],
                                        interactive_only=False)
            if fields:
                break
            self.adb.back()
            time.sleep(0.6)
            self.act("back", screen=obs["signature"])
            if not self.adb.is_foreground(self.pkg):
                self.ensure_app()
        if not fields:
            return "skip", "no text fields discovered"
        fields = fields[:max_fields]
        tested = 0
        for field in fields:
            for label, value in values:
                if not self.budget_left():
                    return "pass", {"fieldsTested": tested,
                                    "stoppedEarly": "budget"}
                self.tap_element(field, action_label="%s-focus" % name)
                time.sleep(0.5)
                start = time.monotonic()
                typed = self.adb.input_text(value)
                self.act("%s-input" % name, target={
                    "label": element_label(field),
                    "valueKind": label,
                    "length": len(value),
                }, ok=typed, ms=(time.monotonic() - start) * 1000)
                time.sleep(0.6)
                # Leave the field: close the IME so the next dump is stable.
                self.adb.back()
                time.sleep(0.5)
                self.act("%s-close-ime" % name)
                obs = self.observe("%s-after-%s" % (name, label))
                tested += 1
                if self.fatal_in(obs):
                    return "fail", "crash typing a %s value (%d chars) " \
                        "into '%s'" % (label, len(value),
                                       element_label(field))
                if obs["blank"] and not obs["dumpFailed"]:
                    return "fail", "blank screen after %s input (%s)" \
                        % (name, label)
        return "pass", {"fieldsTested": tested, "valueKinds":
                        [v[0] for v in values]}

    def j_permission_cycle(self):
        """For every runtime permission the model says the app declares:
        revoke -> relaunch -> no crash; grant -> relaunch -> no crash. pm
        itself is the authority on grantability - unknown permissions are
        skipped, not failed."""
        perms = self.model.get("runtimePermissions", [])[:6]
        if not perms:
            return "skip", "no runtime permissions declared"
        results = []
        for perm in perms:
            if not self.budget_left():
                results.append({"permission": perm, "skipped": "budget"})
                continue
            grantable = self.adb.pm_perm_grantable(self.pkg, perm)
            if not grantable:
                # pm refused and told us why: not a runtime permission on
                # this image, or the app never declared it. Record and move
                # on - guessing here would fail every pre-T targetSdk app.
                results.append({"permission": perm,
                                "grantable": False})
                continue
            self.adb.pm_revoke(self.pkg, perm)
            self.act("permission-revoke", target={"permission": perm})
            ok, _ = self.launch(cold=True)
            time.sleep(2)
            obs_revoked = self.observe("permission-revoked", shot=True)
            self.adb.pm_grant(self.pkg, perm)
            self.act("permission-grant", target={"permission": perm})
            ok2, _ = self.launch(cold=True)
            time.sleep(2)
            obs_granted = self.observe("permission-granted")
            entry = {
                "permission": perm,
                "survivedRevoked": not self.fatal_in(obs_revoked),
                "survivedGranted": not self.fatal_in(obs_granted),
            }
            results.append(entry)
            if not entry["survivedRevoked"]:
                self.adb.pm_grant(self.pkg, perm)  # leave state no worse
                return "fail", "crash with %s revoked" % perm
            if not entry["survivedGranted"]:
                return "fail", "crash with %s granted" % perm
        return "pass", results

    def j_lifecycle_chaos(self):
        """The mandatory chaos matrix. Each event is followed by the same
        verdict: app alive, not blank, no crash."""
        events = []
        # Cold start.
        ok, _ = self.launch(cold=True)
        time.sleep(3)
        obs = self.observe("chaos-cold-start", shot=True)
        if self.fatal_in(obs) or obs["blank"] or not obs["foreground"]:
            return "fail", "cold start failed inside the chaos matrix"
        events.append("cold-start")

        # Warm start (HOME then relaunch, process alive throughout).
        self.adb.home()
        time.sleep(1)
        ok, _ = self.launch()
        time.sleep(2)
        obs = self.observe("chaos-warm-start")
        if self.fatal_in(obs) or not obs["foreground"]:
            return "fail", "warm start failed"
        events.append("warm-start")

        # Rotation matrix 0/1/2/3 with auto-rotate pinned off, then restore.
        for degrees in (1, 2, 3, 0):
            self.adb.set_rotation(degrees)
            time.sleep(1.5)
            obs = self.observe("chaos-rotation-%d" % degrees)
            if self.fatal_in(obs) or obs["blank"]:
                return "fail", "crash or blank at rotation %d" % degrees
            events.append("rotation-%d" % degrees)
        self.adb.set_auto_rotate(True)

        # Process death: background first (am kill only touches background
        # processes), kill, relaunch - saved state must survive the trip.
        self.adb.home()
        time.sleep(1)
        self.adb.am_kill(self.pkg)
        time.sleep(1)
        ok, _ = self.launch()
        time.sleep(2.5)
        obs = self.observe("chaos-process-death", shot=True,
                           expect_kill=True)
        if self.fatal_in(obs) or not obs["foreground"]:
            return "fail", "app failed to relaunch after process death"
        events.append("process-death")

        # Force-stop + restart: the harshest restart, no saved state.
        self.adb.force_stop(self.pkg)
        time.sleep(1)
        ok, _ = self.launch()
        time.sleep(3)
        obs = self.observe("chaos-force-stop-restart", expect_kill=True)
        if self.fatal_in(obs) or obs["blank"] or not obs["foreground"]:
            return "fail", "app failed to restart after force-stop"
        events.append("force-stop-restart")

        # Keyboard: open (tap a field if one exists) and close.
        obs = self.observe("chaos-keyboard-locate")
        fields = self.find_elements(obs, kinds=["text-field"],
                                    interactive_only=False)
        if fields:
            self.tap_element(fields[0], action_label="chaos-ime-open")
            time.sleep(1.5)
            obs_kb = self.observe("chaos-keyboard-open")
            self.adb.back()
            time.sleep(0.8)
            self.act("chaos-ime-close")
            obs_kb2 = self.observe("chaos-keyboard-closed")
            if self.fatal_in(obs_kb) or self.fatal_in(obs_kb2):
                return "fail", "crash while opening/closing the keyboard"
            events.append("keyboard-open-close")
        else:
            events.append("keyboard-open-close:skipped(no field)")

        return "pass", events

    def j_state_persistence(self):
        """Flip a discovered switch, kill the process the way the OS would,
        relaunch, and read the switch back. Honest limit: a black-box engine
        cannot tell a deliberately session-scoped control from a persistence
        bug, so a revert is a warning, never a release blocker."""
        self.ensure_app()
        switch = None
        for _ in range(4):
            obs = self.observe("persistence-locate")
            switches = self.find_elements(obs, kinds=["switch", "radio"])
            if switches:
                switch = switches[0]
                break
            candidates = [e for e in self.find_elements(obs)
                          if not is_destructive(e)]
            if candidates:
                self.tap_element(candidates[0], action_label="persistence-"
                                                                 "walk")
                time.sleep(1)
                continue
            break
        if switch is None:
            return "skip", "no switch/checkbox discovered to flip"
        before = switch.attrs.get("checked", "")
        self.tap_element(switch, action_label="persistence-toggle")
        time.sleep(1)
        obs = self.observe("persistence-toggled", shot=True)
        toggled_desc = switch.descriptor
        # Process death (background + am kill), then relaunch.
        self.adb.home()
        time.sleep(1)
        self.adb.am_kill(self.pkg)
        time.sleep(1)
        self.launch()
        time.sleep(3)
        obs = self.observe("persistence-after-revival", shot=True,
                           expect_kill=True)
        if self.fatal_in(obs) or not obs["foreground"]:
            return "fail", "app failed to relaunch after process death"
        after_switch = None
        for e in obs.get("elements", []):
            if e.descriptor == toggled_desc:
                after_switch = e
                break
        if after_switch is None:
            return "warning", {
                "note": "the toggled control was not found again after "
                        "relaunch (screen changed); persistence unverifiable",
                "control": element_label(switch),
            }
        after = after_switch.attrs.get("checked", "")
        if before != after:
            return "warning", {
                "control": element_label(switch),
                "before": before, "afterRevival": after,
                "note": "state did not survive process death; may be a "
                        "session-scoped control - manual review",
            }
        return "pass", {"control": element_label(switch),
                        "stateSurvivedProcessDeath": True}

    def j_random_exploration(self):
        """Seeded random actions over safe targets. The seed is in the trace
        meta, so the exact sequence is replayable."""
        steps = 0
        crashes = 0
        blanks = 0
        self.ensure_app()
        while self.budget_left() and steps < 40:
            obs = self.observe("random-step")
            if self.fatal_in(obs):
                crashes += 1
                # Recover and keep sampling: one crash is already a failed
                # run, but more evidence is strictly better.
                self.ensure_app()
                continue
            if obs["blank"] and not obs["dumpFailed"]:
                blanks += 1
                self.ensure_app()
                continue
            if not obs["foreground"]:
                self.ensure_app()
                continue
            choice = self.rng.random()
            if choice < 0.60:
                candidates = [e for e in self.find_elements(obs)
                              if not is_destructive(e)
                              and classify_element(e) != "text-field"]
                if candidates:
                    pick = self.rng.choice(candidates)
                    self.tap_element(pick, action_label="random-tap")
                    time.sleep(1)
                else:
                    self.adb.back()
                    self.act("random-back", screen=obs["signature"])
            elif choice < 0.80:
                scrollables = self.find_elements(obs, kinds=["scrollable"])
                if scrollables:
                    s = scrollables[0]
                    (x, y, w, h) = s.bounds or (540, 960, 1080, 1920)
                    self.adb.swipe(x, y + h // 4, x, y - h // 4, 400)
                    self.act("random-scroll", screen=obs["signature"])
                else:
                    self.adb.back()
                    self.act("random-back", screen=obs["signature"])
            else:
                self.adb.back()
                time.sleep(0.5)
                self.act("random-back", screen=obs["signature"])
            steps += 1
        detail = {"steps": steps, "crashes": crashes, "blanks": blanks,
                  "screensSeen": len(self.visited)}
        if crashes:
            return "fail", detail
        if blanks:
            return "fail", "blank screen(s) during random exploration"
        return "pass", detail

    def j_network_resilience(self):
        """Airplane mode on -> the app must not crash and must not wedge the
        UI; airplane mode off -> bounded wait, then the app must still work.
        Only run when the manifest declares INTERNET - flipping radios for
        an offline app tests nothing but the OS."""
        if not self.model.get("declaresInternet"):
            return "skip", "app does not declare INTERNET"
        self.ensure_app()
        self.adb.airplane_mode(True)
        time.sleep(2)
        obs_on = self.observe("airplane-on", shot=True)
        # A short interaction while offline: the UI must stay responsive.
        self.adb.back()
        time.sleep(0.8)
        self.act("airplane-interact-back", screen=obs_on["signature"])
        obs_on2 = self.observe("airplane-on-interacted")
        self.adb.airplane_mode(False)
        # Bounded wait for connectivity recovery; 10 s is plenty for the
        # emulator's wifi to come back and far too short to hang a job.
        recovered = False
        for _ in range(10):
            state = self.adb.shell_out("dumpsys connectivity 2>/dev/null | "
                                       "grep -m1 'Airplane mode'")
            if "false" in state.lower():
                recovered = True
                break
            time.sleep(1)
        time.sleep(2)
        obs_off = self.observe("airplane-off", shot=True)
        failures = []
        if self.fatal_in(obs_on) or self.fatal_in(obs_on2):
            failures.append("crash while offline")
        if self.fatal_in(obs_off):
            failures.append("crash after connectivity returned")
        if obs_on["blank"] and not obs_on["dumpFailed"]:
            failures.append("blank screen while offline")
        if not recovered:
            failures.append("connectivity did not recover in the bounded "
                            "wait (device-side, recorded not failed)")
        if failures and any("crash" in f or "blank" in f for f in failures):
            return "fail", failures
        return "pass", {
            "airplaneCycle": "survived",
            "connectivityRecovered": recovered,
        }

    # ------------------------------------------------------------------

    def run(self):
        journeys = [
            ("cold-start", 1, self.j_cold_start),
            ("first-screen", 1, self.j_first_screen),
            ("rotation", 1, self.j_rotation),
            ("background-foreground", 1, self.j_background_foreground),
            ("navigation", 2, self.j_navigation),
            ("back-stack", 2, self.j_back_stack),
            ("form-inputs", 2, self.j_form_inputs),
            ("permission-cycle", 2, self.j_permission_cycle),
            ("input-fuzz", 3, self.j_input_fuzz),
            ("lifecycle-chaos", 3, self.j_lifecycle_chaos),
            ("state-persistence", 3, self.j_state_persistence),
            ("random-exploration", 3, self.j_random_exploration),
            ("network-resilience", 4, self.j_network_resilience),
        ]
        for name, min_rank, fn in journeys:
            if min_rank > self.rank:
                self.journeys.append({
                    "journey": name, "status": "skipped",
                    "detail": "not in %s mode" % self.mode,
                })
                continue
            if not self.budget_left() and min_rank > 1:
                self.journeys.append({
                    "journey": name, "status": "skipped",
                    "detail": "budget exhausted",
                })
                continue
            start = time.monotonic()
            try:
                status, detail = fn()
            except Exception as exc:  # engine defect must not eat evidence
                status, detail = "fail", "engine exception: %r" % exc
            self.journeys.append({
                "journey": name,
                "status": status,
                "detail": detail,
                "ms": int((time.monotonic() - start) * 1000),
            })
            print("journey %-22s %-8s %s" % (name, status,
                                             str(detail)[:100]))
            if status == "fail":
                self.any_failure = True
            # A hard budget violation ends the run with what it has; the
            # remaining journeys are recorded as skipped so the report's
            # tally can never read "fewer journeys ran" as "more passed".
            if time.monotonic() > self.deadline:
                print("time budget exhausted after journey %s" % name,
                      file=sys.stderr)
                break
        ran = {j["journey"] for j in self.journeys}
        for name, min_rank, _fn in journeys:
            if name not in ran and min_rank <= self.rank:
                self.journeys.append({
                    "journey": name, "status": "skipped",
                    "detail": "budget exhausted before this journey ran",
                })
        self.write_outputs()

    def write_outputs(self):
        meta = {
            "package": self.pkg,
            "versionName": self.model.get("versionName"),
            "versionCode": self.model.get("versionCode"),
            "mode": self.mode,
            "seed": self.seed,
            "maxActions": self.max_actions,
            "device": self.adb.device_info(),
            "journeyCounts": {
                "pass": sum(1 for j in self.journeys
                            if j["status"] == "pass"),
                "fail": sum(1 for j in self.journeys
                            if j["status"] == "fail"),
                "warning": sum(1 for j in self.journeys
                               if j["status"] == "warning"),
                "skip": sum(1 for j in self.journeys
                            if j["status"] == "skipped"),
            },
            "screensVisited": len(self.visited),
            "actionsTotal": self.n,
            "crashEvents": len(self.crashes),
        }
        with open(os.path.join(self.traces_dir, "action-trace.json"),
                  "w") as fh:
            json.dump({"meta": meta, "actions": self.actions}, fh, indent=2)
            fh.write("\n")
        with open(os.path.join(self.traces_dir, "journeys.json"), "w") as fh:
            json.dump({"meta": meta, "journeys": self.journeys}, fh,
                      indent=2)
            fh.write("\n")
        self._flush_crashes()
        print("explore: %d actions, %d screens, journeys %s, crashes %d" % (
            self.n, len(self.visited), meta["journeyCounts"],
            len(self.crashes)))
        if self.any_failure or self.crashes:
            sys.exit(1)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--model", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--mode", default="STANDARD",
                    choices=sorted(MODE_RANK))
    ap.add_argument("--seed", default="20260914",
                    help="deterministic seed; recorded in the trace")
    ap.add_argument("--serial", default="")
    ap.add_argument("--max-actions", type=int, default=None)
    ap.add_argument("--max-minutes", type=int, default=None)
    args = ap.parse_args()

    with open(args.model) as fh:
        model = json.load(fh)
    os.makedirs(args.out, exist_ok=True)
    engine = Engine(model, args.out, args.mode, args.seed,
                    serial=args.serial, max_actions=args.max_actions,
                    max_minutes=args.max_minutes)
    engine.run()


if __name__ == "__main__":
    main()
