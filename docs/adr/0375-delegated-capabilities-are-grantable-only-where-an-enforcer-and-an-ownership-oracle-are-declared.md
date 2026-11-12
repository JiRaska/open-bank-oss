---
date: 2026-11-12
decision-status: proposed
delivery-status: planned
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [authz, payments, cards, documents]
summary: "A delegation capability may be offered only where a declared enforcer and a resource-ownership oracle exist. Delegated authority becomes the first step of the ADR-0374 admission chain, fed by one shared projection."
---

# ADR-0375 — Delegated capabilities are grantable only where an enforcer and an ownership oracle are declared

Relates: ADR-0232 (delegated access — amends D3 and D7), ADR-0249 (dispositor model),
ADR-0374 (payment admission chain — this decision adds its first step), ADR-0312
(business payment signing), ADR-0223 (channel-agnostic authorization hardening),
ADR-0335 D2 (account ownership projection), ADR-0319 (contract-generated clients),
ADR-0162 (PAdES-sealed rendered documents), ADR-0021 (SCA settlement gate).

## Context

ADR-0232 built delegated access as a bounded context with a closed, deliberately
rail-agnostic capability vocabulary (D2) and assigned enforcement to each product
service's event-fed projection (D3). ADR-0249 then moved the cumulative ceiling into
delegation-service, rejecting per-rail counting in its own Alternatives section
because "one grant is spendable through domestic, SEPA, instant and cards". Three
months later the vocabulary has 18 values and the enforcement side has not kept up,
in a way no gate can see: a capability compiles, persists, publishes and renders in
the grant UI whether or not anything reads it.

Measured on `origin/main`, 2026-11-12.

**Capability census** — `git grep` over `*/src/main/*`, excluding delegation-service
itself: **9 of 18 capabilities have no production reference anywhere.**
`ACCOUNT_VIEW_DETAILS`, `ACCOUNT_DOWNLOAD_STATEMENTS`, `ACCOUNT_PROPOSE_PAYMENT`,
`ACCOUNT_MANAGE_BENEFICIARIES`, `ACCOUNT_MANAGE_LIMITS`, `CARD_VIEW_TRANSACTIONS`,
`CARD_MANAGE_STATUS`, `CARD_MANAGE_CHANNELS` — and `DELEGATION_MANAGE`, which is the
one legitimate member of that list, enforced by delegation-service as the owner of
the resource it governs. Eight unkeepable promises; `CARD_MANAGE_STATUS` and
`CARD_MANAGE_CHANNELS` sit next to the two card capabilities
`CardDelegationIntent` does enforce, so the file next door looks like the convention
is being followed.

**Payment rails** — in `customer-edge`'s `CustomerEdgeResource.kt`:

| shape | call sites |
|---|---|
| `resolveDebitAuthority(...)` — owner **or** delegate (ADR-0232 D3/D5) | **1** (domestic payments) |
| `extractOwnerPartyId(accountJson) != customer.partyId` — owner only | **10** |
| `reserveDelegatedSpend(...)` — the ADR-0249 D3 ceiling | **1** |
| `holdForApproval(...)` — ADR-0312 business signing | **6**, one per `PaymentRail` value |

One file enforces business multi-signature on six rails and delegated access on one.
A delegate initiating SEPA, SCT Inst, SWIFT or a standing order is refused with
`403 Debtor account does not belong to caller`: fail-closed, so no unauthorised money
moves, and the grantor is told nothing.

**Object sharing (ADR-0232 D7) is worse, and it is worse in an instructive way.**
`ResourceOwnershipClient.verifyOwnership` has one branch for all three object types:

```kotlin
DelegationResourceType.PAYMENT,
DelegationResourceType.STATEMENT,
DelegationResourceType.DOCUMENT,
-> OwnershipVerdict.UNVERIFIABLE
```

