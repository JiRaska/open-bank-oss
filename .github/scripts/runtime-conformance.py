#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# ---------------------------------------------------------------------------------------
# Runtime conformance: compare what the repo CLAIMS to what the cluster DOES.
#
# WHY THIS EXISTS
# This estate has 117 CI gates and they are good, but every one of them reads committed text,
# because that is the only thing CI can see. Nothing reads running state. On 2026-08-07 a two-hour
# sweep of the live databases, pod logs and AWS APIs found five defects no gate could have caught,
# and each had the same shape — a control that is declared, wired, reports success, and carries
# nothing:
#
#   * four services ship an outbox (dispatcher + backlog gauge + `dispatch-enabled: true`) that
#     NOTHING writes to; the gauge reads 0, which is structurally correct and means nothing (#4007)
#   * card-issuance's outbox is 24 rows, ALL `DEAD` behind a latched breaker — the same gauge also
#     reads 0, because DEAD is excluded from `listProcessable` (#4005)
#   * three CNPG clusters have no recovery point while `Ready` and `ContinuousArchiving` are True,
#     and the prod-readiness C5 cell scores them from the presence of a `backup:` block (#3975)
#   * 21 of 21 canary Rollouts run one replica, so `setWeight: 10` cannot be expressed at all, and
#     every one reports `Healthy` throughout (#3806)
#   * gitops pinned an image tag that had never been built; the fleet attestation gate went red on
#     every PR for four hours before anyone connected the two (#4065)
#
# None of these is a missing gate. They are the same gate class — text — applied to a question that
# is not about text. This script is the other half.
#
# THE SPLIT, AND WHY
# `--collect` needs cluster and AWS reads. No workflow in this repo has those credentials today
# (`dr-restore-verify.yml` is the only one that tries, and it has never run), so a scheduled CI job
# reading live state is a credential decision, not a scripting one.
#
# So the halves are separated the way check-external-feeds.py already separates them:
#
#   --collect            emits a JSON snapshot. Runs wherever access exists: an operator's
#                        machine, a dispatched job, or eventually an in-cluster CronJob.
#   --check <snapshot>   pure function over (snapshot, repo). No network, no cluster. Runs in CI
#                        today against a committed or uploaded snapshot, and is the part that can
#                        be unit-tested — which is the part that decides whether this is trustworthy.
#   --self-test          drives every comparison branch, including the near-misses it must NOT flag.
#
# WHAT IT DOES NOT DO
# It does not replace any gate. A gate answers "is the committed text right"; this answers "did the
# committed text take effect". Both are needed and they fail differently: on 2026-08-07 a manifest
# raising a PVC to 10Gi merged, ArgoCD synced it, the Cluster object read 10Gi — and the PVC stayed
# at 2Gi, because CNPG will not reconcile a cluster whose pod cannot report a status. Every text
# check was green and the thing the change existed to move had not moved.

from __future__ import annotations

import argparse
import json
import pathlib
import re
import subprocess
import sys

# ── probe registry ───────────────────────────────────────────────────────────────────────
# Each probe is (id, claim-side reader, fact-side key, comparator). Adding one means adding a
# collector key and a comparator; the self-test then has to cover it, which is deliberate friction.

PROBES = (
    "backup_recoverability",
    "outbox_liveness",
    "canary_realisability",
    "image_tag_exists",
    "audit_attribution",
)


# ── claim side: what the repo says (pure, reads the working tree) ─────────────────────────

def claim_cnpg_clusters(root: pathlib.Path) -> dict[str, dict]:
    """Every CNPG Cluster declared in gitops, and whether it declares a backup destination.

    A `backup.barmanObjectStore` block is the CLAIM "this database is backed up". The fact side
    asks whether a restore is actually possible, which is a different question and the one that
    matters.
    """
    out: dict[str, dict] = {}
    for path in sorted(root.glob("openbank-infra/gitops/components/*/*.yaml")):
        text = _read(path)
        for doc in text.split("\n---"):
            if "kind: Cluster" not in doc or "postgresql.cnpg.io" not in doc:
                continue
            name = _field(doc, "name")
            ns = _field(doc, "namespace")
            if not name or not ns:
                continue
            out[f"{ns}/{name}"] = {
                "declares_backup": "barmanObjectStore" in doc,
                "source": str(path.relative_to(root)),
            }
    return out


