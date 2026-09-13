#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Detect ArgoCD Applications that report Synced/Healthy while applying nothing (issue #6371).

THE GAP
    With `ServerSideApply=true` the application-controller computes its diff by a server-side
    dry-run apply. If that dry-run is REJECTED the comparison errors, and the Application keeps
    serving its LAST KNOWN status instead of going OutOfSync. It reports `Synced / Healthy` and
    applies nothing, for as long as the rejection lasts — measured at three weeks on the `loki`
    app (2026-08-21: `singleBinary.persistence.size` 10Gi -> 30Gi, and a StatefulSet's
    `volumeClaimTemplates` is immutable). Every values change merged in that window was silently
    dropped, including #6032's Loki ruler fix, which is why that fix appeared to have landed
    while the ruler had still never loaded a rule.

    The state IS recorded, in `.status.conditions[].type == ComparisonError`. It is exposed by
    NO ArgoCD metric: `argocd_app_info` carries `sync_status` and `health_status` only. So both
    rules in `prometheus-rules-argocd.yaml` are quiet BY CONSTRUCTION, not by accident —
    `ArgoCDAppDegraded` keys on health `Degraded` and `ArgoCDAppHealthUnknown` on `Unknown`,
    and this app was `Healthy`. `argocd_app_sync_total` is not a substitute either: an app that
    legitimately needs no sync looks identical to one that CANNOT sync.

WHY NOT A kube-state-metrics customResourceState
    That was the first proposal (#6371) and it is not free: a malformed CRS config takes down the
    kube-state-metrics pod and with it every `kube_*` series in the estate — a far larger blast
    radius than the gap being closed — and KSM parses the config only AFTER connecting to an
    apiserver, so a deliberately broken config and a good one produce byte-identical startup logs
    offline. A probe that cannot tell those apart is not validation. This detector reads the same
    field with no shared failure domain, and is falsifiable offline against fixtures.

WHAT IT REJECTS
    ERROR_CONDITION            any `.status.conditions[].type` ending in `Error` — the class the
                               metrics cannot see. This is the #6371 mechanism itself.
    SYNCED_MASKING_FAILED_OP   `.status.sync.status == Synced` while the last sync operation
                               finished `Failed`/`Error` AT THE VERY REVISION now being reported
                               as Synced. That is the "a failed sync self-heals to Synced"
                               mechanism: the app claims Synced at a revision nothing ever
                               successfully applied.
                               The revision equality is load-bearing, not a refinement. Measured
                               against the live cluster 2026-08-23, `keycloak` had a Failed
                               operation from 2026-08-07 (its theme ConfigMap exceeded the 256KB
                               `last-applied-configuration` annotation limit) while reporting
                               Synced at today's revision — and it is genuinely converged: an
                               unmanaged probe (`kubectl get cm` vs the manifest in git) found the
                               three theme keys byte-identical. Without the revision test this
                               detector is red on that app from the day it lands, which is the
                               one thing that reliably stops anyone reading it.
    UNVERIFIABLE_REVISION      (--repo) an app sourced from this repo whose `.status.sync.revision`
                               is not a commit reachable from `origin/main`. ANCESTRY, not an
                               image tag: a tag can be moved or reused, ancestry cannot (#6234,
                               where an older commit's PR closed a newer one and every
                               version-equality check stayed green).

WHAT IT DELIBERATELY DOES NOT DO
    It does not treat an old `.status.operationState.finishedAt` as a finding on its own. Most of
    this estate's Applications track a pinned upstream chart version and legitimately have not
    synced since May; measured 2026-08-23, 15 of 93 last synced more than a month ago and every
    one was correct. A detector that fires on all of them is one nobody reads by week two.

USAGE
    python3 .github/scripts/check-argocd-sync-integrity.py --capture apps.json [--repo .]
        where apps.json is `kubectl -n argocd get applications -o json`.
    python3 .github/scripts/check-argocd-sync-integrity.py --self-test
    python3 .github/scripts/check-argocd-sync-integrity.py --check-wiring

Exit 0 = no findings. Exit 1 = findings (or a broken/absent capture). Exit 2 = usage error.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import subprocess
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]

# Substring that identifies an Application sourced from THIS repo. Both the SSH and HTTPS
# spellings appear in gitops/apps, so match on the repo name rather than a full URL.
OWN_REPO_MARKER = "open-bank-oss"

FAILED_OPERATION_PHASES = {"Failed", "Error"}

# The wiring the CI half asserts still exists. A detector that runs nowhere reports nothing,
# which reads exactly like a detector reporting no findings.
CRONJOB_MANIFEST = (
    REPO / "openbank-infra/gitops/components/argocd-sync-verifier/argocd-sync-verifier.yaml"
)
APP_MANIFEST = REPO / "openbank-infra/gitops/apps/argocd-sync-verifier.yaml"
SCRIPT_REL = ".github/scripts/check-argocd-sync-integrity.py"


