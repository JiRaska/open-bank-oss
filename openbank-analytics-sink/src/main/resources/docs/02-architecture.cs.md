# Architektura

## C4 — pohled na kontejnery

```
        ┌──────────────────────────────────────────────────────────────┐
        │                    openbank-analytics-sink                     │
        │                                                                │
 Kafka  │  ┌───────────────────┐   AnalyticsEnvelope   ┌──────────────┐ │
 topiky ─┼─►│ AnalyticsConsumer │ ──── (maskováno) ───► │ AnalyticsSink│ │──► ClickHouse
        │  │  @Incoming        │                       │   (port)     │ │   bronze/silver/gold
        │  └─────────┬─────────┘                       └──────────────┘ │
        │            │ nezpracovatelné / neznámé schéma                  │
        │            ▼                                                   │
        │       DeadLetterSink ──► dead_letter_events (karanténa)        │
        │                                                                │
 REST   │  ┌──────────────┐ ┌────────────────────┐ ┌───────────────┐    │
 (8134) ─┼─►│ BackfillRes. │ │ ReconciliationRes. │ │ ErasureRes.   │    │
        │  └──────┬───────┘ └─────────┬──────────┘ └──────┬────────┘     │
        │   SensitiveReload      ReconciliationJob    ErasureService     │
        │   (maker-checker)      (vs zdroj pravdy)     (crypto-shred)    │
        │            │                  │                   │            │
        │   ProposalStore        ReconciliationPorts    CryptoErasure    │
        │   WormArchive (kotvy)    BackfillSource          (Vault)       │
        └──────────────────────────────────────────────────────────────┘
            │ mgmt 8086 /q (health, metriky, docs)
```

## Hexagonální vrstvy (ADR-0002)

Služba dodržuje hexagonální rozdělení platformy. Doménové/kontraktní typy žijí v **`openbank-libs` `com.openbank.libs.analytics`** (`AnalyticsEnvelope`, `AnalyticsProjections`, `BackfillRequest`, `Proposal`/maker-checker, `RetentionPolicies`, `Integrity`, `Reconciliation`), takže doménová logika bez frameworku je sdílená napříč platformou.

### Aplikační vrstva — `com.openbank.analytics.application`
Orchestrace, bez vazby na framework I/O:
- **`AnalyticsConsumer`** — jediná cesta příjmu. Čte surový JSON z Kafky, sestaví `AnalyticsEnvelope`, maskuje PII, aplikuje schema governance, zapíše přes sink, zaznamená freshness.
- **`PayloadMasker`** — maskování PII podle názvu pole (email, IBAN, PAN, telefon, jméno, rodné číslo) pomocí strategií `libs.security.PiiMask`, rekurzivně přes JSON tělo.
- **`SchemaGovernance`** — přijímá/karantenuje události podle `eventType:schemaVersion` vůči katalogu (config nebo Apicurio).
- **`SensitiveReloadService`** — životní cyklus recovery loadu na čtyři oči (PROPOSED→APPROVED/REJECTED→EXECUTED) přes stavový automat `Proposal`.
- **`BackfillService`** — provádí skutečné chunkované reload okno.
- **`ErasureService`** — aplikuje per-kategorii `RetentionPolicies`; crypto-shredne mazatelná data, odmítne kategorie pod zákonným hold s doloženým právním základem.
- **`IngestFreshness`** — sleduje ingest lag a počet dead-letterů pro readiness probe.

### Aplikační porty (out) — `com.openbank.analytics.application.port.out`
- `AnalyticsSink` — zápis envelope do bronze vrstvy skladu.
- `DeadLetterSink` — karanténa nezpracovatelné / karantenované zprávy.
- `SchemaCatalogSource` — dodá přijímaný katalog `eventType:version`.
- `WormArchive` — zapečetí tamper-evidence integrity kotvy do neměnného úložiště.
- `ErasurePort` / `CryptoErasure` — crypto-shred pseudonymního agregátního klíče.
- `BackfillSource` — dodá události pro reload okno.
- `ProposalStore` — perzistuje maker-checker stopu návrhů.
- `ReconciliationPorts` — čtečky stavu skladu a zdroje pravdy.

