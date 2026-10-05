# SPDX-License-Identifier: Apache-2.0
"""Offline provenance and gate fixtures for the R8 fuzz measurement."""

from __future__ import annotations

import datetime as dt
import io
import json
import pathlib
import runpy
import tempfile
import unittest
import zipfile

from readiness_fuzz_evidence import EvidenceError, verify_fuzz_ops

REPO = "JiRaska/open-bank-oss"
REF = f"https://github.com/{REPO}/actions/runs/42"
DAY = dt.date(2026, 8, 1)
TODAY = dt.date(2026, 8, 6)


def fixture() -> dict[str, bytes]:
    record = {
        "service": "openbank-audit-service", "lane": "authz ON", "selected": 15,
        "auth_blocked": 2, "exercised": 13, "run": REF, "date": DAY.isoformat(),
    }
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w") as zipped:
        zipped.writestr("openbank-audit-service-ops.json", json.dumps(record))
    root = f"repos/{REPO}/actions"
    return {
        f"{root}/runs/42": json.dumps({
            "id": 42, "path": ".github/workflows/api-fuzz.yml", "status": "completed",
            "conclusion": "failure", "created_at": "2026-08-01T04:00:00Z",
            "head_branch": "main", "event": "schedule",
        }).encode(),
        f"{root}/runs/42/jobs?per_page=100": json.dumps({
            "total_count": 1, "jobs": [{
                "name": "schemathesis (openbank-audit-service)", "conclusion": "success",
            }],
        }).encode(),
        f"{root}/runs/42/artifacts?per_page=100": json.dumps({
            "artifacts": [{"name": "api-fuzz-reports-openbank-audit-service",
                           "id": 7, "expired": False, "size_in_bytes": len(archive.getvalue())}],
        }).encode(),
        f"{root}/artifacts/7/zip": archive.getvalue(),
    }


def check_ops(data: dict[str, bytes], **overrides: object) -> None:
    args = {"service": "audit", "ops": 13, "attested": DAY, "ttl_days": 21,
            "today": TODAY, "ref": REF, "repository": REPO, "fetch": data.__getitem__}
    args.update(overrides)
    verify_fuzz_ops(**args)


class FuzzEvidenceTest(unittest.TestCase):
    def test_exact_service_artifact_in_a_failed_fleet_run_can_prove_green_lane(self) -> None:
        check_ops(fixture())

    def test_count_mismatch_in_both_directions(self) -> None:
        for count in (12, 14):
            with self.subTest(count=count), self.assertRaisesRegex(EvidenceError, "differs"):
                check_ops(fixture(), ops=count)

    def test_reference_cannot_name_another_repository(self) -> None:
        with self.assertRaisesRegex(EvidenceError, "exact run"):
            check_ops(fixture(), ref="https://github.com/elsewhere/project/actions/runs/42")

    def test_old_measurement_and_renewed_date_fail(self) -> None:
        for kwargs in ({"today": dt.date(2026, 8, 23)},
                       {"attested": dt.date(2026, 8, 6)}, {"ttl_days": 22}):
            with self.subTest(kwargs=kwargs), self.assertRaises(EvidenceError):
                check_ops(fixture(), **kwargs)

    def test_wrong_workflow_branch_or_failed_service_job_fails(self) -> None:
        root = f"repos/{REPO}/actions/runs/42"
        for path, change in (
            (root, {"path": ".github/workflows/other.yml"}),
            (root, {"head_branch": "unreviewed-pr"}),
            (f"{root}/jobs?per_page=100", {"total_count": 1, "jobs": [
                {"name": "schemathesis (openbank-audit-service)", "conclusion": "failure"}]}),
        ):
            data = fixture()
            data[path] = json.dumps({**json.loads(data[path]), **change}).encode()
            with self.subTest(path=path), self.assertRaises(EvidenceError):
                check_ops(data)

    def test_missing_artifact_and_wrong_lane_fail(self) -> None:
        root = f"repos/{REPO}/actions"
        data = fixture()
        data[f"{root}/runs/42/artifacts?per_page=100"] = b'{"artifacts": []}'
        with self.assertRaisesRegex(EvidenceError, "absent"):
            check_ops(data)
        data = fixture()
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as zipped:
            zipped.writestr("openbank-audit-service-ops.json", json.dumps({
                "service": "openbank-audit-service", "lane": "authz OFF", "selected": 15,
                "auth_blocked": 2, "exercised": 13, "run": REF, "date": DAY.isoformat(),
            }))
        data[f"{root}/artifacts/7/zip"] = archive.getvalue()
        with self.assertRaisesRegex(EvidenceError, "different service, lane"):
            check_ops(data)

    def test_record_must_reconcile_selected_and_auth_blocked(self) -> None:
        data = fixture()
        archive = io.BytesIO()
        with zipfile.ZipFile(archive, "w") as zipped:
            zipped.writestr("openbank-audit-service-ops.json", json.dumps({
                "service": "openbank-audit-service", "lane": "authz ON", "selected": 15,
                "auth_blocked": 2, "exercised": 14, "run": REF, "date": DAY.isoformat(),
            }))
        data[f"repos/{REPO}/actions/artifacts/7/zip"] = archive.getvalue()
        with self.assertRaisesRegex(EvidenceError, "inconsistent"):
            check_ops(data)

    def test_gate_checks_active_claim_but_preserves_expired_decay(self) -> None:
        script = pathlib.Path(__file__).with_name("check-readiness-attestations.py")
        module = runpy.run_path(str(script))
        module["PENTEST_OPS_DEBT"].clear()
        with tempfile.TemporaryDirectory() as directory:
            repo = pathlib.Path(directory)
            service = repo / "openbank-audit-service/src/main/resources"
            service.mkdir(parents=True)
            (service / "openapi.yaml").write_text(
                "paths:\n" + "".join(f"  /r{i}:\n    get:\n" for i in range(16))
            )
            att = repo / module["FILE_REL"]
            att.parent.mkdir(parents=True)
            def write(ops: int, date: dt.date = DAY) -> None:
                att.write_text(
                    f"audit:\n  pentest: {{ date: {date}, ttl_days: 21, by: ci-schemathesis, "
                    f"ops: {ops}, ref: {REF} }}\n"
                )
            write(13)
            errors, _, count = module["check"](
                repo, module["FILE_REL"], TODAY, fuzz_fetch=fixture().__getitem__,
            )
            self.assertEqual((errors, count), ([], 1))
            write(12)
            errors, _, _ = module["check"](
                repo, module["FILE_REL"], TODAY, fuzz_fetch=fixture().__getitem__,
            )
            self.assertTrue(any("differs from measured" in error for error in errors), errors)
            write(13, dt.date(2026, 7, 1))
            errors, warnings, _ = module["check"](
                repo, module["FILE_REL"], TODAY,
                fuzz_fetch=lambda _: self.fail("expired claim fetched an artifact"),
            )
            self.assertFalse(errors, errors)
            self.assertTrue(warnings)


if __name__ == "__main__":
    unittest.main()
