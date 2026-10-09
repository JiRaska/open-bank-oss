# UX behaviour spec — Pension savings in the customer app

> Screen and flow contract for the customer app (`openbank-app`, a separate repository —
> ADR-0064) for the pension lifecycle of ADR-0334. It describes **what the app shows and which
> customer-edge route backs it**, not pixels. Domain truth lives in ADR-0334 and the
> `openbank-pension-service` (API 1.1.0) / `openbank-pension-fund-service` contracts; the app talks
> only to `openbank-customer-edge` under `/customer/v1/pension` (API 1.80.0). When this doc and the
> ADR disagree, the ADR wins.

## Ground rules

- **A pension is personal.** Every pension route uses the signed-in person's own party; the
  `X-Acting-For` business context is ignored. Hide the pension entry point while the app is
  acting for a company.
- **Values are illustrative until reviewed.** Catalog offerings carry `reviewStatus`; while it is
  `ILLUSTRATIVE_REQUIRES_LEGAL_AND_COMMERCIAL_REVIEW` every product, fee and projection screen
  shows an "illustrative, not an offer" banner.
- **Money is a decimal string.** Render with the locale's grouping; never parse to a float for
  arithmetic.
- **Every POST that changes something requires an `Idempotency-Key`** per user intent (one tap),
  reused on retry; without it the edge answers 400. The simulation is the only POST without one.
- **Every state change needs SCA**, in one of two shapes:
  - *Edge-bound* (pause/resume, strategy change, contribution mandate, application withdrawal):
    send the call once without `X-SCA-Challenge-Id`; the edge answers 403 `SCA_REQUIRED` with
    `scaLinking` (purpose `APPROVAL`, `approvalRequestId`, `payloadSha256`) computed from the exact
    request. Raise the sca-service challenge with that linking, let the customer approve it, then
    repeat the identical request with the challenge id. A changed body no longer matches (403
    `SCA_REJECTED`) and a challenge is spent once.
  - *Document-bound* (application sign, termination sign, payout confirm, payout account change):
    pension-service binds the challenge to what it issued (the key-information document, or the
    `quoteHash` plus the payout account) and consumes it itself; the edge only refuses a call
    without one.

## Flows

### 1. Discover and simulate

| Screen | Route | Notes |
|---|---|---|
| Product list (DPS / DIP) | `GET /pension/products` | Group by `productLine`; show risk class (1–7), annual management fee, SFDR article, minimum monthly contribution. |
| Product detail | same data | List `requiredDocuments` the customer will be asked to read. |
| Simulator | `POST /pension/simulations` | Inputs: monthly and employer contribution, horizon, optional strategy. One projection per offered strategy; always `illustrative: true` with the returned `disclaimer`. |

### 2. Onboard — new contract (application flow)

1. **Eligibility and identity** — reuse the KYC state (`GET /customer/v1/kyc`); stop if not
   verified. Birth date and residency come from the customer's profile, never from this flow: a
   422 `PARTY_PROFILE_INCOMPLETE` means the profile lacks one — send the customer to update it.
2. **Open the application** — `POST /pension/applications` (product line, jurisdiction, provider,
   contribution schedule). A rejected application carries `rejectionReasons`.
3. **Questionnaire** — `POST /pension/applications/{id}/questionnaire` (knowledge, experience,
   risk appetite, loss tolerance, stable finances, ESG preference).
4. **Recommendation** — `GET /pension/applications/{id}/recommendation`.
5. **Strategy** — `POST /pension/applications/{id}/strategy`; omit `strategyCode` to accept the
   recommendation. Choosing an unsuitable one needs `acknowledgeWarning: true`. The answer names
   the issued key-information document.
6. **Key-information document** — show it, then `POST /pension/applications/{id}/kid/accept`.
7. **Sign** — `POST /pension/applications/{id}/sign` with the approved challenge. The contract is
   activated server-side after the cooling-off period (`coolingOffEndsOn`); within it the customer
   may `POST /pension/applications/{id}/withdraw` (edge-bound SCA).

### 3. Onboard — transfer-in

`POST /pension/transfers-in` opens an application of kind `TRANSFER_IN` with the same fields plus
`transferIn` (ceding provider id, name, contract number), then steps 3–7 of flow 2.

### 4. Contract overview

`GET /pension/contracts` for the list; `GET /pension/contracts/{id}` for one contract:

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
| Pay contributions | `GET …/payment-reference` (one-off), `POST …/contribution-mandates` (standing order / direct debit) | First collection today or later. |
| Pause / resume contributions | `POST …/pause`, `POST …/resume` | Pausing keeps the contract and its units. |
| Tax summary | `GET /pension/contracts/{id}/tax-summary?year=` | Past or current year only. |

A 409 `INVALID_CONTRACT_STATE` means the contract's state does not allow the action; disable the
action for that state rather than letting the customer hit it.

### 6. Early termination

1. **Quote** — `POST /pension/contracts/{id}/termination/quote` returns a binding notice: the
   redemption value (priced by the bank from the unit register), surrender fee, incentive return,
   deduction recaptures, net payout, `quoteHash` and `quoteExpiresAt`. Show every line.
2. **Sign** — the customer approves a challenge bound to `quoteHash` and the payout account, then
   `POST /pension/contracts/{id}/termination/{noticeId}/sign` with `payoutIban`. An expired quote is
   a 409: quote again.

### 7. Regular payout

`POST /pension/contracts/{id}/payouts` returns a binding quote for the chosen form (`LUMP_SUM`,
`ANNUITY`, `PHASED_WITHDRAWAL`, `FIXED_PERIOD_PENSION`, optional `amount` and `months`); after
the customer approves the challenge, `POST /pension/contracts/{id}/payouts/{payoutId}/confirm`
with `payoutIban`. A 400 `PENSION_RULE_REFUSED` means the pack's payout conditions (age, duration)
are not met. `GET …/payouts/{payoutId}` shows the instalments and the account's last four
characters.

**Changing the payout account** of a running scheduled payout:
`PUT /pension/contracts/{id}/payouts/{payoutId}/account` with `payoutIban` (document-bound SCA).
The change is held: `pendingAccountLast4` shows it, and it applies only to instalments due after
the hold — tell the customer the next payment may still go to the old account.

### 8. Death

No customer flow: a death claim is reported to and handled by the back office (admin console,
*Payouts & death claims*).

## Gaps the app must not paper over

- No route changes the contribution schedule or the beneficiary designations of an existing
  contract; pension-service API 1.1.0 has neither.
