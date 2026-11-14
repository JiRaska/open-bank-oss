---
date: 2026-11-13
decision-status: proposed
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [fraud, payments, cards, ci]
summary: "Every operation that moves value on someone's instruction gets a fraud decision first. The set is derived from value-sink call sites, not a rail list; callers send one typed risk context; account-control events feed the same decision."
followup: "#16010 — only the D2 gate is built; no module binds the chain's FRAUD step yet, and D3 to D6 are unbuilt"
---

# ADR-0377 — Fraud decisioning covers every value-moving operation: code-derived scope, a typed risk context and account-control events

Relates: ADR-0374 (payment admission chain — this decision defines the set its FRAUD step
must cover and how that set is proven), ADR-0084 (fraud detection bounded context — extends
§1 and §2), ADR-0354 (financial crime as code — fixes where a fraud pack is evaluated),
ADR-0247 (fraud hold), ADR-0375 (authority step), ADR-0283 (card platform), ADR-0171
(Verification of Payee).

## Context

ADR-0374 decided that every outbound payment on every rail passes one ordered admission
chain before any irrevocable step, that no step fails open, and that fraud is enforced on
every rail (points 1, 2 and 8). It did not define what a rail is, and its planned adoption
gate takes its scope "from the rail list".

Read against `origin/main` on 2026-11-13, that leaves four things undecided.

**The set of operations is wider than the set of rails.** Sixteen modules reach a value
sink — a transaction booking, a journal post, a balance debit or credit, or a scheme
submission. Seven of them are payment rails in the sense ADR-0374 means. The others are
either the bank's own bookkeeping (interest, fees, net settlement) or value that moves on
someone's instruction outside any rail: a loan disbursement, and the own-account and pocket
transfers the customer edge posts straight to transaction-service. A control bound in each
rail does not see those, and a gate scoped from a rail list reads as passing about them.
This repo has made that mistake before with a hand-kept scope: a module missing from the
list is not reported as unchecked, it is not reported at all.

**Binding the chain is not the same as binding its FRAUD step.** The kernel requires every
step to be bound, but a step may be bound as `unbound(step, reason)` — recorded as not
applicable. That is the right tool for a step a rail genuinely does not have. It is also a
way to adopt the chain and leave fraud out, and nothing distinguishes the two today.

**The scoring request cannot carry what a fraud decision needs.** It has five fields:
amount, currency, rail, account, counterparty. Each of the five callers builds it in its
own adapter, and every one of them sends the counterparty as null — so the one rule that
looks at the payee can never fire, and payee history never accumulates. Nothing forced the
field to be supplied: it is nullable in five separately written request types, and it is
typed as a UUID, which a rail does not hold for a payee at another bank.

**Fraud does not start at the payment.** An account takeover is visible when a device is
enrolled, a credential or a contact detail is changed, a payee is added or a limit is
raised — minutes or days before value moves. ADR-0084 §2 lets fraud-service consume such
events as enrichment, and nothing decides that they must reach it or what they do.

## Decision

**D1 — The covered set is every instructed value movement.** We will treat as covered any
code path that reaches a value sink on the instruction of a customer, a third-party
provider, a delegate, a mandate or a standing instruction — on any rail and outside one.
That includes card authorisation, direct-debit collection, standing-order execution, loan
disbursement, payout, bulk release, and transfers between a customer's own accounts. Each
gets a fraud decision before the irrevocable step, through the ADR-0374 decision types, and
an unanswered fraud decision is a HOLD (ADR-0374 point 2). Value the bank moves on its own
account with no instructing party — interest capitalisation, fee posting, net settlement —
is outside the set, and says so with a reason.

**D2 — The set is derived from code and proven by a gate.** We will not keep a list of
rails. A module is value-moving when its `src/main` contains a client call that resolves to
a value sink; the gate derives that set, and a registry classifies each derived module as
`instructed` or `bank-own`. The registry is checked in both directions: a derived module
with no line fails, and a line whose module no longer derives fails. An `instructed` module
must build the admission chain with FRAUD bound to a check, not declared unbound. Modules
that do not yet do so carry a pending state that may only be removed, never added to a
module that already complies.

**D3 — One typed risk context, with required fields.** We will define the fraud request
once, in `openbank-libs-fincrime`, and delete the per-rail request types as each rail binds
the chain (ADR-0374 point 11 already moves the adapter there). Fields a caller always knows
are non-nullable: rail, amount, currency, debtor account, instructing party, initiation
mode (customer present, standing instruction, mandate, third-party provider, delegate) and
a counterparty reference. Where a value is genuinely unknown the type says so with an
explicit variant and a reason, never with null — a missing input and an absent one must not
share a representation. Evidence only some callers have is typed and optional: device and
session reference, channel, merchant category and country, the Verification of Payee
result. Callers send what only they know; fraud-service keeps deriving what it owns
(velocity, payee age and history), as ADR-0354 §1 requires.

