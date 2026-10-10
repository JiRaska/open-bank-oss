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


## Custodian portfolio statements

Upload a complete custodian `semt.002` XML statement with `POST /api/v1/treasury/portfolio/statements` and a nonblank `Idempotency-Key` of at most 128 characters. Upload requires the treasury approver role and the `treasury.portfolio.upload` authorization action; it records holdings and does not post accounting entries.

Configure `openbank.treasury.portfolio.entity`, `safekeeping-accounts`, and `cfi-classes` for the owning legal entity and permitted custody accounts. Instrument classes come from declared CFI prefixes; the longest matching prefix wins. Missing or unmapped CFI, an unconfigured custody account, or valuations in multiple currencies reject the complete upload. The XML reader also rejects incomplete pages, non-complete updates, duplicate or missing ISINs, and DOCTYPE declarations. Its vendored XSD is a working subset, not a certification of the full ISO message standard.

`GET /api/v1/treasury/portfolio/period-end?date=YYYY-MM-DD` reads the current statement at exactly that date, with entity, statement/version identifiers, currency, and positions. Quantity and valuation are decimal strings. An absent statement returns 409 `PORTFOLIO_SNAPSHOT_MISSING`; it does not become an empty portfolio. A recorded empty statement returns an empty positions array and the implementation's CZK currency default. The version-history endpoint `/statements?date=YYYY-MM-DD` can return an empty list when there are no versions.

Reusing a stored upload's key with the same bytes returns that stored version; different bytes under that key return 409. Uploading the current statement's bytes under another key returns its current version. Different bytes for the same entity/date create a new version and supersede the previous one, preserving its identifiers, SHA-256, uploader and supersession trail. Read the history to distinguish a correction from the original snapshot.

A deployment with no configured portfolio entity rejects uploads and has no period-end snapshot. Keep pension-company and bank treasury books separate; configure the entity and custody account allowlist before using this source for reporting. A stored portfolio alone does not prove reporting assembly, reconciliation or statutory submission.

Every accepted key, including a new key for identical bytes, is durably bound to the returned version. After later corrections it still replays that accepted version; different bytes under that key return 409. Migration V16 preserves existing statements' original keys and adds the binding table.
