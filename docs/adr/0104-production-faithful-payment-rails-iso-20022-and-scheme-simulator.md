# 104. Production-faithful payment rails: real ISO 20022 messages + scheme simulator

Date: 2026-06-22
Status: Accepted
Delivery-Status: Shipped
Author(s): Jiří Raška

## Context

OpenBank can originate, screen, orchestrate and **internally** settle a payment, but it
never actually builds the messages a real bank would put on the wire, and it has nothing
on the other end to talk to. The "money out" story stops at the bank's own ledger.

Concretely, verified against the code today (not the docs):

- **No rail builds a real scheme message.** `sepa-payment`, `domestic-payment` and
  `sepa-instant` create a domain object and **emit a Kafka outbox event** (`paymentCreatedPayload`,
  `PAYMENT_STATUS_CHANGED_EVENT`) — there is no `pacs.008`, no `pain.001`, no XSD-validated
  XML anywhere. `swift-service` has a `rawMt: String?` field that is **always `null` at
  creation** with no builder. JAXB is on the BOM but unused by any payment service.
- **No counterparty exists.** There is no `ClearingSimulator`, no scheme/CSM adapter, no
  network stub. Every outbound adapter across the payment fleet targets an **internal**
  OpenBank service (sanctions, fraud, aml, balance, ledger).
- **`settlement-service` is internal-only — and that part is good.** Its Temporal
  workflow (ADR-0101) runs `debitPayer → creditPayee → bookToLedger`, each calling
  balance-service / ledger-service over REST, with real compensation. This is the correct,
  modern settlement core. But it has **no scheme/network layer**: the compensation log
  literally says `"stub: wire reversal to balance-service"`. The "money leaves the bank"
  boundary is currently the *internal* balance-service.

So the honest gap is **not** "money doesn't really leave" — it never can, see below — but
"**the rails build no messages and there is no counterparty to settle against.**" That is a
fixable hole in code, not a licensing wall.

### Why "real money out" is the wrong target

Connecting to CERTIS (ČNB), the SEPA CSMs (EBA STEP2/RT1) or SWIFT requires a **banking
licence, scheme membership, production certificates and legal agreements** — none of which
is code. Chasing literal "real money out" is a dead end for a pre-licence platform. We will
not pretend otherwise, and we will not ship anything that *looks* like it reaches a real
network.

What is achievable, and rare, is everything up to that last hop:

> **Production-faithful to the network boundary, with a faithful scheme simulator standing
> in for the real network — and a clean swap-point to replace it on the day a licence and
> membership exist.**

This is the only honest version of "money out", and it converges with where the regulated
rails are heading anyway: **CERTIS, SEPA and SWIFT CBPR+ all standardise on ISO 20022
(`pacs.*`)**, so a `pacs` core built once, with thin per-scheme wrappers, serves all three.

### What already points this way

- **ADR-0035** proves the house can build and XSD-shape real ISO 20022 (`camt.053`) — but
  that is bank→customer reporting, not the inter-bank `pacs.*` clearing layer.
- **ADR-0090** carries ISO 20022 `transactionStatus` (`RCVD/ACTC/ACSC/RJCT`) — but only as
  status codes in the Berlin Group TPP API, not as wire messages we construct.
- **ADR-0103** records *which* rail a transaction used as a first-class fact — but explicitly
  not the message format or the scheme integration.
- **ADR-0101** gives durable settlement orchestration; **ADR-0100** gives deterministic
  simulation testing. Together they are the substrate a scheme simulator plugs into.

None of these, alone or together, describes building real `pacs.008`/`pacs.002`/`camt.054`,
a scheme simulator, or the licence swap-point. **This ADR is that missing whole.**

## Decision

**We will make the payment rails production-faithful up to the network boundary: each rail
builds real, XSD-validated ISO 20022 messages and hands them to a pluggable scheme gateway
port whose only implementation today is a faithful in-house scheme simulator. The simulator
is the swap-point — the day a licence and scheme membership exist, it is replaced by a real
gateway adapter with zero rewrite above the port.**

### 1. A real ISO 20022 message core in `openbank-libs`

A new `libs/iso20022` module owns the canonical, XSD-validated message layer, built **once**
and reused by every rail (per ADR-0014 centralisation):

- JAXB (or equivalent) bindings generated from the **official ISO 20022 XSDs**, vendored under
  `libs/iso20022/schemas/` with their version pinned.
