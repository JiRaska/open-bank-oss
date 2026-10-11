#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
"""Inventory of long-lived credentials, with deadlines (ADR-0279 WS4 #18).

WHY. The OpenBao migration (ADR-0099) made DB passwords dynamic (`database/creds/…`,
24h TTL) — but every OIDC client secret and API key projected by an ExternalSecret from
a static `secret/data/…` kv path is a long-lived credential with no expiry anywhere in
the system that serves it. Measured 2026-09-04: 113 gitops files touch ExternalSecrets;
the fleet had no single list of which of them hold a static credential and by when it
must be rotated or migrated. This script is that list, plus the ratchet that stops the
list from growing silently.

CONVENTION INTRODUCED (and enforced on NEW manifests only):
  * an ExternalSecret whose remoteRef points at a STATIC kv path (not `database/creds/…`
    or another dynamic mount) must carry metadata annotation
    `openbank.io/rotation-deadline: YYYY-MM-DD` — the date by which the credential is
    rotated or the reference migrates to a dynamic mount;
  * dynamic references may annotate `openbank.io/rotation: dynamic` for the reader's
    benefit, but the path prefix already proves it, so it is not required.

MODES:
  --inventory           full scan, markdown table + counts (the weekly standing issue)
  --enforce-new BASE    diff-scoped gate: a NEW file declaring a static ExternalSecret
                        without the deadline annotation fails
  --self-test           offline fixtures

Existing undeclared statics are DEBT rendered by --inventory, not PR-blocking findings —
same staged shape as every ratchet in this repo. Strimzi-issued Kafka certificates are also
excluded from the static count only when the Kubernetes source, KafkaUser/cluster identity, and
managed CA configuration are proven from the GitOps manifests; unknown or mixed sources remain
static.
"""

from __future__ import annotations

import argparse
import datetime as dt
from functools import lru_cache
import re
import subprocess
import sys
from pathlib import Path

try:
    import yaml
except ImportError:  # pragma: no cover - the gate runner installs PyYAML
    yaml = None

GITOPS = Path("openbank-infra/gitops")
DYNAMIC_KEY = re.compile(r"database/creds/|(^|/)creds/[a-z0-9-]+-db-vault-role")
DEADLINE = "openbank.io/rotation-deadline"
DYNAMIC_ANN = "openbank.io/rotation"
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")

DOC_SPLIT = re.compile(r"^---\s*$", re.M)


class Cred:
    def __init__(self, doc: str, file: Path):
        self.doc = doc
        self.file = file
        self.name = _field(doc, r"name:\s*([a-z0-9.-]+)") or "?"
        self.namespace = _field(doc, r"namespace:\s*([a-z0-9.-]+)") or "?"
        self.keys = re.findall(r"^\s*key:\s*([A-Za-z0-9_./-]+)", doc, re.M)
        parsed = _yaml_text_documents(doc)
        if parsed is not None and len(parsed) == 1:
            source_keys = _source_keys(parsed[0])
            if source_keys is not None:
                self.keys = source_keys
        self.dynamic = bool(self.keys) and all(DYNAMIC_KEY.search(k) for k in self.keys)
        self.operator_managed = False
        ann_block = re.search(r"annotations:\n((?:\s{4,}[^\n]*\n?)*)", doc)
        self.annotations = ann_block.group(1) if ann_block else ""
        self.deadline = _field(self.annotations, re.escape(DEADLINE) + r':\s*"?([^"\n]+)"?')

    @property
    def static(self) -> bool:
        return bool(self.keys) and not self.dynamic and not self.operator_managed


class _UniqueKeyLoader(getattr(yaml, "CSafeLoader", yaml.SafeLoader) if yaml else object):
    """Reject duplicate keys so lifecycle proof cannot depend on YAML last-key-wins."""


if yaml:
    def _construct_unique_mapping(loader, node, deep=False):
        loader.flatten_mapping(node)
        result = {}
        for key_node, value_node in node.value:
            key = loader.construct_object(key_node, deep=deep)
            if key in result:
                raise ValueError(f"duplicate YAML key: {key!r}")
            result[key] = loader.construct_object(value_node, deep=deep)
        return result

    _UniqueKeyLoader.add_constructor(
        yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, _construct_unique_mapping)


def _yaml_documents(paths: list[Path]) -> list[dict] | None:
    if yaml is None:
        return None
    docs: list[dict] = []
    try:
        for path in paths:
            with path.open(encoding="utf-8") as stream:
                for doc in yaml.load_all(stream, Loader=_UniqueKeyLoader):
                    if doc is not None:
                        if not isinstance(doc, dict):
                            return None
                        docs.append(doc)
    except (OSError, TypeError, ValueError, yaml.YAMLError):
        return None
    return docs


