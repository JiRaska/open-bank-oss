#!/usr/bin/env python3
"""Assert every CNPG backup prefix equals the ServiceAccount its credentials are scoped to.

Why this exists
---------------
All CNPG clusters share ONE IAM role for barman-cloud (``aws_iam_role.db_backups`` in
``openbank-infra/aws/envs/sandbox-platform/db-backups.tf``). Its object grants are scoped with
the EKS Pod Identity session tag::

    arn:aws:s3:::<bucket>/${aws:PrincipalTag/kubernetes-service-account}/*

so a compromised database pod can read, overwrite or delete only its OWN archive — not every
other cluster's base backups and WAL, which is what the bucket-wide grant allowed before.

That scoping is only correct while one equality holds for every cluster backing up to the
managed bucket::

    first path segment of barmanObjectStore.destinationPath == Cluster name == ServiceAccount

(CNPG names the instance ServiceAccount after the Cluster.) A cluster that breaks it — a
renamed prefix, a copied manifest with the sibling's destinationPath — is DENIED by IAM, and a
denied WAL archive is easy to miss: the pod stays Ready, the service stays Healthy. So the
equality is checked here, at PR time, rather than discovered at restore time.

Also checked, because each would quietly undo the scoping:

* the role policy in db-backups.tf still scopes object access by the SA tag (a regression to
  ``<bucket>/*`` would pass every other gate);
* every ``sa`` in the association map is unique — the tag is the SA name alone, so two
  namespaces sharing one SA name would share one archive;
* a CNPG ``externalClusters[].barmanObjectStore`` that READS another cluster's prefix (a
  restore) must be a declared cross-prefix reader in ``CROSS_PREFIX_READERS``, mirroring the
  explicit read-only statement in db-backups.tf.

Deliberately NOT touched: ``check-db-backup-associations.py`` (association presence/applied).
This gate only asks whether the archive path and the credential scope agree.

Usage
-----
    python3 openbank-infra/scripts/check-db-backup-prefix-scope.py [--enforce] [--self-test]
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys
import tempfile

import yaml

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
GITOPS_DIR = REPO_ROOT / "openbank-infra" / "gitops"
TF_FILE = REPO_ROOT / "openbank-infra" / "aws" / "envs" / "sandbox-platform" / "db-backups.tf"
BACKUP_BUCKET = "openbank-sandbox-db-backups"
CNPG_API_PREFIX = "postgresql.cnpg.io"

# (reader SA, reader namespace) -> the archive prefix it may READ. Must mirror the explicit
# cross-prefix statements in db-backups.tf; anything else reading a foreign prefix is denied
# by IAM and is reported here before it is discovered mid-restore.
CROSS_PREFIX_READERS: dict[tuple[str, str], str] = {
    ("ledger-db-drill", "ledger"): "ledger-db",  # runbook-0003 restore drill
}

SCOPED_RESOURCE_RE = re.compile(r"\$\$\{aws:PrincipalTag/kubernetes-service-account\}")
BUCKET_WIDE_OBJECT_RE = re.compile(r'resources\s*=\s*\[\s*"\$\{aws_s3_bucket\.db_backups\.arn\}/\*"\s*\]')
SA_ENTRY_RE = re.compile(r'namespace\s*=\s*"([a-z0-9-]+)"\s*,\s*sa\s*=\s*"([a-z0-9-]+)"')


def _rel(path: pathlib.Path) -> str:
    try:
        return str(path.relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def bucket_prefix(dest: str) -> tuple[str, str] | None:
    """('bucket', 'first-segment') for an s3:// destinationPath, else None."""
    m = re.match(r"^s3://([^/]+)/?([^/]*)", dest.strip())
    if not m:
        return None
    return m.group(1), m.group(2)


def cnpg_clusters(gitops_dir: pathlib.Path) -> list[dict]:
    out: list[dict] = []
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
            print(f"::error::could not parse {_rel(path)} — a CNPG cluster there would be unchecked",
                  file=sys.stderr)
            out.append({"parse_error": _rel(path)})
            continue
        for doc in docs:
            if not isinstance(doc, dict) or doc.get("kind") != "Cluster":
                continue
            if not str(doc.get("apiVersion", "")).startswith(CNPG_API_PREFIX):
                continue
            meta = doc.get("metadata") or {}
            spec = doc.get("spec") or {}
            out.append({
                "name": meta.get("name", "?"),
                "namespace": meta.get("namespace", "?"),
                "file": _rel(path),
                "dest": ((spec.get("backup") or {}).get("barmanObjectStore") or {}).get("destinationPath", ""),
                "external": [
                    ((ec or {}).get("barmanObjectStore") or {}).get("destinationPath", "")
                    for ec in (spec.get("externalClusters") or [])
                ],
            })
    return out