- Builders + marshallers for the inter-bank credit-transfer set:
  `pacs.008.001` (FI-to-FI customer credit transfer), `pacs.002.001` (payment status report),
  `camt.054.001` (debit/credit notification). `pacs.004` (return) and `camt.056`
  (recall) are in scope for the return/recall paths.
- **Validation is mandatory**: every outbound message is marshalled and validated against the
  pinned XSD before it leaves the rail. A message that does not validate is a bug, not a reject.
- This is the scheme-agnostic core. It does **not** know about SEPA vs CERTIS vs SWIFT specifics.

### 2. Thin per-scheme profiles, not three message stacks

Each rail keeps a thin profile that wraps the shared `pacs.*` core with scheme-specific
constraints (allowed charset, BIC/IBAN rules, EPC max amount and timing for SCT/SCT Inst,
CERTIS domestic specifics, CBPR+ usage guidelines for SWIFT). One investment, three rails —
the explicit architectural payoff of ISO 20022 convergence.

### 3. A scheme gateway port + simulator (the swap-point)

A new outbound port `SchemeGatewayPort` is the rail's single exit to "the network":

```
submit(message): SchemeSubmissionAck          // sync transport ack (≈ NAK/positive)
                 → later async pacs.002 status (settled / rejected)
```

Its **only** implementation today is **`openbank-clearing-simulator`** — a standalone service
("the counterparty") that:

- **receives** the rail's `pacs.008`, **validates it against the real XSD** (rejecting malformed
  messages exactly as a CSM would),
- returns a realistic **`pacs.002`** ack/reject with **realistic timing** (settlement windows,
  cut-offs) and a configurable, deterministic **reject distribution** (AC04 closed account, AM05
  duplicate, RR04 regulatory, etc.) — wired through ADR-0100 deterministic simulation so reject
  scenarios are reproducible in tests,
- emits a **`camt.054`** credit notification on the beneficiary side to drive reconciliation,
- holds **no posting authority** and touches no real network — it is unmistakably a simulator,
  named and labelled as such, deployed only in non-production.

The simulator is the swap-point: replacing it is implementing `SchemeGatewayPort` against a real
gateway. **Nothing above the port changes.**

### 4. Wire it into the existing settlement core

The Temporal settlement workflow (ADR-0101) gains scheme-aware activities **around** the existing
internal legs, it does not replace them:

`reservePayer → buildAndValidateMessage → submitToScheme → awaitSchemeStatus → onSettled: bookToLedger + creditPayee + camt.054 / onRejected: compensate`

The current `"stub: wire reversal"` compensation becomes a real `pacs.004`/`camt.056` return path
against the simulator. Internal settlement stays the golden source (ADR-0039); the scheme layer is
the outbound boundary in front of it.

### 5. Reconciliation against the simulated counterparty

A reconciliation step ties our internal settlement (ledger/balance) against the simulator's
`pacs.002`/`camt.054`, exercising the *full* loop a real bank runs (sent vs acked vs settled vs
returned) — not a happy-path mock.

### 6. Honesty, loudly

Every artefact states plainly that this is **production-faithful up to the network boundary with a
simulated counterparty**, and that **no real money moves and no real scheme is contacted**. No
status, badge, statement or API field may imply real-network settlement. We would rather under-claim
than let an evaluator mistake the simulator for a live rail.

### 7. Pilot first, then converge

- **Pilot = SEPA SCT** (`sepa-payment`): best public specs (EPC rulebook + freely available XSDs),
  most standardised, and `pacs.*` recycles straight into CERTIS and SWIFT. One rail,
  production-faithful end-to-end, before fanning out.

### 8. Phased rollout (D-phases)

- **D1** — `libs/iso20022`: vendored XSDs, bindings, `pacs.008`/`pacs.002`/`camt.054` builders +
  mandatory XSD validation, unit-tested against EPC sample messages. Additive, no rail change.
- **D2** — `openbank-clearing-simulator` service: receives `pacs.008`, XSD-validates, returns
  realistic `pacs.002` + `camt.054`, deterministic reject scenarios (ADR-0100). Non-prod only.
- **D3** — SEPA SCT pilot: `sepa-payment` builds the real message, `SchemeGatewayPort` →
  simulator, Temporal settlement workflow wires submit/await/compensate, reconciliation closes
  the loop. One rail faithful end-to-end.
- **D4** — fan-out: `sepa-instant` (SCT Inst timing), `domestic-payment` (CERTIS profile),
  `swift-service` (CBPR+ — finally populates `rawMt`/`pacs`), each as a thin profile over the
  shared core. Return/recall (`pacs.004`/`camt.056`) paths land here.

## Alternatives considered

