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
import json
import pathlib
import re
import shutil
import subprocess
import sys

import yaml

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
GITOPS_DIR = REPO_ROOT / "openbank-infra" / "gitops"
TF_FILE = REPO_ROOT / "openbank-infra" / "aws" / "envs" / "sandbox-platform" / "db-backups.tf"

# The bucket db-backups.tf provisions: "${local.cluster_name}-db-backups". Only clusters
# backing up HERE need an association against that bucket's role. One pointing elsewhere is
# reported as a warning (unmanaged lifecycle/retention), never an error — see main().
BACKUP_BUCKET = "openbank-sandbox-db-backups"

# Default EKS cluster + region the associations live in (env.AWS_REGION in platform-tofu.yml).
DEFAULT_EKS_CLUSTER = "openbank-sandbox"
DEFAULT_AWS_REGION = "eu-north-1"

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
        found.extend(clusters_in_text(text, _rel(path)))
    return found


def clusters_in_text(text: str, src: str) -> list[tuple[str, str, str, str]]:
    """The CNPG Clusters declaring a barmanObjectStore in ONE manifest's text.

    Split out of backing_up_clusters so the same-diff check can parse a file's BASE revision
    (from `git show`) with exactly the reader the tree-wide check uses — two readers of one
    shape drift apart, and then the two checks disagree about what a backed-up cluster is.
    """
    if "kind: Cluster" not in text:
        return []
    try:
        docs = list(yaml.safe_load_all(text))
    except yaml.YAMLError:
        # A manifest this script cannot parse must not silently drop out of the check.
        print(f"::warning::could not parse {src} — skipped", file=sys.stderr)
        return []
    found: list[tuple[str, str, str, str]] = []
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
        found.append((meta.get("name", "?"), meta.get("namespace", "?"), src, str(dest)))
    return found


TF_REL = "openbank-infra/aws/envs/sandbox-platform/db-backups.tf"
GITOPS_REL = "openbank-infra/gitops/"


def _git(root: pathlib.Path, *args: str) -> str:
    return subprocess.run(
        ["git", "-C", str(root), *args], capture_output=True, text=True, check=True
    ).stdout


def _git_show_or_empty(root: pathlib.Path, base: str, rel: str) -> str:
    """A file's content at `base`, or "" when it did not exist there (a NEW manifest)."""
    proc = subprocess.run(
        ["git", "-C", str(root), "show", f"{base}:{rel}"], capture_output=True, text=True, check=False
    )
    return proc.stdout if proc.returncode == 0 else ""


def same_diff_violations(root: pathlib.Path, base: str) -> list[tuple[str, str, str]]:
    """Clusters whose backup config AND pod-identity association both arrive in this diff.

    EKS Pod Identity injects credentials at pod ADMISSION. GitOps deploys a merged Cluster
    within minutes, while the association exists only once someone runs `tofu apply` on
    db-backups.tf. So when one PR carries both, the pod is admitted with no credentials, and it
    stays that way until it is recreated: every WAL archive fails "Unable to locate
    credentials". pricing-db (2026-09-28) and card-processing-db (#8837: cluster created
    10-01 20:04:55Z, ~4 min after merge; ~8.7 h without a single archived WAL) both shipped
    that shape. card-processing's tf entry even carried a comment asserting that adding the
    two together was the SAFE order.

    A "new" backup is a (name, namespace) that declares a barmanObjectStore at HEAD and did
    not at `base`, so both a brand-new Cluster and backup added to an existing one count. It
    is a violation only when that cluster's ServiceAccount is declared at HEAD but NOT at
    `base` — an association already on main is assumed applied (the scheduled
    --check-applied run is what verifies that).
    """
    # An unresolvable base must be a loud failure: read as "no file at base", it would make every
    # association look new — or, with a typo'd tf path, none — and the verdict meaningless.
    _git(root, "rev-parse", "--verify", "--quiet", f"{base}^{{commit}}")
    changed = [
        f for f in _git(root, "diff", "--name-only", base).splitlines()
        if f.startswith(GITOPS_REL) and f.endswith((".yaml", ".yml"))
    ]
    base_backed: set[tuple[str, str]] = set()
    head: list[tuple[str, str, str, str]] = []
    for rel in changed:
        base_backed |= {(n, ns) for n, ns, _s, _d in clusters_in_text(_git_show_or_empty(root, base, rel), rel)}
        path = root / rel
        if path.exists():
            head.extend(clusters_in_text(path.read_text(), rel))
    new = [(n, ns, src) for n, ns, src, _d in head if (n, ns) not in base_backed]
    if not new:
        return []
    tf_head = (root / TF_REL).read_text() if (root / TF_REL).exists() else ""
    sa_base = declared_associations(_git_show_or_empty(root, base, TF_REL))
    sa_head = declared_associations(tf_head)
    return [(n, ns, src) for n, ns, src in new if n in sa_head and n not in sa_base]


