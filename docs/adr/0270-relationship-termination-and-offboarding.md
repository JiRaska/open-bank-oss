---
date: 2026-08-21
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [onboarding, accounts, aml-sanctions, compliance]
summary: "The customer relationship becomes an explicit aggregate, separate from the durable Party identity: bank-initiated termination runs ground -> four-eyes -> notice timer -> wind-down -> TERMINATED, and re-onboarding opens a new relationship."
---

# ADR-0270 — Bank-initiated relationship termination and customer offboarding

## Context

The platform can *start* a customer relationship and it can *erase* one. It cannot **end**
one. That gap is not cosmetic — bank-initiated termination (výpověď rámcové smlouvy) is an
ordinary, high-volume banking operation, and today every instance of it has to be improvised
by hand against a money-path book.

What exists:

- `PartyStatus` is `PENDING_KYC, ACTIVE, SUSPENDED, CLOSED, MERGED`
  (`openbank-party-service/.../domain/model/Party.kt`). Its own KDoc states that `CLOSED` is
  written **only** by GDPR Art. 17 erasure, and `PartyRepositoryImpl.erase` confirms it:
  the transition anonymises every PII field (`legalName`, `dateOfBirth`, `taxId`, address)
  and overwrites the email with `erased-<uuid>@erased.invalid`. So the only terminal state
  reachable today destroys the record the bank is legally required to keep.
- `SUSPENDED` is not an operator action. It is written only by `KycAmlEventConsumer`, derived
  in `PartyService.resolveStatus` from a hard-negative KYC/AML signal. There is no endpoint,
  no ground, no notice, no expiry.
- `MERGED` is duplicate-identity retirement (ADR-0179), unrelated.
- `POST /api/v1/accounts/{id}/close` exists and works, but it is a **single-account** operation
  with no relationship-level counterpart, and it refuses (`AccountNotEmptyException`) whenever
  the account still holds money. Its own `KNOWN LIMITATIONS` comment records two gaps that a
  termination flow makes routine rather than exotic: the balance read is point-in-time and not
  a lock, and it cannot see money already in flight (future-dated standing orders, value-dated
  internal transfers) which will still execute against the now-closed account.

The consequence is that "ukončit klienta" currently decomposes into: close each account by
hand (after manually draining each balance), then either leave the party `ACTIVE` forever — a
live customer record for someone who is no longer a customer — or run a GDPR erasure that
deletes the AML and KYC evidence. ADR-0118 fixed the retention side of exactly this question
(10y ledger, 5y KYC/audit; AML and accounting duties override Art. 17), which means erasure is
the *wrong* mechanism for termination by construction, not merely by convention.

**Why now.** Three forcing functions:

1. **Legal.** Under PSD2 Art. 55 and the Payment Accounts Directive (2014/92/EU) Art. 19, a
   bank terminating a framework contract owes the customer a minimum two-month notice period,
   in writing, on stated grounds. Under AMLD Art. 14(4) the opposite duty applies: where
   customer due diligence *cannot* be completed, the bank **must** terminate — and under the
   tipping-off prohibition (AMLD Art. 39) it must do so without disclosing the AML reason.
   These two regimes demand different notice periods and different disclosure behaviour from
   the same state machine, which is precisely the kind of thing that must not live in a runbook.
2. **Operational.** The immediate driver is mundane: a failed onboarding
   (party `93d8f615-…`) that the customer wants to retry. Today there is no way to end the
   stalled relationship and start a clean one without either stranding the record or erasing it.
3. **Symmetry.** ADR-0215 already designs this exact shape for the credit side — grounds,
   forbearance gate, notice as a durable timer, then acceleration. The deposit and relationship
   side has no equivalent. Two different mental models for "the bank ends something" is a
   supervisor magnet.

## Decision

**D1 — Split the durable *identity* from the revocable *relationship*.**

