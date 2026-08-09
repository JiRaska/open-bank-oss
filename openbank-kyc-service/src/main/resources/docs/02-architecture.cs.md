# Architektura

Služba dodržuje hexagonální uspořádání OpenBank (ADR-0002): doménu bez frameworku, aplikační vrstvu use-casů a infrastrukturní adaptéry, které přemosťují HTTP, Kafku a PostgreSQL.

## C4 — pohled na kontejner

```
            ┌──────────────────────────────────────────────────┐
            │                openbank-kyc-service                │
            │                                                    │
  admin-ui ─┤  KycResource (REST, :8114)                         │
   (JWT)    │        │                                           │
            │        ▼                                           │
            │  KycService (aplikace)                             │
            │        │            ▲                              │
            │        ▼            │                              │
            │  KycCaseRepository  PartyEventConsumer ◄── Kafka   │ ◄─ openbank.party.events
            │  (Panache/PG)       (suspend @Incoming)            │
            │        │                                           │
            │        ▼                                           │
            │  kyc_cases / kyc_outbox  ──► KycOutboxDispatcher ──┼─► openbank.kyc.events
            │  (PostgreSQL: openbank_kyc)   (@Scheduled 5s)      │
            └──────────────────────────────────────────────────┘
```

## Hexagonální vrstvy

| Vrstva | Balíček | Odpovědnost |
|---|---|---|
| **Doména** | `com.openbank.kyc.domain.model` | `KycCase`, `KycCheck` a enumy `KycCaseStatus`, `RiskLevel`, `CheckType`, `CheckStatus`. Čistý Kotlin, nulové importy frameworku. |
| **Aplikace** | `com.openbank.kyc.application` | `KycService` — use-casy open / list / get / update-check / approve / reject, včetně idempotentního `openCaseForParty`. Definuje výstupní porty pod `application.port.out`. |
| **Adaptéry — vstupní** | `infrastructure.rest`, `infrastructure.kafka` | `KycResource` (REST), `PartyEventConsumer` (`PARTY_CREATED` → auto-open). |
| **Adaptéry — výstupní** | `infrastructure.persistence`, `infrastructure.kafka`, `infrastructure.outbox` | `KycRepository` (Panache), `KafkaKycOutboxEventPublisher`, `KycOutboxDispatcher`. |
| **Průřezové** | `infrastructure.authz` | `AuthzProducer` napojující OPA-backed `@Authorize` (ADR-0034). |

### Klíčové porty (`application.port.out`)

- `KycCaseRepository` — `save`, `update`, `findById`, `findByPartyId`, `listAll`/`listByStatus`, `countAll`/`countByStatus`.
- `KycOutboxPort` / `KycOutboxRepository` — zápis a vyprazdňování řádků outboxu.
- `KycOutboxEventPublisher` — publikace payloadu outboxu do Kafky.

## Tok outbox → Kafka

KYC rozhodnutí se propagují vzorem transakčního outboxu, aby se změna stavu a její událost commitovaly atomicky:

1. Use-case v `KycService` zmutuje `KycCase` a (pro události kryté outboxem) zapíše řádek `kyc_outbox` ve stejné transakci.
2. `KycOutboxDispatcher` běží každých **5 s** (`@Scheduled`, `concurrentExecution = SKIP`), načte až **25** zpracovatelných řádků a publikuje každý přes `publishWithResilience`.
3. `publishWithResilience` je obalen MicroProfile Fault Tolerance — `@Bulkhead(1)`, `@CircuitBreaker`, `@Retry(2)`, `@Timeout(3000)` — pro izolaci výpadků Kafky.
4. Při úspěchu se řádek označí `SENT`; při selhání `markFailed` zaznamená chybu a `attempt_count` pro pozdější retry.

> Poznámka: události životního cyklu KYC opouštějí službu POUZE přes `kyc_outbox`, zapsaný ve stejné transakci jako změna stavu případu (issue #4007) a přeposílaný kanálem `kyc-outbox-out` do topicu `openbank.kyc.events`. Přímý emitor `kyc-events-out`, který tytéž události publikoval až po commitu, byl odstraněn — dva publisheři nad jedním topicem by soupeřili a atomický může být jen jeden.

### Vstupní konzument

`PartyEventConsumer` odebírá `openbank.party.events` (skupina `kyc-service-party`, `auto.offset.reset=earliest`). Reaguje pouze na `PARTY_CREATED`, ostatní typy ignoruje a je **odolný vůči poison-pill**: jakékoli selhání parsování/domény je zalogováno a zpráva acknowledgnuta. Protože `openCaseForParty` je idempotentní (re-read při race na `uq_kyc_cases_active_party`), replay topicu nikdy nevytvoří duplicitní otevřené případy.

## Transakce a konkurence

- Reaktivní stack od konce ke konci: Hibernate Reactive (Panache) nad Vert.x PG klientem; REST handlery a konzument jsou Kotlin `suspend` funkce dispatchované na event loopu.
- Doménová idempotence je vynucena v databázi parciálním unikátním indexem `uq_kyc_cases_active_party` (V5), který chrání před replayem a budoucím multi-pod scale-outem.

## Sandboxové straight-through zpracování

`KycService.autoEvaluateAndApprove` existuje pouze pro sandbox: když `openbank.kyc.auto-approve=true`, případ auto-otevřený z `PARTY_CREATED` má všechny kontroly nastaveny na `PASSED` a je schválen revizorem `sandbox-auto-approval`, takže onboarding proběhne bez operátora. Tento flag MUSÍ v produkci zůstat `false` — endpoint schválení čtyřma očima je pak jediná cesta schválení (ADR-0068).
