# Architecture

`openbank-sca-service` follows the hexagonal (ports & adapters) architecture mandated by [ADR 0002](../../../../docs/adr/0002-hexagonal-architecture.md). The domain layer has **zero** framework imports.

## C4 — container view

```
        ┌──────────────────────────────────────────────────────────┐
        │                   openbank-sca-service                    │
        │                                                           │
  REST  │  ┌────────────┐   ┌────────────────┐   ┌──────────────┐  │
 ──────►│  │ ScaResource│──►│  ScaService    │──►│  domain model│  │
 (8110) │  │ (adapter)  │   │ (application)  │   │ ScaChallenge │  │
        │  └────────────┘   └──────┬─────────┘   │ EnrolledDevice│ │
        │                          │              └──────────────┘  │
        │        ┌─────────────────┼───────────────────┐           │
        │        ▼                 ▼                   ▼           │
        │  ┌───────────┐   ┌──────────────┐   ┌────────────────┐  │
        │  │ Postgres  │   │ Redis        │   │ sca_outbox →   │  │
        │  │ (Panache  │   │ OTP /        │   │ ScaOutbox      │  │
        │  │ repos)    │   │ idempotency /│   │ Dispatcher →   │  │
        │  │           │   │ decisions    │   │ Kafka          │  │
        │  └───────────┘   └──────────────┘   └────────────────┘  │
        └──────────────────────────────────────────────────────────┘
```

## Hexagonal layers

### Domain (`com.openbank.sca.domain.model`)
Pure Kotlin, no framework. Holds the invariants:
- `ScaChallenge` — state machine helpers `isExpired()`, `canAttempt()`, `complete()`, `fail(reason)`. `fail` only flips to `FAILED` (and records `failedAt`/`failureReason`) once `attemptCount + 1 >= maxAttempts` (default 3); earlier failures stay `PENDING`.
- `EnrolledDevice`, `DeviceApprovalDecision`, `SignatureAlgorithm` (ES256 / ED25519), `DeviceDecisionType` (APPROVED / DENIED).
- `ScaChallenge.dynamicLinkingPayload(decision)` — the exact bytes the device must sign (RTS Art. 5 dynamic linking): `id | decision | amount | currency | creditorIban | reference`. Null linking fields collapse to empty segments so login/consent challenges keep a stable format.

### Application (`com.openbank.sca.application`)
- **Inbound ports** (`port.in`): `InitiateScaUseCase`, `VerifyScaUseCase`, `GetScaUseCase`, `EnrollDeviceUseCase`, `RecordDeviceDecisionUseCase`, `ListDevicesUseCase` with their command/query records.
- **Outbound ports** (`port.out`): `ScaChallengeRepository`, `OtpGenerator`, `OtpStore`, `ScaIdempotencyStore`, `NotificationSender`, `EnrolledDeviceRepository`, `ScaDecisionStore`, `DeviceAssertionVerifier`, `ScaOutboxRepository` (extends `OutboxRepository` from libs; publisher is `OutboxEventPublisher` from libs).
- **`ScaService`** implements all inbound ports. Key logic:
  - `initiate` — picks the method (default `PUSH_NOTIFICATION`), 300 s TTL, persists the challenge, stores a command-derived idempotency key, then either generates+stores an OTP (SMS/TOTP) or dispatches a push (PUSH/BIOMETRIC).
  - `verify` — OTP methods check the Redis OTP store; **decoupled methods consult the recorded device decision and never auto-approve** (ADR-0021). No decision yet ⇒ challenge stays `PENDING`, no attempt consumed.
  - `recordDecision` — fails fast if expired / not PENDING / a decision already exists (write-once), checks device ownership against the challenge party, verifies the signature over `dynamicLinkingPayload`, then stores the decision with a TTL bounded by the challenge expiry.

### Adapters (`com.openbank.sca.infrastructure`)
- **REST**: `ScaResource` (`@Path("/api/v1/sca")`) — coroutine handlers, `@Authorize` on mutating endpoints, per-party ownership enforcement via `SecurityIdentity`, and a set of `ExceptionMapper`s translating domain exceptions to the `ApiError` model.
- **Persistence**: Panache reactive repositories (`ScaChallengeRepositoryImpl`, `EnrolledDeviceRepositoryImpl`, `ScaOutboxRepositoryImpl`) + entities.
- **Redis adapters** (`ScaAdapters`): `SecureOtpGenerator`, `RedisOtpStore`, `RedisScaIdempotencyStore`, `LoggingNotificationSender`. Decoupled-decision store lives in `DeviceApprovalAdapters`, the JCA signature verifier in the infrastructure layer (`JcaDeviceAssertionVerifier`).
- **Messaging/outbox**: `ScaOutboxDispatcher` + `KafkaScaOutboxEventPublisher`.
- **authz**: `AuthzProducer` (OPA client wiring, ADR-0034).

## Outbox → Kafka flow

```
enroll device ──► EnrolledDeviceRepository.saveWithOutbox(device, DEVICE_ENROLLED)
                    └─ ONE Panache.withTransaction: the device row and its outbox row
                       commit together or not at all (#8679; both rows share one `xmin`,
                       asserted by ScaEnrollOutboxAtomicityIT)

   every 5s (delayed 5s, SKIP if running):
   ScaOutboxDispatcher.dispatchScheduledBatch()
        └─ listProcessable(25)
             └─ publishWithResilience(payload)   ──► Kafka topic
                  @Bulkhead(1) @CircuitBreaker @Retry(2) @Timeout(3s)
             └─ markSent(eventId) | markFailed(eventId, error)
```

The outbox write for `DEVICE_ENROLLED` is intentionally a **separate transaction** from the device save: if it fails the device still exists and the read-model (onboarding cockpit, ADR-0068) merely stalls — acceptable for a projection, never a silent security bypass.

## Key ports & cross-cutting concerns

- **Idempotency** — two layers: the REST layer caches the initiate response in `IdempotencyStore` (libs) keyed by `sca:initiate:{partyId}:{key}` (300 s); the use case additionally derives an idempotency key from the full command so identical re-initiations return the same challenge.
- **Fail-closed verifier** — `DeviceAssertionVerifier` implementations must return `false` on any malformed key/signature, never throw through to a success path.
- **Resilience** — outbox publish is wrapped in MicroProfile Fault Tolerance (bulkhead, circuit breaker, retry, timeout).
- **Observability** — Micrometer/Prometheus metrics, OpenTelemetry traces (OTLP), structured JSON logs with `traceId`/`spanId`.
