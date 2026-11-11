---
date: 2026-11-11
decision-status: proposed
delivery-status: planned
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [payments, aml-sanctions, fraud, accounts]
summary: "Every outbound payment on every rail passes one ordered admission chain before money leaves: standing, limits, sanctions, AML destination risk, fraud, payee check. One decision type, one HELD state; a control that cannot answer holds."
---

# ADR-0374 — Payment admission: one ordered pre-execution control chain with a generic hold

## Context

ADR-0032 put a synchronous sanctions gate into payment execution. ADR-0084 added fraud
scoring. ADR-0171 added Verification of Payee. ADR-0371 adds monitoring after booking. Each
was built per rail, and no ADR decides what a payment must pass before it leaves, in what
order, or what happens when a control wants a human. The owner asked on 2026-11-11 for these
controls to be proactive: stop before sending, not detect after.

Measured on `origin/main` (control matrix, 2026-11-11, executing code only):

- **Standing.** `Account.freeze()` and `canDebit()` exist; no rail calls them. The rails'
  account client carries `(id, partyId)` and no status. Suspending a party does not freeze
  its accounts. A frozen or blocked customer can pay.
- **Limits.** No owner-level per-payment, daily or monthly limit is enforced on domestic,
  SEPA, instant, standing orders or own-account transfers. account-service stores
  `dailyLimit` and nothing reads it. Enforced limits exist only for delegates, cards and
  direct-debit mandates, each with its own model and counters.
- **Sanctions.** Domestic, SEPA and instant screen the counterparty name only: no country,
  no bank identifier. The payment list set omits the national terrorist list and includes a
  PEP list, so a PEP name is treated as a sanctions hit. FX screens the party UUID as a name.
  SWIFT, direct debit and own-account transfers are not screened.
- **AML before sending.** Nothing other than the name match can stop a payment.
- **Fraud.** Enforced on domestic behind a flag set only in the sandbox manifest. Shadow on
  SEPA, instant, FX and cards; on instant and cards the score is computed after the decision.
- **Hold.** Domestic and SEPA have no held status: a screening REVIEW leaves the payment
  RECEIVED with the workflow ended and no release path. Instant and FX park it PENDING with
  no release. The only working hold and release is domestic's fraud review.
- **Inbound.** No external-credit intake exists on any rail; incoming checks have no flow
  to attach to yet.

## Decision

We will route every outbound payment, on every rail, through one admission chain before any
irrevocable step, and give every rail the same hold.

1. **One chain, fixed order, shared code.** A library module owns the chain and its types.
   Rails supply facts and adapters; they do not reorder, skip or reimplement steps. Order:
   1. **Standing** — debtor party and account may transact (not frozen, blocked or closed).
   2. **Limits** — per-payment and cumulative limits for the debtor and channel.
   3. **Sanctions** — counterparty name, country and bank identifier against sanctions lists.
   4. **AML destination risk** — jurisdiction, counterparty and amount-versus-profile rules.
   5. **Fraud** — behavioural verdict.
   6. **Payee verification** — where the scheme requires it (ADR-0171 stands).
   Cheap, deterministic, local checks run first so a payment that must be refused costs no
   remote call and leaks nothing to a later control.
2. **One decision type.** Each step returns ALLOW, CHALLENGE, HOLD or REJECT with a reason
   code and its evidence. The chain result is the most severe. A step that cannot answer
   (timeout, dependency down, missing data) returns its own outcome UNAVAILABLE, which the
   chain maps to HOLD for outbound money. No step fails open; no step reports a skip as ALLOW.
3. **One HELD state and one release path.** Every rail's state machine gains HELD. A held
   payment opens exactly one case, in the same transaction as the state change through the
   outbox, so a case-store outage cannot lose it. A four-eyes case decision publishes a
   release or reject event; the rail resumes or rejects from HELD. A hold expires to REJECT
   after a configured period. This generalises the domestic fraud hold; the manual status
   patch that does not restart a workflow is removed.
4. **Standing is authoritative in account-service and propagates.** A party suspended or
   blocked freezes its accounts. Rails read standing with the account; a stale or missing
   answer is UNAVAILABLE.
5. **Limits have one model and one counter.** The bank's ceiling per product comes from the
   pinned catalogue revision (ADR-0341). The customer may set a lower limit; raising one
   needs strong authentication and takes effect after a delay. account-service owns the
   definitions and the cumulative consumption as reserve, confirm and release, the pattern
   delegation ceilings already use. Card and delegate limits keep their owners and are read
   through the same step. A change to a bank ceiling is four-eyes.
6. **Sanctions means sanctions.** The payment gate screens name, country and bank identifier
   against sanctions and national terrorist lists. PEP status is a customer-risk input to
   steps 4 and 5, not a sanctions hit. Every rail that moves money to another party is
   screened, FX and SWIFT included, with the party's name rather than an identifier.
7. **AML destination risk is synchronous and bounded.** It evaluates an ADR-0354 pack over
   facts already local to aml-service: the jurisdiction list, the party's risk rating and
   declared profile, and the counters ADR-0371 maintains. It reads no history at request
   time. This amends ADR-0371, which kept all AML evaluation after booking.
