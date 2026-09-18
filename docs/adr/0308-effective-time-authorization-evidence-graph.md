---
date: 2026-09-13
decision-status: accepted
delivery-status: partial
followup: "#9945 — connect immutable business-action decisions with policy versions and add governed evidentiary export"
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: [open-bank-oss]
tags: [authz, audit, security, admin-ui]
summary: "The shared context graph records effective-time authorization evidence and uses current OPA decisions for every read, enabling reviewers to explain past approvals without treating historical grants as current access."
---

# ADR-0308 — Effective-time authorization evidence graph

## Context

All business lenses need two different answers: whether an action was authorized when
it happened, and whether the current reviewer may see its evidence now. Roles alone
cannot answer either. Delegations, mandates, case assignments, approvals and policies
change over time; a historical grant must never become present-day read authority.

This capability is **P0 and blocks every investigative real-data lens**. The initial
Customer 360 visualization may continue on its existing bounded permission, but no
cross-customer expansion, semantic case retrieval or sensitive export ships first.

## Decision

We will add a shared authorization-evidence ontology, policy contract and access-audit
path to ADR-0303's `context-service`. It is platform functionality used by all lenses,
not a standalone operator graph and not a replacement for service authorization.

The evidence model distinguishes:

- subject —[HELD]→ role/capability/delegation with valid time and source version;
- subject —[ASSIGNED_TO]→ case/portfolio with purpose, validity and approver;
- action —[AUTHORIZED_BY]→ immutable decision evidence containing policy/bundle version,
  evaluated resource/action, outcome and correlation reference;
- action —[APPROVED_BY]→ maker/checker evidence, preserving distinct identities;
- evidence —[SUPERSEDES/REVOKED_BY]→ later evidence without deleting history.

This projection records evidence emitted by authoritative enforcement points. It must
not reconstruct an authorization decision from today's role table or infer that the
absence of a denial means allow. Where the historic decision evidence is unavailable,
the answer is unknown. Source assertions carry valid/recorded time and integrity/hash
references; personal claims and tokens are not copied wholesale.

Every present-day graph request still invokes current OPA policy using authenticated
subject, explicit capability, purpose, verified case/portfolio assignment, bank scope,
root, requested relations/fields and export intent. The server derives policy input;
browser-supplied purpose/case IDs are verified references. A current deny suppresses
historic evidence. PDP or durable read-audit failure denies sensitive investigation.

OPA filtering applies before traversal and again to returned nodes/edges/fields.
Fixed query templates prevent arbitrary query-language injection. RLS is defense in
depth under non-owner/non-BYPASSRLS credentials and transaction-local context; pool
reuse, prepared statements and error paths are tested. Caches include subject access
scope, capability, purpose, verified assignment, policy/bundle version, projection
generation and filters, and are actively invalidated on revocation. TTL alone is insufficient.

The durable read audit stores subject/workload identity, purpose, verified case,
operation, root, normalized query hash, policy version/decision, projection snapshot,
returned evidence references/count and export reference. It excludes raw PII from normal
logs and is protected from the investigator. Audit failure cannot be converted to a
successful empty result. Repeated UI navigation may be batched only if each disclosed
scope remains reconstructable.

The first delivered control contract includes distinct capabilities for root lookup,
edge expansion, sensitive fields, semantic retrieval, batch analysis and export. Access
is least privilege and expires with assignment. Break-glass is time-limited, reasoned,
separately alerted and reviewed; it never silently maps to an admin role.

Acceptance uses a deny matrix across roles, purposes, cases, tenants, roots, relations
and fields; tests revoked/expired grants, policy/audit outage, stale cache, pool reuse,
hidden-path/count/timing leakage, same maker/checker, forged case, replay and current-vs-
historic time. A canary uses synthetic evidence, then audited staff accounts. Zero
unauthorized disclosure is the release threshold; performance targets cannot weaken it.
The production lens cannot run while its context authorization is advisory/shadow or
configured fail-open; an enforced decision and durable denial evidence are release gates.

## Delivery status

### Delivered P0 controls

The shared service now has durable, bank-scoped, effective-time assignments; admin-only proposal,
independent checker approval and immediate revocation; append-only assignment-change audit; current
assignment verification; mandatory OPA; and durable allow/deny/unavailable read audit before graph
disclosure. The admin UI exposes the controlled lifecycle and hides it from non-admin users. OPA
denies service accounts from assignment administration even if a broad operational role is present.
The read-audit table now forces bank-scope row-level security, including for its table owner, and
the audit writer sets that scope transaction-locally before persisting a decision. A missing or
different scope cannot read the row. Context also writes a schema-versioned SHA-256 commitment
into a bank-scoped outbox in the same transaction. A bounded relay publishes only the commitment
and random audit ID, and a dedicated strict Audit consumer validates the exact payload and
persists it idempotently into the fleet hash chain. Both channels are disabled by default until
the images, topic ACLs and operational checks are in place; code and local integration tests do
not prove sandbox delivery or fleet anchoring. Disclosure-outcome evidence, reconciliation and
historical business-action decision ingestion also remain, so this ADR stays `partial`.