def parse_applied_associations(aws_json: str) -> set[str]:
    """Service accounts with a LIVE pod-identity association, from `aws eks
    list-pod-identity-associations --output json`. The CLI auto-paginates, so a single
    invocation returns every association; each carries `serviceAccount` + `namespace`."""
    data = json.loads(aws_json)
    return {a["serviceAccount"] for a in data.get("associations", [])}


def applied_associations(cluster_name: str, region: str) -> set[str]:
    """Query AWS for the service accounts that actually have a pod-identity association.

    This is the half the static (repo-only) check is blind to: db-backups.tf can *declare* an
    association that was never `tofu apply`d, so the tf list and the gitops list agree (green)
    while AWS has no association and every WAL archive fails "Unable to locate credentials"
    (issue #1759). A missing/old aws CLI or a failed call is a HARD error — a live check that
    silently passes when it could not actually look is the exact failure mode being fixed."""
    if shutil.which("aws") is None:
        print("::error::aws CLI not found — cannot run --check-applied", file=sys.stderr)
        raise SystemExit(1)
    cmd = [
        "aws", "eks", "list-pod-identity-associations",
        "--cluster-name", cluster_name, "--region", region, "--output", "json",
    ]
    try:
        out = subprocess.run(cmd, capture_output=True, text=True, check=True, timeout=120).stdout
    except subprocess.CalledProcessError as exc:
        # list-pod-identity-associations needs aws CLI >= 2.15; an older CLI errors here.
        print(f"::error::`{' '.join(cmd)}` failed: {exc.stderr.strip()}", file=sys.stderr)
        raise SystemExit(1) from exc
    except subprocess.TimeoutExpired as exc:
        print(f"::error::`{' '.join(cmd)}` timed out", file=sys.stderr)
        raise SystemExit(1) from exc
    return parse_applied_associations(out)


