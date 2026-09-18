#!/usr/bin/env python3
"""The release reporter's own parser, tested without an emulator.

`testing/generate-report.py` turns a gate run's evidence into the report a repair
attempt is handed, and the way it breaks is not by crashing. It ran for months
with a parse that could only read a *green* run's summary, so the report for
v1.2.0's failed gate - 47 tests on both legs - said "Ran: 0". The shape below is
the one the AndroidJUnitRunner actually prints, taken verbatim from the artifacts
of run 35338666049 (API 30, the `NoClassDefFoundError` that pulled v1.2.0 back to
draft), plus the shape a run killed by the workflow's `timeout` leaves behind.

A reporter that misdescribes a failing run is worse than one that fails: the
verdict in it is still right, so nothing looks broken, and the wrong numbers are
read as facts by whoever - or whatever - tries to fix the failure next.

Run: python3 testing/test_generate_report.py
"""

import importlib.util
import json
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location(
    "generate_report", os.path.join(HERE, "generate-report.py")
)
generate_report = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(generate_report)


# The tail of out/test-stdout.txt from run 35338666049's API 30 leg, verbatim:
# the inline "Error in ..." echo the runner prints as the test fails, the
# numbered summary it prints at the end, and the two spaces after "Tests run:
# 47," that its own format string produces.
FAILED_RUN = """\
dev.eclipse.ssh.AppNavigationTest:.........
dev.eclipse.ssh.MainActivityLifecycleTest:........
dev.eclipse.ssh.SystemBarAppearanceTest:
Error in theSystemBarIconsFollowTheAppsOwnDarkSettingAndNotTheSystems(dev.eclipse.ssh.SystemBarAppearanceTest):
java.lang.NoClassDefFoundError: Failed resolution of: Landroidx/core/view/WindowCompat;
\tat dev.eclipse.ssh.SystemBarAppearanceTest.lightBarIcons$lambda$0(r8-map-id-4a17:13)
\tat mx4.perform(r8-map-id-4a17:12)

dev.eclipse.ssh.security.SecureVaultInstrumentedTest:.........

Time: 75.135
There was 1 failure:
1) theSystemBarIconsFollowTheAppsOwnDarkSettingAndNotTheSystems(dev.eclipse.ssh.SystemBarAppearanceTest)
java.lang.NoClassDefFoundError: Failed resolution of: Landroidx/core/view/WindowCompat;
\tat dev.eclipse.ssh.SystemBarAppearanceTest.lightBarIcons$lambda$0(r8-map-id-4a17:13)

FAILURES!!!
Tests run: 47,  Failures: 1
"""

# A green leg's tail: one summary line, and no numbered list.
PASSED_RUN = """\
dev.eclipse.ssh.AppNavigationTest:.........
dev.eclipse.ssh.security.SecureVaultInstrumentedTest:.........

Time: 71.204

OK (47 tests)
"""

# A run killed by the workflow's `timeout`: the echo is there, the summary never
# is, so the reporter has a failure and no count - and must say exactly that.
KILLED_RUN = """\
dev.eclipse.ssh.AppNavigationTest:.........
dev.eclipse.ssh.ReleaseChaosJourneyTest:....
dev.eclipse.ssh.SystemBarAppearanceTest:
Error in theSystemBarIconsFollowTheAppsOwnDarkSettingAndNotTheSystems(dev.eclipse.ssh.SystemBarAppearanceTest):
java.lang.NoClassDefFoundError: Failed resolution of: Landroidx/core/view/WindowCompat;
\tat dev.eclipse.ssh.SystemBarAppearanceTest.lightBarIcons$lambda$0(r8-map-id-4a17:13)

"""

FAILING_TEST = (
    "theSystemBarIconsFollowTheAppsOwnDarkSettingAndNotTheSystems"
    "(dev.eclipse.ssh.SystemBarAppearanceTest)"
)


