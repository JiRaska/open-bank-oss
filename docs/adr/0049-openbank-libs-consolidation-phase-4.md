# 49. openbank-libs consolidation Phase 4: convention plugin, shared config profile, finished outbox abstraction, observability

Date: 2026-05-31
Status: Accepted
Author(s): jiri.raska

> **Amendment 2026-06-19 — D3 outbox abstraction sweep complete; D4 exception-mapper sweep complete.**
>
> **D3** — All 29 hand-rolled outbox dispatchers migrated to `AbstractOutboxDispatcher`. Every service
> now injects `OutboxEventPublisher` (the libs interface) rather than its concrete Kafka implementation,
> closing the hexagonal-architecture violation (ADR-0002). Per-service
> `*OutboxEntry/Message/Status/Repository/EventPublisher` duplicates deleted. 20 PRs merged:
> non-money-path: #1248 aml, #1255 interest, #1256 party, #1257 pid, #1261 psd2, #1268 sdd,
> #1271 statement, #1292 kyc; money-path (each with threat-model gate): #1259 ledger, #1260 sca,
> #1263 sepa-payment, #1264 swift, #1265 transaction, #1242 clearing, #1244 balance, #1245 fx,
> #1246 account, #1249 domestic, #1250 consent, #1254 lending.
>
> **D4** — Duplicate generic exception-mapper sweep complete. All services that had a competing
> `@Provider` for `IllegalArgumentException`, `IllegalStateException`, or `Exception` alongside
> the libs `CommonExceptionMappers` have been cleaned up. Two intentional exceptions remain:
> **ledger** keeps `IllegalArgumentExceptionMapper` (422 GL-validation, not the libs 400) and
> `IllegalStateExceptionMapper` (409 CONFLICT for double-entry invariant, not the libs 422).
> **psd2** keeps `Psd2IllegalArgMapper` emitting the Berlin-Group `tppMessages` envelope required
> by the standard — dropping it would break Berlin 1.3.12 compliance. Status raised to Accepted.

> **Amendment 2026-06-03 — audit refresh before execution.** A re-audit ahead of starting the work
> found the plan sound but three points needing correction before the D3 sweep can land a *compliant*
> baseline, plus drift since the original count:
>
> 1. **`AbstractOutboxEntity` is structurally unusable, not merely unused.** It is a plain
>    `@MappedSuperclass`, but every service entity extends `PanacheEntity` (active-record `id`), and
>    Kotlin has no multiple inheritance — so it could never have been adopted as written. D3 now adopts a
>    new `PanacheOutboxEntity : PanacheEntity()` base instead; `AbstractOutboxEntity` is deprecated.
> 2. **The libs outbox surface was itself behind ADR-0050 and had to be raised first.** `OutboxStatus`
>    lacked the terminal `DEAD` state (N5) and `OutboxEventPublisher.publish(payload: String)` could not
>    carry the partition key (N2) or `ce-id`/`idempotency-key`/`ce-type` headers (N3) — so consolidating
>    onto it would have frozen the compliance gap into every service. The surface upgrade (landed
>    separately as `refactor/libs-outbox-surface`) adds `OutboxStatus.DEAD`, `OutboxFailurePolicy`,
>    `OutboxKafkaHeaders`, `PanacheOutboxEntity`, and widens the publish port to take the full
>    `OutboxEntry`. **D3 depends on this and must follow it.**
> 3. **Services re-declare the ports/DTOs too, not just the impl.** Each carries its own
>    `*OutboxEntry/Message/Status/Repository/EventPublisher` mirroring `libs…outbox.OutboxPorts`/
>    `OutboxStatus`; the D3 migration deletes those as well as the dispatcher.
>
> **Counts (2026-06-03):** the duplication grew while this ADR sat unexecuted — now **27** hand-rolled
> outbox entities/repositories, **29** dispatchers, **33** event publishers (was "22" throughout below).
> Read the round numbers below as "~all services".

## Context

