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

13. **How a hold is carried, decided for delivery.**
    - *Case intake.* The rail writes a `PaymentHeld` event in the same transaction as the
      status change; aml-service consumes it and opens the case. No rail calls the case
      endpoint for a hold, so a hold without a case cannot exist and the per-rail case
      adapters go away.
    - *Unavailable control.* The payment is HELD at once and re-evaluated automatically on a
      bounded schedule. A case is opened only when re-evaluation is exhausted or the control
      answers with a true review; an outage does not flood the queue.
    - *Funds.* A held payment reserves its amount through the same reserve, confirm and
      release operations as limits (point 4). Until those exist a release re-runs standing
      and balance, and a failed re-run rejects.
    - *Manual status change.* The existing operator status endpoint stays, but refuses any
      transition out of RECEIVED or HELD; leaving those states is the chain's and the
      four-eyes release's alone.
    - *Standing from party state.* A party that is suspended or closed freezes its accounts
      with the origin recorded; returning to active lifts only party-origin freezes. The
      propagation compares event time, so a late replay of an older state changes nothing.
      The customer is not told why.

Delivery order, each a separately approved release: (A) chain types, HELD and release on
domestic and SEPA, with standing; (B) limits; (C) sanctions coverage and inputs;
(D) remaining rails; (E) AML destination risk; (F) fraud enforcement per rail.

**Delivery note, step A on the domestic rail (2026-11-13).** Points 3 and 13 left five things to
the first rail that adopts the chain. They are recorded here because the next rail must make the
same choices, not new ones.

- *The switch.* The chain is off by default per rail (`openbank.<rail>.admission.enabled`). One
  fact says whether a run goes through admission: the task queue it is started on. Whoever starts
  a run reads the switch and picks the rail's own queue or its admission queue; workflow code reads
  the queue from the run's start event and never reads configuration. A run on the rail's own
  queue writes nothing the implementation from before the chain does not write (compared event for
  event with a history that implementation recorded, and replayed both ways). Only a build that
  knows the chain polls the admission queue, so a worker from before it is never handed a task of
  an admission run; if no such worker is up, admission runs wait. Deployment order is therefore not
  a rule to follow: the switch can be set on any pod at any time, and an older image can return
  with admission runs open. They resume when a worker that knows the chain does. A payment already
  HELD is recovered on the admission queue whatever the switch says.
- *The case request travels on the rail's own event topic.* `PAYMENT_HELD` is one rail-neutral
  event type carrying `rail`; aml-service reads the rails' topics on one channel and ignores every
  other type. No new topic. The case key is `payment-hold:<rail>:<paymentId>`, derived by the
  consumer, never taken from the event; the alert code is `PAYMENT_HELD`. A rail accepts a decision
  only from the case with that key, and only while it waits on a requested case.
- *Terms of a hold.* It expires 48 hours after the payment was first held, the value the domestic
  fraud hold already used. An unavailable control is asked again every 15 minutes, five times,
  before the hold becomes a case.
- *The manual status change* (point 13) refuses a transition out of HELD always, and out of
  RECEIVED while the chain is on for the rail and something can still act on the payment. A
  RECEIVED payment whose re-drive attempts are spent and which has no open run is picked up by
  nothing; refusing it too would leave it with no exit. An operator may end that payment, REJECTED
  or CANCELLED only, never VALIDATED, and the state is counted and alerted on. With the chain off a
  RECEIVED payment keeps the exit it had.
- *A hold lives on the payment's row, not in its run.* The row records when the payment was held,
  when its case was requested and what the case decided. The run keeps none of it: it asks one
  activity to look at the row and end the hold if it can be ended, and asks again when nudged or
  after its check interval. So the order of events does not matter. A decision that arrives before
  the run, while it starts, or after it died is on the row for whichever run comes; a sweep gives
  every HELD payment without an open run a new one. Admission activities retry without limit.
- *Expiry wins.* A hold ends in rejection 48 hours after `held_at`, read from the row. A decision
  is accepted only before that, and one accepted but not yet applied when the term ends is not
  applied (point 3: a hold never expires into a release). The run notices within its check
  interval, 15 minutes.
- *Release* (point 13, funds). A case decision covers exactly the evidence the case was shown, and
  nothing else: per control that asked for a human, its reason and a fingerprint of the evidence it
  returned, recorded with the case request. The same reason on other evidence (another matched
  list entry, another score or rule version) is a different question, and a control whose evidence
  does not say what was found is never covered. At release the whole chain is
  evaluated again, and the payment is sent only if every bound control answers, none refuses, and
  every "review" among the answers is one the released case was shown. A refusal rejects. A
  control that still cannot answer leaves the payment HELD. A "review" the case did not cover (a
  control that was down when the case was opened, a list that changed during the hold) leaves the
  payment HELD and requests a further review, as a case of its own with the key
  `payment-hold:<rail>:<paymentId>:<n>`, inside the same hold and the same expiry. A decision is
  accepted only from the case whose key equals, exactly, the key of the hold's current review.
  This is what keeps an outage that turned payments into cases from sending them unscreened.
  Balance is not re-checked at release by this step (see below).
- *One fraud score per payment.* The rail keeps the first real score on the payment and reuses it
  on every later evaluation, because the fraud service stores and counts every score it gives and
  cannot recognise a repeat.
- *What a rail may leave unbound.* A step whose facts or owner do not exist yet is declared unbound
  with its reason, which every admission records. On domestic that is authority (decided when the
  payment is submitted, not again by the chain; ADR-0375 D1), limits (step B), AML destination risk (step E) and payee
  verification (not required by the scheme). Fraud is bound, and reports itself not applicable
  while its own per-rail enforcement is off (point 8).

Not delivered by step A on domestic, and a precondition of switching it on in production: the
four-eyes decision on a hold's case (point 3; the case keeps aml-service's single-analyst
decision), the balance re-check at release and funds reservation for a held payment (point 13
asks for standing AND balance; the release re-runs standing only), the release of a delegated
payment's spend reservation when the chain rejects it or its hold expires, party standing read by
the rail (point 4; the rail reads account standing, which lags a party's suspension by the
propagation delay), one test across rail, case service and back, and a run against a real workflow
server and a broker with access control.

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

Delivered for point 3 in openbank-aml-service (#16003): a case of origin `PAYMENT_HOLD_INTAKE`
reaches CLEARED or BLOCKED only by a proposal of one analyst and a confirmation by a different
one restating its outcome (CLEAR or BLOCK) and reason; the status-changed event is written only
on the confirmation. Proved by `HoldDecisionFourEyesIT` (real HTTP, real Postgres). The OPA
grants for `amlCase.holdDecision.propose` / `.confirm` are a separate governance change.

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
