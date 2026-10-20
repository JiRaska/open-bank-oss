# 05 — Operations

## Fee-assessment warnings

Warnings for unavailable revision terms, failed pin-store operations, failed catalog lookup, and
dropped fee components contain fixed text. They do not include account, product, cycle, revision,
exception details, or component counts. Use the bounded `billing.assessment.skipped` counter's
`reason` label to distinguish skipped assessments and `billing.assessment.dropped_components` to
detect dropped components. These aggregate counters do not identify an individual assessment;
investigate affected records through authorized business data access, not operational logs.

## Ledger accounting-class rollout (ADR-0341 D3)

Billing can post by accounting class (the ledger resolves the GL account) or with explicit GL accounts.
`openbank.billing.ledger.post-by-class` (env `OPENBANK_BILLING_LEDGER_POST_BY_CLASS`) chooses, and defaults to `false`, which keeps the explicit payload every ledger
understands.

Rollout order, always:

1. Deploy the ledger that carries migration `V32__accounting_class_gl_map.sql`, then confirm it serves
   `GET /api/v1/accounting-classes` (200).
2. Only then set `OPENBANK_BILLING_LEDGER_POST_BY_CLASS=true` for Billing and restart it. The flag is read at startup.

Deploying Billing before the ledger is safe while the flag is `false`.

With `false` fee legs carry the accounts billing always posted (2100 / 4003). With `true` they name a class
and role and the ledger map decides; fees in a currency without a mapped fee-income account are then
refused by the ledger (422, tracked in #13226), exactly as they were refused before.

**Rollback.** With the flag `true` the legs carry no account, so an older ledger (below `V32`) refuses
every line. There is no rollback by configuration alone: set `OPENBANK_BILLING_LEDGER_POST_BY_CLASS=false` and redeploy Billing FIRST,
and only then roll the ledger back. Rolling the ledger back first fails every posting until Billing is
redeployed.
