#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Exercise the DAST verdict through its CLI and emitted attestation record."""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("dast-zap-coverage.py")
SERVICE = "openbank-ledger-service"
SPEC = {"paths": {"/api/v1/items": {"get": {}}, "/api/v1/items/{id}": {"get": {}}}}


def run_case(root: Path, name: str, lines: list[str], ready: bool) -> tuple[subprocess.CompletedProcess[str], dict]:
    case = root / name
    case.mkdir()
    spec = case / "openapi.yaml"
    spec.write_text(json.dumps(SPEC))  # JSON is valid YAML; no scanner or network is needed.
    access = case / "access.log"
    access.write_text("\n".join(lines) + "\n")
    result = subprocess.run(
        [sys.executable, str(SCRIPT), "--service", SERVICE, "--spec", str(spec),
         "--access-log", str(access), "--ready", str(ready).lower(),
         "--out-dir", str(case / "reports")],
        text=True,
        capture_output=True,
        check=False,
    )
    record = json.loads((case / "reports" / f"{SERVICE}-ops.json").read_text())
    return result, record


def main() -> None:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        good, record = run_case(
            root, "authenticated",
            ["ZAPACCESS GET /api/v1/items 200", "ZAPACCESS GET /api/v1/items/42 403"], True,
        )
        assert good.returncode == 0 and record["verdict"] == "scanned", (good, record)
        assert (record["selected"], record["auth_blocked"], record["exercised"]) == (2, 1, 1)

        empty, record = run_case(root, "empty", ["ZAPACCESS GET /q/health 200"], True)
        assert empty.returncode == 1 and record["verdict"] == "not-a-scan", (empty, record)
        assert record["exercised"] == 0 and "zero spec operations were requested" in record["reasons"]

        blocked, record = run_case(
            root, "auth-wall",
            ["ZAPACCESS GET /api/v1/items 401"] * 19 + ["ZAPACCESS GET /api/v1/items/42 200"], True,
        )
        assert blocked.returncode == 1 and record["auth_rejected_responses"] == 19, (blocked, record)
        assert any("scan never got past authentication" in reason for reason in record["reasons"])

        unready, record = run_case(root, "unready", ["ZAPACCESS GET /api/v1/items 200"], False)
        assert unready.returncode == 1 and record["verdict"] == "not-a-scan", (unready, record)
        assert "service never became ready" in record["reasons"]
    print("DAST CLI regression: scan evidence, empty scope, auth wall, and unready service passed")


if __name__ == "__main__":
    main()
