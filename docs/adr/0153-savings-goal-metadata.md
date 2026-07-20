---
date: 2026-07-03
decision-status: accepted
delivery-status: shipped
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [accounts, mobile-app, customer-edge]
summary: "Add an optional savings goal as three nullable columns (name, target minor units, target date) on account-service's existing Account aggregate, exposed via PUT/DELETE goal endpoints and proxied by customer-edge."
---

# ADR-0153 — Savings goal metadata on account-service

Shipped in PR #219 (account-service schema/endpoints + customer-edge proxy + GDPR Art. 17
erasure hook). Issue #264 was opened two days after #219 merged, from a stale reading of
this file's own Delivery-Status field — closed as already-resolved.

## Context

The openbank-app Vault screen ("Spořák") shows a savings-goal progress bar — target
amount, goal name ("Nová lednička"), and a "recent stashes" history list — as if it
were the customer's real, chosen goal. It is hardcoded (`OB_VAULT_GOAL` /
`OB_VAULT_STASHES` in `ObData.kt`): every customer sees the same fabricated 40 000 Kč
target regardless of their actual savings account. This surfaced in an FE-honesty audit
(2026-07-03) alongside several other FE-only illusions (false payment success, fabricated
forecast, decorative toggles) that have since been fixed — this is the one remaining
finding that cannot be fixed app-side because **no backend concept of "a customer's
savings goal" exists yet**. It genuinely needs new state, hence an ADR rather than a
same-day bug fix like the rest of that sweep.

The "recent stashes" half of the same screen does NOT need new backend state: a stash is
already a real own-account transfer (`POST /customer/v1/transfers`, the own-account
transfer path under the PSD2 Art. 15 exemption) booked through the existing ledger. The
transaction history for the savings account already contains every stash/withdraw
movement; the screen just needs to derive its "recent stashes" list from
`GET /customer/v1/accounts/{id}/transactions` the way the payday forecast now derives
from real transactions (openbank-app PR #151) instead of a canned list. That part is
in-scope for a follow-up app PR, not this ADR.

## Decision

We will add an **optional goal** to a savings account as three nullable fields on the
existing `Account` aggregate in **openbank-account-service** — not a new service, not a
new bounded context, not a generic "goals" concept detached from an account. A goal only
ever means "this savings account is being tracked toward this target"; there is exactly
one goal per account (nullable = no goal set), so it belongs on the account row the same
way `legalName` (V12) and the sanctions-screen fields already do.

Schema (new migration on `openbank-account-service`):
```sql
ALTER TABLE accounts ADD COLUMN goal_name VARCHAR(120);
ALTER TABLE accounts ADD COLUMN goal_target_minor_units BIGINT;
ALTER TABLE accounts ADD COLUMN goal_target_date DATE;
```
All three nullable; a goal is "set" iff `goal_target_minor_units IS NOT NULL`. Currency is
implicitly the account's own currency (no cross-currency goal).

Domain (`Account` in `openbank-account-service/.../domain/model/Account.kt`):
```kotlin
val goalName: String? = null,
val goalTargetMinorUnits: Long? = null,
val goalTargetDate: LocalDate? = null,
```

REST (new endpoint on `AccountResource`, `openbank-account-service`):
```
PUT    /api/v1/accounts/{accountId}/goal    body: {name, targetMinorUnits, targetDate?}
DELETE /api/v1/accounts/{accountId}/goal    (clears the goal — all three columns to null)
```
`GET /api/v1/accounts/{accountId}` response gains the three fields (null when unset) —
no new GET endpoint needed, the existing account read already returns the row.

Edge (new proxy on `CustomerEdgeResource`, `openbank-customer-edge`), same
`@Authorize`/IDOR pattern as every other party-scoped account mutation in that class
(ownership check against the caller's JWT `sub` before forwarding):
```
PUT    /customer/v1/accounts/{accountId}/goal
DELETE /customer/v1/accounts/{accountId}/goal
```

App (`openbank-app`): `AccountsApi`/a new `SavingsGoalApi` calls the two edge endpoints;
`VaultScreen` reads `goalName`/`goalTargetMinorUnits`/`goalTargetDate` off the live
`Account` instead of `OB_VAULT_GOAL`, with a "set a goal" empty state when unset instead
of always showing a bar. `OB_VAULT_GOAL` remains only as the fake-data-mode seed.

## Alternatives considered

- **A dedicated `openbank-savings-goals-service`** — rejected: one nullable triple of
  columns on an existing aggregate does not justify a new service, its own DB, its own
  Kafka topics, and its own OPA policy surface. Reconsider only if goals grow multiple-
  per-account, get their own lifecycle events, or need cross-account rollups — none of
  which the current UI asks for.
- **Store the goal in the app only (local device state)** — rejected: the whole point of
  this ADR is that the CURRENT hardcoded/local behavior is exactly the FE-illusion being
  removed; a goal must survive a reinstall and be visible from any of the customer's
  devices, so it must be server-side.
- **Model the goal as a `Pocket`-level field instead of `Account`-level** — rejected:
  pockets are currency sub-balances within one account (ADR-0024); a savings goal is
  denominated in the account's own currency and conceptually belongs to the account as a
  whole, not a specific currency pocket. Keeps the schema simpler.

## Consequences

**Positive**
- Closes the last FE-illusion inventory item that needs new backend capability; the
  Vault screen stops fabricating data for every customer.
- Minimal blast radius: one migration, three columns, two REST verbs, mirrors patterns
  (`legalName`, sanctions fields) already proven on the same aggregate.

**Negative**
- `Account` picks up three more optional fields — acceptable now, but if goals grow
  richer (multiple goals, milestones, auto-round-up funding rules) this ADR's model will
  need revisiting; that is an explicit non-goal today, not a design flaw.

**Neutral**
- No new Kafka topic, no new service, no new OPA action family beyond
  `account.goal.write` / `account.goal.read` under the existing `account.*` action
  namespace already governed by `rest.rego`'s `customer-self-action` rule (a customer may
  perform any `customer.*` edge action on their own JWT-bound identity) — the edge
  `@Authorize` action is `customer.accounts.goal.write` per the existing edge action
  naming convention, not a new OPA rule.

## Compliance impact

- PCI DSS: not applicable — no card data.
- DORA:    not applicable — non-critical customer-preference data, not a payment/ledger
  path; a lost goal is a UX regression, not an operational-resilience incident.
- GDPR:    goal name is customer-authored free text (Art. 5 minimisation: cap length,
  120 chars per the schema above) and is customer PII-adjacent — covered by the existing
  account-erasure flow (`legalName` already nulls on GDPR Art. 17 erasure; extend the
  same erasure path to null the three goal columns).
- PSD2:    not applicable — a goal is metadata, not an initiated payment; no SCA required
  to set/clear it (same exemption class as renaming an account nickname, if that exists).
- CNB:     not applicable.

## References

- ADR-0024 (multi-currency account, single-IBAN pockets — why the goal is account-scoped,
  not pocket-scoped)
- FE-honesty audit, 2026-07-03 (openbank-app session) — full inventory of findings,
  of which this is the one requiring new backend state
- `openbank-app` PR #151 (payday forecast derived from real transactions) — the sibling
  fix for the "recent stashes" half of the same screen, which needs no new backend state