class Finding:
    def __init__(self, kind: str, app: str, detail: str):
        self.kind = kind
        self.app = app
        self.detail = detail

    def __str__(self) -> str:
        return f"{self.kind}  {self.app}: {self.detail}"


def _source_urls(spec: dict) -> list:
    srcs = spec.get("sources") or []
    if spec.get("source"):
        srcs = [spec["source"]] + list(srcs)
    return [s.get("repoURL") or "" for s in srcs]


def evaluate(apps: list, git_check=None) -> list:
    """Classify every Application. `git_check(rev) -> str|None` returns a reason or None."""
    findings = []
    for app in apps:
        name = app.get("metadata", {}).get("name", "<unnamed>")
        status = app.get("status") or {}
        sync = status.get("sync") or {}
        sync_status = sync.get("status")
        health = (status.get("health") or {}).get("status")

        for cond in status.get("conditions") or []:
            ctype = cond.get("type") or ""
            # `*Error` is the whole class the metrics cannot express: ComparisonError,
            # SyncError, InvalidSpecError, UnknownError. Matching the suffix rather than a
            # hard-coded list means a new ArgoCD condition type is covered on the day it
            # appears, not on the day someone notices it.
            if ctype.endswith("Error"):
                msg = " ".join((cond.get("message") or "").split())[:300]
                findings.append(
                    Finding(
                        "ERROR_CONDITION",
                        name,
                        f"condition {ctype} while sync={sync_status} health={health}. "
                        f"No ArgoCD metric exposes this. message: {msg}",
                    )
                )

        op = status.get("operationState") or {}
        phase = op.get("phase")
        op_rev = ((op.get("operation") or {}).get("sync") or {}).get("revision")
        # Only when the FAILED operation is the one that would have produced the revision now
        # being claimed. A failure at an older revision that the app has since been compared
        # against and found Synced is convergence, not masking — see the module docstring.
        if (
            sync_status == "Synced"
            and phase in FAILED_OPERATION_PHASES
            and op_rev
            and op_rev == sync.get("revision")
        ):
            findings.append(
                Finding(
                    "SYNCED_MASKING_FAILED_OP",
                    name,
                    f"reports sync=Synced at revision {str(sync.get('revision'))[:8]}, but the "
                    f"only sync operation at that revision finished phase={phase} at "
                    f"{op.get('finishedAt')}. Nothing ever successfully applied it.",
                )
            )

        if git_check is not None and any(OWN_REPO_MARKER in u for u in _source_urls(app.get("spec") or {})):
            reason = git_check(sync.get("revision") or "")
            if reason:
                findings.append(
                    Finding(
                        "UNVERIFIABLE_REVISION",
                        name,
                        f"{reason}. A deploy is verified by commit ANCESTRY, never by a tag "
                        f"or a version string (#6234).",
                    )
                )
    return findings


def make_git_check(repo: pathlib.Path, base_ref: str):
    """Return a git_check closure, or None if the repo cannot answer (never silently pass)."""

    def check(rev: str):
        if len(rev) != 40 or any(c not in "0123456789abcdef" for c in rev.lower()):
            return f"sync.revision {rev!r} is not a commit sha"
        known = subprocess.run(
            ["git", "-C", str(repo), "cat-file", "-e", rev + "^{commit}"],
            capture_output=True,
        )
        if known.returncode != 0:
            return f"sync.revision {rev[:8]} is not a commit in this repository"
        anc = subprocess.run(
            ["git", "-C", str(repo), "merge-base", "--is-ancestor", rev, base_ref],
            capture_output=True,
        )
        if anc.returncode != 0:
            return f"sync.revision {rev[:8]} is not an ancestor of {base_ref}"
        return None

    return check


def load_capture(path: pathlib.Path) -> list:
    doc = json.loads(path.read_text())
    if isinstance(doc, dict) and doc.get("kind") == "List" or "items" in (doc if isinstance(doc, dict) else {}):
        return doc["items"]
    if isinstance(doc, list):
        return doc
    if isinstance(doc, dict) and doc.get("kind") == "Application":
        return [doc]
    raise ValueError("capture is neither an Application, a list, nor a kubectl List")


