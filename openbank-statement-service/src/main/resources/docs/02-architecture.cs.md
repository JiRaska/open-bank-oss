# Architektura

Služba dodržuje hexagonální architekturu OpenBank ([ADR 0002](../../../../docs/adr/0002-hexagonal-architecture-per-service.md)): doména bez frameworku, aplikační vrstva use case a portů a infrastrukturní adaptéry na okrajích. Definující vlastností této služby je **determinismus**: uchovávaný záznam je malý a každý vyrenderovaný výstup je jeho čistou, bajt po bajtu identickou projekcí.

## C4 — kontext

```
        ┌──────────────┐         ┌──────────────────────┐
        │  account-svc │──Kafka─►│                      │
        └──────────────┘         │  statement-service   │
        ┌──────────────┐  REST   │                      │──Kafka──► konzumenti výpisů
        │ transaction  │◄────────│  (uzávěrka + render) │           (audit, downstream)
        │ balance      │◄────────│                      │
        │ account/party│◄────────│                      │
        └──────────────┘         └──────────┬───────────┘
                                            │
                                       PostgreSQL
                                    (openbank_statement)
```

## Hexagonální vrstvy

### Doména (`domain/`, nula importů frameworku)

- **`domain/model`** — `StatementModel` (kanonický neměnný agregát, vlastní `netMovement`), `StatementEntry`, `BalanceAnchor`, `StatementPeriod` (uchovávaný záznam), `StatementFormat`, `CreditDebit`, `PeriodCloseStatus`, `CloseRun` / `CloseTrigger`.
- **`domain/reconcile`** — `ReconciliationPolicy`: čistá, fail-closed. `closing = opening + netMovement` **se musí rovnat** koncovému zůstatku hlášenému balance-service (porovnáno přes `BigDecimal.compareTo`, takže `100` vs `100.00` falešně neselže). Nesoulad vrátí `Result.Mismatch`.
- **`domain/render`** — `Camt053Renderer`, `Mt940Renderer`, `PdfRenderer` a `StatementRenderer` (dispatch). Všechna časová razítka berou z `StatementModel.closedAt`, nikdy z hodin systému — re-render je bajt po bajtu identický (hlídáno testy rendererů).
- **`domain/close`** — `CloseCalendar`: odvozuje měsíční okna, která kapsa stále dluží (self-healing dohánění).

### Aplikace (`application/`)

- **Příchozí porty (`port/in`)** — `ClosePeriodUseCase`, `ClosePocketUseCase`, `RenderStatementUseCase`, `ListStatementsUseCase`, `AdHocExportUseCase`, `RunCloseUseCase`, `CloseRunQueryUseCase`.
- **Odchozí porty (`port/out`)** — `AccountInfoPort`, `BookedEntryPort`, `BalancePort`, `StatementPeriodRepository`, `AccountRegistry`, `CloseRunPorts`, `CloseMetricsPort`.
- **Use case** — `StatementService` (zapojení close / render / export / list; orchestruje rekonciliaci + přidělení sekvence + atomický save-with-outbox), `CloseOrchestrator` (self-healing plánovaný/manuální průchod uzávěrkou; izoluje selhání per-kapsa, zaznamenává výsledek běhu, emituje `period.close_failed`).

### Infrastruktura (`infrastructure/`)

- **`rest`** — `StatementResource` (`/api/v1/statements`), `CloseRunResource` (`/api/v1/statements/close-runs`). Driving adaptéry; překládají `ReconciliationException` → 409, `StatementNotFoundException` → 404.
- **`client`** — `RestClients` + `RestPortAdapters`: MicroProfile rest-client adaptéry pro čtení transaction / balance / account / party, nesoucí OIDC client-credentials bearer (`OidcClientRequestReactiveFilter`).
- **`kafka`** — `AccountRegistryConsumer`: konzumuje `openbank.accounts.account.created`, filtruje podle typu události (`AccountCreated`), idempotentně upsertuje do lokálního `account_registry` (`auto.offset.reset=earliest` doplní historii při prvním nasazení).
- **`scheduler`** — `PeriodCloseScheduler`: cron `0 30 2 1 * ?` (1. v měsíci, 02:30), `concurrentExecution = SKIP`, řízeno `openbank.statement.scheduled-close.enabled`.
- **`outbox`** — `StatementOutboxDispatcher` + `KafkaStatementOutboxEventPublisher` + `StatementOutboxRepository` (viz níže).
- **`persistence`** — reaktivní Panache entity, mappery, repozitáře pro `statement_period`, `statement_outbox`, `account_registry`, `statement_close_run`, `statement_close_failure`.
- **`metrics`** — `CloseMetricsAdapter`: Micrometer/Prometheus čítače pro kadenci uzávěrek.

