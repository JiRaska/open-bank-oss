# Data

This page currently documents only the outbox lifecycle; the rest of the data model is not yet written up.

## Outbox retention (SENT rows)

`billing_outbox` is a delivery buffer. PR #12311 merged a separate, database-unique `(account_id, calendar_year)` issuance key for annual summaries; the SENT outbox row is no longer the source-code rerun guard. The shared libs-runtime `OutboxSentRetentionJob` sees this repository, but `sentRetentionExempt=true` still skips **all** billing SENT rows (ADR-0329, #12187). Billing therefore does not yet receive the fleet's seven-day purge.

The merged migration backfills retained annual-summary intents and fails closed on malformed or duplicate identities; real-database tests cover purge and rerun. Before removing either the runtime exemption or its governance entry, verify that the key is deployed and reconcile it against an independently proven complete historical event archive, including any events whose outbox rows were previously removed. Comparing supplied exports alone cannot prove that the historical archive is complete. Until these checks and the reviewed rollout are complete, do not delete billing SENT rows or enable a billing-specific purge.

The fleet job runs nightly and deletes only old SENT rows for non-exempt repositories. PENDING, FAILED, DISPATCHING, and DEAD rows are never purged by it. Its `openbank_outbox_purged_total` metric should have no billing deletions while this exemption is active.
