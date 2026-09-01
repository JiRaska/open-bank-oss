#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Deployed == main drift watch (issue #3344).
#
# WHY THIS EXISTS
#   The sandbox ran lending 0.11.5 while main was at 0.20.2 — what runs is not what is
#   tested, and the drift was discovered by accident, not by a check. Every gitops
#   component manifest pins an immutable image tag (`sandbox-<sha>` or the manual-refresh
#   `sandbox-<sha>-run<github-run-id>`), so "which build is
#   deployed" is a committed fact in this repo, and "what is main" is a git query away.
#   Nothing compared the two.
#
# WHAT IT CHECKS
#   For every ECR `openbank-*` image referenced from
#   `openbank-infra/gitops/components/**/*.yaml` whose module dir carries a version.txt:
#
#     1. the tag must name a commit (`sandbox-<hex>`, optionally followed by the
#        provenance-only `-run<github-run-id>` suffix) — anything else is UNVERIFIABLE,
#        because an unpinned or hand-set tag can never be shown to equal main;
#     2. the commit must exist and be an ancestor of the checked-out main HEAD —
#        a build from a throwaway branch is not "deployed == main" either;
#     3. `<module>/version.txt` at that commit must equal version.txt on main.
#        The release axis is the contract: a sha behind main whose version matches has
#        only non-releasing changes behind it (rule #2 forces a bump for src changes).
#
#   DRIFT is version mismatch older than --days (default 7). UNVERIFIABLE is reported
#   at any age — a deployment that cannot be compared to main is never "in sync".
#
# TWO LANES, like the external feed watch
#   --offline is the PR lane (enforced in .github/gates/gates.yaml): no git queries, it
#   only proves every pinned tag is well-formed and every deployed module has a
#   version.txt. Deterministic, blocks a malformed tag from merging.
#
#   The scheduled lane (deploy-drift-watch.yml) does the full comparison and escalates
#   per service onto `deploy-drift`-labelled issues. Drift never blocks a merge: an
#   undeployed service is an operational signal, not a defect in the PR at hand.
#
# EXIT CODES
#   0 — every deployed service is in sync with main (or, --offline: declarations clean)
#   1 — the gate itself could not answer (0 images found, git failure, offline
#       declaration error). A scan that read nothing must never report green.
#   2 — drift or unverifiable deployments found (scheduled lane escalates, does not fail)
#
# Run:  python3 .github/scripts/check-deploy-drift.py --root . [--days 7] [--json out.json]
#       python3 .github/scripts/check-deploy-drift.py --self-test
#       python3 .github/scripts/check-deploy-drift.py --root . --offline

import argparse
import datetime
import json
import pathlib
import re
import subprocess
import sys
import tempfile

COMPONENTS_GLOB = "openbank-infra/gitops/components/**/*.yaml"

# image: 265175468565.dkr.ecr.eu-north-1.amazonaws.com/openbank-fx-service:sandbox-99114189
IMAGE_RE = re.compile(
    r"image:\s*[\"']?\S*\.amazonaws\.com/(openbank-[a-z0-9-]+):([^\s\"'}]+)"
)
# A manually requested evidence refresh may rebuild the same commit. ECR tags are immutable,
# so it appends the GitHub workflow run id. The suffix is deliberately narrow: it is provenance,
# not a second version axis, and parsing must still resolve exactly the commit prefix.
SANDBOX_TAG_RE = re.compile(r"^sandbox-([0-9a-f]{8,40})(?:-run([1-9][0-9]*))?$")

STATUS_OK = "ok"
STATUS_DRIFT = "drift"
STATUS_UNVERIFIABLE = "unverifiable"


def find_deployed_images(root: pathlib.Path):
    """Map service name -> set of (tag, manifest path) for ECR openbank-* images."""
    found = {}
    for manifest in sorted(root.glob(COMPONENTS_GLOB)):
        try:
            text = manifest.read_text(encoding="utf-8")
        except OSError as e:
            print(f"ERROR: cannot read {manifest}: {e}", file=sys.stderr)
            continue
        for service, tag in IMAGE_RE.findall(text):
            found.setdefault(service, set()).add(
                (tag, str(manifest.relative_to(root)))
            )
    return found


def git(root: pathlib.Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["git", "-C", str(root), *args],
        capture_output=True, text=True, check=False,
    )


def resolve_commit(root: pathlib.Path, prefix: str):
    """Full sha if the prefix names exactly one commit, else None."""
    cp = git(root, "rev-parse", "--verify", "--quiet", f"{prefix}^{{commit}}")
    if cp.returncode != 0:
        return None
    return cp.stdout.strip()


