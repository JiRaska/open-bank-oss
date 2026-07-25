---
date: 2026-07-25
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [analytics, privacy-gdpr, architecture, admin-ui]
summary: "A new crm-service holds Customer 360 as a pure event-fed read model that owns no facts; GDPR erasure anonymises in place on the PARTY_ERASED event, the same pattern every other consumer already uses."
---

# ADR-0199 — Customer 360 read model in a new crm-service

## Context

The platform has ~35 services and no place that answers *"who is this customer"* in one query. A
banker on a call, a fraud analyst triaging a held payment (the ADR-0032 sub-threshold REVIEW queue)
and a campaign segmenter (ADR-0201) all need the same joined view, and today each would assemble it
by fanning out to party-service, account-service, transaction-service, product-catalog, lending,
consent-service and notification-service. The BIAN gap analysis in
`docs/strategy/01-bian-service-domain-mapping.md` records the absence directly: *Customer Case*
(row 11) and *Customer Workbench* (row 20) are missing Service Domains, against ~33% overall BIAN
coverage.

Three forces make the naive answers wrong.

**Force 1 — a synchronous fan-out join is a latency and coupling trap.** Seven upstream calls per
view, and the view is unavailable whenever any one of them is. `openbank-customer-edge` already shows
the pattern's ceiling for a single customer reading their own data; a banker-facing view over
arbitrary customers, with search and filtering, is a different query shape, and pushing it through the
edge would turn the edge into a reporting service.

**Force 2 — a second source of truth for customer facts is the defect class this repo keeps paying
for.** ADR-0198 exists because marketing consent acquired four descriptions. ADR-0197 exists because
the AGPL module list acquired three. A CRM that *stores* a customer's address, or their consent state,
or their balance becomes the next one. Whatever it holds must be derived, and visibly so.

**Force 3 — erasure over a customer aggregate is normally the expensive part, and here it can be
nearly free.** ADR-0118 fixes an anonymise-and-cascade model with statutory overrides (10y ledger, 5y
KYC/audit) — a customer aggregate is exactly the artifact that makes erasure costly, because every
copy must be found and rewritten. A read model that owns nothing inverts it: erasure becomes *rebuild
without the erased subject*, which is a capability a projection has anyway.

The substrate needed already exists. Every relevant service publishes domain events through the
transactional outbox; `openbank-analytics-sink` already proves the event-fed read-model shape;
CloudNativePG is the fleet Postgres; and ADR-0179 already merged party identity, so a customer has one
`partyId` to key on. Nothing new has to be operated.

Why now: ADR-0201's segmentation and ADR-0203's fraud-triage and collections agents each need this
view. If it is not built once, each will build a partial version. It is also the cheaper half of the
CRM story and is useful on its own, before any AI or campaign capability exists.

## Decision

We will add **`openbank-crm-service`**, a Quarkus/Kotlin hexagonal service (ADR-0002) whose only job
is to maintain a Customer 360 read model.

**D1 — It owns no facts.** Every column is derived from a consumed domain event. There is no write API
for customer data: no `POST /customers`, and no field a caller can set that is not itself an event. It
exposes `GET /api/v1/crm/customers/{partyId}` (the joined view), `GET /api/v1/crm/customers` (search
and filter, banker-facing) and a per-customer timeline. Anything a caller wants to *change* is a call
to the owning service; the CRM view updates when that service's event arrives.

**D2 — CQRS projection over the outbox topics that already exist.** Consumers subscribe to party
identity and contact events (including ADR-0179 merges), account and balance events, transaction
summaries, product holdings, lending exposure, consent grant and revoke (ADR-0126 as extended by
ADR-0198), notification and message-history metadata, and copilot interaction metadata. Storage is a
CNPG Postgres, denormalised for query — not a graph or document store. Consumers are idempotent on
event id, so applying the same event twice is safe by construction — a property D4 needs for a
different reason than replay.

**D3 — What it deliberately does not project.** No transaction-level rows (transaction-service stays
the query surface; the CRM holds counts, volumes and recency). No balances presented as authoritative
figures — ADR-0089's rule that every customer-facing figure comes from a tool call against the owning
service still holds, and the CRM does not become the exception. No KYC document content. No derived
risk or propensity score: scores belong to ADR-0201's feature store, which has point-in-time
correctness guarantees a projection does not.