# ─── wiring check (the CI half) ──────────────────────────────────────────────
def check_wiring() -> list:
    """The live detector runs in-cluster; assert it is still wired, in the repo, at PR time.

    An in-cluster detector that is deleted, unregistered or pointed at a different script stops
    producing findings — which is indistinguishable from producing none.
    """
    problems = []
    if not CRONJOB_MANIFEST.exists():
        problems.append(f"missing {CRONJOB_MANIFEST.relative_to(REPO)} — the in-cluster detector")
    else:
        text = CRONJOB_MANIFEST.read_text()
        if "kind: CronJob" not in text:
            problems.append(f"{CRONJOB_MANIFEST.relative_to(REPO)} declares no CronJob")
        if SCRIPT_REL not in text:
            problems.append(
                f"{CRONJOB_MANIFEST.relative_to(REPO)} does not invoke {SCRIPT_REL} — the "
                f"comparison must be the committed, unit-tested one, never a second inline copy"
            )
        # Reading Applications is the whole point; without the verb the job captures nothing
        # and exits 0.
        if "applications" not in text or "argoproj.io" not in text:
            problems.append(
                f"{CRONJOB_MANIFEST.relative_to(REPO)} grants no read on argoproj.io/applications"
            )
        if "exit 1" not in text:
            problems.append(
                f"{CRONJOB_MANIFEST.relative_to(REPO)} never fails the Job — a finding that only "
                f"lands in a ConfigMap is a finding nobody acts on"
            )
    if not APP_MANIFEST.exists():
        problems.append(
            f"missing {APP_MANIFEST.relative_to(REPO)} — the detector is not registered with ArgoCD"
        )
    return problems


# ─── self-test ───────────────────────────────────────────────────────────────
def _app(name, sync="Synced", health="Healthy", conditions=None, phase=None, revision=None, op_revision=None, own=True):
    spec = {"source": {"repoURL": ("git@github.com:JiRaska/open-bank-oss.git" if own else "https://grafana.github.io/helm-charts")}}
    status = {"sync": {"status": sync}, "health": {"status": health}}
    if conditions:
        status["conditions"] = conditions
    if phase:
        status["operationState"] = {
            "phase": phase,
            "finishedAt": "2026-08-21T17:25:22Z",
            "operation": {"sync": {"revision": op_revision if op_revision is not None else revision}},
        }
    if revision is not None:
        status["sync"]["revision"] = revision
    return {"metadata": {"name": name}, "spec": spec, "status": status}


LOKI_COMPARISON_ERROR = (
    "Failed to compare desired state to live state: failed to calculate diff: error calculating "
    "server side diff: serverSideDiff error: error running server side apply in dryrun mode for "
    'resource StatefulSet/loki: StatefulSet.apps "loki" is invalid: spec: Forbidden: updates to '
    "statefulset spec for fields other than 'replicas' are forbidden"
)


