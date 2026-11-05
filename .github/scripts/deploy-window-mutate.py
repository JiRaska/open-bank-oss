#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Recheck a selected deployment and request auto-merge through the existing train.

This helper only arms. The App-pinned merge train owns checks, reservations and merging.
GraphQL has no expected-head parameter; the last live read narrows, but cannot eliminate,
the check/arm race. A changed selection must wait for the next ordinary flush.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time


def load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


SCRIPTS = Path(__file__).resolve().parent
window = load_module("deploy_window", SCRIPTS / "deploy-window.py")
train = load_module("deploy_window_train", SCRIPTS / "merge-train.py")
SHA_RE = re.compile(r"[0-9a-f]{40}")


def gh(*args: str):
    result = subprocess.run(["gh", *args], capture_output=True, text=True, check=True)
    return json.loads(result.stdout)


def run(*args: str, input_text: str | None = None) -> str:
    return subprocess.run(args, input=input_text, capture_output=True, text=True,
                          check=True).stdout.strip()


def require_train_policy(repo: str) -> None:
    # Reuse the controller's effective-policy predicate, including its App integration pin.
    # The older strict-only named-ruleset check contradicts train ownership (ADR-0343).
    rules = gh("api", f"repos/{repo}/rules/branches/main")
    reason = train.ruleset_guard(rules, os.environ.get("GITOPS_APP_ID", ""))
    if reason:
        raise ValueError(f"merge train does not own merging: {reason}")


def selected_pr(pr, repo: str, number: int, head: str, base: str) -> tuple[str, str]:
    if not isinstance(pr, dict) or pr.get("number") != number:
        raise ValueError("selected deployment response is missing or malformed")
    if pr.get("state") != "open" or pr.get("draft") is not False:
        raise ValueError("selected deploy PR is no longer open and ready")
    labels = pr.get("labels")
    if not isinstance(labels, list) or any(not isinstance(label, dict) or
                                         not isinstance(label.get("name"), str) for label in labels):
        raise ValueError("selected deploy PR labels are unreadable")
    if any(label["name"] == "blocked" for label in labels):
        raise ValueError("selected deploy PR carries a blocked deployment hold")
    source, target = pr.get("head"), pr.get("base")
    if not isinstance(source, dict) or not isinstance(target, dict):
        raise ValueError("selected deploy PR head/base are unreadable")
    if source.get("sha") != head or target.get("sha") != base or target.get("ref") != "main":
        raise ValueError("selected deploy PR head or base changed; retry selection")
    if any(not isinstance(ref.get("repo"), dict) or ref["repo"].get("full_name") != repo
           for ref in (source, target)):
        raise ValueError("selected deploy PR is not from this repository")
    branch = source.get("ref")
    if not isinstance(branch, str) or not branch.startswith(window.DEPLOY_PREFIXES):
        raise ValueError("selected PR no longer has a deploy branch")
    node_id = pr.get("node_id")
    if not isinstance(node_id, str) or not node_id:
        raise ValueError("selected deploy PR node ID is unreadable")
    return node_id, branch


def verify_source(repo: str, number: int, branch: str, selected_main: str) -> None:
    run("git", "fetch", "--depth=100", "origin", "main")
    if run("git", "rev-parse", "origin/main") != selected_main:
        raise ValueError("main changed before source verification; retry selection")
    run("git", "reset", "--hard", "origin/main")
    # Preserve main's existing service-only freshness verifier. Admin UI retains its
    # existing producer/contract/hold checks, rather than reviving the old #12211 blanket hold.
    if branch.startswith(window.DEPLOY_PREFIXES[0]):
        diff = run("gh", "pr", "diff", str(number), "--repo", repo)
        run(sys.executable, str(SCRIPTS / "deploy-window.py"), "verify", "--root", ".",
            input_text=diff)


def live_main(repo: str) -> str:
    return gh("api", f"repos/{repo}/git/ref/heads/main")["object"]["sha"]


def reselect(number: int, head: str, base: str) -> bool:
    # The same fully paginated collector/decision as the initial selection: no capped
    # second inventory and no separate approximation of repair priority or manual expedition.
    with tempfile.TemporaryDirectory(prefix="deploy-window-arm-") as directory:
        root = Path(directory)
        run("bash", str(SCRIPTS / "deploy-window-inputs.sh"), directory)
        if (root / "main.sha").read_text(encoding="utf-8").strip() != base:
            raise ValueError("main changed during deploy selection; retry next flush")
        prs = json.loads((root / "prs.json").read_text(encoding="utf-8"))
        commits = json.loads((root / "commits.json").read_text(encoding="utf-8"))
        event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
        decision_args = (int(time.time()), window.last_deploy_by_kind(commits),
                         int(os.getenv("DEPLOY_WINDOW_SECONDS", "1800")),
                         window.manual_deploy_head(event))
        # The flush offers one candidate per deploy kind and the workflow falls through a
        # refused kind, so the arm is valid iff it is STILL one of the same candidates.
        def offered(candidates: list[dict]) -> bool:
            return any(c["number"] == number and c.get("head_sha") == head for c in candidates)

        choices = window.flush_candidates(prs, *decision_args)
        if not choices and any(p.get("repair_priority") and p.get("state", "open") == "open"
                               for p in prs):
            # A newly marked repair is an expected successful deferral, not a failure.
            # Prove it is the only selection difference; this counterfactual never arms.
            unprioritized = [{**p, "repair_priority": False} for p in prs]
            if offered(window.flush_candidates(unprioritized, *decision_args)):
                return False
        if not offered(choices):
            raise ValueError("deploy selection deferred or changed (window, repair, armed PR or head)")
        return True


def main(args: argparse.Namespace) -> None:
    repo = os.environ["GITHUB_REPOSITORY"]
    if (args.number < 1 or not SHA_RE.fullmatch(args.selected_main)
            or not SHA_RE.fullmatch(args.selected_head)):
        raise ValueError("selection did not capture exact main/head SHA")
    if live_main(repo) != args.selected_main:
        raise ValueError("main changed after selection; retry next flush")
    require_train_policy(repo)
    node_id, branch = selected_pr(gh("api", f"repos/{repo}/pulls/{args.number}"), repo,
                                  args.number, args.selected_head, args.selected_main)
    verify_source(repo, args.number, branch, args.selected_main)
    require_train_policy(repo)
    should_arm = reselect(args.number, args.selected_head, args.selected_main)
    if live_main(repo) != args.selected_main:
        raise ValueError("main changed before arming; retry next flush")
    latest = selected_pr(gh("api", f"repos/{repo}/pulls/{args.number}"), repo,
                         args.number, args.selected_head, args.selected_main)
    if latest != (node_id, branch):
        raise ValueError("selected deploy PR identity changed before arming")
    if not should_arm:
        print(f"::notice::repair priority is active; deploy PR #{args.number} remains unarmed")
        return
    gh("api", "graphql", "-f", "query=mutation($id: ID!) { enablePullRequestAutoMerge(input: {pullRequestId: $id, mergeMethod: SQUASH}) {pullRequest {number}}}",
       "-f", f"id={node_id}")
    print(f"auto-merge armed on deploy PR #{args.number}; merge remains owned by the train")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--number", type=int, required=True)
    parser.add_argument("--selected-main", required=True)
    parser.add_argument("--selected-head", required=True)
    try:
        main(parser.parse_args())
    except (KeyError, TypeError, ValueError, OSError, subprocess.CalledProcessError) as error:
        print(f"::error::{error}", file=sys.stderr)
        sys.exit(1)