ADR-0014 established `openbank-libs` as the shared service-infrastructure layer and landed Phases 1–3
(web plumbing, domain primitives, error contract, security/audit foundation). It explicitly deferred two
items: a **Gradle convention plugin (Phase 4)** and a **Quarkus platform extension (Phase 5)**, and its
migration plan said services adopt the shared code "opportunistically when touched". A follow-up audit
(2026-05-31) measured how much was actually adopted and what duplication remains. The findings:

- **Build duplication — no convention plugin exists.** There is no `buildSrc` / `build-logic`. All 28
  service `build.gradle.kts` repeat the same `plugins{}` block, `group`, `kotlin { jvmToolchain(25);
  freeCompilerArgs … "-Xjsr305=strict" }`, the identical 4-annotation `allOpen{}`, the `cyclonedxBom`
  task config, and the `tasks.test {}` logmanager/Docker env block. A diff of `account` vs `ledger`
  build files is ~11–13 lines, almost entirely the dependency *list*.

- **`version.txt` does not exist for any service.** CLAUDE.md non-negotiable #2 and ADR-0029 D2 mandate a
  per-service `version.txt` as the single release-version source, read into
  `quarkus.application.version`. In reality every service is hardcoded `version = "0.1.0-SNAPSHOT"` and
  nothing reads a `version.txt`. The version plumbing wired by ADR-0029/ADR-0048 (`/info`,
  `X-Service-Version`) therefore reports the `0.0.0`/`0.1.0` default. This is the unfinished half of
  ADR-0029 D2 "Layer B".

- **Config duplication — 28× copy-paste, already drifting.** Each `src/main/resources/application.yaml`
  repeats the same security-headers block (`X-Content-Type-Options`/`X-Frame-Options`/CSP/HSTS — 28/28),
  OIDC block (26), OTel exporter (26), management/`/q` block (27), Flyway retry (24) and datasource
  default (26). The hand-maintenance has already produced drift: `openbank-account-service` declares the
  `flyway:` key **twice**; the Kafka serializer is written two different ways across services. A
  security-header fix today is a 28-file edit.

- **Outbox — the shared abstraction exists but is bypassed.** ADR-0013/ADR-0014 shipped
  `libs.persistence.outbox` (`AbstractOutboxEntity`, `OutboxDispatch`, `OutboxPorts`, `OutboxStatus`).
  Yet 22 services each carry their own `*OutboxDispatcher.kt` that are byte-identical except the injected
  port type names (same `@Scheduled(every="5s")`, same `@Bulkhead/@CircuitBreaker/@Retry/@Timeout`, same
  `BATCH_SIZE`), and `AbstractOutboxEntity` has **zero** usages. The banking-critical resilience policy is
  copy-pasted into 22 money-path files instead of tuned in one place.

- **Error contract — generic mappers duplicated, with a correctness risk.** `libs.api.error`
  auto-registers `@Provider` mappers for `IllegalArgumentException`, `IllegalStateException`,
  `NoSuchElementException`, `WebApplicationException` and a sanitising `Exception` fallback — and the libs
  versions take `traceId` from the correlation MDC, so errors join to logs. Yet 8 services re-declare a
  generic `ExceptionMapper<IllegalArgumentException>` (and `account` also re-declares `IllegalState` and
  `Exception`). Two `@Provider` mappers for the *same* exception type on one classpath is a
  non-deterministic JAX-RS registration — a real bug, not just DRY.

- **Observability gap in libs.** Despite ADR-0008 (OpenTelemetry), libs provides **no** shared Micrometer
  metrics, no OTel runtime wiring, and no SmallRye health/readiness checks. Every service configures these
  itself (the OTel/management blocks above), and there are no shared domain health checks.

- **Other flags.** Each service's `openapi.yaml` redefines an `ApiError` schema inline that is **out of
  sync** with the real `libs.api.error.ApiError` (missing `status`/`timestamp`/`details`); 23 Dockerfiles
  differ only by name/port; the 22 outbox Flyway DDLs differ only by table name; Kafka topic naming has
  no enforced convention (`openbank.account.events` vs `openbank.accounts.account.created`).

The unifying theme: the *shared code already exists or is cheap to add*, but **adoption is partial and
the build/config layer was never DRYed**, so cross-cutting fixes (security headers, resilience policy,
error correlation) cannot land in one place. This is the Phase 4 work ADR-0014 named but did not schedule.