def is_ancestor_of_head(root: pathlib.Path, sha: str) -> bool:
    return git(root, "merge-base", "--is-ancestor", sha, "HEAD").returncode == 0


def commit_date(root: pathlib.Path, sha: str):
    cp = git(root, "show", "-s", "--format=%cI", sha)
    if cp.returncode != 0:
        return None
    try:
        return datetime.datetime.fromisoformat(cp.stdout.strip())
    except ValueError:
        return None


def version_at(root: pathlib.Path, ref: str, module: str):
    cp = git(root, "show", f"{ref}:{module}/version.txt")
    if cp.returncode != 0:
        return None
    return cp.stdout.strip()


def parse_sandbox_tag(tag: str):
    """Hex sha prefix from an approved immutable sandbox tag, else None."""
    m = SANDBOX_TAG_RE.match(tag)
    return m.group(1) if m else None


def classify_version_drift(deployed_version, main_version, age_days, threshold_days):
    """Pure verdict for a resolved deployment. Self-test drives this both ways."""
    if deployed_version is None or main_version is None:
        return STATUS_UNVERIFIABLE
    if deployed_version == main_version:
        return STATUS_OK
    if age_days is not None and age_days <= threshold_days:
        return STATUS_OK
    return STATUS_DRIFT


def evaluate_service(root: pathlib.Path, service: str, tags, threshold_days: int,
                     now: datetime.datetime):
    """One report entry per service (the worst of its deployed tags)."""
    module = f"openbank-{service}" if not service.startswith("openbank-") else service
    main_version_file = root / module / "version.txt"
    if not main_version_file.is_file():
        return None  # platform image without a release axis (keycloak, pyroscope, …)
    main_version = main_version_file.read_text(encoding="utf-8").strip()

    entries = []
    for tag, manifest in sorted(tags):
        entry = {
            "service": module,
            "image": f"openbank-{service}",
            "tag": tag,
            "manifest": manifest,
            "main_version": main_version,
            "deployed_version": None,
            "age_days": None,
            "status": STATUS_UNVERIFIABLE,
            "reason": "",
        }
        prefix = parse_sandbox_tag(tag)
        if prefix is None:
            entry["reason"] = (
                f"tag {tag!r} does not name a commit (want sandbox-<sha>[-run<id>]) — "
                "an unpinned tag can never be shown to equal main"
            )
            entries.append(entry)
            continue
        sha = resolve_commit(root, prefix)
        if sha is None:
            entry["reason"] = (
                f"tag {tag!r} names commit {prefix} which is not in this repository — "
                "the deployed build's provenance cannot be verified"
            )
            entries.append(entry)
            continue
        if not is_ancestor_of_head(root, sha):
            entry["reason"] = (
                f"deployed commit {sha[:12]} is not an ancestor of main — "
                "the sandbox runs a build that never landed"
            )
            entries.append(entry)
            continue
        deployed_version = version_at(root, sha, module)
        date = commit_date(root, sha)
        age_days = None
        if date is not None:
            age_days = (now - date).days
        entry["deployed_version"] = deployed_version
        entry["age_days"] = age_days
        status = classify_version_drift(
            deployed_version, main_version, age_days, threshold_days
        )
        entry["status"] = status
        if status == STATUS_DRIFT:
            entry["reason"] = (
                f"deployed {deployed_version} ({age_days}d old, {sha[:12]}) "
                f"vs main {main_version} — drift beyond {threshold_days}d"
            )
        elif status == STATUS_UNVERIFIABLE:
            entry["reason"] = (
                f"{module}/version.txt does not exist at deployed commit {sha[:12]}"
            )
        entries.append(entry)

    # Worst status wins when a service is pinned in several manifests.
    order = {STATUS_DRIFT: 0, STATUS_UNVERIFIABLE: 1, STATUS_OK: 2}
    entries.sort(key=lambda e: order[e["status"]])
    return entries[0]


