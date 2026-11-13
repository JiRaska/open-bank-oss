#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Issue kind check — every open issue carries EXACTLY ONE kind label (rules.yaml: issues.kinds).

GitHub's native issue types exist only for organisation-owned repositories; this one is
user-owned, so the kind of an issue can only be carried by a label. A label has no
cardinality: an issue can carry none, or two, and nothing in GitHub objects. Measured
2026-11-03 over 397 open issues: 242 carried no kind and 27 carried two — every one of the
125 label-less issues had been opened through `gh issue create` without `--label`, which a
template (the only thing that applied a kind) never sees.

This script makes that state VISIBLE instead of silent: an issue with zero kinds (unless a
generator owns its lifecycle) or with more than one gets the `needs-kind` label, and loses it
as soon as the kinds are fixed. `label:needs-kind` is then the queryable debt.

Modes:
  --selftest                 falsify the classifier against known-positive and known-negative cases
  --labels a,b,c             print the verdict for one label set (debugging)
  --event <path>             act on one issue from a GitHub `issues` event payload
  --sweep                    act on every open issue (schedule / workflow_dispatch)
  --dry-run                  with --event/--sweep: report, mutate nothing

Classification reads the kind set from rules.yaml — the list is never restated here.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

RULES = Path(__file__).resolve().parents[2] / "openbank-libs" / "governance" / "rules.yaml"

OK, MISSING, AMBIGUOUS, EXEMPT = "ok", "missing", "ambiguous", "exempt"


def load_config(path: Path = RULES) -> dict:
    import yaml  # PyYAML; the workflow installs it hash-pinned

    kinds = yaml.safe_load(path.read_text())["issues"]["kinds"]
    return {
        "labels": set(kinds["labels"]),
        "flag": kinds["flag_label"],
        "generator_owned": set(kinds["generator_owned"]),
    }


def classify(labels: set[str], cfg: dict) -> tuple[str, list[str]]:
    found = sorted(labels & cfg["labels"])
    if len(found) == 1:
        return OK, found
    if len(found) > 1:
        # Owned or not, two kinds is a contradiction someone must resolve.
        return AMBIGUOUS, found
    if labels & cfg["generator_owned"]:
        # The generator that opened it also closes it; a kind would carry no decision.
        return EXEMPT, found
    return MISSING, found


def needs_flag(verdict: str) -> bool:
    return verdict in (MISSING, AMBIGUOUS)


def selftest() -> int:
    cfg = {
        "labels": {"bug", "enhancement", "governance", "sweep", "tech-debt"},
        "flag": "needs-kind",
        "generator_owned": {"deploy-drift", "fleet-health"},
    }
    cases = [
        # (labels, expected verdict) — each RED case is a known-positive the check MUST flag.
        (set(), MISSING),
        ({"triage"}, MISSING),
        ({"security", "money-path"}, MISSING),
        ({"bug", "governance"}, AMBIGUOUS),
        ({"enhancement", "governance", "fleet-health"}, AMBIGUOUS),
        ({"needs-kind"}, MISSING),
        ({"bug"}, OK),
        ({"tech-debt", "compliance:gdpr"}, OK),
        ({"bug", "needs-kind"}, OK),
        ({"deploy-drift", "fleet-health"}, EXEMPT),
        ({"fleet-health", "bug"}, OK),
    ]
    failed = 0
    for labels, want in cases:
        got, _ = classify(labels, cfg)
        mark = "ok  " if got == want else "FAIL"
        failed += got != want
        print(f"{mark} {sorted(labels)} -> {got} (want {want})")
    flagged = sum(needs_flag(classify(l, cfg)[0]) for l, _ in cases)
    if flagged == 0:
        print("FAIL the classifier flagged nothing — a check that cannot go red is decoration")
        failed += 1
    # The real config must parse and carry what the classifier depends on.
    real = load_config()
    if not real["labels"] or real["flag"] in real["labels"]:
        print("FAIL rules.yaml issues.kinds is empty or lists the flag label as a kind")
        failed += 1
    print(f"SUBJECTS={len(cases)}")
    print(f"selftest: {len(cases)} cases, {failed} failed")
    return 1 if failed else 0


class GitHub:
    def __init__(self, repo: str, token: str, dry_run: bool):
        self.base = f"https://api.github.com/repos/{repo}"
        self.token = token
        self.dry_run = dry_run

    def _call(self, method: str, path: str, body=None):
        req = urllib.request.Request(
            self.base + path,
            method=method,
            data=json.dumps(body).encode() if body is not None else None,
            headers={
                "Authorization": f"Bearer {self.token}",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                raw = resp.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            # Removing a label the issue no longer carries is the desired end state.
            if method == "DELETE" and e.code == 404:
                return None
            raise

    def open_issues(self):
        page = 1
        while True:
            batch = self._call("GET", f"/issues?state=open&per_page=100&page={page}")
            if not batch:
                return
            for item in batch:
                if "pull_request" not in item:  # the issues endpoint also returns PRs
                    yield item
            page += 1

    def set_flag(self, number: int, flag: str, want: bool, have: bool) -> str:
        if want == have:
            return "unchanged"
        if self.dry_run:
            return "would add" if want else "would remove"
        if want:
            self._call("POST", f"/issues/{number}/labels", {"labels": [flag]})
            return "added"
        self._call("DELETE", f"/issues/{number}/labels/{urllib.parse.quote(flag, safe='')}")
        return "removed"


def act(gh: GitHub, issue: dict, cfg: dict) -> str:
    labels = {l["name"] for l in issue.get("labels", [])}
    verdict, kinds = classify(labels, cfg)
    outcome = gh.set_flag(issue["number"], cfg["flag"], needs_flag(verdict), cfg["flag"] in labels)
    print(f"#{issue['number']}: {verdict} {kinds} -> {cfg['flag']} {outcome}")
    return verdict


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--selftest", action="store_true")
    mode.add_argument("--labels")
    mode.add_argument("--event")
    mode.add_argument("--sweep", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    if args.selftest:
        return selftest()
    cfg = load_config()
    if args.labels is not None:
        verdict, kinds = classify({l for l in args.labels.split(",") if l}, cfg)
        print(json.dumps({"verdict": verdict, "kinds": kinds, "flag": needs_flag(verdict)}))
        return 0

    gh = GitHub(os.environ["GH_REPO"], os.environ["GH_TOKEN"], args.dry_run)
    if args.event:
        issue = json.loads(Path(args.event).read_text())["issue"]
        if issue.get("state") != "open":
            print(f"#{issue['number']}: closed — no verdict")
            return 0
        act(gh, issue, cfg)
        return 0

    tally: dict[str, int] = {}
    for issue in gh.open_issues():
        v = act(gh, issue, cfg)
        tally[v] = tally.get(v, 0) + 1
    print("sweep:", json.dumps(tally, sort_keys=True))
    if not tally:
        # Zero open issues in this repo means the listing failed, not that the backlog is clean.
        print("::error::sweep saw no open issues — the listing is broken, not the backlog clean")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