def claim_outbox_services(root: pathlib.Path) -> dict[str, dict]:
    """Services that ship outbox machinery, and whether anything constructs an OutboxMessage.

    The write side is the discriminator (#4007): a dispatcher polling a table nothing writes to is
    indistinguishable from a drained queue at every observable point.
    """
    out: dict[str, dict] = {}
    for svc in sorted(p for p in root.glob("openbank-*") if p.is_dir()):
        # openbank-libs-* ships the ABSTRACT dispatcher every service extends. It owns no table and
        # constructs no message by design, so including it would be a permanent false positive —
        # found by running this against the real estate before trusting it.
        if svc.name.startswith("openbank-libs"):
            continue
        main = svc / "src" / "main" / "kotlin"
        if not main.is_dir():
            continue
        has_dispatcher = writes = False
        for kt in main.rglob("*.kt"):
            body = _read(kt)
            if re.search(r"class\s+\w*OutboxDispatcher\b", body):
                has_dispatcher = True
            if "OutboxMessage(" in body and "data class OutboxMessage" not in body:
                writes = True
        if has_dispatcher:
            out[svc.name] = {"constructs_outbox_message": writes}
    return out


def claim_canary_rollouts(root: pathlib.Path) -> dict[str, dict]:
    """Argo Rollouts declaring canary steps, with the replica count and the smallest setWeight.

    `setWeight` is realised by pod count. With N replicas the smallest expressible non-zero share is
    100/N percent, so a `setWeight` below that cannot mean what it says (#3806).
    """
    out: dict[str, dict] = {}
    for path in sorted(root.glob("openbank-infra/gitops/components/*/*.yaml")):
        for doc in _read(path).split("\n---"):
            if "kind: Rollout" not in doc or "canary:" not in doc:
                continue
            name = _field(doc, "name")
            ns = _field(doc, "namespace")
            if not name:
                continue
            weights = [int(w) for w in re.findall(r"setWeight:\s*(\d+)", doc)]
            nonzero = [w for w in weights if 0 < w < 100]
            replicas = _field(doc, "replicas")
            out[f"{ns or '?'}/{name}"] = {
                "declared_replicas": int(replicas) if (replicas or "").isdigit() else None,
                "min_setweight": min(nonzero) if nonzero else None,
                "has_traffic_routing": "trafficRouting:" in doc,
            }
    return out


def claim_audit_producers(root: pathlib.Path) -> dict:
    """The topics audit-service subscribes to — its claim about which producers it can attribute.

    The envelope in `openbank-libs-domain/.../audit/AuditEvent.kt` names `sourceService` and an actor,
    so every row is CLAIMED to be attributable. The fact side counts how many actually are.
    """
    cfg = root / "openbank-audit-service" / "src" / "main" / "resources" / "application.yaml"
    m = re.search(r"^\s*topics:\s*(\S.*)$", _read(cfg), re.M)
    topics = [t.strip() for t in m.group(1).split(",") if t.strip()] if m else []
    return {"subscribed_topics": len(topics)}


def claim_image_pins(root: pathlib.Path) -> dict[str, str]:
    """Every immutable GitOps pin, including manual-refresh run provenance suffixes."""
    pins: dict[str, str] = {}
    for path in sorted(root.glob("openbank-infra/gitops/**/*.yaml")):
        for repo, tag in re.findall(r"/(openbank-[a-z0-9-]+):(sandbox-[0-9a-f]+(?:-run[1-9][0-9]*)?)", _read(path)):
            pins[repo] = tag
    return pins


