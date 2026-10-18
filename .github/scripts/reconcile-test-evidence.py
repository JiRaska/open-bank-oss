#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Which tested commit describes each RUNNING service image? (#13201)

admin-ui reads Test Intelligence evidence keyed by the commit in each running image tag
(`sandbox-<sha8>`). That commit is often one Services CI never built for the service:
auto-deploy rebuilds at the main tip, so an infra-only commit becomes the tag of every
redeployed service, and its own Services CI run built nothing. Keyed strictly, the store
would stay a miss for most of the fleet.

So for every running image this finds the NEWEST main-push Services CI run that retained the
service's run envelope at a commit that is an ancestor of (or equal to) the image commit, and
only uses it when `pact-version-tree-equivalent.sh` proves the two commits byte-identical in
every build input of that service. The first candidate that is DIFFERENT ends the search: the
service changed after it, so older runs describe older code. Unproven ⇒ nothing published,
never a borrowed verdict.

Prints one JSON object per publishable component:
  {"component", "image_sha", "tested_sha", "run_id", "artifact", "equivalence"}
and a summary on stderr. Uses `gh api` (GITHUB_TOKEN) and the local full-history checkout.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import subprocess
import sys
from pathlib import Path

TAG = re.compile(r"\b(openbank-[a-z0-9-]+):sandbox-([0-9a-f]{8})(?:-run\d+)?\b")
ARTIFACT_RETENTION_DAYS = 30


def sh(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(args, text=True, capture_output=True, check=check)


def gh_json(path: str) -> list | dict:
    out = sh("gh", "api", "--paginate", "--slurp", path).stdout
    return json.loads(out)


def running_images(root: Path) -> dict[str, str]:
    """component -> image sha8, for every built module whose image gitops deploys."""
    found: dict[str, set[str]] = {}
    for manifest in (root / "openbank-infra/gitops/components").rglob("*.yaml"):
        for name, sha8 in TAG.findall(manifest.read_text(encoding="utf-8", errors="replace")):
            if (root / name / "build.gradle.kts").is_file():
                found.setdefault(name, set()).add(sha8)
    ambiguous = sorted(name for name, shas in found.items() if len(shas) > 1)
    for name in ambiguous:
        print(f"skip {name}: more than one deployed tag {sorted(found[name])}", file=sys.stderr)
    return {name: next(iter(shas)) for name, shas in found.items() if len(shas) == 1}


def resolve(sha8: str) -> str | None:
    result = sh("git", "rev-parse", "--verify", "--quiet", f"{sha8}^{{commit}}", check=False)
    return result.stdout.strip() or None


def is_ancestor(older: str, newer: str) -> bool:
    return sh("git", "merge-base", "--is-ancestor", older, newer, check=False).returncode == 0


def tree_equivalent(root: Path, component: str, tested: str, image: str) -> bool:
    script = root / ".github/scripts/pact-version-tree-equivalent.sh"
    return sh("bash", str(script), component, tested, image, check=False).returncode == 0


def main_push_runs(repo: str) -> list[dict]:
    since = (dt.datetime.now(dt.timezone.utc) - dt.timedelta(days=ARTIFACT_RETENTION_DAYS)).date()
    pages = gh_json(f"repos/{repo}/actions/workflows/services-ci.yml/runs"
                    f"?branch=main&event=push&status=completed&per_page=100&created=>={since}")
    runs = [run for page in pages for run in page.get("workflow_runs", [])]
    return sorted(runs, key=lambda run: run["created_at"], reverse=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True)
    parser.add_argument("--root", default=".")
    parser.add_argument("--only", nargs="*", default=None, help="limit to these components")
    args = parser.parse_args()
    root = Path(args.root).resolve()

    images = running_images(root)
    if args.only:
        images = {name: sha for name, sha in images.items() if name in args.only}
    runs = main_push_runs(args.repo)
    artifacts_by_run: dict[int, dict[str, dict]] = {}

    def envelope_artifact(run: dict, component: str) -> dict | None:
        if run["id"] not in artifacts_by_run:
            pages = gh_json(f"repos/{args.repo}/actions/runs/{run['id']}/artifacts?per_page=100")
            artifacts_by_run[run["id"]] = {
                item["name"]: item for page in pages for item in page.get("artifacts", []) if not item["expired"]}
        prefix = f"test-intelligence-run-{component}-{run['id']}-a"
        named = [item for name, item in artifacts_by_run[run["id"]].items() if name.startswith(prefix)]
        # the latest attempt is the one that describes the run's final verdict
        return max(named, key=lambda item: int(item["name"].rsplit("-a", 1)[1]), default=None)

    published = 0
    for component, sha8 in sorted(images.items()):
        image = resolve(sha8)
        if not image:
            print(f"skip {component}: image commit {sha8} not in history", file=sys.stderr)
            continue
        verdict = "no tested commit with a retained envelope"
        for run in runs:
            tested = run["head_sha"]
            if tested != image and not is_ancestor(tested, image):
                continue
            artifact = envelope_artifact(run, component)
            if artifact is None:
                continue
            if tested == image:
                equivalence = "identical"
            elif tree_equivalent(root, component, tested, image):
                equivalence = "tree-equivalent"
            else:
                verdict = f"newest tested commit {tested[:8]} differs in build inputs from {image[:8]}"
                break
            print(json.dumps({"component": component, "image_sha": image, "tested_sha": tested,
                              "run_id": run["id"], "artifact": artifact["name"], "equivalence": equivalence}))
            published += 1
            verdict = ""
            break
        if verdict:
            print(f"skip {component}: {verdict}", file=sys.stderr)
    print(f"reconcile: {published}/{len(images)} running components have provable evidence", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
