---
date: 2026-09-19
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [payments, sca, authz, customer-edge]
summary: "delegation-service owns the business signing policy, signer groups, trusted payees and N-of-M approval requests; customer-edge holds a business payment until APPROVED and releases it to the rail once, by a single-use claim."
---

# ADR-0312 — Business payment signing: multi-signature approval and trusted payees

## Context

ADR-0284 D3 records representation mandates in party-service, including `authority`
(`SOLE`/`JOINT`) and `requiredSignatures` (`PartyMandateResource`, validated in
`PartyService.validateMandateThreshold`: SOLE ⇒ 1, JOINT ⇒ ≥ 2). Nothing on the money path reads
them. A payment made under `X-Acting-For` requires only that the human holds an ACTIVE mandate, so
a JOINT representative moves company money alone with their own SCA (#10281 item 2). That gap is a
design decision, not a patch, which is why it is recorded here.

Three adjacent decisions exist and none covers it:

- **ADR-0232** (delegated access) gave delegation-service an `approvalPolicy` enum
  (`SOLO | ANY_ONE | ALL | N_OF_M`, `V1__init_delegation.sql: chk_delegation_n_of_m`). Only `SOLO` is
  enforced; the others are refused with `400 APPROVAL_POLICY_UNSUPPORTED`
  (`openbank-delegation-service/src/main/resources/openapi.yaml`, `x-unsupported-values`). It
  governs a grant from one person to another, not the signing rule of a legal entity.
- **ADR-0155** (four-eyes) is a bank-staff maker/checker flow over `AuthorizeInterceptor` with no
  wired `ApprovalStore` in this service (`application.yaml: authz.four-eyes`). It does not
  count customer signatures against a mandate.
- **account-service `SigningRule`** (`SINGLE | JOINT_ALL | JOINT_ANY_TWO | OWNER_PLUS_ONE`,
  `AccountAuthorization.kt`) is persisted on the account (`AccountEntities.signingRule`) and read
  by no decision anywhere: `git grep -n signingRule -- '*/src/main'` finds only the entity, the
  mapper and the domain default. A second signing rule would be a second source of truth for the
  same question.

## Decision

We will:

1. **Make delegation-service the single owner** of `SigningPolicy`, `SignerGroup`,
   `TrustedPayee` and `ApprovalRequest` for a legal-entity party. With no stored policy the
   effective policy is *derived* from the live party-service mandates — SOLE ⇒ any one
   representative, JOINT ⇒ `requiredSignatures` of the representatives — and is not persisted
   until a change is approved.
2. **Hold business payments upstream of the rails, in customer-edge.** When the evaluated policy
   requires more than one signature, the edge consumes the initiator's SCA (dynamically linked),
   freezes the exact rail request body into an `ApprovalRequest` and answers `202
   PENDING_APPROVAL`; no rail is called. Payment services keep their state machines unchanged.
3. **Release exactly once.** On `APPROVED` the edge calls `release-claim`, an atomic
   compare-and-set `APPROVED → RELEASED` in delegation-service's database that returns a claim
   token and the frozen payload once; a second claim answers `409`. The edge posts the payload to
   the rail with `Idempotency-Key = approvalId` and reports `release-result`.
4. **Treat policy and trusted-payee changes as signed items.** `POLICY_CHANGE`, `PAYEE_ADD` and
   `PAYEE_REMOVE` requests need the strictest round of the current policy and take effect
   atomically when `APPROVED`. A payee is trusted only after that round completes.
5. **Resolve the SigningRule overlap:** delegation-service's `SigningPolicy` is the single source
   of truth for "how many people must sign a payment for this entity". account-service
   `SigningRule` is **deprecated** — no new reader may be added, and it is removed in a follow-up
   once nothing writes it. The ADR-0232 grant-level `approvalPolicy` values other than `SOLO` stay
   refused; business co-signing is not built on grants.
6. **Trusted payees are not an SCA exemption.** A trusted payee lowers the *number of
   signatures* to one; it never removes the initiator's SCA. This is deliberately not the PSD2
   RTS trusted-beneficiary exemption (RTS Article 13), which this platform does not apply.

Signature validity: the signer is an eligible representative whose mandate is re-checked LIVE with
party-service at signing and again at release; the same person counts once; the initiator's own
SCA is the first signature and the initiator is never a co-signer; each signature's SCA challenge
is dynamically linked to `approvalRequestId + payloadSha256` (and amount, currency, creditor IBAN
for a payment). Unsigned requests expire (default 72 h); an expired request never executes.
Lifecycle events are published through the transactional outbox on `delegation.approval-events`.

## Alternatives considered

- **Enforce the mandate inside each payment service** (domestic, SEPA, SWIFT). Rejected: four
  money-path state machines would each gain a PENDING_APPROVAL state and its own counting, and
  the threshold would be implemented four times against one register fact.
- **Extend account-service `SigningRule` and count there.** Rejected: a rule per *account* cannot
  express amount bands, signer groups or a trusted-payee list per *entity*, and account-service
  does not know the mandates; it would duplicate party-service's authority.
- **Use ADR-0155 four-eyes.** Rejected: it is a staff maker/checker over an interceptor, keyed by
  action, with no notion of an entity's representatives or of N > 2.

## Consequences

**Positive**
- A JOINT mandate is enforced on business payments; every signature is attributable to one
  natural person and bound to the exact payload.
- The rails are unchanged; release is idempotent end to end (DB CAS + rail Idempotency-Key).

**Negative**
- A held payment is not visible to the rails until released, so balance and mandate are
  re-validated only at release (the rail's normal validation); a release can fail after approval
  (`RELEASE_FAILED`, surfaced to every signer).
- delegation-service gains a second aggregate family and becomes a runtime dependency of business
  payments; an outage refuses new business multi-signature payments (fail closed).

**Neutral**
- Enforcement is by tests and OPA (`delegation_rest_ext.rego`, the customer-edge service account
  identified by `principal.id`), not a new CI gate: the guards are single code paths in
  delegation-service and each is proven by a sabotage test.

### Delivery check

`grep -c 'release-claim' openbank-delegation-service/src/main/resources/openapi.yaml` prints ≥ 1
and `grep -rn 'delegation.approval-events' openbank-delegation-service/src/main/resources/application.yaml`
prints the outgoing channel. Absent either, this ADR is not delivered.

## Threats

- **Double release** — two edge pods claim the same approval: DB compare-and-set, one winner,
  `409` for the rest; rail `Idempotency-Key = approvalId` as the second barrier.
- **Stale mandate** — a representative removed from the register after the request was created:
  eligibility re-checked live at every signature and at release.
- **Initiator self-cosign** — the initiator signing twice, or via a second device: distinct
  party ids, initiator excluded from co-signers.
- **Device keys bound to the entity** — an SCA credential enrolled to the company party could
  approve for it (#10281 item 1): signatures carry the human's party id, never the entity's;
  enrolment to non-natural persons is refused in sca-service.

## Compliance impact

- PCI DSS: not applicable — no card data is stored or processed by the approval flow.
- DORA:    not applicable — no change to ICT risk tooling or third-party arrangements.
- GDPR:    signer party ids and names are stored on approval requests as contract evidence under the existing delegation retention (5 years, `governance.yaml`).
- PSD2:    every signer performs SCA with dynamic linking to the frozen payload; the trusted-payee list is not used as an SCA exemption.
- CNB:     not applicable — no reporting obligation changes.

## References

- #10281 — business-profile SCA: entity-bound devices, JOINT mandates, deciding party
- ADR-0284 (representation mandates), ADR-0232 (delegated access), ADR-0155 (four-eyes)
- `docs/threat-models/openbank-delegation-service.md`