def _source_keys(doc: dict) -> list[str] | None:
    """Read only the explicit ESO key forms this provenance check understands."""
    spec = doc.get("spec")
    if not isinstance(spec, dict):
        return None
    keys: list[str] = []
    data = spec.get("data", [])
    data_from = spec.get("dataFrom", [])
    if not isinstance(data, list) or not isinstance(data_from, list):
        return None
    for item in data:
        if not isinstance(item, dict) or set(item) != {"secretKey", "remoteRef"}:
            return None
        remote = item["remoteRef"]
        if not isinstance(remote, dict) or not isinstance(remote.get("key"), str):
            return None
        keys.append(remote["key"])
    for item in data_from:
        if not isinstance(item, dict) or set(item) != {"extract"}:
            return None
        extract = item["extract"]
        if not isinstance(extract, dict) or not isinstance(extract.get("key"), str):
            return None
        keys.append(extract["key"])
    return keys or None


def _positive_duration(value: object) -> bool:
    """Accept a positive Go-style duration; reject zero and malformed refresh intervals."""
    if not isinstance(value, str):
        return False
    parts = re.findall(r"(\d+(?:\.\d+)?)(ns|us|µs|ms|s|m|h)", value)
    if not parts or "".join(number + unit for number, unit in parts) != value:
        return False
    return any(float(number) > 0 for number, _ in parts)


def _yaml_text_documents(text: str) -> list[dict] | None:
    if yaml is None:
        return None
    try:
        docs = [doc for doc in yaml.load_all(text, Loader=_UniqueKeyLoader) if doc is not None]
    except (TypeError, ValueError, yaml.YAMLError):
        return None
    return docs if all(isinstance(doc, dict) for doc in docs) else None


@lru_cache(maxsize=8)
def _strimzi_inventory_files(root: str) -> tuple[list[dict], list[dict], list[dict]] | None:
    """Parse source definitions once per inventory run; malformed input invalidates proof."""
    if yaml is None:
        return None
    gitops = Path(root) / GITOPS
    store_docs = _yaml_documents(sorted((gitops / "components/external-secrets").rglob("*.yaml")))
    kafka_docs = _yaml_documents(sorted((gitops / "components/kafka").rglob("*.yaml")))
    # KafkaUser documents live in service components alongside unrelated large manifests.
    # Parse only files declaring that resource; an unusual representation simply loses the
    # exemption and remains static.
    user_paths = [path for path in sorted(gitops.rglob("*.yaml"))
                  if re.search(r"^kind:\s*KafkaUser\s*$", path.read_text(encoding="utf-8"), re.M)]
    user_docs = _yaml_documents(user_paths)
    if store_docs is None or kafka_docs is None or user_docs is None:
        return None
    return store_docs, kafka_docs, user_docs


def _operator_managed_credential(cred: Cred, root: Path) -> bool:
    """Prove every ESO source key is a Strimzi-managed Kafka TLS certificate."""
    parsed = _yaml_text_documents(cred.doc)
    if parsed is None or len(parsed) != 1:
        return False
    external = parsed[0]
    meta = external.get("metadata")
    spec = external.get("spec")
    if not isinstance(meta, dict) or not isinstance(spec, dict):
        return False
    namespace = meta.get("namespace")
    store_ref = spec.get("secretStoreRef")
    keys = _source_keys(external)
    refresh_policy = spec.get("refreshPolicy", "Periodic")
    refresh_interval = spec.get("refreshInterval")
    if (not isinstance(namespace, str) or not isinstance(store_ref, dict)
            or store_ref.get("kind") != "ClusterSecretStore"
            or not isinstance(store_ref.get("name"), str) or not keys
            or refresh_policy != "Periodic"
            or ("refreshInterval" in spec and not _positive_duration(refresh_interval))):
        return False

    evidence = _strimzi_inventory_files(str(root.resolve()))
    if evidence is None:
        return False
    store_docs, kafka_docs, user_docs = evidence

    stores = [d for d in store_docs if d.get("kind") == "ClusterSecretStore"
              and isinstance(d.get("metadata"), dict)
              and d["metadata"].get("name") == store_ref["name"]]
    if len(stores) != 1:
        return False
    store_spec = stores[0].get("spec")
    if not isinstance(store_spec, dict):
        return False
    provider = store_spec.get("provider")
    kubernetes = provider.get("kubernetes") if isinstance(provider, dict) else None
    if (not isinstance(kubernetes, dict) or set(provider) != {"kubernetes"}
            or kubernetes.get("remoteNamespace") != "messaging"):
        return False
    server = kubernetes.get("server", {})
    if (not isinstance(server, dict)
            or ("url" in server and server["url"] != "https://kubernetes.default.svc")):
        return False

    clusters = [d for d in kafka_docs if d.get("kind") == "Kafka"
                and isinstance(d.get("metadata"), dict)
                and d["metadata"].get("namespace") == "messaging"]
    users = [d for d in user_docs if d.get("kind") == "KafkaUser"
             and isinstance(d.get("metadata"), dict)
             and d["metadata"].get("namespace") == "messaging"]

    def cluster_manages(cluster: dict, ca_name: str, *, require_user_operator: bool = False) -> bool:
        spec = cluster.get("spec")
        if not isinstance(spec, dict):
            return False
        entity = spec.get("entityOperator")
        if require_user_operator:
            operator = entity.get("userOperator") if isinstance(entity, dict) else None
            if (not isinstance(operator, dict)
                    or ("enabled" in operator and operator["enabled"] is not True)):
                return False
            # This proof matches an unprefixed Secret in the cluster's namespace.
            # A different watcher or generated name needs its own provenance proof.
            if (operator.get("watchedNamespace", "messaging") != "messaging"
                    or operator.get("secretPrefix", "") != ""):
                return False
        ca = spec.get(ca_name, {})
        if not isinstance(ca, dict):
            return False
        # Strimzi defaults this field to true. Accept that default or an explicit true;
        # invalid/unknown values do not establish an automated CA lifecycle.
        return ("generateCertificateAuthority" not in ca
                or ca["generateCertificateAuthority"] is True)

    def is_managed_key(key: str) -> bool:
        if not isinstance(key, str):
            return False
        for cluster in clusters:
            cluster_meta = cluster.get("metadata")
            if not isinstance(cluster_meta, dict):
                continue
            cluster_name = cluster_meta.get("name")
            if not isinstance(cluster_name, str):
                continue
            if key == f"{cluster_name}-cluster-ca-cert":
                matching_clusters = [candidate for candidate in clusters
                                     if (candidate.get("metadata") or {}).get("name") == cluster_name]
                return len(matching_clusters) == 1 and cluster_manages(cluster, "clusterCa")
            named_users = [candidate for candidate in users
                           if isinstance(candidate.get("metadata"), dict)
                           and candidate["metadata"].get("name") == key]
            if len(named_users) != 1:
                continue
            user = named_users[0]
            user_meta = user.get("metadata")
            labels = user_meta.get("labels") if isinstance(user_meta, dict) else None
            user_spec = user.get("spec")
            auth = user_spec.get("authentication") if isinstance(user_spec, dict) else None
            if (isinstance(labels, dict)
                    and labels.get("strimzi.io/cluster") == cluster_name
                    and isinstance(auth, dict) and auth.get("type") == "tls"
                    and cluster_manages(cluster, "clientsCa", require_user_operator=True)):
                matching_clusters = [candidate for candidate in clusters
                                     if (candidate.get("metadata") or {}).get("name") == cluster_name]
                if len(matching_clusters) == 1:
                    return True
        return False

    # A dataFrom extract can contain several credentials. Every source key must be
    # attributable to Strimzi; one static or unknown key keeps the whole ESO static.
    return all(is_managed_key(key) for key in keys)


