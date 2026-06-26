# Runbook 0003 — PostgreSQL 16.14 → 18.x major upgrade (CloudNativePG)

Status: Draft (needs a maintenance window — money-path downtime)
Owner: Platform + DB
Related: ADR-0027 (cloud substrate), ADR-0079 (infra lifecycle), ADR-0030 (money-path)

## Why

Every OpenBank database runs **PostgreSQL 16.14** on **CloudNativePG (CNPG)** operator
**1.29.1** (chart `cloudnative-pg` 0.28.2, installed by OpenTofu —
`openbank-infra/aws/envs/sandbox-platform/main.tf`). 16 is still in support (EoL Nov 2028,
~880 days), so this is a **proactive** major, not an emergency — but 18 is the current major
and the FinOps lifecycle gate (ADR-0054) wants us off the trailing edge before it bites.

There are **~20 single-instance Clusters** (`postgresql.cnpg.io/v1`), one per service DB, in
`openbank-infra/gitops/components/<svc>/postgres.yaml`. **10 back money-path services**
(transaction, sepa-payment, sepa-instant, domestic-payment, clearing, ledger, swift, fx,
account, balance) → ADR-0030 applies: 2 approvals + a tested rollback before any of them move.

## The hard constraint

A major version is an **on-disk format change** — you cannot just bump the image tag and
restart (the new binary won't open a v16 data directory). CNPG gives two supported paths:

| Path | Downtime | Risk | When |
|------|----------|------|------|
| **A. Declarative offline in-place** (`pg_upgrade`, CNPG ≥1.26) | minutes per DB | low–med | non-money-path + a window |
| **B. Logical-replication blue/green** (new Cluster, switchover) | seconds (switchover) | med (2× storage, ordering) | money-path / zero-downtime |

CNPG 1.29 supports **Path A** declaratively: change `.spec.imageName` to the v18 image and
the operator fences the Cluster, runs `pg_upgrade --link`, and restarts on 18 — but the
**Cluster is offline for the duration** and it is **not auto-reversible** (the v16 dir is
upgraded in place; rollback = restore from backup). Use Path A for the ~10 non-money DBs.
Use **Path B** for the money-path DBs where a multi-minute outage is unacceptable.

## Pre-checks (once, before any DB)

- [ ] **Backups verified.** Every Cluster has a WAL archive / scheduled backup to S3 (ADR-0027)
      and a **restore has been test-rehearsed** for at least one DB. No upgrade without a proven restore.
- [ ] **Operator first.** Confirm CNPG operator ≥1.26 (we run 1.29.1) so declarative major upgrade
      is available; bump the operator chart on its own PR first if needed.
- [ ] **Extensions/compat.** `SELECT * FROM pg_extension;` per DB — confirm every extension has an
      18 build in the CNPG image (`ghcr.io/cloudnative-pg/postgresql:18.x`). Flag any custom ones.
- [ ] **18 image pinned + Kyverno-signed.** Add `ghcr.io/cloudnative-pg/postgresql:18.x` to the
      allowed image set; CNPG images are upstream-signed.
- [ ] **App compat.** Quarkus/Hibernate + the reactive PG driver against 18 — run the per-service
      `*ApiIT` against an 18 Testcontainer in CI before touching the cluster.
- [ ] **Staging dry-run.** Do the whole thing on one non-money DB (e.g. `audit-db`) end-to-end first.

## Path A — declarative offline in-place (non-money-path DBs)

Per DB, one at a time (e.g. audit, dispute, interest, notifications, consent, …):

1. **Backup now**: trigger an on-demand `Backup` and confirm it completes.
2. **Scale the consumer to 0** (graceful): drop write traffic so the offline window is clean.
3. **Bump the image** in `gitops/components/<svc>/postgres.yaml`:
   ```yaml
   spec:
     imageName: ghcr.io/cloudnative-pg/postgresql:18.x   # was :16.14
   ```
   PR (2 approvals if money-path — but these are non-money) → merge → ArgoCD sync.
4. **Operator runs `pg_upgrade`**; watch `kubectl cnpg status <cluster> -n <ns>` until `Cluster in healthy state` on 18.
5. **Smoke test**: `SELECT version();`, app readiness, a representative query.
6. **Scale the consumer back up.** Done.

> **No post-upgrade `ANALYZE` storm (16→18).** PostgreSQL 18's `pg_upgrade` **preserves optimizer
> statistics** across the major upgrade, so the cluster comes up with usable plans immediately —
> the historical "re-`ANALYZE` everything before letting traffic in" step is **not** required here.
> **Caveat:** only single-column/per-table statistics are transferred — **extended statistics
> (`CREATE STATISTICS`) are not preserved by `pg_upgrade`**. If any DB uses them, run
> `vacuumdb --all --analyze-in-stages --missing-stats-only` post-upgrade to regenerate just the
> missing ones (cheap; skips what was transferred). Adopt this as the standing expectation for the
> next major. Decision context: ADR-0106.

Rollback (Path A): the in-place upgrade is one-way → **restore the pre-upgrade backup** into a
fresh Cluster and repoint. This is why step 1 is non-negotiable.

## Path B — logical-replication blue/green (money-path DBs)

Zero-downtime; ~2× storage during cutover. Per money-path DB:

1. **Backup** (as always).
2. **New 18 Cluster** (`<svc>-db-v18`) via `bootstrap.initdb.import` (logical) from the live 16
   Cluster — copies schema + data, then stays in sync via a subscription.
3. **Let it catch up**; verify row counts + `pg_stat_subscription` lag ≈ 0.
4. **Quiesce writes** briefly (rate-limit / four-eyes pause), confirm lag 0.
5. **Switchover**: repoint the service `QUARKUS_DATASOURCE_*` (and the `-rw` Service) to
   `<svc>-db-v18`; the outage is just the rolling pod restart (seconds).
6. **Soak** on 18; keep the old 16 Cluster as instant rollback for 24–48h, then prune.

Rollback (Path B): repoint back to the still-running 16 Cluster — seconds, no data loss
(the 16 Cluster was never written to after the quiesce).

## Order of execution

1. Operator ≥1.26 (separate PR).
2. Path A pilot: `audit-db` (non-money) end-to-end.
3. Path A: the remaining ~9 non-money DBs, batched.
4. Path B: the 10 money-path DBs, one per change window, each its own ADR-0030 PR (2 approvals
   + this runbook linked as the tested rollback).

## Follow-ups

- Automate the per-DB step with a small Workflow (one agent per DB) once the pilot proves the path.
- Add an 18 Testcontainer matrix to the money-path services' CI before their cutover.
- Re-pin the FinOps lifecycle gate baseline to 18 once the fleet is migrated.
- Adopt PG-18 platform features deliberately, not by default — UUIDv7 as the identifier
  convention and the low-cost wins, with `io_uring` AIO deferred pending an ADR-0081 seccomp
  decision (ADR-0106).

## Restore Drill Result

| Field | Value |
|-------|-------|
| Date | 2026-06-26 |
| Operator | CNPG 1.29.1 |
| imageName | ghcr.io/cloudnative-pg/postgresql:18.1 |
| storageClass | gp3 |
| targetTime | 2026-06-20T03:00:00Z |
| Source | s3://openbank-sandbox-db-backups/ledger-db |
| RTO | N/A — restore did not complete |
| Row count | N/A |
| Result | **FAIL** |

### Diagnostika

Restore cluster `ledger-db-restore-drill` (namespace `ledger`) se nepodařilo spustit.
Všechny pody `ledger-db-restore-drill-1-full-recovery-*` crashovaly opakovaně
s chybou:

```
Barman cloud backup list exception: Unable to locate credentials
barman-cloud-backup-list ... s3://openbank-sandbox-db-backups/ledger-db ledger-db-s3
exit status 4
```

**Root cause:** Drill Cluster dostane nový ServiceAccount
(`ledger-db-restore-drill`), který **není asociován s EKS Pod Identity** pro
přístup k S3. Existující `ledger-db` SA má IAM roli přidělenou přes
`aws/.../db-backups.tf`; nový SA vznikl bez ní — barman `inheritFromIAMRole`
tedy nenašel credentials.

### Fix (aplikován 2026-06-26)

`db-backups.tf` přidává permanentní Pod Identity asociaci pro SA `ledger-db-drill`
v namespace `ledger`. Restore drill se musí spouštět jako CNPG Cluster
pojmenovaný **`ledger-db-drill`** (ne `ledger-db-restore-drill`) — CNPG vytvoří
SA se stejným jménem jako cluster, a tato SA má IAM roli přidělenu.

### Postup pro příští drill

```bash
# 1. Ujisti se, že tofu apply proběhl po přidání ledger-drill do db-backups.tf
# 2. Aplikuj restore manifest:
kubectl apply -f - <<'EOF'
apiVersion: postgresql.cnpg.io/v1
kind: Cluster
metadata:
  name: ledger-db-drill        # musí přesně odpovídat SA s Pod Identity
  namespace: ledger
spec:
  instances: 1
  imageName: ghcr.io/cloudnative-pg/postgresql:18.1
  storage:
    size: 10Gi
    storageClass: gp3
  bootstrap:
    recovery:
      source: ledger-backup
      recoveryTarget:
        targetTime: "2026-06-20T03:00:00Z"
  externalClusters:
    - name: ledger-backup
      barmanObjectStore:
        destinationPath: s3://openbank-sandbox-db-backups/ledger-db
        s3Credentials:
          inheritFromIAMRole: true
        wal:
          compression: gzip
EOF

# 3. Čekej na Healthy state (obvykle 3-5 min):
kubectl get cluster ledger-db-drill -n ledger -w

# 4. Ověř data:
kubectl exec -n ledger ledger-db-drill-1 -- psql -U postgres -c "SELECT count(*) FROM journal_entry;"

# 5. Zaznamenej RTO a row count výše a aktualizuj tabulku.

# 6. Smaž drill cluster:
kubectl delete cluster ledger-db-drill -n ledger
```

> **Precheck z runbooku** „Backups verified — restore has been test-rehearsed"
> musí být splněn **před** zahájením PG upgrade (Path A ani B). Fix v TF
> je připraven; drill zopakovat po `platform-tofu` apply (viz issue #2207).