- **A — Status quo: emit event, mark VALIDATED/SETTLED.** Pros: nothing to build. Cons: builds
  zero scheme messages, has no counterparty, and is exactly the "fakes settled" pattern that makes
  a demo bank read as a toy. Rejected — it is the gap this ADR closes.
- **B — Chase real network connectivity.** Cons: legally impossible without a licence, scheme
  membership, production certs and contracts; no amount of code reaches it. Rejected as a target.
- **C — Build messages but skip the simulator (validate XSD, then drop).** Pros: cheaper. Cons:
  never exercises ack/reject/return, timing, or reconciliation — the half of "faithful" that
  actually proves the rail. Rejected.
- **D — Three independent per-scheme message stacks.** Cons: triples the work and the bug surface,
  and throws away the ISO 20022 convergence that is the whole architectural gift. Rejected in
  favour of one `pacs.*` core + thin profiles.
- **E — Embed the simulator inside each payment service.** Cons: couples test-double code into
  money-path services, blurs the swap-point, and can't model a single shared counterparty/CSM.
  Rejected — the simulator is a standalone, clearly-labelled counterparty behind a port.

## Consequences

**Positive**
- The rails become **production-faithful to the network boundary** — real, XSD-valid scheme
  messages and a realistic counterparty loop (ack/reject/timing/return/reconcile), not a marked-as-settled fake.
- **License-ready**: the swap-point is a single port; going live is implementing one adapter, with
  no rewrite above it.
- **One core, three rails**: ISO 20022 convergence means CERTIS/SEPA/SWIFT share the `pacs.*` layer.
- Reframes the "#1 money-out gap" from "money doesn't leave" (unsolvable) to "rails build no
  messages + no counterparty" (solvable in code) — and closes it.
- Genuinely rare: most OSS/demo banks fake "settled"; real ISO 20022 + a faithful simulator is what
  convinces an evaluator this is not a toy.

**Negative**
- A real ISO 20022 implementation (XSD bindings, validation, per-scheme profiles) plus a new
  `openbank-clearing-simulator` service is significant, money-path work.
- `swift-service`'s null `rawMt` and the rails' event-only paths must be reworked to build messages.
- The simulator must be unmistakably non-production and can **never** be deployed to a prod surface.

**Neutral**
- Internal settlement (ADR-0101/0039) is unchanged as the golden source; the scheme layer sits in
  front of it as the outbound boundary.
- A real SEPA/SWIFT **test sandbox** could later replace the simulator for an even higher-fidelity
  *test* handshake without a full licence — the port makes that a drop-in too.

## Compliance impact

- **Money-path (ADR-0030):** the new `clearing-simulator` and the rail changes are money-path — each
  resulting PR needs 2 approvals + a threat model (`docs/threat-models/<service>.md`). The threat
  model must explicitly cover "simulator must never reach production / be mistaken for a live rail".
- **PSD2 / SCA:** unchanged; the SCA settlement gate (ADR-0086) stays in front of the rail. ISO 20022
  status maps cleanly onto the Berlin Group `transactionStatus` already exposed (ADR-0090).
- **CNB / SEPA / SWIFT:** building to the real rulebooks + XSDs is the substrate for eventual scheme
  certification; nothing here claims membership or certification.
- **DORA:** the full send/ack/reject/return/reconcile loop is materially better operational-resilience
  evidence than a happy-path mock.
- **GDPR:** messages carry the same debtor/creditor data already held; the simulator runs on synthetic
  / sandbox data only and persists no production personal data.

## References

- ADR-0030 — Supply-chain security & SSDLC hardening (money-path threat-model requirement)
- ADR-0032 — Synchronous sanctions/AML screening gate in payment execution
- ADR-0035 — Multi-currency statements (`camt.053`) — prior in-house ISO 20022 (customer-facing)
- ADR-0036 — SEPA Direct Debit mandate lifecycle (the SDD collection path this enables)
- ADR-0039 — Ledger as golden source; balance as projection (internal settlement stays authoritative)
- ADR-0090 — PSD2 XS2A Berlin Group base (ISO 20022 `transactionStatus` already exposed)
- ADR-0100 — Deterministic simulation testing (reproducible reject/timing scenarios)
- ADR-0101 — Temporal durable execution (the settlement workflow the scheme layer wires into)
- ADR-0103 — Transaction rail & instruction type at origination (the rail fact this layer makes real)
- EPC SEPA SCT / SCT Inst rulebooks + ISO 20022 `pacs.*`/`camt.*` schemas (external, pinned in `libs/iso20022/schemas/`)
