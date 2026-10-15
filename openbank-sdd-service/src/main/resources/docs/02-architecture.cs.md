# Architektura

## C4 — Kontext systému

```mermaid
graph LR
  admin[admin-ui]
  pay[platební / clearingové služby]
  audit[audit-service]
  led[ledger / zaúčtování platby]
  notif[notification]

  sdd[(sdd-service)]:::svc
  db[(PostgreSQL<br/>db: openbank_sdd)]
  kafka[(Kafka<br/>openbank.sdd.event)]

  admin -- "POST/GET /sdd/mandates" --> sdd
  pay -- "POST /sdd/collections/authorise<br/>(fail-closed rozhodnutí)" --> sdd

  sdd --> db
  sdd -- "outbox → publish" --> kafka

  kafka --> led
  kafka --> audit
  kafka --> notif

  classDef svc fill:#dbeafe,stroke:#2563eb
```

## C4 — Kontejner (vnitřní struktura)

```mermaid
graph TB
  subgraph "openbank-sdd-service (Quarkus, reaktivně)"
    direction TB
    rest[REST<br/>SddResource<br/>ExceptionMappers]
    uc[Application<br/>SddMandateService<br/>in/out porty]
    dom[Domain<br/>SddMandate + MandateLifecycle<br/>CollectionAuthorisationPolicy<br/>RefundPolicy]
    persist[Persistence<br/>SddMandateRepositoryImpl<br/>Hibernate Reactive / Panache]
    outbox["Outbox<br/>SddOutboxDispatcher<br/>@Scheduled každých 5s"]
    sched[Scheduler<br/>MandateExpiryScheduler<br/>cron, defaultně vypnuto]
  end

  rest --> uc
  uc --> dom
  uc --> persist
  uc --> outbox
  sched --> persist

  persist -.-> db[(PostgreSQL)]
  outbox -.-> kafka[(Kafka)]
```

## Hexagonální vrstvy

Struktura balíčků odráží **ports-and-adapters** (ADR-0002):

```
com.openbank.sdd/
├── domain/                    ◄── jádro — nulové závislosti na frameworku
│   ├── model/                 SddMandate, MandateAmendment, enumy (SddScheme, SequenceType, MandateStatus)
│   ├── lifecycle/             MandateLifecycle (čisté přechody + idle-expiry)
│   ├── authorise/             CollectionAuthorisationPolicy (fail-closed ACCEPT/REJECT/REFUSE)
│   └── refund/                RefundPolicy (lhůty 8 týdnů / 13 měsíců)
│
├── application/               ◄── orchestrace use-case
│   ├── port/in/               vstupní porty (use-case Register/Confirm/Manage/Amend/Authorise/AssessRefund/List)
│   ├── port/out/              výstupní porty (SddMandateRepository, SddOutbox)
│   └── usecase/               SddMandateService (zapojí porty, persistuje, emituje)
│
└── infrastructure/            ◄── adaptéry
    ├── rest/                  SddResource (JAX-RS), DTO, ExceptionMappers
    ├── persistence/           SddMandateRepositoryImpl, entity, mapper
    ├── outbox/                SddOutboxDispatcher, KafkaSddOutboxEventPublisher, SddOutboxRepository
    └── scheduler/             MandateExpiryScheduler (cron, opt-in)
```

**Pravidlo závislostí:** `domain` ← `application` ← `infrastructure`. Doména vlastní každé rozhodnutí — stavový automat mandátu, fail-closed autorizační politiku i aritmetiku lhůt refundu — je bez frameworku a bez hodin (volající předávají `asOf`). Díky tomu je regulační logika jednotkově testovatelná izolovaně.

## Klíčové porty

| Port | Směr | Účel |
|---|---|---|
| `RegisterMandateUseCase` / `ConfirmMandateUseCase` / `ManageMandateUseCase` / `AmendMandateUseCase` | vstupní | příkazy životního cyklu mandátu |
| `AuthoriseCollectionUseCase` | vstupní | fail-closed autorizace inkasa |
| `AssessRefundUseCase` | vstupní | posouzení lhůty refundu |
| `ListMandatesUseCase` | vstupní | výpisy/načtení (čtení) |
| `SddMandateRepository` | výstupní | trezor mandátů (find by id / podle reference `(CID, UMR)` / podle účtu / živé mandáty) |
| `SddOutbox` | výstupní | vložení outbox zprávy ve stejné transakci jako zápis mandátu |

## Tok outboxu

