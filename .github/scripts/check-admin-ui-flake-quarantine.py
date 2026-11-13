#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""ADR-0370 U2: measured flake and an expiring quarantine for the Admin UI e2e suite.

Two modes.

Gate (default): `openbank-admin-ui/e2e/flaky-quarantine.json` must be well formed. Every entry
names a test that still exists, an owner, a tracking issue and an expiry date no later than
MAX_DAYS ahead. An entry past its expiry fails, so a quarantine cannot become a permanent
skip: the test is either fixed and removed from the list, or deleted.

Report (`--junit FILE...`): read Playwright JUnit written with `includeRetries: true`. A
`<testcase>` carrying `<flakyFailure>` failed and then passed on retry, which the suite's
`retries: 1` otherwise absorbs silently. Each one is printed as a `::warning`, listed in the
step summary, and counted as `FLAKY=<n>`. With `--fail-on-unquarantined` a flaky test that is
not quarantined fails the step. CI runs it without that flag (advisory) until the flake rate
has a measured baseline.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import pathlib
import re
import sys
import tempfile
# Input is this repo's own Playwright JUnit from the same job, never third-party XML.
import xml.etree.ElementTree as ET  # noqa: S405

UI = "openbank-admin-ui"
QUARANTINE = "e2e/flaky-quarantine.json"
MAX_DAYS = 30
REQUIRED = ("test", "owner", "issue", "expires", "reason")


def load(ui: pathlib.Path) -> list[dict]:
    return json.loads((ui / QUARANTINE).read_text(encoding="utf-8"))["quarantine"]


def gate_findings(ui: pathlib.Path, today: dt.date) -> list[str]:
    out: list[str] = []
    for i, entry in enumerate(load(ui)):
        where = f"{QUARANTINE}[{i}]"
        missing = [k for k in REQUIRED if not str(entry.get(k, "")).strip()]
        if missing:
            out.append(f"{where}: missing {', '.join(missing)}")
            continue
        file, sep, title = entry["test"].partition(" › ")
        spec = ui / "e2e" / file
        if not sep or not spec.is_file():
            out.append(f"{where}: test must be '<spec file> › <title>' naming an existing spec, got {entry['test']!r}")
        elif title not in spec.read_text(encoding="utf-8"):
            out.append(f"{where}: no test titled {title!r} in {file}; delete the entry")
        if not re.fullmatch(r"#\d+", entry["issue"]):
            out.append(f"{where}: issue must be '#<number>'")
        try:
            expires = dt.date.fromisoformat(entry["expires"])
        except ValueError:
            out.append(f"{where}: expires must be YYYY-MM-DD")
            continue
        if expires < today:
            out.append(f"{where}: quarantine of {entry['test']!r} expired {expires}; fix the test or delete it")
        elif expires > today + dt.timedelta(days=MAX_DAYS):
            out.append(f"{where}: expires {expires} is more than {MAX_DAYS} days ahead")
    return out


def flaky_tests(junit_files: list[pathlib.Path]) -> list[str]:
    found: list[str] = []
    for f in junit_files:
        if not f.is_file():
            continue
        for case in ET.parse(f).getroot().iter("testcase"):
            if case.find("flakyFailure") is not None:
                found.append(f"{case.get('classname')} › {case.get('name')}")
    return sorted(set(found))


def report(ui: pathlib.Path, junit_files: list[pathlib.Path], fail_on_unquarantined: bool) -> int:
    existing = [f for f in junit_files if f.is_file()]
    if not existing:
        print("SUBJECTS=UNRESOLVED")
        print("admin-ui-flake-report: no JUnit file found — nothing was measured")
        return 0
    quarantined = {e["test"] for e in load(ui)}
    flaky = flaky_tests(existing)
    fresh = [t for t in flaky if t not in quarantined]
    for t in flaky:
        level = "notice" if t in quarantined else "warning"
        print(f"::{level} title=Flaky e2e test::{t} passed only on retry")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as fh:
            fh.write(f"### Admin UI e2e flake (ADR-0370 U2)\n\n{len(flaky)} test(s) passed only on retry")
            fh.write(f", {len(fresh)} not quarantined.\n\n" + "".join(f"- `{t}`\n" for t in flaky))
    print(f"FLAKY={len(flaky)}")
    print(f"admin-ui-flake-report: {len(flaky)} flaky, {len(fresh)} not quarantined")
    return 1 if fail_on_unquarantined and fresh else 0


def self_test() -> None:
    today = dt.date(2026, 11, 11)
    with tempfile.TemporaryDirectory() as tmp:
        ui = pathlib.Path(tmp)
        (ui / "e2e").mkdir()
        (ui / "e2e/a.spec.ts").write_text("test('loads the page', async () => {})\n")

        def write(entries: list[dict]) -> None:
            (ui / QUARANTINE).write_text(json.dumps({"quarantine": entries}))

        good = {"test": "a.spec.ts › loads the page", "owner": "x", "issue": "#1", "expires": "2026-11-20", "reason": "r"}
        write([good])
        assert gate_findings(ui, today) == [], gate_findings(ui, today)
        write([{**good, "expires": "2026-11-10"}])
        assert "expired" in gate_findings(ui, today)[0]
        write([{**good, "expires": "2027-01-01"}])
        assert "days ahead" in gate_findings(ui, today)[0]
        write([{**good, "test": "a.spec.ts › gone"}])
        assert "no test titled" in gate_findings(ui, today)[0]
        write([{**good, "owner": ""}])
        assert "missing owner" in gate_findings(ui, today)[0]

        junit = ui / "j.xml"
        junit.write_text(
            '<testsuites><testsuite><testcase name="loads the page" classname="a.spec.ts">'
            '<flakyFailure message="m"/></testcase><testcase name="stable" classname="a.spec.ts"/>'
            "</testsuite></testsuites>"
        )
        assert flaky_tests([junit]) == ["a.spec.ts › loads the page"]
        write([])
        assert report(ui, [junit], fail_on_unquarantined=True) == 1
        write([good])
        assert report(ui, [junit], fail_on_unquarantined=True) == 0
    print("self-test: ok")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--junit", nargs="*", type=pathlib.Path)
    parser.add_argument("--fail-on-unquarantined", action="store_true")
    parser.add_argument("--today", type=dt.date.fromisoformat, default=dt.date.today())
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    ui = pathlib.Path(args.root) / UI
    if args.junit is not None:
        sys.exit(report(ui, args.junit, args.fail_on_unquarantined))
    out = gate_findings(ui, args.today)
    for line in out:
        print(f"::error title=admin-ui-flake-quarantine::{line}")
    entries = len(load(ui))
    # Zero entries is the healthy state, not a vacuous run: the subject is the list itself.
    print(f"SUBJECTS={max(entries, 1)}")
    print(f"admin-ui-flake-quarantine: {entries} quarantined test(s), {len(out)} finding(s)")
    sys.exit(1 if out else 0)


if __name__ == "__main__":
    main()
