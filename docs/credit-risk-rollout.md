# Credit-risk integrity rollout

The implementation distinguishes a recorded risk assessment, a queued accounting movement and
an acknowledged ledger posting. Only complete, current, non-demonstration assessments with no
pending allowance command qualify for portfolio ratios in the admin console.

## Production prerequisites

Supply and independently approve a `RiskParameterSource` implementation with documented model
version, PD horizon, LGD and EAD methodology, effective-interest discounting, forward-looking
scenarios, SICR/default/cure rules and validation evidence. The bundled flat model is disabled
by default (`lending.risk.allow-demonstration-model=false`). Enabling it is for demonstrations;
its records remain visibly disqualified from portfolio KPIs. A version label alone does not
establish model approval. Approve jurisdiction/product policies separately; starter DSTI/DTI
thresholds are policy examples, not a declaration of applicable regulatory limits.

Provide actual bureau evidence and verified monthly income, existing monthly debt service and
total outstanding debt in the application currency. Unknown debt is not zero. Missing required
evidence refers the application for review. Historical decision ratios are never backfilled from
current schedules or policies. Human overrides remain separately attributable.

## Deployment

1. Use a maintenance deployment, not a rolling/canary overlap. Stop all old lending writers and
   outbox dispatchers before starting any new writer. Apply V17, deploy every replica, then resume
   dispatch with only the new publisher running. Old publishers would incorrectly mark internal
   allowance commands sent without posting to the ledger. Deploy backend before UI; retain evidence.
2. Ensure the durable outbox and real ledger adapter are enabled and monitored. Validate failed
   command recovery and idempotency on the target ledger before enabling a production model.
3. Run a current-date provisioning cycle with the approved model. The scan drains the whole
   nonterminal book, including defaulted exposures. Repeating the same date does not revise it.
4. Reconcile queued movements with ledger acknowledgements, by loan and currency. Confirm zero
   unassessed, stale, demonstration and pending counts before using the displayed ratios.
5. Deploy the UI and verify both error and incomplete-data states. The latest-1000 detail charts
   remain diagnostic and must not be used as a full-book accounting extract.

Allowance commands persist their reference, signed amount and accounting date atomically with
the local state. Their dispatcher retries identical requests; `loan.provisioned` evidence follows
the ledger acknowledgement. Terminal transitions enqueue a release of the last assessed allowance.
Outbox delivery remains at least once: downstream event consumers must remain idempotent.
The pending count includes terminal loans whose release has not completed, even in a currency
with no remaining exposure. A pending count is not an error count; it also includes legitimate delivery in progress. Failed
commands require operational recovery and must not be deleted or marked sent manually.

## Rollback

Pause provisioning before rolling back. Keep the added nullable evidence columns and widened
period column. Daily keys and internal allowance commands are not understood by the prior
writer/dispatcher: drain and reconcile all commands while the new dispatcher is still running,
or retain that dispatcher until they complete. Do not roll an old writer back onto a book with
unreconciled new commands. Retain historical monthly and daily rows; do not truncate keys, clear
ratios, fabricate prior evidence or replay movements with a different reference. Rollback does
not reverse already posted ledger entries; any correction needs an explicit reconciled adjustment.
