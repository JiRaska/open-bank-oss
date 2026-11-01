"""Pinned renderer shape, redaction and incomplete-diagnostic regressions."""
# SPDX-License-Identifier: Apache-2.0
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "sanitize-gradle-task-profile.py"
SPEC = importlib.util.spec_from_file_location("task_profile", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
# Gradle 9.8.1 ProfileReportRenderer$ProfilePageRenderer$2: tab4, three
# headers, project total row, and indentPath task rows; no tbody element.
TABLE = """<div class="tab" id="tab4"><h2>Task Execution</h2><table><thead><tr>
<th>Task</th><th class="numeric">Duration</th><th>Result</th></tr></thead>
<tr><td>:openbank-lending-service</td><td class="numeric">1m0.12s</td><td>(total)</td></tr>
<tr><td class="indentPath">:openbank-lending-service:test</td><td class="numeric">1m0.12s</td><td></td></tr>
<tr><td class="indentPath">:openbank-libs:jar</td><td class="numeric">0.001s</td><td>FROM-CACHE</td></tr>
</table></div>"""


class SanitizedTaskProfileTest(unittest.TestCase):
    def collect(self, html=TABLE, version="9.8.1", additional=None):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "wrapper.properties").write_text(
                f"distributionUrl=https\\://services.gradle.org/distributions/gradle-{version}-bin.zip\n")
            if html is not None:
                (root / "profile-fixture.html").write_text(html)
            if additional is not None:
                (root / "profile-second.html").write_text(additional)
            return MODULE.report(root, root / "wrapper.properties")

    def assert_incomplete(self, result, reason="invalid_profile"):
        self.assertEqual("incomplete", result["status"])
        self.assertEqual(reason, result["reasonEnum"])
        self.assertEqual([], result["profiles"])

    def test_renderer_task_rows_become_numeric_closed_schema(self):
        result = self.collect()
        self.assertEqual("parsed", result["status"])
        self.assertFalse(result["criticalPathAvailable"])
        self.assertEqual([
            {"path": ":openbank-lending-service:test", "durationMs": 60120, "outcome": "work_performed"},
            {"path": ":openbank-libs:jar", "durationMs": 1, "outcome": "from_cache"},
        ], result["profiles"][0]["tasks"])

    def test_other_tables_and_free_text_are_never_copied(self):
        sensitive = "https://private.example.invalid/?token=NEVER_RETAIN_THIS"
        html = f'<p>{sensitive}</p><div id="tab3"><table><tr><td>{sensitive}</td></tr></table></div>{TABLE}'
        result = self.collect(html)
        self.assertEqual("parsed", result["status"])
        self.assertNotIn("private", json.dumps(result))
        self.assertNotIn("NEVER_RETAIN_THIS", json.dumps(result))

    def test_unsafe_task_path_and_arbitrary_skip_message_are_rejected(self):
        for html in (TABLE.replace(":openbank-libs:jar", ":token=NEVER_RETAIN_THIS"),
                     TABLE.replace("FROM-CACHE", "private.example.invalid NEVER_RETAIN_THIS"),
                     TABLE.replace("1m0.12s", "https://private.example.invalid")):
            with self.subTest(html=html):
                result = self.collect(html)
                self.assert_incomplete(result)
                self.assertNotIn("NEVER_RETAIN_THIS", json.dumps(result))

    def test_missing_duplicate_truncated_or_unknown_table_is_incomplete(self):
        for html in ("<html></html>", TABLE + TABLE, TABLE[:-12],
                     TABLE.replace("Task Execution", "Unknown table"),
                     TABLE.replace("Duration</th>", "Elapsed</th>"),
                     TABLE.replace("indentPath", "unknown-class"),
                     TABLE.replace(":openbank-libs:jar", ":openbank-lending-service:test")):
            with self.subTest(html=html):
                self.assert_incomplete(self.collect(html))

    def test_later_invalid_report_discards_all_earlier_task_data(self):
        self.assert_incomplete(self.collect(additional=TABLE.replace("FROM-CACHE", "unknown")))

    def test_missing_profile_and_changed_gradle_are_explicit(self):
        self.assert_incomplete(self.collect(html=None), "profile_missing")
        self.assert_incomplete(self.collect(version="9.9.0"), "unsupported_gradle")

    def test_native_duration_forms_and_all_closed_outcomes(self):
        for value, expected in (("0s", 0), ("0.123s", 123), ("2m0.01s", 120010),
                                ("1h0m0.00s", 3600000), ("1d0h0m0.00s", 86400000)):
            self.assertEqual(expected, MODULE.milliseconds(value))
        for outcome in MODULE.OUTCOMES:
            self.assertEqual("parsed", self.collect(TABLE.replace("FROM-CACHE", outcome))["status"])

    def test_cli_replaces_stale_output_with_incomplete_without_raw_text(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            wrapper = root / "wrapper.properties"
            wrapper.write_text("distributionUrl=https\\://services.gradle.org/distributions/gradle-9.8.1-bin.zip\n")
            (root / "profile-one.html").write_text(TABLE.replace("FROM-CACHE", "NEVER_RETAIN_THIS"))
            output = root / "out.json"
            output.write_text('{"status":"parsed","profiles":["stale"]}')
            result = subprocess.run([sys.executable, str(SCRIPT), "--profiles", str(root),
                                     "--wrapper", str(wrapper), "--out", str(output)],
                                    capture_output=True, text=True, timeout=5)
            self.assertEqual(0, result.returncode)
            self.assert_incomplete(json.loads(output.read_text()))
            self.assertNotIn("NEVER_RETAIN_THIS", result.stdout + result.stderr + output.read_text())


if __name__ == "__main__":
    unittest.main()
