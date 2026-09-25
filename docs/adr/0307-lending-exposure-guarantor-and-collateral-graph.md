---
date: 2026-09-13
decision-status: accepted
delivery-status: partial
followup: "#10234 — activate an authorized Lending writer and dedicated reference publisher, audited case-scoped read, Context projection, UI and load qualification"
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [lending, compliance, authz, admin-ui]
summary: "Lending uses an effective-dated exposure lens on the shared context graph, keeping borrower, guarantor, collateral and valuation roles distinct while preventing double-counted or unauthorized totals."
---

# ADR-0307 — Lending exposure, guarantor and collateral graph

## Context

Credit officers and risk reviewers need to see which facilities share borrowers,
guarantors and collateral and how those links affect exposure. A relationship diagram
is useful, but a graph can double-count syndicated/shared facilities or collateral,
mix currencies and valuations, and expose another borrower's financial information.

This lens is **P3**. It follows the P0 controls and P1/P2 lenses because it combines
money-path data, multi-party financial exposure and calculated values. Its shared-stack
reuse is agreed now; release waits for calculation reconciliation and two-person review.

## Decision

We will add a `LENDING_EXPOSURE_REVIEW` lens to ADR-0303's shared `context-service`.
Lending-service and authoritative accounting/risk sources remain owners. The context
projection stores references and effective-dated observations, not a new loan book.

The ontology distinguishes borrower, co-borrower, guarantor, facility, contract,
collateral asset, collateral allocation and valuation:

- party —[BORROWER/CO_BORROWER/GUARANTOR_OF]→ facility;
- facility —[SECURED_BY]→ collateral allocation —[ALLOCATES]→ collateral;
- collateral —[VALUED_BY]→ immutable valuation observation;
- facility —[REPORTED_AS]→ authoritative exposure snapshot.

Guarantee cap, currency, seniority, percentage and validity belong on the guarantee
edge. Collateral allocation carries secured amount, currency, priority and validity.
A physical asset is one node even when allocated to several facilities. Exposure and
coverage totals are calculated only from a declared snapshot, valuation basis and FX
rate/time. Results show formula and contributors and refuse totals when required inputs
are missing, stale or inconsistent. They do not silently net, convert or double-count.

OPA checks risk/lending role, assigned portfolio or case, legal entity/borrower scope,
edge and field classification, purpose and effective date. A user allowed one facility
does not automatically see another borrower sharing a guarantor or collateral. Such a
node may appear as a protected overlap indicator only when policy explicitly permits
that aggregate; names and amounts remain hidden. Detailed expansion requires a new
decision and audit. Export and portfolio-wide search use separate approval.

Interactive traversal is two hops/request and bounded by ADR-0303. Portfolio aggregation
is an asynchronous snapshot job, never an interactive graph traversal. Query and worker
pools have quotas below payment/ledger workloads. Cache keys include portfolio, purpose,
policy version, snapshot/effective date, currency basis and projection generation.

Acceptance reconciles every displayed exposure/coverage figure to authoritative test
snapshots and covers shared collateral, partial guarantees, co-borrowers, currency
conversion, valuation expiry, corrections, cycles, merged parties, unauthorized overlap,
revocation, missing sources and 10× workload tests. Production pilot is read-only for a
named portfolio and requires human credit/risk and security review.

## Source readiness and migration sequence

The current Lending source has a borrower `partyId` on each loan and an approved
`collateral` row tied to exactly one `loan_id`. That row has type, description,
market value, currency, haircut and valuation time. It has **no verified shared
asset identity**, secured-allocation amount, allocation priority or independent
valuation version. No guarantor party or guarantee cap is stored in this service.
Searching equal descriptions, collateral types, amounts or party names must not
create graph edges. The existing collateral value contributes to IFRS 9 LGD;
repurposing or summing it as shared-asset coverage would alter a money-path
calculation and risk double counting.

The source transition is expand-first and source-owned:

1. Add an immutable, server-assigned asset identity backed by a reviewed source
   reference, with the source register's two-letter jurisdiction and a versioned
   identity-evidence hash. This jurisdiction identifies the evidence source, not
   the physical asset's location. Corrections keep a stable canonical asset ID and
   advance one approved revision at a time; allocations attach only to that
   canonical identity, so a corrected observation cannot split one physical asset
   into multiple graph nodes. A canonical root is unique for the verified
   jurisdiction/register/record reference tuple. Equal descriptions or references
   from different registers do not imply the same asset. A new
   allocation references both that asset and an existing approved collateral row
   for one loan. Its secured amount, currency, priority and effective interval
   are explicit; an asset is shared only when two independently approved
   allocations refer to the **same verified asset ID**. Old collateral rows
   remain unlinked and are reported as `IDENTITY_UNKNOWN`, never auto-grouped.
2. Add guarantee contracts separately. Each names a verified guarantor party,
   one facility, an explicit cap and currency, seniority and effective interval.
   Registration and approval use different authenticated people. A guarantee
   of type `CollateralType.GUARANTEE` in the existing table is not by itself a
   guarantor identity or enforceable guarantee contract.
3. Persist immutable valuation observations and source corrections. A current
   value is usable only with an approved valuation basis and unexpired effective
   date. Corrections append a new version and a reviewed supersession link; they
   never rewrite evidence that an earlier reviewer saw.
4. Expose a case/portfolio-scoped Lending read contract and only minimized
   reference events to Context. Context authorizes each facility and each
   cross-borrower expansion separately. The source read returns provenance and
   incomplete-data reasons before any graph edge is drawn. No arbitrary
   portfolio-wide traversal is introduced.
5. Reconcile any proposed exposure or coverage figure against the loan book,
   authoritative accounting/risk snapshot and a declared FX rate/time. Until
   every contributor is available and reconciled, the UI shows the topology
   and `TOTAL_UNAVAILABLE`, not a partial or currency-blind total.

Mixed-version rollout leaves the existing `CollateralUseCase`, IFRS 9 LGD and
ledger postings unchanged. New writers are disabled until their schema, maker/
checker policy, negative tests and bounded read API are ready. Verify old loan
and provisioning flows before and after expansion; verify new rows cannot be
read through a non-approved or cross-portfolio path. Rollback disables the new
writer and Context consumer while retaining approved source evidence and audit
history. Dropping populated evidence tables is a separate retention decision,
not a deployment rollback. A 1×/10× synthetic portfolio with one asset shared
across facilities, partial guarantees and mixed currencies is required before
the P3 pilot. Flyway V20 is the **schema-only expand stage** for the four fact
types; it enforces separate proposal/decision actors, immutable decided facts,
approved matching legacy collateral on an allocation insert, and a locked
recheck at allocation approval. A proposal whose collateral was released can
be rejected but cannot be approved. V20 alone does not deliver the P3 lens.
An internal guarantee writer now validates loan, Party and signed Document
evidence on proposal and approval, and persists an approval pointer atomically
with the decision. It is disabled by default and has no HTTP route, dedicated
Kafka publisher or credential; the existing broad Lending publisher refuses its
event type. There is still no case-scoped source read contract, Context projector,
UI or measured portfolio workload.
Separately, the existing Customer 360 credit-application overlay uses an optional
database-bounded `limit` on the party application list. Omitting the parameter
retains the existing full-list contract. A `(party_id, created_at DESC, id DESC)`
index supports the newest-first bounded query; on a large live table it must
be prebuilt concurrently before the Flyway migration so startup does not build
it under write load. The graph requests one extra row to mark truncation.
This overlay is not the case-scoped Lending exposure read contract in step 4.
The database checks proposal/decision separation and local referential lineage.
ADR-0311 preserves ADR-0152's single-bank-per-deployment boundary for Lending. V20 therefore uses local IDs
and foreign keys without a per-row bank dimension; the existing `loan` and
`collateral` tables follow the same boundary. A writer must derive the bank
identifier for outgoing Context references from trusted deployment configuration,
never from proposal data, and must reject a mismatch with Document's server-stamped
provenance. The schema cannot establish
that a guarantor party is verified, an asset identity is unique across documents,
or a document hash matches the authoritative file.
The future source adapter must verify these with their owners and recheck the
current collateral status at publication/read time. No row in V20 alone is
eligible to become a Context edge.