```mermaid
sequenceDiagram
  participant C as Klient
  participant R as SddResource
  participant S as SddMandateService
  participant DB as PostgreSQL
  participant D as SddOutboxDispatcher
  participant K as Kafka

  C->>R: POST /sdd/mandates (nebo lifecycle operace)
  R->>S: register/confirm/authorise(...)
  S->>DB: BEGIN TX
  S->>DB: UPSERT sdd_mandate
  S->>DB: INSERT sdd_outbox (event, status=PENDING)
  S->>DB: COMMIT
  R-->>C: 201 / 200

  loop každých 5s (concurrentExecution = SKIP)
    D->>DB: claim the oldest due row per aggregate (FOR UPDATE SKIP LOCKED, status=DISPATCHING)
    D->>K: publish do openbank.sdd.event (key = aggregate_id, header ce-id = event_id)
    D->>DB: UPDATE sdd_outbox SET status = SENT
  end
```

**Garance outboxu (ADR-0050 / ADR-0003):**

- **Kernel outbox v2 (ADR-0327, #11874)** — repozitář je sdílený `AbstractPanacheOutboxRepository`. Každý claim bere jen nejstarší splatný řádek každého agregátu (`FOR UPDATE SKIP LOCKED`, označený `DISPATCHING`), takže pořadí per-agregát platí i při více dispatcherech; různé agregáty z jednoho claimu se publikují souběžně (`@Bulkhead(OutboxDispatch.SEND_CONCURRENCY)`). Jeden tick claimuje, dokud claim nic neodešle; neúspěšné řádky čekají s backoffem a řádek, který ve stavu `DISPATCHING` zanechal spadlý pod, se po uplynutí stale-claim okna převezme znovu. `concurrentExecution = SKIP` dál brání překryvu v rámci JVM.
- **Partition key = aggregate_id (N2)** — každá událost jednoho mandátu padne na stejnou partition, čímž se zachová pořadí per-mandát.
- **event_id jako idempotenční klíč (N3)** — nesen jako Kafka hlavičky `ce-id` / `idempotency-key`, takže at-least-once doručení konzumenti bezpečně deduplikují.
- **Zpracování poison zpráv (N5)** — selhání publish jednotlivého řádku jsou izolovaná (`recoverWithUni` → `markFailed`); po `MAX_ATTEMPTS` (10) řádek přejde do terminálního stavu `DEAD`, je vyloučen z dotazu a emituje WARN, na který se napojí operátorský alert.
- **Odolnost** — Kafka publish je obalen MicroProfile Fault Tolerance (`@Bulkhead`, `@CircuitBreaker`, `@Retry` 2x, `@Timeout` 3s).

Dispatcher běží na Vert.x event loopu (vrací `Uni<Void>`), takže reaktivní Panache session se vždy otevírají on-context (ADR-0050 N1).

## Komponenty z `openbank-libs`

| Modul | Použití zde |
|---|---|
| `libs.web.ServiceInfoResource` | `/api/v1/info` (build metadata, X-API-Version / X-Service-Version) |
| `libs.docs.DocsResource` | **tato dokumentace** (`/q/openbank/docs`) |
| `libs.util.BuildInfo` | runtime snapshot tech stacku |
| `libs.security.*` | OIDC/JWT plumbing, bootstrap verifikace |

## Principy

1. **Hranice agregátu = SddMandate** — identita je `(creditorIdentifier, UMR)`; životní cyklus i amendments jsou součástí agregátu.
2. **Fail-closed autorizace** — politika vrací ACCEPT až po projití všech kontrol v pořadí; jakákoli závada REJECTuje (technicky) nebo REFUSEuje (právo plátce).
3. **Čistá doména, žádné wall-clock hodiny** — životní cyklus a aritmetika refundu berou explicitní `asOf`/clock seam; žádná vzdálená volání uvnitř transakce.
4. **Transakční outbox** — zápis mandátu a vložení události sdílí jednu transakci; asynchronní doručení přes dispatcher.
5. **v1 nikdy nehýbe penězi** — ACCEPT emituje událost pro navazující zaúčtovací cestu; nevratné odepsání/refund je delegováno.

## Omezení iniciátoři mandátů (ADR-0335 D6)

`ScopedMandateService` omezuje pension-service na mandáty, jejichž `creditorIdentifier` je jeho
nakonfigurovaný SEPA identifikátor věřitele (`OPENBANK_SDD_SCOPED_INITIATORS_PENSION_CREDITOR_IDENTIFIER`).
Musí uvést `partyId` subjektu a projekce vlastnictví z account-service, volaná přes mTLS, musí potvrdit,
že debetní IBAN vlastní a má aktivní tato strana a že označuje mandátovaný účet. Pension smí rušit jen
mandáty svého věřitele. Každé odmítnutí je 403 dřív, než se cokoli uloží.