### Remaining P0 audit-delivery contract

The current `ALLOWED` row records a successful *access decision before the read*. It does not
establish that any evidence was returned. Before an investigative lens is declared complete,
Context must append a separate disclosure outcome after materializing the bounded response and
before sending it to the caller. That outcome records the projection generation, normalized
query hash, returned evidence references/count and whether the response was truncated. Failure
to persist the disclosure outcome suppresses the response; a source timeout or failed query must
never be reported as a successful disclosure. Historical decision evidence from the source
enforcement point remains a distinct record, not an inference from either read-audit row.

Context will write each read-audit row and its export outbox entry in one database transaction.
The outbox carries only a schema version, random audit event ID, occurrence time and SHA-256
commitment over a canonical, length-delimited representation of the complete local row, including
the random ID. It carries no investigator, customer, case, root or evidence reference in clear
text. Re-delivery keeps the same event ID and commitment. A dedicated Kafka topic allows Context
to write and Audit to read; neither the legacy best-effort `AuditConsumer` nor its shared topic
qualifies because they acknowledge persistence failures. A dedicated Audit consumer must validate
the version and digest format, persist idempotently into the hash-chained, anchored audit store,
and acknowledge only after commit. Its own DLQ, retention, Kafka ACLs and lag/backlog alerts are
part of the delivery, including an alarm on any DLQ record. A poisoned commitment is quarantined
for investigation, never silently treated as exported.

The local row remains the only place holding the restricted detail. An authorized, purpose-bound
verification operation can recompute its commitment by audit event ID and compare it with the
anchored fleet record; a mismatch or a local row without a fleet record is a finding. Periodic
reconciliation compares committed local IDs with centrally stored IDs, so a lost or stalled relay
cannot appear healthy merely because requests still return. Context read latency depends on the
local atomic insert, not Kafka or Audit availability, while bounded outbox age and central
verification are release gates. Measure that insert at populated 1× and 10× load together with
payment-path p95 before enabling a real-data lens. Rollback disables new sensitive reads and the
relay while retaining both append-only stores and pending outbox rows; it does not delete an
audit trail or treat an unexported commitment as exported.

### Source delegation history and root-scoped review

The authority-history lens consumes the existing versioned delegation lifecycle stream and
stores immutable, deduplicated source observations. It retains event time and database record
time independently, supports `effectiveAt` and `knownAt`, and rejects conflicting content for
an already recorded source revision. The audit view exposes grantor, grantee, resource,
capabilities and lifecycle evidence with provenance, bounded to 100 observations.

`AUTHORIZATION_REVIEW` requires a maker/checker-approved assignment to the exact delegation
root. A broad case assignment, another delegation root, or a historical grant cannot satisfy
this check. OPA restricts the purpose and human compliance/admin role, while database row-level
security applies a transaction-local bank scope. Both requested timestamps enter the read audit.

These are source delegation assertions. The response explicitly reports business-action
`actionAuthorization: UNKNOWN`: without an immutable decision from the actual enforcement
point, this view does not establish that a specific payment or approval was authorized.
Generic success audit events are not substituted for that missing decision evidence.

Rollback stops the history consumer and endpoint while retaining append-only observations.
The nullable assignment/audit columns preserve the earlier P1 access contract. New delegation
topic replay is isolated from source services and uses a dedicated dead-letter topic.

## Alternatives considered

- **Role-only RBAC:** rejected; it cannot express purpose, assignment, object or field scope.
- **Use historical grants for current reads:** rejected; it converts evidence into authority.
- **Client-side filtering:** rejected; denied data has already escaped once serialized.
- **OPA without durable read audit:** rejected for sensitive investigation; authorization
  and accountable observation are both required.
- **Per-lens authorization implementations:** rejected; they drift at the highest-risk boundary.

## Consequences

**Positive**
- All lenses share one testable authorization and observation contract.
- Reviewers can explain past authorization without gaining historical privileges.

**Negative**
- Sensitive reads depend on both OPA and durable audit availability and fail closed.
- Correct invalidation and field/edge filtering add latency and operational work.

**Neutral**
- Source services remain responsible for authorizing business writes and emitting evidence.

## Compliance impact

- PCI DSS: any card-related lens gets least privilege and audited access; sensitive
  authentication data remains excluded.
- DORA: policy/audit dependencies require availability, recovery and failure-mode tests.
- GDPR: implements purpose limitation, access minimization and accountable access evidence.
- PSD2: historic consent/mandate evidence is not treated as current authorization.
- CNB: produces explainable access/decision evidence; no certification is claimed.

## References

- [ADR-0303](0303-banking-context-graph-and-authorized-hybrid-retrieval.md)
- [ADR-0034](0034-unified-opa-authz-mcp-and-rest.md)
- [ADR-0232](0232-delegated-access-customer-to-party-sharing-with-granular-capabilities.md)
- [Implementation roadmap #9945](https://github.com/JiRaska/open-bank-oss/issues/9945)
