# Data

This page currently documents only the outbox lifecycle; the rest of the data model is not yet written up.

## Outbox retention (SENT rows)

`billing_outbox` is a delivery buffer, but its SENT annual-summary rows are currently the only durable record that an `(accountId, year)` summary was issued. The shared libs-runtime `OutboxSentRetentionJob` sees this repository, but `sentRetentionExempt=true` skips **all** billing SENT rows (ADR-0329, #12187). Billing therefore does not yet receive the fleet's seven-day purge.

PR #12311 provides the independent `(account_id, calendar_year)` issuance key, a fail-closed backfill from retained annual-summary intents, and real-database purge/rerun tests. Deploy that guard and confirm historical coverage before removing both the runtime exemption and its governance entry. Until then, do not delete billing SENT rows or enable a billing-specific purge.

The fleet job runs nightly and deletes only old SENT rows for non-exempt repositories. PENDING, FAILED, DISPATCHING, and DEAD rows are never purged by it. Its `openbank_outbox_purged_total` metric should have no billing deletions while this exemption is active.
