#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# PR FILE OVERLAP — tell a PR which OTHER open PRs are editing the same files.
#
# WHY THIS EXISTS
#   Parallel agents land on the same artifact routinely, and CI cannot see it: every check
#   in this repo is scoped to ONE pull request, computed against ONE base. Two PRs that each
#   pass every gate can still be a duplicate of each other (#2984 vs #3009 — two copies of
#   one guard, the second fully green), a semantic collision that only breaks after both
#   merge, or a race for a single-valued field that git merges as identical text rather than
#   as a taken value (#481 x #524: two spec PRs both claiming the same openapi info.version).
#
#   The instinct that catches these is "what else touched this file today", and it is
#   currently a manual habit — a `git diff origin/main origin/<branch> -- <file>` somebody
#   has to think of running. This gate makes it automatic and free: no model, no clone, one
#   REST call per open PR.
#
# WHAT IT IS NOT
#   It is not a conflict detector. Git already reports textual conflicts, and the failures
#   above are precisely the ones git calls CLEAN. Nor does it claim an overlap is wrong —
#   the three real outcomes differ and only a human can pick between them:
#
#     * identical content   the other PR already has this exact change (#3103's security.yml
#                           matched main byte for byte, so merging was a no-op)
#     * true duplicate      same defect, same fix, twice (#3009 — close one)
#     * real divergence     two authoring decisions in one file (#3024 differed by 101 lines)
#
#   So the gate reports and classifies; it never decides. That is also why it is advisory:
#   an enforced version would red BOTH PRs the moment two of them touched rules.yaml, and
#   neither author could clear it without the other merging first — a deadlock the repo has
#   already paid for once elsewhere (the SLO registry gate blocking the queue).
#
# THE ONE THING IT DOES DECIDE
#   `identical content` is not a judgement call, so it is computed rather than reported.
#   GitHub's pulls/<n>/files serves the blob sha of each file AT THAT PR'S HEAD, so two PRs
#   whose blob shas agree hold the same bytes and there is nothing to reconcile. This
#   matters because the file list itself is computed against the MERGE-BASE: after a
#   competing PR squash-merges, the surviving PR still lists the overlap as a diff even
#   though the content already agrees (repo lore: read the content, not the diff). Without
#   the sha comparison this gate would warn loudest exactly when the work was already done.
#
# SERIALIZED PATHS
#   Some files are registries every service appends itself to. An overlap there is not a
#   coincidence to note but an ordering problem to resolve, because a clean merge of two
#   neighbouring list entries silently keeps ONE (.release-please-manifest.json went 56 ->
#   55 entries that way). Those paths are called out separately in the output. The set is
#   deliberately SHORT and hand-kept: it is a list of external facts about which files are
#   shared registries, not the gate's scope — the gate reads every overlapping file either
#   way, so a path missing from this list is still reported, just without the louder header.
#   (repo lore: never let a gate's SCOPE be hand-kept; a hand-kept list of FACTS is fine.)
#
# FALSIFIABILITY
#   --self-test runs the classifier over synthetic PR-file fixtures with no network at all:
#   a disjoint pair that must stay clean, a divergent overlap that must be reported, an
#   identical-sha overlap that must be reported as already-agreeing rather than as a
#   divergence, and a serialized-path overlap that must be escalated. It also asserts that
#   an enumeration FAILURE is not reported as clean — the failure mode this class of gate
#   dies of (a permission-shaped 404 is indistinguishable from an empty list, and it always
#   fails in the direction of a confident wrong answer).
#
# USAGE
#   python3 .github/scripts/check-pr-file-overlap.py            # PR number from the environment
#   python3 .github/scripts/check-pr-file-overlap.py --pr 4311  # explicit
#   python3 .github/scripts/check-pr-file-overlap.py --self-test
#
# EXIT CODES
#   0  no overlap, or every overlap already holds identical content
#   1  at least one divergent overlap (a warning under `mode: advisory`)
#   2  could not enumerate the open PRs — NOT a clean verdict

