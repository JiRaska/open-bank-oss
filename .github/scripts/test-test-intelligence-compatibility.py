#!/usr/bin/env python3
"""Exercise complete, missing, malformed, and stale compatibility evidence."""

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


HERE = Path(__file__).resolve().parent
COLLECTOR = HERE / "collect-test-run-evidence.py"
CHECK = HERE / "check-test-intelligence-compatibility.py"


def check(path: Path, *, branch: str = "main") -> int:
    result = subprocess.run(
        [sys.executable, str(CHECK), str(path), "--component", "openbank-kyc-service",
         "--commit", "0123456789abcdef", "--branch", branch, "--run-id", "123",
         "--attempt", "1"],
        capture_output=True, text=True, check=False,
    )
    return result.returncode


def main() -> None:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        service = root / "openbank-kyc-service"
        service.mkdir()
        envelope_path = root / "run.json"
        env = os.environ | {
            "GITHUB_SHA": "0123456789abcdef",
            "GITHUB_REF_NAME": "main",
            "GITHUB_HEAD_REF": "",
            "GITHUB_RUN_ID": "123",
            "GITHUB_RUN_ATTEMPT": "1",
            "GITHUB_WORKFLOW": "Services CI",
            "GITHUB_SERVER_URL": "https://github.com",
            "GITHUB_REPOSITORY": "JiRaska/open-bank-oss",
        }
        subprocess.run(
            [sys.executable, str(COLLECTOR), "--service", str(service),
             "--out", str(envelope_path)], env=env, check=True, capture_output=True,
        )
        assert check(envelope_path) == 0
        assert check(envelope_path, branch="another-branch") == 1
        assert check(root / "missing.json") == 1

        valid = json.loads(envelope_path.read_text(encoding="utf-8"))
        valid["schemaVersion"] = 2
        envelope_path.write_text(json.dumps(valid), encoding="utf-8")
        assert check(envelope_path) == 1
        envelope_path.write_text("{incomplete", encoding="utf-8")
        assert check(envelope_path) == 1
    print("Test Intelligence compatibility upload: complete/current accepted; stale, missing, invalid rejected")


if __name__ == "__main__":
    main()