### Source proof boundary for the first writer

The writer must derive the outgoing Context `bank_scope` from the Lending
deployment, never from the proposal body or a tenant claim. On both proposal and approval it
resolves the loan/collateral in Lending, the guarantor in Party, and the cited
document in Document. A missing, archived, mismatched or unavailable source
fails closed; it is not converted into a graph fact. The document check must
compare `documentId`, expected SHA-256, case/loan reference and bank scope in
one purpose-limited operation, returning only a match decision. Lending must
not use the generic document metadata/content endpoints for this check: they
disclose more than the decision needs, and the current shared backend client
does not identify Lending uniquely. Provision a dedicated Lending service
identity and allow only this action in the Document policy. Document compares
its server-assigned `documents.bank_scope` (nullable for pre-migration rows),
while Lending resolves the loan in its deployment-local book and stamps the
configured bank identifier. Keep the document bytes
and free text out of the graph event. The fact's
`source_sha256` pins the exact document version the checker verified, so a
later document change does not retroactively alter what the checker saw.
For a guarantee, the dedicated boolean proof endpoint checks a `SIGNED`
document's post-seal SHA-256, the loan `caseRef`, guarantor `partyRef` and bank
scope. Old documents with a null bank scope fail closed; JSON metadata is never
used as bank authority. The realm template declares a separate
`openbank-lending-graph` client with only `ROLE_LENDING_GRAPH_PROOF`; the endpoint
checks both that role and its exact principal. It remains unusable in a
deployment until the credential is provisioned and available to Lending. The
internal writer stays disabled until that credential, a case-scoped route and
dedicated reference channel are reviewed and deployed.
Party also offers a boolean-only, purpose-limited guarantor identity check to
that exact client. It returns true only for a real active customer with approved
KYC and cleared AML; absence or ineligibility returns false. It proves neither
consent nor the loan-specific guarantee, which still needs the signed Document
check and separate maker/checker decision. A source outage propagates as failure,
never as a verified identity.

Approval then rechecks current source state in the same logical decision flow
and emits the versioned, reference-only event through Lending's transactional
outbox. Context consumes it idempotently, preserves effective and recorded time,
and reads detail through the source's case-scoped API under a separate OPA
decision. Until the dedicated credential, source check, writer and outbox exist,
the V20 rows must not be projected or presented as verified links. This
deliberately leaves the schema-and-proof stage dark rather than inventing evidence.

## Alternatives considered

- **Compute totals from visible canvas nodes:** rejected; pagination/authorization would
  make totals incomplete and manipulable.
- **Use graph paths as accounting exposure:** rejected; topology is not a valuation rule.
- **Separate lending graph store:** rejected without a measured shared-stack limitation.
- **Deliver alongside the first pilot:** rejected due to financial and cross-party sensitivity.

## Consequences

**Positive**
- Shared guarantees/collateral become explainable without copying authoritative ownership.
- Calculation provenance prevents graph topology from masquerading as exposure accounting.

**Negative**
- Correct aggregation needs effective-dated valuations, allocations and reconciliation.
- Field/aggregate filtering is complex when assets or guarantors span portfolios.

**Neutral**
- Lending decisions, limits, provisioning and ledger postings remain in their owners.

## Compliance impact

- PCI DSS: not applicable unless a source incorrectly introduces cardholder data; such fields are denied.
- DORA: batch and interactive workloads require isolation and recovery evidence.
- GDPR: guarantor/co-borrower financial links require purpose limitation and minimization.
- PSD2: not applicable — the lens grants no account or payment capability.
- CNB: calculated exposure must reconcile to authoritative reporting inputs; no new return is created.

## References

- [ADR-0303](0303-banking-context-graph-and-authorized-hybrid-retrieval.md)
- [ADR-0308](0308-effective-time-authorization-evidence-graph.md)
- [ADR-0311](0311-deployment-bank-provenance-for-context-evidence.md)
- [ADR-0028](0028-lending-bounded-context.md)
- [ADR-0037](0037-anacredit-credit-exposure-reporting.md)
- [Implementation roadmap #9945](https://github.com/JiRaska/open-bank-oss/issues/9945)
