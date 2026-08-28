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
    txt = read(gitops_facts.module_dir(short, REPO) / "governance.yaml")
    facts = {}
    # databaseName (ADR-0196) replaced schemaName, which named a Postgres schema that existed
    # nowhere in the fleet. ownsNoDatabase is an explicit assertion, not inferred from a datastore
    # string — keep reading both here so this script doesn't reintroduce that inference.
    for key in ("dataDomain", "primaryDatastore", "databaseName", "ownsNoDatabase",
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
    txt = read(gitops_facts.module_dir(short, REPO) / "src" / "main" / "resources" / "application.yaml")
    for p in re.findall(r"port:\s*(\d+)", txt):
        if p != "8085":
            return p
    return "?"


def management_port(short: str) -> str:
    """The declared management port, preferring the workload's actual probe/scrape contract.

    Health and metrics deliberately live on a separate Quarkus management listener in some
    services. Reusing the business HTTP port makes an on-call command look reasonable but return
    404/connection refused precisely during an incident. The workload declaration is authoritative
    for this runbook because its named ``management`` port is what probes and the fleet PodMonitor
    actually target; application.yaml is only a fallback for undeployed services.
    """
    names = gitops_facts.module_names(short)
    for manifest in sorted(GITOPS.rglob("*.yaml")):
        for doc in read(manifest).split("\n---"):
            kind = re.search(r"^kind:\s*(\S+)", doc, re.M)
            name = re.search(r"^\s{2}name:\s*(\S+)", doc, re.M)
            if not kind or kind.group(1) not in ("Deployment", "Rollout") or not name or name.group(1) not in names:
                continue
            port = re.search(r"^\s+-\s+name:\s*management\s*$\s*^\s+containerPort:\s*(\d+)", doc, re.M)
            if port:
                return port.group(1)
    txt = read(gitops_facts.module_dir(short, REPO) / "src" / "main" / "resources" / "application.yaml")
    configured = re.search(r"^\s{2}management:\s*$.*?^\s{4}port:\s*(\d+)", txt, re.M | re.S)
    return configured.group(1) if configured else "8085"


def application_automated(short: str) -> bool | None:
    """Whether the Argo Application owning this component declares automated sync.

    ``False`` is materially different from a missing Application: a workload YAML under a manual
    Application is desired state only, not a claim that Argo ever created a pod. Keep this small
    parser intentionally tied to the source path rather than the Application's display name.
    """
    marker = f"path: openbank-infra/gitops/components/{short}"
    for app in sorted((GITOPS / "apps").glob("*.yaml")):
        text = read(app)
        if marker not in text:
            continue
        sync_policy = re.search(r"^  syncPolicy:\s*$([\s\S]*?)(?=^\S|\Z)", text, re.M)
        return bool(sync_policy and re.search(r"^\s+automated:\s*$", sync_policy.group(1), re.M))
    return None


def workload_live_unverified(short: str) -> bool:
    return gitops_facts.service_namespace(short, GITOPS) is not None and application_automated(short) is False


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

    That fallback is a LIE for the one service it applies to, which is why
    [deployment_status] exists: with no workload anywhere in gitops the runbook silently
    interpolated the short name and told the on-call engineer to run `kubectl -n tax-reporting`
    against a namespace that has never existed — the exact defect this function's second
    paragraph records as fixed, surviving in the one case the fix could not resolve. The
    fallback stays (the commands need to render as something) and the banner now says the
    commands do not apply.
    """
    return gitops_facts.service_namespace(short, GITOPS) or short


def deployment_status(short: str) -> str:
    """A banner for a service with NO workload in gitops — Deployment, Rollout or nothing.

    Derived, never declared: the same `gitops_facts.service_namespace` question the
    prod-readiness collector asks for its NOT-DEPLOYED verdict (#5706, #5760), so the runbook
    and the matrix cannot disagree about whether a service runs. Empty for every deployed
    service, so this changes no committed runbook but the undeployed one.
    """
    if gitops_facts.service_namespace(short, GITOPS) is not None:
        if workload_live_unverified(short):
            return (
                "## Deployment status — WORKLOAD DESIRED — LIVE STATUS UNVERIFIED\n"
                "\n"
                "The GitOps manifest declares this workload, but its owning ArgoCD Application has **no\n"
                "automated sync**. This is desired state, not evidence that the Deployment, database,\n"
                "metrics scrape, or traffic path exists in the cluster. Before using any command below\n"
                "as an incident procedure, complete the separately reviewed manual sync and observe\n"
                "the resulting deployment and health checks.\n"
                "\n"
            )
        return ""
    component = GITOPS / "components" / short
    data_plane = "\n".join(read(path) for path in component.glob("*.yaml"))
    staged_namespace = re.search(
        r"^kind:\s*Namespace\s*$.*?^\s{2}name:\s*(\S+)", data_plane, re.M | re.S
    )
    staged_cnpg = bool(re.search(
        r"^apiVersion:\s*postgresql\.cnpg\.io/\S+\s*$.*?^kind:\s*Cluster\s*$",
        data_plane,
        re.M | re.S,
    ))
    if staged_namespace or staged_cnpg:
        namespace = staged_namespace.group(1) if staged_namespace else short
        data_plane_facts = []
        if staged_namespace:
            data_plane_facts.append(f"Namespace `{namespace}`")
        if staged_cnpg:
            data_plane_facts.append("CNPG cluster")
        data_plane_description = " and ".join(data_plane_facts)
        return (
            "## Deployment status — WORKLOAD NOT DEPLOYED\n"
            "\n"
            "**This service has no workload anywhere in `openbank-infra/gitops/`** — no Deployment\n"
            f"or Rollout. Its data plane is declared separately ({data_plane_description}), but\n"
            "declared GitOps state is not live evidence: do not run the workload, claim\n"
            "traffic, or treat backup configuration as healthy until the separately reviewed sync and\n"
            "cluster-health checks have completed. The operational commands below remain plans for the\n"
            "absent workload, not proof that it has ever run.\n"
            "\n"
            "The production-readiness matrix reports this as **NOT-DEPLOYED** because the service\n"
            "workload is absent; a staged namespace or database cannot close runtime-readiness cells.\n"
            "\n"
        )
    return (
        "## Deployment status — NOT DEPLOYED\n"
        "\n"
        "**This service has no workload anywhere in `openbank-infra/gitops/`** — no Deployment,\n"
        "no Rollout, and therefore no namespace, no CNPG cluster, no NetworkPolicy and no\n"
        "PodMonitor coverage. It is a released component (it has a `version.txt`) that has never\n"
        "run, so **every `kubectl` command below names a namespace that does not exist** and every\n"
        "procedure here is a plan rather than a rehearsed one.\n"
        "\n"
        "The production-readiness matrix reports it as **NOT-DEPLOYED** rather than NO-GO for the\n"
        "same reason: the cells it fails are consequences of the absent workload, not controls\n"
        "someone skipped, and none of them can be closed by a repo change. Whether this service\n"
        "should be deployed is an owner decision — see the service's own `CLAUDE.md`.\n"
        "\n"
    )


def is_stateless(datastore: str) -> bool:
    """A service that declares no primary datastore. `none` / `n/a` / empty all count."""
    return gitops_facts.is_stateless(datastore)


def owns_no_database(facts: dict) -> bool:
    """True iff the service owns no database — the ADR-0196 `ownsNoDatabase: true` assertion,
    falling back to the old string-inference for a governance.yaml this script can still parse
    but that predates the assertion (defensive; the CI schema gate should already reject that
    shape). NOT the same question as `is_stateless(datastore)`: a service can own no database
    while still holding real state elsewhere — copilot and customer-edge both declare
    `primaryDatastore: Redis` + `ownsNoDatabase: true`, and customer-edge's Redis entries include
    durable, TTL-less passkey credentials. Conflating the two is exactly the inaccuracy that
    caused this runbook to tell an on-call engineer "restore from the datastore's managed
    backup" for a service with no database and no such backup."""
    if facts.get("ownsNoDatabase", "").strip().lower() == "true":
        return True
    return is_stateless(facts.get("primaryDatastore", ""))


def backup_configured(short: str) -> bool:
    """True iff a deployed manifest configures a backup for this service's datastore
    (a CNPG `kind: Cluster` declaring `spec.backup.barmanObjectStore`). Mirrors the readiness
    collector's C5 detection so the runbook's DR text never claims a backup the cluster
    doesn't actually have.

    This used to be a whole-file substring test — `"barmanObjectStore" in text and short in text`
    over every file under `components/` — which matched PROSE, not configuration. See
    `gitops_facts.cnpg_backup_configured` for the collision it produced and why the answer is
    now read structurally."""
    return gitops_facts.cnpg_backup_configured(short, GITOPS)


def dr_for(datastore: str, has_backup: bool, owns_no_db: bool = None) -> str:
    d = (datastore or "").lower()
    # owns_no_db defaults to the old string-inference for any caller that still passes only
    # `datastore` (kept so this stays a narrow, additive change to a stable public function).
    no_db = is_stateless(datastore) if owns_no_db is None else owns_no_db
    if no_db and is_stateless(datastore):
        # Nothing declared at all — nothing to restore, and telling an on-call engineer to
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
    if no_db:
        # Owns no DATABASE (ADR-0196 `ownsNoDatabase: true`) but declares a real datastore —
        # copilot and customer-edge both keep state in Redis with no database behind it. Do NOT
        # claim "nothing to lose": customer-edge's Redis entries include durable, TTL-less
        # passkey credentials, so a blanket "safe to lose" claim would be actively wrong there.
        return (f"- **Mechanism:** this service owns no database — there is no managed backup to "
                f"restore, and none is expected. It does hold state in **{datastore}**.\n"
                f"- **Before assuming zero impact:** check this service's own `governance.yaml` "
                f"and `{datastore}` keys for anything with a long or no TTL (a durable credential, "
                f"not a session cache) — losing that requires its own recovery path, not a redeploy.\n"
                "- **Restore:** re-sync the ArgoCD Application (or `kubectl rollout restart` the "
                f"Deployment). Verify against the `{datastore}` cluster's own health/backup posture, "
                "which this runbook does not track.\n"
                "- **Verify:** health endpoint green, then re-drive one request end to end.")
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

{deployment_status}## Service identity

| Field | Value |
|---|---|
| Service | `{module}` |
| HTTP port | `{port}` |
| Data domain | {domain} |
| Datastore | {datastore} (database `{database}`) |
| Classification | {classification} |
| Retention | {retention} |
| Lineage role | {role} |

## Dependencies

- **Upstream (this service consumes):** {upstream}
- **Downstream (depends on this service):** {downstream}

A failure here propagates to the downstream services above — check them when
triaging an incident that starts on `{short}`.

## Health & probes

- Readiness: `GET :{management_port}/q/health/ready` · Liveness: `GET :{management_port}/q/health/live`
- Metrics: {metrics_status}
- Logs: {logs_cmd}, or Loki
  `{{namespace="{ns}"}}`.

## Routine operations

{operations_caveat}- **Restart:** {restart_cmd}
- **Scale:** {scale_cmd} (or edit the GitOps manifest — GitOps is source of truth; a later ArgoCD sync reconciles manual changes).
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


def ops_commands(short: str, ns: str) -> dict[str, str]:
    """The three incident commands, correct for the kind that actually carries this service.

    21 of the fleet's workloads are Argo Rollouts — essentially the whole money path — and kubectl
    does not treat them as Deployments. Every runbook used to say `deploy/<svc>`, so `logs`,
    `restart` and `scale` all answered `Error from server (NotFound)` for ledger, consent,
    sepa-payment, transaction, settlement, fraud, sanctions, kyc and twelve more (issue #2662).

    Swapping in `rollout/` fixes only ONE of the three, which is why each form below was run against
    the live cluster before being written here:

      | command | deploy/ | rollout/ | works |
      |---------|---------|----------|-------|
      | logs    | no      | **no**   | `-l app.kubernetes.io/name=<svc>` |
      | scale   | no      | yes      | `scale rollout/<svc>` |
      | restart | no      | **no**   | `kubectl argo rollouts restart` |

    `kubectl logs` and `kubectl rollout restart` do not understand the Rollout CRD at all. The
    plugin-free restart is offered alongside the plugin form on purpose: a runbook that assumes a
    kubectl plugin on the reader's laptop fails in exactly the situation it exists for.
    """
    svc = f"{short}-service"
    if gitops_facts.workload_kind(short, GITOPS) == "Rollout":
        return {
            "logs_cmd": f"`kubectl logs -n {ns} -l app.kubernetes.io/name={svc} -f`",
            "restart_cmd": (
                f"`kubectl argo rollouts restart {svc} -n {ns}` (Argo Rollout — plain "
                f"`kubectl rollout restart` does NOT work on the CRD). Without the plugin: "
                f"`kubectl patch rollout {svc} -n {ns} --type merge "
                f'-p \'{{"spec":{{"restartAt":"<RFC3339-now>"}}}}\'`.'
            ),
            "scale_cmd": f"`kubectl scale rollout/{svc} -n {ns} --replicas=<n>`",
        }
    return {
        "logs_cmd": f"`kubectl logs -n {ns} deploy/{svc} -f`",
        "restart_cmd": f"`kubectl rollout restart deploy/{svc} -n {ns}` (rolling, zero-downtime at >1 replica).",
        "scale_cmd": f"`kubectl scale deploy/{svc} -n {ns} --replicas=<n>`",
    }


def render(short: str) -> str:
    f = gov_facts(short)
    up = ", ".join(f"`{s}`" for s in f.get("upstream", [])) or "_none declared_"
    down = ", ".join(f"`{s}`" for s in f.get("downstream", [])) or "_none declared_"
    datastore = f.get("primaryDatastore", "—")
    owns_none = owns_no_database(f)
    nothing_at_all = owns_none and is_stateless(datastore)
    has_backup = False if owns_none else backup_configured(short)
    if nothing_at_all:
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
    elif owns_none:
        # Owns no database but declares a real datastore (copilot, customer-edge: Redis). RPO/RTO
        # for THAT store is a separate question this runbook does not answer — see dr_for().
        rpo = (f"- **RPO/RTO: not this service's to promise** — it owns no database. Its "
               f"**{datastore}** state has its own recovery posture; see the mechanism below "
               "before assuming zero impact.")
        failure_modes = (
            "- **Pod CrashLoopBackOff at boot:** usually a missing/invalid config or secret\n"
            "  (`ExternalSecret` not synced). Check `kubectl describe pod` events and the\n"
            "  first 50 log lines.\n"
            f"- **Readiness flapping:** this service owns no database, but check its **{datastore}**\n"
            "  connectivity before ruling out the datastore — an upstream dependency below, or the\n"
            "  OPA sidecar if `AUTHZ_ENFORCE` is on (with no reachable PDP, `@Authorize` fails\n"
            "  closed), are the other likely causes."
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
    live_unverified = workload_live_unverified(short)
    return TEMPLATE.format(
        short=short,
        module=gitops_facts.module_dir(short, REPO).name,
        ns=service_namespace(short),
        deployment_status=deployment_status(short),
        failure_modes=failure_modes,
        domain=f.get("dataDomain", "—"),
        datastore=datastore,
        database=f.get("databaseName", "—"),
        classification=f.get("dataClassification", "—"),
        retention=f.get("retentionPolicy", "—"),
        role=f.get("dataLineageRole", "—"),
        port=http_port(short),
        management_port=management_port(short),
        metrics_status=(
            f"declared for the fleet PodMonitor (namespace `{service_namespace(short)}`), but live scrape status is unverified until manual sync and health observation."
            if live_unverified else
            f"scraped by the fleet PodMonitor (namespace `{service_namespace(short)}`); dashboards in Grafana."
        ),
        operations_caveat=(
            "> **Live status unverified:** the commands below are planned procedures until the manual ArgoCD sync and cluster health checks have been observed.\n\n"
            if live_unverified else ""
        ),
        upstream=up,
        downstream=down,
        rpo=rpo,
        dr=dr_for(datastore, has_backup, owns_none),
        **ops_commands(short, service_namespace(short)),
    )


def all_services() -> list[str]:
    out = []
    out = set()
    for d in REPO.glob("openbank-*-service"):
        m = re.match(r"openbank-(.+)-service", d.name)
        if m:
            out.add(m.group(1))
    # Every module rules.yaml declares money-path needs a runbook too — the `-service` glob alone
    # skipped openbank-sepa-payment, openbank-sepa-instant and openbank-domestic-payment, so the
    # three modules that move SEPA and domestic payments had no operational runbook at all (#2364).
    for short in gitops_facts.money_path_services(REPO):
        if gitops_facts.module_dir(short, REPO).is_dir():
            out.add(short)
    return sorted(out)



def self_test() -> int:
    """Falsify the DR-text classifier.

    These runbooks are read by an on-call engineer at 3am, so a wrong branch here is not a
    documentation defect — it is an instruction. The incident this function's docstring
    records is exactly that: a runbook told the engineer to "restore from the datastore's
    managed backup" for a service that has no database and no such backup.

    Two distinctions carry the whole thing, and they are NOT the same question:
      * is_stateless(datastore) — declares no primary datastore at all
      * ownsNoDatabase — owns no DATABASE, but may still hold real state (copilot and
        customer-edge both declare Redis + ownsNoDatabase, and customer-edge's Redis holds
        durable TTL-less passkey credentials)
    Conflating them produces confident, wrong instructions in both directions: "you have
    nothing to lose" for a service holding credentials, or a backup-restore procedure for a
    service with no backup.
    """
    fails: list[str] = []

    def says(text: str, *needles: str) -> bool:
        return all(n in text for n in needles)

    def case(label: str, ok: bool) -> None:
        if not ok:
            fails.append(label)

    # STATELESS: no datastore at all. Recovery is a redeploy, and the text must say so without
    # ever mentioning a backup.
    t = dr_for("none", has_backup=False)
    case("a stateless service is told to redeploy, not restore",
         says(t, "no primary datastore", "redeploy"))
    case("a stateless service is NOT offered a backup restore", "managed backup" not in t)

    # OWNS NO DATABASE but holds state — the incident case. It must NOT claim zero impact, and
    # must point at the TTL question rather than a restore procedure.
    t = dr_for("Redis", has_backup=False, owns_no_db=True)
    case("a no-database service with state is told there is no backup to restore",
         says(t, "owns no database", "no managed backup"))
    case("...and is told to check for durable, TTL-less keys",
         says(t, "TTL"))
    case("...and is NOT told it holds nothing to lose",
         "holds no state to lose" not in t)

    # POSTGRES WITH a backup: PITR is real and the procedure applies.
    t = dr_for("PostgreSQL", has_backup=True)
    case("a backed-up postgres gets the PITR mechanism", says(t, "WAL archiving", "PITR"))
    case("...and carries no unmet-prerequisite warning", "NOT met" not in t)

    # POSTGRES WITHOUT a backup: the sharpest case. The procedure must be prefixed by the fact
    # that DR IS NOT ACHIEVABLE — printing the same steps without that line is the failure
    # that reads as a working runbook at the exact moment it is being followed.
    t = dr_for("PostgreSQL", has_backup=False)
    case("an unbacked-up postgres says DR is not achievable today",
         says(t, "no backup configured", "not achievable"))
    case("...and still shows the procedure for after enablement", "bootstrap.recovery" in t)

    # The has_backup flag must actually change the answer — if the two postgres texts were
    # identical, the flag would be decorative and the warning could never appear.
    case("has_backup changes the postgres text",
         dr_for("PostgreSQL", has_backup=True) != dr_for("PostgreSQL", has_backup=False))

    # owns_no_db must override the datastore-based inference. Defaulting it to the inference
    # is what conflates the two questions the docstring warns about.
    case("owns_no_db=True changes the answer for a service WITH a datastore",
         dr_for("Redis", has_backup=False, owns_no_db=True)
         != dr_for("Redis", has_backup=False, owns_no_db=False))

    # --- the NOT-DEPLOYED banner (#5706, #5760) ---------------------------------------------
    # Falsified against a real repo fact rather than a fixture, because the whole point is that
    # the banner and the readiness matrix answer the SAME question from the SAME resolver: if
    # gitops_facts ever starts resolving a namespace for an undeployed service, both this and
    # the collector's NOT-DEPLOYED verdict go wrong together and this case is what says so.
    undeployed = [x for x in all_services() if gitops_facts.service_namespace(x, GITOPS) is None]
    deployed = [x for x in all_services() if gitops_facts.service_namespace(x, GITOPS) is not None]
    case("a deployed service gets no deployment banner",
         all(deployment_status(x) == "" for x in deployed[:5]))
    # A manual-sync Application is a third state: its Deployment YAML is desired state, not proof
    # that Argo has ever created a pod. Incentive is the fixture because it deliberately has no
    # automated sync while this exact distinction is under rollout review.
    incentive = render("incentive")
    case("a manual-sync workload is explicitly live-unverified",
         says(incentive, "WORKLOAD DESIRED — LIVE STATUS UNVERIFIED", "no\nautomated sync"))
    case("a manual-sync workload does not present declared metrics as a live scrape",
         says(incentive, "live scrape status is unverified"))
    case("a separate management listener is used for health commands",
         "GET :8087/q/health/ready" in incentive and "GET :8156/q/health/ready" not in incentive)
    if undeployed:
        absent_data_plane = [
            x for x in undeployed if "namespace that does not exist" in deployment_status(x)
        ]
        if absent_data_plane:
            t = deployment_status(absent_data_plane[0])
            case(
                "an undeployed service without a staged data plane is told its kubectl commands name a namespace that does not exist",
                says(t, "NOT DEPLOYED", "namespace that does not exist"),
            )
        staged_data_plane = [
            x for x in undeployed if "data plane is declared separately" in deployment_status(x)
        ]
        if staged_data_plane:
            t = deployment_status(staged_data_plane[0])
            case(
                "an undeployed service with a staged data plane never calls that declared namespace absent",
                says(t, "WORKLOAD NOT DEPLOYED", "data plane is declared separately")
                and "namespace that does not exist" not in t,
            )
        case("...and every undeployed banner reaches its rendered runbook",
             all("NOT DEPLOYED" in render(x) for x in undeployed))
    # No `else` that passes: an empty undeployed set is the expected steady state (every released
    # component deployed), and the two cases above are then vacuous rather than wrong.

    # --- the ownsNoDatabase assertion itself ------------------------------------------------
    case("an explicit ownsNoDatabase: true is honoured",
         owns_no_database({"ownsNoDatabase": "true", "primaryDatastore": "Redis"}) is True)
    case("a service with a real database is not 'owns no database'",
         owns_no_database({"ownsNoDatabase": "false", "primaryDatastore": "PostgreSQL"}) is False)
    # The legacy fallback: no assertion at all, infer from the datastore.
    case("with no assertion, a stateless datastore infers owns-no-database",
         owns_no_database({"primaryDatastore": "none"}) is True)
    case("with no assertion, a real datastore infers a database",
         owns_no_database({"primaryDatastore": "PostgreSQL"}) is False)
    # Whitespace and case must not decide a DR instruction.
    case("the assertion is read case- and whitespace-insensitively",
         owns_no_database({"ownsNoDatabase": " TRUE ", "primaryDatastore": "PostgreSQL"}) is True)

    if fails:
        for f in fails:
            sys.stderr.write(f"::error::self-test: {f}\n")
        sys.stderr.write(f"self-test FAILED ({len(fails)} case(s))\n")
        return 1
    print("self-test ok: runbook deployment/DR classifier is falsifiable (20 cases)")
    return 0

def main():
    if "--self-test" in sys.argv:
        sys.exit(self_test())

    ap = argparse.ArgumentParser()
    ap.add_argument("services", nargs="*")
    ap.add_argument("--force", action="store_true", help="overwrite existing runbooks")
    args = ap.parse_args()
    RUNBOOKS.mkdir(parents=True, exist_ok=True)
    targets = args.services or all_services()
    created, skipped = 0, 0
    for short in targets:
        if not gitops_facts.module_dir(short, REPO).is_dir():
            print(f"skip: no module directory for {short!r}", file=sys.stderr)
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
