# Data

This page currently documents only the outbox lifecycle; the rest of the data model is not yet written up.

## Outbox retention (SENT rows)

`billing_outbox` is a delivery buffer, but its SENT annual-summary rows are currently the only durable record that an `(accountId, year)` summary was issued. The shared libs-runtime `OutboxSentRetentionJob` sees this repository, but `sentRetentionExempt=true` skips **all** billing SENT rows (ADR-0329, #12187). Billing therefore does not yet receive the fleet's seven-day purge.

PR #12311 provides the independent `(account_id, calendar_year)` issuance key, a fail-closed backfill from retained annual-summary intents, and real-database purge/rerun tests. Deploy that guard and confirm historical coverage before removing both the runtime exemption and its governance entry. Until then, do not delete billing SENT rows or enable a billing-specific purge.

The fleet job runs nightly and deletes only old SENT rows for non-exempt repositories. PENDING, FAILED, DISPATCHING, and DEAD rows are never purged by it. Its `openbank_outbox_purged_total` metric should have no billing deletions while this exemption is active.

## Pinned catalog revision (ADR-0341)

Fees are priced from the product-catalog revision the account is **pinned** to, not from whatever is current. `billing_account_pin` holds one row per account (`account_id`, `product_revision_id`), written once at the account's first assessment; a pin is never updated in place. `assessed_fee` gains nullable `product_revision_id` and `accounting_class`; the fee is posted with the revision's accounting class and the ledger resolves the GL account. Fees assessed before the pin existed keep the legacy payload path (`LegacyRevisionBridge`, counted) until they drain. Migration V9 is additive; rollback is `DROP TABLE billing_account_pin` plus dropping the two columns.

## Product-IR shadow (ADR-0352 phase 3)

Migration V11 adds two tables, neither read on the posting path. `billing_product_shadow_input` is a local outbox: one row per non-skipped assessment, committed in the assessment's own transaction, holding the frozen inputs (balance, monthly turnover, pocket balance, segment), the pinned `product_revision_id` and the legacy decision per fee. The comparator claims due rows with `SKIP LOCKED`, marks them `DONE` with an outcome (`AGREED`, `DIVERGED`, `RULE_UNMAPPED`, `INPUT_UNKNOWN`, `NO_IR`, `VERIFICATION_DISABLED`, `VERIFICATION_FAILED`, or a retry reason ending `_EXHAUSTED`) and erases the frozen account facts. `billing_product_revision_ir` is billing's immutable local copy of a pinned revision's product IR and publish-path signature, written once per revision and protected by a trigger; a revision without IR is recorded too. Rollback drops both tables and the trigger function; the previous release neither writes nor reads them.

V12 preserves the assessment instant and zone, actual account opening instant and invocation cadence in the shadow row. Older rows keep these facts unknown and finish as `INPUT_UNKNOWN`; they are not replayed at worker time. The monthly scheduler supplies its actual cadence; a manual call supplies none. Historical event/balance-dependent predicates remain unknown without complete source facts. If the actual tag is absent, a legacy cycle supports a counterfactual shadow dispatch only for one unconditional ON_OPEN registration of the same cadence, with no possible opening rejection or duplicate producer. This is not evidence that an IR calendar event actually fired. Conditional, later-lifecycle or ambiguous registrations remain unknown. Currency mismatch and ambiguous fee-code mappings are also unknown, never numerical agreement. Rollback: stop the shadow worker, deploy the previous writer, then drop only the five V12 columns; retain V11 queue/decision records.