**D4 — Account-control events feed the decision; they are not admitted.** Device
enrolment, strong-authentication credential change, contact-detail change, payee creation
and limit increase reach fraud-service as events. They are not routed through the admission
chain and they never block the action itself. What they change is the next decision: the
age of the most recent such change is an input a fraud pack can read, so a payment shortly
after one can be challenged or held.

**D5 — No new verdict.** Cooling-off after a risky change and a scam warning before an
authorised payment are expressed with the existing verdicts and reason codes: a pack rule
over the D4 inputs yields CHALLENGE or REVIEW, and the client renders the warning from the
reason code. ADR-0354 §2 keeps the executable verdict set closed, and this decision does
not reopen it.

**D6 — A fraud pack is evaluated in fraud-service only.** The pure evaluator in
`openbank-libs-fincrime` is shared code; the live fraud verdict is computed in one place.
Rails call it through the one port in the chain module. The same evaluator runs replay and
the typology scenarios in CI, which is what sharing it buys. This matches ADR-0374 point 7,
where the service that owns the state evaluates its own pack.

## Alternatives considered

- **Keep ADR-0374's rail-list scope for the adoption gate.** Cheaper to write and enough
  for the seven rails. Rejected because the list is the thing being checked: a module that
  moves value and is not on it is invisible, and three such modules exist today.
- **Enforce at the sink — make transaction-service refuse a booking that carries no
  admission proof.** One choke point instead of sixteen call sites. Rejected for now:
  ADR-0374 rejected a central admission service, the scheme submission is a second sink
  transaction-service never sees, and the bank's own bookings would need the same proof or
  an exemption list at the sink. It remains the stronger control if the registry in D2
  proves hard to keep honest.
- **Keep the fraud request permissive and enrich everything server-side.** No caller
  changes. Rejected because the payee, the initiation mode and the device are known only to
  the caller, and the nullable field is exactly how five callers came to send nothing.
- **Score account-control events synchronously and block the action.** Stops a takeover
  earliest. Rejected: login and enrolment are served by the identity provider and the
  strong-authentication service, a blocked credential change locks a legitimate customer
  out of the remedy, and the value at risk only moves at the payment, where D4 applies.
- **Evaluate packs in each rail from a local snapshot.** No network hop on the payment
  path. Rejected: velocity and payee state live in fraud-service, and a pack change would
  be live in as many versions as there are rail deployments until the last one rolls.

## Consequences

**Positive**
- A new value-moving module cannot be added without someone classifying it.
- Adopting the chain with FRAUD declared unbound is visible as a named pending state.
- A caller cannot omit the payee by leaving a field out.

**Negative**
- The gate is module-granular. It proves a module builds the chain with a FRAUD check; it
  does not prove every sink call in that module runs behind it. The per-rail test ADR-0374
  requires in its delivery check carries that.
- Card tokenisation and the card-issuance authorisation endpoint move no value themselves,
  so the gate does not derive them. They are covered by D1 and tracked by hand in #16010.
- The registry is one more file a rail PR touches when it binds the chain.

**Neutral**
- This supersedes the scope definition of ADR-0374's planned `payment-admission-chain-adopted`
  gate; ADR-0374 itself is unchanged and its delivery slices keep their order.
- Enforcement is still switched on per rail and only after that rail has run in shadow:
  a currency with no threshold mapping scores REVIEW today, so enforcing a multi-currency
  rail before that is resolved would hold every such payment.
- Inbound credits enter the covered set when an intake exists (ADR-0374 point 9), with the
  direction carried in the D3 context.

### Gate

`value-movement-admission-coverage` (enforced), `.github/scripts/check-value-movement-admission.py`,
registry `.github/scripts/value-movement-admission-registry.txt`.

### Delivery check

D2 is delivered when the gate passes and no pending state is left:

```bash
python3 .github/scripts/check-value-movement-admission.py
grep -cE 'pending-(chain|fraud)' .github/scripts/value-movement-admission-registry.txt   # 0
```

D3 is delivered when no module outside the libraries declares its own fraud request:

```bash
git ls-files 'openbank-*/src/main/*/FraudScoreClient.kt' 'openbank-*/src/main/*/FraudScoringAdapter.kt' | grep -v '^openbank-libs'   # prints nothing
```

## Compliance impact

- PCI DSS: not applicable — the risk context carries a merchant category and country, no
  card number or authentication data; card authorisation data handling is unchanged.
- DORA: not applicable as a distinct engagement — no criticality tier or recovery target
  changes.
- GDPR: D3 and D4 send device and session references and account-control events to
  fraud-service for fraud prevention. That is new processing in that service and needs an
  entry in its threat model and retention rules before it is built.
- PSD2: transaction-risk monitoring is the engagement; D1 widens what it covers and D5
  keeps strong-authentication step-up as the response to CHALLENGE.
- CNB: not applicable — no reporting obligation changes.

## References

- ADR-0374, ADR-0084, ADR-0354, ADR-0247, ADR-0375
- #15724 (admission chain programme), #16010 (this decision's delivery tail)