def check(clusters: list[dict], tf_text: str) -> tuple[list[str], int]:
    findings: list[str] = []
    subjects = 0

    if not SCOPED_RESOURCE_RE.search(tf_text):
        findings.append("db-backups.tf: role policy no longer scopes objects by "
                        "${aws:PrincipalTag/kubernetes-service-account} — every cluster could "
                        "read/delete every other cluster's backups")
    if BUCKET_WIDE_OBJECT_RE.search(tf_text):
        findings.append("db-backups.tf: a statement grants objects bucket-wide "
                        "(\"${aws_s3_bucket.db_backups.arn}/*\") — that re-opens cross-cluster access")

    entries = SA_ENTRY_RE.findall(tf_text)
    seen: dict[str, str] = {}
    for ns, sa in entries:
        if sa in seen and seen[sa] != ns:
            findings.append(f"db-backups.tf: SA `{sa}` associated in namespaces `{seen[sa]}` and "
                            f"`{ns}` — the SA tag alone would give both one shared archive")
        seen.setdefault(sa, ns)

    for c in clusters:
        if "parse_error" in c:
            findings.append(f"{c['parse_error']}: unparseable manifest")
            continue
        bp = bucket_prefix(str(c["dest"])) if c["dest"] else None
        if bp and bp[0] == BACKUP_BUCKET:
            subjects += 1
            if bp[1] != c["name"]:
                findings.append(
                    f"{c['file']}: Cluster `{c['namespace']}/{c['name']}` archives to prefix "
                    f"`{bp[1] or '<bucket root>'}`, but its credentials are scoped to `{c['name']}/` "
                    f"(the ServiceAccount = Cluster name). Every WAL archive would be DENIED. Set "
                    f"destinationPath: s3://{BACKUP_BUCKET}/{c['name']}")
        for ext in c["external"]:
            ebp = bucket_prefix(str(ext)) if ext else None
            if not ebp or ebp[0] != BACKUP_BUCKET or ebp[1] == c["name"]:
                continue
            allowed = CROSS_PREFIX_READERS.get((c["name"], c["namespace"]))
            if allowed != ebp[1]:
                findings.append(
                    f"{c['file']}: Cluster `{c['namespace']}/{c['name']}` restores from foreign "
                    f"prefix `{ebp[1]}/`, which IAM denies to SA `{c['name']}`. Add an explicit "
                    f"read-only statement in db-backups.tf AND an entry in CROSS_PREFIX_READERS")
    return findings, subjects


def self_test() -> int:
    fails: list[str] = []
    good_tf = (
        'resources = ["${aws_s3_bucket.db_backups.arn}/${local.db_backup_own_prefix}/*"]\n'
        'db_backup_own_prefix = "$${aws:PrincipalTag/kubernetes-service-account}"\n'
        'a = { namespace = "ledger", sa = "ledger-db" }\n'
        'b = { namespace = "payments", sa = "card-processing-db" }\n'
    )

    def run(label, clusters, tf, want_findings):
        got, _ = check(clusters, tf)
        if bool(got) != want_findings:
            fails.append(f"{label}: expected findings={want_findings}, got {got}")

    ok = {"name": "ledger-db", "namespace": "ledger", "file": "f", "external": [],
          "dest": f"s3://{BACKUP_BUCKET}/ledger-db"}
    run("matching prefix is clean", [ok], good_tf, False)
    run("mismatched prefix is flagged",
        [dict(ok, dest=f"s3://{BACKUP_BUCKET}/ledger")], good_tf, True)
    run("bucket-root destination is flagged",
        [dict(ok, dest=f"s3://{BACKUP_BUCKET}")], good_tf, True)
    run("other bucket is out of scope",
        [dict(ok, dest="s3://some-other-bucket/whatever")], good_tf, False)
    run("tf without tag scoping is flagged", [ok],
        good_tf.replace("$${aws:PrincipalTag/kubernetes-service-account}", "x"), True)
    run("tf with bucket-wide object grant is flagged", [ok],
        good_tf + 'resources = ["${aws_s3_bucket.db_backups.arn}/*"]\n', True)
    run("duplicate SA across namespaces is flagged", [ok],
        good_tf + 'c = { namespace = "other", sa = "ledger-db" }\n', True)
    drill = {"name": "ledger-db-drill", "namespace": "ledger", "file": "f", "dest": "",
             "external": [f"s3://{BACKUP_BUCKET}/ledger-db"]}
    run("declared cross-prefix reader is clean", [drill], good_tf, False)
    run("undeclared cross-prefix reader is flagged",
        [dict(drill, name="kyc-db-drill", namespace="kyc")], good_tf, True)
    run("declared reader in wrong namespace is flagged",
        [dict(drill, namespace="payments")], good_tf, True)

    # The real parser over a real file — an empty parse must not read as clean.
    with tempfile.TemporaryDirectory() as d:
        p = pathlib.Path(d) / "c.yaml"
        p.write_text(
            "apiVersion: postgresql.cnpg.io/v1\nkind: Cluster\nmetadata: {name: x-db, namespace: x}\n"
            f"spec:\n  backup:\n    barmanObjectStore:\n      destinationPath: s3://{BACKUP_BUCKET}/y-db\n"
            "---\napiVersion: kafka.strimzi.io/v1beta2\nkind: Cluster\nmetadata: {name: k}\n")
        parsed = cnpg_clusters(pathlib.Path(d))
        if [c.get("name") for c in parsed] != ["x-db"]:
            fails.append(f"parser: expected only the CNPG cluster, got {parsed}")
        got, n = check(parsed, good_tf)
        if not got or n != 1:
            fails.append(f"parser end-to-end: expected 1 subject + a finding, got {n}, {got}")

    for f in fails:
        print(f"SELF-TEST FAIL: {f}", file=sys.stderr)
    print("self-test:", "FAIL" if fails else "PASS")
    return 1 if fails else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--enforce", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()

    clusters = cnpg_clusters(GITOPS_DIR)
    findings, subjects = check(clusters, TF_FILE.read_text())
    print(f"SUBJECTS={subjects}")
    if subjects == 0:
        print("::error::no CNPG cluster backs up to the managed bucket — the scan found nothing, "
              "which is a broken probe, not a clean fleet")
        return 1
    for f in findings:
        print(f"::{'error' if args.enforce else 'warning'}::{f}")
    if findings:
        return 1 if args.enforce else 0
    print(f"OK: {subjects} CNPG backup prefixes match the ServiceAccount their credentials are scoped to")
    return 0


if __name__ == "__main__":
    sys.exit(main())