**D4 — Erasure follows the ADR-0118 pattern every other consumer already uses: direct anonymisation
on the `PARTY_ERASED` event, not a replay.** An earlier draft of this ADR proposed rebuilding the
projection from the event log with the erased subject's events filtered out. That does not hold
against this fleet's actual Kafka configuration: every topic's `kafka-topic.yaml` sets
`retention.ms: 604800000` (7 days) with `cleanup.policy: delete`, not compacted and not
infinite-retention, so a full rebuild is only possible for events younger than a week — which is
false for nearly every real customer. `openbank-analytics-sink`, the one existing analog in this
codebase, was checked for precedent and does *not* rely on replay either: its `ErasureService` does
per-row crypto-shredding against stored data, gated by `RetentionPolicies`, and its `BackfillService`
pulls from a dedicated `BackfillSource`, never by re-consuming Kafka from offset zero.

The corrected mechanism: crm-service subscribes to `PARTY_ERASED` exactly as `notification-service`'s
`PartyErasureConsumer`, `kyc-service`'s `PartyEventConsumer.handleErased`, and
`card-issuance-service`'s `PartyEventConsumer` already do, and on receipt anonymises or deletes its
own persisted projection rows for that `partyId` directly — an in-place `UPDATE`/`DELETE` keyed by
party, not a rebuild. This needs no durable event history at all, matches the pattern every other
ADR-0118 consumer already uses (so it is the *expected* shape for a new consumer, not a novel one),
and sidesteps the retention question entirely: the projection only ever needs the live event stream
going forward, never a full historical replay. The statutory-override data (ledger, KYC, audit) is
never in the projection to begin with, so the projection cannot be the thing that retains what it
must not. This has to be *exercised*, not assumed: a test that erases a seeded customer and asserts
the projection retains no trace of them after the event is consumed. An erasure path that has only
ever been reasoned about is the unfalsified-gate failure mode `CLAUDE.md` documents at length — feed
it the input it must flag.

**D5 — Two consumers, one model.** A banker-facing admin-ui page, and MCP read tools so the ADR-0203
agents query the same view a human sees. Tool-calls are authorized by the ADR-0034 OPA sidecar as
principal type `AI_AGENT`. This is also why the CRM must be read-only *at the API boundary* rather
than by convention: an agent holding a write tool over a projection could write a fact whose owner
never agreed to it.

**D6 — Licensing.** crm-service holds customer data and serves the money-adjacent servicing path; it
is **not** agent-plane, so it stays Apache-2.0 and is **not** added to `rules.yaml agpl_modules`. Only
the ADR-0203 agents are, per the ADR-0197 property test — "agent plane, moves no money" — which
crm-service fails on the first clause.

## Alternatives considered

- **Extend party-service into the 360 view.** It already owns identity and the `partyId`, so no new
  service, no new deployment, no new Postgres. Rejected: party-service is an authority for the facts
  it owns, and mixing an authoritative write model with a wide derived read model in one service is
  exactly how a projection quietly becomes a second source of truth. It would also put a
  banker-facing search workload onto a service on the servicing critical path.
- **Extend `openbank-analytics-sink`.** Already event-fed, already doing read-model work — the closest
  existing home. Rejected on retention and purpose: analytics data is aggregate and governed as
  analytics (`RetentionPolicy`), whereas a 360 view is per-identified-customer operational data with a
  different lawful basis and a different erasure obligation. Merging them makes one retention policy
  serve two purposes, and the stricter one loses.
- **Query-time federation in the admin-ui BFF.** No new service and no new storage — genuinely the
  cheapest option, and the right answer if the only consumer were one banker screen. Rejected once
  ADR-0201 and ADR-0203 are consumers: a BFF join is not queryable by a segmenter, cannot be searched
  or sorted across customers, and would have to be reimplemented in every non-UI caller.
- **A commercial CRM (Salesforce, Dynamics) with the bank as a data source.** The honest choice for a
  real bank, and it buys campaign tooling for free. Rejected for three specific reasons rather than on
  principle: it places identified customer data outside the ADR-0175 eu-north-1 residency boundary and
  turns a core capability into an ADR-0174 ICT third-party dependency with an exit problem; the consent
  enforcement this platform's differentiation rests on (ADR-0198, ADR-0200) cannot be expressed inside
  it; and it makes the reference implementation undemonstrable as open source, which is the project's
  purpose.
- **A dedicated graph or document store for a wide sparse aggregate.** Better data-model fit.
  Rejected as an unpriced new runtime: CNPG is already operated, already backed up (with the
  WAL-archive lesson already paid for) and already inside the residency decision. A denormalised
  Postgres table is enough for a `partyId` lookup and a filtered banker search.

## Consequences

