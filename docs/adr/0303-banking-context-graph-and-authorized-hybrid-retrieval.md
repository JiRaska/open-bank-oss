---
date: 2026-09-13
decision-status: accepted
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: [open-bank-oss]
tags: [admin-ui, database, authz, ai-agents]
summary: "Build a banking relationship graph on an isolated PostgreSQL read model, add permission-scoped pgvector/full-text retrieval, and protect payment workloads with bounded queries and measured resource isolation."
followup: "#9945 — P2/P3 lenses, hybrid retrieval, bitemporal history rows and production workload evidence remain"
---

# ADR-0303 — Banking context graph and authorized hybrid retrieval

## Context

Operators need to explain relationships across customers, accounts, payments, mandates,
corporate ownership, cases and operational incidents. Customer 360 already reads a
party-scoped event projection (ADR-0210) and is the first entry point. The same graph
interaction will later open from a payment or a verified investigation case.

The product direction and PostgreSQL-first hybrid architecture were accepted on
2026-09-13. A visually connected node must not imply current ownership, a valid mandate,
or suspicious behavior without supporting evidence. Semantic similarity is useful for
finding comparable documents/cases, but it cannot establish a banking relationship.

The existing Customer 360 permission is suitable only for the existing projection
scope. A multi-customer investigative view requires explicit purpose, case assignment,
source permissions, classification, revocation and read-audit controls before release.
Investigation must not create a synchronous dependency in payment processing.

## Decision

### D0 — One shared stack, multiple policy-bound lenses

We will build one `context-service` and one operational stack for all context-graph
uses. It owns one canonical identity namespace, typed/bitemporal edge envelope,
provenance contract, event projector, PostgreSQL adjacency store, full-text/pgvector
retrieval, bounded query engine, OPA integration, durable read audit, observability,
backup/rebuild process and admin UI explorer. Fraud/AML, corporate KYC, payment
complaints, lending, authorization review and incident impact are lenses over that
platform, not independent databases or services.

Sharing does not mean one undifferentiated data pool or workload. Tables and indexes
are partitioned by bank scope, sensitivity/lens and stable entity hash where the
measured distribution requires it. Each lens has an allow-listed ontology, source
contracts, OPA capability/purpose, query templates, retention policy, quotas and
worker/connection pool. Interactive reads, semantic retrieval, ingestion and rebuilds
have separate workload classes. A noisy lens cannot consume another lens's reserved
capacity or weaken its field/edge policy. Deployment may scale worker/query replicas
independently while preserving one codebase and one owning service.

Shared nodes reference authoritative identities; lens-specific observations/edges
retain their source and classification. An edge admitted for one purpose is not
automatically visible from another lens. Cross-lens traversal is denied unless a
policy explicitly allows the edge and both classifications. There is no global
"show the whole bank" query.

Delivery sequence is control-driven: ADR-0308 is P0; ADR-0306 and ADR-0309 are P1
bounded pilots; ADR-0304 and ADR-0305 are P2 cross-party lenses; ADR-0307 is P3 after
financial aggregation reconciliation. Business urgency and delivery order are tracked
separately: Fraud/AML is critical, but its disclosure scope makes it a later safe rollout.

### D1 — One explainable context view, delivered in bounded slices

We will expose a small authorized neighborhood, expandable deliberately, with evidence
for each relationship and a clear distinction between facts, summaries and hypotheses.
Customer 360, fraud/AML, corporate KYC, payment tracing, lending exposure, internal
controls and incident impact are the initial scenarios described in the
[product and technical design](../design/banking-context-graph.md).

The Customer 360 slice combines the stored event projection with seven bounded live feeds:
accounts, cards, notification interactions, credit applications, AML cases, registered devices
and documents. The admin BFF fans those reads out in parallel under the human bearer, with a
five-second timeout per source, requests at most one bounded page where pagination exists, and
returns a data-minimised graph contract capped per domain. A source failure
marks the graph partial and does not erase successful domains. Notification recipients, subjects
and bodies, device tokens and app-instance identifiers, document storage keys and hashes, and AML
free text never enter the graph response. Nodes retain their owning source; edges are created only
from authoritative IDs present in those responses.