def run_offline(root: pathlib.Path) -> int:
    images = find_deployed_images(root)
    if not images:
        print("ERROR: found 0 ECR openbank-* images under "
              f"{COMPONENTS_GLOB} — the scan is broken, not the fleet.",
              file=sys.stderr)
        return 1
    errors = 0
    skipped = []
    for service, tags in sorted(images.items()):
        module = service if service.startswith("openbank-") else f"openbank-{service}"
        if not (root / module / "version.txt").is_file():
            skipped.append(service)
            continue
        for tag, manifest in sorted(tags):
            if parse_sandbox_tag(tag) is None:
                print(f"ERROR: {manifest}: {service} pinned to {tag!r} — "
                      "deployed images must carry sandbox-<sha>[-run<id>] so the drift watch "
                      "can compare them to main.", file=sys.stderr)
                errors += 1
    if skipped:
        print(f"skipped (no version.txt, no release axis): {', '.join(sorted(skipped))}")
    if errors:
        print(f"{errors} malformed image pin(s).", file=sys.stderr)
        return 1
    print(f"declaration drift clean: {len(images) - len(skipped)} deployed module(s), "
          "every pin is an approved immutable sandbox tag.")
    return 0


def run_full(root: pathlib.Path, threshold_days: int, json_path: str) -> int:
    images = find_deployed_images(root)
    if not images:
        print("ERROR: found 0 ECR openbank-* images under "
              f"{COMPONENTS_GLOB} — the scan is broken, not the fleet.",
              file=sys.stderr)
        return 1
    now = datetime.datetime.now(datetime.timezone.utc)
    entries = []
    for service, tags in sorted(images.items()):
        entry = evaluate_service(root, service, tags, threshold_days, now)
        if entry is not None:
            entries.append(entry)
    if not entries:
        print("ERROR: every deployed image was skipped — no module with version.txt "
              "matched. The watch would report green over nothing.", file=sys.stderr)
        return 1

    report = {
        "generated_at": now.isoformat(),
        "threshold_days": threshold_days,
        "entries": entries,
    }
    if json_path:
        pathlib.Path(json_path).write_text(json.dumps(report, indent=2) + "\n",
                                           encoding="utf-8")

    bad = [e for e in entries if e["status"] != STATUS_OK]
    for e in entries:
        if e["status"] == STATUS_OK:
            print(f"OK          {e['service']}: deployed == main ({e['main_version']})")
        else:
            print(f"{e['status'].upper():<13}{e['service']}: {e['reason']} [{e['manifest']}]")
    print(f"\n{len(entries) - len(bad)} in sync, {len(bad)} drifted/unverifiable "
          f"(threshold {threshold_days}d).")
    return 2 if bad else 0


# ---------------------------------------------------------------------------
# Self-test: the verdicts above are only worth anything if the flagging paths
# provably execute. Builds a throwaway git repo with a back-dated "deployed"
# commit and drives the full scan against fixtures it MUST flag — and fixtures
# it MUST NOT (a gate that flags everything is noise nobody keeps).
# ---------------------------------------------------------------------------

def _git_env(date: str):
    import os
    env = dict(os.environ)
    env["GIT_AUTHOR_DATE"] = date
    env["GIT_COMMITTER_DATE"] = date
    env["GIT_AUTHOR_NAME"] = env["GIT_COMMITTER_NAME"] = "self-test"
    env["GIT_AUTHOR_EMAIL"] = env["GIT_COMMITTER_EMAIL"] = "self@test"
    return env


def _git_fixture(root: pathlib.Path, date: str, *args: str):
    # A hermetic fixture must not inherit a contributor's global commit.gpgsign=true.
    # This only creates throwaway test commits; it never changes the repository policy.
    subprocess.run(["git", "-c", "commit.gpgsign=false", "-C", str(root), *args], check=True,
                   capture_output=True, env=_git_env(date))


