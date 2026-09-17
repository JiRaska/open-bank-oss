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
2. The service verifies an active principal/case/purpose assignment in its database, then sends the
   server-derived `assignmentVerified=true` fact with principal roles and the root to the OPA
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
| Spoofed purpose or case | Request values must match an active server-side assignment; creation needs an admin maker and distinct admin checker, is limited to 31 days, and is audited | Maker and checker can still collude; organizational access review remains required |
| Tampered graph evidence | Kafka producer/consumer ACLs and mTLS, exact source/schema validation, mandatory source-issued revisions in production, stale/duplicate suppression and evidence references | P0/P1 has no signed event envelope; a compromised authorized producer can still emit false facts |
| Repudiated read | Durable audit records principal, case, purpose, action, root, outcome and policy version before disclosure; DB triggers reject application updates/deletes | Audit table needs export to the fleet tamper-evident audit service before production |
| Relationship disclosure | Role gate, assignment, OPA, namespace-specific fixed query, 100-node/200-edge bounds; complaint hops follow only explicit outgoing lifecycle and booking relations; denied requests return no counts | Broad assignment issuance could still expose linked cases |
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

The new event store also forces bank-scoped RLS and has no general graph traversal or
cross-case search API. Its event UUIDs and party/account references remain personal
data: legal retention, restriction and deletion handling must be completed before a
real-data rollout. The synthetic sandbox exercises do not establish those controls.

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
