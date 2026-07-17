# Architecture

The service follows the OpenBank hexagonal architecture (ADR 0002): a framework-free domain at the centre, an application layer of use cases and ports, and adapters on the edge.

## C4 — container view

```
        ┌──────────────────────────────────────────────────────────┐
        │                 openbank-consent-service                  │
        │                                                            │
   REST │  ┌──────────────┐   ┌─────────────────┐   ┌────────────┐  │
  ─────►│  │ ConsentResource│ │ ConsentService  │   │ Consent     │ │
  OIDC  │  │  (adapter in)  │─►│ (application)   │──►│ (domain)    │ │
        │  └──────────────┘   └───────┬─────────┘   └────────────┘  │
        │                             │ ports out                   │
        │     ┌───────────────────────┼───────────────────────┐    │
        │     ▼                       ▼                       ▼     │
        │ ConsentRepository    ConsentOutboxRepository ScaChallengeClient
        │  (status + outbox,    (dispatch → Kafka)      (REST → sca)  │
        │   one transaction)                                          │
        └─────┬───────────────────────┬───────────────────┬─────────┘
              ▼                        ▼                   ▼
        PostgreSQL              consent_outbox        sca-service
       (openbank_consents)      → Kafka dispatcher
                                  openbank.consent.events
```

## Hexagonal layers

### Domain (`domain/`) — zero framework imports

- `model/Consent.kt` — the `Consent` aggregate (immutable `data class`) with invariants enforced in `init`:
  - at least one scope,
  - `validTo > validFrom`,
  - **PSD2 RTS Art. 10 cap**: AIS scopes ⇒ max 90 days validity; otherwise 365 days.
  - transitions are pure functions returning a new copy: `activate(scaSessionId)`, `revoke(reason)`, `reject()`; queries `isActive()`, `hasScope()`, `coversAccount(iban)`.
- `model` enums: `ConsentScope`, `GranteeType`, `ConsentStatus`, and the `ConsentValidationResult` sealed type (`Valid` / `Invalid(reason, code)`).
- `event/ConsentEvents.kt` — `ConsentGranted`, `ConsentRevoked`, `ConsentExpired`, `ConsentRejected`, each extending the shared `com.openbank.libs.domain.event.DomainEvent` (aggregateType `"Consent"`, version `1`).

### Application (`application/`)

- **Inbound ports** (`port/in/ConsentUseCases.kt`): `CreateConsentUseCase`, `ActivateConsentUseCase`, `RevokeConsentUseCase`, `GetConsentUseCase`, `ValidateConsentUseCase`, with their command DTOs.
- **Outbound ports** (`port/out/`): `ConsentRepository` (its `save(consent, event)` persists the status change and the outbox row in one transaction), `ScaChallengeClient`, plus the outbox ports (`ConsentOutboxRepository`, `ConsentOutboxEventPublisher`).
- `usecase/ConsentService.kt` — the single `@ApplicationScoped` implementation of all five inbound ports. It also re-applies the validity cap defensively and defines the typed domain exceptions (e.g. `ConsentNotFoundException`, `ConsentNotOwnedByPartyException`, `ConsentScaNotCompletedException`).

### Adapters (`infrastructure/`)

- **REST in** — `rest/ConsentResource.kt` (`@Path("/api/v1/consents")`), DTOs in `rest/dto/`, and `rest/ExceptionMappers.kt` mapping domain exceptions to `ApiError` + HTTP status.
- **Persistence** — `persistence/entity/ConsentEntity.kt` + `ConsentOutboxEntity.kt` (Panache reactive), `persistence/repository/ConsentRepositoryImpl.kt` + `ConsentOutboxRepositoryImpl.kt`.
- **Messaging / outbox** — `outbox/ConsentOutboxDispatcher.kt` (scheduled) and `messaging/KafkaConsentOutboxEventPublisher.kt`. Lifecycle events reach Kafka only via the outbox (there is no direct-emit publisher).
- **SCA client** — `client/ScaChallengeClient.kt`: a MicroProfile `@RegisterRestClient(configKey = "sca-service")` wrapped by a resilient adapter.
- **Authz** — `authz/AuthzProducer.kt` produces an `OpaSidecarPolicyDecisionPoint` for the libs `@Authorize` interceptor (ADR 0034).
- **Idempotency** — `idempotency/IdempotencyConfig.kt` wires the libs `IdempotencyStore` (Redis).

## SCA-gated activation flow

Activation is the security-critical transition (ADR 0021 — *no auto-approve*):

```
POST /consents/{id}/activate?scaSessionId=S
   → load consent (404 if missing)
   → reject if already ACTIVE (409)
   → ScaChallengeClient.getChallenge(S)        (REST → sca-service)
       · NotFound        → 422 SCA challenge not found
       · other error     → 503 verification unavailable
   → require challenge.partyId == consent.partyId
        AND challenge.purpose == "CONSENT_GRANT"  (else 422 mismatch)
   → require challenge.status == "COMPLETED"       (else 422 not completed)
   → consent.activate(S); save; publish ConsentGranted
```

The SCA client is hardened with `@Timeout(2000)`, `@Retry(maxRetries=2)` and `@CircuitBreaker` so a flaky SCA service degrades to a clean 503 rather than a hang.

## Outbox → Kafka flow

Lifecycle events are written transactionally with the consent change (transactional outbox pattern):

```
ConsentService.publish(event)
   → insert row into consent_outbox (status PENDING)
            │
            ▼  every 5s (ConsentOutboxDispatcher, @Scheduled, SKIP concurrent)
   listProcessable(BATCH_SIZE=25)
   → publishWithResilience(payload)        @Bulkhead @CircuitBreaker @Retry @Timeout(3000)
       · success → markSent(eventId)
       · failure → markFailed(eventId, error)   (attempt_count++, retried next tick)
   → Kafka topic openbank.consent.events
```

The dispatcher swallows top-level errors so the scheduler never crashes; per-event failures are isolated and persisted to `last_error`.

## Key ports

| Port | Direction | Adapter |
|---|---|---|
| `CreateConsentUseCase` / `ActivateConsentUseCase` / `RevokeConsentUseCase` / `GetConsentUseCase` / `ValidateConsentUseCase` | in | `ConsentResource` |
| `ConsentRepository` | out | `ConsentRepositoryImpl` (Panache reactive, PostgreSQL); `save(consent, event)` writes the status change + outbox row in one transaction (then dispatcher → Kafka) |
| `ConsentOutboxRepository` / `ConsentOutboxEventPublisher` | out | `ConsentOutboxRepositoryImpl` / `KafkaConsentOutboxEventPublisher` |
| `ScaChallengeClient` | out | `ResilientScaChallengeClient` → `sca-service` |
| `PolicyDecisionPoint` | out | `OpaSidecarPolicyDecisionPoint` (OPA sidecar) |

## Notable cross-cutting concerns

- **Reactive end-to-end** — Hibernate Reactive + Vert.x PG client; resources are `suspend` functions (Kotlin coroutines bridged via kotlinx-coroutines-reactive).
- **Resilience** — SmallRye Fault Tolerance on both the SCA call and the outbox dispatch.
- **Observability** — Micrometer/Prometheus metrics and OpenTelemetry tracing (OTLP) ship with `service.name = openbank-consent-service`.
- **Security headers** — strict CSP, HSTS, `X-Frame-Options: DENY`, etc., set globally in `application.yaml`.