## Decision

We will complete the `openbank-libs` consolidation as **Phase 4**, in four independently-shippable
workstreams, additive-first so nothing freezes. Money-path services (`rules.yaml: money_path_services`)
migrate under the 2-approval + threat-model rule (ADR-0030 D2); non-money-path services migrate first to
prove each change.

### D1 — Gradle convention plugin (`build-logic`) + real `version.txt`

- Introduce `build-logic/` (composite build) with an `openbank.quarkus-service` convention plugin that
  owns: the common `plugins{}` set, `group`, the Kotlin toolchain + `freeCompilerArgs`, the `allOpen`
  config, the `cyclonedxBom` task, and the `tasks.test {}` block. Each service `build.gradle.kts` keeps
  **only** `id("openbank.quarkus-service")` plus its own dependency list.
- The plugin reads `<service>/version.txt` and sets the project `version` and
  `quarkus.application.version` from it — closing the ADR-0029 D2 gap so `version.txt` becomes the real,
  single release-version source feeding `/info` and `X-Service-Version` (ADR-0048).
- Seed `version.txt` for every service at its current declared OpenAPI/release intent (default `0.1.0`),
  so release-please (ADR-0029) has a base to bump from.

### D2 — Shared `application.yaml` config profile

- Ship a libs-provided `application-common` config (Quarkus config `include` / profile) carrying the
  security headers, OIDC defaults, OTel exporter, management `/q` block, Flyway retry and datasource
  defaults. Each service `application.yaml` keeps only service-specific keys (name, port, datasource db,
  Kafka channels) and `quarkus.config.locations`/`%profile` include of the common file.
- Fix the drift uncovered (the duplicate `flyway:` key in `account`, the divergent Kafka serializer
  syntax) as part of the extraction.

### D3 — Finish the outbox abstraction: `AbstractOutboxDispatcher`

> **Prerequisite (Amendment 2026-06-03):** the libs outbox surface upgrade
> (`refactor/libs-outbox-surface`) must land first — it adds `OutboxStatus.DEAD`, `OutboxFailurePolicy`,
> `OutboxKafkaHeaders`, `PanacheOutboxEntity`, and widens `OutboxEventPublisher.publish` to take the full
> `OutboxEntry`. The existing `OutboxDispatch.dispatchOnce` loop now also passes the whole entry. Without
> this, D3 would consolidate onto a surface that cannot express the ADR-0050 N2/N3/N5 controls.

- Add `libs.persistence.outbox.AbstractOutboxDispatcher` — an abstract `@Scheduled` driver over
  `OutboxPorts` carrying the canonical `BATCH_SIZE` and the MicroProfile Fault Tolerance policy
  (`@Bulkhead/@CircuitBreaker/@Retry/@Timeout`) in one place. Each service keeps a tiny concrete subclass
  that binds its `OutboxRepository`/`OutboxEventPublisher` (the annotations must remain on a concrete CDI
  bean for the interceptors to fire — same constraint ADR-0013 noted for `OutboxDispatch`).
- Provide a libs `OutboxEventPublisher` Kafka helper that applies `OutboxKafkaHeaders` (partition key +
  `ce-id`/`idempotency-key`/`ce-type`) so the N2/N3 controls come for free instead of per service.
- Migrate services to it: extend `PanacheOutboxEntity` (**not** the deprecated `AbstractOutboxEntity` —
  see amendment), bind the libs `OutboxRepository`/`OutboxEventPublisher`, and delete the hand-rolled
  dispatcher **plus** the per-service `*OutboxEntry/Message/Status/Repository/EventPublisher` duplicates
  of `OutboxPorts`/`OutboxStatus`. **Order: non-money-path first, then money-path** (account, transaction,
  sepa-payment, domestic-payment, sca, consent) each as its own threat-model-reviewed PR.

### D4 — Delete the duplicate generic exception mappers

