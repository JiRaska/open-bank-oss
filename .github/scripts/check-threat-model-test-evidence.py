#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Did the test a threat model cites actually RUN GREEN? (issue #13171, ADR-0144 advisory)

`check-threat-model-claims.py` (threat-model-claims-resolve) accepts a cited test class's EXISTENCE
as proof. A class can exist while excluded from its CI task, skipped for a month, or red on main.
This reads the newest Test Intelligence run envelope per component and reports UNVERIFIED when a
cited test class (or the tests declaring `verifies:<scope>/<row-id>`) is absent from it or not
passed. The logic lives beside the claims parser (`load_evidence`, `evidence_findings`); this
entrypoint only decides where evidence comes from and whether findings fail.

  --evidence PATH     envelope file or directory (repeatable; requires --run-metadata)
  --fetch-evidence    newest scheduled main Services CI envelopes via `gh` (best effort: ANY
                      failure is UNRESOLVED — not a pass, not a failure)
  --pr-base SHA       with --fetch-evidence: skip the fetch if this diff touches nothing the audit
                      reads differently. The envelopes come from the newest scheduled main run, so
                      an unrelated PR would get main's answer at about 90 gh calls per CI run. A
                      failed diff or an empty base fetches as before.
  --enforce           make UNVERIFIED fail. Advisory until rules.yaml: threat_model_test_evidence
                      reaches its target_enforce_date.
"""
from __future__ import annotations

import argparse
import importlib.util
import pathlib
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
REPO = HERE.parent.parent


# What can change this audit's answer on a PR. Test sources are absent on purpose: the envelopes predate
# the PR, so editing a test cannot change what they say about it.
RELEVANT = ("docs/threat-models/", ".github/scripts/check-threat-model-")


def fetch_needed(base: str, root: pathlib.Path, run=subprocess.run) -> bool:
    if not base:
        return True
    try:
        diff = run(["git", "-C", str(root), "diff", "--name-only", f"{base}...HEAD"],
                   capture_output=True, text=True, timeout=60, check=True).stdout
    except (OSError, subprocess.SubprocessError):
        return True
    return any(line.startswith(RELEVANT) for line in diff.splitlines())


def fetch_needed_self_test() -> int:
    class Out:
        def __init__(self, stdout):
            self.stdout = stdout

    def diff(text):
        return lambda *a, **k: Out(text)

    def broken(*a, **k):
        raise subprocess.CalledProcessError(128, "git")

    root = pathlib.Path(".")
    cases = [
        ("no base fetches", fetch_needed("", root, diff("")), True),
        ("unrelated diff skips", fetch_needed("b", root, diff("openbank-x/src/main/A.kt\n")), False),
        ("empty diff skips", fetch_needed("b", root, diff("")), False),
        ("test source skips", fetch_needed("b", root, diff("openbank-x/src/test/ATest.kt\n")), False),
        ("threat model fetches", fetch_needed("b", root, diff("a.md\ndocs/threat-models/x.md\n")), True),
        ("this gate fetches", fetch_needed("b", root, diff(".github/scripts/check-threat-model-claims.py\n")), True),
        ("failed diff fetches", fetch_needed("b", root, broken), True),
    ]
    bad = [name for name, got, want in cases if got != want]
    print(f"fetch_needed self-test: {len(cases)} cases, failed={bad}")
    return 1 if bad else 0


def _claims_module():
    spec = importlib.util.spec_from_file_location("tmclaims", HERE / "check-threat-model-claims.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=str(REPO))
    ap.add_argument("--evidence", action="append", default=[], metavar="PATH")
    ap.add_argument("--fetch-evidence", action="store_true")
    ap.add_argument("--pr-base", default="")
    ap.add_argument("--run-metadata", type=pathlib.Path, help="independently obtained trusted run API JSON for offline evidence")
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    a = ap.parse_args()
    m = _claims_module()
    if a.self_test:
        # the evidence cases live in the claims self-test, beside the parser
        return m.self_test() or fetch_needed_self_test()
    root = pathlib.Path(a.root).resolve()
    services = m.subjects_all(root)
    print(f"SUBJECTS={len(services)}")
    if a.fetch_evidence and not fetch_needed(a.pr_base, root):
        print("::notice title=test evidence::NOT CHECKED on this PR — it touches no threat model and the "
              "evidence is main's, so main's own run reports the same answer. Not a pass.")
        return 0
    paths = [pathlib.Path(x) for x in a.evidence]
    trusted_run = None
    if a.run_metadata:
        import json
        try:
            trusted_run = json.loads(a.run_metadata.read_text())
        except (OSError, ValueError):
            pass
    with tempfile.TemporaryDirectory() as scratch:
        if a.fetch_evidence:
            fetched, trusted_run = m.fetch_latest_evidence(pathlib.Path(scratch))
            paths += fetched
        evidence = m.load_evidence(paths, trusted_run)
        if not evidence:
            print("::notice title=test evidence::UNRESOLVED — no Test Intelligence envelope could be read; "
                  "cited tests were not checked for a green run. Not a pass and not a failure.")
            return 0
        findings, judged = m.evidence_findings(root, services, m.Corpus(root), evidence)
    print(f"test-evidence audit: {len(evidence)} component envelope(s), {judged} cited test claim(s) "
          f"judged, {len(findings)} UNVERIFIED")
    level = "error" if a.enforce else "warning"
    for kind, svc, rid, detail in findings:
        print(f"::{level} file=docs/threat-models/{svc}.md,title=threat-model claim ({kind})::{svc} {rid}: {detail}")
    return 1 if (findings and a.enforce) else 0


if __name__ == "__main__":
    sys.exit(main())