**Positive**
- One place answers "who is this customer", and every consumer — banker, agent, segmenter — sees the
  same answer, so the CRM and a campaign cannot disagree about a customer.
- Erasure over the customer aggregate becomes an in-place anonymisation on a single event, the same
  mechanism every other ADR-0118 consumer already uses, instead of a cross-table delete sweep that
  must itself be audited.
- Closes the named BIAN gap (Customer Case) and strengthens the partial one (Customer Workbench,
  today only partially covered by admin-ui) with one service.
- The fraud REVIEW queue gains the context it lacks today, which is the precondition for ADR-0203's
  triage agent rather than a separate build.

**Negative**
- Eventual consistency becomes visible to a banker on a call: the view can lag the owning service by
  the outbox dispatch interval. The view must therefore display its own staleness (a per-source "as
  of"), or a stale figure will be read as a current one. That is the honest cost of not fanning out.
- A new service means a new Postgres, a new OPA sidecar bundle, NetworkPolicy edges to every consumed
  topic, and a `version.txt`. The standing-order lesson applies directly: a newly graduating service
  must be added to **every** fleet-sweep exclusion list it was previously absent from, or it ships
  without Kafka mTLS or without its OPA sidecar and nobody notices.
- A wide projection of identified customer data concentrates a high-value target in one place. It
  holds no credentials and no card data, but the ADR-0081 segmentation baseline and the ADR-0118 PII
  tiering must be applied deliberately rather than assumed inherited.
- Consuming eight topic families is eight chances for a schema change to break the projection. Event
  schemas must stay backward-compatible, which is already the fleet rule but now has a consumer that
  spans nearly the whole domain.

**Neutral**
- Whether the banker surface lives inside admin-ui or becomes its own console is a UI decision this
  ADR does not make; the API contract is identical either way.

## Compliance impact

- PCI DSS: not applicable — no PAN or cardholder data is projected; a card appears as a product
  reference only.
- DORA: this is an internal service in the ICT dependency picture rather than a third party, and
  choosing it over a commercial CRM is the exit position recorded in the ADR-0174 register.
- GDPR: Art. 5(1)(c) data minimisation, for which D3's exclusions are the mechanism; Art. 17 erasure,
  where D4's direct anonymisation-on-event, matching ADR-0118's existing consumer pattern, is what
  makes the request satisfiable; Art. 32, since a
  concentrated PII store needs the access and segmentation controls named above; Art. 30, since a new
  processing purpose needs its own record-of-processing row. The lawful basis is legitimate interest
  for servicing, deliberately distinct from the consent basis marketing requires under ADR-0198 — a
  customer appearing in the CRM is not thereby a customer who may be marketed to.
- PSD2: not applicable — the projection serves bank staff and internal agents, not TPPs, and grants no
  account access. TPP access stays consent-gated through psd2-service.
- CNB: not applicable — no regulatory report is derived from this view; statutory reporting stays with
  finrep and anacredit against their own authoritative sources.

## References

- [ADR-0002](0002-hexagonal-architecture-per-service.md) — the per-service architecture this follows.
- [ADR-0118](0118-gdpr-data-lifecycle-and-retention.md) — the anonymise-and-cascade erasure model and
  the statutory overrides that keep ledger and KYC data out of the projection.
- [ADR-0126](0126-unified-consent-lifecycle.md) and
  [ADR-0198](0198-marketing-consent-as-a-first-class-consent-service-scope.md) — the consent state the
  projection reflects and must never become the authority for.
- [ADR-0179](0179-party-identity-merge.md) — one `partyId` per customer, the key this is built on.
- [ADR-0034](0034-unified-opa-authz-mcp-and-rest.md) — the single sidecar authorizing both the banker's
  REST call and the agent's MCP tool-call.
- [ADR-0089](0089-customer-facing-ai-assistant.md) — every customer-facing figure comes from a tool
  call against the owning service.
- [ADR-0174](0174-ict-third-party-dependencies-and-exit-strategy.md) and
  [ADR-0175](0175-data-residency-and-sovereignty.md) — why a commercial CRM was rejected.
- [ADR-0197](0197-agpl-open-core-boundary-covers-the-whole-agent-plane.md) — the licence-boundary
  property that keeps crm-service Apache-2.0.
- [ADR-0201](0201-customer-segmentation-and-next-best-action-on-the-ml-decisioning-platform.md) and
  [ADR-0203](0203-business-plane-ai-agents.md) — the consumers.
- `docs/strategy/01-bian-service-domain-mapping.md` — rows 11 and 20, the named coverage gaps.
