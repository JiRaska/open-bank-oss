#!/usr/bin/env python3
"""Publish a compatibility artifact only for a complete current-run envelope."""

import argparse
import importlib.util
import json
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("envelope", type=Path)
    parser.add_argument("--component", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--branch", required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--attempt", required=True, type=int)
    args = parser.parse_args()

    if not args.envelope.is_file():
        print("Test Intelligence compatibility evidence: run.json is missing")
        return 1

    collector_path = Path(__file__).with_name("collect-test-run-evidence.py")
    spec = importlib.util.spec_from_file_location("test_run_evidence_collector", collector_path)
    assert spec and spec.loader
    collector = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(collector)
    try:
        envelope = json.loads(args.envelope.read_text(encoding="utf-8"))
        collector.validate_envelope(envelope)
        run = envelope["run"]
        if (envelope["component"] != args.component
                or run["commit"] != args.commit
                or run["branch"] != args.branch
                or run["id"] != args.run_id
                or run["attempt"] != args.attempt):
            raise ValueError("envelope provenance differs from this job")
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print(f"Test Intelligence compatibility evidence: invalid ({exc})")
        return 1

    print("Test Intelligence compatibility evidence: current-run envelope validated")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
