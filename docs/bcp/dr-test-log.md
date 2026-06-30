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