The live overlay closes the bootstrap gap when Kafka retention cannot reconstruct activity
that predates a new projector. It is confined to the operator read path and is not a payment
dependency. Persistent history and multi-hop investigation remain owned by context-service;
the overlay does not become a second event store and does not authorize cross-customer
traversal. The browser shares one in-flight BFF response between the graph and the portfolio,
device and document panels, avoiding duplicate source reads. The canvas bounds rendering to
48 nodes, supports fact search, keyboard selection and zoom, and reports truncation rather than
presenting a bounded result as complete.

The BFF enforces `compliance:view` before any source request and each source independently
re-authorizes the relayed user. It does not claim to deliver the case-scoped authorization
or durable investigative audit in D4. The `partial` status records implementation in this
change, not production deployment.

### D2 — Isolated event-fed PostgreSQL relationship projection

We will implement `context-service` as the owner of a read-only investigative API and
its own PostgreSQL database, following Quarkus/Kotlin and the service ownership model.
Nodes and directed typed edges carry stable source references, source versions,
classification, bank/tenant scope, valid-time and recorded-time. Identity resolution
uses authoritative IDs; similarity-derived associations remain separate hypotheses.

The service consumes existing domain events through its own Kafka consumer group.
Idempotent upserts handle duplicate delivery; version guards prevent older events from
overwriting newer state. Removal and revocation require tombstones. A replay builds a
new projection generation and switches readers only after validation, preserving
current authorization and deletions. Kafka retention alone is not a rebuild guarantee:
versioned event schemas and authorized source snapshots are part of the recovery plan.

We will use a separately resourced CloudNativePG cluster, connection pools and workload
capacity. We will not put graph scans, embedding generation or replays on payment,
ledger or copilot-help database resources. Shared infrastructure contention still
requires measurement. Ingestion failure can make this view stale; it cannot block a
payment or silently fall back to an unbounded scan of authoritative services.

### D3 — Hybrid retrieval augments explicit relationships

We will use PostgreSQL full-text search and pgvector for an approved document/case
corpus. Embeddings are generated asynchronously through the governed inference gateway
and retain source hash/version, model version, classification and document permissions.
They are derived sensitive data with the same deletion and access lifecycle as the
source. Graph opening and explicit relationship expansion do not require an LLM.

This extends the PostgreSQL-first direction of ADR-0183 to a distinct workload; it does
not supersede that ADR's bounded help corpus or put customer evidence into it. Filtered
ANN search must be evaluated against exact retrieval within the authorized corpus.
Keyword/vector rank fusion must preserve the exact ID, numeric and temporal filters.
Similarity scores are relevance signals, not probabilities of fraud or proof of a link.

### D4 — Authorization applies to the entire observation surface

Before cross-customer investigation is exposed, the context API must enforce the
intersection of an authenticated subject, explicit capability, verified purpose,
current case/portfolio assignment, bank scope, source permissions and classification.
Case IDs supplied by the browser are references to verify, not authorization grants.
Infrastructure administration does not automatically authorize customer investigation.

OPA owns the policy decision; the service owns fixed bounded query templates and
server-derived predicates. Every returned node, edge and field must be allowed.
Traversal must not reveal a path through hidden nodes. Counts, suggestions, exports,
cache entries, citations and LLM context must obey the same boundary; do not disclose
how many unauthorized neighbors exist. An unavailable policy decision fails closed.

Durably audit subject, purpose, verified case, root, operation, policy version,
decision and evidence references before releasing investigative evidence. Audit
unavailability fails closed for that surface. Keep raw personal content out of normal
logs. Broad or sensitive disclosure uses expiring approval by a second person; export
has its own permission and is reauthorized on download.

