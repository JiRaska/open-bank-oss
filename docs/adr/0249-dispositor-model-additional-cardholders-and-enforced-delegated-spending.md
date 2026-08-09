---
date: 2026-08-08
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [authz, cards, payments, sca]
summary: "Disponent model: a delegate may SPEND — an additional card in their own name with its own limits, and payments from a shared account inside ceilings that are actually counted, reserved in one authoritative place."
---

# ADR-0249 — Dispositor model: additional cardholders and enforced delegated spending

Relates: ADR-0232 (delegated access), ADR-0021 (SCA settlement gate),
ADR-0034 (unified OPA), ADR-0072 (party identity), ADR-0133 (tamper-evident audit),
ADR-0155 (four-eyes), ADR-0190 (card authorisation), ADR-0227 (approval inbox).

## Context

ADR-0232 gave customers granular sharing and stopped one step short of the thing
most people mean by "disponent": someone who can **spend**. Its vocabulary has
`ACCOUNT_INITIATE_PAYMENT` and the grant carries `dailyLimit` / `monthlyLimit`,
but `DelegationGrant.withinLimits` only ever checked `perTransactionLimit`. The
cumulative ceilings were assigned to "the enforcing product service" (D3) and no
product service implemented them, so #3613 made the service refuse those fields
outright rather than carry numbers nobody counts.

The app therefore ships three read-only presets and a KDoc explaining why a
"5 000 Kč/den" chip would be a claim the bank cannot keep. That reasoning was
correct and is the reason this ADR exists rather than a UI change.

What the customer actually asks for is two different products that the market
has long separated, and that BIAN separates too:

| What people ask for | Market name | BIAN service domain |
|---|---|---|
| "give my partner a card on my account" | additional cardholder / dodatková karta | Card Transaction Authorisation, Party Lifecycle Management |
| "let my bookkeeper pay from the account, up to a limit" | account mandate / disponent | Party Authorisation, Payment Order |

Conflating them is the usual mistake. An additional card is a **card product
issued to another person**, with its own PAN, its own limits, its own block —
Revolut, Monzo and every Czech incumbent model it this way. An account mandate is
an **authorisation over an account**, exercised through payment orders, and it is
where the cumulative-limit problem actually lives.

## What is already true

Three things the codebase already has, which decide the shape of this design:

1. `Card` carries `partyId` (the cardholder) **separately from** `accountId`. A
   card whose holder is not the account owner is structurally expressible today —
   no schema change, no new aggregate.
2. `Card` carries `dailyLimitMinorUnits` and `monthlyLimitMinorUnits`, and card
   authorisation is the rail that enforces them. Card ceilings are therefore
   **already real**, unlike delegation's.
3. `CardDelegationGuard` already answers "holder OR an ACTIVE in-window grant"
   per intent. It has no production caller, which is a wiring gap, not a design
   gap.

So the card half of the disponent model is mostly *reachability* work. The
account half needs a new mechanism.

## Decision

### D1 — Additional cardholder is a card, not a permission

Issuing a disponent card creates a normal `Card` whose `partyId` is the
delegate and whose `accountId` is the grantor's account, linked to the grant that
authorised it. It has its own PAN, its own embossed name, and its own
`dailyLimitMinorUnits` / `monthlyLimitMinorUnits` — enforced by the card rail
that already enforces them for primary cards.

This is deliberately **not** modelled as `ACCOUNT_INITIATE_PAYMENT` on the
account. A card is a bounded instrument: it can be blocked, re-limited or
destroyed without touching the account, and the ceiling that binds it is the
card's own, which the platform counts today.

### D2 — Delegated card controls, enforced through the existing guard

`CARD_MANAGE_LIMITS` and `CARD_VIEW` are honoured at the edge by calling
`CardDelegationGuard`, so a grantee may freeze, unfreeze and re-limit the card
they were given — the controls a real disponent expects. The grantor keeps every
control over the card unconditionally, including revoking it, and revocation of
the grant blocks the card rather than merely hiding it: a card that still
transacts after its authorisation ended is the failure everyone would remember.

### D3 — Cumulative spend is counted in ONE place, before the money moves