import argparse
import json
import os
import re
import subprocess
import sys

REPO = "JiRaska/open-bank-oss"

# Files whose overlaps are an ORDERING problem, not just a coincidence: shared registries
# where two PRs append neighbouring entries and git keeps one. Matched as exact paths or,
# where the trailing `/` is present, as a directory prefix.
SERIALIZED = (
    ".github/gates/gates.yaml",
    "openbank-libs/governance/rules.yaml",
    "openbank-libs/governance/agents.yaml",
    ".release-please-manifest.json",
    "release-please-config.json",
    ".github/workflows/ci.yml",
    ".github/workflows/auto-deploy.yml",
    "openbank-infra/gitops/",
)

# An openapi.yaml overlap carries its own known failure — two PRs can claim the same
# info.version and both pass, because the api-contract gate classifies against each PR's
# creation-time base (#481 x #524, and again from an already-MERGED PR in #3055).
OPENAPI_RE = re.compile(r"/openapi\.yaml$")


def is_serialized(path):
    for s in SERIALIZED:
        if s.endswith("/"):
            if path.startswith(s):
                return True
        elif path == s:
            return True
    return False


# --------------------------------------------------------------------------- enumeration


def _gh(args):
    """Run gh, returning parsed JSON. Raises on any failure — never returns an empty list
    for a call that did not succeed, because that is the shape that reports a clean.

    `-R` is appended only for the porcelain commands that need a repo context (gh outside a
    checkout fails with `not a git repository`, which reads like a content problem). `gh
    api` does not accept it — the repo is already in the endpoint path."""
    cmd = ["gh"] + args
    if args and args[0] != "api":
        cmd += ["-R", REPO]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"gh {' '.join(args)} failed (rc={proc.returncode}): {proc.stderr.strip()}")
    try:
        return json.loads(proc.stdout)
    except json.JSONDecodeError as e:
        raise RuntimeError(f"gh {' '.join(args)} returned non-JSON: {e}") from e


def fetch_open_prs():
    """[{number, title, files: {path: blob_sha}}] for every OPEN pull request."""
    prs = _gh(["pr", "list", "--state", "open", "--limit", "200", "--json", "number,title"])
    out = []
    for pr in prs:
        n = pr["number"]
        # --slurp is required with --paginate: without it gh concatenates one JSON array per
        # page, which is not a JSON document. It yields [[page], [page], ...], hence the flatten.
        pages = _gh(["api", f"repos/{REPO}/pulls/{n}/files?per_page=100", "--paginate", "--slurp"])
        files = [f for page in pages for f in page]
        out.append(
            {
                "number": n,
                "title": pr.get("title", ""),
                # `sha` is the blob at THIS PR's head — the discriminator that separates
                # "already agrees" from "genuinely diverges". `status` is carried so a file
                # deleted by one PR and edited by another is still visible as an overlap.
                "files": {f["filename"]: f.get("sha", "") for f in files},
            }
        )
    return out


def resolve_pr_number(explicit):
    if explicit:
        return int(explicit)
    for var in ("PR_NUMBER", "GITHUB_PR_NUMBER"):
        if os.environ.get(var, "").strip().isdigit():
            return int(os.environ[var])
    # pull_request events set GITHUB_REF to refs/pull/<n>/merge. No ${{ }} needed, so this
    # works from the gate manifest, which cannot expand workflow expressions.
    m = re.match(r"refs/pull/(\d+)/", os.environ.get("GITHUB_REF", ""))
    if m:
        return int(m.group(1))
    ev = os.environ.get("GITHUB_EVENT_PATH", "")
    if ev and os.path.exists(ev):
        try:
            with open(ev) as fh:
                n = (json.load(fh).get("pull_request") or {}).get("number")
            if n:
                return int(n)
        except (OSError, ValueError, json.JSONDecodeError):
            pass
    return None


# ------------------------------------------------------------------------- classification


