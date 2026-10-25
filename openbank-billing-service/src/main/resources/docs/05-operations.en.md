# 05 — Operations

## Fee-assessment warnings

Warnings for unavailable revision terms, failed pin-store operations, failed catalog lookup, and
dropped fee components contain fixed text. They do not include account, product, cycle, revision,
exception details, or component counts. The bounded `openbank.billing.assessment.skipped` counter's
`reason` label identifies skip reasons recorded by that counter; it does not represent every
skipped assessment. `openbank.billing.assessment.dropped_components` counts assessments with one or more
dropped components; it does not count the components themselves. These aggregate counters do not
identify an individual assessment;
investigate affected records through authorized business data access, not operational logs.

## Which price components are billed (#13230)

A revision whose price components carry a catalog `role` is billed by role. Only `FEE` is charged; a `LIMIT`,
`THRESHOLD` or `RATE` never is. If the revision also embeds a v1 document, a `FEE` is billed only when its
code is one the v1 document derives, and it keeps that v1 fee's id. The idempotency key
(cycle, account, fee id, currency) therefore stays the same when an account is re-pinned mid-cycle, and
nothing is charged twice. A `FEE` with any other code is dropped and counted as
`openbank.billing.fee.component.dropped{reason=NOT_IN_LEGACY_DOCUMENT}`. A revision with no roles at all
is billed by the legacy match against its embedded v1 fees, exactly as before.

A dropped `NOT_IN_LEGACY_DOCUMENT` fee is never charged, so it is lost revenue on every assessment
under that revision. The `BillingFeeComponentNotBilledNotInLegacyDocument` alert (warning) fires on
it. The remedy is a new catalog revision: either add the fee to the v1 product so its code is derived,
or publish a revision without the embedded v1 document. Then re-pin the affected accounts. The steps
are in `docs/runbooks/0031-billing-fee-not-in-legacy-document.md`.

## Billing metric prefix migration

Seven billing counters now use the `openbank.billing.*` Micrometer prefix. The suffixes are
`assessment.no_fee_identity`, `assessment.dropped_components`, `assessment.skipped{reason}`,
`revision_pin.fallback_to_current`, `accounting_class.derived{source}`,
`fee.component.dropped{reason}`, and `fee.post.legacy_payload`. Prometheus exports these dot
names with underscores (and a counter suffix); update any external dashboards, recording rules,
and alerts to the new series before deploying this billing image. No reader of the seven old
series is tracked in this repository. Do not interpret a series-name change or counter reset as
proof that the underlying billing condition stopped occurring. The dropped-components assessment
counter increments once per affected assessment, not once per individual component.

Rollback of the billing image restores the old series names. Keep both selectors in external
queries during the cutover; remove the old selector only after verifying the new series on a
running billing instance. This metric change does not enable posting or alter fee decisions.

## Ledger accounting-class rollout (ADR-0341 D3)

Billing can post by accounting class (the ledger resolves the GL account) or with explicit GL accounts.
`openbank.billing.ledger.post-by-class` (env `OPENBANK_BILLING_LEDGER_POST_BY_CLASS`) chooses, and defaults to `false`, which keeps the explicit payload every ledger
understands.

Rollout order, always:

1. Deploy the ledger that carries migration `V32__accounting_class_gl_map.sql`, then confirm it serves
   `GET /api/v1/accounting-classes` (200).
2. Only then set `OPENBANK_BILLING_LEDGER_POST_BY_CLASS=true` for Billing and restart it. The flag is read at startup.

Deploying Billing before the ledger is safe while the flag is `false`.

With `false` a **CZK** fee's legs carry the accounts billing always posted (2100 / 4003). A non-CZK fee
always names a class and role, whatever the flag: the two literal accounts are CZK and the ledger refuses
them for any other currency (#13226). With `true` every fee names a class and role and the ledger map
decides. Fee income is booked in the fee's own currency on 4003 (CZK), 4004 (EUR), 4005 (USD) and
4006 (GBP), seeded by ledger `V34`, so deploy that ledger before this Billing release. A fee in any other
currency is not written to the outbox: it is counted as
`openbank_billing_assessment_skipped_total{reason="FEE_INCOME_CURRENCY_UNMAPPED"}`. To measure and replay
the non-CZK backlog that the old behaviour stranded, follow `docs/runbooks/0030-billing-non-czk-fee-backlog.md`.

**Rollback.** With the flag `true` the legs carry no account, so an older ledger (below `V32`) refuses
every line. There is no rollback by configuration alone: set `OPENBANK_BILLING_LEDGER_POST_BY_CLASS=false` and redeploy Billing FIRST,
and only then roll the ledger back. Rolling the ledger back first fails every posting until Billing is
redeployed.

## Day-close participation (ADR-0342, #13167)

Billing reports each completed Prague accounting day to ledger-service as participant `billing`
(`BillingCloseParticipationScheduler`, hourly at :50; `openbank.billing.close.report-cron`, lookback
`openbank.billing.close.lookback-days`, default 3), on its own identity (named oidc-client `m2m`,
Keycloak `openbank-billing`), authorized by the ledger as `ledger.close.participate` for `billing` only.

| Day state | What is reported |
|---|---|
| the cycle is not due (not the cron's day), or the sweep is off | SKIPPED with the reason |
| the cycle was due and has no `billing_close_job_run` row, or a fee of the cycle is still PENDING | nothing yet - the ledger holds the day |
| cycle ran, every fee booked | RAN (journals = POSTED/reversed fees) |
| accounts failed to assess, sweep aborted, or a fee ended FAILED | REFUSED with a fixed category |

**Missed cycle.** The cron fires once a month in Europe/Prague. `BillingCycleScheduler.catchUp`
(every `openbank.billing.scheduler.catch-up-every`, default 15m, first 2m after boot) runs the sweep
for the due day when the cron has already fired this month and no cycle run is recorded. It is
idempotent by cycle id and never overlaps a running sweep.

**Fee refused into a closed day.** A `409 ACCOUNTING_DAY_CLOSED` marks the fee FAILED at once and
increments `openbank_billing_fee_post_closed_day_refusals_total`. Alert `BillingFeeRefusedIntoClosedDay`
fires. Recover with runbook `0033-billing-fee-refused-into-closed-day.md` (four-eyes re-bill under a new
key recorded in `billing_fee_rebill`).