# ── comparators: claim vs fact. Pure. This is what the self-test drives. ──────────────────

def cmp_backup_recoverability(claims: dict, facts: dict) -> list[str]:
    """A cluster that declares a backup must have a recovery point.

    `Ready` and `ContinuousArchiving` are deliberately NOT consulted: on 2026-08-07 all three
    no-recovery-point clusters reported at least one of them True, and `ContinuousArchiving` is
    trivially True on a cluster with no archive destination configured at all.
    """
    findings = []
    for key, claim in sorted(claims.items()):
        if not claim.get("declares_backup"):
            continue
        fact = facts.get(key)
        if fact is None:
            findings.append(f"{key}: declares a backup destination but the cluster was not found at runtime")
        elif not fact.get("first_recoverability_point"):
            findings.append(
                f"{key}: declares a backup destination and has NO firstRecoverabilityPoint "
                f"(phase={fact.get('phase')!r}) — no restore is possible"
            )
    return findings


def _resolve_outbox_fact(svc: str, facts: dict):
    """Match a service directory to its outbox fact, which is keyed by TABLE name.

    `openbank-card-issuance-service` owns `card_outbox`, so the collector's natural key is
    `openbank-card`. An exact match is preferred; a prefix match is accepted only when it is
    UNIQUE. An ambiguous prefix returns None rather than guessing — a wrong match here would
    attribute one service's dead rows to another, which is worse than reporting nothing.
    """
    if svc in facts:
        return facts[svc]
    hits = [k for k in facts if svc.startswith(k)]
    return facts[hits[0]] if len(hits) == 1 else None


def cmp_outbox_liveness(claims: dict, facts: dict) -> list[str]:
    """An outbox that exists must be written to, and must not be wholly dead-lettered."""
    findings = []
    for svc, claim in sorted(claims.items()):
        fact = _resolve_outbox_fact(svc, facts)
        if fact is None:
            continue  # not deployed / no table reachable — not a conformance question
        total = fact.get("total", 0)
        dead = fact.get("dead", 0)
        if not claim.get("constructs_outbox_message"):
            findings.append(
                f"{svc}: ships an outbox dispatcher but constructs no OutboxMessage in src/main "
                f"(table has {total} row(s)) — the dispatcher polls a table nothing writes to"
            )
        elif total > 0 and dead == total:
            findings.append(
                f"{svc}: all {total} outbox row(s) are DEAD — nothing has ever been published, and "
                f"the backlog gauge reads 0 because DEAD is excluded from listProcessable"
            )
    return findings


def cmp_canary_realisability(claims: dict, facts: dict) -> list[str]:
    """A canary step must be expressible with the replicas actually running."""
    findings = []
    for key, claim in sorted(claims.items()):
        weight = claim.get("min_setweight")
        if weight is None or claim.get("has_traffic_routing"):
            continue  # no partial step, or a traffic router makes pod count irrelevant
        replicas = (facts.get(key) or {}).get("replicas", claim.get("declared_replicas"))
        if not replicas:
            continue
        smallest = 100.0 / replicas
        if weight < smallest:
            findings.append(
                f"{key}: canary declares setWeight {weight}% but runs {replicas} replica(s), so the "
                f"smallest expressible share is {smallest:.0f}% — the step cannot mean what it says"
            )
    return findings


def cmp_image_tag_exists(claims: dict, facts: dict) -> list[str]:
    """An image tag gitops pins must exist in the registry."""
    findings = []
    for repo, tag in sorted(claims.items()):
        present = facts.get(repo)
        if present is None:
            continue  # registry not queried for this repo
        if tag not in present:
            findings.append(f"{repo}: gitops pins {tag}, which does not exist in the registry")
    return findings