and `DelegationService.verifyResourceOwnership` refuses on `UNVERIFIABLE` —
"refusing to mint a grant over an unverified resource", correctly, per the ADR-0232
T1 threat model. So **no object grant of any type can be created in production.** The
reader half is built and unreachable: `CustomerDocumentResource.documentContent`'s own
KDoc records that a DOCUMENT grant "was until now a grant nobody could use" and
presents that as fixed, while the writer still refuses to mint one. Nothing caught the
gap because `DelegationServiceTest` offers a DOCUMENT grant against a mocked
`ownershipClient` and `SharedDocumentTest` mocks delegation-service's reply — both
halves green, the real path unjoinable. Same family as every other defect here that a
mocked port hid from its adapter.

Two further facts about objects, pointing in opposite directions:

- `CAPABILITY_MATRIX` permits `PAYMENT to setOf(OBJECT_READ)` and
  `STATEMENT to setOf(OBJECT_READ)`, and no route anywhere serves a shared payment or
  statement to a grantee — the edge's payment and statement reads are owner-only IDOR
  guards. The same unkeepable promise as the five payment rails.
- `Exposure` — redaction rules, `maxViews`, watermark, `allowDownload` — has **zero**
  production readers outside delegation-service's own model, entity and DTO, and
  delegation-service **knows**: `rejectUnsupportedExposure` refuses an offer carrying
  exposure ("this platform cannot yet transform the shared object or atomically enforce
  redaction, watermark or maxViews"), `rejectLegacyExposure` blocks reinstating a
  historical one, and the edge omits a legacy exposure grant from the shared list
  without fetching the document. That is not a defect. It is this ADR's rule already
  implemented — by hand, for one field, in one service.

And the one object path that does work in production is the one ADR-0232 D7 treats as
the special case: `ExternalDisclosure` CAS-counts views and enforces `maxViews`,
expiry, revoke and lock. A sealed PDF sent to a stranger has enforced limits; a share
between two customers of the bank cannot be created.

**ADR-0374 lands the missing structure.** Merged 2026-11-11, it decides that every
outbound payment on every rail passes one ordered admission chain owned by a library
module — rails supply facts and adapters and "do not reorder, skip or reimplement
steps" — with one decision type, one HELD state, and a step that cannot answer mapping
to HOLD rather than ALLOW. Its point 5 already says delegate limits "keep their owners
and are read through the same step". So the shared-enforcement question this ADR would
otherwise have to decide is decided; what is left is that the chain has no step for
*whose money this is*, and that the vocabulary has no contract binding a capability to
its enforcer.

## Decision

We will make a delegated capability a **contract with two declared halves** — an
enforcer and an ownership oracle — and place the money-path half inside the ADR-0374
chain rather than beside it.

**D1 — Delegated authority is the first step of the ADR-0374 admission chain.** A new
step **Authority** runs before Standing: may this actor debit this account — owner, or
an ACTIVE grant carrying the required capability for this amount. It is the cheapest,
most local, fully deterministic step, so it belongs first by that ADR's own ordering
rule, and a payment that must be refused costs no remote call. It returns the same
`ALLOW | CHALLENGE | HOLD | REJECT` type; a missing or stale projection is
`UNAVAILABLE`, which the chain maps to HOLD for outbound money. Authority is a separate
verdict from Limits: a delegate inside their ceiling with no grant is REJECT, and a
delegate with a grant over their ceiling is the Limits step's answer, not this one's.

**D2 — One event-fed projection, in the chain module, not one per rail.** This amends
ADR-0232 D3. The `delegation.granted|suspended|revoked|expired` projection moves into
the ADR-0374 chain module, which every rail already runs, so a rail joins with config
and a migration instead of the ~10 production files
`openbank-domestic-payment` carries today. The durable spend binding and the
reservation state consumer are absorbed the same way, under ADR-0374 point 11, and
`SpendReservationOperationType` gains one value per rail enabled. No synchronous
delegation-service call enters the money path, which is what ADR-0232 D3 refused and
this ADR keeps.

**D3 — The customer edge stops deciding authority on a debiting route.** The chain is
authoritative; two enforcement points would drift, and the edge's is the one that
cannot see the rail's state machine or the HELD transition. `resolveDebitAuthority`
retires from the debit path once D1 lands. The owner-only ownership comparison stays
exactly where it is correct — read routes, where it is the IDOR guard — and is
forbidden on a debiting route.

**D4 — A capability is grantable only where its enforcer is declared.**
`rules.yaml` gains `delegation_capability_enforcement`, keyed on the
**`(resourceType, capability)` pair**, each entry naming the enforcing module, the
enforcing function or chain step, and a measured status. The subject set is
`DelegationGrant.CAPABILITY_MATRIX`, so the gate fails in both directions: a pair the
matrix permits with no declared enforcer, and a declared enforcer for a pair the matrix
does not permit. delegation-service refuses to OFFER a pair whose status is
`unenforced` — generalizing `rejectUnsupportedExposure` from one field to the whole
vocabulary, and ADR-0249 D5's refusal of a ceiling-less `ACCOUNT_INITIATE_PAYMENT` from
one capability. A pair may be declared unenforced deliberately, with an issue
reference, the way `LOAN` is a deliberate absence in ADR-0232 D1; it then cannot reach
a grantor's screen.

**D5 — Every object type gets the ownership oracle account-service already has.** The
missing piece for ADR-0232 D7 is not the reader, it is `ResourceOwnershipClient` having
no answer. document-service, statement-service and the service owning a payment each
expose the same small ownership projection account-service exposes today
(`POST /api/v1/accounts/ownership-verifications`, action `account.verifyOwnership`,
ADR-0335 D2), consumed through a contract-generated client (ADR-0319). Fail-closed on
`UNVERIFIABLE` is unchanged — it is correct and this ADR does not touch it. Object
pairs become mintable in the order their oracle lands, and until then D4 keeps them off
the grant screen instead of letting them be offered and refused.

**D6 — Exposure is never enforced by decorating a live read.** A redacted,
watermarked, view-counted share is a **rendered sealed artifact** — document-service
renders it, PAdES-signs it (ADR-0162), and it is shared as a counted disclosure in the
shape `ExternalDisclosure` already enforces — never an `Exposure` constraint bolted
onto a live API grant, which is why `maxViews` on a streaming route could never be
atomic. Today's refusal therefore becomes a declared end state rather than a
provisional gap, and the party-recipient and external-recipient halves of D7 converge
on one mechanism instead of two.

**D7 — Cards: correct the gating first; only then ask whether to grow or shrink.** Card
controls stay in card-issuance and at the edge; they are not payment-admission steps and
D1 does not touch them. Applying D4's census to the three unenforced card capabilities
found that two of them are not missing features at all — they are **existing routes
guarded by the wrong capability**. `freeze`, `unfreeze` and `block` all run through
`cardAction`, and `PUT /cards/{id}/controls` toggles contactless, online, ATM and abroad;
all four were gated on `CARD_MANAGE_LIMITS`, so a vocabulary that distinguishes limits
from status from channels collapsed to one capability at the only place that reads it.
Those two are therefore corrected, not built: `CARD_MANAGE_STATUS` gates the status
actions and `CARD_MANAGE_CHANNELS` the channel toggles. Both card role presets grant all
five card capabilities, so no preset-based delegate loses anything; a hand-built grant
carrying limits alone loses freeze, which is the correction rather than a regression.

`CARD_VIEW_TRANSACTIONS` stays unenforced for a different reason, and the difference
matters: there is no per-card transaction route at the edge, so there is no call site
being guarded by the wrong capability. Enforcing it means building the route, which is
product work, and it is not papered over with a `CardDelegationIntent` member either —
the check endpoint that would consume one has no production caller, so the member would
be a dead branch. Dispute-service reading the card projection belongs with that route.

The general rule this yields: **before deciding whether an unenforced capability should
be built or dropped, check whether its route already exists and is gated on a neighbour.**
A census that skips that step proposes product work for what is actually a one-line
correction.

**D8 — ADR-0223 is unchanged, and this says why.** OPA keeps deciding channel and role
policy; `@Authorize` still runs first. The Authority step answers a different question
over data the sidecar does not hold and must not cache — grant state, resource
ownership, reserved headroom — and a cached permit would outlive the instant,
unilateral revocation ADR-0232 D4 promises. Recording this keeps "enforcement lives
only at the sidecar" from being read as violated by what was always a domain invariant.

## Alternatives considered

- **Extend ADR-0232 D3 as written — a projection and a domain guard in each rail and
  each product service.** The standing plan, with three months of evidence against it:
  ~10 production files per rail, one rail delivered, five outstanding. It also
  re-creates the problem ADR-0249 rejected, since a grant's headroom would be read from
  six places. Rejected on measured cost and on ADR-0374 point 1 deciding the opposite
  for every other control.
- **A parallel delegated-debit gate at the customer edge, over `PaymentRail`.** The
  first draft of this ADR, and attractive because the edge already holds the
  `PaymentRail` registry and ADR-0312's six-rail hold. Rejected once ADR-0374 landed:
  two enforcement points for one invariant drift, and the edge cannot see the rail's
  HELD state, so a held delegated payment would have no consistent release path.
- **Enforce the grant in the OPA sidecar only.** One point for every channel, MCP and
  PSD2 included, no chain step. Rejected because the decision needs the amount, the
  resource's owner and the remaining headroom: the sidecar would have to call
  delegation-service synchronously on the money path, which ADR-0232 D3 refuses, or
  cache grant state against ADR-0232 D4.
- **Narrow the vocabulary to what is enforced and delete the rest.** Honest and cheap,
  and it closes every unkeepable promise today. Rejected as the global answer —
  ADR-0249's disponent, ADR-0284's SME representation and ADR-0233's agent grantee all
  assume a delegate who can act — but it is the correct per-pair answer, and D4 is
  written so that choosing it is a declared act rather than a silence.

## Consequences

**Positive**
- A grant means the same thing on every rail, which ADR-0232 D2 and ADR-0249 both
  already claimed in prose.
- The unenforced-capability class stops being shippable. Today finding one took reading
  a service map and then a `git grep` over 18 enum values.
- Object sharing gets a reachable path for the first time, and the two halves of
  ADR-0232 D7 converge on the counted-artifact mechanism that already works.
- Authority as a chain step inherits ADR-0374's HELD state and four-eyes release for
  free, so a delegated payment that cannot be authorised has somewhere to go other than
  a 403.

**Negative**
- D1 adds a step to the chain before ADR-0374 has delivered the chain. The sequencing is
  therefore fixed: D4's registry and refusal can land first and independently; D1 and D2
  follow ADR-0374 delivery slice (A), not ahead of it.
- Moving the projection into the chain module makes a libs regression a fleet money-path
  regression. Every rail that enables it needs a real-DB integration test — the Panache
  assigned-`@Id` and Vert.x-context traps are already paid-for lessons here.
- D4 will refuse grants the app offers today; the card capabilities are the likely first
  casualties, so D7 has to land with D4 or the app's `CARD_MANAGE` preset breaks.
- D5 asks three services for a new endpoint each, and statement-service has no
  delegation code at all today.

**Neutral**
- No new service and no new topic. D5 adds one route per object owner; D4 adds a
  registry and a checker; D1/D2/D3 move existing code.
- The SCA gate, the `onBehalfOf` audit threading and the grant lifecycle are untouched.

### Gate

`delegation-capability-enforcement` (new, `.github/scripts/`, group `kotlin`): derives
its subject set from `DelegationGrant.CAPABILITY_MATRIX`, requires one
`rules.yaml: delegation_capability_enforcement` entry per permitted
`(resourceType, capability)` pair, and fails in both directions — a permitted pair with
no entry, an entry for a pair the matrix does not permit, an entry naming a file that
does not reference the capability, and an `unenforced` pair that has in fact gained an
enforcer. Lands `advisory` with the registry recorded from the census above, flips to
`enforced` in the PR that lands D4's refusal, so the two cannot disagree.

The enforced `enforcement-reachability` gate (#3615) is the precedent and the sibling.
Its `isAuthorizedForAmount` entry needs correcting either way: it reads
`status: unreachable` with a `why` dated 2026-08-03 saying "no money-moving service
reads a delegation grant at all", which the domestic path has since falsified. A
registry whose only entry has gone stale is the argument for widening its scope, not
against the instrument.

### Delivery check

D4 — the registry covers the matrix, in both directions, and the gate is enforced:

```
python3 .github/scripts/check-delegation-capability-enforcement.py --enforce
```
```
grep -A3 'id: delegation-capability-enforcement' .github/gates/gates.yaml | grep 'mode:'
```

D4/D7 — no grantable pair is unenforced. Expected: empty output.

```
python3 .github/scripts/check-delegation-capability-enforcement.py --list-unenforced
```

D5 — no object type answers `UNVERIFIABLE` by construction. Expected: no match, and the
`when` in `ResourceOwnershipClient` has a branch per `DelegationResourceType` value:

```
git grep -n 'DelegationResourceType.DOCUMENT,' -A2 -- '*/infrastructure/client/ResourceOwnershipClient.kt' | grep UNVERIFIABLE
```

D3 — the owner-only comparison is gone from every debiting route. Expected: the count
equals the number of read routes that use it (10 today, all of them; the figure drops as
debit routes move to the chain and the gate in D4's PR replaces this grep):

```
git grep -c 'extractOwnerPartyId(accountJson) != customer.partyId' -- '*/rest/CustomerEdgeResource.kt'
```

D1 — Authority is a step of the chain, not an edge branch: the chain module declares it
in its ordered step list, and `resolveDebitAuthority` has no production caller (which
the `enforcement-reachability` registry then records).

## Compliance impact

- PCI DSS: not applicable — no PAN, PIN or cardholder data is read or moved; ADR-0249
  D5's refusal of delegated PAN reveal is unchanged.
- DORA: not applicable as a distinct engagement — this changes where existing controls
  run. Criticality tiers and RTO/RPO targets are unchanged.
- GDPR: D5 and D6 change how a shared document reaches a recipient. A sealed rendered
  artifact carries the recipient's identity in its watermark by design, which is a
  purpose the grantor chooses; the grantor's per-delegate activity feed (ADR-0232 D6)
  gains the rails D1 covers, so a delegate's payment on any rail becomes visible to the
  account holder. No new personal data is collected and no new recipient is introduced.
- PSD2: not applicable — a delegate is a bank customer acting on a shared account, not a
  third-party provider; nothing here touches the XS2A surface or TPP consent. SCA stays
  on the initiating delegate's own device per ADR-0021 and ADR-0232 D5.
- CNB: not applicable — no reporting obligation and no product term changes; the
  four-eyes flag on high-value grant creation (ADR-0232 D4) is unchanged.

## References

- ADR-0374 — payment admission chain; point 1 (one chain, shared code), point 5
  (delegate limits read through the Limits step), point 11 (shared code in libraries).
  D1 adds its first step.
- ADR-0232 — delegated access; D2 the vocabulary, D3 the enforcement assignment D2
  amends, D4 unilateral revocation, D7 the object and external-disclosure halves D5 and
  D6 reconcile.
- ADR-0249 — dispositor model; D3 the central ceiling and the Alternatives row rejecting
  per-rail counting; D5 the refusal this ADR's D4 generalizes.
- ADR-0312 — business payment signing; `PaymentRail` and its six-rail hold, the
  measurement that exposed the asymmetry.
- ADR-0335 D2 — the account ownership projection D5 replicates per object type.
- `DelegationGrant.CAPABILITY_MATRIX` — the subject set of D4's gate.
- `ResourceOwnershipClient.verifyOwnership` — the `UNVERIFIABLE` branch D5 removes.
- `DelegationService.rejectUnsupportedExposure` — the hand-written instance of D4's rule.
- `rules.yaml: enforcement_reachability` and the `enforcement-reachability` gate (#3615).
