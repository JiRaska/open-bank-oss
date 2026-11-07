"""Producer-to-consumer perf artifact contract regressions (no load execution)."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import zipfile

import yaml

HERE = Path(__file__).resolve().parent


def load(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


PRODUCER = load("bounded_perf_producer", "stage-perf-gate-report.py")
CONSUMER = load("bounded_perf_consumer", "stage-admin-ui-performance.py")
COLLECTOR = load("bounded_perf_archive", "collect-admin-ui-test-evidence.py")
ISSUE = load("bounded_perf_issue", "perf-gate-issue-body.py")
SERVICE = "openbank-example-service"
SPEC = {"summary": SERVICE + "-summary.json", "envelope": SERVICE + "-run.json"}


def packed(directory):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(directory.iterdir()):
            archive.write(path, path.name)
    return stream.getvalue()


class BoundedPerfReportTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.reports = self.root / "reports"
        self.reports.mkdir()
        self.output = self.root / "upload"
        self.envelope = {
            "schemaVersion": 1, "component": SERVICE, "suites": [], "coverage": None,
            "testInfrastructure": {"declared": [], "observed": []},
            "run": {"id": "123", "attempt": 1, "commit": "a" * 40, "branch": "main",
                    "workflow": "Performance gate", "url": "https://github.com/fixture/bank/actions/runs/123",
                    "observedAt": "2026-10-01T10:00:00Z"},
            "specializedEvidence": [{"kind": "performance", "state": "passed", "source": "summary.json",
                                     "thresholdResults": {"evaluated": 1, "breached": 0}}],
        }
        self.summary = {"metrics": {"http_req_duration": {"p(95)": 12.5,
                                                       "thresholds": {"p(95)<100": False}},
                                    "http_reqs": {"count": 10}}}
        self.write_json()

    def write_json(self):
        (self.reports / SPEC["envelope"]).write_text(json.dumps(self.envelope, indent=2) + "\n")
        (self.reports / SPEC["summary"]).write_text(json.dumps(self.summary, indent=2) + "\n")

    def stage(self):
        PRODUCER.stage(SERVICE, self.reports, self.output)

    def bundle(self, raw):
        return CONSUMER._bundle(raw, SPEC, COLLECTOR.safe_archive_members)

    def test_oversized_irrelevant_boot_log_reproduces_red_then_staged_contract_passes(self):
        boot = self.reports / (SERVICE + "-boot.log")
        with boot.open("wb") as stream:
            stream.seek(COLLECTOR.MAX_MEMBER_BYTES)
            stream.write(b"end of diagnostic\n")
        (self.reports / (SERVICE + "-k6.log")).write_bytes(b"k6 diagnostic\n")
        (self.reports / "unrelated.json").write_text("{}")
        before = {name: (self.reports / name).read_bytes() for name in SPEC.values()}
        with self.assertRaisesRegex(CONSUMER.StageError, "unsafe or malformed"):
            self.bundle(packed(self.reports))
        self.stage()
        self.assertEqual({path.name for path in self.output.iterdir()},
                         set(SPEC.values()) | {SERVICE + "-boot.log", SERVICE + "-k6.log"})
        actual = self.bundle(packed(self.output))
        for kind, name in SPEC.items():
            self.assertEqual(actual[kind], before[name])
        bounded = (self.output / boot.name).read_bytes()
        self.assertLessEqual(len(bounded), PRODUCER.MAX_LOG_BYTES)
        self.assertTrue(bounded.startswith(b"[truncated diagnostic: source_bytes="))
        self.assertTrue(bounded.endswith(b"end of diagnostic\n"))
        self.assertEqual(ISSUE.classify(self.reports, SERVICE), ISSUE.classify(self.output, SERVICE))

    def test_failed_metrics_are_preserved_and_not_reclassified_as_pass(self):
        self.envelope["specializedEvidence"][0].update(state="failed", thresholdResults={"evaluated": 1, "breached": 1})
        self.summary["metrics"]["http_req_duration"]["thresholds"]["p(95)<100"] = True
        self.write_json()
        self.stage()
        self.assertEqual(json.loads(self.bundle(packed(self.output))["summary"]), self.summary)
        self.assertEqual(ISSUE.classify(self.output, SERVICE)["kind"], "breach")

    def test_not_run_may_omit_summary_and_retains_boot_diagnostic_pointer(self):
        observation = self.envelope["specializedEvidence"][0]
        observation["state"] = "not-run"
        observation.pop("thresholdResults")
        self.write_json()
        (self.reports / SPEC["summary"]).unlink()
        (self.reports / (SERVICE + "-boot.log")).write_bytes(b"failed before measurement\n")
        self.stage()
        self.assertNotIn("summary", self.bundle(packed(self.output)))
        self.assertEqual(ISSUE.classify(self.reports, SERVICE), ISSUE.classify(self.output, SERVICE))

    def test_required_missing_malformed_oversized_and_symlink_files_fail_closed(self):
        for name in SPEC.values():
            original = (self.reports / name).read_bytes()
            for probe in ("missing", "malformed", "oversized", "symlink"):
                with self.subTest(name=name, probe=probe):
                    path = self.reports / name
                    path.unlink()
                    if probe == "malformed":
                        path.write_bytes(b"{invalid JSON")
                    elif probe == "oversized":
                        with path.open("wb") as stream:
                            stream.seek(PRODUCER.MAX_JSON_BYTES)
                            stream.write(b"x")
                    elif probe == "symlink":
                        path.symlink_to(self.reports / "outside")
                    with self.assertRaises((ValueError, OSError)):
                        self.stage()
                    self.assertFalse(self.output.exists())
                    path.unlink(missing_ok=True)
                    path.write_bytes(original)

    def test_nonfinite_missing_observations_and_component_mismatch_stay_red(self):
        for change in ("nan", "zero-requests", "wrong-component", "missing-performance", "wrong-thresholds"):
            with self.subTest(change=change):
                envelope, summary = copy.deepcopy(self.envelope), copy.deepcopy(self.summary)
                if change == "nan":
                    self.summary["metrics"]["http_req_duration"]["p(95)"] = float("nan")
                elif change == "zero-requests":
                    self.summary["metrics"]["http_reqs"]["count"] = 0
                elif change == "wrong-component":
                    self.envelope["component"] = "openbank-other-service"
                elif change == "missing-performance":
                    self.envelope["specializedEvidence"] = []
                else:
                    self.envelope["specializedEvidence"][0]["thresholdResults"]["breached"] = 1
                self.write_json()
                with self.assertRaises(ValueError):
                    self.stage()
                self.assertFalse(self.output.exists())
                self.envelope, self.summary = envelope, summary

    def test_stale_output_unsafe_service_and_diagnostic_symlink_are_rejected(self):
        self.output.mkdir()
        with self.assertRaises(ValueError):
            self.stage()
        self.output.rmdir()
        with self.assertRaises(ValueError):
            PRODUCER.stage("../escape", self.reports, self.output)
        (self.reports / (SERVICE + "-boot.log")).symlink_to(self.reports / "absent")
        with self.assertRaises(ValueError):
            self.stage()

    def test_consumer_path_and_crc_safety_remain_fail_closed(self):
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, "w") as archive:
            archive.writestr("../escape", b"x")
        with self.assertRaises(CONSUMER.StageError):
            self.bundle(raw.getvalue())
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, "w", compression=zipfile.ZIP_STORED) as archive:
            archive.writestr(SPEC["summary"], b'{"fixture":true}')
        corrupt = bytearray(raw.getvalue())
        offset = 30 + struct.unpack_from("<H", corrupt, 26)[0] + struct.unpack_from("<H", corrupt, 28)[0]
        corrupt[offset] ^= 1
        with self.assertRaises(CONSUMER.StageError):
            self.bundle(bytes(corrupt))

    def test_actual_workflow_upload_is_gated_on_successful_explicit_staging(self):
        workflow = yaml.safe_load((HERE.parent / "workflows/perf-gate.yml").read_text())
        steps = workflow["jobs"]["perf"]["steps"]
        stage = next(step for step in steps if step.get("id") == "stage-perf-reports")
        upload = next(step for step in steps if step.get("name") == "Upload perf reports")
        self.assertIn('stage-perf-gate-report.py --service "${{ matrix.service }}"', stage["run"])
        self.assertEqual(upload["if"], "always() && steps.stage-perf-reports.outcome == 'success'")
        self.assertEqual(upload["with"]["path"], "perf-upload/")
        self.assertEqual(upload["with"]["if-no-files-found"], "error")


if __name__ == "__main__":
    unittest.main()
