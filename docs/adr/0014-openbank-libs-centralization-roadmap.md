---
date: 2026-05-28
decision-status: accepted
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [libs, architecture, governance]
summary: "openbank-libs becomes the shared service-infrastructure layer rather than a generic utility module, absorbing REST filters, outbox, idempotency, security/audit primitives and a Gradle convention plugin in phases."
---

# openbank-libs as the shared service-infrastructure layer

**Delivery note (updated 2026-07-01):**
- **Phase 1 (house cleaning)** — ✅ Shipped: unified dependency declaration; 12 byte-identical `InfoResource.kt` deleted; Jandex plugin added to libs; `RedisIdempotencyStore` consolidated.
- **Phase 2 (domain primitives + error contract)** — ✅ Shipped: `libs.persistence.outbox` shared entities; typesafe identifiers (`AccountId`, `TransactionId`, `PartyId`, etc. with JPA converters); `CommonExceptionMappers` returning canonical `ApiError`.
- **Phase 3 (security + audit foundation)** — ✅ Shipped: `PiiMask` (the masking *functions*; the `@MaskSensitive` annotation this line once also claimed was inert — no serialization filter ever honoured it — and was removed, #4011); canonical `Roles.*` constants; `AuditEvent`/`AuditEventPublisher`; `ServiceTokenProvider`/`BearerTokenClientHeadersFactory` for S2S auth.
- **Phase 4 (Gradle convention plugin)** — ✅ Shipped: `openbank.quarkus-service` convention plugin (ADR-0049) eliminates ~900 lines of duplicated build script.
- **Phase 5 (Quarkus platform extension)** — ⬜ Deferred: revisit once libs API is stable across 2–3 minor versions.
- **Service migration** (opportunistic) — Partial: services adopt libs primitives when touched; full fleet bespoke-code removal is ongoing.

## Context

A cross-service audit (2026-05-28) measured significant duplication across the
~28 services:

- **REST/web**: 14 identical `InfoResource`, 13 divergent `ExceptionMappers`,
  zero adoption of the three `@Provider` filters that already live in
  `libs.web` (`CorrelationIdFilter`, `RateLimitFilter`, `ApiVersionResponseFilter`).
- **Persistence**: ~20 near-identical `<service>OutboxEntity` / `*OutboxRepository` /
  `*OutboxDispatcher` triples, ~600 lines of identical Flyway DDL.
- **Idempotency**: 9 byte-equivalent `RedisIdempotencyStore` classes, although
  the interface already existed in `libs.idempotency`.
- **Security/audit**: `libs.security` and `libs.audit` were empty; meanwhile
  ~36 occurrences of `@PermitAll` on business endpoints, no canonical role
  enum, no shared PII masking, no S2S auth, no shared audit envelope.
- **Build**: 8 services used `project(":openbank-libs")` (root-build-only),
  2 had no libs dependency at all despite importing `com.openbank.libs.*`.

The libs JAR was also not Jandex-indexed, so even the bits that did live there
weren't discovered by Quarkus' ArC bean scan in consuming services.

## Decision

We will treat `openbank-libs` as the shared **service-infrastructure** layer
(not a generic Kotlin utility module). Phases 1–3 below land the foundation;
existing services migrate opportunistically when touched.

### Phase 1 — house cleaning (landed)

- Unify the dependency declaration across all 28 services to the composite-build-safe
  `implementation("com.openbank:openbank-libs")` form. The `project(":openbank-libs")`
  form silently breaks when a service is built standalone with `includeBuild(..)`.
- Delete the 12 byte-identical `InfoResource.kt` files and rely on
  `libs.web.ServiceInfoResource`.
- Add the Jandex Gradle plugin to `openbank-libs` so consuming services'
  Quarkus build picks up the libs `@Provider` filters and `@ApplicationScoped`
  beans automatically. No per-service `quarkus.index-dependency` needed.
- Move `RedisIdempotencyStore` from `account-service` into
  `libs.idempotency.impl` and delete the 8 duplicates.

### Phase 2 — domain primitives & error contract (landed)

- `libs.persistence.outbox`: shared `OutboxStatus`, `OutboxMessage`,
  `OutboxEntry`, `OutboxRepository`, `OutboxEventPublisher`, plus a
  `@MappedSuperclass AbstractOutboxEntity` and an `OutboxDispatch.dispatchOnce()`
  utility that services call from their own `@Scheduled` method (resilience
  annotations stay service-side so MicroProfile Fault Tolerance interceptors fire).
  See ADR 0013.
- `libs.domain.identifiers`: typesafe `AccountId`, `TransactionId`, `PartyId`,
  `CardId`, `DisputeId`, `OrderId`, `ConsentId`, `PaymentId`, `CaseId` with
  `@JsonValue` + `@JsonCreator` (JSON shape is a bare UUID string) and matching
  JPA `AttributeConverter`s. Stops `account.partyId` and `payment.partyId`
  from being assignable to each other at compile time.
- `libs.api.error.CommonExceptionMappers`: canonical mappers for
  `IllegalArgumentException`, `IllegalStateException`, `NoSuchElementException`,
  `ConstraintViolationException`, `WebApplicationException` and a sanitising
  fallback. All return `ApiError` with `traceId` taken from the correlation
  MDC, so error responses join to log lines for the same request.

### Phase 3 — security & audit foundation (landed)

- `libs.security.PiiMask`: deterministic masking for email, IBAN, PAN, phone,
  name, national-ID, with PCI-DSS-compliant defaults. Backs the GDPR Art. 25/32
  fix for the admin UI's unmasked PII tables. Applied **explicitly** by the
  caller: a `@MaskSensitive` annotation shipped alongside it and was inert —
  its KDoc promised "downstream serialization filters (admin-ui proxy,
  audit-event sanitizer)" that were never written, so an annotated field
  serialised in full while the source read as protected. Removed in #4011.
