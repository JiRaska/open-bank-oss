#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors.
"""Run real Node trace fixtures and report their executed subject count."""

from __future__ import annotations

import os
import re
import signal
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

def completed_node_subjects(output: str) -> int:
    """Count executed successful fixtures from Node's real TAP summary, never discovery."""
    summary = {}
    for line in output.splitlines():
        match = re.fullmatch(r"# (tests|pass|fail|cancelled|skipped|todo) ([0-9]+)", line)
        if match:
            key, value = match.groups()
            if key in summary:
                raise ValueError("duplicate Node TAP summary")
            summary[key] = int(value)
    if set(summary) != {"tests", "pass", "fail", "cancelled", "skipped", "todo"}:
        raise ValueError("missing Node TAP summary")
    if summary["tests"] != summary["pass"] or any(summary[k] for k in ("fail", "cancelled", "skipped", "todo")):
        raise ValueError("Node fixtures did not all execute successfully")
    return summary["pass"]


def run_node_gate() -> int:
    result = subprocess.run(
        ["node", "--test", "--test-reporter=tap", str(ROOT / "openbank-admin-ui/scripts/request-send-trace.test.mjs")],
        stdout=subprocess.PIPE, check=False,
    )
    sys.stdout.buffer.write(result.stdout)
    sys.stdout.buffer.flush()
    if result.returncode:
        return result.returncode
    try:
        subjects = completed_node_subjects(result.stdout.decode("utf-8"))
    except (ValueError, UnicodeDecodeError) as error:
        print(f"Node fixture accounting failed: {error}", file=sys.stderr)
        return 1
    print(f"SUBJECTS={subjects}")
    return 0


if __name__ == "__main__":
    status = run_node_gate()
    if status < 0:
        if -status not in (signal.SIGKILL, signal.SIGSTOP):
            signal.signal(-status, signal.SIG_DFL)
        os.kill(os.getpid(), -status)
    sys.exit(status)
