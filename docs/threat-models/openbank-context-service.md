<!-- SPDX-License-Identifier: Apache-2.0 -->
# Threat model — openbank-context-service

- **Status:** Reviewed implementation, staged P0/P1 bounded pilot
- **Last reviewed:** 2026-09-13
- **Owner:** context-service CODEOWNERS
- **Related ADRs:** ADR-0030, ADR-0034, ADR-0303, ADR-0306, ADR-0308, ADR-0309

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
   transaction, ledger and ICT-incident topics. Consumers validate source/schema/version, cap event
   size, dead-letter invalid records and retain an idempotency ledger; source systems retain ownership.

## STRIDE analysis

| Threat | Control | Residual risk |
|---|---|---|
| Spoofed purpose or case | Request values must match an active server-side assignment; creation needs an admin maker and distinct admin checker, is limited to 31 days, and is audited | Maker and checker can still collude; organizational access review remains required |
| Tampered graph evidence | Kafka producer/consumer ACLs and mTLS, exact source/schema validation, mandatory source-issued revisions in production, stale/duplicate suppression and evidence references | P0/P1 has no signed event envelope; a compromised authorized producer can still emit false facts |
| Repudiated read | Durable audit records principal, case, purpose, action, root, outcome and policy version before disclosure; DB triggers reject application updates/deletes | Audit table needs export to the fleet tamper-evident audit service before production |
| Relationship disclosure | Role gate, assignment, OPA, namespace-specific fixed query, 100-node/200-edge bounds; complaint hops follow only explicit outgoing lifecycle and booking relations; denied requests return no counts | Broad assignment issuance could still expose linked cases |
| Resource exhaustion | Indexed bounded fixed-template queries of at most three directed hops, with hard result limits and 500 ms DB timeout; dedicated CNPG/resources; staged at zero until the 100 RPS plus payment-control workload gate passes | Hot roots need production-distribution evidence and per-principal rate limits before increasing bounds |
| Privilege escalation | `authz.enforce=true`; service and OPA both bind each action to its exact purpose; M2M identities are hard-denied; PDP outage returns 503 | Human maker/checker entitlements need periodic access review |

Complaint, domestic-payment, transaction and ledger producers use transactional outboxes and
database-backed aggregate revisions; ICT incident events also carry an explicit source revision.
Retained records predating those revisions require a measured compatibility replay. The staged
production manifest enables strict-only consumption before the first replica and keeps non-zero
replicas gated on a clean replay boundary plus workload evidence; the zero-replica deployment remains
suitable for synthetic replay and control validation.

## P1 privacy boundary

Complaint context may return opaque typed references and evidence links to assigned investigators.
Incident impact returns aggregate counts by node type. It never returns affected identifiers;
identifier drill-down requires a separate future action, assignment and audit trail.

## Invariants

1. No graph read occurs before assignment verification, current OPA allow and committed audit.
2. PDP failure, audit failure or missing assignment releases no graph data.
3. Historical validity explains evidence and never grants current access.
4. Queries are template-based and bounded; complaint payment, booking-transaction and ledger hops are
   directed, source/prefix/relation allow-listed, and there is no arbitrary graph query API.
5. Context ingestion never participates in payment authorization or ledger posting.
6. P0/P1 stores opaque source references and bounded labels, not raw customer or payment text.

## Rollback

Scale the context deployment to zero to stop routes and projections; source services and payment
paths continue independently. Before any real projection or audit record exists, migrations can be
rolled back in reverse order. After adoption, retain projection, assignment and audit tables for
evidence retention and rebuild a new generation instead of deleting the active one.
