# Architektura

Služba dodržuje hexagonální architekturu OpenBank (ADR-0002): doména bez frameworku, aplikační vrstva use case za porty a adaptéry na okraji.

## C4 — pohled na kontejnery

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
        │  │ TppOutbox        │ ◄───── │ tabulka tpp_outbox   │  │
        │  │ Dispatcher       │ ─────► │ KafkaTppOutboxEvent  │──┼──► Kafka
        │  │ (@Scheduled 5s)  │        │ Publisher            │  │   (openbank.tpp.registry.event)
        │  └──────────────────┘        └─────────────────────┘  │
        └──────────────────────────────────────────────────────┘
                  ▲                              ▲
                  │ OIDC token                   │ OPA rozhodnutí
              Keycloak                        OPA sidecar (advisory)
```

## Hexagonální vrstvy

### Doména (`com.openbank.tppregistry.domain.model`)

Čistý Kotlin, žádné importy frameworku:

- `TppEntry` — agregát (id, tppId, name, countryCode, nca, roles, status, Subject DN + expirace QWAC/QSeal, časová razítka, pole blacklistu).
- enum `TppRole` — `AISP`, `PISP`, `PIISP`, `ASPSP`.
- enum `TppStatus` — `ACTIVE`, `SUSPENDED`, `REVOKED`, `BLACKLISTED`. Zapisují se pouze `ACTIVE` (registrace) a `BLACKLISTED` (blacklist API); zbylé dva dnes nemají zapisovatele (#6489). `checkAuthorization` odmítá každý stav jiný než `ACTIVE`, takže kontrola je správná pro všechny čtyři.
- `TppAuthorizationResult` — read model vracený kontrolou autorizace.
- `EbaRegisterSyncState` — value object evidence syncu.

### Aplikace (`com.openbank.tppregistry.application`)

- **In-port** `TppRegistryUseCase` (`port.in`) s command/query záznamy: `CheckTppAuthorizationQuery`, `RegisterTppCommand`, `BlacklistTppCommand`, `GetTppQuery`, `ListTppsQuery`.
- **Out-port** `TppRepository` (`port.out`) — abstrakce perzistence (findByTppId, save, update, list, saveSyncState, getSyncState).
- **Use case** `TppRegistryService` — jediná implementace. Pozoruhodná logika:
  - `checkAuthorization` odmítá, když: TPP nenalezen, status ≠ ACTIVE, roli nemá, nebo expirovaný QWAC.
  - `registerTpp` odmítá duplicitní `tppId` s `TppAlreadyExistsException`.
  - `attemptEbaSync` je obalen MicroProfile Fault Tolerance (`@Timeout(3000)`, `@Retry`, `@CircuitBreaker`), aktuálně ale vrací stub stav („EBA sync not yet implemented").

### Adaptéry (`com.openbank.tppregistry.infrastructure` a `com.openbank.tpp.infrastructure`)

- `rest.TppRegistryResource` — JAX-RS resource pod `/api/v1/tpp-registry`; řeší idempotenční replay přes `IdempotencyStore`; `@Authorize(action = "tppRegistry.blacklist", resource = "#tppId")` na blacklistu.
- `rest.ExceptionMappers` — mapuje `TppNotFoundException`→404, `TppAlreadyExistsException`→409, `EbaSyncUnavailableException`→503. `IllegalArgumentException` je záměrně ponechán kanonickému mapperu z libs (ADR-0049 D4).
- `persistence.TppRepositoryImpl` + Panache repos — Hibernate Reactive nad PostgreSQL.
- `authz.AuthzProducer` — produkuje bean `PolicyDecisionPoint` napojený na OPA sidecar.
- `idempotency.IdempotencyConfig` — per-service `@Produces` Redis-backed `IdempotencyStore`.
- **Outbox balíček** `com.openbank.tpp.infrastructure` — `TppOutboxDispatcher` (`@Scheduled` každých 5 s, fault-tolerant publish), `KafkaTppOutboxEventPublisher` (vypouští na kanál `tpp-events-out`), `TppOutboxEntity` + `TppOutboxRepositoryImpl`. Porty (`TppOutboxEventPublisher`, `TppOutboxRepository`, `TppOutboxMessage`, `TppOutboxStatus`) žijí v `com.openbank.tpp.application.port.out`.

## Tok outbox → Kafka

Vzor transactional-outbox je zapojen end-to-end na úrovni infrastruktury:

1. Řádek doménové události by se zapsal do `tpp_outbox` (status `PENDING`) ve stejné transakci jako změna agregátu.
2. `TppOutboxDispatcher.dispatchScheduledBatch()` běží každých 5 s (batch 25, `concurrentExecution = SKIP`), čte zpracovatelné řádky a volá `publishWithResilience`.
3. `KafkaTppOutboxEventPublisher` posílá `Record<String,String>` (náhodný klíč, payload) na topic `openbank.tpp.registry.event`.
4. Při úspěchu se řádek označí `SENT`; při selhání `markFailed` zaznamená chybu a inkrementuje `attempt_count`.

> **Historie (issue #4007):** po většinu života této služby byl outbox transport plně přítomen — tabulka, dispatcher, publisher, Kafka kanál, zapisovací ACL, `dispatch-enabled: true` — a **nikdy nikdo nezapsal řádek**, takže `openbank.tpp.registry.event` neměl vůbec žádného producenta. `TppRegistryService` nyní emituje `TPP_REGISTERED` a `TPP_BLACKLISTED` (`TppEvents`); událost se předává do `TppRepository.save`/`update` jako POVINNÝ parametr, takže neexistuje bezudálostní varianta, kterou by šlo obejít. `TppOutboxWriteIT` proti reálné databázi dokazuje, že řádek vzniká ve stejné transakci jako změna stavu — mockovaný repozitář to poznat nedokáže, proto mezera přežila zelenou sadu testů.

## Souhrn klíčových portů

| Port | Směr | Adaptér |
|---|---|---|
| `TppRegistryUseCase` | příchozí | `TppRegistryResource` (REST) |
| `TppRepository` | odchozí | `TppRepositoryImpl` (Panache/PostgreSQL) |
| `TppOutboxRepository` | odchozí | `TppOutboxRepositoryImpl` (PostgreSQL) |
| `TppOutboxEventPublisher` | odchozí | `KafkaTppOutboxEventPublisher` (Kafka) |
| `PolicyDecisionPoint` | odchozí | `OpaSidecarPolicyDecisionPoint` (OPA) |
| `IdempotencyStore` | odchozí | `RedisIdempotencyStore` (Redis) |

## Průřezové aspekty (z openbank-libs)

- **Idempotence** — hlavička `Idempotency-Key` replayovaná z Redisu s per-operation cache klíči.
- **AuthZ** — `@Authorize` + OPA sidecar (ADR-0034), advisory režim (`authz.enforce=false`).
- **Observabilita** — OpenTelemetry OTLP export, SmallRye Health, JSON konzolové logování.
- **Odolnost** — MicroProfile Fault Tolerance na EBA syncu a outbox publisheru.
