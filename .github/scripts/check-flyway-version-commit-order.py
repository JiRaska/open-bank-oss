#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""A Flyway migration added to main LATER must carry a HIGHER version than every one before it.

Why this exists
----------------
security-scanner-service was in CrashLoopBackOff for 4+ hours (issue #5628):
`V4__drop_security_outbox.sql`'s own PR (#4940) merged AFTER `V5__create_ict_incidents.sql`'s PR
(#4939) had already deployed and applied V5 to the live database. Flyway's default
`validateOnMigrate` refuses to boot once a lower-numbered migration than the highest already
applied shows up on the classpath — "Detected resolved migration not applied to database: 4" —
and there is no error at merge time, no error in either PR's own CI (each ran green against ITS
OWN base, per this repo's documented "PR green is about the base, not main" trap), only a crashed
pod hours later.

The set {2, 3, 4, 5} is not, by itself, a defect: sorted version numbers with no gaps look
completely fine as a static invariant, which is why no existing gate catches this shape. The
actual defect is ORDER — file V4 reached `main` chronologically AFTER file V5. Two competing PRs
racing for the next version number is exactly the class of collision this repo already guards for
API-contract versions and the release manifest; Flyway versions had no equivalent guard.

WHAT IT CHECKS
--------------
For each `openbank-*/src/main/resources/db/migration/V<N>__*.sql`, find the commit that FIRST
added that file to `origin/main`'s history (`git log --diff-filter=A`). Sort each service's
migrations by that commit's position in main's history (earliest first). The version numbers in
that order must be strictly increasing — a migration that reached main later must never carry a
version number lower than one that reached main earlier.

This is a DETECT-FAST gate, not a prevention: it runs on push to main (ci.yml's `push: [main]`
trigger), so two PRs racing for the same next-version slot can both still merge green against
their own stale bases — nothing pre-merge can see a sibling PR's future content. What this buys is
the alarm firing within minutes of the SECOND merge landing, on main itself, instead of being
found by a crashed pod hours or days later. The fix differs by WHEN it is caught: renumbering is
only safe while the offending file is still open in its own PR, not yet on main (once it reaches
main, check-db-migration.py's own db-migration-gate blocks renaming it — see
KNOWN_VIOLATIONS below for the alternative: `QUARKUS_FLYWAY_OUT_OF_ORDER=true`).

WHAT IT DELIBERATELY DOES NOT CHECK
------------------------------------
Whether a migration has actually been applied to any real database (no cluster access from CI) —
this is a git-history proxy for that fact, not a replacement for it. A repository history
rewrite (squash-merge changes each commit's SHA but preserves file-introduction ORDER via
`git log`'s default topological traversal, which is what this reads) does not defeat the check;
an actual `git rebase`/history-edit that reorders commits could, which is the same blind spot
every other order-sensitive gate in this repo already has.

Usage:  check-flyway-version-commit-order.py [--enforce] [--selftest]
Advisory by default (prints ::warning, exits 0) per the repo convention; --enforce fails the build.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import subprocess
import sys

import gatelib

REPO = pathlib.Path(__file__).resolve().parents[2]
VERSION_RE = re.compile(r"^V(\d+)__.*\.sql$")

# Known violations that predate this gate, each already fixed with `out-of-order: true` (renaming
# is not an option once a migration has reached main — check-db-migration.py's db-migration-gate
# deliberately treats a rename of an already-committed migration as "editing its identity" and
# blocks it, since it cannot know from git alone whether that file was ever applied to a live
# database). Checked BOTH ways, same idiom as check-kafka-acl-coverage.py's KNOWN_GAPS: an entry
# that stops reproducing is itself reported, so this list can only shrink.
KNOWN_VIOLATIONS: dict[str, str] = {
    "openbank-security-scanner/src/main/resources/db/migration/V4__drop_security_outbox.sql":
        "issue #5628 / PR #5630 — QUARKUS_FLYWAY_OUT_OF_ORDER=true set in gitops.",
    "openbank-campaign-service/src/main/resources/db/migration/V13__campaign_decision_graph.sql":
        "V15 (Stories) deployed before the additive graph migrations V13/V14; "
        "QUARKUS_FLYWAY_OUT_OF_ORDER=true set in components/campaign/campaign-service.yaml, "
        "with its own note to remove once all environments have recorded V13/V14.",
}


def migration_files(root: pathlib.Path) -> list[tuple[pathlib.Path, int]]:
    out = []
    for svc_dir in sorted(root.glob("openbank-*")):
        mig_dir = svc_dir / "src" / "main" / "resources" / "db" / "migration"
        if not mig_dir.is_dir():
            continue
        for f in sorted(mig_dir.glob("V*.sql")):
            m = VERSION_RE.match(f.name)
            if m:
                out.append((f, int(m.group(1))))
    return out


def duplicate_versions(files_by_service: dict[str, list[tuple[pathlib.Path, int]]]) -> list[str]:
    """Two migrations sharing one version inside one service — always fatal, never baselined.

    This is a DIFFERENT defect from the out-of-order shape the rest of this gate measures, and
    the remedy is different too. `QUARKUS_FLYWAY_OUT_OF_ORDER=true` permits applying a LOWER
    version after a higher one has been applied; it does nothing about two files CLAIMING the
    same version, because Flyway keys its schema history by version and refuses to resolve the
    set at all — `FlywayException: Found more than one migration with version N`. The service
    then does not boot, so there is no partial state and no baseline that could make it
    acceptable. The only fix is to renumber, and renumbering is safe exactly because the
    collision prevented application: no checksum exists in any schema history to invalidate.

    Measured 2026-09-06: notification-service carried V14__synthetic_outbox_taint.sql (#6731)
    and V14__notification_deduplication_key.sql (#8334) — merged 13 days apart, from branches
    that never saw each other. Git reports no conflict for two differently-named files, so
    nothing before this said a word.
    """
    out = []
    for service, files in sorted(files_by_service.items()):
        by_version: dict[int, list[str]] = {}
        for path, version in files:
            by_version.setdefault(version, []).append(path.name)
        for version, names in sorted(by_version.items()):
            if len(names) > 1:
                out.append(
                    f"::error::{service}: {len(names)} migrations claim version {version} "
                    f"({', '.join(sorted(names))}). Flyway keys its schema history by version and "
                    f"refuses the whole set — 'Found more than one migration with version "
                    f"{version}' — so the service does not boot. Renumber the one that reached "
                    f"main LAST to the next free version; QUARKUS_FLYWAY_OUT_OF_ORDER does not "
                    f"help here and no KNOWN_VIOLATIONS entry can excuse it.",
                )
    return out


def first_commit_order(paths: list[pathlib.Path]) -> tuple[dict[pathlib.Path, int], str]:
    """{path: position in origin/main's history, lower = earlier} for the commit that first
    ADDED each path (git log --diff-filter=A, oldest add if a path was ever removed+re-added).

    One `git log --name-status` pass over the whole migration-file pathspec, not one subprocess
    per file (350 of them, ~55s) -- a single rev-list plus a single name-status scan does the
    same job in a couple of seconds.
    """
    order: dict[pathlib.Path, int] = {}
    # Resolve the mainline through several candidates, because CI does not have the one a
    # developer's checkout does. `actions/checkout` fetches with a narrow refspec, so
    # `refs/remotes/origin/main` frequently does NOT exist on a PR run even at fetch-depth: 0 --
    # `git fetch origin main` there updates FETCH_HEAD and nothing else. The first version of this
    # function caught the resulting CalledProcessError and returned an EMPTY map, which made the
    # whole gate a silent no-op: no order, therefore no pairs to compare, therefore no violations,
    # therefore green. It was only visible because the KNOWN_VIOLATIONS both-ways check then
    # reported every baseline entry as stale -- the ratchet catching the checker, which is the
    # single reason that idiom is worth its cost.
    # HEAD is never a candidate: on a PR run it is the merge commit, so `rev-list HEAD`
    # includes the branch's own commits and the gate stops measuring "order on main" and
    # starts measuring "order including this PR" -- a different question, answered
    # confidently.
    #
    # The mainline is FETCHED and its DEPTH is checked. Both matter, and the second one is
    # what the first two attempts at this function missed.
    #
    # The gates shard checks out at fetch-depth: 1 (ci.yml says so in its own comment). On a
    # shallow repo `git log --diff-filter=A` reports every file as ADDED at the shallow
    # boundary commit, because that grafted root is where history appears to begin. So all 355
    # migrations resolved to ONE position, the per-service sort fell through to its version
    # tiebreak, every service looked monotonic, and the gate reported zero violations while
    # printing a healthy "355 migrations ordered". Measured: CI resolved
    # `FETCH_HEAD@15dd7273d (3 commits)` and found nothing, where a full local history finds
    # two — campaign V15 at rev-list position 4094 against V13 at 4167, security-scanner V5 at
    # 4382 against V4 at 4476.
    #
    # A mainline shorter than the corpus it must order cannot order it. That is checkable, so
    # it is checked, rather than trusted.
    if subprocess.run(["git", "rev-parse", "--is-shallow-repository"], cwd=REPO,
                      capture_output=True, text=True, check=False).stdout.strip() == "true":
        # Deepen in place rather than requiring fetch-depth: 0 on the shard — 60 other gates
        # do not need full history and should not pay for it.
        for args in (["--unshallow"], ["--depth=2147483647"]):
            if subprocess.run(["git", "fetch", "--quiet", *args, "origin", "main"], cwd=REPO,
                              capture_output=True, text=True, check=False).returncode == 0:
                break
    else:
        subprocess.run(["git", "fetch", "--quiet", "origin", "main"],
                       cwd=REPO, capture_output=True, text=True, check=False)

    # Every candidate is evaluated and the LONGEST wins, never the first that answers: a
    # truncated FETCH_HEAD is a plausible-looking answer that silently changes the question.
    revs: list[str] = []
    mainline = "unresolved"
    for ref in ("FETCH_HEAD", "origin/main", "main"):
        try:
            candidate = subprocess.run(
                ["git", "rev-list", "--reverse", ref],
                cwd=REPO, capture_output=True, text=True, check=True,
            ).stdout.splitlines()
        except subprocess.CalledProcessError:
            continue
        if len(candidate) > len(revs):
            revs, mainline = candidate, ref
    position = {sha: i for i, sha in enumerate(revs)}
    if revs:
        provenance = f"{mainline}@{revs[-1][:9]} ({len(revs)} commits)"

    # The depth guard. A history with fewer commits than there are migrations cannot have
    # introduced them one at a time, so it is truncated whatever it claims to be.
    if len(revs) < len(paths):
        return {}, f"{provenance} — TOO SHALLOW for {len(paths)} migrations"

    wanted = {str(p.relative_to(REPO)) for p in paths}
    try:
        # Deliberately NOT --follow: its similarity-based rename detection produced a false
        # match for document-service's V2 (attributed to an unrelated infra commit whose diff
        # happened to look similar), placing it chronologically before V1 and firing a false
        # violation despite flyway_schema_history confirming both applied in the correct
        # 1-then-2 order. A plain add-commit lookup is exact for the case this gate cares about
        # (two files landing as siblings in one PR resolve to the identical commit, which the
        # stable sort in find_violations then orders by filename -- V1 before V2).
        # Traverse the SAME ref the positions came from, never implicit HEAD. On a PR run
        # actions/checkout puts HEAD on the merge commit, so a HEAD traversal walks both parents
        # and can attribute a file's "add" to a commit that is not on the mainline at all --
        # while the positions are mainline-only. The two disagreed silently: CI found the order
        # monotonic and reported zero violations where a local run found two, and again only the
        # KNOWN_VIOLATIONS both-ways check made it visible (every baseline entry read as stale).
        # Same failure family as the empty-map bug above: the gate ran, and measured the wrong
        # history.
        log = subprocess.run(
            [
                "git", "log", "--name-status", "--diff-filter=A", "--format=commit %H",
                mainline, "--", "**/db/migration/V*.sql",
            ],
            cwd=REPO, capture_output=True, text=True, check=True,
        ).stdout.splitlines()
    except subprocess.CalledProcessError:
        return order, provenance

    # Newest-first traversal: the LAST (oldest) commit that added a given path wins, so a later
    # match for the same path must not overwrite an earlier one already recorded.
    current_sha = None
    seen: dict[str, str] = {}
    for line in log:
        if line.startswith("commit "):
            current_sha = line.removeprefix("commit ")
            continue
        if not line.startswith("A\t") or current_sha is None:
            continue
        rel = line[2:]
        if rel in wanted:
            seen[rel] = current_sha  # overwritten by each older match as traversal continues

    for path in paths:
        rel = str(path.relative_to(REPO))
        sha = seen.get(rel)
        if sha in position:
            order[path] = position[sha]
    return order, provenance


def find_violations(
    files_by_service: dict[str, list[tuple[pathlib.Path, int]]],
    order: dict[pathlib.Path, int],
) -> tuple[list[str], set[str]]:
    findings = []
    used_baseline: set[str] = set()
    for service, files in files_by_service.items():
        with_order = [(order[p], v, p) for p, v in files if p in order]
        with_order.sort()  # by commit position: earliest-added first
        prev_version = None
        prev_path = None
        for _, version, path in with_order:
            if prev_version is not None and version <= prev_version:
                key = str(path.relative_to(REPO))
                if key in KNOWN_VIOLATIONS:
                    used_baseline.add(key)
                else:
                    findings.append(
                        f"::error file={path.relative_to(REPO)}::{service}: {path.name} "
                        f"(version {version}) reached main AFTER {prev_path.name} (version "
                        f"{prev_version}) but carries a lower-or-equal version number. Flyway "
                        f"will refuse to apply it once {prev_path.name} or later is already "
                        f"deployed — 'Detected resolved migration not applied to database: "
                        f"{version}' — and crash the service on every boot (issue #5628). If "
                        f"this migration is STILL OPEN in a PR (not yet merged to main), "
                        f"renumber it to the next free version instead. If it has ALREADY "
                        f"reached main, renumbering is blocked by check-db-migration.py's "
                        f"db-migration-gate (renaming an already-committed migration is treated "
                        f"as editing it) — set QUARKUS_FLYWAY_OUT_OF_ORDER=true in that "
                        f"service's gitops Deployment env instead (see "
                        f"components/campaign/campaign-service.yaml for the pattern), then add "
                        f"an entry to this script's KNOWN_VIOLATIONS.",
                    )
            prev_version = version
            prev_path = path
    return findings, used_baseline


def selftest() -> int:
    p_early = REPO / "openbank-fake-service" / "V5__a.sql"
    p_late = REPO / "openbank-fake-service" / "V4__b.sql"
    violations, used = find_violations(
        {"svc": [(p_early, 5), (p_late, 4)]},
        {p_early: 0, p_late: 1},  # p_late reached main AFTER p_early (higher position)
    )
    if len(violations) != 1 or used:
        print(f"selftest FAIL: expected 1 unbaselined violation for the #5628 shape, "
              f"got {len(violations)} (baseline hits: {used})")
        return 1
    ok_violations, _ = find_violations(
        {"svc": [(p_early, 4), (p_late, 5)]},
        {p_early: 0, p_late: 1},  # correctly increasing version in commit order
    )
    if ok_violations:
        print(f"selftest FAIL: monotonically increasing versions wrongly flagged: {ok_violations}")
        return 1
    # A path matching a KNOWN_VIOLATIONS key must be counted as baselined, not raised again.
    baselined_path = REPO / next(iter(KNOWN_VIOLATIONS))
    p_before = REPO / "openbank-fake-service-2" / "V1__x.sql"
    baseline_violations, baseline_used = find_violations(
        {"svc": [(p_before, 99), (baselined_path, 1)]},
        {p_before: 0, baselined_path: 1},
    )
    if baseline_violations or not baseline_used:
        print(f"selftest FAIL: a KNOWN_VIOLATIONS entry was not recognised as baselined "
              f"(findings={baseline_violations}, used={baseline_used}).")
        return 1
    # Duplicate versions inside one service: fatal, and NOT excusable by KNOWN_VIOLATIONS. The
    # negative case matters as much — two services may each own a V1 without colliding.
    dup = duplicate_versions({"svc": [(REPO / "openbank-x" / "V14__a.sql", 14),
                                      (REPO / "openbank-x" / "V14__b.sql", 14)]})
    if len(dup) != 1:
        print(f"selftest FAIL: a duplicate version inside one service was not flagged: {dup}")
        return 1
    same_version_two_services = duplicate_versions({
        "svc-a": [(REPO / "openbank-a" / "V1__x.sql", 1)],
        "svc-b": [(REPO / "openbank-b" / "V1__y.sql", 1)],
    })
    if same_version_two_services:
        print(f"selftest FAIL: two services each owning V1 wrongly flagged: "
              f"{same_version_two_services}")
        return 1
    baselined_dup = duplicate_versions({
        "svc": [(REPO / next(iter(KNOWN_VIOLATIONS)), 13),
                (REPO / "openbank-x" / "V13__other.sql", 13)],
    })
    if not baselined_dup:
        print("selftest FAIL: a duplicate version was silenced by KNOWN_VIOLATIONS — it must "
              "not be, because Flyway refuses the set regardless of any gitops flag.")
        return 1
    print("selftest OK: flags the #5628 shape (later commit, lower version), spares "
          "monotonically increasing versions, recognises a baselined KNOWN_VIOLATIONS entry, "
          "and flags a duplicate version that no baseline may excuse.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--selftest", action="store_true")
    args = ap.parse_args()
    if args.selftest:
        return selftest()

    all_files = migration_files(REPO)
    order, provenance = first_commit_order([p for p, _ in all_files])

    files_by_service: dict[str, list[tuple[pathlib.Path, int]]] = {}
    for path, version in all_files:
        service = path.relative_to(REPO).parts[0]
        files_by_service.setdefault(service, []).append((path, version))

    if all_files and not order:
        print(f"mainline resolved as: {provenance}")
        # Never report "clean" from a scan that resolved nothing. A gate that cannot see its
        # corpus must say so, not agree with it.
        print("::error::could not obtain a usable history for main, so no migration could be "
              "ordered. Either the fetch failed, or the repository is shallow and could not be "
              "deepened — a history shorter than the migration corpus reports every file as "
              "added at the shallow boundary, which makes every service look monotonic and the "
              "gate green about work it did not do. See the resolved-mainline line above.")
        gatelib.subjects(0, "migrations ordered")
        return 1

    # Duplicate versions first: they are fatal on their own, decidable without history, and
    # not excusable by the baseline the ordering half uses.
    findings = duplicate_versions(files_by_service)
    order_findings, used_baseline = find_violations(files_by_service, order)
    findings += order_findings

    for key in sorted(set(KNOWN_VIOLATIONS) - used_baseline):
        findings.append(
            f"::error::stale KNOWN_VIOLATIONS entry {key} — that migration no longer reproduces "
            f"the out-of-order shape (renumbered, reordered, or removed). Remove it, so the list "
            f"can only shrink.",
        )

    total = sum(len(v) for v in files_by_service.values())
    for line in findings:
        print(line if args.enforce else line.replace("::error", "::warning", 1))
    # Count what was actually ORDERED, not what exists on disk: a scan that found every file
    # and could order none of them is the exact silent-no-op above, and the min_subjects floor
    # should catch it independently of the baseline check.
    gatelib.subjects(len(order), "migrations ordered against mainline history")
    verdict = "clean." if not findings else f"{len(findings)} finding(s) above."
    print(f"mainline resolved as: {provenance}")
    print(f"check-flyway-version-commit-order: {total} migration(s) across "
          f"{len(files_by_service)} service(s) checked, {len(KNOWN_VIOLATIONS)} known baseline "
          f"entr{'y' if len(KNOWN_VIOLATIONS) == 1 else 'ies'} — {verdict}")
    return 1 if findings and args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