def self_test() -> int:
    """Feed the detector states it MUST classify, in both directions. A detector that has only
    ever seen healthy input is unfalsified."""
    cases = []

    # 1. THE REAL 2026-08-21 STATE, verbatim: Synced AND Healthy AND ComparisonError.
    #    This is the state that existed for three weeks and that nothing in the estate could see.
    cases.append((
        "loki as it really was on 2026-08-21 (Synced/Healthy + ComparisonError)",
        [_app("loki", conditions=[{"type": "ComparisonError", "message": LOKI_COMPARISON_ERROR}])],
        ["ERROR_CONDITION"],
    ))

    # 2. Negative control: the SAME app with the condition removed — today's real state.
    #    If this fires, the detector is keying on Synced/Healthy and is useless.
    cases.append(("loki as it is today (no condition)", [_app("loki")], []))

    # 3. Any other *Error condition is the same class, not a special case.
    for ctype in ("SyncError", "InvalidSpecError", "UnknownError"):
        cases.append((
            f"{ctype} is caught by the suffix rule",
            [_app("x", conditions=[{"type": ctype, "message": "m"}])],
            ["ERROR_CONDITION"],
        ))

    # 4. Non-error conditions must NOT fire. SharedResourceWarning is live on `agent` today;
    #    a detector that reddens on it is red from the day it lands.
    cases.append((
        "SharedResourceWarning does not fire",
        [_app("agent", sync="OutOfSync", conditions=[{"type": "SharedResourceWarning", "message": "m"}])],
        [],
    ))

    # 5. A failed sync operation masked behind sync=Synced.
    cases.append((
        "Synced at the very revision whose only sync operation Failed",
        [_app("y", phase="Failed", revision="c" * 40)],
        ["SYNCED_MASKING_FAILED_OP"],
    ))
    cases.append((
        "Synced with a Succeeded operation",
        [_app("y", phase="Succeeded", revision="c" * 40)],
        [],
    ))
    # OutOfSync + Failed is honest reporting, not masking — the status already says so.
    cases.append((
        "OutOfSync while the last operation Failed",
        [_app("y", sync="OutOfSync", phase="Failed", revision="c" * 40)],
        [],
    ))
    # THE REAL keycloak STATE, 2026-08-23: a Failed operation at ba578499 while Synced at
    # a13b85d3. Verified converged by an unmanaged probe (live ConfigMap == the manifest in
    # git, three keys byte-identical), so this MUST NOT fire. It is the negative control that
    # keeps this rule from being the resting state.
    cases.append((
        "keycloak: Failed operation at an OLDER revision, since converged",
        [_app("keycloak", phase="Failed", revision="a" * 40, op_revision="b" * 40)],
        [],
    ))

    # 6. Ancestry. The checker is handed a stub so the case is deterministic offline.
    def stub_git(rev):
        return None if rev == "a" * 40 else f"sync.revision {rev[:8]} is not an ancestor of origin/main"

    cases_git = [
        ("revision on main", [_app("z", revision="a" * 40)], []),
        ("revision NOT reachable from main", [_app("z", revision="b" * 40)], ["UNVERIFIABLE_REVISION"]),
        # An upstream-chart app carries a chart version, not a sha — it must be exempt, or the
        # detector is red on 22 of 93 apps forever.
        ("upstream chart app is exempt from ancestry", [_app("loki", revision="6.55.0", own=False)], []),
    ]

    failures = 0
    for label, apps, expect in cases:
        got = sorted(f.kind for f in evaluate(apps))
        if got != sorted(expect):
            print(f"SELF-TEST FAIL: {label}: expected {sorted(expect)}, got {got}")
            failures += 1
        else:
            print(f"  ok  {label} -> {got or 'no findings'}")
    for label, apps, expect in cases_git:
        got = sorted(f.kind for f in evaluate(apps, git_check=stub_git))
        if got != sorted(expect):
            print(f"SELF-TEST FAIL: {label}: expected {sorted(expect)}, got {got}")
            failures += 1
        else:
            print(f"  ok  {label} -> {got or 'no findings'}")

    # 7. The wiring half must be able to fail too: point it at a repo where the manifest is gone.
    global CRONJOB_MANIFEST
    saved = CRONJOB_MANIFEST
    CRONJOB_MANIFEST = REPO / "openbank-infra/gitops/components/argocd-sync-verifier/does-not-exist.yaml"
    if not check_wiring():
        print("SELF-TEST FAIL: wiring check passed with the detector manifest absent")
        failures += 1
    else:
        print("  ok  wiring check fails when the in-cluster detector is missing")
    CRONJOB_MANIFEST = saved
    if check_wiring():
        print(f"SELF-TEST FAIL: wiring check red on the real repo: {check_wiring()}")
        failures += 1
    else:
        print("  ok  wiring check green on the real repo")

    print(f"\nself-test: {failures} failure(s)")
    return 1 if failures else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--capture", help="kubectl -n argocd get applications -o json")
    ap.add_argument("--repo", help="repo checkout, enables the ancestry check")
    ap.add_argument("--base-ref", default="origin/main")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--check-wiring", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    if args.check_wiring:
        problems = check_wiring()
        if problems:
            print("FAIL: the in-cluster ArgoCD sync verifier is not wired (#6371):")
            for p in problems:
                print(f"  - {p}")
            return 1
        print("OK: the in-cluster ArgoCD sync verifier is wired and invokes the committed checker.")
        return 0

    if not args.capture:
        ap.error("one of --capture, --self-test or --check-wiring is required")

    path = pathlib.Path(args.capture)
    if not path.exists():
        # An absent capture is a FAILED capture, never "no findings".
        print(f"FAIL: capture {path} does not exist — the detector observed nothing.")
        return 1
    try:
        apps = load_capture(path)
    except Exception as exc:  # noqa: BLE001 - any parse problem is a failed capture
        print(f"FAIL: capture {path} is unreadable ({exc}) — the detector observed nothing.")
        return 1
    if not apps:
        print(f"FAIL: capture {path} holds zero Applications — the detector observed nothing.")
        return 1

    git_check = make_git_check(pathlib.Path(args.repo), args.base_ref) if args.repo else None
    findings = evaluate(apps, git_check=git_check)

    print(f"ArgoCD sync integrity — {len(apps)} Application(s) examined (#6371).")
    if not findings:
        print("OK: no Application is reporting a status it cannot back up.")
        return 0
    print(f"FAIL: {len(findings)} finding(s):")
    for f in findings:
        print(f"  - {f}")
    print(
        "\nA `ComparisonError` app keeps serving its LAST status — `Synced/Healthy` is not "
        "evidence anything was applied. Read .status.operationState.finishedAt, and confirm by "
        "rendering the chart from the app's own live valuesObject and diffing the live object."
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
