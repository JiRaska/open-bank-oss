# DR Test Log

This is an append-only record of disaster recovery tests conducted for the OpenBank platform.
Each entry records the date, scenario, outcome, and any corrective actions.

Format:
```
## YYYY-MM-DD — [Scenario title]
- **Type**: table-top | live-failover
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
