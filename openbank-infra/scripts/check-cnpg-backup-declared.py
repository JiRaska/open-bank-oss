#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Every CNPG Cluster in gitops declares a backup, or says in place why it does not.

WHY THIS EXISTS, AND WHY check-db-backup-associations.py CANNOT DO IT.

That gate asserts a declared backup is correctly wired: a cluster declaring
`barmanObjectStore` must have a matching `aws_eks_pod_identity_association` in
db-backups.tf, or its WAL archiving fails silently. It is enforced, and it works.

Its subject set, though, is exactly "clusters that declare a barmanObjectStore" -- the
scan does `if not dest: continue`. A cluster with no backup block at all is not a
mismatched pair, so it is skipped, and skipped is indistinguishable from clean. The
gate that exists to make an unrecoverable database visible therefore cannot see the
most unrecoverable kind: the one that never asked to be backed up.

Measured against the sandbox on 2026-08-16: 59 live CNPG clusters, 57 with a recovery
point, 0 declared-but-unrecoverable -- and 2 with no backup declared at all. The
sibling gate reported clean on all of it, correctly and uselessly.

WHAT THIS ASSERTS

For every CNPG Cluster manifest under gitops, one of:
  * `spec.backup.barmanObjectStore.destinationPath` is set -- the other gate takes over
    from there and checks it is actually wired; or
  * the metadata carries `openbank.io/backup-exempt-reason: "<why>"`, non-empty.

The exemption is an ANNOTATION ON THE CLUSTER, deliberately, not an entry in a list
kept beside the manifests. A separate list is free to drift from the thing it
describes and nothing notices -- which is the defect this repo keeps re-finding. An
annotation moves with the file, is visible to anyone reading the manifest, and cannot
outlive the cluster it excuses.

WHAT IT CANNOT SEE, STATED SO NOBODY READS ITS SILENCE AS COVERAGE

A database that is not in gitops at all. `glitchtip-pg` in the observability namespace
is live and has no backup, and it is deployed by a third-party chart rather than by a
manifest in this tree, so no gitops-scoped check can reach it. This gate's subject is
the gitops tree, and that is a smaller set than "the databases that exist". Comparing
the two needs cluster access and belongs in a runtime control, not here.
"""
from __future__ import annotations

import argparse
import pathlib
import sys

import yaml

CNPG_API_PREFIX = "postgresql.cnpg.io/"
EXEMPT_ANNOTATION = "openbank.io/backup-exempt-reason"


def cnpg_clusters(gitops_dir: pathlib.Path):
    """Yield (name, namespace, relpath, destinationPath, exempt_reason) per CNPG Cluster."""
    for path in sorted(gitops_dir.rglob("*.yaml")):
        try:
            docs = list(yaml.safe_load_all(path.read_text()))
        except yaml.YAMLError:
            continue
        for doc in docs:
            if not isinstance(doc, dict):
                continue
            if doc.get("kind") != "Cluster":
                continue
            if not str(doc.get("apiVersion", "")).startswith(CNPG_API_PREFIX):
                continue
            meta = doc.get("metadata") or {}
            spec = doc.get("spec") or {}
            dest = (
                ((spec.get("backup") or {}).get("barmanObjectStore") or {}).get("destinationPath", "")
            )
            reason = ((meta.get("annotations") or {}).get(EXEMPT_ANNOTATION) or "").strip()
            yield (
                meta.get("name", "<unnamed>"),
                meta.get("namespace", "<no-namespace>"),
                str(path),
                dest,
                reason,
            )


def check(gitops_dir: pathlib.Path) -> tuple[int, int]:
    findings = 0
    subjects = 0
    for name, ns, rel, dest, reason in cnpg_clusters(gitops_dir):
        subjects += 1
        if dest:
            continue
        if reason:
            print(f"  exempt: {ns}/{name} — {reason}")
            continue
        findings += 1
        print(
            f"::error file={rel}::CNPG Cluster {ns}/{name} declares no "
            f"spec.backup.barmanObjectStore, so it has no recovery point and nothing else "
            f"reports it — check-db-backup-associations only inspects clusters that DO declare "
            f"one. Either add a barmanObjectStore, or annotate the cluster with "
            f'{EXEMPT_ANNOTATION}: "<why this database is disposable>".'
        )
    return subjects, findings


def self_test() -> int:
    """Falsify in both directions: the gate must FIRE on a bare cluster and must NOT on the others.

    A self-test that only builds a passing case proves the gate can be silent, which is
    the one thing never in doubt.
    """
    import tempfile

    cases = [
        ("declares a backup", {
            "apiVersion": "postgresql.cnpg.io/v1", "kind": "Cluster",
            "metadata": {"name": "a", "namespace": "n"},
            "spec": {"backup": {"barmanObjectStore": {"destinationPath": "s3://x"}}},
        }, 0),
        ("no backup, no reason — MUST fire", {
            "apiVersion": "postgresql.cnpg.io/v1", "kind": "Cluster",
            "metadata": {"name": "b", "namespace": "n"},
            "spec": {"instances": 1},
        }, 1),
        ("no backup, exempt with a reason", {
            "apiVersion": "postgresql.cnpg.io/v1", "kind": "Cluster",
            "metadata": {"name": "c", "namespace": "n",
                         "annotations": {EXEMPT_ANNOTATION: "scratch data, rebuilt on boot"}},
            "spec": {"instances": 1},
        }, 0),
        ("empty reason is not a reason — MUST fire", {
            "apiVersion": "postgresql.cnpg.io/v1", "kind": "Cluster",
            "metadata": {"name": "d", "namespace": "n",
                         "annotations": {EXEMPT_ANNOTATION: "   "}},
            "spec": {"instances": 1},
        }, 1),
        ("a non-CNPG kind: Cluster is not a subject", {
            "apiVersion": "cluster.x-k8s.io/v1", "kind": "Cluster",
            "metadata": {"name": "e", "namespace": "n"}, "spec": {},
        }, 0),
    ]
    failures = 0
    for label, doc, want in cases:
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d)
            (p / "m.yaml").write_text(yaml.safe_dump(doc))
            _, got = check(p)
        status = "ok" if got == want else "FAIL"
        if got != want:
            failures += 1
        print(f"  {status:4}  want={want} got={got}  {label}")
    print("self-test: PASS" if not failures else f"self-test: FAIL ({failures})")
    return 1 if failures else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--gitops", default="openbank-infra/gitops")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--enforce", action="store_true")
    args = ap.parse_args()

    if args.self_test:
        return self_test()

    gitops = pathlib.Path(args.gitops)
    if not gitops.is_dir():
        print(f"::error::{gitops} is not a directory — refusing to report clean on an unread tree.")
        return 1

    subjects, findings = check(gitops)
    print(f"SUBJECTS={subjects}  # CNPG Cluster manifests under {gitops}")
    if subjects == 0:
        print("::error::found no CNPG Cluster manifests at all — that is a broken scan, not a clean tree.")
        return 1
    if findings:
        print(f"{findings} CNPG cluster(s) with no declared backup and no stated reason.")
        return 1 if args.enforce else 0
    print("OK: every CNPG cluster in gitops declares a backup or says why it does not.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
