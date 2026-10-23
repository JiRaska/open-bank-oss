# openbank-treasury-service — Documentation

> **What it is:** the bank's own money-market deals (ADR-0315): placements, borrowings, ČNB deposit facility, ČNB lombard and FX spot. A deal moves `DRAFT → PENDING_APPROVAL → BOOKED → CONFIRMED → SETTLED → MATURED`, or to `CANCELLED`, with four-eyes separation between the people who create, submit, approve and confirm it.

This documentation is published by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service, ADR 0019).

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / PostgreSQL (Flyway migrations)
- **Ports:** 8160 (app), 8090 (management)
- **Auth:** dealers, approvers and senior approvers by role (`ROLE_TREASURY_DEALER`, `ROLE_TREASURY_APPROVER`, `ROLE_TREASURY_SENIOR_APPROVER`, `ROLE_ADMIN`). A non-human principal cannot take a person's step (403).

## Limits (ADR-0315 D4)

Two limit families are checked when a deal is submitted and again when it is approved, because exposure and mandates can move in between.

- **Counterparty limit.** Recorded at submit; a breach blocks booking (422 `LIMIT_BREACHED`) unless a senior approver recorded an override with a reason that still covers it (`POST /deals/{id}/override-limit`).
- **Product limit.** The bank's mandate per product, independent of the counterparty. It is **enforced**: at submit a deal outside its mandate never reaches an approver's queue, and at approval it is evaluated again against the mandate as declared now. A breach is 422 `PRODUCT_LIMIT_BREACHED`, and the response carries `breaches[]`, one entry per rule broken with the declared `limit` and the deal's `actual` figure. It is **not overridable**: the senior override covers the counterparty limit only. Dealing outside a mandate means changing the declared mandate, under review.

### Declaring product limits

Product limits are declared as code in `application.yaml` under `openbank.treasury.product-limits`, one entry per product (`MM_PLACEMENT`, `MM_BORROWING`, `CNB_DEPOSIT_FACILITY`, `CNB_LOMBARD`, `FX_SPOT`):

- `max-principal` — the largest principal per deal, by currency. The set of currencies IS the allowed-currency list: a currency with no entry may not be dealt in that product.
- `max-tenor-days` — optional cap on value-to-maturity in calendar days; not applied to `FX_SPOT`, which has no tenor.

The rules are fail-closed. A product with no entry is not permitted at all, and an unknown product name in the configuration refuses to boot, so a typo cannot leave the real product refused by omission. The shipped values are sandbox-sized, with each cap at or above the largest synthetic counterparty line so the counterparty limit stays the binding one in the sandbox.

### Rules and the booked event

The rules reported in `breaches[]` are `PRODUCT_NOT_PERMITTED`, `CURRENCY_NOT_PERMITTED`, `MAX_PRINCIPAL` and `MAX_TENOR`. The `treasury.deal.booked.v1` event carries an additive, optional `productLimit` (`decision` = `WITHIN_LIMIT`, plus the maximum principal in the deal's currency and the maximum tenor); a booked deal is always within its product limit, and `v1` consumers are unaffected.

## Contract

The API contract is `openapi.yaml` (`info.version` follows ADR-0048). Product limits are additive: 1.18.0, with a 422 on `submit` and `approve`.

## Corrections (ADR-0344 D7)

A BOOKED, CONFIRMED or SETTLED leg-described deal (IRS, FRA, BOND, TREASURY_BILL, REPO, REVERSE_REPO) is changed through a four-eyes correction, never edited in place; the legacy products are reversed and re-booked instead.

- `POST /api/v1/treasury/deals/{id}/corrections` proposes partial new terms (legs, rate, calendars, business-day convention, note) or a `fullUnwind`, with a reason and an `effectiveFrom` date. A dealer or the back office proposes. The response carries a preview: the new flows, the changed flows and the delta journals the change would post. At most one correction per deal is PENDING.
- `/approve` and `/reject` are decided by an approver who is human and is none of the deal's term authors: not the proposer, creator, submitter, a draft editor or the proposer of an earlier correction.
- The corrected deal is vetted exactly as a booking is: reference data, product mandate and the counterparty limit. A limit breach can be proposed only with `requestLimitOverride`; then a senior approver records the override (`/override-limit`) before anyone approves.
- Approval commits the new terms version and its cash-flow ledger in one transaction. If a fixing, override or payment changed the schedule meanwhile, the answer is 409 `FLOW_CHANGED`; a released payment on a changed flow gives 409 `PAYMENT_RELEASED`. The delta journals then post under `treasury:<dealId>:correction:<correctionId>:…` keys, and the correction is `POSTED` once all of them are in the ledger. Anything the approval could not post is finished by the hourly `treasury-correction-posting` job. If the ledger has closed the journal's day, the journal goes into today's open day.
- Every POST requires an `Idempotency-Key`. The approval is published as `treasury.deal.corrected.v1`.

## Build-time wiring

- **OPA policy decision point.** `openbank.authz.opa-pdp-producer.enabled: true` makes libs-runtime's `OpaPolicyDecisionPointProducer` supply the OPA sidecar client, configured by `opa.url`, `opa.path` and `opa.timeout-ms` (default 500 ms). It is read at build time.