`Party` today conflates two lifetimes: who someone is (permanent, evidence-bearing, retained
5–10 years) and whether the bank currently does business with them (revocable, repeatable).
We will introduce a `Relationship` aggregate in `openbank-party-service`, keyed by `partyId`,
of which a party may have **many, sequentially**. `Party` keeps identity, documents, KYC and
AML history. `Relationship` owns the lifecycle:

```
                        ┌─ (customer request) ──────────────────────────┐
                        │                                              ▼
PROSPECTIVE ──► ACTIVE ─┼─ TERMINATION_PROPOSED ──► NOTICE_SERVED ──► WIND_DOWN ──► TERMINATED
      │                 │        (four-eyes)         (durable timer)                 (terminal)
      │                 └─ RESTRICTED ─────────────────────────────────┘
      └──► ABANDONED (terminal — onboarding never completed)
```

`Party.status` stops being independently writable and becomes **derived** from the party's
current relationship plus its KYC/AML state, so the existing `resolveStatus` fail-closed
policy keeps its meaning. `CLOSED` and `MERGED` retain today's semantics untouched:
erasure and duplicate retirement stay orthogonal to termination, and a terminated party is
still fully erasable later when its retention clock expires.

This is the decision that answers the immediate driver. **Re-onboarding is not a special
case — it is opening a new relationship on an existing identity.** The failed attempt ends
as `ABANDONED`, a fresh relationship starts at `PROSPECTIVE`, and every KYC document,
screening result and audit entry already gathered stays attached to the party where an
examiner expects to find it.

**D2 — Termination is always on a declared ground, and the ground is data, not code.**

A `TerminationGround` carries: notice period, whether the reason may be disclosed to the
customer, whether the account may be restricted immediately, and whether re-onboarding is
permitted afterwards. Initial set:

| Ground | Notice | Disclose reason | Immediate restriction | Re-onboarding |
|---|---|---|---|---|
| `CUSTOMER_REQUEST` | none | n/a | no | free |
| `ORDINARY_NOTICE` | 2 months | yes | no | free |
| `CONTRACT_BREACH` | per product terms | yes | no | reviewed |
| `KYC_INCOMPLETE` | statutory minimum | limited | yes | after remediation |
| `AML_CDD_FAILURE` | immediate | **no** (tipping-off) | yes | **blocked, escalates** |
| `SANCTIONS_MATCH` | immediate | **no** | yes (freeze, not close) | **blocked, escalates** |
| `ONBOARDING_ABANDONED` | none | n/a | n/a | free |

The table ships as effective-dated reference data on the same pattern as ADR-0212's
jurisdictional packs, not as a Kotlin `when`. A termination requested without an active
ground for the party's jurisdiction **fails closed**.

**D3 — Notice is a Temporal durable timer; the wind-down is a Temporal workflow.**

ADR-0101/0120 already make Temporal the durable execution engine for money-path
orchestration, and ADR-0215 uses a durable timer for the identical "notice must elapse before
enforcement" requirement. `NOTICE_SERVED → WIND_DOWN` cannot fire early, cannot be lost across
a restart, and is visible as workflow state rather than inferred from a scheduler that may
never have run.

During the notice period the customer keeps normal use of the account — that is what the
notice period *is* under PAD Art. 19 — except where the ground sets immediate restriction.
`RESTRICTED` reuses the shipped `freezeAccount`/`unfreezeAccount` primitives rather than
inventing a parallel block; a sanctions freeze is deliberately **not** a close.

**D4 — Wind-down closes the relationship's obligations before it closes its accounts, and no
money is ever stranded.**

The wind-down workflow, in order, each step idempotent and evidenced:

1. Cancel forward commitments — standing orders, direct-debit mandates, scheduled payments,
   card tokens, delegation grants (ADR-0232).
2. Settle and stop recurring charges (billing-service), so no fee accrues past the date.
3. Quiesce inbound — after the termination date incoming credits are returned to the sender
   with a return reason, not silently booked to a closing account.
