---
date: 2026-07-23
decision-status: proposed
delivery-status: planned
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, database, architecture]
summary: "When keyword retrieval outgrows the copilot help corpus, add semantic retrieval with pgvector on the existing Postgres rather than a dedicated vector DB — reusing CloudNativePG, no new runtime, content stays in the audit chain."
---

# ADR-0183 — pgvector retrieval augmentation for the copilot knowledge base

## Context

`openbank-copilot-service` today does deliberate keyword-overlap scoring over
bundled markdown (`HelpKnowledgeBase.kt` — "no embeddings, no vector store" by
design). The platform audit confirmed there is no vector DB, no embeddings, and
no RAG pipeline anywhere in code or gitops, and judged that absence *correct for
now*: keyword retrieval is adequate for the current help corpus, and ADR-0148
deliberately keeps money-path prompt content in-repo and in the audit chain
rather than in a SaaS vector store.

This ADR does not overturn that. It records the decision for *when* keyword
retrieval stops being adequate — a larger help/knowledge corpus, or a genuine
semantic-recall need the overlap scorer cannot serve — so the choice is made
deliberately in advance rather than reactively reaching for whichever vector
database is fashionable at the time.

## Decision

When (and only when) the copilot corpus outgrows keyword retrieval, we will add
semantic retrieval with **pgvector on the existing CloudNativePG Postgres**, not
a dedicated vector database:

1. **Storage.** Embeddings live in a pgvector column on a table in the
   copilot-service database (ADR-0009 database-per-service); no new datastore,
   no new operator, no new backup/DR path — it inherits the CNPG HA and backup
   posture (ADR-0159, ADR-0175 residency).
2. **Content stays in-repo.** The source knowledge remains versioned markdown in
   the repo (as today); embeddings are a derived index over it, regenerable from
   source. Money-path prompt content stays in the ADR-0148 registry and the
   audit chain, never only in a vector index.
3. **Embedding provider.** Embeddings are generated through the same governed
   LLM/inference path the rest of the AI stack uses (the ADR-0174 gateway
   topology), so embedding calls inherit the same egress control and residency
   position as chat calls — no separate un-audited egress to a US provider.
4. **Retrieval.** Hybrid retrieval (keyword + vector) is preferred over
   vector-only, since the existing keyword scorer already works and pgvector
   augments rather than replaces it.
5. **Trigger, not now.** This is `proposed`/`planned`: nothing ships until the
   corpus actually outgrows keyword search. A graph database remains explicitly
   out of scope (ADR-0027 in-cluster-OSS/small-team philosophy) until real
   relationship-analytics volume exists.

## Alternatives considered

- **A dedicated vector database (Pinecone, Weaviate, Qdrant, Milvus).**
  Rejected: a new runtime, a new operational surface, a new backup/DR/residency
  concern, and for a SaaS option a new data-egress path — all to serve a corpus
  Postgres+pgvector handles comfortably at this scale. ADR-0022 already rejected
  adding datastores the team size does not justify.
- **Keep keyword-only retrieval indefinitely.** Rejected as the forward
  position: it is right today and this ADR keeps it until the corpus grows, but
  pretending semantic recall will never be needed just defers an unplanned
  scramble. The decision is "pgvector when, not whether."
- **Store prompts/knowledge in a SaaS vector store (e.g. the Langfuse/vector
  tooling mentioned aspirationally in `agents.yaml`).** Rejected for money-path
  content for the same reason ADR-0148 gives: it belongs in the git history and
  audit chain, not a third-party system outside ADR-0086.

## Consequences

**Positive**
- Semantic retrieval with zero new runtime — reuses Postgres, CNPG HA/backup,
  and the existing residency and egress controls.
- Source content stays in-repo and auditable; embeddings are a regenerable
  derived index, not a source of truth.

**Negative**
- pgvector at very large scale is less specialised than a dedicated vector DB;
  this decision is explicitly bounded to the copilot corpus scale, and a future
  ADR would revisit if that boundary is crossed.
- Adds an embedding-generation step and an index-refresh job to copilot-service.

**Neutral**
- Hybrid retrieval keeps the existing keyword path in place; the vector index is
  additive, so a rollback is dropping the index, not re-architecting retrieval.

## Compliance impact

- PCI DSS: not applicable — no card data in the help/knowledge corpus.
- DORA:    no new datastore or operator; pgvector runs inside the existing CNPG estate already in the ADR-0174 register.
- GDPR:    the help corpus is not personal data; if any future indexed content becomes personal, it inherits copilot-service's retention and the residency position (ADR-0175). Embeddings are derived data, deletable by dropping the index.
- PSD2:    not applicable — retrieval augmentation of a help corpus, no payment or account-access surface.
- CNB:     not applicable.

## References

- ADR-0009 — Database-per-service on CloudNativePG
- ADR-0022 — ClickHouse analytics (datastore-restraint precedent)
- ADR-0148 — AI assurance (in-repo prompt/content, audit chain)
- ADR-0174 — ICT third-party dependencies / LLM gateway topology
- ADR-0175 — Data residency and sovereignty