Revocation is checked independently of projection freshness and historical data time.
A stale projection or cache must not preserve an obsolete grant. RLS may provide a
second boundary using a non-bypass database role and transaction-local context; test
connection-pool reuse and owner/BYPASSRLS behavior. The browser never enforces policy
by merely hiding a button. Retrieved document text is untrusted evidence, never an
instruction capable of changing a user's permissions or executing a banking action.

### D5 — Performance budgets are acceptance criteria, not assumptions

Use indexed one-hop adjacency with no arbitrary SQL/Cypher from a client. Synchronous
requests may traverse at most two hops, return at most 100 nodes / 200 edges / 256 KiB,
and inspect at most 5,000 candidate edges under a 500 ms DB statement timeout.
A final result LIMIT alone is not a work bound. High-degree hubs need authorized
aggregation or an asynchronous job. Partial results carry a clear indication and a
cursor bound to scope, filters and projection generation.

The initial targets are server p95 ≤ 300 ms and p99 ≤ 1 s for first-neighborhood
reads including policy/audit, semantic retrieval p95 ≤ 800 ms without answer
generation, and ingestion lag p95 ≤ 10 s. Above 60 s lag the UI identifies the data
as delayed. Start with at most two concurrent requests per user and bounded global
queues; determine global concurrency from capacity tests. Request cancellation must
stop database work. Embedding batches and replays have separate throttles/pools.

Before a production investigation rollout, benchmark planned annual volume and 10×
growth with high-degree nodes, selective permissions, cold/warm caches, concurrent
ingestion, replay and revocation. An initial test profile of 50 concurrent users /
100 RPS is a planning assumption. Run a comparable payment control workload: reject
a candidate that violates that workload's SLO or repeatedly regresses p95 by more
than 2%. Evaluate authorized recall@20 against exact retrieval, initially targeting
at least 0.95. These are unmeasured targets until evidence is attached to #9945.

### D6 — Technology escalation follows evidence

Retain PostgreSQL adjacency plus pgvector as the initial architecture. Evaluate Neo4j
if bounded multi-hop relationship analysis cannot meet accepted capability/SLO needs
after appropriate indexing and query design. Evaluate Qdrant if filtered semantic
retrieval and independent vector scaling are the measured limiting workload. Either
choice needs a new decision covering licensing/edition, authorization integration,
HA, backup/restore, residency and resource cost; neither replaces the access service.

### D7 — Scale by partition and workload class before adding datastores

The first scale unit is a stateless query/projector replica. PostgreSQL partitions and
indexes follow measured bank-scope, lens, time and entity-degree distributions; read
replicas may serve snapshot reads only when their lag is exposed and security revocation
is checked on the current authorization path. Projection generation is immutable during
a query, so cursors and pages cannot cross rebuild generations.

Capacity planning includes edge/provenance rows, indexes, vector values, WAL, replicas,
backups, replay headroom and audit writes. Autoscaling uses queue depth, query latency
and saturation, with hard admission control before database collapse. High-degree and
long-path analyses move to snapshot-bound asynchronous jobs. Sharding or a specialist
graph/vector store is introduced only after the shared workload benchmark identifies
the limiting dimension and a recovery/security exercise proves the new boundary.

## Alternatives considered

- **Vector database as the whole context model:** rejected. Semantic proximity cannot
  encode authoritative ownership, delegation validity or a directed transfer reliably.
- **Neo4j or Qdrant immediately:** deferred. These were considered in the technical
  design, but no workload benchmark currently justifies the extra operational stack.
- **Live fan-out into banking services per expansion:** rejected for the general graph.
  It couples investigation latency and load to authoritative transaction processing.
- **Extend the copilot help database for customer evidence:** rejected. Its corpus and
  resource/access boundaries differ from the investigative workload.
- **Role-only access or browser filtering:** rejected for cross-customer investigation.
  Neither represents case assignment, purpose or permissions on individual evidence.

## Consequences

**Positive**
- A useful Customer 360 slice can be delivered before a new database or inference path.
- Relationship provenance, authorization and work limits are designed before expansion.
- Derived data and asynchronous ingestion keep investigation out of the payment path.
- PostgreSQL/CNPG reuse reduces the number of technologies the team must operate.

