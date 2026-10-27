#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
import copy
import importlib.util
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from urllib.parse import parse_qs, urlsplit
import zipfile

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
SOURCE_ROOT = Path(os.environ.get("OPENBANK_PERFORMANCE_TEST_REPO", HERE.parents[1]))


def load(name, file):
    spec = importlib.util.spec_from_file_location(name, file)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


STAGE = load("stage_admin_ui_performance", HERE / "stage-admin-ui-performance.py")
SELECTOR = load("stage_performance_selector", SOURCE_ROOT / ".github/scripts/select-performance-evidence.py")
COLLECTOR = load("stage_performance_collector", SOURCE_ROOT / ".github/scripts/collect-admin-ui-test-evidence.py")
REPO = "fixture/bank"
SHA = "a" * 40
START = "2026-10-01T10:00:00Z"
OBSERVED = "2026-10-01T10:10:00Z"
CREATED = "2026-10-01T10:11:00Z"
FINISHED = "2026-10-01T10:12:00Z"


def run(identifier, workflow="perf-gate.yml", attempt=1, start=START, **extra):
    return {
        "id": identifier, "run_attempt": attempt, "run_started_at": start,
        "updated_at": FINISHED, "head_sha": SHA, "head_branch": "main",
        "head_repository": {"full_name": REPO}, "event": "schedule",
        "status": "completed", "conclusion": "failure",
        "path": ".github/workflows/" + workflow,
        "name": "Performance gate" if workflow == "perf-gate.yml" else "Perf baseline",
        **extra,
    }


def envelope(metadata, component, state="passed", attempt=None, observed=OBSERVED):
    return {
        "schemaVersion": 1, "component": component, "suites": [], "coverage": None,
        "testInfrastructure": {"declared": [], "observed": []},
        "run": {
            "id": str(metadata["id"]), "attempt": attempt or metadata["run_attempt"],
            "commit": SHA, "branch": "main", "workflow": metadata["name"],
            "url": f"https://github.com/{REPO}/actions/runs/{metadata['id']}", "observedAt": observed,
        },
        "specializedEvidence": [{
            "kind": "performance", "state": state, "source": "summary.json", "detail": "fixture",
            **({"thresholdResults": {"evaluated": 1, "breached": int(state == "failed")}}
               if state != "not-run" else {}),
        }],
    }


def summary(failed=False):
    return {"metrics": {"http_req_duration": {"p(95)": 12.5, "thresholds": {"p(95)<100": failed}},
                        "http_reqs": {"count": 10}, "http_req_failed": {"value": 0}, "checks": {"value": 1}}}


def archive(files, symlink=False, duplicate=False):
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as zipped:
        for name, value in files.items():
            raw = value if isinstance(value, bytes) else json.dumps(value).encode()
            info = zipfile.ZipInfo(name)
            if symlink:
                info.external_attr = 0o120777 << 16
            zipped.writestr(info, raw)
            if duplicate:
                zipped.writestr(info, raw)
    return out.getvalue()


class PerformanceStageTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.runs = {"perf-gate.yml": [], "perf-baseline.yml": []}
        self.jobs, self.artifacts, self.archives, self.attempts = {}, {}, {}, {}
        self.logs, self.reads = {}, []

    def add_run(self, metadata, components=None):
        workflow = metadata["path"].rsplit("/", 1)[1]
        self.runs[workflow].append(metadata)
        self.jobs[metadata["id"]] = []
        self.artifacts[metadata["id"]] = []
        if workflow == "perf-gate.yml":
            self.jobs[metadata["id"]] = [
                {"id": 900, "name": "derive perf-gate scope", "started_at": metadata["run_started_at"], "conclusion": "success"},
                *[{"id": 901 + index, "name": name, "started_at": metadata["run_started_at"], "conclusion": "success"}
                  for index, (_, name) in enumerate(SELECTOR.EXPECTED_GATE)],
            ]

    def artifact(self, metadata, component, *, identifier, baseline=None, state="passed", created=CREATED, attempt=None, observed=OBSERVED):
        if baseline == "common":
            name, summary_name, envelope_name = "k6-money-path-summary", "perf-summary.json", "perf-run.json"
        elif baseline:
            name = f"k6-{baseline}-read-baseline-summary"
            summary_name, envelope_name = f"{baseline}-summary.json", f"{baseline}-perf-run.json"
        else:
            name = "perf-reports-" + component
            summary_name, envelope_name = component + "-summary.json", component + "-run.json"
        self.artifacts[metadata["id"]].append({
            "id": identifier, "name": name, "expired": False, "created_at": created,
            "workflow_run": {"id": metadata["id"], "head_sha": SHA, "head_branch": "main"},
        })
        files = {envelope_name: envelope(metadata, component, state, attempt, observed)}
        if state != "not-run":
            files[summary_name] = summary(state == "failed")
        self.archives[identifier] = archive(files)
        return self.artifacts[metadata["id"]][-1]

    def gate(self, identifier=100):
        metadata = run(identifier)
        self.add_run(metadata)
        for index, (name, _) in enumerate(SELECTOR.EXPECTED_GATE):
            self.artifact(metadata, name.removeprefix("perf-reports-"), identifier=identifier * 10 + index)
        return metadata

    def module(self, component="openbank-account-service", identifier="account"):
        file = self.root / component / "src/test/k6" / (identifier + "-read-baseline.js")
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text("// governed read baseline fixture\n")

    def json_api(self, url):
        self.reads.append(url)
        parsed = urlsplit(url)
        self.assertEqual(parsed.scheme + "://" + parsed.netloc, "https://api.github.com")
        prefix = f"/repos/{REPO}/actions/"
        self.assertTrue(parsed.path.startswith(prefix), url)
        tail = parsed.path.removeprefix(prefix)
        query = parse_qs(parsed.query)
        if tail.startswith("workflows/"):
            workflow = tail.split("/")[1]
            self.assertEqual(query.get("branch"), ["main"])
            self.assertEqual(query.get("status"), ["completed"])
            return {"workflow_runs": copy.deepcopy(self.runs[workflow]) if query.get("page", ["1"]) == ["1"] else []}
        bits = tail.split("/")
        rid = int(bits[1])
        if bits[-1] == "jobs":
            return {"jobs": copy.deepcopy(self.jobs[rid])}
        if bits[-1] == "artifacts":
            return {"artifacts": copy.deepcopy(self.artifacts[rid])}
        if len(bits) == 4 and bits[2] == "attempts":
            return copy.deepcopy(self.attempts[(rid, int(bits[3]))])
        raise AssertionError(url)

    def bytes_api(self, url):
        self.reads.append(url)
        return self.archives[int(urlsplit(url).path.split("/")[-2])]

    def collect(self, **overrides):
        options = dict(safe_archive_members=COLLECTOR.safe_archive_members,
                       validate_envelope=COLLECTOR.ENVELOPES.validate,
                       selector=SELECTOR, fetch_log=lambda identifier: self.logs[identifier])
        options.update(overrides)
        return STAGE.stage_performance(
            self.root, REPO, self.json_api, self.bytes_api, **options,
        )

    def test_failed_workflow_retains_both_successful_smoke_measurements(self):
        metadata = self.gate()
        result = self.collect()
        self.assertEqual(len(result["payloads"]), 4)
        self.assertEqual(result["sources"][0]["run_id"], metadata["id"])
        self.assertFalse(result["sources"][0]["missing"])
        self.assertFalse(list(self.root.rglob("*.json")), "stager must never write its checkout")

    def test_latest_missing_artifact_never_borrows_older_pass(self):
        self.gate(99)
        current = self.gate(100)
        self.artifacts[current["id"]].pop()
        result = self.collect()
        self.assertEqual(result["sources"][0]["run_id"], 100)
        self.assertEqual(len(result["sources"][0]["missing"]), 1)
        self.assertNotIn("/runs/99/artifacts", " ".join(self.reads))

    def test_not_run_baseline_and_unambiguous_module_are_preserved(self):
        self.module()
        metadata = run(200, "perf-baseline.yml")
        self.add_run(metadata)
        self.jobs[200] = [{"id": 1, "name": "k6 money-path read baseline", "started_at": START, "conclusion": "success"},
                          {"id": 2, "name": "k6 module read baseline (account)", "started_at": START, "conclusion": "success"}]
        self.artifact(metadata, "openbank-money-path", identifier=2000, baseline="common", state="not-run")
        self.artifact(metadata, "openbank-account-service", identifier=2001, baseline="account", state="not-run")
        result = self.collect()
        self.assertEqual(len(result["payloads"]), 2)
        for name, raw in result["payloads"].items():
            self.assertTrue(name.endswith("-summary.json.run.json"), name)
            self.assertEqual(json.loads(raw)["specializedEvidence"][0]["state"], "not-run")

    def test_unknown_latest_gate_scope_is_an_older_evidence_barrier(self):
        self.gate(99)
        self.gate(100)
        self.jobs[100].pop()
        result = self.collect()
        self.assertEqual((result["sources"][0]["scope"], result["sources"][0]["run_id"]), ("barrier", 100))
        self.assertFalse(result["payloads"])
        self.assertNotIn("/runs/99/", " ".join(self.reads))

    def test_only_a_positively_proven_narrow_dispatch_is_skipped(self):
        self.gate(99)
        newest = self.gate(100)
        newest["event"] = "workflow_dispatch"
        self.jobs[100].pop()
        result = self.collect(fetch_log=None)
        self.assertEqual(result["sources"][0]["scope"], "barrier")
        self.logs[900] = "perf-gate: scope OVERRIDDEN by dispatch input (1 of 1 requested service(s))"
        result = self.collect()
        self.assertEqual(result["sources"][0]["run_id"], 99)
        self.assertEqual(len(result["payloads"]), 4)

    def test_failed_only_rerun_carry_requires_its_own_attempt_metadata(self):
        metadata = run(100, attempt=2, start="2026-10-01T12:00:00Z", updated_at="2026-10-01T12:12:00Z")
        self.add_run(metadata)
        self.jobs[100][1]["started_at"] = START
        self.artifact(metadata, "openbank-product-catalog", identifier=1000, attempt=1)
        self.artifact(metadata, "openbank-flaky-test-hunter", identifier=1001,
                      created="2026-10-01T12:11:00Z", observed="2026-10-01T12:10:00Z")
        self.attempts[(100, 1)] = run(100)
        result = self.collect()
        self.assertEqual(len(result["payloads"]), 4)
        self.assertTrue(any(url.endswith("/runs/100/attempts/1") for url in self.reads))
        self.attempts[(100, 1)]["head_sha"] = "b" * 40
        with self.assertRaises(STAGE.StageError):
            self.collect()

    def test_old_failed_job_is_not_carried_into_current_attempt(self):
        metadata = run(100, attempt=2, start="2026-10-01T12:00:00Z", updated_at="2026-10-01T12:12:00Z")
        self.add_run(metadata)
        self.jobs[100][1].update(started_at=START, conclusion="failure")
        self.artifact(metadata, "openbank-product-catalog", identifier=1000, attempt=1)
        result = self.collect()
        self.assertFalse(result["payloads"])
        self.assertEqual(len(result["sources"][0]["missing"]), 2)

    def test_foreign_nonmain_or_wrong_workflow_run_is_rejected(self):
        metadata = self.gate()
        original = copy.deepcopy(metadata)
        for changes in [{"head_repository": {"full_name": "other/repo"}}, {"head_branch": "feature"},
                        {"path": ".github/workflows/untrusted.yml"}, {"event": "pull_request"},
                        {"status": "in_progress"}, {"run_started_at": "not a timestamp"}]:
            with self.subTest(changes=changes):
                metadata.clear()
                metadata.update({**original, **changes})
                with self.assertRaises(STAGE.StageError):
                    self.collect()

    def test_component_run_attempt_commit_and_url_must_match(self):
        metadata = self.gate()
        component = "openbank-product-catalog"
        for changes in [{"component": "openbank-other"}, {"run": {"id": "999"}},
                        {"run": {"attempt": 2}}, {"run": {"commit": "b" * 40}},
                        {"run": {"url": "https://example.invalid/actions/runs/100"}},
                        {"run": {"observedAt": "2026-10-01T10:13:00Z"}}]:
            data = envelope(metadata, component)
            for key, value in changes.items():
                if key == "run":
                    data[key].update(value)
                else:
                    data[key] = value
            self.archives[1000] = archive({component + "-run.json": data, component + "-summary.json": summary()})
            with self.subTest(changes=changes), self.assertRaises(STAGE.StageError):
                self.collect()

    def test_unsafe_zip_shapes_and_oversized_json_are_rejected(self):
        metadata = self.gate()
        component = "openbank-product-catalog"
        valid = {component + "-run.json": envelope(metadata, component), component + "-summary.json": summary()}
        probes = [archive({**valid, "../escape.json": {}}), archive(valid, symlink=True),
                  archive({**valid, "nested/" + component + "-summary.json": summary()}),
                  archive({component + "-run.json": b"x" * (STAGE.MAX_JSON_BYTES + 1)})]
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            probes.append(archive(valid, duplicate=True))
        for raw in probes:
            with self.subTest(size=len(raw)):
                self.archives[1000] = raw
                with self.assertRaises(STAGE.StageError):
                    self.collect()

    def test_missing_summary_is_a_barrier_not_a_fabricated_envelope(self):
        metadata = self.gate()
        component = "openbank-product-catalog"
        self.archives[1000] = archive({component + "-run.json": envelope(metadata, component)})
        result = self.collect()
        self.assertEqual(len(result["payloads"]), 2)
        self.assertIn("summary absent", result["sources"][0]["missing"][0]["reason"])
        self.assertFalse(any(component in file for file in result["payloads"]))

    def test_summary_must_not_launder_threshold_breach_or_nonfinite_data(self):
        metadata = self.gate()
        component = "openbank-product-catalog"
        for raw in [json.dumps(summary(True)).encode(), b'{"metrics":NaN}',
                    b'{"metrics":{},"metrics":{}}', b'{"metrics":{}}']:
            self.archives[1000] = archive({component + "-run.json": envelope(metadata, component),
                                           component + "-summary.json": raw})
            with self.subTest(raw=raw), self.assertRaises(STAGE.StageError):
                self.collect()

    def test_artifact_identity_and_expiration_are_not_ignored(self):
        self.gate()
        artifact = self.artifacts[100][0]
        artifact["workflow_run"]["id"] = 999
        with self.assertRaises(STAGE.StageError):
            self.collect()
        artifact["workflow_run"]["id"] = 100
        artifact["expired"] = True
        result = self.collect()
        self.assertEqual(len(result["sources"][0]["missing"]), 1)

    def test_baseline_derivation_covers_ten_modules_without_manual_allowlist(self):
        modules = {"account": "openbank-account-service", "balance": "openbank-balance-service",
                   "consent": "openbank-consent-service", "domestic-payment": "openbank-domestic-payment",
                   "fx": "openbank-fx-service", "ledger": "openbank-ledger-service", "sca": "openbank-sca-service",
                   "sepa-instant": "openbank-sepa-instant", "sepa-payment": "openbank-sepa-payment",
                   "transaction": "openbank-transaction-service"}
        metadata = run(200, "perf-baseline.yml")
        self.add_run(metadata)
        self.jobs[200].append({"name": "k6 money-path read baseline", "started_at": START, "conclusion": "success"})
        self.artifact(metadata, "openbank-money-path", identifier=2000, baseline="common")
        for index, (identifier, component) in enumerate(modules.items(), 1):
            self.module(component, identifier)
            self.jobs[200].append({"name": "k6 module read baseline (" + identifier + ")", "started_at": START, "conclusion": "success"})
            self.artifact(metadata, component, identifier=2000 + index, baseline=identifier)
        result = self.collect()
        self.assertEqual(len(result["payloads"]), 22)
        self.assertEqual(len(result["sources"][1]["selected"]), 11)
        self.assertFalse(result["sources"][1]["missing"])

    def test_ambiguous_baseline_definition_is_never_attributed(self):
        self.module()
        self.module("openbank-account-service", "account-other")
        result = self.collect()
        self.assertEqual(len(result["sources"][1]["expected"]), 1)
        self.assertTrue(any("Ambiguous" in value for value in result["warnings"]))

    def test_complete_history_pagination_is_reused_and_bounded(self):
        calls = []
        def pages(url):
            calls.append(url)
            return {"items": [{}] * (100 if parse_qs(urlsplit(url).query)["page"] == ["1"] else 1)}
        self.assertEqual(len(STAGE._pages(pages, "https://api.github.com/fixture", "items")), 2)
        self.assertEqual(len(calls), 2)
        with self.assertRaises(STAGE.StageError):
            STAGE._pages(lambda _url: {"items": [{}] * 100}, "https://api.github.com/fixture", "items")

    def test_rerun_attempt_timestamp_orders_runs_before_numeric_id(self):
        self.gate(100)
        newer_attempt = self.gate(99)
        newer_attempt.update(run_started_at="2026-10-02T10:00:00Z", updated_at="2026-10-02T10:12:00Z")
        self.jobs[99] = []
        result = self.collect()
        self.assertEqual((result["sources"][0]["run_id"], result["sources"][0]["scope"]), (99, "barrier"))
        self.assertFalse(result["payloads"])

    def test_latest_baseline_missing_bundle_never_borrows_prior_pass(self):
        for rid in [199, 200]:
            metadata = run(rid, "perf-baseline.yml")
            self.add_run(metadata)
            self.jobs[rid] = [{"name": "k6 money-path read baseline", "started_at": START, "conclusion": "success"}]
            if rid == 199:
                self.artifact(metadata, "openbank-money-path", identifier=1990, baseline="common")
        result = self.collect()
        self.assertEqual(result["sources"][1]["run_id"], 200)
        self.assertEqual(len(result["sources"][1]["missing"]), 1)
        self.assertFalse(result["payloads"])
        self.assertFalse(any("/runs/199/" in url for url in self.reads))


if __name__ == "__main__":
    unittest.main()