8. **Fraud is enforced on every rail, before the irrevocable step.** CHALLENGE maps to a
   strong-authentication step-up, REVIEW to HOLD, DECLINE to REJECT. Enforcement is a
   per-rail activation with its own approval, starting from shadow evidence.
9. **Inbound credits enter the same types.** When an external-credit intake is built, it
   screens the originator and may HOLD the credit before it is made available; it does not
   ship without that. Monitoring after booking (ADR-0371) covers the rest.
10. **Instant payments keep their deadline.** The chain has a time budget per rail. A step
    that would exceed it returns UNAVAILABLE and the payment is rejected to the customer in
    time rather than held past the scheme deadline.

11. **Shared code lives in the libraries, once.** Measured: four rails carry identical
    sanctions clients and fraud score clients, five carry a best-effort case opener, and the
    sanctions adapter has drifted (one rail reports first failure and meters it, three do
    not). The chain module absorbs the adapter and its fail-closed mapping, the synchronous
    fraud scoring adapter and metrics, the transactional case opener and the hold resolution
    consumer. Inter-service clients are generated from the provider contract (ADR-0319),
    sanctions and party included. Name normalisation uses the one library key. Left per
    rail because the differences are real: status enums, reactive adapters on the instant
    rail, and limit counters with different owners.
12. **AI prepares held payments for the reviewer and never releases one.** A read-only agent
    in the agent plane orders the held queue, gathers the customer's payee history and the
    evidence each step recorded, and explains a sanctions near-match. Sanctions queue
    ordering follows ADR-0350 and fraud triage ADR-0203. Release and reject stay four-eyes
    human decisions; an agent proposal is not an approval and does not count as one of the
    two.

Delivery order, each a separately approved release: (A) chain types, HELD and release on
domestic and SEPA, with standing; (B) limits; (C) sanctions coverage and inputs;
(D) remaining rails; (E) AML destination risk; (F) fraud enforcement per rail.

## Alternatives considered

- **Keep per-rail controls and close the gaps one by one.** Smallest change per PR.
  Rejected: the matrix shows nine rails with nine different subsets and three different
  ways to park a payment; the divergence is the defect.
- **A central admission service called by every rail.** One deployable, one place to audit.
  Rejected: it puts a new synchronous dependency in front of all money movement, and its
  outage stops every rail at once. A shared library gives one implementation without one
  runtime; the remote calls that remain are the ones already there.
- **Controls in the edge or gateway.** Earliest possible refusal. Rejected: standing orders,
  direct debits and back-office payments do not pass the customer edge.
- **Fail open when a control is unavailable.** Keeps payments flowing. Rejected for outbound
  money: an attacker who can slow a dependency would switch the control off.

## Consequences

**Positive**
- A payment cannot leave without having passed, or been held by, every control, on any rail.
- A hold is a first-class state with an owner, a deadline and evidence.
- Adding a control or a rail is one adapter, and the order cannot drift.

**Negative**
- Every money-path service changes, most of them in their state machine and their Temporal
  workflow; in-flight workflows need versioned changes.
- Holding on unavailability trades availability for control: a dependency outage becomes a
  queue of held payments. Capacity of the review queue becomes an operational limit.
- More latency before execution. Budgets are measured per rail before enforcement.
- Customers will see refusals and holds that do not happen today, limits first.

**Neutral**
- Planned gate `payment-admission-chain-adopted`: every module in `money_path_services` that
  initiates outbound payments invokes the chain and declares HELD; scope derived from the
  rail list, not hand-kept. Enforced once delivery step D lands.
- Threat models of each touched rail are updated in the PR that adopts the chain.

### Delivery check

Not implemented in this revision:

```bash
grep -rlE 'PaymentAdmission' openbank-*/src/main/kotlin | cut -d/ -f1 | sort -u   # every outbound rail
grep -rn 'HELD' openbank-domestic-payment/src/main/kotlin openbank-sepa-payment/src/main/kotlin | head
grep -n 'id: payment-admission-chain-adopted' -A4 .github/gates/gates.yaml | grep 'mode: enforced'
```

Per rail, a real-HTTP, real-database test must prove by effect: a payment from a frozen
account is rejected; a payment over the daily limit is rejected and the counter is
unchanged; a sanctions review, an unavailable control and a fraud review each end HELD with
one case; a release resumes execution exactly once; a reject returns funds; expiry rejects.

## Compliance impact

- PCI DSS: not applicable — card authorisation data handling is unchanged; card limits are
  read, not moved.
- DORA: not applicable — no ICT third party or recovery arrangement changes.
- GDPR: holds and cases process personal data already held, for the same purpose.
- PSD2: CHALLENGE reuses the existing strong-authentication step; limit increases require
  it. No exemption logic changes.
- CNB: gives sanctions, AML and fraud controls a single enforced point before execution.
  A gate or a test is not an attestation of compliance.

## References

- ADR-0032, ADR-0084, ADR-0171, ADR-0184, ADR-0203, ADR-0247, ADR-0319, ADR-0341, ADR-0350, ADR-0354, ADR-0371
