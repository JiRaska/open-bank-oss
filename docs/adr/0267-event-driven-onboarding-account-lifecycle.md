---
date: 2026-08-20
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [onboarding, accounts, kafka, kyc]
summary: "A new party's accounts are opened and activated by party domain events, not by an edge call: PARTY_CREATED opens inert PENDING_ACTIVATION accounts, the KYC+AML two-key gate activates them, and balance init follows the events."
---

# ADR-0267 — Event-driven onboarding account lifecycle

## Context

ADR-0069 decided the customer onboarding *journey*: the customer edge creates the
party, an operator clears KYC, and `POST /onboarding/account` then calls
account-service to open an account — a synchronous, edge-driven pull, gated on the
party already being `ACTIVE`.

What actually shipped for the sandbox onboarding path is a different mechanism.
Account opening is driven by **party domain events** rather than by an edge call,
accounts exist in an inert `PENDING_ACTIVATION` state before KYC and AML clear, and
two downstream effects — zero-balance initialization and the welcome-bonus credit —
hang off the resulting events. ADR-0116 records the KYC engine's own case lifecycle
and its sandbox straight-through mode, but stops at the KYC case; nothing records the
account-side lifecycle.

That gap had a visible cost. Eleven sites in `openbank-account-service` (and roughly
two dozen more across aml, balance, party, customer-edge, onboarding and the gitops
manifests) cited **ADR-0073** for this behaviour. ADR-0073 is *"Hardware-backed
credential storage for the customer app"* — mobile Keystore/Keychain secrets, an
unrelated subject, and one whose `delivery-status: planned` is correct for what it
really decides. The citations were a placeholder for a decision that was never
written down (#5718). This ADR writes it down; it records behaviour already in
production rather than proposing a change.

Two constraints shaped the mechanism and are worth stating, because they are the
reason the obvious synchronous design does not work here:

1. **An onboarding account is opened from a Kafka consumer, which has no
   request-scoped JWT.** account-service's outbound balance REST client fails closed
   without one, so a synchronous balance init could never succeed on this path.
2. **That consumer runs on the Vert.x event loop.** A blocking REST call from it
   throws outright. Worse, because the init ran *before* the publish, its failure also
   suppressed the `AccountCreated` event.

## Decision

**We will drive the onboarding account lifecycle from party domain events, and
express every downstream effect as a reaction to an event rather than a synchronous
call.**

**1. `PARTY_CREATED` opens inert accounts.** account-service's `PartyEventConsumer`
consumes `openbank.party.events`. For an `INDIVIDUAL` party it opens a
`PENDING_ACTIVATION` multi-currency `CURRENT` account (one IBAN plus a primary CZK
pocket) and a `SAVINGS` account, so a fresh customer has something to explore
immediately. `PENDING_ACTIVATION` is genuinely inert: `canDebit` and `canCredit` both
require `ACTIVE`, so **no money can move before KYC and AML clear**. `OpenAccountCommand`
carries an `initialStatus` that defaults to `ACTIVE` for operator-opened accounts;
only the onboarding path passes `PENDING_ACTIVATION`.

**2. The party's KYC+AML two-key gate is the activation trigger.** party-service
decides party activation on two independent keys — the KYC case outcome and the AML
screening outcome (`parties.aml_status`, the second key). When the party becomes
`ACTIVE`, account-service activates that party's pending accounts.
`Account.activate()` accepts only `PENDING_ACTIVATION`, so activation is a
single-direction transition and a replayed event cannot resurrect a closed or frozen
account.

**3. Balance initialization is event-driven.** balance-service's `BalanceInitConsumer`
consumes `openbank.accounts.account.created` and creates the zero balance row. This
replaces the synchronous REST init, for the two reasons in the Context above. It is
idempotent (`initializeBalance` no-ops when a balance exists), so it co-exists with the
operator REST path and tolerates at-least-once delivery.

**4. The welcome bonus is real money, granted once, and off by default.** The bonus is
initiated through transaction-service as a double-entry payment (Dr bank cash-clearing
/ Cr customer deposit) — **never a direct balance poke**. It lands on the `CURRENT`
account only, never once per account, and the implementation keys the transaction on the
account id so re-activation or event re-delivery cannot pay it twice. It stays
flag-gated (`openbank.welcome-bonus.enabled`, default **off**) and sandbox-only, since
it conjures money that no funding leg backs.

**5. Failure handling distinguishes poison pills from transient failures.** An
unparseable event, or one missing the partyId, can never succeed: it is logged and
acked. A well-formed event that could not be projected yet gets a short bounded retry;
if it still fails the exception escapes and SmallRye routes the record to the
dead-letter topic (`failure-strategy: dead-letter-queue`). Parked for replay, never
destroyed. This distinction is not decoration — an earlier catch-all conflated the two
and acked-and-dropped a whole cohort's `PARTY_CREATED`, so those customers silently
never got an account (2026-06-24..2026-07-17).

**6. Idempotency is per party AND per account type.** Re-delivered events are no-ops.

## Alternatives considered

- **Keep ADR-0069's edge-driven pull as the only path** (`POST /onboarding/account`
  after the party is `ACTIVE`). Simpler and synchronous, and it remains the
  operator/edge path. Rejected as the *sole* mechanism because it gives the customer
  nothing until an operator has acted, and it cannot open the account-shaped scaffolding
  a sandbox customer needs to try the app before KYC completes.
