---
date: 2026-07-19
decision-status: proposed
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [kyc, accounts, ledger]
summary: "Party-service gains a first-class merge operation (status MERGED plus merged_into, four-eyes gated, emitting PARTY_MERGED) distinct from GDPR erasure, because retiring a duplicate via erasure is a false compliance statement."
followup: "#1783 — the party.merge four-eyes gate is wired but AUTHZ_ENFORCE=false (advisory) fleet-wide on party-service, pending the two unrelated M2M allow-rules' 'would DENY' population being confirmed empty"
---

# ADR-0179 — Duplicate party identity merge

**Delivery note (2026-08-18).** The merge mechanism is shipped and has processed a live merge:
`PartyStatus.MERGED` + `merged_into` (V15), `POST /api/v1/parties/{id}/merge`, `PARTY_MERGED`
event and the fail-closed account guard (PR #1783, deployed via #1793). The four-eyes gate on
`party.merge` is wired but currently advisory — `AUTHZ_ENFORCE=false` on party-service, service-wide
— pending confirmation that the *other* two M2M allow-rules on this service
(`party.consent.update`, `party:resolve`) produce no "would DENY" surprises; `party.merge` itself
has no M2M caller today (`rules.yaml` confirms it). `transaction-service`'s companion
`POST /api/v1/transactions/merge-sweep` (PR #1789) is merged but has not yet been exercised on a
live path.

## Context

ADR-0072 established "1 person = 1 party" and defends it at *creation* time: a RČ blind index
(`POST /api/v1/parties/resolve`) plus a four-eyes case queue in pid-service whose verdict
`LINK_TO_EXISTING` routes an applicant onto the party that already exists.

Both mechanisms are pre-creation gates. Neither can act once two parties for the same natural
person are already live — and they do become live: a customer onboarding through a channel that
carries no RČ (or with a differing one), a channel that predates the blind index, or an operator
creating a party by hand all bypass the dedup key. The platform has no remediation path for that
state today.

The absence has a sharp edge. The only writer of `PartyStatus.CLOSED` is GDPR Art. 17 erasure
(`DELETE /api/v1/parties/{id}`), which anonymises PII and emits `PARTY_ERASED`. So the only way
an operator can currently "retire" a duplicate party is to record it as *the data subject
exercised their right to be forgotten* — a false compliance statement, destructive of the very
history the merge is meant to preserve, and acted on by every downstream consumer of that event.

The workaround available without any code change is worse than the gap: open matching currency
pockets on the surviving account, move the balance across as an ordinary customer payment, close
the source account, then erase the source party. The ledger then attests that one legal person
paid another and was subsequently erased. A duplicate-identity data-quality finding is routine;
a money movement that misrepresents what happened is an audit finding.

## Decision

We will add an explicit, first-class merge operation to party-service, distinct from erasure.

1. **`PartyStatus.MERGED`** joins the status enum, and a nullable `merged_into` (UUID, FK to
   `parties.id`) column records the surviving party. The pair makes the relation traversable in
   both directions and distinguishes "retired as a duplicate" from "erased on request".
   `deriveStatus` treats `MERGED` as terminal exactly as it already treats `CLOSED` — a merged
   party is never re-opened by a later KYC or AML signal.

2. **`POST /api/v1/parties/{id}/merge`** with body `{ "mergedIntoPartyId": "<uuid>", "reason": "..." }`,
   roles OPERATOR/ADMIN, authz action `party.merge`, gated on four-eyes approval (the ledger
   `ApprovalResource` pattern). Preconditions, all fail-closed: both parties exist, neither is
   already `MERGED` or `CLOSED`, the two ids differ, and the target is not itself merged into a
   third party (no chains — the caller resolves to the final survivor first).

3. **`PARTY_MERGED`** is published to the existing party status-changed topic, carrying both ids
   and the approval reference. PII is preserved on the retired row; nothing is anonymised.

4. **The balance transfer is an internal adjustment, not a customer payment** — but that
   distinction has to be *built*, and this ADR is where the gap is recorded rather than assumed.

   Two things turned out not to be true of the platform as it stands:

   - **`TransactionType.ADJUSTMENT` is inert.** `PaymentJournalFactory.buildLines` never reads
     `transaction.type`; it branches only on currency and on source/target nullity. DEBIT,
     TRANSFER and ADJUSTMENT produce byte-identical journals. Today the type is a label on the
     transaction row and nothing else, so posting the sweep "as an ADJUSTMENT" would buy exactly
     zero audit distinction from an ordinary payment.
   - **Neither `ledger.create` nor `transaction.create` is four-eyes gated.** The OPA verb list
     covers `post` and `reverse`, which no posting endpoint actually emits, and
     `authz.four-eyes.enforce` defaults to `false` in ledger-service regardless.

   So the sweep needs a distinguishable path of its own. We will add a dedicated operator-only
   action rather than reusing `transaction.create` — that action is on the M2M payment rails, and
   the OPA bundle's own guidance is that a verb may only join the four-eyes list when every
   fleet-wide caller of it is a human operator. The mechanical shape already exists and is
   correct: the `source != null && target != null` branch of `PaymentJournalFactory` posts two
   deposit-control legs, the control account nets to zero, and only the sub-ledger dimension
   moves — a bookkeeping correction between two records of one person, not a transfer of value
   between two persons. What is missing is the type carried through to the journal, the reason
   code, and the approval binding.

   This lands as a separate money-path change (2 approvals + threat model). Until it does, the
   merge endpoint's account guard is what prevents a half-finished merge: a duplicate with an
   open account cannot be retired at all.

Order is fixed and each step is separately auditable: approve merge case → open matching currency
pockets on the surviving account (pockets are an account-service aggregate; the balance projection
auto-creates a balance row but *not* a pocket, so skipping this leaves a balance with no owning
pocket — a silent structural inconsistency, not an error) → sweep per currency → close the source
account → merge the party.

Account closure does *not* check the balance ([ADR-0109](0109-customer-managed-currency-pockets.md)
option B), so the sweep must precede it. The merge endpoint therefore refuses a source party that
still owns any non-CLOSED account, putting the check where the domain can enforce it. That guard is
fail-closed: if account-service cannot be reached, the merge aborts, because "we could not ask"
must never resolve to "owns nothing".

## Alternatives considered

- **Reuse GDPR erasure to retire the duplicate** — zero new code. Rejected: it records a legal
  assertion that is false, anonymises history the merge exists to keep, and fires `PARTY_ERASED`
  at consumers that will treat it as a subject-rights event.
- **Rewrite `party_id` on the duplicate's rows in place** — appears to give "one clean history".
  Rejected: postings are append-only and reference the counterparty as booked; re-pointing them
  falsifies the ledger, breaks reconciliation, and destroys the audit trail. It is the operation
  an auditor is specifically looking for.
- **Leave the duplicate live and link the two via external ids** — the existing
  `linkKeycloakSub` path already attaches a `KEYCLOAK_ID` to a party. Rejected as a *merge*: it
  moves no accounts, retires nothing, and leaves two ACTIVE parties for one person, so every
  downstream aggregate (positions, limits, AML exposure, reporting) still double-counts. Retained
  as the correct tool for its own job — attaching a credential to an already-correct party.
- **Extend pid-service's `LINK_TO_EXISTING` verdict to act post-creation** — reuses the existing
  four-eyes queue. Rejected for this ADR: that queue models an *applicant* being routed before a
  party exists, and overloading it with a two-live-parties case conflates two different states.
  Revisit if operators end up wanting one queue.

## Consequences

**Positive**
- A duplicate can be remediated without a false GDPR statement or a falsified posting.
- Both identities stay queryable, and `merged_into` makes the relation explicit for reporting
  and for any downstream service holding a stale `partyId`.
- Aggregates over a merged person stop double-counting.

**Negative**
- Every service holding a `partyId` must decide how it follows `merged_into`. This ADR ships the
  party-service primitive and the event; consumer adoption is a follow-up per service and until
  it lands a stale `partyId` still resolves to the retired row.
- Merge is not reversible through the API. An erroneous merge is corrected by a compensating
  journal entry and a new party, not by an un-merge.
- Adds a second terminal status, so any exhaustive `when` over `PartyStatus` needs a new branch.

**Neutral**
- The four-eyes machinery is borrowed from ledger rather than generalised; extracting a shared
  approval component is deferred until a third caller wants it.

## Compliance impact

- PCI DSS: not applicable — no cardholder data in scope.
- DORA:    Art. 9 (data integrity) — replaces an ad-hoc manual remediation with a recorded,
           approved, replayable operation.
- GDPR:    Art. 5(1)(d) accuracy — this is the mechanism that makes a duplicated identity
           correctable. Explicitly *separates* the correction from Art. 17 erasure, which it
           currently has to impersonate; retained PII on the merged row stays under the
           surviving party's existing lawful basis and retention schedule.
- PSD2:    not applicable.
- CNB:     record-keeping — the retired party and its history remain retrievable for the
           statutory period; the merge itself is an audited, approved event.

## References

- [ADR-0072](0072-client-identity-unification.md) — "1 person = 1 party" and the creation-time dedup gate
- [ADR-0109](0109-customer-managed-currency-pockets.md) — currency pockets; closure without a zero-balance check
- [ADR-0030](0030-supply-chain-security-and-ssdlc-hardening.md) — threat-model requirement for money-path change
