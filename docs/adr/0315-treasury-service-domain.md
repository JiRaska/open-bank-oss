---
date: 2026-09-24
decision-status: accepted
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [architecture, ledger, accounting-close, ai-agents]
summary: "openbank-treasury-service owns the bank's own deals: a deal lifecycle with four-eyes booking, GL posting through the ledger API, nostro reconciliation and ČNB minimum reserves; AI agents draft tickets and never book."
---

# ADR-0315 — Treasury service domain: deals, GL posting, nostro and minimum reserves

## Context

ADR-0313 (D7, D12, D14) put a money-path treasury service in scope and required its domain to be
decided in its own ADR. What exists on `origin/main` (2026-09-24):

- The ledger already has bank-own GL accounts — nostro 1001, scheme nostro-at-scheme 1110–1113
  (ADR-0281), cash clearing 1100–1103, FX position (ADR-0025), capital 6000–6060 — and a posting
  API: `POST /api/v1/journals` with `idempotencyKey`, per-currency balanced lines and reversal.
- Nothing books interbank deposits, repo, bonds or derivatives; there are no GL accounts for them,
  no counterparty master for banks, and no reserve tracking.
- Payment rails settle against a simulator (ADR-0104); treasury will do the same.

## Decision

1. **Bounded context.** `openbank-treasury-service`, hexagonal (ADR-0002), registered in
   `rules.yaml: money_path_services` with its threat model in the PR that creates it.

2. **Deal aggregate and lifecycle.** One `Deal` aggregate per product type:
   `MM_DEPOSIT` / `MM_LOAN` (overnight and term), `CNB_DEPOSIT_FACILITY`, `CNB_LENDING_FACILITY`,
   `REPO` / `REVERSE_REPO`, `FX_SPOT` / `FX_FORWARD` / `FX_SWAP`, `IRS`, `BOND_PURCHASE` /
   `BOND_SALE`. States:
   `DRAFT → PENDING_APPROVAL → BOOKED → CONFIRMED → SETTLED → MATURED`, plus `CANCELLED`
   (before booking) and `REVERSED` (after, via ledger reversal). Only `BOOKED` and later post.

3. **Four-eyes booking is structural.** `PENDING_APPROVAL → BOOKED` requires an approver who is
   not the creator, enforced in the domain (not only in OPA). A `DRAFT` may be created by a human
   dealer or by an AI agent; an agent principal (`AI_AGENT`) can never approve, book, confirm or
   settle — enforced by OPA policy and asserted by a negative test that runs as the agent.

4. **Limits before booking.** Counterparty limit, product limit and the ADR-0313 risk limits are
   checked at `PENDING_APPROVAL`; a breach blocks booking unless a second, senior approver
   records an override with a reason. Limits are declared as code.

5. **GL posting through the ledger API only.** Each state change that moves value posts a
   balanced journal with idempotency key `treasury:<dealId>:<event>`: booking (commitment,
   off-balance where applicable), settlement (nostro ↔ placement/borrowing), daily accrual,
   maturity, and revaluation for FVTPL / FVOCI instruments. New GL accounts (placements,
   borrowings, repo, bonds by IFRS 9 category, derivative assets/liabilities, accrued interest)
   come as a ledger migration in the first PR. The treasury service never writes the ledger's
   database.

6. **Transactional outbox** for `treasury.deal.*` events (with `openbank.outbox.dispatch-enabled: true`),
   consumed by the risk engine as `MONEY_MARKET_DEAL`, `BOND` and `DERIVATIVE_LEG` instruments
   (ADR-0314 D4).

7. **Nostro reconciliation.** Incoming statements (camt.053, with MT940 mapped to it) are matched
   to expected settlements; breaks become cases with age and amount, and an unmatched break past
   a threshold alerts. Sandbox statements come from the simulated counterparty.

8. **Minimum reserves.** Tracks the reserve base per maintenance period from ledger balances, the
   daily holding at the ČNB account, and the running average versus requirement. It proposes
   daily holdings; it does not move money itself — a movement is a `CNB_DEPOSIT_FACILITY` or
   transfer deal through the normal lifecycle.

9. **Simulated market.** A simulated counterparty set quotes from the risk engine's curve set
   plus a spread and confirms / settles deals, so the full lifecycle runs end to end in the
   sandbox. Real confirmation matching and dealing-platform connectivity stay external
   (ADR-0313).

10. **AI agents (ADR-0313 D12)** get a charter in `agents.yaml` with tools limited to read plus
    `treasury.deal.draft`. Every draft stores the inputs and rationale the agent used, visible to
    the approver.

## Alternatives considered

- **Put deals in the ledger as special journals.** Rejected: a deal has a lifecycle, limits and
  approvals before it posts anything; that is treasury logic, which ADR-0313 keeps out of the
  ledger.
- **Approval only in OPA.** Rejected: a policy that is not enforcing (AUTHZ_ENFORCE is off in
  several services) would silently allow self-approval; the domain rule makes it structural.
- **One generic `Deal` with free-form attributes.** Rejected: settlement and accrual rules differ
  per product, and a typed model is what makes the posting rules testable.

## Consequences

**Positive**
- The bank's own funding, placements and hedges exist in the ledger, so the risk engine sees the
  whole balance sheet, not only the customer side.
- The approval rule is testable by a negative case run as the AI principal.

**Negative**
- A new money-path service: two approvals per PR, threat model, mutation testing, higher coverage.
- A ledger migration adding GL accounts touches another money-path service.

**Neutral**
- Real market connectivity remains out of scope.

## Compliance impact

- PCI DSS: not applicable — no cardholder data.
- DORA: a new service supporting a critical function; it joins the ICT asset register like any
  new service. No specific requirement claimed here.
- GDPR: not applicable — counterparties are banks and the central bank, not natural persons.
- PSD2: not applicable — no payment-service-user surface.
- CNB: minimum reserve tracking and money-market dealing with ČNB are modelled; the sandbox does
  not transact with ČNB.

## References

- ADR-0313, ADR-0314, ADR-0002, ADR-0025, ADR-0030, ADR-0031, ADR-0104, ADR-0281