- Remove the service-level `ExceptionMapper<IllegalArgumentException>` (8 services) and `account`'s extra
  `ExceptionMapper<IllegalStateException>` + `ExceptionMapper<Exception>`. The libs equivalents are
  strictly better (correlation `traceId`) and resolve the `@Provider` collision. Keep every
  business-specific mapper (e.g. `AccountNotFoundExceptionMapper`, `ConsentNotFoundMapper`) — JAX-RS picks
  those by specificity. This is shipped first as a quick correctness win.

### D5 — Observability follow-on (scoped, not yet scheduled)

- A later increment adds shared SmallRye health/readiness checks and a Micrometer/OTel baseline to libs so
  the per-service OTel/management config (D2) collapses further. Tracked here, sequenced after D1–D4.

The remaining flags (openapi `ApiError` schema re-sync to the libs DTO, templated Dockerfile, outbox
Flyway DDL template, Kafka topic-naming convention in `rules.yaml`) are folded into the relevant
workstream PRs as they touch each service.

## Alternatives considered

- **Quarkus platform extension (ADR-0014 Phase 5) instead of a convention plugin.** Stronger build-time
  wiring, but heavier maintenance and tighter version coupling. Deferred again — the convention plugin
  (D1) captures most of the build-duplication value at a fraction of the cost; revisit Phase 5 once the
  libs API is stable across 2–3 minors (ADR-0014's own rationale).
- **Leave the duplication, document "consult libs first".** That is ADR-0014's status quo, and the audit
  shows it produced partial adoption, drift and a `@Provider` collision. Rejected — the cross-cutting
  fixes must be landable in one place.
- **One big-bang PR migrating all 28 services.** Rejected — un-reviewable, and unacceptable for money-path
  services that each need a threat model and 2 approvals. Per-workstream, per-service PRs instead.
- **Generate per-service build files / config from a template script.** A generator is one more thing to
  run and drift from; a convention plugin and a config `include` are evaluated by the build itself, so
  they cannot silently fall out of sync. Rejected in favour of D1/D2.

## Consequences

**Positive**
- Cross-cutting fixes (security headers, outbox resilience policy, error correlation, version source) land
  in one place instead of 22–28.
- `version.txt` becomes real, closing the ADR-0029 D2 gap so the version plumbing (ADR-0048) reports true
  values at runtime.
- The `@Provider` collision is removed; error responses gain log-correlated `traceId` everywhere.
- New services start from the convention plugin + common config and inherit the platform defaults for free.

**Negative**
- Phase 4 touches every service's `build.gradle.kts` and `application.yaml`, and 22 services' outbox
  wiring — a large, if mechanical, migration; money-path migrations are gated by threat models + 2
  approvals and so land more slowly.
- A convention plugin and a shared config profile add a layer of indirection a reader must know to consult
  (mitigated by ADR-0014 + this ADR being the pointer).
- During migration the shared and bespoke versions coexist per ADR-0014's established pattern.

**Neutral**
- No runtime behaviour change for end users from D1/D2/D4 (build/config/error-internals only); D3 changes
  *where* the outbox policy lives, not its values.
- `CONTRIBUTING.md`/CLAUDE.md remain the narrative pointing at `rules.yaml`.

## Compliance impact

- PCI DSS: Req. 6.3 (consistent secure-build config via the convention plugin) — supported.
- DORA:    Art. 8–10 (ICT change/version traceability) — `version.txt` becomes the real release source;
  Art. 25 (resilience) — the outbox fault-tolerance policy becomes centrally tunable.
- GDPR:    not applicable (no personal-data path change).
- PSD2:    not applicable directly.
- NIS2:    Art. 21 — single-implementation security headers (the 28× copy-paste collapses to one).

## References

- ADR-0008 — OpenTelemetry for observability (the libs observability gap, D5).
- ADR-0013 — Shared transactional outbox primitives (the abstraction D3 finishes).
- ADR-0014 — `openbank-libs` centralization roadmap (this ADR schedules its deferred Phase 4).
- ADR-0029 — Versioning/release as code (D2 `version.txt` gap closed by D1).
- ADR-0030 — Supply-chain & SSDLC (money-path threat-model gate governing D3 migrations).
- ADR-0048 — Decoupled version axes (consumes the real `version.txt` from D1).
- Audit 2026-05-31 — duplication and libs-gap findings.