def _field(text: str, pattern: str) -> str | None:
    m = re.search(pattern, text)
    return m.group(1).strip() if m else None


def scan(root: Path) -> list[Cred]:
    out: list[Cred] = []
    for f in sorted(root.rglob("*.yaml")):
        if GITOPS.parts[0] not in f.parts and str(GITOPS) not in str(f):
            continue
        text = f.read_text()
        for doc in DOC_SPLIT.split(text):
            if re.search(r"^kind:\s*ExternalSecret\s*$", doc, re.M):
                credential = Cred(doc, f)
                credential.operator_managed = _operator_managed_credential(credential, root)
                out.append(credential)
    return out


def validate_deadline(c: Cred, today: dt.date) -> str | None:
    """None when OK, else the violation text."""
    if c.deadline is None:
        return f"missing annotation `{DEADLINE}`"
    if not DATE_RE.match(c.deadline):
        return f"`{DEADLINE}: {c.deadline}` is not YYYY-MM-DD"
    if dt.date.fromisoformat(c.deadline) < today:
        return f"`{DEADLINE}: {c.deadline}` is in the past — rotate or re-date with a reason"
    return None


def inventory(root: Path, today: dt.date) -> tuple[str, int]:
    creds = scan(root)
    statics = [c for c in creds if c.static]
    operator_managed = [c for c in creds if c.operator_managed]
    dynamic = [c for c in creds if c.dynamic]
    declared = [c for c in statics if c.deadline and not validate_deadline(c, today)]
    overdue = [c for c in statics if c.deadline and validate_deadline(c, today) and "past" in (validate_deadline(c, today) or "")]
    undeclared = [c for c in statics if not c.deadline]
    lines = [
        "# Long-lived credential inventory", "",
        f"ExternalSecrets: **{len(creds)}** total — {len(statics)} static (long-lived), "
        f"{len(operator_managed)} operator-managed rotation, {len(dynamic)} dynamic "
        f"(`database/creds/…`, ADR-0099).", "",
        f"Static credentials with a valid rotation deadline: **{len(declared)}**; "
        f"overdue: **{len(overdue)}**; undeclared (debt): **{len(undeclared)}**.", "",
        "## Static credentials", "",
        "| credential | namespace | backend key(s) | rotation deadline |", "|---|---|---|---|",
    ]
    for c in sorted(statics, key=lambda c: (c.namespace, c.name)):
        dl = c.deadline or "— **UNDECLARED**"
        lines.append(f"| `{c.name}` | {c.namespace} | {', '.join(f'`{k}`' for k in c.keys)} | {dl} |")
    lines += ["", "_Maintained by credential-inventory.yml (ADR-0279 #18). Do not edit by hand. "
              "New static ExternalSecrets must carry `openbank.io/rotation-deadline` — enforced "
              "by the `credential-deadline-ratchet` gate._"]
    print(f"credential-inventory: {len(creds)} ExternalSecrets, {len(statics)} static, "
          f"{len(operator_managed)} operator-managed, {len(dynamic)} dynamic, "
          f"{len(declared)} with deadline, {len(overdue)} overdue, {len(undeclared)} undeclared")
    return "\n".join(lines) + "\n", 1 if overdue else 0


