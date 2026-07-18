# Architektura

Služba dodržuje hexagonální architekturu OpenBank (ADR 0002): doménu bez frameworku v centru, aplikační vrstvu use-casů a portů a adaptéry na okraji.

## C4 — pohled na kontejner

```
        ┌──────────────────────────────────────────────────────────┐
        │                 openbank-consent-service                  │
        │                                                            │
   REST │  ┌──────────────┐   ┌─────────────────┐   ┌────────────┐  │
  ─────►│  │ ConsentResource│ │ ConsentService  │   │ Consent     │ │
  OIDC  │  │  (adaptér in)  │─►│ (aplikace)      │──►│ (doména)    │ │
        │  └──────────────┘   └───────┬─────────┘   └────────────┘  │
        │                             │ porty out                   │
        │     ┌───────────────────────┼───────────────────────┐    │
        │     ▼                       ▼                       ▼     │
        │ ConsentRepository    ConsentOutboxRepository ScaChallengeClient
        │  (stav + outbox,      (dispatch → Kafka)      (REST → sca)  │
        │   jedna transakce)                                         │
        └─────┬───────────────────────┬───────────────────┬─────────┘
              ▼                        ▼                   ▼
        PostgreSQL              consent_outbox        sca-service
       (openbank_consents)      → Kafka dispatcher
                                  openbank.consent.events
```

## Hexagonální vrstvy

### Doména (`domain/`) — nulové importy frameworku

- `model/Consent.kt` — agregát `Consent` (neměnná `data class`) s invarianty vynucenými v `init`:
  - alespoň jeden scope,
  - `validTo > validFrom`,
  - **strop PSD2 RTS čl. 10**: AIS scopy ⇒ max 90 dní platnosti; jinak 365 dní.
  - přechody jsou čisté funkce vracející novou kopii: `activate(scaSessionId)`, `revoke(reason)`, `reject()`; dotazy `isActive()`, `hasScope()`, `coversAccount(iban)`.
- enumy v `model`: `ConsentScope`, `GranteeType`, `ConsentStatus` a sealed typ `ConsentValidationResult` (`Valid` / `Invalid(reason, code)`).
- `event/ConsentEvents.kt` — `ConsentGranted`, `ConsentRevoked`, `ConsentExpired`, `ConsentRejected`, každá rozšiřuje sdílený `com.openbank.libs.domain.event.DomainEvent` (aggregateType `"Consent"`, version `1`).

### Aplikace (`application/`)

- **Vstupní porty** (`port/in/ConsentUseCases.kt`): `CreateConsentUseCase`, `ActivateConsentUseCase`, `RevokeConsentUseCase`, `GetConsentUseCase`, `ValidateConsentUseCase` se svými command DTO.
- **Výstupní porty** (`port/out/`): `ConsentRepository` (jeho `save(consent, event)` uloží změnu stavu i řádek outboxu v jedné transakci), `ScaChallengeClient` plus outbox porty (`ConsentOutboxRepository`, `ConsentOutboxEventPublisher`).
- `usecase/ConsentService.kt` — jediná `@ApplicationScoped` implementace všech pěti vstupních portů. Rovněž defenzivně znovu aplikuje strop platnosti a definuje typované doménové výjimky (např. `ConsentNotFoundException`, `ConsentNotOwnedByPartyException`, `ConsentScaNotCompletedException`).

### Adaptéry (`infrastructure/`)

- **REST in** — `rest/ConsentResource.kt` (`@Path("/api/v1/consents")`), DTO v `rest/dto/` a `rest/ExceptionMappers.kt` mapující doménové výjimky na `ApiError` + HTTP status.
- **Perzistence** — `persistence/entity/ConsentEntity.kt` + `ConsentOutboxEntity.kt` (Panache reactive), `persistence/repository/ConsentRepositoryImpl.kt` + `ConsentOutboxRepositoryImpl.kt`.
- **Messaging / outbox** — `outbox/ConsentOutboxDispatcher.kt` (scheduled) a `messaging/KafkaConsentOutboxEventPublisher.kt`. Události životního cyklu se do Kafky dostávají výhradně přes outbox (přímý publisher neexistuje).
- **SCA klient** — `client/ScaChallengeClient.kt`: MicroProfile `@RegisterRestClient(configKey = "sca-service")` obalený odolným adaptérem.
- **Authz** — `authz/AuthzProducer.kt` produkuje `OpaSidecarPolicyDecisionPoint` pro libs interceptor `@Authorize` (ADR 0034).
- **Idempotence** — `idempotency/IdempotencyConfig.kt` napojuje libs `IdempotencyStore` (Redis).

