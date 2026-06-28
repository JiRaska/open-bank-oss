# 26. OLTP source-side reconciliation: per-service reconciliation-summary endpoints

Date: 2026-05-30
Status: Accepted
Delivery-Status: Partial

## Context

ADR-0023 closed nine regulatory findings on the analytics layer. Two of them — **F4** (independent
row-count tie-out) and **F5** (completeness gap detection) — together answer the examiner question
"can you prove the 10-year analytics warehouse has not silently lost or diverged from the operational
record?". The control logic is pure and unit-tested in `openbank-libs` (`Reconciliation.countDiff` /
`Reconciliation.fingerprint`, `Completeness.gaps` / `gapsFromVersions`, keyed on `AggregateKey`), and
the **warehouse side is real**: `ClickHouseWarehouseStateReader` runs the three cheap aggregate reads
over the bronze layer (`max(aggregate_version) GROUP BY type,id`, `count(DISTINCT aggregate_id) GROUP
BY type`, `groupUniqArray(aggregate_version) GROUP BY type,id`) and `ReconciliationJob` seals a signed
fingerprint of each pass into the WORM `integrity_anchors`.

**The remaining gap is the source side.** `ReconciliationJob` compares `source.currentVersions()`
against `warehouse.currentVersions()`, but the `ReconciliationSource` port is still bound to
`NoOpReconciliationSource` (returns empty), so the drift check runs **one-sided**: it can see what the
warehouse holds but has nothing authoritative to compare it against. The per-aggregate ground truth —
`max(version)` and a count per `aggregate_type`/`aggregate_id` — lives in each domain service's own
Postgres database (ADR-0009, postgres-per-service).

The hard constraint: **the analytics-sink owns no OLTP database (ADR-0022) and must not read other
services' databases directly.** Doing so would break service-DB ownership boundaries and put read load
on operational stores — precisely what the Kappa/outbox design (ADR-0003) exists to avoid (the sink is
fed only from the existing event stream, never a second extraction path). So the source-side authority
must be exposed *by each service, through its own boundary*, not pulled from its database by the sink.

This ADR records how each event-emitting service exposes its per-aggregate reconciliation summary, how
the sink consumes it, and the security/performance envelope — following the established ADR-0023
pattern: pure logic + a shared contract in `openbank-libs`, wired through a port whose `@Default`
binding is an offline-buildable no-op, with the real integration landing as a build-time-gated
`@Alternative @Priority` adapter.

Status legend (per service, mirroring ADR-0023): 🟢 GREEN = two-sided reconciliation live and tested;
🟡 YELLOW = contract + sink adapter exist, service endpoint not yet implemented (reconciliation stays
warehouse-only — no regression vs. today).

## The decision

### D1 — Each service exposes a read-only reconciliation-summary endpoint

Every in-scope service exposes one role-gated, read-only HTTP endpoint that returns, **per aggregate**,
its `max(version)` and, per aggregate type, a count:

```
GET /api/v1/analytics/reconciliation-summary
GET /api/v1/analytics/reconciliation-summary?since=<ISO-8601>   # incremental window (see D5)

200 application/json
{
  "service": "account",
  "generatedAt": "2026-05-30T02:30:00Z",
  "watermark": "2026-05-30T02:30:00Z",
  "countsByType": { "account": 1234, "account_pocket": 2310 },
  "aggregates": [
    { "aggregateType": "account",        "aggregateId": "…", "maxVersion": 7 },
    { "aggregateType": "account_pocket", "aggregateId": "…", "maxVersion": 3 }
  ]
}
```

The baseline is a single JSON document (one DTO, trivially parsed, feeds both port maps directly). For
the high-volume table (transaction) this evolves to streamed NDJSON with cursor paging — see D5 — so
neither side buffers the full result set; the same `aggregates`/`countsByType` semantics carry over.

The shape deliberately **mirrors the three warehouse reads** so the existing pure primitives compare
the two sides with no new logic:

- `aggregateType`/`aggregateId` + `maxVersion` ⇒ feeds `source.currentVersions()` → `Reconciliation.diff`.
- `countsByType` ⇒ feeds `source.rowCountsByType()` → `Reconciliation.countDiff` (the F4 tie-out).
- **No version *sequence* is exposed.** The OLTP store keeps only *current state* — one row per
  aggregate carrying its JPA `@Version` (e.g. `accounts.version`) — not the historical event sequence.
  Completeness (F5) is therefore *inherently warehouse-only* (it asks "did bronze receive a contiguous
  sequence?"), which is exactly why the existing `ReconciliationSource` port declares only
  `currentVersions()` + `rowCountsByType()` and **not** `versionsByAggregate()`. This ADR keeps that
  asymmetry; no source-side completeness read is introduced.

The authoritative source value is the aggregate table's optimistic-lock `@Version` column
(`SELECT aggregate_id, version`), which is the same monotonic value the outbox emits as
`AnalyticsEnvelope.aggregateVersion`, so the two sides converge to equal `max(version)` when in sync.
A service that owns several aggregate types (e.g. account-service owns `account` and `account_pocket`)
`UNION`s one projection per table; the `aggregateType` token **must be the same canonical string the
event envelope carries**, or the keys will not line up.

> ⚠️ **This premise currently holds only for account-service** (verified 2026-05-30, see "Phase 2
> precondition" below). The other in-scope producers emit a constant or absent `version` and, in two
> cases, no `aggregateType` at all — so the warehouse infers it from an id field. Until those producers
> are fixed, a source endpoint reporting the real `@Version`/own `aggregateType` would *manufacture*
> drift. Phase 2 is blocked on that producer-side fix.

### D2 — The contract lives in `openbank-libs` (`libs/analytics`), not a new `openbank-contracts` module

We **do not introduce** an `openbank-contracts/` module. `openbank-libs` already houses the
reconciliation primitives (`Reconciliation`, `Completeness`, `AggregateKey`) and is already depended on
by both the sink and every domain service, so it is the natural, lowest-friction home and avoids a new
module's build/version overhead. We add to `com.openbank.libs.analytics`:

- A DTO `AggregateReconciliationSummary` (the per-line + summary shapes above) — shared so the sink's
  parser and every service's serializer cannot drift.
- A JAX-RS **interface** `ReconciliationSummaryContract` carrying the `@Path`, `@GET`, `@Produces`
  **and the `@RolesAllowed` annotation** (see D3). Each service implements this interface, so the path,
  media type and — critically — the security gate are defined once and cannot be forgotten or
  weakened per service. The service supplies only the data (its repository query); the contract owns
  the wire shape and the authorization.

Each service's implementation is a thin `@ApplicationScoped` resource: inject the repository, run the
projection, stream NDJSON. No per-service contract duplication.

### D3 — Security: role-gated, never `@PermitAll`; S2S via the existing OIDC client

These endpoints expose aggregate metadata (ids, versions, counts) — audit material, so they are gated
exactly like the existing `ReconciliationResource`:

```kotlin
@RolesAllowed(Roles.SERVICE, Roles.AUDITOR, Roles.ADMIN, Roles.COMPLIANCE)
```

- **Never `@PermitAll`.** The gate is declared on the shared `ReconciliationSummaryContract` interface
  (D2) so it is impossible to ship a service that exposes the data unauthenticated.
- Per repo convention (`com.openbank.libs.security.Roles`, whose own KDoc says *use the constants, not
  raw strings*) we use the `Roles.*` constants, which resolve to the literals `"ROLE_SERVICE"`,
  `"ROLE_AUDITOR"`, `"ROLE_ADMIN"`, `"ROLE_COMPLIANCE"`. `ROLE_SERVICE` is required because the
  **caller is the analytics-sink calling service-to-service** — under the OAuth2 client-credentials
  grant it presents the `openbank-services` client token, which carries `ROLE_SERVICE`. The human
  audit roles let an examiner-facing operator hit the endpoint directly.
- **S2S auth uses the existing mechanism**, not a new one: the sink's REST client is a
  `@RegisterRestClient` interface annotated `@OidcClientFilter` (client-id `openbank-services`, the
  same OIDC config already in the sink's `application.yaml`), backed by `ServiceTokenProvider`. The
  filter fetches, caches and renews the bearer token and injects `Authorization: Bearer …` per call —
  no bespoke token handling in the adapter.

### D4 — The sink consumes the endpoints via a composite `ReconciliationSource` HTTP adapter

Following the ADR-0023 adapter pattern exactly (`ClickHouseWarehouseStateReader` as the template):

- A per-service HTTP reader and a composite `HttpReconciliationSource` implementing the existing
  `ReconciliationSource` port: `@ApplicationScoped @Alternative @Priority(100)`, gated at **build time**
  by a new property `openbank.analytics.reconcile.source.backend=http` via `@IfBuildProperty`. The
  `@Default NoOpReconciliationSource` stays as the offline binding, so the service still boots and
  reconciles as a clean no-op with **zero new dependency** and no infra when the gate is unset (CI,
  tests, dev without the source wired).
- Transport is the **JDK `HttpClient`** (no new Maven dependency, consistent with `ClickHouseClient`),
  with the actual HTTP `send` behind an `open` overridable seam so URL building and NDJSON parsing are
  pure and unit-testable **without a running server**. The OIDC bearer is supplied by the
  `@OidcClientFilter`/`ServiceTokenProvider` (D3).
- The composite fans out to each configured service, merges their `AggregateKey -> maxVersion` and
  `type -> count` maps into the single union the port returns. Endpoints are configured as a map, e.g.
  `openbank.analytics.reconcile.source.endpoints=account=http://openbank-account-service:8081,balance=…`
  — adding a service is a config line, no code change. A service that is unreachable at run time is
  logged loudly and **excluded** from that pass (it surfaces as warehouse-only for those keys rather
  than failing the whole reconciliation), mirroring the "boot-resilient, log loudly" stance of
  `ApicurioSchemaCatalogSource`.

### D5 — Performance: off-peak, replica-friendly, incremental, bounded payload

The per-aggregate dump is `O(rows)` — the same cardinality as the warehouse side — so on the largest
tables (transactions) a full dump is genuinely expensive. The query must never compete with customer
traffic. Mitigations, in order of importance:

1. **Off-peak pull.** The sink drives reconciliation from its existing off-peak cron
   (`openbank.analytics.reconcile.cron`, default `0 30 2 * * ?`) — never a fixed rate — so the source
   endpoints are hit only in the quiet window.
2. **Read-replica.** Services with a Postgres read-replica (ADR-0009) should point the summary query
   at the replica so it never touches the primary's customer write path.
3. **Incremental windowing.** The `?since=<watermark>` parameter lets steady-state reconciliation
   transfer only aggregates changed since the last successful pass (using each table's `updated_at`),
   so day-to-day cost is proportional to *churn*, not table size. A periodic (e.g. weekly) full pass
   (`since` omitted) still catches silent divergence the incremental path could miss.
4. **Streaming + cursor paging.** NDJSON is streamed and paged by an `aggregate_id` cursor so neither
   the service nor the sink buffers the whole result set in memory.
5. **Read-only, statement-timeout-bounded** transactions, so a runaway summary query cannot hold
   resources.

**Indexing.** `max(version)` per aggregate needs **no new index**: the aggregate tables hold one row
per `aggregate_id` keyed by the primary key, so `version` is a projection of that row — there is no
`GROUP BY`/sort to support. `count(*)` per type is cheap. The only index worth adding is a btree on
`updated_at` (or `(aggregate_type, updated_at)` where a table mixes types) **for the incremental
window** — and only on tables large enough to matter (transactions, party). We deliberately avoid
adding indexes that would slow the customer write path; `updated_at` is low-cardinality-churn and many
tables index it already.

### D6 — Scope and phased rollout

**In scope = the streams the sink actually ingests.** Although ~13 services emit domain events, only
the six topics the analytics-sink consumes (`openbank-analytics-sink/.../application.yaml`:
`openbank.account.events`, `openbank.transaction.events`, `openbank.balance.events`,
`openbank.party.events`, `openbank.kyc.events`, `openbank.consent.events`) land in the warehouse — and
you can only reconcile what the warehouse holds. So the in-scope services are:

| Service | Aggregate type(s) | Relative volume |
|---|---|---|
| account | `account`, `account_pocket` | low |
| balance | `balance` | medium |
| party | `party` | low–medium |
| kyc | `kyc` | low |
| consent | `consent` | low |
| transaction | `transaction` | **high** (largest table) |

If a future topic is added to the sink's subscription, this list (and the endpoint config map in D4)
extends to match — the contract and adapter do not change.

**Phases** (each flips a service from warehouse-only 🟡 to two-sided 🟢, tracked like the ADR-0023
findings):

- **Phase 0 — foundation (no behaviour change).** Land the shared contract (`AggregateReconciliationSummary`
  DTO + `ReconciliationSummaryContract` interface with role gate) in `openbank-libs`, and the sink-side
  `HttpReconciliationSource` composite adapter (gated off by default). No service implements the
  endpoint yet, so the `@Default` no-op stays bound and reconciliation remains exactly as today
  (warehouse-only) — **zero regression risk**.
- **Phase 1 — reference service (account). ✅ Code complete + verified in test.** Endpoint implemented
  on **account-service** (`AccountReconciliationResource`/`Repository`) reporting `aggregateType`
  `"Account"` + `max(version)`; a `@QuarkusTest` proves it registers from the inherited interface
  `@Path`, the role gate is enforced (401 anon / 403 disallowed role), and the JSON parses on the sink
  side. account-service is the **only** service whose producer emits a real `@Version` (see precondition
  below), so it is the only one for which the version tie-out is meaningful today. *Remaining (ops, not
  code):* flip the build-time gate + rebuild images to validate the live HTTP + S2S path and zero-drift
  against ClickHouse.
- **Phase 2 — low/medium-volume fan-out (balance, party, kyc, consent). ⛔ BLOCKED on producer-side
  parity (see below).** This was scoped as "a thin repository query behind the now-proven contract", but
  a 2026-05-30 trace showed that assumption is false: none of these four producers emit their real
  aggregate `@Version`, and balance/kyc emit no `aggregateType` (the warehouse mis-infers it). Writing
  the source endpoints as designed would produce false drift. Phase 2 must not start until the producers
  are fixed.
- **Phase 3 — high-volume last (transaction).** Add transaction **after** the incremental-window +
  replica + streaming path (D5) is proven on the smaller services, since it is the table where full
  dumps are most expensive and the incremental path matters most. (transaction's producer must be
  checked for the same parity gap before it is scheduled.)

### Phase 2 precondition — producer-side version/identity parity (finding 2026-05-30)

Tracing what each in-scope producer actually emits vs. what the sink's `AnalyticsConsumer.toEnvelope`
(+ `inferAggregateType`) stores in bronze shows the ADR's "outbox emits the entity `@Version`" premise
holds **only for account**:

| Service  | Warehouse `aggregateType`     | Warehouse `aggregateId` | Warehouse `version` | Root cause in producer |
|----------|-------------------------------|-------------------------|---------------------|------------------------|
| account  | `"Account"`                   | account id              | **real `@Version`** ✓ | `AccountEvents` pass `version: Long` |
| consent  | `"Consent"`                   | consent id              | **constant `1`**    | `ConsentEvents`: `override val version = 1L` |
| party    | `"PARTY"`                     | partyId                 | **`0`**             | event map has no `version` → consumer fallback `0L` |
| balance  | **`"ACCOUNT"`** (inferred)    | **accountId**           | **`0`**             | `BalanceEvent` carries no `aggregateType`/`version`, only `accountId` |
| kyc      | **`"PARTY"`** (inferred)      | **partyId**             | **`0`**             | event carries both `partyId` and `kycCaseId`; `inferAggregateType` tests `partyId` first, so kyc collides with party |

Consequences for reconciliation if Phase 2 were built as designed (source reports own `aggregateType` +
real `@Version`):
- The **version drift check** (`Reconciliation.diff`) would flag *every* aggregate of all four services
  (warehouse holds a constant `0`/`1`, source holds the real version).
- **balance** and **kyc** are not even stored under their own identity — balance lands as
  `"ACCOUNT"`/accountId and kyc as `"PARTY"`/partyId (a cross-service key collision, a bronze
  data-quality bug in its own right). Their keys would never match a `"Balance"`/`"KycCase"` source.

**Decision (2026-05-30): fix the producers first (the chosen path).** Before any Phase 2 source
endpoint is written, the four producers must emit their real `aggregateType` + aggregate `@Version` in
the outbox (the account-service / `DomainEvent`-with-real-`version` shape), and the kyc→party inference
collision must be removed. Only then is a Phase 2 source endpoint a faithful tie-out rather than a false
drift generator. This is a producer-side change (4 services + the sink's `inferAggregateType` heuristic),
out of scope for this ADR's reconciliation-reader work and tracked as its own follow-up; account-service
remains the single verified 🟢 reference until it lands.

## Consequences

**Positive.** F4/F5 become genuinely two-sided: the sink finally has an authoritative source to
compare the warehouse against, so the regulatory drift check stops being one-sided. The control
respects the service-DB ownership boundary (each service exposes its own truth) and the Kappa design
(no second extraction path, no read load on primaries — off-peak, replica, incremental). The contract
+ shared-role-gate-on-interface approach makes it impossible to ship an unauthenticated or
shape-divergent endpoint. The pattern is identical to the existing ClickHouse/Vault/Apicurio adapters,
so there is one mental model and **no new dependency**.

**Negative / trade-offs.** Every in-scope service now carries a small new surface (one endpoint + one
repository query) and must be deployed for its slice of reconciliation to go two-sided — the rollout is
genuinely incremental, and a service left in Phase 0 stays warehouse-only (honest 🟡, not a false
green). The full (non-incremental) pass on the transaction table is inherently `O(rows)` and must be
scheduled and replica-targeted carefully; a mis-pointed query at the primary during business hours
would be a real incident, so D5's off-peak/replica guidance is a requirement, not advice. As with all
ADR-0023 adapters the integration is build-time gated, so a deployment that forgets
`openbank.analytics.reconcile.source.backend=http` (or omits an endpoint from the config map) silently
keeps the no-op for the missing services — the gate and the endpoint map must be part of the production
profile and verified, not assumed.

## References
- ADR-0023 — analytics regulatory hardening (F4 count tie-out, F5 completeness; this ADR closes the
  documented source-side follow-up)
- ADR-0022 — event-fed ClickHouse analytics layer (the sink owns no OLTP DB)
- ADR-0003 — transactional outbox / Kafka (the single extraction path; no second read path on primaries)
- ADR-0009 — postgres-per-service (per-service DB ownership; read-replica option)
- ADR-0045 — lightweight ports + offline-buildable no-op defaults (the realization pattern)
- BCBS 239 §3 (accuracy/completeness/integrity); DORA (ICT monitoring); EBA/GL SoD
- `openbank-libs` — `analytics.Reconciliation` (`diff`/`countDiff`/`fingerprint`),
  `analytics.Completeness`, `analytics.AggregateKey`; `security.Roles`, `security.ServiceTokenProvider`
- `openbank-analytics-sink` — `application/port/out/ReconciliationPorts.ReconciliationSource` (the port
  this fills), `infrastructure/reconcile/ReconciliationJob`, `infrastructure/reconcile/NoOpReconciliationPorts`,
  `infrastructure/reconcile/ClickHouseWarehouseStateReader` (the warehouse-side template),
  `infrastructure/clickhouse/ClickHouseClient` (the JDK-HttpClient + open-seam adapter template),
  `infrastructure/rest/ReconciliationResource` (the `@RolesAllowed` precedent)