4. Sweep residual balances to the customer's nominated payout IBAN. Where no IBAN is
   nominated or the payout fails, the balance moves to a **dedicated unclaimed-funds suspense
   account**, ledger-posted and reconcilable, and the customer's claim on it survives the
   relationship. FX dust reuses the sweep-to-close conversion from ADR-0107.
5. Only then call the existing `closeAccount` per account — now guaranteed to meet its own
   empty-balance precondition rather than being fought with.
6. Close the relationship: `TERMINATED`.

Step 3 is what fixes `closeAccount`'s documented in-flight-money gap for this flow. Step 4 is
the load-bearing one: **`AccountNotEmptyException` is a correct guard and we will not weaken
it.** Termination satisfies the guard instead of bypassing it.

**D5 — Termination is a four-eyes action, and its evidence is a first-class record.**

`TERMINATION_PROPOSED → NOTICE_SERVED` requires a second approver via the existing party
approvals endpoint (`/api/v1/parties/approvals/{id}`) — the bank ending a customer's access
to payment accounts is at least as consequential as the changes already gated there. Every
transition emits a canonical evidence record into the ADR-0133 tamper-evident chain, on the
ADR-0214 pattern, so a termination is reconstructible on one query: who proposed it, on what
ground, who approved, when notice was served and by which channel, what was paid out where.

The notice itself is a generated, archived document (document-service) delivered through
notification-service, because "we told the customer" is a claim the bank must be able to
evidence years later, not a fire-and-forget push.

**D6 — Terminated is terminal for the relationship, never for the identity, and the
re-onboarding gate is explicit.**