class ParseSuiteTest(unittest.TestCase):
    def test_a_green_run_counts_its_tests(self):
        ran, failed, failures = generate_report.parse_suite(PASSED_RUN)
        self.assertEqual(ran, 47)
        self.assertEqual(failed, 0)
        self.assertEqual(failures, [])

    def test_a_failed_run_counts_its_tests(self):
        # The bug this file exists for: 47 tests ran, and the report said 0.
        ran, failed, failures = generate_report.parse_suite(FAILED_RUN)
        self.assertEqual(ran, 47)
        self.assertEqual(failed, 1)

    def test_a_failed_run_lists_the_test_once_and_with_its_stack(self):
        # Both the inline echo and the numbered summary describe this one
        # failure. Counting both would report two.
        _, failed, failures = generate_report.parse_suite(FAILED_RUN)
        self.assertEqual(failed, 1)
        self.assertEqual(failures[0]["test"], FAILING_TEST)
        self.assertIn("NoClassDefFoundError", failures[0]["stack"])

    def test_a_killed_run_lists_the_failures_it_printed(self):
        # The numbered summary comes last, so a killed run has none - but the
        # echo is already in the log, and dropping it leaves a FAIL verdict
        # listing nothing at all.
        ran, failed, failures = generate_report.parse_suite(KILLED_RUN)
        self.assertEqual(failed, 1)
        self.assertEqual(failures[0]["test"], FAILING_TEST)
        self.assertIn("NoClassDefFoundError", failures[0]["stack"])

    def test_a_killed_run_has_no_count_rather_than_zero(self):
        ran, _, _ = generate_report.parse_suite(KILLED_RUN)
        self.assertIsNone(ran)

    def test_an_empty_log_is_not_a_crash(self):
        self.assertEqual(generate_report.parse_suite(""), (None, 0, []))


class ReportRenderingTest(unittest.TestCase):
    """One end-to-end pass through main(), because the report is the artifact."""

    def _run(self, stdout_text, passed, label="v1.2.1"):
        out_dir = tempfile.mkdtemp(prefix="eclipse-report-test-")
        stdout_path = os.path.join(out_dir, "test-stdout.txt")
        with open(stdout_path, "w") as f:
            f.write(stdout_text)
        argv = sys.argv
        try:
            sys.argv = [
                "generate-report.py", out_dir, label, "35", "deadbee",
                "maragung/EclipseSSH", stdout_path,
                os.path.join(out_dir, "release-validation.json"), "-", passed,
            ]
            generate_report.main()
        finally:
            sys.argv = argv
        with open(os.path.join(out_dir, "release-test-report.md")) as f:
            report = f.read()
        failure_path = os.path.join(out_dir, "failure-report.json")
        failure = None
        if os.path.exists(failure_path):
            with open(failure_path) as f:
                failure = json.load(f)
        return report, failure

    def test_a_killed_run_reports_unknown_rather_than_zero_tests(self):
        report, failure = self._run(KILLED_RUN, "no")
        self.assertIn("Ran: unknown", report)
        self.assertNotIn("Ran: 0", report)
        self.assertIn("- Failed: 1", report)
        self.assertIn("DO NOT RELEASE", report)
        # The repair loop reads the test name out of this file, not the report.
        self.assertEqual(failure["summary"].split(",")[0], "1 failing tests")
        self.assertEqual(failure["failingTests"][0]["test"], FAILING_TEST)

    def test_a_green_run_reports_release(self):
        # The verdict is the workflow's conclusion, not a re-parse of stdout:
        # argv says pass, and the report must agree with it.
        report, failure = self._run(PASSED_RUN, "yes")
        self.assertIn("**PASS**", report)
        self.assertIn("- Ran: 47", report)
        self.assertIsNone(failure)

    def test_the_report_names_what_was_under_test_not_only_a_tag(self):
        # The label is the tag on a release run and `<ref>@<commit>` on an
        # apk_source=build run, which publishes nothing. Reading the tag's slot
        # as if it were always a tag is what left issue #124 titled
        # "Release test failure: " with an empty version line in its report.
        report, failure = self._run(KILLED_RUN, "no", label="fix/x@abc1234")
        self.assertIn("- Under test: `fix/x@abc1234`", report)
        self.assertEqual(failure["environment"]["label"], "fix/x@abc1234")


if __name__ == "__main__":
    unittest.main(verbosity=2)
