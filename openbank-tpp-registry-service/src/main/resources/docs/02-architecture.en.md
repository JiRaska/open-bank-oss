# Architecture

The service follows the OpenBank hexagonal architecture (ADR-0002): a framework-free domain, an application layer of use cases behind ports, and adapters on the edge.

## C4 — container view

```
        ┌──────────────────────────────────────────────────────┐
        │            openbank-tpp-registry-service               │
        │                                                        │
   REST │  ┌──────────────┐   in-port   ┌────────────────────┐  │
  ──────┼─►│ TppRegistry  │ ──────────► │  TppRegistryService │  │
        │  │  Resource    │             │  (use case)         │  │
        │  └──────────────┘             └─────────┬──────────┘  │
        │                                out-port │            │
        │                              ┌──────────▼──────────┐  │
        │                              │   TppRepository      │──┼──► PostgreSQL
        │                              └─────────────────────┘  │   (openbank_tpp_registry)
        │  ┌──────────────────┐  poll  ┌─────────────────────┐  │
        │  │ TppOutbox        │ ◄───── │ tpp_outbox table     │  │
        │  │ Dispatcher       │ ─────► │ KafkaTppOutboxEvent  │──┼──► Kafka
        │  │ (@Scheduled 5s)  │        │ Publisher            │  │   (openbank.tpp.registry.event)
        │  └──────────────────┘        └─────────────────────┘  │
        └──────────────────────────────────────────────────────┘
                  ▲                              ▲
                  │ OIDC token                   │ OPA decision
              Keycloak                        OPA sidecar (advisory)
```

## Hexagonal layers

### Domain (`com.openbank.tppregistry.domain.model`)

Pure Kotlin, no framework imports:

- `TppEntry` — the aggregate (id, tppId, name, countryCode, nca, roles, status, QWAC/QSeal Subject DN + expiry, timestamps, blacklist fields).
- `TppRole` enum — `AISP`, `PISP`, `PIISP`, `ASPSP`.
- `TppStatus` enum — `ACTIVE`, `SUSPENDED`, `REVOKED`, `BLACKLISTED`.
- `TppAuthorizationResult` — the read model returned by the authorization check.
- `EbaRegisterSyncState` — sync bookkeeping value object.

### Application (`com.openbank.tppregistry.application`)

- **In-port** `TppRegistryUseCase` (`port.in`) with command/query records: `CheckTppAuthorizationQuery`, `RegisterTppCommand`, `BlacklistTppCommand`, `GetTppQuery`, `ListTppsQuery`.
- **Out-port** `TppRepository` (`port.out`) — persistence abstraction (findByTppId, save, update, list, saveSyncState, getSyncState).
- **Use case** `TppRegistryService` — the only implementation. Notable logic:
  - `checkAuthorization` rejects on: TPP not found, status ≠ ACTIVE, role not held, or expired QWAC.
  - `registerTpp` rejects a duplicate `tppId` with `TppAlreadyExistsException`.
  - `attemptEbaSync` is wrapped in MicroProfile Fault Tolerance (`@Timeout(3000)`, `@Retry`, `@CircuitBreaker`) but currently returns a stub state ("EBA sync not yet implemented").

### Adapters (`com.openbank.tppregistry.infrastructure` and `com.openbank.tpp.infrastructure`)

- `rest.TppRegistryResource` — JAX-RS resource under `/api/v1/tpp-registry`; handles idempotency replay via `IdempotencyStore`; `@Authorize(action = "tppRegistry.blacklist", resource = "#tppId")` on blacklist.
- `rest.ExceptionMappers` — maps `TppNotFoundException`→404, `TppAlreadyExistsException`→409, `EbaSyncUnavailableException`→503. `IllegalArgumentException` is deliberately left to the libs-provided canonical mapper (ADR-0049 D4).
- `persistence.TppRepositoryImpl` + Panache repos — Hibernate Reactive over PostgreSQL.
- `authz.AuthzProducer` — produces the `PolicyDecisionPoint` bean wired to the OPA sidecar.
- `idempotency.IdempotencyConfig` — per-service `@Produces` of the Redis-backed `IdempotencyStore`.
- **Outbox package** `com.openbank.tpp.infrastructure` — `TppOutboxDispatcher` (`@Scheduled` every 5s, fault-tolerant publish), `KafkaTppOutboxEventPublisher` (emits to channel `tpp-events-out`), `TppOutboxEntity` + `TppOutboxRepositoryImpl`. The ports (`TppOutboxEventPublisher`, `TppOutboxRepository`, `TppOutboxMessage`, `TppOutboxStatus`) live in `com.openbank.tpp.application.port.out`.

## Outbox → Kafka flow

The transactional-outbox pattern is wired end-to-end at the infrastructure level:

1. A domain event row would be written into `tpp_outbox` (status `PENDING`) in the same transaction as the aggregate change.
2. `TppOutboxDispatcher.dispatchScheduledBatch()` runs every 5 s (batch size 25, `concurrentExecution = SKIP`), reads processable rows and calls `publishWithResilience`.
3. `KafkaTppOutboxEventPublisher` sends a `Record<String,String>` (random key, payload) to topic `openbank.tpp.registry.event`.
4. On success the row is marked `SENT`; on failure `markFailed` records the error and increments `attempt_count`.

> **Accurate caveat:** the current `TppRegistryService` writes to `TppRepository` only — it does **not** yet insert outbox rows on register/blacklist. The outbox transport (table, dispatcher, publisher, Kafka channel) is fully present and the topic is configured, but **no domain events are emitted yet**. This is the first follow-up to wire (e.g. `TppRegistered`, `TppBlacklisted`).

## Key ports summary

| Port | Direction | Adapter |
|---|---|---|
| `TppRegistryUseCase` | inbound | `TppRegistryResource` (REST) |
| `TppRepository` | outbound | `TppRepositoryImpl` (Panache/PostgreSQL) |
| `TppOutboxRepository` | outbound | `TppOutboxRepositoryImpl` (PostgreSQL) |
| `TppOutboxEventPublisher` | outbound | `KafkaTppOutboxEventPublisher` (Kafka) |
| `PolicyDecisionPoint` | outbound | `OpaSidecarPolicyDecisionPoint` (OPA) |
| `IdempotencyStore` | outbound | `RedisIdempotencyStore` (Redis) |

## Cross-cutting concerns (from openbank-libs)

- **Idempotency** — `Idempotency-Key` header replayed from Redis with per-operation cache keys.
- **AuthZ** — `@Authorize` + OPA sidecar (ADR-0034), advisory mode (`authz.enforce=false`).
- **Observability** — OpenTelemetry OTLP export, SmallRye Health, JSON console logging.
- **Resilience** — MicroProfile Fault Tolerance on EBA sync and the outbox publisher.