**Negative**
- The complete investigative product needs a new service, storage capacity, audit path,
  policy integration and a measured performance gate; the first UI slice does not supply them.
- Eventual consistency, deletion propagation, authorization revocation and reconstruction
  require explicit lifecycle work. Resource isolation does not mean zero infrastructure cost.
- Source contracts may not expose every needed relationship or valid-time fact yet.
  Missing evidence must remain unknown rather than be generated by an LLM.

**Neutral**
- No automatic customer decision or money movement is introduced by this ADR.
- The UI preview alone is not an end-to-end investigative capability or performance proof.

## Delivery evidence and rollback

The delivered P0/P1 slice includes `openbank-context-service`, isolated PostgreSQL/Flyway
storage, OPA, durable read and assignment-change audit, maker-checker assignment APIs and
admin UI, generation-scoped idempotent Kafka projectors for complaint and ICT-incident
events, bounded complaint and aggregate incident APIs, GitOps resources, DLQs, metrics,
alerts and real-PostgreSQL HTTP integration tests. The repeatable 100 RPS workload profile
is in `openbank-context-service/e2e`; production-sized benchmark evidence is still required
before expanding the pilot. The GitOps component declares one replica; deployment
readiness alone is not production capacity evidence.

P0/P1 keeps the latest source version in each rebuild generation. Full bitemporal version-row
history, pgvector/full-text retrieval, P2/P3 lenses and production capacity evidence remain in
[#9945](https://github.com/JiRaska/open-bank-oss/issues/9945). Rollback scales the deployment
to zero and stops its consumers without affecting source or money-path services.

For D2–D6, deliver a threat model, API contract and contract tests, Flyway migrations
with rollback notes, source-event compatibility tests, authorization/audit negative
tests, deletion/replay tests and load evidence before release. Start the new path on
synthetic data, then an explicitly scoped pilot. Disabling its API and consumers must
leave source banking services operational; restored projections must still apply
current revocations and tombstones.

## Compliance impact

- PCI DSS: minimize card-related data in the projection; this decision does not authorize
  storing PAN or sensitive authentication data in graph or vector indexes.
- DORA: adds an investigative dependency requiring capacity isolation, monitoring,
  recovery tests and evidence of its failure behavior.
- GDPR: requires purpose-limited access, minimized derived personal data, retention,
  deletion propagation and review of inference/embedding processing before a real-data pilot.
- PSD2: does not change consent authority or grant payment initiation/account access;
  a projected consent observation must not be used as an authorization decision.
- CNB: retain explainable source evidence and auditability for supervisory review;
  this ADR claims no certification or determination of legal compliance.

## References

- [ADR-0210 — Customer 360](0210-customer-360-as-a-query-over-the-analytics-silver-layer.md)
- [ADR-0183 — pgvector retrieval augmentation](0183-pgvector-retrieval-augmentation-for-the-copilot-knowledge-base.md)
- [Product scenarios and technical design](../design/banking-context-graph.md)
- [pgvector indexing and filtering](https://github.com/pgvector/pgvector)
- [Neo4j vector-index memory configuration](https://neo4j.com/docs/operations-manual/current/performance/vector-index-memory-configuration/)
- [Qdrant filtering](https://qdrant.tech/documentation/search/filtering/)
- [ADR-0304 — Fraud and AML relationship investigation](0304-fraud-and-aml-relationship-investigation.md)
- [ADR-0305 — Corporate KYC ownership and authority graph](0305-corporate-kyc-ownership-and-authority-graph.md)
- [ADR-0306 — Payment complaint and return trace](0306-payment-complaint-and-return-trace.md)
- [ADR-0307 — Lending exposure, guarantor and collateral graph](0307-lending-exposure-guarantor-and-collateral-graph.md)
- [ADR-0308 — Effective-time authorization evidence graph](0308-effective-time-authorization-evidence-graph.md)
- [ADR-0309 — Incident business impact graph](0309-incident-business-impact-graph.md)
