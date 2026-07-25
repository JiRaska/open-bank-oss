#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""
Generate per-service operational starter runbooks (prod-readiness C9/C6).

For every openbank-<short>-service this writes docs/runbooks/svc-<short>.md with a
service-specific operational runbook grounded in the service's DECLARED facts:
governance.yaml (data domain, datastore, schema, classification, retention,
upstream/downstream lineage) + its HTTP port. Each runbook carries a "Disaster
recovery" section (RPO/RTO + restore pointer) so the readiness collector scores
C6 = Verified (documented DR procedure), not just C9.

These are STARTER runbooks: real, fact-grounded scaffolding that ops extend with
hard-won specifics. Bank-grade (C9=3 / C6=3) still requires a real on-call rotation
and an exercised DR drill — those live as TTL'd attestations, never faked here.

The generator SKIPS a runbook that already exists, so it never clobbers a
hand-extended one. Pass --force to regenerate (overwrites — use with care).

Usage:
    generate-service-runbooks.py [--force] [<short> ...]   # default: all services
"""
from __future__ import annotations
import argparse
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gitops_facts  # noqa: E402  (path must be set before the import)

REPO = Path(__file__).resolve().parents[2]
RUNBOOKS = REPO / "docs" / "runbooks"
GITOPS = REPO / "openbank-infra" / "gitops"


def read(p: Path) -> str:
    try:
        return p.read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return ""


def gov_facts(short: str) -> dict:
    """Parse the flat scalars + lineage service names from governance.yaml."""
    txt = read(REPO / f"openbank-{short}-service" / "governance.yaml")
    facts = {}
    for key in ("dataDomain", "primaryDatastore", "schemaName",
                "dataClassification", "retentionPolicy", "dataLineageRole"):
        m = re.search(rf"^{key}:\s*(.+?)\s*$", txt, re.M)
        if m:
            facts[key] = m.group(1).strip()
    # lineage service names, split by the upstream/downstream blocks
    up = re.search(r"upstream:(.*?)(?:^\s{2}\w|\Z)", txt, re.S | re.M)
    down = re.search(r"downstream:(.*?)(?:^\s{2}\w|\Z)", txt, re.S | re.M)
    facts["upstream"] = re.findall(r"serviceName:\s*([\w-]+)", up.group(1)) if up else []
    facts["downstream"] = re.findall(r"serviceName:\s*([\w-]+)", down.group(1)) if down else []
    return facts


def http_port(short: str) -> str:
    """The Quarkus HTTP port (skip 8085, the shared management port)."""
    txt = read(REPO / f"openbank-{short}-service" / "src" / "main" / "resources" / "application.yaml")
    for p in re.findall(r"port:\s*(\d+)", txt):
        if p != "8085":
            return p
    return "?"


def service_namespace(short: str) -> str:
    """The namespace this service's Deployment/Rollout actually lands in.

    The runbook used to interpolate the service short name as its namespace, which is wrong for
    a third of the fleet: document-service runs in `documents`, ap2 and mcp in `platform`,
    settlement/vop/card-issuance/standing-order in `payments`. Every `kubectl -n <ns>` line in
    those runbooks named a namespace that does not exist, so the first command an on-call
    engineer copied out of them returned nothing.

    The resolution itself now lives in gitops_facts, shared with the prod-readiness collector
    and the PodMonitor coverage gate — three tools were each growing their own copy of this
    question, and two of them had already answered it wrong in two different ways (#2255). Only
    the display fallback is local: a service with no workload still needs *something* to render.
    """
    return gitops_facts.service_namespace(short, GITOPS) or short


def is_stateless(datastore: str) -> bool:
    """A service that declares no primary datastore. `none` / `n/a` / empty all count."""
    return gitops_facts.is_stateless(datastore)


def backup_configured(short: str) -> bool:
    """True iff a deployed manifest configures a backup for this service's datastore
    (CNPG barmanObjectStore). Mirrors the readiness collector's C5 detection so the
    runbook's DR text never claims a backup the cluster doesn't actually have."""
    comp = GITOPS / "components"
    if not comp.is_dir():
        return False
    for f in comp.rglob("*.yaml"):
        t = read(f)
        if "barmanObjectStore" in t and (short in t or f.parent.name.startswith(short)):
            return True
    return False


def dr_for(datastore: str, has_backup: bool) -> str:
    d = (datastore or "").lower()
    if is_stateless(datastore):
        # A stateless service has nothing to restore, and telling an on-call engineer to
        # "restore from the datastore's managed backup" sends them hunting for a backup
        # that does not exist — during an incident, at 3am. Its recovery is a redeploy.
        return ("- **Mechanism:** none needed — this service declares no primary datastore, so it "
                "holds no state to lose. Recovery is a redeploy from the GitOps manifests, which "
                "are the source of truth.\n"
                "- **Restore:** re-sync the ArgoCD Application (or `kubectl rollout restart` the "
                "Deployment). Any state this service reads lives in its upstream services above — "
                "recover those first, using their own runbooks.\n"
                "- **Verify:** health endpoint green, then re-drive one request end to end against "
                "an upstream that is already known-good.")
    if "postgres" in d:
        proc = ("- **Restore:** create a `Cluster` with `bootstrap.recovery` pointing at the "
                "backup object store; CNPG replays WAL to the target time. See runbook 0003 "
                "(PG major upgrade) for the cluster-recreate mechanics.\n"
                "- **Verify:** `kubectl cnpg status <db>-rw -n <ns>` shows the recovered cluster "
                "Healthy and the `*-app` secret regenerated.")
        if has_backup:
            return ("- **Mechanism:** CloudNativePG continuous WAL archiving + base backups to "
                    "S3 (`barmanObjectStore`). Point-in-time recovery (PITR).\n" + proc)
        return ("- **⚠ Prerequisite NOT met:** this PostgreSQL cluster has **no backup configured** "
                "(`barmanObjectStore` absent — prod-readiness C5=1). **DR is not achievable today** "
                "and the RPO/RTO targets above do NOT yet apply. Enabling the CNPG backup is the "
                "blocking prerequisite (see the backup sweep). Once enabled, the procedure is:\n"
                "- **Mechanism (after enablement):** CNPG continuous WAL + base backups → PITR.\n" + proc)
    if "cassandra" in d:
        return ("- **Mechanism:** node-level snapshots + commitlog archiving to S3 (verify a "
                "snapshot schedule is actually configured before relying on this).\n"
                "- **Restore:** stop writes, restore the snapshot to a fresh ring, replay "
                "commitlog to the target time, then re-point the service at the recovered keyspace.\n"
                "- **Verify:** `nodetool status` shows all nodes UN and a row-count spot check "
                "against the last known-good figure.")
    return ("- **Mechanism:** restore from the datastore's managed backup to a fresh instance "
            "(confirm a backup is actually configured for this datastore first).\n"
            "- **Restore:** provision a new datastore from the latest backup, replay any "
            "incremental logs, re-point the service.\n"
            "- **Verify:** health endpoint green + a domain spot check against last known-good.")


TEMPLATE = """<!-- Generated starter runbook (generate-service-runbooks.py) from declared
service facts. Real scaffolding — EXTEND with operational specifics; do not delete.
Bank-grade ops (prod-readiness C9=3 / C6=3) still needs a real on-call rotation and an
exercised DR drill, tracked as TTL'd attestations, never faked here. -->

# Runbook — openbank-{short}-service

> Operational runbook for the `{short}` service. Data domain **{domain}**,
> classification **{classification}**, datastore **{datastore}**.

## Service identity

| Field | Value |
|---|---|
| Service | `openbank-{short}-service` |
| HTTP port | `{port}` |
| Data domain | {domain} |
| Datastore | {datastore} (schema `{schema}`) |
| Classification | {classification} |
| Retention | {retention} |
| Lineage role | {role} |

## Dependencies

- **Upstream (this service consumes):** {upstream}
- **Downstream (depends on this service):** {downstream}

A failure here propagates to the downstream services above — check them when
triaging an incident that starts on `{short}`.

## Health & probes

- Readiness: `GET :{port}/q/health/ready` · Liveness: `GET :{port}/q/health/live`
- Metrics: scraped by the fleet PodMonitor (namespace `{ns}`); dashboards in Grafana.
- Logs: `kubectl logs -n {ns} deploy/{short}-service -f`, or Loki
  `{{namespace="{ns}"}}`.

## Routine operations

- **Restart:** `kubectl rollout restart deploy/{short}-service -n {ns}` (rolling, zero-downtime at >1 replica).
- **Scale:** `kubectl scale deploy/{short}-service -n {ns} --replicas=<n>` (or edit the GitOps Deployment — GitOps is source of truth, a manual scale is reverted by ArgoCD).
- **Config/secret change:** edit the GitOps manifest; ArgoCD syncs. Never `kubectl edit` in place.

## Common failure modes

{failure_modes}
- **Downstream errors:** verify the upstream dependencies above are healthy before
  assuming the fault is local.

## Disaster recovery

{rpo}
{dr}

> RPO/RTO above are documented targets. They become **Bank-grade** (prod-readiness
> C6=3) only once a restore/failover drill has actually been rehearsed and attested
> (`openbank-libs/governance/attestations.yaml: {short}.dr_drill`).

## Escalation & break-glass

- First responder: the owning squad's on-call (rotation tracked as the
  `{short}.oncall` attestation — until that is live, escalate via the team channel).
