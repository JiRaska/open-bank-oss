---
date: 2026-07-26
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [analytics, architecture, privacy-gdpr, admin-ui]
summary: "Customer 360 ships as a read API over analytics-sink's existing ClickHouse silver layer rather than ADR-0199's new crm-service: the same topics are already ingested and silver_current_state already is the projection."
---

# ADR-0210 — Customer 360 as a query over the analytics silver layer

## Context

ADR-0199 decided a Customer 360 read model in a **new** `openbank-crm-service`: a CQRS projection
over the existing outbox topics, into its own CNPG Postgres, with its own consumers. ADR-0209 D2
sequenced it and asked one question before funding a fourth service — could the read model start as
a query over `openbank-analytics-sink`'s existing bronze instead? This ADR is that spike's answer,
and the answer is yes.

Verified on `origin/main`:

- **The topic set is already identical.** analytics-sink consumes
  `openbank.account.events, openbank.transaction.events, openbank.balance.events,
  openbank.party.events, openbank.kyc.events, openbank.consent.events, openbank.sca.events,
  openbank.documents.document.event, openbank.onboarding.funnel.events, openbank.feedback.events`
  — essentially the exact list ADR-0199 D2 enumerates. A `crm-service` would be a **second**
  consumer group over the same topics writing a second database.
- **The projection already exists.** `openbank_analytics.silver_current_state` is a view over
  `bronze_events` reducing to current state per aggregate with
  `argMax(…, (aggregate_version, occurred_at, ingested_at))` grouped by
  `(aggregate_type, aggregate_id)`. That is a CQRS current-state projection — already written,
  already deterministically ordered, already tolerant of out-of-order delivery via version.
- **An as-of view exists too.** `silver_as_of` is the same reduction with a parameterised
  `WHERE occurred_at <= {t:DateTime64(3,'UTC')}`. Point-in-time reconstruction is available.
- **ClickHouse is deployed**, not aspirational:
  `openbank-infra/gitops/components/analytics/` carries `clickhouse.yaml`,
  `clickhouse-init-configmap.yaml` and `clickhouse-auth-externalsecret.yaml`, and the init
  ConfigMap applies the bronze/silver/gold schema on first boot.
- **Both objections ADR-0199 D4 raised are already answered here.** Retention: bronze is durable
  ClickHouse, not the 7-day `cleanup.policy: delete` Kafka topics that made a rebuild impossible.
  Erasure: analytics-sink already implements ADR-0118 by crypto-shredding (`ErasureService`,
  `ErasurePort`, `VaultCryptoErasure`) — the same mechanism ADR-0199 D4 settled on after
  rejecting replay.

So ADR-0199's *decision* — a derived read model that owns no facts, over the outbox topics — is
right and is unchanged. What it got wrong is that the projection had to be new.

## Decision

**D1 — Customer 360 is a read API over the analytics silver layer, not a new service.** No
`openbank-crm-service`, no second CNPG Postgres, no second consumer group. The view is served by
querying `silver_current_state` (and `silver_as_of` when a point-in-time view is asked for),
filtered to one party.

**D2 — The real work is the party key, and it is the only new persistence.** `bronze_events` is
keyed by `(aggregate_type, aggregate_id)`, so a party event's `aggregate_id` is the partyId but an
account or transaction event's is an accountId or transactionId. Assembling "everything for this
party" needs account→party (and card→party, case→party) resolution. That mapping is derived from
the same event stream and materialises as a ClickHouse view alongside the existing silver views. It
does not become a service, and it is the one thing this ADR adds to the schema.

**D3 — Figures stay non-authoritative, and the response shape says so.** ADR-0089's rule that every
customer-facing figure comes from a tool call against the owning service is unchanged, and ADR-0199
D3's exclusions carry over verbatim: no balance presented as authoritative, no transaction-level
rows (counts, volumes, recency only), no KYC document content, no derived risk or propensity score.
The response carries the `occurred_at` of the newest event it reduced, so a consumer can see how
stale the view is instead of assuming.

**D4 — Operator-facing first.** The first surface is an admin-ui page reached through the existing
BFF (ADR-0056), for a banker looking at one customer. No customer-facing exposure and no
`customer-edge` route in this ADR — that would put a non-authoritative aggregate in front of the
customer, which D3 exists to prevent.

**D5 — Erasure and retention are inherited, not reimplemented.** GDPR Art. 17 is analytics-sink's
existing crypto-shredding. Art. 15 remains party-service's `/gdpr-export` (ADR-0118), which this
view does not replace and must not be mistaken for. Adding a second erasure implementation for a
second store is precisely the cost D1 avoids.