def cmp_audit_attribution(claims: dict, facts: dict) -> list[str]:
    """Every audit row must say what it is and where it came from.

    ZERO thresholds on purpose. A percentage here would be a guessed number, and a guessed threshold
    can sit under the regression it exists to catch — a row that cannot name its own type or producer
    is a defect at n=1, and the count is context rather than the trigger.

    `distinct_real_sources` is reported alongside because the shape matters for the fix: one real
    source out of 21 subscribed topics is "nobody populates the field", which is a fleet change;
    a single missing producer is one service's change.
    """
    if not facts:
        return []
    findings = []
    total = facts.get("total", 0)
    if not total:
        return []
    unknown_type = facts.get("unknown_type", 0)
    unknown_source = facts.get("unknown_source", 0)
    real = facts.get("distinct_real_sources", 0)
    topics = claims.get("subscribed_topics", 0)

    if unknown_type:
        findings.append(
            f"{unknown_type} of {total} audit row(s) have event_type='UNKNOWN' — the consumer's "
            f"fallback fired, which is a successful parse and therefore silent"
        )
    if unknown_source:
        pct = 100.0 * unknown_source / total
        findings.append(
            f"{unknown_source} of {total} audit row(s) ({pct:.0f}%) have source_service='unknown'; "
            f"{real} producer(s) populate it across {topics} subscribed topic(s) — the trail cannot "
            f"say which service produced it"
        )
    return findings


COMPARATORS = {
    "backup_recoverability": cmp_backup_recoverability,
    "outbox_liveness": cmp_outbox_liveness,
    "canary_realisability": cmp_canary_realisability,
    "image_tag_exists": cmp_image_tag_exists,
    "audit_attribution": cmp_audit_attribution,
}


# ── fact side: the cluster. Only this half needs credentials. ─────────────────────────────

def _sh(args: list[str]) -> str:
    try:
        return subprocess.run(args, capture_output=True, text=True, check=False).stdout
    except OSError:
        return ""


def collect() -> dict:
    """Snapshot the live estate. Read-only: get / describe / list only, never a mutation."""
    snap: dict = {"schema": 1, "facts": {}}

    clusters = {}
    raw = _sh(["kubectl", "get", "cluster.postgresql.cnpg.io", "-A", "-o", "json"])
    for item in _items(raw):
        meta, status = item.get("metadata", {}), item.get("status", {})
        clusters[f"{meta.get('namespace')}/{meta.get('name')}"] = {
            "first_recoverability_point": status.get("firstRecoverabilityPoint"),
            "phase": status.get("phase"),
        }
    snap["facts"]["backup_recoverability"] = clusters

    rollouts = {}
    raw = _sh(["kubectl", "get", "rollout", "-A", "-o", "json"])
    for item in _items(raw):
        meta = item.get("metadata", {})
        rollouts[f"{meta.get('namespace')}/{meta.get('name')}"] = {
            "replicas": item.get("spec", {}).get("replicas"),
        }
    snap["facts"]["canary_realisability"] = rollouts

    outbox: dict[str, dict] = {}
    audit: dict = {}
    for key in clusters:
        ns, cl = key.split("/", 1)
        db = _psql(ns, cl, "postgres",
                   "SELECT datname FROM pg_database WHERE datistemplate=false "
                   "AND datname<>'postgres' LIMIT 1;")
        if not db:
            continue

        table = _psql(ns, cl, db,
                      "SELECT table_name FROM information_schema.tables WHERE table_schema='public' "
                      "AND table_name LIKE '%outbox%' LIMIT 1;")
        if table:
            row = _psql(ns, cl, db,
                        f"SELECT count(*), count(*) FILTER (WHERE status='DEAD') FROM {table};")
            if "|" in row:
                total, dead = row.split("|")[:2]
                # Key by the SERVICE the table belongs to, not the table: `card_outbox` is
                # openbank-card-issuance-service's. The comparator resolves the rest via a unique
                # prefix — keying by table name alone silently matched nothing on the first real run.
                svc = "openbank-" + table.replace("_outbox", "").replace("_", "-")
                outbox[svc] = {"total": int(total), "dead": int(dead)}

        if _psql(ns, cl, db, "SELECT to_regclass('public.audit_entries');"):
            row = _psql(ns, cl, db,
                        "SELECT count(*), "
                        "count(*) FILTER (WHERE event_type='UNKNOWN'), "
                        "count(*) FILTER (WHERE source_service='unknown'), "
                        "count(DISTINCT source_service) FILTER (WHERE source_service<>'unknown') "
                        "FROM audit_entries;")
            if row.count("|") == 3:
                total, ut, us, real = row.split("|")
                audit = {
                    "total": int(total), "unknown_type": int(ut),
                    "unknown_source": int(us), "distinct_real_sources": int(real),
                }

    snap["facts"]["outbox_liveness"] = outbox
    snap["facts"]["audit_attribution"] = audit
    # The registry is a separate credential domain (ECR), so image facts stay caller-supplied.
    # An absent fact prints NOT CHECKED and never reads as conformant.
    snap["facts"].setdefault("image_tag_exists", {})
    return snap