A `TERMINATED` relationship is immutable. A new relationship may be opened on the same party
**unless** the terminating ground says otherwise (D2's last column). An `AML_CDD_FAILURE` or
`SANCTIONS_MATCH` termination writes a durable re-onboarding block on the party; a subsequent
self-registration or `createParty` that resolves to that identity fails closed into a
compliance queue rather than quietly minting a second relationship. This is the case the
naive "just delete and re-register" answer gets catastrophically wrong.

## Alternatives considered

- **Reuse `CLOSED` for termination.** Zero new states, immediate. Rejected outright: `CLOSED`
  is written by GDPR erasure and anonymises PII, so terminating a customer would destroy the
  KYC and AML evidence that ADR-0118 requires be retained 5 years — and would do it silently,
  since the record still exists and merely reads as `erased-…@erased.invalid`. This is the
  option that was on the table when this ADR was raised, and it is the reason it was written.
- **Reuse `SUSPENDED` as the terminal state.** Cheaper than a new lifecycle. Rejected:
  `SUSPENDED` is *derived* from KYC/AML signals by `resolveStatus`, so a later clearing event
  would silently reactivate a customer the bank had terminated — a fail-open on the exact
  transition that must fail closed. It is also not terminal by design, carries no ground, no
  notice and no evidence.
- **Add `TERMINATED` to `PartyStatus` and stop there, with no `Relationship` aggregate.**
  Much smaller change and it would have satisfied the immediate driver. Rejected as the wrong
  shape rather than merely insufficient: it makes termination terminal for the *identity*, so
  re-onboarding forces either a duplicate party (fragmenting the AML history that ADR-0179
  exists to keep whole) or an un-auditable `TERMINATED → PENDING_KYC` back-edge on a supposedly
  terminal state. Sequential relationships on one durable identity is the model that makes
  re-onboarding ordinary. Noted as the fallback if D1 proves too large to land in one arc — see
  the phasing note below.
- **Operations runbook plus the existing per-account close.** Genuinely considered, because it
  is what happens today. Rejected: the notice-period timer, the tipping-off asymmetry and the
  residual-balance payout are each individually the kind of step that a human executes
  correctly ninety-nine times, and the hundredth is a complaint to the ČNB.
- **Model termination in the credit lifecycle (ADR-0215) and generalise from there.** Rejected
  as a sequencing choice, not a disagreement: ADR-0215 is `proposed/planned`, so there is
  nothing shipped to generalise from. This ADR deliberately mirrors its vocabulary — grounds,
  notice as a durable timer, evidence per transition — so the two converge rather than diverge.

## Consequences

**Positive**
- The immediate driver is solved without data destruction: a failed onboarding ends as
  `ABANDONED` and the customer re-onboards onto the same identity, keeping every document and
  screening result already collected.
- The PSD2 Art. 55 / PAD Art. 19 notice obligation becomes a durable timer instead of a
  calendar reminder, and the AMLD Art. 39 tipping-off constraint becomes a property of the
  ground rather than a thing the operator has to remember not to say.
- `closeAccount`'s empty-balance guard stops being an obstacle operators route around.
- Residual balances become reconcilable rather than stranded — a recurring source of
  ombudsman complaints at real banks.
- Party records stop accumulating permanently-`ACTIVE` non-customers, which today silently
  inflate every downstream customer count and campaign audience.

**Negative**
- This is a substantial change to the party aggregate, with a Flyway migration and a
  backfill: every existing party needs a synthetic relationship. Backfill correctness is the
  main delivery risk.
- `Party.status` becoming derived touches every consumer of party status across the fleet —
  admin-ui, onboarding-service's `PartyStage` projection, campaign audiences, customer-edge.
- Temporal dependency extends into party-service, which does not use it today.
- A wind-down that spans account, balance, ledger, billing, standing-order, card and
  delegation services has many partial-failure modes; each step must be independently
  resumable, which is why it is a workflow and not a REST call.

**Neutral**
- Customer-initiated closure (`CUSTOMER_REQUEST`) falls out of the same machinery for free.
  It is not the driver here but it is the same flow with a different ground.
- Dormancy and escheatment are adjacent and deliberately out of scope; the unclaimed-funds
  suspense account from D4 is the hook they will attach to.

## Compliance impact

- PCI DSS: not applicable — no cardholder data enters this flow; card *tokens* are cancelled
  via card-service's existing interface.
- DORA: not applicable — no change to ICT third-party dependencies or exit strategy.
- GDPR: engaged, and the point is the separation. Termination explicitly does **not** erase.
  Art. 17 erasure remains the separate mechanism already shipped, and remains subordinate to
  the AML and accounting retention duties recorded in ADR-0118. A terminated party becomes
  erasable when its retention clock expires, not when its relationship ends.
- PSD2: engaged — Art. 55 framework-contract termination and its minimum two-month notice
  period for bank-initiated termination is the requirement D2's `ORDINARY_NOTICE` row and D3's
  durable timer implement.
- CNB: engaged via the Czech payment-system act transposing PSD2 and the Payment Accounts
  Directive, and via AML customer-due-diligence duties. The evidence record in D5 exists so a
  supervisory review of a contested termination can be answered from the platform rather than
  from mailboxes.

## References

- ADR-0118 — GDPR data lifecycle, retention periods, erasure model (why erasure ≠ termination)
- ADR-0179 — party identity merge; `MERGED` semantics and the durable-identity argument
- ADR-0215 — loan termination and early-exit lifecycle (the credit-side analogue this mirrors)
- ADR-0212 — jurisdictional compliance packs as versioned effective-dated data (the D2 pattern)
- ADR-0101 / ADR-0120 — Temporal durable execution for money-path workflows
- ADR-0107 — sweep-to-close currency-pocket conversion (D4 step 4, FX dust)
- ADR-0133 / ADR-0214 — tamper-evident evidence chain and lifecycle evidence records
- ADR-0232 — delegated access grants (revoked in D4 step 1)
- `openbank-party-service/src/main/kotlin/com/openbank/party/domain/model/Party.kt` — `PartyStatus`
- `openbank-party-service/src/main/kotlin/com/openbank/party/application/usecase/PartyService.kt` — `resolveStatus`
- `openbank-account-service/src/main/kotlin/com/openbank/account/application/usecase/AccountService.kt` — `closeAccount`, `AccountNotEmptyException`, `KNOWN LIMITATIONS`