**D6 — ADR-0199 is superseded in mechanism, retained in principle.** Its D1 (owns no facts), D2's
topic set, D3's exclusions and D4's erasure reasoning all stand; only "in a new crm-service" does
not. ADR-0200's campaign-service, if funded, reads this view — nothing in ADR-0200 depended on the
360 being its own service.

**D7 — `silver_as_of` does NOT satisfy ADR-0140 phase 2, and must not be read as unblocking the
NBA model.** This needs saying explicitly because the two look alike and the mistake would be
expensive. `silver_as_of` is an as-of view over **raw domain events**, reduced per aggregate.
ADR-0140 phase 2 requires a materialised point-in-time snapshot of **feature values**, keyed
`feature:<name>:<entity-id>`, produced by the *same* `compute` function that serves the online
store — the shared computation is the whole mechanism, because it is what makes training/serving
parity a property rather than a convention. An as-of view over events provides neither the feature
values nor that parity. So ADR-0209 D4 stands unchanged: ADR-0201 D5 does not start until ADR-0140
phase 2 exists. Building segment training on `silver_as_of` would reintroduce precisely the skew
ADR-0140 was written to prevent, while looking like it had satisfied the prerequisite.

## Alternatives considered

- **Build `crm-service` as ADR-0199 specifies.** Rejected on evidence, not principle: it
  duplicates a consumer group, a projection and a database that already exist and are already
  deployed, and would need a second ADR-0118 erasure implementation. ADR-0199 was written before
  anyone checked what analytics-sink already did.
- **Query `bronze_events` directly instead of the silver views.** Rejected: every caller would
  re-derive the `argMax` reduction, and the first one to write it slightly differently produces a
  different "current state" for the same party. The silver views exist so that reduction has one
  definition.
- **Materialise the 360 as a `gold_*` table.** Deferred, not rejected. The existing gold layer is
  aggregate reporting (funnel, feedback, volumes); a per-party materialisation is a performance
  decision that should follow a measured problem, and a table needs its own refresh semantics.
- **Serve it from party-service.** Rejected: party-service owns party facts, and hanging an
  aggregate of ten other domains' events off it would make that boundary meaningless — the same
  reason ADR-0199 did not put it there either.

## Consequences

**Positive**
- Removes a service, a database, a consumer group and a second erasure implementation from the
  CRM programme's cost — which was ADR-0209's stated blocker on funding D2.
- Point-in-time views come free via `silver_as_of`, which ADR-0199 did not offer at all.
- Durable past Kafka's 7-day retention on day one, because bronze already is.

**Negative**
- Couples an operator surface to ClickHouse availability. ClickHouse is not on a money path and
  this view is read-only, so the failure mode is a degraded admin-ui panel rather than a blocked
  payment — but the page must render an honest unavailable state, not an empty one.
- Query-over-view latency is not projection-table latency. D3's staleness field makes freshness
  visible; the performance answer is the deferred gold materialisation. Assuming a view will always
  be fast enough would be the mistake here.
- The account→party resolution (D2) is genuinely new logic, and if it is wrong the 360 shows
  another customer's account. It needs a test asserting **isolation**, not just assembly.

**Neutral**
- No new runtime dependency, no new service, no new gitops component: ClickHouse and analytics-sink
  are already deployed and already consume these topics.

## Compliance impact

- PCI DSS: not applicable — no cardholder data enters this view; ADR-0199 D3's exclusions carry over
  and card data stays with card-issuance-service.
- DORA:    a new read dependency on ClickHouse for an operator console surface. Not ICT
  third-party, not on a money path, degrades to an unavailable panel.
- GDPR:    Art. 17 erasure is analytics-sink's existing crypto-shredding, inherited not
  reimplemented (D5). Art. 15 remains party-service's `/gdpr-export` — this view does not replace
  it. Art. 5(1)(c) minimisation is why D3 keeps counts and recency rather than transaction rows.
- PSD2:    not applicable — no AIS/PIS surface; TPP access remains psd2-service's.
- CNB:     not applicable — no regulatory reporting surface.

## References

- ADR-0199 (Customer 360 in a new crm-service — superseded in mechanism by D6, retained in principle)
- ADR-0209 D2 (asked for this spike before funding a fourth service)
- ADR-0022 (event-fed ClickHouse analytics layer — the bronze/silver/gold layering this reuses)
- ADR-0118 (erasure pattern; analytics-sink's crypto-shredding is the implementation D5 inherits)
- ADR-0089 (customer-facing figures come from the owning service — why D3 is non-authoritative)
- ADR-0056 (admin-ui reaches services through the BFF, which D4's page uses)