### Infrastrukturní vrstva — `com.openbank.analytics.infrastructure`
Adaptéry vázané build-time konfigurací, každý s offline výchozím nastavením, takže služba je buildovatelná offline:
- **sink:** `LoggingAnalyticsSink` (`@Default`) / `ClickHouseAnalyticsSink` (`type=clickhouse`); `LoggingDeadLetterSink`.
- **clickhouse:** `ClickHouseClient` (HTTP rozhraní), `ClickHouseAnalyticsSink`, `ClickHouseProposalStore`, `ClickHouseWormArchive`, `ClickHouseWarehouseStateReader`.
- **schema:** `ConfigSchemaCatalogSource` (`@Default`) / `ApicurioSchemaCatalogSource` (`backend=apicurio`).
- **erasure:** `NoOpCryptoErasure` (`@Default`) / `VaultCryptoErasure` (`backend=vault`).
- **worm:** `LoggingWormArchive` / `ClickHouseWormArchive` / `S3WormArchive` (`backend=s3`, S3 Object Lock režim COMPLIANCE).
- **reconcile:** `ReconciliationJob` (plánovaný cron + manuálně), `NoOpReconciliationPorts`/`HttpReconciliationSource` (fan-out na role-gated reconciliation-summary endpoint každé služby), `NoOpBackfillSource`.
- **rest:** `BackfillResource`, `ReconciliationResource`, `ErasureResource`, `MakerCheckerExceptionMapper`.
- **health:** `IngestHealthCheck` (readiness na lag/DLQ).
- **`DataResidencyValidator`** — startup guard (boot selže, pokud region není na allow-listu).

## Tok událost → bronze

1. Produkující služba zapíše doménovou událost do svého transakčního outboxu; outbox ji relayuje do Kafky (ADR-0003).
2. `AnalyticsConsumer.@Incoming("analytics-events-in")` přijme surový JSON (at-least-once).
3. Událost se namapuje na `AnalyticsEnvelope`; `aggregateType`/`aggregateId`/`version` se odvodí z dobře známých názvů polí, pokud nejsou explicitní.
4. `PayloadMasker` maskuje PII listy; `SchemaGovernance` zkontroluje schéma (karanténa při `strict` a neznámém).
5. Envelope se zapíše přes `AnalyticsSink`. ClickHouse `ReplacingMergeTree(aggregate_version)` slučuje duplicity; `eventId` je dedupe klíč.
6. Per řádek se spočítá `record_hash`; dávky se zapečetí do Merkle `integrity_anchors`, zřetězí a (volitelně) zrcadlí do S3 Object Lock WORM.
7. Jakékoliv selhání karantenuje surovou zprávu do `dead_letter_events` (nikdy tiše nezahozeno) a inkrementuje freshness čítač dead-letterů.

## Klíčová designová rozhodnutí

- **Žádný outbox zde.** Toto je downstream konzument; neemituje žádné doménové události.
- **Výběr adaptérů v build-time.** Každá externí závislost (ClickHouse, Vault, S3, Apicurio, HTTP reconciliation source) je opt-in přes `openbank.analytics.*.backend`/`type`, výchozí je offline no-op/logging binding (vzor ADR-0026), takže výchozí build nepotřebuje žádnou infrastrukturu.
- **Bronze je log of record** (ADR-0022); silver/gold jsou odvozené pohledy, úložiště v jediné kopii.
- **Tamper-evidence mimo ClickHouse** — ClickHouse je operátorem měnitelný, takže autoritativní integrity řetězec žije ve WORM (S3 Object Lock); tabulka `integrity_anchors` v ClickHouse je dotazovatelné zrcadlo (ADR-0023).
