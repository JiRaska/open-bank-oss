# Architektura

Služba dodržuje hexagonální architekturu (porty a adaptéry) předepsanou [ADR 0002](../../../../docs/adr/0002-hexagonal-architecture-per-service.md). Doménová vrstva má **nulové framework importy**.

## C4 — kontejnerový pohled

```
        ┌──────────────────────────────────────────────────────────────┐
        │  openbank-sepa-instant  (Quarkus, port 8127 / mgmt 8085)       │
        │                                                                │
        │  REST adaptér ──► aplikační use-cases ──► doména               │
        │       │                   │                                    │
        │       │                   ├─► SanctionsScreeningPort ─────────►│──► sanctions-service
        │       │                   ├─► AmlCasePort ────────────────────►│──► aml-service
        │       │                   ├─► SctInstPaymentRepository ───────►│──► PostgreSQL
        │       │                   └─► SctInstEventPublisher / outbox ─►│
        │       │                                                        │
        │  OutboxDispatcher (@Scheduled 5s) ──► Kafka emitter ──────────►│──► openbank.sepa.instant.events
        └──────────────────────────────────────────────────────────────┘
```

## Hexagonální vrstvy

### Doména (`domain/`)
Čistý Kotlin, žádný Quarkus.

- `model/SctInstPayment` — agregát (data class) a enum `SctInstStatus` (`PENDING, PROCESSING, SETTLED, REJECTED, TIMEOUT, RECALLED`).
- `event/SctInstEvents` — zapečetěná (sealed) hierarchie `SctInstEvent`: `SctInstPaymentSubmitted`, `SctInstPaymentSettled`, `SctInstPaymentRejected`, `SctInstPaymentTimeout`, `SctInstPaymentRecalled`.
- `screening/ScreeningPolicy` — čistý rozhodovací objekt. `decide(results)` vrací `BLOCK > REVIEW > CLEAR`:
  - **BLOCK** — jakýkoli `HIT`, jakýkoli `ESCALATED`, nebo `POTENTIAL_HIT` striktně nad `POTENTIAL_HIT_BLOCK_THRESHOLD = 0.85`.
  - **REVIEW** — jakýkoli podprahový `POTENTIAL_HIT` (kandidát na false-positive → lidská kontrola).
  - **CLEAR** — vše ostatní (`CLEAR` / `WHITELISTED`, včetně prázdné množiny výsledků).
  Práh záměrně zrcadlí vlastní `isHighRisk` sankční služby, aby se obě nerozcházely.

### Aplikace (`application/`)
Use-cases a porty.

- **Vstupní porty** (`port/in`): `SubmitSctInstPaymentUseCase`, `GetSctInstPaymentUseCase`, `RecallSctInstPaymentUseCase` + `SubmitSctInstCommand`.
- **Výstupní porty** (`port/out`): `SctInstPaymentRepository`, `SctInstEventPublisher`, `SanctionsScreeningPort` (+ `ScreeningUnavailableException`), `AmlCasePort` (+ `OpenAmlCaseCommand`, `AmlCaseRiskLevel`), `SctInstOutboxPort`.
- `usecase/SctInstPaymentService` — orchestruje sankční bránu (viz tok níže).

### Adaptéry (`infrastructure/`)
- `rest/SctInstResource` — JAX-RS resource na `/api/v1/sepa-instant`; `@Authorize(action = "sctInstPayment.recall", …)` na recallu (ADR-0034).
- `rest/ExceptionMappers` — `NotFoundException → 404`, `BadRequestException → 400`.
- `client/SanctionsScreeningAdapter` + `SanctionsServiceClient` — REST klient k sanctions-service; mapuje vzdálený stav na lokální `ScreeningMatchStatus`, při nedostupnosti vyhodí `ScreeningUnavailableException`.
- `client/AmlCaseAdapter` + `AmlServiceClient` — REST klient k case store aml-service.
- `persistence/` — `SctInstPaymentEntity` / `SctInstOutboxEntity`, Panache reaktivní repozitáře, `SctInstMapper`.
- `outbox/SctInstOutboxDispatcher` — plánovaný poller.
- `kafka/` — `KafkaSctInstEventPublisher`, `KafkaSctInstOutboxEventPublisher`.
- `authz/AuthzProducer` — zapojuje libs authz klienta (ADR-0034).

## Tok sankční brány (ADR-0032, adaptace na okamžitou linku)

Při `submit(command)`:

1. **Kontrola idempotence** — `repo.findByIdempotencyKey`; existuje-li záznam, vrátí se beze změny.
2. Sestaví se základní platba (`status = PENDING`, `submittedAt = now`).
3. **Prověrka jména plátce, pak příjemce** synchronně přes `SanctionsScreeningPort`.
4. `ScreeningPolicy.decide(results)`:
   - **CLEAR → proceed**: `status = PROCESSING`, nastaví `executionTimeoutAt = now + execution-timeout-seconds (10s)`, uloží, publikuje `SctInstPaymentSubmitted`.
   - **REVIEW → hold**: uloží `PENDING`, otevře **HIGH** AML případ (`AML_HOLD`); nikdy nezúčtuje.
   - **BLOCK → reject**: uloží `REJECTED` (`reason = SANCTIONS_HIT`), otevře **CRITICAL** AML případ, publikuje `SctInstPaymentRejected`.
5. **Výpadek prověrky** (`ScreeningUnavailableException`) → **fail closed**: podrží `PENDING`, otevře **MEDIUM** AML případ (`SCREENING_UNAVAILABLE`). Platba se nikdy neuvolní neprověřená (ADR-0032 §C).

Otevření AML případu je **best-effort** (`openCaseQuietly`): výpadek case store zaloguje chybu, ale nikdy nesmí překlopit již vynesený sankční verdikt.

## Tok outbox → Kafka

Doménové události se ukládají do `sct_inst_outbox` ve stejné transakci jako agregát. `SctInstOutboxDispatcher` běží `@Scheduled(every = "5s", delayed = "5s", concurrentExecution = SKIP)`, načte až `BATCH_SIZE = 25` zpracovatelných řádků uvnitř Panache session, odešle každý payload do kanálu `sct-inst-events-out` (Kafka topic `openbank.sepa.instant.events`), pak řádek označí jako odeslaný nebo neúspěšný. To dává at-least-once doručení oddělené od request cesty.

## Odolnost a rate limiting

Konfigurováno pod `openbank.resilience` / `openbank.rate-limit` (SmallRye Fault Tolerance): circuit breaker (volume 20, failure ratio 0.3, success threshold 10, 5 s delay), retry (max 2, 100 ms delay, 50 ms jitter), timeout (10 s) a strop souběhu (`max-concurrent-requests: 500`).