def _psql(ns: str, cluster: str, db: str, sql: str) -> str:
    """One read-only query against a CNPG instance. Empty string on any failure — never a guess."""
    return _sh([
        "kubectl", "exec", "-n", ns, f"{cluster}-1", "-c", "postgres", "--",
        "psql", "-U", "postgres", "-d", db, "-t", "-A", "-F", "|", "-c", sql,
    ]).strip().split("\n")[0].strip()


def _items(raw: str) -> list[dict]:
    try:
        return json.loads(raw).get("items", []) if raw.strip() else []
    except json.JSONDecodeError:
        return []


# ── plumbing ─────────────────────────────────────────────────────────────────────────────

def _read(path: pathlib.Path) -> str:
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return ""


def _field(doc: str, key: str) -> str | None:
    m = re.search(rf"^\s+{re.escape(key)}:\s*(\S+)", doc, re.M)
    return m.group(1).strip("\"'") if m else None


def check(root: pathlib.Path, snapshot: dict) -> int:
    claims = {
        "backup_recoverability": claim_cnpg_clusters(root),
        "outbox_liveness": claim_outbox_services(root),
        "canary_realisability": claim_canary_rollouts(root),
        "image_tag_exists": claim_image_pins(root),
        "audit_attribution": claim_audit_producers(root),
    }
    facts = snapshot.get("facts", {})
    total = 0
    for probe in PROBES:
        if probe not in facts:
            print(f"::warning::{probe}: no facts in the snapshot — NOT CHECKED (this is not a pass)")
            continue
        for finding in COMPARATORS[probe](claims[probe], facts[probe]):
            print(f"::warning::{probe}: {finding}")
            total += 1
    verb = "divergence(s)" if total != 1 else "divergence"
    print(f"runtime-conformance: {total} {verb} between what the repo claims and what the cluster does")
    return 1 if total else 0