## Tok uzávěrky období (fail-closed)

```
POST /{accountId}/close (from,to)
  │
  ▼
StatementService.closeMonth
  │  pro každou měnovou kapsu (sekvenčně, concatenate — race-free sekvence)
  ▼
closePocket → findByPeriod  ──► existuje? ── ano ──► vrať existující (idempotentní)
  │                                  └─ ne
  ▼
mintPeriod:
  • bookedEntries(account,ccy,from,to)         (transaction-service)
  • opening = koncový zůstatek předchozí uzávěrky, jinak zůstatek den předtím (balance-service)
  • hlášený koncový zůstatek                    (balance-service)
  • ReconciliationPolicy.reconcile(opening, netMovement, reported)
        ├─ Mismatch  → ReconciliationException → HTTP 409 (ŽÁDNÝ záznam, ŽÁDNÁ událost)
        └─ Reconciled → nextLegalSequence → StatementPeriod
                       → saveWithOutbox (záznam období + period.closed událost, jedna transakce)
```

Počáteční zůstatek se řetězí: počátek kapsy je koncový zůstatek předchozí uzávěrky (kontinuita), s fallbackem na zůstatek balance-service den před obdobím, pokud předchozí uzávěrka neexistuje.

## Outbox → Kafka (ADR-0050)

`saveWithOutbox` zapíše řádek `statement_period` a outbox řádek `account.statement.period.closed.v1` v **jedné transakci**, takže pád nikdy nemůže zanechat uzavřené období, jehož událost nebyla nikdy emitována.

`StatementOutboxDispatcher` (`@Scheduled every 5s`, `concurrentExecution = SKIP`, Deployment připnut na `replicas: 1` — jediný writer, ADR-0050 N4) vyprazdňuje řádky PENDING/FAILED na Vert.x event loopu, sekvenčně (`transformToUniAndConcatenate`, zachovává pořadí per-agregát). `KafkaStatementOutboxEventPublisher` publikuje s:

- **Partition key = `aggregate_id`** (N2) — všechny události jednoho agregátu výpisu si drží pořadí.
- **Hlavičky `ce-id` / `idempotency-key` = `event_id`** (N3) — at-least-once doručení je downstream bezpečně deduplikováno.
- **Fault tolerance** — `@Bulkhead`, `@CircuitBreaker`, `@Retry`, `@Timeout`.

Opakovaná selhání publikace inkrementují `attempt_count`; při `MAX_ATTEMPTS = 10` je řádek zaparkován do terminálního stavu **DEAD** (N5) a vyloučen z dotazu na zpracovatelné, s WARN logem pro alerting.

## Klíčové porty (shrnutí)

| Port | Směr | Adaptér | Podklad |
|---|---|---|---|
| `ClosePeriodUseCase` / `RenderStatementUseCase` / … | in | `StatementResource` | REST |
| `RunCloseUseCase` / `CloseRunQueryUseCase` | in | `CloseRunResource`, `PeriodCloseScheduler` | REST / cron |
| `BookedEntryPort` | out | `RestPortAdapters` | transaction-service |
| `BalancePort` | out | `RestPortAdapters` | balance-service |
| `AccountInfoPort` | out | `RestPortAdapters` | account / party |
| `StatementPeriodRepository` / `CloseRunPorts` | out | persistence | PostgreSQL |
| `AccountRegistry` | out | `AccountRegistryConsumer` + repo | Kafka + PostgreSQL |
| (outbox) | out | `StatementOutboxDispatcher` | PostgreSQL → Kafka |
