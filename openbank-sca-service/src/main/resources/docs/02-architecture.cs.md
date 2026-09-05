# Architektura

`openbank-sca-service` dodržuje hexagonální architekturu (ports & adapters) předepsanou [ADR 0002](../../../../docs/adr/0002-hexagonal-architecture.md). Doménová vrstva nemá **žádné** frameworkové importy.

## C4 — pohled na kontejner

```
        ┌──────────────────────────────────────────────────────────┐
        │                   openbank-sca-service                    │
        │                                                           │
  REST  │  ┌────────────┐   ┌────────────────┐   ┌──────────────┐  │
 ──────►│  │ ScaResource│──►│  ScaService    │──►│ doménový model│ │
 (8110) │  │ (adaptér)  │   │ (aplikace)     │   │ ScaChallenge │  │
        │  └────────────┘   └──────┬─────────┘   │ EnrolledDevice│ │
        │                          │              └──────────────┘  │
        │        ┌─────────────────┼───────────────────┐           │
        │        ▼                 ▼                   ▼           │
        │  ┌───────────┐   ┌──────────────┐   ┌────────────────┐  │
        │  │ Postgres  │   │ Redis        │   │ sca_outbox →   │  │
        │  │ (Panache  │   │ OTP /        │   │ ScaOutbox      │  │
        │  │ repos)    │   │ idempotence /│   │ Dispatcher →   │  │
        │  │           │   │ rozhodnutí   │   │ Kafka          │  │
        │  └───────────┘   └──────────────┘   └────────────────┘  │
        └──────────────────────────────────────────────────────────┘
```

## Hexagonální vrstvy

### Doména (`com.openbank.sca.domain.model`)
Čistý Kotlin, bez frameworku. Drží invarianty:
- `ScaChallenge` — pomocníci stavového automatu `isExpired()`, `canAttempt()`, `complete()`, `fail(reason)`. `fail` přepne na `FAILED` (a zapíše `failedAt`/`failureReason`) až když `attemptCount + 1 >= maxAttempts` (výchozí 3); dřívější selhání zůstávají `PENDING`.
- `EnrolledDevice`, `DeviceApprovalDecision`, `SignatureAlgorithm` (ES256 / ED25519), `DeviceDecisionType` (APPROVED / DENIED).
- `ScaChallenge.dynamicLinkingPayload(decision)` — přesné bajty, které zařízení musí podepsat (RTS čl. 5 dynamické provázání): `id | decision | amount | currency | creditorIban | reference`. Prázdná pole provázání kolabují na prázdné segmenty, aby výzvy typu login/souhlas měly stabilní formát.

### Aplikace (`com.openbank.sca.application`)
- **Vstupní porty** (`port.in`): `InitiateScaUseCase`, `VerifyScaUseCase`, `GetScaUseCase`, `EnrollDeviceUseCase`, `RecordDeviceDecisionUseCase`, `ListDevicesUseCase` se svými command/query záznamy.
- **Výstupní porty** (`port.out`): `ScaChallengeRepository`, `OtpGenerator`, `OtpStore`, `ScaIdempotencyStore`, `NotificationSender`, `EnrolledDeviceRepository`, `ScaDecisionStore`, `DeviceAssertionVerifier`, `ScaOutboxRepository` (rozšiřuje `OutboxRepository` z libs; publisher je `OutboxEventPublisher` z libs).
- **`ScaService`** implementuje všechny vstupní porty. Klíčová logika:
  - `initiate` — zvolí metodu (výchozí `PUSH_NOTIFICATION`), TTL 300 s, uloží výzvu, uloží idempotenční klíč odvozený z příkazu, pak buď vygeneruje+uloží OTP (SMS/TOTP), nebo odešle push (PUSH/BIOMETRIC).
  - `verify` — OTP metody kontrolují Redis OTP store; **decoupled metody konzultují zaznamenané rozhodnutí zařízení a nikdy neschválí automaticky** (ADR-0021). Žádné rozhodnutí ⇒ výzva zůstává `PENDING`, pokus se nespotřebuje.
  - `recordDecision` — rychle selže pokud je expirovaná / není PENDING / rozhodnutí už existuje (write-once), zkontroluje vlastnictví zařízení vůči party výzvy, ověří podpis nad `dynamicLinkingPayload`, pak uloží rozhodnutí s TTL omezeným expirací výzvy.

### Adaptéry (`com.openbank.sca.infrastructure`)
- **REST**: `ScaResource` (`@Path("/api/v1/sca")`) — coroutine handlery, `@Authorize` na mutujících endpointech, vynucení vlastnictví per-party přes `SecurityIdentity` a sada `ExceptionMapper`ů překládajících doménové výjimky na model `ApiError`.
- **Persistence**: Panache reactive repozitáře (`ScaChallengeRepositoryImpl`, `EnrolledDeviceRepositoryImpl`, `ScaOutboxRepositoryImpl`) + entity.
- **Redis adaptéry** (`ScaAdapters`): `SecureOtpGenerator`, `RedisOtpStore`, `RedisScaIdempotencyStore`, `LoggingNotificationSender`. Store decoupled rozhodnutí žije v `DeviceApprovalAdapters`, JCA verifikátor podpisu v infrastrukturní vrstvě (`JcaDeviceAssertionVerifier`).
- **Messaging/outbox**: `ScaOutboxDispatcher` + `KafkaScaOutboxEventPublisher`.
- **authz**: `AuthzProducer` (zapojení OPA klienta, ADR-0034).

## Tok outbox → Kafka

```
zápis zařízení ──► EnrolledDeviceRepository.saveWithOutbox(device, DEVICE_ENROLLED)
                     └─ JEDNA Panache.withTransaction: řádek zařízení a jeho outbox řádek
                        commitnou společně, nebo vůbec (#8679; oba řádky sdílejí jeden `xmin`,
                        ověřeno v ScaEnrollOutboxAtomicityIT)

   každých 5s (odloženo 5s, SKIP pokud běží):
   ScaOutboxDispatcher.dispatchScheduledBatch()
        └─ listProcessable(25)
             └─ publishWithResilience(payload)   ──► Kafka topic
                  @Bulkhead(1) @CircuitBreaker @Retry(2) @Timeout(3s)
             └─ markSent(eventId) | markFailed(eventId, error)
```

Zápis do outboxu pro `DEVICE_ENROLLED` je záměrně **oddělená transakce** od uložení zařízení: pokud selže, zařízení stále existuje a read-model (onboarding cockpit, ADR-0068) se jen pozastaví — přijatelné pro projekci, nikdy ne tiché obejití bezpečnosti.

## Klíčové porty a průřezové aspekty

- **Idempotence** — dvě vrstvy: REST vrstva cachuje odpověď na initiate v `IdempotencyStore` (libs) pod klíčem `sca:initiate:{partyId}:{key}` (300 s); use case navíc odvodí idempotenční klíč z celého příkazu, takže identické re-inicializace vrací stejnou výzvu.
- **Fail-closed verifikátor** — implementace `DeviceAssertionVerifier` musí při jakémkoli vadném klíči/podpisu vrátit `false`, nikdy neprojít výjimkou do úspěšné cesty.
- **Odolnost** — publikace outboxu je obalena MicroProfile Fault Tolerance (bulkhead, circuit breaker, retry, timeout).
- **Pozorovatelnost** — Micrometer/Prometheus metriky, OpenTelemetry trasy (OTLP), strukturované JSON logy s `traceId`/`spanId`.