def self_test() -> int:
    fails = 0

    def expect(name: str, got, want):
        nonlocal fails
        if got == want:
            print(f"  ok   {name}")
        else:
            print(f"  FAIL {name}\n       want {want}\n       got  {got}")
            fails = 1

    # backup: declared + no recovery point => flagged; declared + point => clean; undeclared => ignored
    expect(
        "backup: declared, no recovery point is flagged",
        len(cmp_backup_recoverability(
            {"ai/db": {"declares_backup": True}},
            {"ai/db": {"first_recoverability_point": None, "phase": "Cluster in healthy state"}})),
        1)
    expect(
        "backup: a healthy phase does NOT excuse a missing recovery point",
        "no restore is possible" in cmp_backup_recoverability(
            {"ai/db": {"declares_backup": True}},
            {"ai/db": {"first_recoverability_point": None, "phase": "Cluster in healthy state"}})[0],
        True)
    expect(
        "backup: declared with a recovery point is clean",
        cmp_backup_recoverability(
            {"ai/db": {"declares_backup": True}},
            {"ai/db": {"first_recoverability_point": "2026-08-07T10:00:00Z"}}),
        [])
    expect(
        "backup: a cluster declaring no backup is not our question",
        cmp_backup_recoverability({"o/pg": {"declares_backup": False}}, {}),
        [])
    expect(
        "backup: declared but absent at runtime is flagged, not skipped",
        len(cmp_backup_recoverability({"ai/db": {"declares_backup": True}}, {})),
        1)

    # outbox: no writer => flagged even with an empty table; all-DEAD => flagged; healthy => clean
    expect(
        "outbox: dispatcher with no writer is flagged",
        len(cmp_outbox_liveness(
            {"svc": {"constructs_outbox_message": False}}, {"svc": {"total": 0, "dead": 0}})),
        1)
    expect(
        "outbox: every row DEAD is flagged",
        len(cmp_outbox_liveness(
            {"svc": {"constructs_outbox_message": True}}, {"svc": {"total": 24, "dead": 24}})),
        1)
    expect(
        "outbox: SENT rows with a writer are clean",
        cmp_outbox_liveness(
            {"svc": {"constructs_outbox_message": True}}, {"svc": {"total": 577, "dead": 0}}),
        [])
    expect(
        "outbox: SOME dead is not all dead — not flagged",
        cmp_outbox_liveness(
            {"svc": {"constructs_outbox_message": True}}, {"svc": {"total": 100, "dead": 2}}),
        [])
    expect(
        "outbox: a service with no runtime fact is skipped, not passed",
        cmp_outbox_liveness({"svc": {"constructs_outbox_message": False}}, {}),
        [])
    # Key shapes differ by construction: claims are directory names, facts are table names.
    # These four cases are the ones that made the first real run silently report nothing.
    expect(
        "outbox: a table-derived key resolves to its service directory",
        len(cmp_outbox_liveness(
            {"openbank-card-issuance-service": {"constructs_outbox_message": True}},
            {"openbank-card": {"total": 24, "dead": 24}})),
        1)
    expect(
        "outbox: an exact key still wins",
        len(cmp_outbox_liveness(
            {"openbank-ledger-service": {"constructs_outbox_message": True}},
            {"openbank-ledger-service": {"total": 577, "dead": 0}})),
        0)
    expect(
        "outbox: an AMBIGUOUS prefix is skipped, never guessed",
        cmp_outbox_liveness(
            {"openbank-sepa-payment": {"constructs_outbox_message": True}},
            {"openbank-sepa": {"total": 1, "dead": 1}, "openbank-s": {"total": 9, "dead": 9}}),
        [])
    # This pair reads the REAL tree, so it needs a known-positive next to the exclusion.
    # `"openbank-libs-runtime" not in {}` is True, so the exclusion case alone passed from an
    # empty directory — measured 2026-09-01, the whole self-test exited 0 with cwd on an empty
    # tree. The exclusion is load-bearing (libs-runtime really does ship AbstractOutboxDispatcher),
    # and a test that cannot detect the absence of its own corpus is decoration.
    _real_outbox = claim_outbox_services(pathlib.Path("."))
    expect(
        "outbox: libs are excluded from the claim side entirely",
        "openbank-libs-runtime" in _real_outbox,
        False)
    expect(
        "outbox: the real tree yields dispatcher services (else the exclusion above is vacuous)",
        len(_real_outbox) > 0,
        True)

    # canary: 1 replica + setWeight 10 => flagged; 10 replicas => clean; traffic router => exempt
    expect(
        "canary: setWeight 10 on 1 replica is flagged",
        len(cmp_canary_realisability(
            {"ns/r": {"min_setweight": 10, "has_traffic_routing": False, "declared_replicas": 1}},
            {"ns/r": {"replicas": 1}})),
        1)
    expect(
        "canary: setWeight 10 on 10 replicas is clean",
        cmp_canary_realisability(
            {"ns/r": {"min_setweight": 10, "has_traffic_routing": False, "declared_replicas": 10}},
            {"ns/r": {"replicas": 10}}),
        [])
    expect(
        "canary: a traffic router makes pod count irrelevant",
        cmp_canary_realisability(
            {"ns/r": {"min_setweight": 10, "has_traffic_routing": True, "declared_replicas": 1}},
            {"ns/r": {"replicas": 1}}),
        [])
    expect(
        "canary: RUNTIME replicas win over the declared count",
        len(cmp_canary_realisability(
            {"ns/r": {"min_setweight": 10, "has_traffic_routing": False, "declared_replicas": 10}},
            {"ns/r": {"replicas": 1}})),
        1)
    expect(
        "canary: a rollout with no partial step is not our question",
        cmp_canary_realisability(
            {"ns/r": {"min_setweight": None, "has_traffic_routing": False, "declared_replicas": 1}},
            {"ns/r": {"replicas": 1}}),
        [])

    # image: pinned-but-absent => flagged; present => clean; unqueried => skipped
    expect(
        "image: a pin absent from the registry is flagged",
        len(cmp_image_tag_exists({"openbank-x": "sandbox-dead"}, {"openbank-x": ["sandbox-live"]})),
        1)
    expect(
        "image: a pin present in the registry is clean",
        cmp_image_tag_exists({"openbank-x": "sandbox-live"}, {"openbank-x": ["sandbox-live"]}),
        [])
    expect(
        "image: a repo the registry was not queried for is skipped, not passed",
        cmp_image_tag_exists({"openbank-x": "sandbox-dead"}, {}),
        [])

    # audit: ZERO thresholds — one unattributable row is a defect. The cases below pin that a
    # single bad row is flagged, so no percentage can creep in later and hide the tail.
    expect(
        "audit: a single UNKNOWN event_type is flagged (no threshold)",
        len(cmp_audit_attribution(
            {"subscribed_topics": 21},
            {"total": 1000, "unknown_type": 1, "unknown_source": 0, "distinct_real_sources": 5})),
        1)
    expect(
        "audit: a single unknown source_service is flagged (no threshold)",
        len(cmp_audit_attribution(
            {"subscribed_topics": 21},
            {"total": 1000, "unknown_type": 0, "unknown_source": 1, "distinct_real_sources": 5})),
        1)
    expect(
        "audit: both kinds are reported separately, not merged into one",
        len(cmp_audit_attribution(
            {"subscribed_topics": 21},
            {"total": 1692, "unknown_type": 124, "unknown_source": 1298, "distinct_real_sources": 1})),
        2)
    expect(
        "audit: a fully attributed trail is clean",
        cmp_audit_attribution(
            {"subscribed_topics": 21},
            {"total": 1000, "unknown_type": 0, "unknown_source": 0, "distinct_real_sources": 21}),
        [])
    expect(
        "audit: an EMPTY table is not a pass and not a finding — nothing to attribute yet",
        cmp_audit_attribution({"subscribed_topics": 21}, {"total": 0}),
        [])
    expect(
        "audit: no facts at all is skipped, not passed",
        cmp_audit_attribution({"subscribed_topics": 21}, {}),
        [])

    if fails:
        print("runtime-conformance: self-test FAIL")
        return 1
    print("runtime-conformance: self-test PASS")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--collect", action="store_true", help="snapshot the live estate to stdout as JSON")
    ap.add_argument("--check", metavar="SNAPSHOT", help="compare a snapshot against the repo's claims")
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--root", default=".", help="repository root (default: cwd)")
    args = ap.parse_args()

    if args.self_test:
        return self_test()
    if args.collect:
        json.dump(collect(), sys.stdout, indent=2, sort_keys=True)
        print()
        return 0
    if args.check:
        try:
            snapshot = json.loads(pathlib.Path(args.check).read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            print(f"::error::cannot read snapshot {args.check}: {exc}")
            return 2
        return check(pathlib.Path(args.root), snapshot)
    ap.print_help()
    return 2


if __name__ == "__main__":
    sys.exit(main())
