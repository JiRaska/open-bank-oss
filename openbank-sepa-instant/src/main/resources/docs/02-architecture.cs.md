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
        │       │                   ├─► platba + outbox ─────────────────►│──► PostgreSQL
        │       │                   └─► outbox relay ────────────────────►│──► Kafka události
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
- **Výstupní porty** (`port/out`): `SctInstPaymentRepository`, `SctInstOutboxRepository`, `SanctionsScreeningPort` (+ `ScreeningUnavailableException`), `AmlCasePort` (+ `OpenAmlCaseCommand`, `AmlCaseRiskLevel`).
- `usecase/SctInstPaymentService` — orchestruje sankční bránu (viz tok níže).

### Adaptéry (`infrastructure/`)
- `rest/SctInstResource` — JAX-RS resource na `/api/v1/sepa-instant`; `@Authorize(action = "sctInstPayment.recall", …)` na recallu (ADR-0034).
- `rest/ExceptionMappers` — `NotFoundException → 404`, `BadRequestException → 400`.
- `client/SanctionsScreeningAdapter` + `SanctionsServiceClient` — REST klient k sanctions-service; mapuje vzdálený stav na lokální `ScreeningMatchStatus`, při nedostupnosti vyhodí `ScreeningUnavailableException`.
- `client/AmlCaseAdapter` + `AmlServiceClient` — REST klient k case store aml-service.
- `persistence/` — entity platby a outboxu, reaktivní repozitáře, `SctInstMapper`.
- `outbox/` — `SctInstOutboxDispatcher` vyzvedává a opakuje doručení uložených událostí.
- `kafka/` — `KafkaSctInstEventPublisher` odesílá uložený čtyřpolový payload.
- `authz/AuthzProducer` — zapojuje libs authz klienta (ADR-0034).

## Tok sankční brány (ADR-0032, adaptace na okamžitou linku)

Při `submit(command)`:

1. **Kontrola idempotence** — `repo.findByIdempotencyKey`; existuje-li záznam, vrátí se beze změny.
2. Sestaví se základní platba (`status = PENDING`, `submittedAt = now`).
3. **Prověrka jména plátce, pak příjemce** synchronně přes `SanctionsScreeningPort`.
4. `ScreeningPolicy.decide(results)`:
   - **CLEAR → proceed**: `status = PROCESSING`, nastaví `executionTimeoutAt = now + execution-timeout-seconds (10s)`, uloží platbu a outbox řádek `SctInstPaymentSubmitted` společně.
   - **REVIEW → hold**: uloží `PENDING`, otevře **HIGH** AML případ (`AML_HOLD`); nikdy nezúčtuje.
   - **BLOCK → reject**: uloží `REJECTED` (`reason = SANCTIONS_HIT`) a jeho událost společně; otevře **CRITICAL** AML případ.
5. **Výpadek prověrky** (`ScreeningUnavailableException`) → **fail closed**: podrží `PENDING`, otevře **MEDIUM** AML případ (`SCREENING_UNAVAILABLE`). Platba se nikdy neuvolní neprověřená (ADR-0032 §C).

Otevření AML případu je **best-effort** (`openCaseQuietly`): výpadek case store zaloguje chybu, ale nikdy nesmí překlopit již vynesený sankční verdikt.

## Publikování do Kafky

Zdrojový kandidát #12181 ukládá každý přechod platby s událostí a její outbox řádek v jedné PostgreSQL transakci. `SctInstOutboxDispatcher` pak řádek vyzvedne a opakuje doručení přes `KafkaSctInstEventPublisher` do `openbank.sepa.instant.events`. Doručení je at-least-once: potvrzení brokerem následované selháním před `markSent` může stejnou událost zopakovat. Dosavadní čtyřpolový Kafka payload se nemění; záznam je nově klíčován id platby (pořadí v rámci platby) a standardní outbox hlavičky nesou trvalé `ce-id` při každém pokusu. Audit-service použije toto ID před Kafka offsetem, pokud tělo nemá `eventId`, a proto musí být auditní konzument nasazen před producentem. Jde o popis zdrojového kódu, nikoli o důkaz schválení, merge či nasazení kandidáta.

| Přechod platby | Dosavadní doménová událost | Záruka v kandidátu |
| --- | --- | --- |
| Nová platba do `PROCESSING` | `SctInstPaymentSubmitted` | Platba a řádek události se potvrdí spolu; relay opakuje odeslání až do úspěchu nebo viditelného `DEAD`. |
| Nová platba do `REJECTED` (screening nebo schéma) | `SctInstPaymentRejected` | Platba a řádek události se potvrdí spolu; stejná politika opakování. |
| `PROCESSING` do `SETTLED` | `SctInstPaymentSettled` | Stav a událost se potvrdí spolu pod zámkem platby; stejná politika opakování. |
| `SETTLED` do `RECALLED` | `SctInstPaymentRecalled` | Stav a událost se potvrdí spolu pod zámkem platby; stejná politika opakování. |
| Nová platba zadržená ve `PENDING` | V dosavadním schématu není doménová událost platby | Řádek platby je trvalý, založení samostatného AML případu zůstává best effort. Doručení události platby zde není slíbeno. |

Idempotentní opakování existujícího podání vrátí uloženou platbu bez druhé události. Tato migrace zpětně nevytváří historické přechody; případná chybějící auditní fakta vyžadují samostatnou schválenou rekonciliaci.

## Odolnost a rate limiting

Konfigurováno pod `openbank.resilience` / `openbank.rate-limit` (SmallRye Fault Tolerance): circuit breaker (volume 20, failure ratio 0.3, success threshold 10, 5 s delay), retry (max 2, 100 ms delay, 50 ms jitter), timeout (10 s) a strop souběhu (`max-concurrent-requests: 500`).