- Break-glass cluster access is audited; use it only for a declared incident and
  record the justification.
"""


def render(short: str) -> str:
    f = gov_facts(short)
    up = ", ".join(f"`{s}`" for s in f.get("upstream", [])) or "_none declared_"
    down = ", ".join(f"`{s}`" for s in f.get("downstream", [])) or "_none declared_"
    datastore = f.get("primaryDatastore", "—")
    stateless = is_stateless(datastore)
    has_backup = False if stateless else backup_configured(short)
    if stateless:
        rpo = ("- **RPO: n/a** — no persistent state. **RTO target:** ≤ 10 min "
               "(image pull + rollout).")
        failure_modes = (
            "- **Pod CrashLoopBackOff at boot:** usually a missing/invalid config or secret\n"
            "  (`ExternalSecret` not synced). Check `kubectl describe pod` events and the\n"
            "  first 50 log lines.\n"
            "- **Readiness flapping:** this service holds no datastore, so look outward — an\n"
            "  upstream dependency below, or the OPA sidecar if `AUTHZ_ENFORCE` is on (with no\n"
            "  reachable PDP, `@Authorize` fails closed)."
        )
    else:
        rpo = ("- **RPO target:** ≤ 5 min (continuous archiving). **RTO target:** ≤ 30 min (restore + warm-up)."
               if has_backup else
               "- **RPO/RTO: undefined** — no backup is configured yet (see the prerequisite below), so "
               "no recovery-point/time guarantee can be made today.")
        failure_modes = (
            "- **Pod CrashLoopBackOff at boot:** usually a missing/invalid config or secret\n"
            "  (`ExternalSecret` not synced) or a Flyway checksum mismatch. Check\n"
            "  `kubectl describe pod` events and the first 50 log lines.\n"
            f"- **Readiness flapping:** datastore ({datastore}) unreachable or saturated — check the\n"
            "  datastore pod/cluster health and connection-pool metrics."
        )
    return TEMPLATE.format(
        short=short,
        ns=service_namespace(short),
        failure_modes=failure_modes,
        domain=f.get("dataDomain", "—"),
        datastore=datastore,
        schema=f.get("schemaName", "—"),
        classification=f.get("dataClassification", "—"),
        retention=f.get("retentionPolicy", "—"),
        role=f.get("dataLineageRole", "—"),
        port=http_port(short),
        upstream=up,
        downstream=down,
        rpo=rpo,
        dr=dr_for(f.get("primaryDatastore", ""), has_backup),
    )


def all_services() -> list[str]:
    out = []
    for d in sorted(REPO.glob("openbank-*-service")):
        m = re.match(r"openbank-(.+)-service", d.name)
        if m:
            out.append(m.group(1))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("services", nargs="*")
    ap.add_argument("--force", action="store_true", help="overwrite existing runbooks")
    args = ap.parse_args()
    RUNBOOKS.mkdir(parents=True, exist_ok=True)
    targets = args.services or all_services()
    created, skipped = 0, 0
    for short in targets:
        if not (REPO / f"openbank-{short}-service").is_dir():
            print(f"skip: openbank-{short}-service not found", file=sys.stderr)
            continue
        out = RUNBOOKS / f"svc-{short}.md"
        if out.exists() and not args.force:
            skipped += 1
            continue
        out.write_text(render(short), encoding="utf-8")
        created += 1
    print(f"runbooks: {created} written, {skipped} kept (existing)")


if __name__ == "__main__":
    main()