def self_test() -> int:
    failures = []

    def expect(name, cond):
        if not cond:
            failures.append(name)
            print(f"SELF-TEST FAIL: {name}")
        else:
            print(f"self-test ok: {name}")

    # --- pure functions, both directions ----------------------------------
    expect("parses sandbox-<sha>", parse_sandbox_tag("sandbox-99114189") == "99114189")
    expect("parses full sha", parse_sandbox_tag("sandbox-" + "a" * 40) == "a" * 40)
    expect("parses manual refresh tag", parse_sandbox_tag("sandbox-99114189-run32826611610") == "99114189")
    expect("rejects sandbox-sec1", parse_sandbox_tag("sandbox-sec1") is None)
    expect("rejects non-numeric refresh suffix", parse_sandbox_tag("sandbox-99114189-runproof") is None)
    expect("rejects zero refresh run", parse_sandbox_tag("sandbox-99114189-run0") is None)
    expect("rejects latest", parse_sandbox_tag("latest") is None)
    expect("rejects semver", parse_sandbox_tag("1.2.3") is None)
    expect("same version is in sync",
           classify_version_drift("1.0.0", "1.0.0", 400, 7) == STATUS_OK)
    expect("young drift is tolerated",
           classify_version_drift("1.0.0", "2.0.0", 3, 7) == STATUS_OK)
    expect("old drift is flagged",
           classify_version_drift("1.0.0", "2.0.0", 30, 7) == STATUS_DRIFT)
    expect("unknown version is unverifiable",
           classify_version_drift(None, "2.0.0", 1, 7) == STATUS_UNVERIFIABLE)
    expect("image regex finds ECR ref",
           IMAGE_RE.findall("image: 265175468565.dkr.ecr.eu-north-1.amazonaws.com/"
                            "openbank-fx-service:sandbox-99114189")
           == [("openbank-fx-service", "sandbox-99114189")])
    expect("image regex ignores docker.io",
           IMAGE_RE.findall("image: docker.io/openpolicyagent/opa:1.17.0") == [])

    # --- end-to-end against a fixture git repo -----------------------------
    with tempfile.TemporaryDirectory() as tmp:
        repo = pathlib.Path(tmp)
        _git_fixture(repo, "2026-01-01T00:00:00+00:00", "init", "-q", "-b", "main")
        module = repo / "openbank-demo-service"
        module.mkdir()
        (module / "version.txt").write_text("1.0.0\n", encoding="utf-8")
        comp = repo / "openbank-infra/gitops/components/demo"
        comp.mkdir(parents=True)
        _git_fixture(repo, "2026-01-01T00:00:00+00:00", "add", ".")
        _git_fixture(repo, "2026-01-01T00:00:00+00:00", "commit", "-q", "-m", "v1")
        old_sha = subprocess.run(
            ["git", "-C", str(repo), "rev-parse", "HEAD"],
            capture_output=True, text=True, check=True).stdout.strip()

        # main moves to 2.0.0; the manifest still pins the v1 build.
        (module / "version.txt").write_text("2.0.0\n", encoding="utf-8")
        _git_fixture(repo, "2026-06-01T00:00:00+00:00", "commit", "-qam", "v2")

        drift_yaml = (comp / "demo.yaml")
        drift_yaml.write_text(
            "spec:\n  containers:\n    - image: 265175468565.dkr.ecr.eu-north-1."
            f"amazonaws.com/openbank-demo-service:sandbox-{old_sha[:8]}\n",
            encoding="utf-8")
        expect("stale pin is flagged end-to-end",
               run_full(repo, 7, "") == 2)

        # The drift must be about age: a 1-day grace window would still flag a
        # months-old pin, and a huge threshold must NOT (young-drift tolerance
        # is the only thing standing between this watch and alert fatigue).
        expect("huge threshold tolerates the same pin",
               run_full(repo, 100000, "") == 0)

        # Pin the CURRENT build: deployed == main must be green at any age.
        new_sha = subprocess.run(
            ["git", "-C", str(repo), "rev-parse", "HEAD"],
            capture_output=True, text=True, check=True).stdout.strip()
        drift_yaml.write_text(
            "spec:\n  containers:\n    - image: 265175468565.dkr.ecr.eu-north-1."
            f"amazonaws.com/openbank-demo-service:sandbox-{new_sha[:8]}\n",
            encoding="utf-8")
        expect("current pin is in sync",
               run_full(repo, 7, "") == 0)

        # An unparseable tag must never read as "in sync" — offline lane fails
        # it, scheduled lane reports it unverifiable.
        drift_yaml.write_text(
            "spec:\n  containers:\n    - image: 265175468565.dkr.ecr.eu-north-1."
            "amazonaws.com/openbank-demo-service:latest\n", encoding="utf-8")
        expect("unpinned tag fails the offline lane", run_offline(repo) == 1)
        expect("unpinned tag is unverifiable in the full scan",
               run_full(repo, 7, "") == 2)

    if failures:
        print(f"\n{len(failures)} self-test expectation(s) FAILED", file=sys.stderr)
        return 1
    print("\nself-test: all expectations hold.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Deployed == main drift watch (issue #3344).")
    ap.add_argument("--root", default=".")
    ap.add_argument("--days", type=int, default=7,
                    help="tolerated version-drift age in days (default 7)")
    ap.add_argument("--json", default="", help="write a machine-readable report here")
    ap.add_argument("--offline", action="store_true",
                    help="declaration checks only (PR lane; no git queries)")
    ap.add_argument("--self-test", "--selftest", action="store_true",
                    help="falsify this checker's own flagging paths and exit")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    root = pathlib.Path(args.root).resolve()
    if args.offline:
        return run_offline(root)
    return run_full(root, args.days, args.json)


if __name__ == "__main__":
    sys.exit(main())
