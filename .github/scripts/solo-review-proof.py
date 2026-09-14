#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Verify owner-anchored AI review evidence. This does not certify CI or authorize merge.

The trust anchor is a repository variable set by the owner after reviewing the policy
commit, never a value supplied by the pull request. Reruns are deliberately rejected:
GitHub's review-history API does not bind an approval to a workflow attempt.
"""
import argparse
import hashlib
import io
import json
import re
import subprocess
import sys
import zipfile
from urllib.parse import quote

CONTEXT = "solo-review-admission"
WORKFLOW = ".github/workflows/agent-review.yml"
ENVIRONMENT = "solo-review-owner"
SHA = re.compile(r"[0-9a-f]{40}\Z")
MAX_EVIDENCE_BYTES = 2_000_000


def require(condition, reason):
    if not condition:
        raise ValueError(reason)


def gh(path, *, binary=False):
    result = subprocess.run(["gh", "api", path], capture_output=True, timeout=45)
    require(result.returncode == 0, f"GitHub read failed: {path}")
    return result.stdout if binary else json.loads(result.stdout)


def pages(path, key=None):
    out = []
    for page in range(1, 101):
        data = gh(f"{path}{'&' if '?' in path else '?'}per_page=100&page={page}")
        rows = data[key] if key else data
        require(isinstance(rows, list), "invalid paginated response")
        out.extend(rows)
        if len(rows) < 100:
            return out
    raise ValueError("pagination exceeded safety bound; no complete snapshot")


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def validate_environment(environment, owner_id):
    require(environment.get("name") == ENVIRONMENT, "wrong acceptance environment")
    require(environment.get("can_admins_bypass") is False, "environment permits bypass or omits its policy")
    rules = [r for r in environment.get("protection_rules", []) if r.get("type") == "required_reviewers"]
    require(len(rules) == 1, "owner approval protection missing or ambiguous")
    reviewers = rules[0].get("reviewers", [])
    require(len(reviewers) == 1 and reviewers[0].get("type") == "User"
            and reviewers[0].get("reviewer", {}).get("id") == owner_id,
            "environment must require the repository owner")


def validate_reports(bundle):
    require(bundle.get("schema") == 1, "unsupported review evidence")
    subject = bundle["subject"]
    for field in ("head", "base", "merge_base", "policy_sha"):
        require(isinstance(subject.get(field), str) and SHA.fullmatch(subject[field]), f"invalid {field}")
    files = subject["files"]
    require(isinstance(files, list) and files and len(set(files)) == len(files)
            and all(isinstance(p, str) and p for p in files), "incomplete changed-file manifest")
    require(subject.get("complete") is True, "review input was incomplete")
    require(type(subject.get("protected")) is bool, "missing protected-path classification")
    require(re.fullmatch(r"[0-9a-f]{64}", subject.get("input_digest", "")), "missing review input identity")
    reports = bundle["reports"]
    require(len(reports) == 2, "exactly two independent reports required")
    require({r.get("slot") for r in reports} == {"correctness", "security"}, "duplicate or missing reviewer slot")
    require(len({r.get("session_id") for r in reports}) == 2, "review sessions were reused")
    require(len({r.get("model") for r in reports}) == 2, "review models must differ")
    for report in reports:
        require(report.get("subject_digest") == digest(subject), "report belongs to another subject")
        require(isinstance(report.get("session_id"), str) and report["session_id"], "missing session identity")
        require(isinstance(report.get("model"), str) and report["model"], "missing observed model identity")
        require(type(report.get("tool_uses")) is int and report["tool_uses"] == 0, "reviewer invoked tools or omitted accounting")
        require(report.get("driver_success") is True, "review driver did not finish")
        response = report["response"]
        require(response.get("verdict") == "NO_FINDINGS" and response.get("findings") == [],
                "findings or incomplete verdict require a new review")
        count = report.get("structured_output_uses", 0)
        require(type(count) is int and count >= 0, "invalid output attempt count")
        attempts = report.get("structured_output_attempts")
        if attempts is None:
            require(count <= 1, "multiple outputs omitted their attempt history")
        else:
            require(isinstance(attempts, list) and len(attempts) == count, "incomplete output attempt history")
            require(all(isinstance(a, dict) and a.get("verdict") == "NO_FINDINGS"
                        and a.get("findings") == [] for a in attempts),
                    "earlier output contains findings or an unresolved verdict")
            require(not attempts or digest(attempts[-1]) == digest(response), "final output differs from attempt history")
        coverage = response.get("coverage", [])
        require(len(coverage) == len(files) and {c.get("path") for c in coverage} == set(files),
                "review omitted changed files")
        require(all(isinstance(c.get("analysis"), str) and len(c["analysis"].strip()) >= 40 for c in coverage),
                "review lacks per-file reasoning")


def validate_run(run, workflow, anchor, repo):
    require(run.get("repository", {}).get("full_name") == repo, "wrong repository")
    require(run.get("event") == "workflow_dispatch", "untrusted workflow event")
    require(run.get("path", "").split("@")[0] == WORKFLOW and workflow.get("path") == WORKFLOW
            and run.get("workflow_id") == workflow.get("id"), "wrong review workflow")
    require(SHA.fullmatch(anchor or "") and run.get("head_sha") == anchor, "policy source differs from owner anchor")
    require(run.get("run_attempt") == 1, "rerun approval provenance is ambiguous; dispatch a fresh run")
    require(run.get("status") == "completed" and run.get("conclusion") == "success", "review run not successful")


def validate_subject(subject, pull, repo, anchor):
    require(subject.get("repo") == repo and subject.get("pr") == pull.get("number"), "wrong PR")
    require(pull.get("state") == "open", "PR is no longer open")
    require(subject.get("head") == pull.get("head", {}).get("sha"), "PR head changed after review")
    require(subject.get("base_ref") == pull.get("base", {}).get("ref"), "PR base branch changed")
    require(subject.get("policy_sha") == anchor, "review policy changed")


POLICY_INPUTS = (".github/scripts/check-agent-pr-guard.py", "openbank-libs/governance/rules.yaml")


def live_base_sha(repo, base_ref):
    require(isinstance(base_ref, str) and base_ref, "missing base branch")
    branch = gh(f"repos/{repo}/branches/{quote(base_ref, safe='')}")
    sha = (branch.get("commit") or {}).get("sha") if isinstance(branch, dict) else None
    require(isinstance(sha, str) and SHA.fullmatch(sha), "missing live base commit")
    return sha


def validate_policy_snapshot(repo, base_ref, anchor):
    """A pinned classifier must not silently lag changes to the base policy."""
    base = live_base_sha(repo, base_ref)
    require(SHA.fullmatch(anchor or ""), "invalid policy snapshot")
    for path in POLICY_INPUTS:
        identities = []
        for revision in (base, anchor):
            entry = gh(f"repos/{repo}/contents/{path}?ref={revision}")
            require(isinstance(entry, dict) and entry.get("type") == "file" and SHA.fullmatch(entry.get("sha", "")),
                    "missing classification policy file")
            identities.append(entry["sha"])
        require(identities[0] == identities[1],
                f"classification policy changed on base: {path}; reviewed re-anchor required")
    return base


def read_bundle(archive):
    require(len(archive) <= MAX_EVIDENCE_BYTES, "oversized evidence archive")
    with zipfile.ZipFile(io.BytesIO(archive)) as z:
        require(z.namelist() == ["admission.json"], "unexpected evidence archive members")
        require(z.infolist()[0].file_size <= MAX_EVIDENCE_BYTES, "oversized expanded evidence")
        return json.loads(z.read("admission.json"))


def verify(repo, pr, run_id, *, protected):
    prefix = f"repos/{repo}"
    anchor = gh(f"{prefix}/actions/variables/SOLO_REVIEW_POLICY_SHA")["value"]
    run = gh(f"{prefix}/actions/runs/{run_id}")
    workflow = gh(f"{prefix}/actions/workflows/agent-review.yml")
    validate_run(run, workflow, anchor, repo)
    artifacts = pages(f"{prefix}/actions/runs/{run_id}/artifacts", "artifacts")
    matches = [a for a in artifacts if a.get("name") == "solo-review-admission-1" and not a.get("expired")]
    require(len(matches) == 1, "missing or ambiguous final evidence")
    bundle = read_bundle(gh(f"{prefix}/actions/artifacts/{matches[0]['id']}/zip", binary=True))
    require(bundle.get("run_id") == run_id and bundle.get("run_attempt") == 1, "evidence run mismatch")
    validate_reports(bundle)
    pull = gh(f"{prefix}/pulls/{pr}")
    validate_subject(bundle["subject"], pull, repo, anchor)
    policy_base = validate_policy_snapshot(repo, pull["base"]["ref"], anchor)
    files = pages(f"{prefix}/pulls/{pr}/files")
    require(len(files) == pull.get("changed_files"), "GitHub file list incomplete")
    # The producer disables rename detection and includes both sides of a rename.
    paths = {f["filename"] for f in files} | {f["previous_filename"] for f in files if "previous_filename" in f}
    require(paths == set(bundle["subject"]["files"]), "reviewed file manifest differs from current PR")
    comparison = gh(f"{prefix}/compare/{pull['base']['sha']}...{pull['head']['sha']}")
    require(comparison.get("merge_base_commit", {}).get("sha") == bundle["subject"]["merge_base"],
            "PR diff base changed after review")
    jobs = pages(f"{prefix}/actions/runs/{run_id}/attempts/1/jobs", "jobs")
    expected = {"solo-correctness", "solo-security", "solo-seal"}
    if protected or bundle["subject"]["protected"]:
        expected.add("solo-owner-acceptance")
        owner_id = gh(prefix)["owner"]["id"]
        environment = gh(f"{prefix}/environments/{ENVIRONMENT}")
        validate_environment(environment, owner_id)
        approvals = gh(f"{prefix}/actions/runs/{run_id}/approvals")
        require(any(a.get("state") == "approved" and a.get("user", {}).get("id") == owner_id
                    and any(e.get("id") == environment["id"] for e in a.get("environments", []))
                    for a in approvals), "owner environment acceptance missing")
        require(bundle.get("owner_accepted") is True, "producer did not record protected acceptance")
    for name in expected:
        found = [j for j in jobs if j.get("name", "").split(" / ")[-1] == name]
        require(len(found) == 1 and found[0].get("conclusion") == "success", f"job {name} did not succeed exactly once")
    # Re-read mutable inputs after all evidence reads. Any error is unresolved, never clean.
    require(gh(f"{prefix}/actions/variables/SOLO_REVIEW_POLICY_SHA")["value"] == anchor, "anchor changed during verification")
    final_pull = gh(f"{prefix}/pulls/{pr}")
    validate_subject(bundle["subject"], final_pull, repo, anchor)
    require(final_pull["base"]["sha"] == pull["base"]["sha"], "base changed during verification; retry the snapshot")
    require(live_base_sha(repo, final_pull["base"]["ref"]) == policy_base, "live base changed during verification")
    validate_run(gh(f"{prefix}/actions/runs/{run_id}"), workflow, anchor, repo)
    if protected or bundle["subject"]["protected"]:
        require(gh(f"{prefix}/environments/{ENVIRONMENT}") == environment,
                "acceptance environment changed during verification")
    return bundle


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", required=True)
    parser.add_argument("--pr", required=True, type=int)
    parser.add_argument("--run", required=True, type=int)
    parser.add_argument("--protected", action="store_true")
    args = parser.parse_args()
    try:
        verify(args.repo, args.pr, args.run, protected=args.protected)
    except (ValueError, KeyError, TypeError, OSError, subprocess.TimeoutExpired, zipfile.BadZipFile) as error:
        print(f"REVIEW UNRESOLVED: {error}", file=sys.stderr)
        return 2
    print("Review evidence verified; CI, threat models and merge policy must still pass.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