def self_test() -> int:
    """Falsify the three readers this gate correlates.

    A CNPG cluster that declares a barmanObjectStore but whose ServiceAccount has no
    pod-identity association takes NO BACKUPS — and CNPG reports that as a backup failure in
    its own status, nowhere a human looks. The database keeps serving, the WAL keeps rotating,
    and the absence is discovered at restore time, the one moment it cannot be fixed.

    Each of the three inputs can silently come back EMPTY, and an empty input makes the
    correlation agree — this gate's own failure mode is the shape it exists to catch.
    """
    import tempfile
    import json as _json

    fails: list[str] = []

    def case(label, got, want):
        if got != want:
            fails.append(f"{label}: expected {want}, got {got}")

    # --- what terraform DECLARES ----------------------------------------------------------
    tf = (
        'resource "aws_eks_pod_identity_association" "ledger" {\n'
        '  service_account = "ledger-db"\n'
        '  namespace       = "openbank"\n'
        '}\n'
        'resource "aws_eks_pod_identity_association" "party" {\n'
        '  service_account = "party-db"\n'
        '}\n'
    )
    case("declared associations are read", sorted(declared_associations(tf)),
         ["ledger-db", "party-db"])
    # An empty tf declares nothing — which the caller must never read as "all clusters
    # covered". Pinned so the emptiness is a known property rather than an accident.
    case("an empty tf declares nothing", declared_associations(""), set())

    # --- what AWS has APPLIED --------------------------------------------------------------
    live = _json.dumps({"associations": [
        {"serviceAccount": "ledger-db", "namespace": "openbank"},
        {"serviceAccount": "party-db", "namespace": "openbank"},
    ]})
    case("applied associations are parsed", sorted(parse_applied_associations(live)),
         ["ledger-db", "party-db"])
    # THE HALF THE STATIC CHECK IS BLIND TO: a declaration never applied. An empty list must
    # parse as empty rather than raise, or the live comparison is skipped and repo-only
    # agreement is mistaken for coverage.
    case("no live associations parses as empty",
         parse_applied_associations('{"associations": []}'), set())
    case("a response with no associations key parses as empty",
         parse_applied_associations("{}"), set())

    # --- which clusters actually BACK UP ---------------------------------------------------
    with tempfile.TemporaryDirectory() as td:
        d = pathlib.Path(td)
        (d / "ledger.yaml").write_text(
            "apiVersion: postgresql.cnpg.io/v1\nkind: Cluster\n"
            "metadata:\n  name: ledger-db\n  namespace: openbank\n"
            "spec:\n  backup:\n    barmanObjectStore:\n      destinationPath: s3://backups/ledger\n")
        # No barmanObjectStore ⇒ not backing up ⇒ not this gate's subject. Including it would
        # demand an association for a database that takes no backups.
        (d / "nobackup.yaml").write_text(
            "apiVersion: postgresql.cnpg.io/v1\nkind: Cluster\n"
            "metadata:\n  name: cache-db\n  namespace: openbank\nspec:\n  instances: 1\n")
        # STRIMZI also uses `kind: Cluster`. Without the apiVersion filter every Kafka
        # manifest scores as a database with missing backups — noise that gets a gate switched
        # off, which is the same outcome as not having one.
        (d / "kafka.yaml").write_text(
            "apiVersion: kafka.strimzi.io/v1beta2\nkind: Cluster\n"
            "metadata:\n  name: events\n  namespace: openbank\n"
            "spec:\n  backup:\n    barmanObjectStore:\n      destinationPath: s3://x\n")
        # Malformed YAML must be skipped with a warning, not take the scan down — one bad file
        # would otherwise hide every cluster after it.
        (d / "broken.yaml").write_text("kind: Cluster\n  bad: [indent\n")

        found = sorted(name for name, _ns, _src, _dest in backing_up_clusters(d))
        case("only CNPG clusters WITH a barmanObjectStore are found", found, ["ledger-db"])
        case("an empty gitops dir finds no clusters", backing_up_clusters(d / "nope"), [])

    # --- same-diff rule: backup config + association must NOT land together -----------------
    # Real git repos, because the subject is a DIFF: the reader of the base revision is the
    # part most likely to come back empty and make every cluster look "new" or none.
    import os as _os

    def _repo(td: pathlib.Path) -> pathlib.Path:
        env = {**_os.environ, "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t",
               "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@t"}
        subprocess.run(["git", "init", "-q", str(td)], check=True, env=env)
        _repo.env = env  # type: ignore[attr-defined]
        return td

    def _commit(td: pathlib.Path, files: dict[str, str]) -> str:
        for rel, body in files.items():
            (td / rel).parent.mkdir(parents=True, exist_ok=True)
            (td / rel).write_text(body)
        subprocess.run(["git", "-C", str(td), "add", "-A"], check=True)
        subprocess.run(["git", "-C", str(td), "-c", "commit.gpgsign=false", "commit", "-qm", "x"],
                       check=True, env=_repo.env)  # type: ignore[attr-defined]
        return _git(td, "rev-parse", "HEAD").strip()

    tf0 = 'locals {\n  db_backup_clusters = {\n    party = { namespace = "party", sa = "party-db" }\n  }\n}\n'
    tf1 = tf0.replace('  }\n}', '    card-processing = { namespace = "payments", sa = "card-processing-db" }\n  }\n}')
    cl_nobackup = ("apiVersion: postgresql.cnpg.io/v1\nkind: Cluster\n"
                   "metadata:\n  name: card-processing-db\n  namespace: payments\nspec:\n  instances: 2\n")
    cl_backup = cl_nobackup + ("  backup:\n    barmanObjectStore:\n"
                               "      destinationPath: s3://openbank-sandbox-db-backups/card-processing-db\n")
    pg = GITOPS_REL + "components/payments/postgres.yaml"
    with tempfile.TemporaryDirectory() as td:
        # #8837's exact shape: new cluster with backup + its association, one diff -> RED.
        r = _repo(pathlib.Path(td))
        base = _commit(r, {TF_REL: tf0, GITOPS_REL + "components/x.yaml": "a: 1\n"})
        _commit(r, {TF_REL: tf1, pg: cl_backup})
        case("#8837 shape (new cluster + association in one diff) is flagged",
             [v[0] for v in same_diff_violations(r, base)], ["card-processing-db"])
    with tempfile.TemporaryDirectory() as td:
        # Backup ADDED to an existing cluster, association in the same diff -> RED too.
        r = _repo(pathlib.Path(td))
        base = _commit(r, {TF_REL: tf0, pg: cl_nobackup})
        _commit(r, {TF_REL: tf1, pg: cl_backup})
        case("backup added to an existing cluster + association in one diff is flagged",
             [v[0] for v in same_diff_violations(r, base)], ["card-processing-db"])
    with tempfile.TemporaryDirectory() as td:
        # The sanctioned order: association already on base, backup config lands now -> GREEN.
        r = _repo(pathlib.Path(td))
        base = _commit(r, {TF_REL: tf1, GITOPS_REL + "components/x.yaml": "a: 1\n"})
        _commit(r, {pg: cl_backup})
        case("association already on base passes", same_diff_violations(r, base), [])
    with tempfile.TemporaryDirectory() as td:
        # An unrelated edit to an already-backed-up cluster plus a tf change -> GREEN.
        r = _repo(pathlib.Path(td))
        base = _commit(r, {TF_REL: tf1, pg: cl_backup})
        _commit(r, {TF_REL: tf1 + "# c\n", pg: cl_backup.replace("instances: 2", "instances: 3")})
        case("editing an already-backed-up cluster passes", same_diff_violations(r, base), [])

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: db-backup associations are falsifiable (13 cases)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--enforce", action="store_true", help="exit non-zero on findings")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument(
        "--check-applied",
        action="store_true",
        help="also assert each declared association is LIVE in AWS (needs aws creds + CLI >= 2.15)",
    )
    parser.add_argument(
        "--base",
        help="also enforce the same-diff rule against this ref (PR_DIFF_BASE): a PR must not add a "
        "cluster's backup config AND its pod-identity association together",
    )
    parser.add_argument("--cluster-name", default=DEFAULT_EKS_CLUSTER, help="EKS cluster name")
    parser.add_argument("--region", default=DEFAULT_AWS_REGION, help="AWS region")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

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

    # Live-AWS drift: a declared association that was never `tofu apply`d. The static checks
    # above cannot see this — tf and gitops both list the cluster (green) while AWS has no
    # association, so barman fails every archive with "Unable to locate credentials" (#1759).
    # Opt-in (needs aws creds) and always an ERROR: unapplied credentials ARE broken backups.
    drift: list[tuple[str, str]] = []
    if args.check_applied:
        applied = applied_associations(args.cluster_name, args.region)
        print(f"pod-identity associations LIVE in AWS ({args.cluster_name}): {len(applied)}")
        # Only clusters that target our bucket AND are declared in tf: an undeclared one is
        # already reported as `missing` above; reporting it again as drift is just noise.
        drift = [(name, ns) for name, ns, _, _ in ours if name in declared and name not in applied]
        for name, namespace in drift:
            print(
                f"::{level}::{name} (namespace {namespace}) is declared in {_rel(TF_FILE)} but has "
                f"NO live pod-identity association in AWS — db-backups.tf was never applied for it. "
                f'Its WAL archiving fails "Unable to locate credentials". Run `tofu apply` in '
                f"openbank-infra/aws/envs/sandbox-platform (expect adds only, 0 destroy)."
            )

    split: list[tuple[str, str, str]] = []
    if args.base:
        split = same_diff_violations(REPO_ROOT, args.base)
        for name, namespace, src in split:
            print(
                f"::{level} file={src}::{name} (namespace {namespace}) gains a barmanObjectStore in "
                f"the SAME diff that adds its pod-identity association to {_rel(TF_FILE)}. GitOps "
                f"deploys the Cluster minutes after merge; the association exists only after "
                f"`tofu apply`, and EKS Pod Identity injects credentials at pod ADMISSION — so the "
                f'pod archives nothing ("Unable to locate credentials") until it is recreated. '
                f"Split it: (1) a PR adding only the db-backups.tf entry, merged AND applied; "
                f"(2) then the gitops PR adding the backup config."
            )

    if not missing and not drift and not split:
        if args.check_applied:
            print("✓ every managed cluster has an association declared AND live in AWS")
        else:
            print("✓ every cluster targeting the managed bucket has an association")
        return 0

    problems = []
    if missing:
        problems.append(f"{len(missing)} cluster(s) would silently never back up")
    if drift:
        problems.append(f"{len(drift)} declared association(s) not applied in AWS")
    if split:
        problems.append(f"{len(split)} cluster(s) add backup config and association in one diff")
    print("\n" + "; ".join(problems) + ".", file=sys.stderr)
    # Advisory unless --enforce, mirroring the static gate: a PR that merely REVEALS preexisting
    # drift (e.g. this check's own introducing PR) must not be blocked by it. The scheduled run
    # passes --enforce, so live drift is a red check there — that is the alarm.
    return 1 if args.enforce else 0


if __name__ == "__main__":
    sys.exit(main())