def _diff_added(base: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(["git", "diff", "--name-only", "--diff-filter=A", f"{base}...HEAD"],
                          capture_output=True, text=True, check=False)


def _can_diff(base: str) -> bool:
    """Can the gate's ACTUAL query run — not merely: does the object exist.

    This distinction is the whole bug, twice over. `base...HEAD` is a three-dot diff, so it
    needs a MERGE BASE, not just the base commit. Fetching the sha by name (`--depth=1`) makes
    `git cat-file -e` succeed while the histories stay disconnected, so the diff still exits
    128. The first version of this fix checked exactly that — object presence — declared the
    base reached, and failed in precisely the same place it was written to fix.

    Probing with the real command is the only criterion that cannot be satisfied by an
    almost-correct repair.
    """
    return _diff_added(base).returncode == 0


def reach_base(base: str) -> bool:
    """Make `base` reachable in a shallow checkout, reporting honestly if it cannot be.

    The gates shard checks out at `fetch-depth: 1` (ci.yml says so in its own comment), so a
    PR whose base commit has since scrolled out of the shallow window simply does not have it.
    `git diff base...HEAD` then exits 128 — and because the caller passed `check=True`, the
    gate died with a CalledProcessError traceback and was reported as
    `FAIL new static ExternalSecret must declare a rotation deadline`. It had found no such
    thing. It had not looked.

    That is the failure mode this repo keeps re-learning: a check that cannot distinguish
    "nothing to report" from "could not run" is not a check. Here it happened to fail closed,
    which is the right direction and the wrong sentence — it accused the PR of a credential
    violation it did not commit, and sent the author looking for an ExternalSecret that was
    never there. Measured on #9095, whose diff touches no manifest at all.

    Same deepen-in-place idiom as check-flyway-version-commit-order.py, for the same reason:
    60 other gates do not need full history and should not pay for it.
    """
    if _can_diff(base):
        return True
    # Deepen, cheapest first, re-probing with the real query after each attempt. Fetching the
    # sha by name alone is deliberately NOT enough here — see _can_diff.
    for args in (["--depth=1", "origin", base], ["--unshallow", "origin", "main"],
                 ["--depth=2147483647", "origin", "main"]):
        subprocess.run(["git", "fetch", "--quiet", *args],
                       capture_output=True, text=True, check=False)
        if _can_diff(base):
            return True
    return False


def enforce_new(base: str, today: dt.date) -> int:
    if not reach_base(base):
        print(f"::error::credential-deadline-ratchet could not reach the diff base {base}, so it "
              f"scanned NOTHING. This is an UNKNOWN result, not a clean one, and not a finding "
              f"against this pull request. Usual cause: the base commit has scrolled out of the "
              f"shard's shallow window (ci.yml checks out at fetch-depth: 1) — rebasing the branch "
              f"onto current main resolves it.")
        return 1
    out = _diff_added(base)
    if out.returncode != 0:
        print(f"::error::credential-deadline-ratchet could not diff {base}...HEAD "
              f"(git exit {out.returncode}), so it scanned NOTHING. UNKNOWN, not clean, and not a "
              f"finding against this pull request. git said: {out.stderr.strip()[:300]}")
        return 1
    bad = 0
    for name in out.stdout.splitlines():
        f = Path(name)
        if not f.exists() or f.suffix != ".yaml":
            continue
        for doc in DOC_SPLIT.split(f.read_text()):
            if not re.search(r"^kind:\s*ExternalSecret\s*$", doc, re.M):
                continue
            c = Cred(doc, f)
            c.operator_managed = _operator_managed_credential(c, Path("."))
            if not c.static:
                continue
            violation = validate_deadline(c, today)
            if violation:
                print(f"::error::{name}: new static ExternalSecret '{c.name}' {violation}. "
                      f"Rotate-by dates are the only thing between a long-lived credential and "
                      f"an immortal one (ADR-0279 #18).")
                bad += 1
    print(f"credential-deadline-ratchet: {bad} finding(s)")
    return 1 if bad else 0


def self_test() -> int:
    bad = 0
    today = dt.date(2026, 9, 4)
    doc_static = (
        "kind: ExternalSecret\nmetadata:\n  name: foo-oidc\n  namespace: foo\n"
        "spec:\n  target:\n    name: foo-oidc\n  data:\n  - remoteRef:\n      key: account-service\n")
    c = Cred(doc_static, Path("x.yaml"))
    if not c.static or c.dynamic:
        print("self-test FAIL: static classification"); bad += 1
    if validate_deadline(c, today) is None:
        print("self-test FAIL: missing deadline must be a violation"); bad += 1
    doc_dyn = doc_static.replace("key: account-service", "key: database/creds/foo-db-vault-role")
    if Cred(doc_dyn, Path("x.yaml")).static:
        print("self-test FAIL: dynamic path classified as static"); bad += 1
    if Cred(doc_dyn + "# projected field (key: password)\n", Path("x.yaml")).static:
        print("self-test FAIL: comment text classified as a static source"); bad += 1
    doc_flow = ("kind: ExternalSecret\nspec:\n  data:\n"
                "    - secretKey: password\n      remoteRef: {key: static-api-key}\n")
    if not Cred(doc_flow, Path("x.yaml")).static:
        print("self-test FAIL: flow mapping static source was ignored"); bad += 1
    doc_mixed = doc_dyn + "  - remoteRef:\n      key: static-api-key\n"
    if not Cred(doc_mixed, Path("x.yaml")).static:
        print("self-test FAIL: mixed dynamic/static sources bypassed static deadline"); bad += 1
    doc_ok = doc_static.replace("metadata:\n",
                                f'metadata:\n  annotations:\n    {DEADLINE}: "2027-01-01"\n')
    if validate_deadline(Cred(doc_ok, Path("x.yaml")), today) is not None:
        print("self-test FAIL: valid future deadline flagged"); bad += 1
    doc_past = doc_ok.replace("2027-01-01", "2026-01-01")
    v = validate_deadline(Cred(doc_past, Path("x.yaml")), today)
    if v is None or "past" not in v:
        print("self-test FAIL: past deadline not caught"); bad += 1
    doc_bad = doc_ok.replace('"2027-01-01"', '"next quarter"')
    if validate_deadline(Cred(doc_bad, Path("x.yaml")), today) is None:
        print("self-test FAIL: non-date deadline accepted"); bad += 1

    if yaml is None:
        print("self-test FAIL: PyYAML unavailable for operator lifecycle proof"); bad += 1
    else:
        import tempfile

        def lifecycle_fixture(*, store: str = "kafka-messaging-certs",
                              provider: str = "kubernetes", remote_ns: str = "messaging",
                              source: str = "pension-service", auth: str = "tls",
                              cluster_ca: str = "", clients_ca: str = "",
                              user_operator: bool = True, mixed: bool = False,
                              operator_options: str = "",
                              cluster_label: str = "openbank-cluster",
                              malformed_store_spec: bool = False,
                              server_url: str | None = None,
                              null_server_url: bool = False,
                              refresh_policy: str | None = None,
                              refresh_interval: str | None = "1h",
                              null_refresh_interval: bool = False) -> bool:
            with tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                gitops = root / GITOPS
                ext_dir = gitops / "components/external-secrets"
                kafka_dir = gitops / "components/kafka"
                user_dir = gitops / "components/pension"
                for directory in (ext_dir, kafka_dir, user_dir):
                    directory.mkdir(parents=True, exist_ok=True)
                server_yaml = ("      server:\n        url: null\n" if null_server_url else
                               f"      server:\n        url: {server_url}\n"
                               if server_url is not None else "")
                store_spec = ("spec: invalid\n" if malformed_store_spec else
                              "spec:\n  provider:\n"
                              f"    {provider}:\n      remoteNamespace: {remote_ns}\n" +
                              server_yaml)
                (ext_dir / "store.yaml").write_text(
                    "apiVersion: external-secrets.io/v1\nkind: ClusterSecretStore\n"
                    "metadata:\n  name: kafka-messaging-certs\n" + store_spec)
                user_operator_yaml = "    userOperator: {}\n" if user_operator else ""
                if user_operator and operator_options:
                    user_operator_yaml = "    userOperator:\n" + operator_options
                ca_yaml = "" if not cluster_ca else f"  clusterCa:\n    generateCertificateAuthority: {cluster_ca}\n"
                clients_yaml = "" if not clients_ca else f"  clientsCa:\n    generateCertificateAuthority: {clients_ca}\n"
                (kafka_dir / "kafka.yaml").write_text(
                    "apiVersion: kafka.strimzi.io/v1\nkind: Kafka\nmetadata:\n"
                    "  name: openbank-cluster\n  namespace: messaging\nspec:\n"
                    "  entityOperator:\n" + user_operator_yaml + ca_yaml + clients_yaml)
                (user_dir / "kafka-user.yaml").write_text(
                    "apiVersion: kafka.strimzi.io/v1\nkind: KafkaUser\nmetadata:\n"
                    "  name: pension-service\n  namespace: messaging\n  labels:\n"
                    f"    strimzi.io/cluster: {cluster_label}\nspec:\n"
                    f"  authentication:\n    type: {auth}\n")
                extra = "\n    - extract:\n        key: static-api-key" if mixed else ""
                refresh_yaml = ((f"  refreshPolicy: {refresh_policy}\n")
                                if refresh_policy is not None else "")
                interval_yaml = ((f"  refreshInterval: {refresh_interval}\n")
                                 if refresh_interval is not None else "")
                if null_refresh_interval:
                    interval_yaml = "  refreshInterval: null\n"
                (user_dir / "external-secret.yaml").write_text(
                    "apiVersion: external-secrets.io/v1\nkind: ExternalSecret\nmetadata:\n"
                    "  name: pension-service-kafka-keystore\n  namespace: pension\nspec:\n"
                    + refresh_yaml + interval_yaml +
                    "  secretStoreRef:\n    kind: ClusterSecretStore\n"
                    f"    name: {store}\n  dataFrom:\n    - extract:\n        key: {source}"
                    + extra + "\n")
                credential = Cred((user_dir / "external-secret.yaml").read_text(),
                                  Path("openbank-infra/gitops/components/pension/external-secret.yaml"))
                return _operator_managed_credential(credential, root)

        expected_positive = lifecycle_fixture()
        if not expected_positive:
            print("self-test FAIL: TLS KafkaUser source with managed Strimzi CAs was not classified")
            bad += 1
        documented_defaults = lifecycle_fixture(
            server_url="https://kubernetes.default.svc", refresh_interval=None)
        if not documented_defaults:
            print("self-test FAIL: documented in-cluster API/default refresh was rejected")
            bad += 1
        if not lifecycle_fixture(operator_options=(
                "      watchedNamespace: messaging\n      secretPrefix: ''\n")):
            print("self-test FAIL: explicit same-namespace/unprefixed operator was rejected")
            bad += 1
        for label, kwargs in (
            ("wrong store", {"store": "vault-kv"}),
            ("wrong backend", {"provider": "vault"}),
            ("malformed store spec", {"malformed_store_spec": True}),
            ("wrong source namespace", {"remote_ns": "other"}),
            ("remote Kubernetes API", {"server_url": "https://other-cluster.example"}),
            ("null Kubernetes API", {"null_server_url": True}),
            ("unknown source", {"source": "unrelated-static-secret"}),
            ("external TLS auth", {"auth": "tls-external"}),
            ("wrong Kafka cluster", {"cluster_label": "other-cluster"}),
            ("disabled client CA", {"clients_ca": "false"}),
            ("unknown client CA policy", {"clients_ca": "unknown"}),
            ("missing user operator", {"user_operator": False}),
            ("different watched namespace", {"operator_options": "      watchedNamespace: other\n"}),
            ("prefixed user secret", {"operator_options": "      secretPrefix: kafka-\n"}),
            ("null watched namespace", {"operator_options": "      watchedNamespace: null\n"}),
            ("null secret prefix", {"operator_options": "      secretPrefix: null\n"}),
            ("mixed static sources", {"mixed": True}),
            ("created-once projection", {"refresh_policy": "CreatedOnce"}),
            ("on-change projection", {"refresh_policy": "OnChange"}),
            ("zero refresh interval", {"refresh_interval": "0s"}),
            ("malformed refresh interval", {"refresh_interval": "soon"}),
            ("null refresh interval", {"null_refresh_interval": True}),
            ("disabled cluster CA", {"source": "openbank-cluster-cluster-ca-cert",
                                     "cluster_ca": "false"}),
        ):
            if lifecycle_fixture(**kwargs):
                print(f"self-test FAIL: {label} source incorrectly classified as operator-managed")
                bad += 1
        ca_positive = lifecycle_fixture(source="openbank-cluster-cluster-ca-cert")
        if not ca_positive:
            print("self-test FAIL: managed Strimzi cluster CA source was not classified")
            bad += 1
        prefix_negative = lifecycle_fixture(source="pension-service-user")
        if prefix_negative:
            print("self-test FAIL: unconfigured user secret alias was classified")
            bad += 1
        malformed = Cred(
            "kind: ExternalSecret\nmetadata:\n  namespace: pension\nspec:\n"
            "  secretStoreRef:\n    kind: ClusterSecretStore\n    name: kafka-messaging-certs\n"
            "  secretStoreRef:\n    kind: ClusterSecretStore\n    name: kafka-messaging-certs\n"
            "  dataFrom:\n    - extract:\n        key: pension-service\n",
            Path("fixture.yaml"))
        if _operator_managed_credential(malformed, Path("/missing/root")):
            print("self-test FAIL: duplicate-key source evidence was accepted")
            bad += 1
        pension_manifest = Path(
            "openbank-infra/gitops/components/pension/kafka-pension-mtls.yaml")
        if pension_manifest.is_file():
            pension_docs = pension_manifest.read_text(encoding="utf-8")
            real_credentials = [Cred(doc, pension_manifest) for doc in DOC_SPLIT.split(pension_docs)
                                if re.search(r"^kind:\s*ExternalSecret\s*$", doc, re.M)]
            for credential in real_credentials:
                credential.operator_managed = _operator_managed_credential(credential, Path("."))
            managed_names = {credential.name for credential in real_credentials
                             if credential.operator_managed}
            expected_names = {"pension-service-kafka-keystore", "kafka-cluster-ca-truststore"}
            if not expected_names.issubset(managed_names):
                print("self-test FAIL: pension KafkaUser/CA ExternalSecrets were not proven "
                      "operator-managed")
                bad += 1

    # The gate must be unable to pass off "could not look" as "looked and found nothing" —
    # and must not dress it up as a credential finding either. A sha of the right shape that
    # cannot exist stands in for the scrolled-out base seen in CI.
    #
    # Run it inside a throwaway repo with NO remote, not against the checkout. Two reasons, both
    # learned the hard way: against the real repo `reach_base` reaches the network and tries
    # `--unshallow`, which took 12.7s against this gate's 5s budget and failed the shard; and a
    # self-test that deepens the checkout it is running in has changed the thing it was meant to
    # observe. With no `origin` configured every fetch fails immediately and offline, so the
    # unreachable-base path is exercised at full speed.
    import contextlib
    import io
    import os
    import tempfile
    unreachable = "0" * 40
    buf = io.StringIO()
    cwd = os.getcwd()
    with tempfile.TemporaryDirectory() as tmp:
        try:
            os.chdir(tmp)
            subprocess.run(["git", "init", "--quiet"], check=False,
                           capture_output=True, text=True)
            with contextlib.redirect_stdout(buf):
                rc = enforce_new(unreachable, today)
        finally:
            os.chdir(cwd)
    said = buf.getvalue()
    if rc == 0:
        print("self-test FAIL: unreachable base returned success — a gate that did not run "
              "must never report clean"); bad += 1
    if "UNKNOWN" not in said:
        print("self-test FAIL: unreachable base did not say UNKNOWN"); bad += 1
    if "rotation deadline" in said or "ADR-0279" in said:
        print("self-test FAIL: unreachable base reported as a credential violation"); bad += 1

    # The three checks above pin the OUTCOME contract (never clean, always UNKNOWN, never an
    # accusation) — but they pass whether or not `reach_base` actually recovers anything, because
    # an unrecoverable base and a failed diff both land on an honest UNKNOWN. That left the half
    # that fixes the real CI symptom unproven, so it gets its own case: a base that is genuinely
    # absent from a shallow clone and genuinely recoverable from its origin. `file://` keeps it
    # offline and fast; the deepening path is the same one CI takes.
    # Strip the inherited git environment for the WHOLE fixture, process-wide and restored
    # after. Passing a clean env to the setup commands is not enough: the recovery case calls
    # enforce_new, whose own subprocesses inherit os.environ, and GIT_DIR/GIT_INDEX_FILE
    # override the working directory — so `os.chdir(clone)` is silently ignored and git keeps
    # answering about the main checkout. That is what CI was doing, and it is invisible from
    # inside the fixture: every command succeeds, against the wrong repository.
    inherited_git = {k: v for k, v in os.environ.items() if k.startswith("GIT_")}
    for k in inherited_git:
        del os.environ[k]
    try:
        with tempfile.TemporaryDirectory() as tmp:
            origin, clone = Path(tmp) / "origin", Path(tmp) / "clone"
            git_o = ["git", "-C", str(origin)]
            # A fixture must not depend on the ambient git configuration. CI built no commits at
            # all here — `rev-parse HEAD~2` came back `fatal: ambiguous argument` — while the same
            # code committed fine on a developer machine, which is the signature of a system or
            # global gitconfig doing something the fixture never asked for (a commit template, a
            # hooks path, signing, an identity policy). Rather than chase which one, the whole
            # ambient layer is switched off: GIT_CONFIG_* to /dev/null, HOME inside the fixture,
            # and the identity supplied through the environment instead of inherited.
            # Strip EVERY inherited GIT_* variable, not just the config ones. CI exports the
            # checkout's own git environment, so the fixture's `git commit` was writing through
            # the MAIN repository's index and died on a blob it had no reason to know about:
            #
            #   error: invalid object 100644 74c3fada… for '.clusterfuzzlite/Dockerfile'
            #
            # A file from this repo, in a commit meant to contain one line of text. Inheriting the
            # ambient git environment is what made a fixture that passes locally impossible in CI.
            env = {
                **{k: v for k, v in os.environ.items() if not k.startswith("GIT_")},
                "GIT_CONFIG_GLOBAL": os.devnull,
                "GIT_CONFIG_SYSTEM": os.devnull,
                "GIT_CONFIG_NOSYSTEM": "1",
                "HOME": str(Path(tmp) / "home"),
                "GIT_AUTHOR_NAME": "fixture", "GIT_AUTHOR_EMAIL": "fixture@example.invalid",
                "GIT_COMMITTER_NAME": "fixture", "GIT_COMMITTER_EMAIL": "fixture@example.invalid",
            }
            (Path(tmp) / "home").mkdir(exist_ok=True)
            steps: list[subprocess.CompletedProcess[str]] = []

            def git(*args: str) -> subprocess.CompletedProcess[str]:
                done = subprocess.run(["git", *args], check=False, capture_output=True,
                                      text=True, env=env)
                steps.append(done)
                return done

            # Build defensively and stop at the first failure. A fixture that raises instead of
            # reporting is the same defect once more: the exception escapes as a crash and the
            # gate is recorded as unfalsified, with no sentence anywhere saying the fixture is
            # what broke.
            origin.mkdir(parents=True, exist_ok=True)
            try:
                if git("init", "--quiet", "-b", "main", str(origin)).returncode == 0:
                    for n in range(3):
                        (origin / f"f{n}.txt").write_text(str(n))
                        if git(*git_o[1:], "add", f"f{n}.txt").returncode != 0:
                            break
                        if git(*git_o[1:], "-c", "commit.gpgsign=false",
                               "commit", "--quiet", "-m", f"c{n}").returncode != 0:
                            break
            except OSError as exc:
                steps.append(subprocess.CompletedProcess(["<fixture build>"], 1, "", str(exc)))
            rev = git(*git_o[1:], "rev-parse", "HEAD~2")
            old_sha = rev.stdout.strip()
            cloned = git("clone", "--quiet", "--depth=1", f"file://{origin}", str(clone))
            # A fixture that failed to build must say so as a FIXTURE fault. Reporting it as
            # "the gate is broken" would be this gate's own bug one storey up: a setup failure
            # dressed as a finding. `git rev-parse` echoes its argument back when it cannot
            # resolve it, so an unbuilt fixture hands the gate the literal string "HEAD~2" and
            # the failure reads exactly like a real one.
            setup_error = None
            if not re.fullmatch(r"[0-9a-f]{40}", old_sha):
                # Name the FIRST step that failed, not just the last symptom: the earlier
                # attempt reported rev-parse's complaint, which is downstream of whichever
                # setup command actually broke.
                first_bad = next((d for d in steps if d.returncode != 0), None)
                blame = (f"first failing setup step: {' '.join(first_bad.args)!r} "
                         f"rc={first_bad.returncode} stderr={first_bad.stderr.strip()[:200]}"
                         if first_bad else "every setup step exited 0, which should be impossible")
                setup_error = (f"rev-parse gave {old_sha!r} (rc={rev.returncode}); {blame}")
            elif cloned.returncode != 0:
                setup_error = f"clone rc={cloned.returncode}; stderr={cloned.stderr.strip()[:200]}"
            present_before, rc2, buf2 = False, 0, io.StringIO()
            if setup_error is None:
                cwd2 = os.getcwd()
                try:
                    os.chdir(clone)
                    # Prove git is answering about the CLONE before trusting anything it says.
                    # GIT_DIR/GIT_INDEX_FILE override the working directory, so a leaked one
                    # makes every command below succeed against the wrong repository — and the
                    # failure then reads as "reach_base is broken", which is the exact
                    # misattribution this gate exists to stop.
                    # Ask which REPOSITORY git is using, not which directory it is standing
                    # in. `--show-toplevel` reports the cwd whatever GIT_DIR says, so it agrees
                    # with the fixture even while every command goes to another repository —
                    # a probe that cannot observe the fault it was added for. `--git-dir` is
                    # the one that moves. An empty answer is a fault too: `Path("").resolve()`
                    # is the cwd, so blank would otherwise read as agreement.
                    shown = subprocess.run(["git", "rev-parse", "--absolute-git-dir"],
                                           check=False, capture_output=True, text=True)
                    gitdir = shown.stdout.strip()
                    if shown.returncode != 0 or not gitdir:
                        setup_error = (f"`git rev-parse --absolute-git-dir` gave no answer in "
                                       f"the fixture clone (rc={shown.returncode}, "
                                       f"stderr={shown.stderr.strip()[:120]})")
                    elif Path(gitdir).resolve() != (clone / ".git").resolve():
                        setup_error = (f"git is using repository {gitdir!r}, not the fixture "
                                       f"clone's {str(clone / '.git')!r} — a GIT_* variable is "
                                       f"leaking in, so every answer below is about the wrong "
                                       f"repository")
                    present_before = _can_diff(old_sha)
                    with contextlib.redirect_stdout(buf2):
                        rc2 = enforce_new(old_sha, today)
                finally:
                    os.chdir(cwd2)
    finally:
        os.environ.update(inherited_git)

    if setup_error is not None:
        print(f"::error::self-test FAIL: the recovery FIXTURE did not build, so the recovery path was "
              f"never exercised — this is a fixture fault, not a gate finding: {setup_error}")
        bad += 1
    elif present_before:
        print("self-test FAIL: shallow clone already had the old base — the fixture proves "
              "nothing, so the recovery path was never exercised"); bad += 1
    elif rc2 != 0 or "UNKNOWN" in buf2.getvalue():
        print(f"self-test FAIL: a recoverable base was not recovered (rc={rc2}) — reach_base "
              f"is not doing its job: {buf2.getvalue().strip()[:200]}"); bad += 1
    print("credential-inventory self-test: " + ("clean" if not bad else f"{bad} failure(s)"))
    return 1 if bad else 0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--inventory", action="store_true")
    ap.add_argument("--md", help="write the inventory markdown here")
    ap.add_argument("--enforce-new", metavar="BASE")
    ap.add_argument("--today", default=dt.date.today().isoformat(),
                    help="YYYY-MM-DD override (deterministic tests; no bare clock reads in CI)")
    ap.add_argument("--root", default=".")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    today = dt.date.fromisoformat(args.today)
    if args.enforce_new:
        return enforce_new(args.enforce_new, today)
    if args.inventory:
        md, rc = inventory(Path(args.root), today)
        if args.md:
            Path(args.md).write_text(md)
        else:
            print(md)
        return rc
    ap.error("one of --inventory / --enforce-new / --self-test is required")


if __name__ == "__main__":
    sys.exit(main())