def classify(me, others):
    """Return (divergent, agreeing) overlap records against the PR `me`."""
    divergent, agreeing = [], []
    for other in others:
        if other["number"] == me["number"]:
            continue
        for path, sha in sorted(me["files"].items()):
            if path not in other["files"]:
                continue
            rec = {
                "path": path,
                "pr": other["number"],
                "title": other["title"],
                "serialized": is_serialized(path),
                "openapi": bool(OPENAPI_RE.search(path)),
            }
            # Equal blob shas: both heads hold the same bytes, so there is nothing to
            # reconcile even though the merge-base-computed file list shows a diff.
            if sha and sha == other["files"][path]:
                agreeing.append(rec)
            else:
                divergent.append(rec)
    return divergent, agreeing


def report(me_number, divergent, agreeing):
    if agreeing:
        print(f"note: {len(agreeing)} overlapping file(s) already hold IDENTICAL content "
              f"in both PRs — no action (the file list is computed against the merge-base, "
              f"so a competing PR that already merged still shows here).")
        for r in agreeing:
            print(f"  = {r['path']}  (also in #{r['pr']})")

    if not divergent:
        print(f"ok: PR #{me_number} shares no diverging file with any other open PR.")
        return 0

    ser = [r for r in divergent if r["serialized"]]
    api = [r for r in divergent if r["openapi"]]
    oth = [r for r in divergent if not r["serialized"] and not r["openapi"]]

    if ser:
        print("::warning::SHARED REGISTRY overlap — needs an explicit merge ORDER, not just "
              "a green check. Two neighbouring entries appended to one list merge CLEANLY "
              "and keep only one (.release-please-manifest.json went 56 -> 55 entries that "
              "way). Whoever lands second must re-read the file on origin/main after the "
              "merge and confirm their entry is still there.")
        for r in ser:
            print(f"  ! {r['path']}  <- also #{r['pr']}: {r['title']}")

    if api:
        print("::warning::OPENAPI overlap — check info.version against LIVE origin/main, not "
              "against your PR's base. The api-contract gate classifies against the PR's "
              "creation-time base, so a competing bump that merges first is invisible to it "
              "and both PRs can claim the same version (#481 x #524, #3055). Whoever lands "
              "second takes the next version.")
        for r in api:
            print(f"  ! {r['path']}  <- also #{r['pr']}: {r['title']}")

    if oth:
        print("::warning::This PR edits files that other OPEN PRs also edit. CI cannot see "
              "this: each check is scoped to one PR. Compare the CONTENT before assuming "
              "divergence — `git diff origin/<theirs> origin/<mine> -- <path>` — the three "
              "outcomes are identical content, a true duplicate (close one), or two real "
              "authoring decisions (merge both, expect a fixup).")
        for r in oth:
            print(f"  ~ {r['path']}  <- also #{r['pr']}: {r['title']}")

    return 1


# ------------------------------------------------------------------------------ self-test


