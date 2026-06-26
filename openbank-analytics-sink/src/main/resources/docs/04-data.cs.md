# Data

## Model perzistence

Tato služba nevlastní **žádnou OLTP / PostgreSQL databázi** a nepoužívá **žádný Flyway** — záměrně (ADR-0022). Jejím store of record je **ClickHouse analytický sklad**, databáze `openbank_analytics`. DDL je verzováno v `src/main/resources/clickhouse/V1__analytics_bronze_silver.sql` a aplikuje ho **operátor** (nebo budoucí bootstrap `ClickHouseAnalyticsSink`), **ne** migrační runner.

Výchozí `LoggingAnalyticsSink` nepotřebuje žádný externí systém, takže služba startuje a je testovatelná s nulovou infrastrukturou; trvalý `ClickHouseAnalyticsSink` se aktivuje přes `openbank.analytics.sink.type=clickhouse`.

## Medailonové vrstvy

| Vrstva | Objekt | Engine | Účel |
|---|---|---|---|
| **bronze** | `bronze_events` (tabulka) | `ReplacingMergeTree(aggregate_version)` | Append-only **log of record** — jeden řádek na přijatý envelope, duplicity tolerovány (slučovány při merge/FINAL). |
| **silver** | `silver_current_state` (view) | view nad bronze (`argMax`) | Current-state-per-aggregate, last-writer-wins, zrcadlí `AnalyticsProjections.latestPerAggregate`. |
| **silver** | `silver_history` (view) | view (`leadInFrame`) | SCD2 valid-from/valid-to historie verzí per agregát. |
| **silver** | `silver_as_of` (parametrizovaný view) | view, `WHERE occurred_at <= {t}` | Point-in-time „report k datu" current state. |
| **gold** | `gold_daily_event_volume` (view) | view | Denní rollup objemu událostí per služba/typ — kam míří BI dashboardy. |

## Provozní / compliance tabulky

| Tabulka | Engine | Retence (TTL) | Účel |
|---|---|---|---|
| `bronze_events` | `ReplacingMergeTree(aggregate_version)`, `PARTITION BY toYYYYMM(occurred_at)` | **10 let** (podlaha, nikdy nesnižovat) | Log of record. |
| `dead_letter_events` | `ReplacingMergeTree(failed_at)` | 1 rok | Nezpracovatelné / neznámé-schéma zprávy, v karanténě (nezahozeny), idempotentní na `content_hash`, přehratelné. |
| `backfill_audit` | `MergeTree` | 10 let | Jeden řádek na recovery load (kdo/co/proč + počty) — evidence, že mezera byla záměrně zaplněna. |
| `integrity_anchors` | `MergeTree` | **bez TTL** (přežije každý záznam) | Merkle root per zapečetěná dávka, zřetězený na předchozí kotvu — tamper-evidence (ADR-0023). Autoritativní kopie žije ve WORM/S3 Object Lock; toto je dotazovatelné zrcadlo. |
| `reload_proposals` | `ReplacingMergeTree(updated_at)` | 10 let | Maker-checker stopa rozhodnutí (PROPOSED→APPROVED/REJECTED/WITHDRAWN→EXECUTED). |

## Sloupce `bronze_events` (výběr)

| Sloupec | Typ | Poznámky |
|---|---|---|
| `event_id` | `UUID` | Dedupe klíč; bloom-filter index `idx_event_id`. |
| `aggregate_type` / `aggregate_id` | `LowCardinality(String)` / `String` | např. `ACCOUNT`, `PARTY`, `TRANSACTION`, `CONSENT`, `KYC_CASE`. |
| `aggregate_version` | `Int64` | Verzní klíč `ReplacingMergeTree` (last-writer-wins). |
| `event_type`, `occurred_at`, `source_service`, `schema_version` | — | Identita / původ události. |
| `actor_id`, `actor_type`, `trace_id` | `Nullable` | Původ / tracing. |
| `ingest_source`, `batch_id`, `ingested_at` | — | Lineage: `STREAM` / `INITIAL_LOAD` / `BACKFILL` / `CORRECTION` + reload dávka. |
| `record_hash` | `String` | Deterministický SHA-256 nad identitou + obsahem řádku (tamper-evidence). |
| `payload` | `String` (JSON) | **PII-maskované** tělo události — nikdy surové PII. |

## Zacházení s PII

PII je **maskováno na hranici příjmu** komponentou `PayloadMasker`, ještě před jakýmkoliv trvalým zápisem — bronze vrstva se drží ≥10 let, takže nikdy nesmí obsahovat surové identifikátory (GDPR Art. 25 data-protection-by-design).

| Název pole (case-insensitive) | Strategie maskování |
|---|---|
| `email`, `emailaddress` | `EMAIL` |
| `iban`, `accountnumber` | `IBAN` |
| `pan`, `cardnumber` | `PAN` |
| `phone`, `phonenumber`, `msisdn` | `PHONE` |
| `name`, `fullname`, `firstname`, `lastname` | `NAME` |
| `nationalid`, `birthnumber`, `rodnecislo`, `ssn` | `NATIONAL_ID` |

Maskování je **konzervativní allow-by-default**: rozpoznaný klíč je nevratně maskován; nerozpoznané pole projde strukturálně (stále nese analytickou hodnotu). Uchovávané `aggregateId` je **pseudonym**, ne přímý identifikátor.

## Retence & výmaz

- **Bronze podlaha: 10 let** (`AnalyticsRetention.BRONZE_MINIMUM`; ClickHouse TTL nastaveno shodně). Je to podlaha (zvyšovat, nikdy nesnižovat) — smazání bronze je nevratné a vzdává se možnosti recompute/reconcile.
- **Výmaz** (GDPR Art. 17) řeší `ErasureService` aplikací per-kategorie `RetentionPolicies`: mazatelné kategorie jsou **crypto-shrednuté** (`VaultCryptoErasure` při `erasure.backend=vault`); kategorie pod AML/účetním zákonným hold jsou **odmítnuty** s doloženým právním základem (Art. 17(3)(b)). Protože po maskování v sinku zbývá jen pseudonymní id, je reziduální datum už minimalizováno.
