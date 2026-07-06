# Automated DR restore-and-verify (design)

Status: **design + workflow skeleton** (SSDLC-audit follow-up 2026-07-06). The BCP policy
(`bcp-policy.md`) and DR test log (`dr-test-log.md`) record **table-top** exercises only —
runbooks are read, the S3 archive is confirmed reachable, but no restore is actually
executed and no integrity check runs. This document specifies the automated quarterly
restore that closes that gap, and `.github/workflows/dr-restore-verify.yml` is its
runnable-once-wired skeleton.

Almost no OSS banking reference implementation actually *proves* its backups restore. Doing
it automatically, quarterly, with a ledger-integrity assertion is a genuine differentiator.

## What it proves

A backup you have never restored is a hope, not a control. This exercise proves, without a
human in the loop:

1. **The CNPG barman S3 archive for `ledger-db` is restorable** — a fresh cluster bootstraps
   from it (`bootstrap.recovery`), not just "the bucket is reachable".
2. **The restored ledger is internally consistent** — the double-entry invariant holds after
   restore. The assertion is the existing read-only integrity surface:
   `GET /api/v1/ledger/close/trial-balance` must return `balanced: true` (debits == credits
   across the general ledger). A restore that silently truncated WAL mid-transaction would
   leave an unbalanced GL and fail here.
3. **RTO/RPO are measured, not asserted** — wall-clock from restore-start to a green
   trial-balance is the real RTO; the gap between the latest WAL and the backup target is the
   real RPO. Both get appended to `dr-test-log.md`.

## Isolation (non-negotiable)

The restore targets a **throwaway namespace** (`dr-verify-<run-id>`) with its own CNPG
`Cluster` and a single ledger-service pod. It never touches the live `ledger-db`, never
publishes to Kafka (outbox dispatch disabled), and is torn down at the end of the run
(`kubectl delete namespace`). The restore is READ side only — no writes are replayed into any
live system.

## Flow

```
1. Create namespace dr-verify-<run-id>
2. Apply a CNPG Cluster with:
     bootstrap.recovery.source = ledger-db barman S3 store (read-only creds)
     externalClusters[ledger-db].barmanObjectStore = s3://openbank-sandbox-db-backups/ledger-db
3. Wait for the restored cluster to reach "Cluster in healthy state"   ← RTO clock starts at step 2
4. Deploy ledger-service against the restored DB, OUTBOX_DISPATCH_ENABLED=false, OIDC off
5. curl /q/health/ready, then GET /api/v1/ledger/close/trial-balance?fiscalYear=<current>
6. Assert balanced == true                                            ← RTO clock stops
7. Append {date, RTO, RPO, PASS/FAIL, lessons} to docs/bcp/dr-test-log.md (PR or job summary)
8. kubectl delete namespace dr-verify-<run-id>   (always, even on failure)
```

## Why the workflow is dispatch-only / gated

`dr-restore-verify.yml` needs **cluster access** (a self-hosted runner in the deploy pool
with `kubectl` + IRSA for the read-only backup bucket) that a hosted runner does not have. It
is therefore `workflow_dispatch`-only and **skips with a notice** unless the runner is
cluster-attached. The steps that require live infra are marked `# WIRING:` in the workflow —
they are the deliberate remaining work, not hidden failures. Once wired and green, promote to
a quarterly `schedule:` (aligns with the `bcp-policy.md` quarterly cadence, next due
2026-09-30).

## Remaining wiring (tracked)

- [ ] Read-only IAM role for `s3://openbank-sandbox-db-backups/ledger-db` usable from the DR namespace SA
- [ ] CNPG `Cluster` recovery manifest template (parameterized by run-id)
- [ ] ledger-service ephemeral Deployment manifest for the DR namespace (no Kafka, no OIDC)
- [ ] Self-hosted runner label for the deploy pool with kubectl access
- [ ] Flip `dr-restore-verify.yml` to a quarterly schedule after the first green manual run
- [ ] Extend to balance-service and transaction-service (T0 set) once ledger is proven

## Note on referenced runbooks

`bcp-policy.md` cites `runbook-0002-disaster-recovery.md` and `runbook-0003-pg-pitr.md`, but
`docs/runbooks/` currently holds `0002-vault-*` and `0003-postgresql-*` under those numbers —
the DR runbooks are not yet written under those names. Writing them is part of closing
ADR-0146 (key-ceremony + DR runbooks); this automated check is the executable complement to
them.
