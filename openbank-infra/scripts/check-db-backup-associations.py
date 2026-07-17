#!/usr/bin/env python3
"""Assert every CNPG cluster that declares a backup has an EKS Pod Identity association.

Why this exists (issue #1444)
-----------------------------
Backup credentials reach a CNPG pod via **EKS Pod Identity**, granted per
``(namespace, serviceAccount)`` by an ``aws_eks_pod_identity_association`` in
``openbank-infra/aws/envs/sandbox-platform/db-backups.tf``. That association list was
maintained by hand, and its own comment claimed it covered "all remaining clusters with
barmanObjectStore". It did not: it named 36 entries against 51 live clusters.

The three clusters that declared a backup to the managed bucket but had no association
(``sdd-db``, ``tpp-registry-db``, ``vop-db``) failed **every** WAL archive with
``Barman cloud WAL archive check exception: Unable to locate credentials`` — sdd-db for three
days before anyone looked. Nothing linked the two sides: a new service copies a sibling's
``inheritFromIAMRole: true``, the Cluster reports Ready, the app reports Healthy, and the
backup silently never happens. There is no failure until someone needs a restore.

Same shape as the OPA generator gate (#1184), where a hard-coded list of 4 silently covered
25 for months. A hand-maintained list of things that must not be forgotten will be forgotten;
the fix is to check it mechanically, not to remember harder.

Why a CI guard and not a discovered ``for_each``
------------------------------------------------
Both in-Terraform routes were tried and are blocked:

* ``data.kubernetes_resources`` returns ``objects`` as a **dynamic** type, so it is
  ``(known after apply)`` and cannot drive a ``for_each`` (which needs plan-time keys). A
  ``tofu plan`` of that design produced ``0 to add, 35 to destroy`` — it would have revoked
  S3 credentials fleet-wide.
* ``fileset`` + ``yamldecode`` over the manifests cannot work either: 30 of the 42 files
  declaring a CNPG Cluster are multi-document YAML, which ``yamldecode`` does not parse.

A static check also fires at PR time rather than at ``tofu apply`` — earlier, and without
coupling the AWS layer to live cluster state.

Usage
-----
    python3 openbank-infra/scripts/check-db-backup-associations.py [--enforce]

Advisory (exit 0 on findings) unless ``--enforce``.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

import yaml

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
GITOPS_DIR = REPO_ROOT / "openbank-infra" / "gitops"
TF_FILE = REPO_ROOT / "openbank-infra" / "aws" / "envs" / "sandbox-platform" / "db-backups.tf"

# The bucket db-backups.tf provisions: "${local.cluster_name}-db-backups". Only clusters
# backing up HERE need an association against that bucket's role. One pointing elsewhere is
# reported as a warning (unmanaged lifecycle/retention), never an error — see main().
BACKUP_BUCKET = "openbank-sandbox-db-backups"

CNPG_API_PREFIX = "postgresql.cnpg.io"

def _rel(path: pathlib.Path) -> str:
    """Repo-relative path when possible; absolute otherwise (unit tests use a tmpdir)."""
    try:
        return str(path.relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


# `sa = "x"` inside the db_backup_clusters map, plus `service_account = "x"` for any
# standalone aws_eks_pod_identity_association resource. Matching both means a cluster covered
# either way counts as covered — the check is about the outcome, not the style.
_SA_RE = re.compile(r'\bsa\s*=\s*"([a-z0-9-]+)"')
_SERVICE_ACCOUNT_RE = re.compile(r'\bservice_account\s*=\s*"([a-z0-9-]+)"')


def declared_associations(tf_text: str) -> set[str]:
    """Service accounts granted a pod-identity association in db-backups.tf."""
    return set(_SA_RE.findall(tf_text)) | set(_SERVICE_ACCOUNT_RE.findall(tf_text))


def backing_up_clusters(gitops_dir: pathlib.Path) -> list[tuple[str, str, str, str]]:
    """Every CNPG Cluster in gitops that declares a barmanObjectStore.

    Returns (name, namespace, source-file, destinationPath) tuples — including clusters
    pointing at some OTHER bucket, so the caller can flag those too. CNPG names the instance
    pods' ServiceAccount after the Cluster, so `name` is also the SA to look for — verified
    against all 51 live clusters, none of which override it via spec.serviceAccountTemplate.

    Note the apiVersion filter: Strimzi's Kafka CRD *also* uses `kind: Cluster`, and several
    kafka-*-mtls.yaml manifests would otherwise be picked up as databases.
    """
    found: list[tuple[str, str, str, str]] = []
    for path in sorted(gitops_dir.rglob("*.yaml")):
        try:
            text = path.read_text()
        except OSError:
            continue
        if "kind: Cluster" not in text:
            continue
        try:
            docs = list(yaml.safe_load_all(text))
        except yaml.YAMLError:
            # A manifest this script cannot parse must not silently drop out of the check.
            print(f"::warning::could not parse {_rel(path)} — skipped", file=sys.stderr)
            continue
        for doc in docs:
            if not isinstance(doc, dict):
                continue
            if doc.get("kind") != "Cluster":
                continue
            if not str(doc.get("apiVersion", "")).startswith(CNPG_API_PREFIX):
                continue
            dest = (
                (doc.get("spec") or {}).get("backup", {}).get("barmanObjectStore", {}).get("destinationPath", "")
            )
            if not dest:
                continue
            meta = doc.get("metadata") or {}
            found.append((meta.get("name", "?"), meta.get("namespace", "?"), _rel(path), str(dest)))
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--enforce", action="store_true", help="exit non-zero on findings")
    args = parser.parse_args()

    if not TF_FILE.exists():
        print(f"::error::{TF_FILE} not found", file=sys.stderr)
        return 1

    declared = declared_associations(TF_FILE.read_text())
    clusters = backing_up_clusters(GITOPS_DIR)

    if not clusters:
        # Zero is never a legitimate answer here, and a check that silently passes when its
        # own discovery broke is worse than no check — that is the bug this file exists for.
        print(
            "::error::found no CNPG clusters backing up to "
            f"{BACKUP_BUCKET} — discovery is broken, not the fleet",
            file=sys.stderr,
        )
        return 1

    ours = [c for c in clusters if c[3].startswith(f"s3://{BACKUP_BUCKET}/")]
    # A cluster aimed at a DIFFERENT bucket is reported separately and much more mildly. Today
    # that is goalert-db on the legacy openbank-cnpg-backups-<acct> bucket, and its backups
    # WORK — it archives every 5 minutes and holds real base backups. It is simply outside the
    # lifecycle/encryption/retention rules db-backups.tf sets, which is a governance gap, not
    # an outage. Do not conflate the two: an early version of this check called it "no
    # backups", which was flatly wrong.
    elsewhere = [c for c in clusters if not c[3].startswith(f"s3://{BACKUP_BUCKET}/")]

    print(f"CNPG clusters declaring a barmanObjectStore: {len(clusters)}")
    print(f"  ...targeting {BACKUP_BUCKET}: {len(ours)}")
    print(f"pod-identity associations declared in {_rel(TF_FILE)}: {len(declared)}")

    missing = [c for c in ours if c[0] not in declared]
    level = "error" if args.enforce else "warning"

    for name, namespace, src, _dest in missing:
        print(
            f"::{level} file={src}::{name} (namespace {namespace}) declares "
            f"barmanObjectStore -> s3://{BACKUP_BUCKET}/ but has no aws_eks_pod_identity_association "
            f"in {_rel(TF_FILE)}. Its WAL archiving will fail with "
            f'"Unable to locate credentials" and it will have no backups. Add '
            f'`{namespace.replace("-", "_")} = {{ namespace = "{namespace}", sa = "{name}" }}` '
            f"to local.db_backup_clusters."
        )

    # Always a warning, never an error: these clusters' backups may well be working — they are
    # just not governed by db-backups.tf's lifecycle/encryption/retention. Failing a PR over
    # that would be wrong, and an inaccurate gate gets ignored, which is how the real ones
    # (above) went unnoticed for three days.
    for name, namespace, src, dest in elsewhere:
        print(
            f"::warning file={src}::{name} (namespace {namespace}) backs up to {dest}, outside the "
            f"Terraform-managed {BACKUP_BUCKET}. Its backups are not necessarily broken, but that "
            f"bucket's lifecycle, encryption and retention are not set by db-backups.tf. Either fold "
            f"the bucket into Terraform or repoint destinationPath at s3://{BACKUP_BUCKET}/{name} "
            f"(and add it to local.db_backup_clusters)."
        )

    if not missing:
        print("✓ every cluster targeting the managed bucket has an association")
        return 0

    print(f"\n{len(missing)} cluster(s) would silently never back up.", file=sys.stderr)
    return 1 if args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
