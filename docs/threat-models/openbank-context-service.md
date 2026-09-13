<!-- SPDX-License-Identifier: Apache-2.0 -->
# Threat model — openbank-context-service

- **Status:** Draft, P0/P1 bounded pilot
- **Last reviewed:** 2026-09-13
- **Owner:** context-service CODEOWNERS
- **Related ADRs:** ADR-0030, ADR-0034, ADR-0303, ADR-0306, ADR-0308, ADR-0309

## Scope and assets

The service stores typed references and relationships copied from source-owned events. It does not
store raw payment narratives, names, IBANs, device fingerprints or embeddings in the P0/P1 slice.
The most sensitive assets are the relationship graph, effective-time case assignments and durable
read audit. It is outside every synchronous payment path.

## Trust boundaries

1. An authenticated admin caller supplies a case id, purpose and root reference. These values are
   claims, not authority.
2. The service verifies an active principal/case/purpose assignment in its database, then sends the
   server-derived `assignmentVerified=true` fact with principal roles and the root to the OPA
   sidecar. OPA is mandatory and the service fails closed.
3. The service commits an allow/deny/unavailable audit record before an allowed graph result is
   released.
4. Projection data arrives asynchronously from source contexts; source systems retain ownership.

## STRIDE analysis

| Threat | Control | Residual risk |
|---|---|---|
| Spoofed purpose or case | Request values must match an active server-side assignment; OPA receives the verified fact | Assignment provisioning is an external control and needs four-eyes administration before production |
| Tampered graph evidence | Every node/edge carries source, evidence reference, source version, valid time and recorded time | P0/P1 has no signed event envelope; signatures remain a follow-up |
| Repudiated read | Durable audit records principal, case, purpose, action, root, outcome and policy version before disclosure | Audit table needs export to the tamper-evident audit service before production |
| Relationship disclosure | Role gate, assignment, OPA, namespace-specific fixed query, 100-node/200-edge bounds; denied requests return no counts | Broad assignment issuance could still expose linked cases |
| Resource exhaustion | Indexed one-hop queries with hard result limits; service and database are isolated from money paths | Hot roots need load-test evidence and per-principal rate limits before increasing bounds |
| Privilege escalation | `authz.enforce=true` is literal configuration; PDP outage returns 503; historical grants are never used as current authority | OPA policy rules for the new actions must be reviewed before deployment |

## P1 privacy boundary

Complaint context may return opaque typed references and evidence links to assigned investigators.
Incident impact returns aggregate counts by node type. It never returns affected identifiers;
identifier drill-down requires a separate future action, assignment and audit trail.

## Invariants

1. No graph read occurs before assignment verification, current OPA allow and committed audit.
2. PDP failure, audit failure or missing assignment releases no graph data.
3. Historical validity explains evidence and never grants current access.
4. Queries are template-based and bounded; there is no arbitrary graph query API.
5. Context ingestion never participates in payment authorization or ledger posting.
6. P0/P1 stores opaque source references and bounded labels, not raw customer or payment text.

## Rollback

Disable the context routes and event projections. Before any real projection or audit record exists,
V1 can be rolled back by dropping `context_read_audit`, `context_case_assignments`, `context_edges`
and `context_nodes` in that order. After adoption, keep the additive schema for evidence retention;
do not delete it during application rollback.