`ACCOUNT_INITIATE_PAYMENT` becomes usable only together with a spend counter that
is authoritative, not advisory. delegation-service gains a reservation API:

```
POST /api/v1/delegations/{id}/reservations   {amount, currency, idempotencyKey}
  -> 201 {reservationId}            within all ceilings
  -> 409 {reason: DAILY|MONTHLY|PER_TX}  refused, with which ceiling and how much is left
POST /api/v1/delegations/{id}/reservations/{rid}/confirm
POST /api/v1/delegations/{id}/reservations/{rid}/release
```

The edge reserves **before** initiating the payment and confirms on a settled
outcome, releasing on failure. Reserve-then-confirm rather than count-after:
counting after settlement lets two concurrent payments both pass a check that
neither would pass alone, and "we noticed afterwards" is not a limit.

Why in delegation-service and not in the payment rail, against ADR-0232 D3's
original assignment: a customer with one grant can spend through domestic
payments, SEPA, instant and cards. A counter per rail cannot see the others, so
each rail would enforce a ceiling the customer does not have. The grant is the
only place that sees all of it. ADR-0232 D3 is amended, not ignored — the
enforcing service still refuses, it just asks one authority first.

### D4 — Nothing is granted without SCA, and nothing is silent

Issuing a disponent card and granting spend both require the grantor's SCA, and
both emit audit events (ADR-0133). The grantee's first use of a new authority
notifies the grantor. A disponent relationship the account owner cannot see the
consequences of is not a feature.

### D5 — What we deliberately do not build

- **No spend without a ceiling.** A grant carrying `ACCOUNT_INITIATE_PAYMENT`
  with no daily and no monthly limit is refused at creation. "Unlimited access to
  someone else's account" is a product decision no bank should make by omission.
- **No delegated PIN or PAN reveal.** PCI scope, and the disponent has their own
  card with their own credentials.
- **No cash withdrawal delegation in this iteration.** ATM rails do not consult
  the reservation API, so the ceiling would be a claim we cannot keep — the exact
  mistake this ADR exists to stop repeating.

## Consequences

The app can finally offer what customers ask for, and every number it shows is
one the platform counts. The cost is a synchronous call from the edge to
delegation-service on the delegated-payment path, and a reservation lifecycle
that must release on every failure branch — if it leaks, a delegate's ceiling
silently shrinks, which is why release is part of the same test suite as
confirm.

`perTransactionLimit` stays as it is. `dailyLimit` / `monthlyLimit` stop being
refused, because they finally mean something.

## Alternatives considered

**Count cumulative spend in each payment rail** — ADR-0232 D3's original
assignment. Rejected: one grant is spendable through domestic, SEPA, instant and
cards, and a per-rail counter cannot see the others, so every rail would enforce
a ceiling the customer does not actually have. Four counters that each undercount
are worse than one that is right.

**Count after settlement instead of reserving** — simpler, no lifecycle to leak.
Rejected: two concurrent payments would both pass a check that neither passes
alone, and a limit discovered afterwards is not a limit.

**Model the additional card as `ACCOUNT_INITIATE_PAYMENT` on the account** —
fewer concepts. Rejected: it throws away the thing that makes a card safe. A card
has its own ceiling that the platform already enforces, and can be blocked or
re-limited without touching the account.

**Ship the UI now and enforce later** — repeatedly tempting, since the screens
are cheap and the backend is not. Rejected on the same grounds the app's own
KDoc already gives: a "5 000 Kč/den" chip the platform does not honour is worse
than no feature, because the customer acts on it.

## Compliance impact

PSD2 SCA applies unchanged: granting spend and issuing a disponent card are both
SCA-bound (ADR-0021), and the delegate's payments pass the same settlement gate
as anyone's — delegation decides *whether* they may, never *instead of* SCA.

Every grant, reservation, confirmation and revocation is an audit event
(ADR-0133), which is what makes a disponent relationship reconstructable after
the fact — the question a dispute or an AML review actually asks.

GDPR: a disponent sees the grantor's account activity by design, which is a
disclosure the grantor authorises explicitly and can withdraw. No new personal
data leaves the bank; the grantee's identity is already a party.

PCI DSS scope is unchanged, which is why D5 refuses delegated PIN and PAN reveal.