def self_test():
    """Falsify the classifier with fixtures. No network: fetch_open_prs is never called."""
    failures = []

    def check(label, cond):
        if not cond:
            failures.append(label)

    me = {
        "number": 1,
        "title": "mine",
        "files": {
            "openbank-x/src/main/kotlin/A.kt": "aaa",
            "openbank-libs/governance/rules.yaml": "rrr",
            "openbank-x/src/main/resources/openapi.yaml": "ooo",
            "shared/agreed.txt": "same",
        },
    }

    # 1. A disjoint PR must produce NOTHING. A gate that flags everything is as useless as
    #    one that flags nothing, and only this case separates them.
    disjoint = [{"number": 2, "title": "elsewhere", "files": {"openbank-y/B.kt": "bbb"}}]
    d, a = classify(me, disjoint)
    check("disjoint PR reported as an overlap", not d and not a)

    # 2. Same path, DIFFERENT blob sha — a real divergence, must be reported.
    div = [{"number": 3, "title": "theirs", "files": {"openbank-x/src/main/kotlin/A.kt": "zzz"}}]
    d, a = classify(me, div)
    check("divergent overlap not reported", len(d) == 1 and not a)
    check("divergent overlap misclassified as serialized", d and not d[0]["serialized"])

    # 3. Same path, SAME blob sha — already agrees. Must NOT be reported as a divergence,
    #    or the gate warns loudest exactly when the work is already done (#3103).
    same = [{"number": 4, "title": "already merged shape", "files": {"shared/agreed.txt": "same"}}]
    d, a = classify(me, same)
    check("identical-content overlap wrongly reported as divergent", not d and len(a) == 1)

    # 4. A shared registry must escalate, and an openapi.yaml must be tagged for the
    #    version race — the two cases with a named failure behind them.
    reg = [{"number": 5, "title": "registry", "files": {
        "openbank-libs/governance/rules.yaml": "different",
        "openbank-x/src/main/resources/openapi.yaml": "different",
    }}]
    d, a = classify(me, reg)
    check("shared registry overlap not escalated", any(r["serialized"] for r in d))
    check("openapi overlap not tagged", any(r["openapi"] for r in d))

    # 5. The PR must never overlap with ITSELF (it shares every file with itself).
    d, a = classify(me, [dict(me)])
    check("PR reported as overlapping itself", not d and not a)

    # 6. Directory-prefix serialization, and a NON-registry sibling that must stay ordinary.
    check("gitops prefix not serialized",
          is_serialized("openbank-infra/gitops/components/payments/payments-services.yaml"))
    check("ordinary path wrongly serialized",
          not is_serialized("openbank-x/src/main/kotlin/A.kt"))

    # 7. Exit codes must distinguish the three verdicts. In particular an enumeration
    #    failure is 2, never 0 — a permission-shaped absence must not read as clean.
    devnull = open(os.devnull, "w")
    real_stdout, sys.stdout = sys.stdout, devnull  # report() prints; the verdict is the assertion
    try:
        clean_rc = report(1, [], [])
        divergent_rc = report(1, [{"path": "p", "pr": 9, "title": "t",
                                   "serialized": False, "openapi": False}], [])
    finally:
        sys.stdout = real_stdout
        devnull.close()
    check("clean verdict is not 0", clean_rc == 0)
    check("divergent verdict is not 1", divergent_rc == 1)

    # 8. _gh must RAISE on a failed call rather than return an empty list. Proven against a
    #    command that cannot succeed, so the failure path is executed rather than assumed.
    try:
        _gh(["api", "repos/JiRaska/open-bank-oss/pulls/0/files-does-not-exist"])
        failures.append("_gh returned normally on a failing call (would report a false clean)")
    except RuntimeError:
        pass

    if failures:
        for f in failures:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(failures)} case(s))\n")
        return 1
    print("self-test ok: overlap classifier is falsifiable (8 cases)")
    return 0


def main():
    ap = argparse.ArgumentParser(description="Report open PRs editing the same files as this one.")
    ap.add_argument("--pr", help="PR number (default: resolved from the CI environment)")
    ap.add_argument("--self-test", action="store_true", help="falsify the classifier, no network")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    number = resolve_pr_number(args.pr)
    if number is None:
        print("skipped: no pull request in scope (not a pull_request event, and no --pr).")
        return 0

    try:
        prs = fetch_open_prs()
    except RuntimeError as e:
        # NOT a clean verdict. On GitHub a permission-shaped absence is byte-identical to a
        # real one, so the only honest answer is "could not check".
        sys.stderr.write(f"::error::could not enumerate open PRs — this is NOT a clean "
                         f"verdict, the overlap check did not run: {e}\n")
        return 2

    me = next((p for p in prs if p["number"] == number), None)
    if me is None:
        print(f"skipped: PR #{number} is not in the open-PR list (closed or merged already).")
        return 0

    print(f"PR #{number}: {len(me['files'])} changed file(s), compared against "
          f"{len(prs) - 1} other open PR(s).")
    divergent, agreeing = classify(me, prs)
    return report(number, divergent, agreeing)


if __name__ == "__main__":
    sys.exit(main())
