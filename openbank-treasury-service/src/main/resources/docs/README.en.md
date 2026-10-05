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

The API contract is `openapi.yaml` (`info.version` follows ADR-0048). Product limits were added in 1.18.0. Version 1.19.0 corrects the three validation 400 schemas: `GET /quotes` and both nostro statement uploads return the libs-runtime `ApiError`/`ProblemDetail` shape (`code`, `message`, `status`, `traceId`, `timestamp`). Treasury-owned business errors, including quote unavailability, retain `{ "error": ... }`.
