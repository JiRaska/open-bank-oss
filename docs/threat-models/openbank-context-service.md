<!-- SPDX-License-Identifier: Apache-2.0 -->
# Threat model — openbank-context-service

- **Status:** Reviewed implementation, staged P0/P1 bounded pilot
- **Last reviewed:** 2026-09-17
- **Owner:** context-service CODEOWNERS
- **Related ADRs:** ADR-0030, ADR-0034, ADR-0303, ADR-0304, ADR-0306, ADR-0308, ADR-0309

## Scope and assets

The service stores typed references and relationships copied from source-owned events. It does not
store raw payment narratives, names, IBANs, device fingerprints or embeddings in the P0/P1 slice.
The most sensitive assets are the relationship graph, effective-time case assignments and durable
read audit. It is outside every synchronous payment path.

## Trust boundaries

1. An authenticated investigator supplies a case id, purpose and root reference. These values are
   claims, not authority.
2. The service verifies an active principal/case/purpose/**exact root** assignment in its database, then sends the
   server-derived `assignmentVerified=true` and `rootScopeVerified=true` facts with principal roles and the root to the OPA
   sidecar. OPA is mandatory and the service fails closed.
3. The service commits an allow/deny/unavailable audit record before an allowed graph result is
   released.
4. Projection data arrives asynchronously over mTLS Kafka from ACL-scoped complaint, domestic-payment,
   transaction, ledger, clearing, SEPA-payment and ICT-incident topics. Consumers validate
   source/schema/version, cap event size, dead-letter invalid records and retain an idempotency ledger;
   source systems retain ownership.

## STRIDE analysis

| Threat | Control | Residual risk |
|---|---|---|
| Spoofed purpose, case or root | Request values must match an active exact-root server-side assignment; creation needs an admin maker and distinct admin checker, is limited to 31 days, and is audited. Historical null-root grants authorize no graph read | Maker and checker can still collude; organizational access review remains required |
| Tampered graph evidence | Kafka producer/consumer ACLs and mTLS, exact source/schema validation, mandatory source-issued revisions in production, stale/duplicate suppression and evidence references | P0/P1 has no signed event envelope; a compromised authorized producer can still emit false facts |
| Repudiated read | Durable audit records principal, case, purpose, action, root, outcome and policy version before disclosure; DB triggers reject application updates/deletes | Audit table needs export to the fleet tamper-evident audit service before production |
| Relationship disclosure | Role gate, exact-root assignment, independent OPA scope check, namespace-specific fixed query, 100-node/200-edge bounds; complaint hops follow only explicit outgoing lifecycle and booking relations; denied requests return no counts | Approved root access still exposes the permitted bounded neighborhood; linked-case filtering needs continued review |
| Resource exhaustion | Indexed bounded fixed-template queries of at most three directed hops, with hard result limits and 500 ms DB timeout; dedicated CNPG/resources; expansion of the pilot requires the 100 RPS plus payment-control workload gate | Hot roots need production-distribution evidence and per-principal rate limits before increasing bounds |
| Privilege escalation | `authz.enforce=true`; service and OPA both bind each action to its exact purpose; M2M identities are hard-denied; PDP outage returns 503 | Human maker/checker entitlements need periodic access review |

Complaint, domestic-payment, transaction, ledger, clearing and SEPA-payment producers use
transactional outboxes and database-backed aggregate revisions; ICT incident events also carry an
explicit source revision.
Retained records predating those revisions require a measured compatibility replay. The manifest enables strict-only consumption and declares one replica. A clean replay boundary
and workload evidence remain prerequisites for expanding the pilot; readiness is not evidence
that either gate passed.

## P1 privacy boundary

Complaint context may return opaque typed references and evidence links to assigned investigators.
Incident impact returns aggregate counts by node type. It never returns affected identifiers;
identifier drill-down requires a separate future action, assignment and audit trail.

### Complaint revision history

Complaint corrections retain source-versioned graph-input snapshots with opaque account,
transaction and dispute IDs, event time, receipt time and a normalized graph-input digest.
Same-revision conflicts roll back the transaction; older deliveries cannot replace the
latest current projection. Append-only triggers and forced bank-scoped RLS protect the
snapshot table, and the reader sets its scope transaction-locally with the same SQL timeout.
Historical reads still require a current exact-root assignment, purpose, OPA and durable
disclosure audit. They cannot pivot into another complaint through a shared transaction.

The selected complaint revision is effective at source event time; it does not prove what
was known then. Pre-migration overwritten evidence cannot be backfilled from current rows,
and linked payment projectors still require their own historical reproducibility proof.
This increases retained restricted references: governed retention/restriction/erasure and
replay operations remain production prerequisites. No bulk history export is introduced.

Normalized payment/booking/rail nodes use a shared append-only history and event digest store
with forced bank-scoped RLS. A changed event node set fails before current-state deduplication;
older deliveries are retained without replacing the latest current materialization. Source-owned
nodes and references are explicitly distinct, and a future owning-source fact cannot erase an
earlier reference. Historical lookups use scoped indexed top-one candidates for at most 100 keys,
retain SQL and caller timeouts, and suppress dangling links when a node's history is unavailable.
No raw source payload is retained. Stable edge-history correctness and the increased history
retention footprint remain separate production acceptance requirements.

## Delegation source history

The authority-history endpoint adds root-scoped assignments and an append-only source observation
store. Current COMPLIANCE/ADMIN role, exact delegation root, case and purpose must pass before
OPA and durable audit. Neither an old assignment nor a historical grant permits current access.
Read audit records both requested event time and knowledge cutoff. The current source observation
is not proof that a specific business action was authorized: action authorization remains UNKNOWN.

Kafka lifecycle revisions are nonnegative (the initial offer is revision zero). Same-revision
identical replay is idempotent; conflicting content fails. Known spend-reservation events share the
topic and are ignored with a distinct metric; unknown or malformed lifecycle events fail into the
configured DLQ. Projection retains only allowlisted identifiers and authority conditions, excluding
free text and credentials. Producer compromise remains a source-evidence risk.

History storage forces bank-scoped RLS, tested with a nonowner role that has neither SUPERUSER nor
BYPASSRLS. The repository sets scope transaction-locally and limits SQL execution time. Production
credentials must retain those role restrictions: RLS does not constrain a database superuser.
Queries return at most 100 observations, disclose truncation, and never call source services.
Late arrivals preserve earlier knowledge-cutoff results. Audit/history triggers prohibit normal
application mutation; governed retention, restriction and erasure workflows remain required before
expanding to real personal data. This slice introduces neither bulk export nor ownership inference.

## AML source-case evidence boundary

Only `aml.case.created.v1` and `aml.case.status_changed.v1` from the AML transactional
outbox are admitted. The event ID and type come from broker headers, and the partition
key must match the body case ID. The append-only projection preserves event time and
recording time, rejects conflicting event ID replay, and indexes explicit party/account/
transaction references. Customer references, matched names, free-text reasons and
analyst identities are not projected. A shared reference is an investigative lead,
never a finding or automatic adverse decision.

The AML read path requires COMPLIANCE/ADMIN, a maker/checker-approved assignment for the
exact `aml-case:<uuid>` root, the AML purpose, OPA allowance and durable audit before
querying the projection. It then reads the current case ID and status directly from the
owning AML service with the investigator's authenticated bearer and a bounded, uncached
request. The source applies its own roles and OPA policy; no shared M2M identity gains
case-read permission through this lens. A terminal,
missing, mismatched, malformed or unreachable source cannot release evidence. This check
prevents Kafka lag and timestamp ties from treating a stale projected OPEN as current
authority. A live status check still has a time-of-check/time-of-use race if a case closes
immediately afterward; the response contains source observations, not a synchronous AML
decision. Source-case updates and investigative assignments remain separate controls.

The network endpoint discovers only candidates with the investigator's own approved
case-root assignment, then repeats OPA, durable read audit and current-source checks
for each candidate. Revocation between discovery and authorization omits that case.
Candidate source or PDP outage fails the entire response; no unassigned case ID or
count is returned. The endpoint caps candidates at four, so an empty or short result
is not a completeness claim. Equal identifiers may be investigative leads only.

The new event store also forces bank-scoped RLS and has no general graph traversal or
unrestricted cross-case search API. Its event UUIDs and party/account references remain personal
data: legal retention, restriction and deletion handling must be completed before a
real-data rollout. The synthetic sandbox exercises do not establish those controls.

## Proposed corporate KYC boundary (not deployed)

ADR-0305's corporate lens is gated on a durable KYB source observation and a reviewed
data path. The present `/ubo` answer is not historical evidence. The company-scoped PSC
record reference and a corporate registration number with jurisdiction may support
reviewed entity resolution; matching names or a PSC notification date must never create
a person identity or an ownership-effective date. Incomplete pages, restricted identities
and source errors remain unknown. A later corrected observation must not silently erase
what an earlier analyst actually saw at decision time.

The current `openbank.kyb.events` topic has onboarding and analytics consumers and is
not an approved path for owner-level evidence. A future reference-only event requires a
separate topic, KYB-only producer ACL, context-only consumer ACL, isolated DLQ and bounded
retention. Its contract must exclude names, birth dates, addresses and free text. Context
must fetch the versioned observation through a service-authorized API, enforce case and
field policy before disclosure, and rebuild from the durable KYB source after Kafka
retention expires. Topic/ACL/consumer inventory, payload tests, deletion/restriction
propagation and an egress review are required together before enabling the producer.

This design does not authorize owner evidence ingestion or change the P0/P1 invariant
that their projection stores only opaque references.

## Fraud case reference ingress

The dedicated Fraud topic contains only an event type, case ID, revision and event
time. Context checks the broker topic, partition key, event identity/type headers,
the exact four-field payload and lifecycle revision before inserting an append-only,
bank-scoped reference row. The consumer's own DLQ and Kafka group are isolated from
payment, AML and KYB streams. Replay is idempotent; a conflicting event ID or case
revision fails instead of rewriting prior knowledge. PostgreSQL forces bank-scoped
RLS, and the application sets scope and statement timeout within each transaction.
No read endpoint or inferred customer edge is introduced by this ingestion step.

Context now exposes a data-free 204/403/503 access decision for one exact Fraud case
root. It requires the investigator's current root assignment, FRAUD_INVESTIGATION
purpose, human-admin policy allow and committed read audit. Fraud passes the human
bearer to this decision endpoint before its source-owned evidence route returns any
account or counterparty identifier. The Fraud network lens now first authorizes and
read-audits the exact root, then fetches its current OPEN source associations with
the investigator's bearer over HTTPS. Candidate discovery uses only the same
investigator's currently valid Fraud case assignments and is limited to four case
IDs. Each candidate receives a separate policy decision, committed read audit, and
current OPEN source check before identifiers are compared. Only same-role account
or counterparty UUID equality creates a visible edge; score identity is source
evidence but never a cross-case match. A candidate denial is omitted; source,
policy, audit or reference-store failure fails the whole request. Truncation is
disclosed, and a missing edge is not a completeness or innocence claim. A case
pointer or exact identifier match is a lead, not a finding or permission to inspect
an unassigned customer. Context stores no account/counterparty values from this feed.
The source case and outbox remain authoritative if the topic or Context consumer
lags. A malformed or unauthorized broker record is nacked to the dedicated DLQ.

## Lending source evidence

The optional single-loan guarantee view reads evidence from Lending only after
Context verifies the human investigator's exact loan assignment, purpose and
policy. It forwards that investigator's bearer token, requests at most 100 facts,
validates the returned loan ID and evidence references, limits concurrent source
calls, records disclosure references, and returns `no-store`. Shared guarantors
do not authorize a read of another loan. Source outages and invalid responses
fail closed. The view remains disabled by default; activation requires a declared
Context-to-Lending network edge and a trusted source connection. The Kafka
reference pointer is not used by this view and cannot establish a guarantee.

## Invariants

1. No graph read occurs before assignment verification, current OPA allow and committed audit.
2. PDP failure, audit failure or missing assignment releases no graph data.
3. Historical validity explains evidence and never grants current access.
4. Queries are template-based and bounded; complaint payment, booking, clearing, return and ledger
   hops are directed, source/prefix/relation allow-listed, and there is no arbitrary graph query API.
5. Context ingestion never participates in payment authorization or ledger posting.
6. P0/P1 stores opaque source references and bounded labels, not raw customer or payment text.

## Rollback

Scale the context deployment to zero to stop routes and projections; source services and payment
paths continue independently. Before any real projection or audit record exists, migrations can be
rolled back in reverse order. After adoption, retain projection, assignment and audit tables for
evidence retention and rebuild a new generation instead of deleting the active one.


Generation replay retains unknown legacy ledger provenance as NULL and never assigns it to an
active generation. Retained legacy incident digests are checked before a new generation write by source aggregate
and revision; a conflict rolls back without graph or ledger rows. Missing legacy digests remain
unverifiable rather than being represented as verified. New idempotency identities, incident revision constraints and ORM edge
identities include bank and generation. V19 requires a drained, coordinated writer transition;
old binaries must not run against the new conflict targets. Replay still requires controlled
source retention and generation configuration and does not independently grant access to any
graph. Existing authorization and bank-scoped readers continue to apply.


Relationship observations use the same bank/generation boundaries as node history, with forced
RLS and append-only event/edge tables. Event-level edge-set hashes reject changed or omitted
relationships on replay. Historical selection applies source/prefix/relation allowlists before
result limits and returns evidence/time/version from one eligible observation. Missing producer
withdrawal semantics must never be interpreted as proof that a relationship is currently valid
or revoked; these are historical source observations.


Relationship history coverage on current edges is derived metadata, scoped by bank, generation,
namespace and complete source relationship identity. Updates preserve original validity and
evidence, retain the earliest observation time under concurrent insertion, and commit with the
verified observation. Backfill sets/restores transaction-local bank scope for forced-RLS reads.
Governed history removal must rebuild coverage before reading resumes; stale coverage must not
stand in for evidence that has been deleted.