- `libs.security.Roles`: canonical role string constants
  (`ROLE_ADMIN`/`OPERATOR`/`VIEWER`/`COMPLIANCE`/`AUDITOR`/`SUPERVISOR`/`KYC`/
  `PAYMENTS`/`SERVICE`) plus `SecurityContextExtensions` (`currentUserId`,
  `actorType`, `correlationId`, `requireAnyRole`). Replaces ad-hoc strings.
- `libs.audit.AuditEvent` + `AuditEventPublisher` + `LoggingAuditEventPublisher`:
  canonical audit envelope with all GDPR Art. 30 fields. Default logger-based
  publisher means an unwired Kafka topic doesn't silently drop audit events;
  services override with a Kafka-based `@Alternative` for durable delivery.
- `libs.security.ServiceTokenProvider` + `BearerTokenClientHeadersFactory`:
  bearer-token injection on outbound REST clients (production uses Quarkus
  `quarkus-oidc-client-reactive-filter`, libs provides the abstraction so
  test doubles can swap in). Closes the S2S-auth gap audit found in 26 of
  28 services.

## Alternatives considered

- **Quarkus extension (`openbank-quarkus-platform`)**. Stronger automation
  (build-time wiring, deployment processors), but adds significant
  maintenance overhead and locks services tighter to a single platform
  version. Defer until the libs API is stable across 2–3 minor versions.
- **Gradle convention plugin (`build-logic/openbank.quarkus-service`)**.
  Orthogonal — eliminates the ~900 lines of duplicated build script, but
  doesn't address runtime duplication. Planned as Phase 4; not blocked by
  this ADR.
- **Per-service Quarkus extensions**. Premature and creates a combinatorial
  matrix of extension versions × service versions.

## Consequences

**Positive**
- Every new service gets correlation IDs, rate limiting, version headers,
  PII masking, canonical roles, ID types, error contract, audit envelope and
  S2S auth without writing them.
- Regulatory fixes land once. GDPR PII masking, DORA Art. 17 audit
  reconstruction and PSD2/CNB role enforcement all have a single
  authoritative implementation.
- The libs JAR is Jandex-indexed, so Quarkus discovery is transparent — no
  per-service `quarkus.index-dependency` glue.

**Negative**
- Existing services keep their bespoke code until migrated. The shared and
  the bespoke versions coexist for the duration; this is intentional and
  cheap, but readers must know to consult libs first.
- The libs JAR now has more `compileOnly` dependencies
  (`jakarta.persistence-api`, `jakarta.validation-api`,
  `microprofile-rest-client-api`, `quarkus-redis-client`,
  `mutiny-kotlin`). Runtime services already bring matching `implementation`
  declarations, so there is no transitive runtime cost.

**Neutral**
- The libs module needs JDK ≥ 20 to build (Gradle 8.8 blocks JDK 26 with a
  cryptic error). Project CI already uses JDK 21; document for local
  developers in CONTRIBUTING.

## Migration plan

The shared and bespoke versions coexist. Services migrate when touched:

1. **Idempotency** — services that use libs' interface can drop their own
   `RedisIdempotencyStore` immediately; the libs `@Default` bean picks up.
2. **InfoResource** — already removed; the libs version is auto-discovered
   via Jandex. Smoke test on first service startup after merge.
3. **Outbox** — see ADR 0013 migration plan. Order: account, transaction,
   sepa-payment, domestic-payment, sca, consent.
4. **Error contract** — services that emit `mapOf("error" to …)` should
   move to `ApiError` next time their REST surface is touched. The libs
   `GenericExceptionMapper` is a last-resort sanitiser; any service-specific
   business exception wins via more-specific mapper.
5. **Security/audit** — adopt `Roles.*` constants in `@RolesAllowed`,
   inject `LoggingAuditEventPublisher` and call it from every mutation
   handler. Promote to Kafka publisher when audit-service Kafka topic
   contract is finalised.

Phase 4 (Gradle convention plugin) and Phase 5 (Quarkus platform extension)
are tracked separately.

## Compliance impact

- **GDPR** Art. 25/32 — PII masking has an authoritative implementation.
- **GDPR** Art. 30 — `AuditEvent` carries all RoPA fields.
- **DORA** Art. 17–23 — durable audit trail with correlation back to logs.
- **DORA** Art. 25 — shared resilience-bearing outbox dispatch loop.
- **PSD2** RTS — canonical roles and S2S auth pattern for SCA-gated
  endpoints.
- **NIS2** Art. 21 — single-implementation security headers, S2S
  authentication, audit.

## References

- ADR 0003 — Transactional outbox for Kafka.
- ADR 0007 — Vault for secrets management.
- ADR 0008 — OpenTelemetry for observability.
- ADR 0013 — Shared transactional outbox primitives.
- Audit 2026-05-28 — regulatory and duplication findings.
