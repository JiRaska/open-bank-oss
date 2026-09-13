# DR Test Log

This is an append-only record of disaster recovery tests conducted for the OpenBank platform.
Each entry records the date, scenario, outcome, and any corrective actions.

Format:
```
## YYYY-MM-DD — [Scenario title]
- **Type**: table-top | live-restore | live-failover
- **Scope**: T0 / T1 / T2 / T3 / all
- **Participants**: roles present
- **Scenario**: what was simulated
- **Result**: PASS / FAIL / PARTIAL
- **RTO achieved**: measured (or N/A for table-top)
- **RPO achieved**: measured (or N/A for table-top)
- **Lessons learned**: any findings
- **Actions**: follow-up tasks opened
```

---

## 2026-07-26 — Live restore drill: ledger-db PITR from S3 (PASS)

- **Type**: live drill (not a table-top — a real cluster was restored from the real backup)
- **Scope**: ledger-service (`ledger/ledger-db`), the first money-path database to carry a
  restore drill.
- **Participants**: BCP Owner (CTO / maintainer)
- **Scenario**: Restored `ledger-db` into a throwaway `ledger-db-drill` CNPG cluster from the
  live S3 barman object store, targeting `2026-07-26T02:05:08Z` (the `stoppedAt` of backup
  `ledger-db-daily-20260726020500`), then compared the recovered data against the live primary.
- **Result**: **PASS** — recovered `journal_entries` count 96, matching the live `ledger-db`
  primary exactly.
- **RTO achieved**: ~84s (Cluster created 06:50:04Z → primary Healthy 06:51:28Z). This is
  restore time only; it excludes detection and the decision to restore.
- **RPO achieved**: ~4h45m at the chosen target — the distance from the daily base backup
  (02:05:08Z) to the drill. PITR to a later point was available via the WAL archive and was
  not exercised, so the measured figure is the base-backup interval, not the floor.
- **Lessons learned**:
  - `externalClusters[].barmanObjectStore.serverName` defaults to the *externalCluster entry's*
    name, not the original cluster's. It therefore searched an empty S3 prefix and reported
    `no target backup found` with no access error — an empty list, not a failure. Always verify
    the real prefix with `aws s3 ls s3://<bucket>/<svc>-db/` **before** writing the manifest.
  - A first attempt on 2026-06-26 failed on IAM credentials; the Pod Identity association for
    `<svc>-drill` must be `tofu apply`d, not merely declared (#1759).
  - `journal_entries` is partitioned; the singular `journal_entry` named in an earlier version
    of runbook-0003 has never existed.
- **Actions**:
  - Procedure and both footguns recorded in `docs/runbooks/0003-postgresql-16-to-18-major-upgrade.md`
    (the repo's de-facto CNPG restore/PITR procedure), verified end-to-end (#2495).
  - Remaining ~19 money-path databases still have no restore drill; the procedure above is now
    proven and applies per service.

---

## 2026-06-30 — Initial table-top: sandbox database PITR restore

- **Type**: table-top
- **Scope**: T0 (payment-service, ledger-service, balance-service, transaction-service)
- **Participants**: BCP Owner (CTO / maintainer)
- **Scenario**: Simulated accidental deletion of account-service database; validated runbook-0003
  (CNPG PITR restore) steps and confirmed S3 WAL archive is reachable.
- **Result**: PASS (runbook steps verified; backup reachable; estimated restore time ~25 min)
- **RTO achieved**: N/A (table-top)
- **RPO achieved**: N/A (table-top)
- **Lessons learned**:
  - PITR restore requires `QUARKUS_FLYWAY_REPAIR_AT_START=true` if WAL ends mid-migration.
  - OpenBao break-glass keys must be retrieved from AWS Secrets Manager before restore, not after.
- **Actions**:
  - Added pre-restore OpenBao key retrieval step to runbook-0003.
  - Scheduled next table-top for 2026-09-30.

---

## 2026-07-26 — Live restore drill: ledger-db PITR from S3 into a scratch CNPG cluster

- **Type**: live-restore (a real restore was executed; this was **not** a failover, so it does
  not substitute for a `dr_drill`)
- **Scope**: `openbank-ledger-service` only (`ledger-db`, sandbox). The other ~19 CNPG
  databases — 9 of them money-path — have **not** had this drill.
- **Participants**: BCP Owner (CTO / maintainer)
- **Scenario**: Bootstrap a fresh CNPG `Cluster` (`ledger-db-drill`, ns `ledger`,
  `ghcr.io/cloudnative-pg/postgresql:18.1`, gp3) by `bootstrap.recovery` from
  `s3://openbank-sandbox-db-backups/ledger-db`, recovery target
  `targetTime: 2026-07-26T02:05:08Z` (the `stoppedAt` of base backup
  `ledger-db-daily-20260726020500`), then compare restored data against the live primary.
- **Result**: PASS on the third attempt — two prior attempts failed and both root causes were
  fixed (see below).
- **RTO achieved**: **~84 s** for the database only — Cluster created 06:50:04Z → primary
  Ready 06:51:28Z. This is the CNPG restore time, **not** an application-level RTO: no service
  was cut over to the restored cluster, so time-to-serve-traffic remains unmeasured.
- **RPO achieved**: **not measured.** PITR was taken to a chosen `targetTime`, not to the moment
  of a failure, so no data-loss window was established. Recorded as unmeasured rather than as
  the WAL-archive interval, which would be an assumption, not a measurement.
- **Data integrity check**: `SELECT count(*) FROM journal_entries` returned **96** on the
  restored cluster, matching the live `ledger-db` primary exactly. (`journal_entries` is
  partitioned `_2024`…`_2028` + `_default`; the singular `journal_entry` in an earlier version
  of runbook-0003 never existed.)
- **Lessons learned**:
  - *Attempt 1 (2026-06-26) — FAIL, credentials.* A drill Cluster gets its own ServiceAccount,
    which had no EKS Pod Identity association, so barman could not reach S3
    (`Unable to locate credentials`, exit 4). Fixed by adding a permanent association for SA
    `ledger-db-drill` in `db-backups.tf` (applied as part of #1759) — hence the drill cluster
    must be named exactly `ledger-db-drill`.
  - *Attempt 2 (2026-07-26) — FAIL, `serverName`.* `barmanObjectStore.serverName` defaults to
    the **externalCluster entry name**, not the source cluster name, so barman listed an empty
    prefix and reported `no target backup found` with no access error. Fixed by setting
    `serverName: ledger-db` explicitly. Verify the real S3 layout with `aws s3 ls` **before**
    writing the manifest.
  - A declared Terraform association is not an applied one — check `tofu state show`, not the
    `.tf` file (#1759).
- **Actions**:
  - Verified end-to-end procedure and manifest recorded in
    [runbook-0003 § Restore Drill Result](../runbooks/0003-postgresql-16-to-18-major-upgrade.md#restore-drill-result).
  - Repeat per-service for the remaining money-path databases (#4755 tracks the money-path
    chaos drill with a measured RTO).
  - `dr-restore-verify.yml` still cannot execute this in CI — no cluster-capable runner and the
    CNPG recovery manifests are not in-repo (#4757), so this drill remains a manual exercise.
