# UX behaviour spec — Pension savings in the customer app

> Screen and flow contract for the customer app (`openbank-app`, a separate repository —
> ADR-0064) for the pension lifecycle of ADR-0334. It describes **what the app shows and which
> customer-edge route backs it**, not pixels. Domain truth lives in ADR-0334 and the
> `openbank-pension-service` / `openbank-pension-fund-service` contracts; the app talks only to
> `openbank-customer-edge` under `/customer/v1/pension` (API 1.80.0). When this doc and the ADR
> disagree, the ADR wins.

## Ground rules

- **A pension is personal.** Every pension route uses the signed-in person's own party; the
  `X-Acting-For` business context is ignored. Hide the pension entry point while the app is
  acting for a company.
- **Values are illustrative until reviewed.** Catalog offerings carry `reviewStatus`; while it is
  `ILLUSTRATIVE_REQUIRES_LEGAL_AND_COMMERCIAL_REVIEW` every product, fee and projection screen
  shows an "illustrative, not an offer" banner.
- **Money is a decimal string.** Render with the locale's grouping; never parse to a float for
  arithmetic.
- **Backend-pending routes** (marked `x-backend-pending` in the edge spec) answer 404 until their
  backend slice ships. The app treats a 404 on those routes as "not available yet" and hides the
  feature, never as an error.
- Every POST sends an `Idempotency-Key` per user intent (one tap), reused on retry.

## Flows

### 1. Discover and simulate

| Screen | Route | Notes |
|---|---|---|
| Product list (DPS / DIP) | `GET /pension/products` | Group by `productLine`; show risk class (1–7), annual management fee, SFDR article, minimum monthly contribution. |
| Product detail | same data | List `requiredDocuments` the customer will be asked to read. |
| Simulator | `POST /pension/simulations` *(pending)* | Inputs: strategy, monthly and employer contribution, horizon. Output as a range, with the illustrative banner. |

### 2. Onboard — new contract

1. **Eligibility and identity** — reuse the KYC state (`GET /customer/v1/kyc`); stop if not verified.
2. **Suitability and ESG questionnaire** — not yet backed by an edge route (gap; ADR-0334 §4 step 2).
3. **Strategy choice** — from the selected offering; recommend the questionnaire's outcome.
4. **Contribution and beneficiaries** — amount, frequency, optional employer amount; beneficiaries
   whose shares must total 100 % (the edge refuses otherwise with a message naming the rule).
5. **Review and documents** — key-information document and contract terms (document-service).
6. **Create** — `POST /pension/contracts` → contract in `DRAFT`. A 400 `PENSION_RULE_REFUSED`
   means the jurisdiction pack refused it (age, residency, provider type): show a calm explanation.
7. **Sign and submit** — SCA, then `POST /pension/contracts/{id}/submit` → `PENDING_ACTIVATION`.
   The cooling-off period and activation are server-side; the app shows "waiting for activation".

### 3. Onboard — transfer-in

Same as flow 2, then `POST /pension/contracts/{id}/transfers-in` *(pending, S2)* with the ceding
provider's name and contract number. Show the transfer as a tracked step on the contract overview.

### 4. Contract overview

`GET /pension/contracts` *(pending)* for the list; `GET /pension/contracts/{id}` for one contract:

- status badge (`DRAFT`, `PENDING_ACTIVATION`, `ACTIVE`, `SUSPENDED` = "contributions paused",
  `TERMINATING`, `PAID_OUT`, `TRANSFERRED_OUT`, `CLOSED`);
- current value: `valuation.totals` per currency. If `valuation` is null show "value unavailable";
  if `valuation.complete` is false show the value with "some units are awaiting a price";
- holdings per fund (units, NAV per unit, NAV date) and `pendingOrders` as "being invested";
- transactions: `GET /pension/contracts/{id}/transactions`.

### 5. Manage

| Action | Route | Rule shown to the customer |
|---|---|---|
| Change strategy | `PUT /pension/contracts/{id}/strategy` | Units switch at the next NAV; effective date never in the past. |
| Change contribution | `PUT /pension/contracts/{id}/contribution` *(pending, S3)* | |
| Pause / resume contributions | `POST …/pause`, `POST …/resume` | Pausing keeps the contract and its units. |
| Beneficiaries | `PUT /pension/contracts/{id}/beneficiaries` *(pending, S5)* | Shares total 100 %. |
| Tax summary | `GET /pension/contracts/{id}/tax-summary?year=` *(pending, S3)* | Past or current year only. |

A 409 `INVALID_CONTRACT_STATE` means the contract's state does not allow the action; disable the
action for that state rather than letting the customer hit it.

### 6. Early termination

1. **Preview** — `POST /pension/contracts/{id}/early-termination/preview`. Show current value,
   fee, every clawback line, estimated net payout and the pack's `notes`. The value is priced by
   the bank from the unit register; the customer never enters it.
   - `incentiveHistoryIncluded: false` → add "state contributions you may have to return are not
     yet included" (until backend slice S3 wires the incentive register).
   - 409 `VALUATION_INCOMPLETE` → "some units are awaiting a price, try again after the next
     valuation day"; 503 `VALUATION_UNAVAILABLE` → "try again later".
2. **Confirm with SCA** → `POST …/early-termination/notice`; the contract becomes `TERMINATING`.

### 7. Regular payout

Two steps, both *(pending, S5)*: `POST /pension/contracts/{id}/payouts` returns a binding quote for
the chosen form (`LUMP_SUM`, `ANNUITY`, `PHASED_WITHDRAWAL`, `FIXED_PERIOD_PENSION`, optional
`amount` and `months`); after the customer completes SCA,
`POST /pension/contracts/{id}/payouts/{payoutId}/confirm` with the `scaChallengeId` and the payout
IBAN. A 400 `PENSION_RULE_REFUSED` means the pack's payout conditions (age, duration) are not met.

### 8. Death

No customer flow: a death claim is reported to and handled by the back office (admin console,
*Payouts & death claims*). Beneficiaries see only what flow 5 lets the participant set.

## Gaps the app must not paper over

- Suitability/ESG questionnaire and key-information document delivery have no edge route yet.
- Contract list, simulation, contribution change, beneficiaries, tax summary, transfer-in and
  payout depend on backend slices S2/S3/S5 of #12350.
- SCA binding of the submit and early-termination notice intents is not enforced by the edge
  (backend slice S5 replaces the notice with a quote/sign flow that carries an SCA challenge);
  the app must still run the SCA step before calling them.