- **Synchronous REST balance init from the account-opening path.** This is what
  existed and it was removed, not rejected in the abstract: from a Kafka consumer there
  is no request JWT to propagate (the client fails closed) and a blocking call on the
  event loop throws — and because it ran before the publish, it also suppressed the
  `AccountCreated` event that everything downstream depends on.
- **Open the account only once the party is already `ACTIVE`** (no
  `PENDING_ACTIVATION` state). Fewer states, but it loses the invariant that makes the
  early opening safe: an inert account that cannot debit or credit is a strictly weaker
  object than no account, and it lets IBAN allocation and the welcome-bonus wiring be
  exercised before the compliance gate rather than in a rush after it.
- **Credit the welcome bonus directly to the balance.** Rejected outright: it would
  create money with no journal entry, on a money path.

## Consequences

**Positive**
- The compliance gate is enforced by the domain object, not only by a caller: a
  `PENDING_ACTIVATION` account cannot move money at all.
- Account opening no longer depends on a request-scoped JWT or on a blocking call from
  the event loop, which is what made the previous shape fail on this path.
- Every downstream effect (balance init, welcome bonus, notifications) reacts to a
  published event, so each is independently replayable and idempotent.

**Negative**
- The lifecycle now spans four services and two topics, so a customer with no account
  can have four different causes; the DLQ is the first place to look, not the last.
- Accounts exist for parties who may never pass KYC. They are inert, but they are rows,
  and dormant-party cleanup has to account for them.
- Eventual consistency: an account can exist for a short window with no balance row.

**Neutral**
- This ADR is retrospective. It records what shipped across the phases previously
  labelled "ADR-0073 phase 1/2/3" in the changelogs; those changelog entries are
  immutable derived data and keep the wrong number.
- The welcome bonus is off by default outside the sandbox, so most environments exercise
  points 1-3 and 5-6 only.

## Compliance impact

- PCI DSS: not applicable — no card data is involved in this path.
- DORA:    the poison-pill/transient split and the dead-letter topic are the
           operational-resilience property here: a dependency outage parks an onboarding
           event for replay instead of destroying it.
- GDPR:    not applicable beyond what already applies — party PII stays in
           party-service; account-service stores the account, not the identity record.
- PSD2:    an account cannot debit or credit until the party has cleared KYC and AML,
           which is the same gate ADR-0069 places at the edge, enforced one layer deeper.
- CNB:     AML screening is the second key of the activation gate; an account for an
           unscreened party is inert by construction.

## References

- ADR-0069 — Customer onboarding journey (the edge-driven path this complements).
- ADR-0116 — KYC engine, four-eyes gate, sandbox straight-through mode.
- ADR-0024 — Multi-currency account, single IBAN, pockets.
- ADR-0003 — Transactional outbox for Kafka.
- ADR-0073 — Hardware-backed credential storage for the customer app: the ADR these
  sites *used* to cite, and which decides something else entirely (#5718).
- `docs/threat-models/openbank-account-service.md` — welcome-bonus M2M trust boundary.