## Tok aktivace gated přes SCA

Aktivace je bezpečnostně kritický přechod (ADR 0021 — *žádné auto-approve*):

```
POST /consents/{id}/activate?scaSessionId=S
   → načti souhlas (404 pokud chybí)
   → odmítni pokud již ACTIVE (409)
   → ScaChallengeClient.getChallenge(S)        (REST → sca-service)
       · NotFound        → 422 SCA výzva nenalezena
       · jiná chyba      → 503 ověření nedostupné
   → vyžaduj challenge.partyId == consent.partyId
        AND challenge.purpose == "CONSENT_GRANT"  (jinak 422 mismatch)
   → vyžaduj challenge.status == "COMPLETED"       (jinak 422 not completed)
   → consent.activate(S); save; publish ConsentGranted
```

SCA klient je vyztužen `@Timeout(2000)`, `@Retry(maxRetries=2)` a `@CircuitBreaker`, takže nestabilní SCA služba degraduje do čisté 503 místo zaseknutí.

## Tok outbox → Kafka

Události životního cyklu se zapisují transakčně se změnou souhlasu (vzor transakční outbox):

```
ConsentService.publish(event)
   → insert řádku do consent_outbox (status PENDING)
            │
            ▼  každých 5s (ConsentOutboxDispatcher, @Scheduled, SKIP concurrent)
   listProcessable(BATCH_SIZE=25)
   → publishWithResilience(payload)        @Bulkhead @CircuitBreaker @Retry @Timeout(3000)
       · úspěch  → markSent(eventId)
       · selhání → markFailed(eventId, error)   (attempt_count++, retry v dalším ticku)
   → Kafka topic openbank.consent.events
```

Dispatcher polyká chyby na nejvyšší úrovni, takže scheduler nikdy nespadne; selhání jednotlivých událostí jsou izolována a ukládána do `last_error`.

## Klíčové porty

| Port | Směr | Adaptér |
|---|---|---|
| `CreateConsentUseCase` / `ActivateConsentUseCase` / `RevokeConsentUseCase` / `GetConsentUseCase` / `ValidateConsentUseCase` | in | `ConsentResource` |
| `ConsentRepository` | out | `ConsentRepositoryImpl` (Panache reactive, PostgreSQL); `save(consent, event)` zapíše změnu stavu + řádek outboxu v jedné transakci (poté dispatcher → Kafka) |
| `ConsentOutboxRepository` / `ConsentOutboxEventPublisher` | out | `ConsentOutboxRepositoryImpl` / `KafkaConsentOutboxEventPublisher` |
| `ScaChallengeClient` | out | `ResilientScaChallengeClient` → `sca-service` |
| `PolicyDecisionPoint` | out | `OpaSidecarPolicyDecisionPoint` (OPA sidecar) |

## Významné průřezové aspekty

- **Reaktivní end-to-end** — Hibernate Reactive + Vert.x PG klient; resource funkce jsou `suspend` (Kotlin korutiny přemostěné přes kotlinx-coroutines-reactive).
- **Odolnost** — SmallRye Fault Tolerance na volání SCA i na dispatch outboxu.
- **Observabilita** — Micrometer/Prometheus metriky a OpenTelemetry tracing (OTLP) se `service.name = openbank-consent-service`.
- **Bezpečnostní hlavičky** — striktní CSP, HSTS, `X-Frame-Options: DENY` atd., nastavené globálně v `application.yaml`.
