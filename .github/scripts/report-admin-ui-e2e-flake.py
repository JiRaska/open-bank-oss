#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""ADR-0370 U2: report Admin UI e2e tests that passed only on retry.

A report, not a gate: it runs inside the conditional Admin UI CI job, right after the
Playwright run whose JUnit it reads, and is advisory until the flake rate has a measured
baseline. The logic and its self-test live in check-admin-ui-flake-quarantine.py, whose gate
mode (the quarantine expiry) is declared in gates.yaml.
"""

from __future__ import annotations

import argparse
import importlib.util
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("flake_quarantine", HERE / "check-admin-ui-flake-quarantine.py")
assert _spec and _spec.loader
flake = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(flake)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    parser.add_argument("--fail-on-unquarantined", action="store_true")
    parser.add_argument("junit", nargs="+", type=pathlib.Path)
    args = parser.parse_args()
    sys.exit(flake.report(pathlib.Path(args.root) / flake.UI, args.junit, args.fail_on_unquarantined))


if __name__ == "__main__":
    main()
