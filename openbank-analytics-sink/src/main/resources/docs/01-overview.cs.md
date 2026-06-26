# Přehled

## Co služba dělá

`openbank-analytics-sink` je **jediná cesta příjmu dat do analytického/reportingového skladu** (ADR-0022). Služba:

- **Konzumuje proud doménových událostí platformy** — tytéž outboxem publikované Kafka topiky, které přijímá i audit služba (`account`, `transaction`, `balance`, `party`, `kyc`, `consent`). Je plněna z existujícího proudu událostí (ADR-0003), takže reporting přidává **nulovou čtecí zátěž** na provozní databáze.
- **Normalizuje** každou surovou událost do kanonického `AnalyticsEnvelope` a **maskuje PII na hranici** (`PayloadMasker`) předtím, než se cokoliv trvale zapíše — bronze vrstva se drží ≥10 let a nikdy nesmí obsahovat surové identifikátory.
- **Zapisuje medailonový sklad** v ClickHouse: **bronze** (append-only log of record), **silver** (pohledy current-state-per-aggregate, last-writer-wins), **gold** (BI rollupy).
- **Zpevňuje sklad pro regulátory** (ADR-0023): tamper-evidence kotvy, dead-letter karanténa, recovery loady na čtyři oči, rekonciliace proti zdroji pravdy, GDPR Art. 17 výmaz, kontrola datové rezidence.

## Co služba **NEDĚLÁ**

- ❌ Nevlastní OLTP / PostgreSQL databázi — store of record je ClickHouse sklad (ADR-0022). Žádný Hibernate, žádný Flyway.
- ❌ Nepoužívá CDC/Debezium/WAL extrakci — **není** druhou čtecí cestou na provozní DB.
- ❌ Neprodukuje doménové události — je čistý **konzument**; žádný outbox.
- ❌ Nesedí v žádné request-path zákazníka — je asynchronní, mimo money-path.
- ❌ Nepočítá zůstatky, neúčtuje do hlavní knihy, neprovádí platby — to dělají `balance-service` / `ledger-service` / platební služby.

## Pozice v doméně

```
  account-service ┐
  transaction-svc │  outbox → Kafka doménové události
  balance-service │  (account/transaction/balance/
  party-service   │   party/kyc/consent .events)
  kyc-service     │            │
  consent-service ┘            ▼
                     ┌──────────────────────┐
                     │  analytics-sink      │  PII maskováno na hranici
                     │  (AnalyticsConsumer) │
                     └──────────┬───────────┘
                                │ port AnalyticsSink
                                ▼
                     ┌──────────────────────┐      ┌─────────────────┐
                     │ ClickHouse sklad     │◄─────│ BI: Metabase /  │
                     │ bronze→silver→gold   │ čtení│ Superset        │
                     └──────────┬───────────┘      └─────────────────┘
                                │ integrity anchors
                                ▼
                     S3 Object Lock (WORM) — tamper-evidence
```

## Klíčové use-casy

| Use-case | API | Událost / mechanismus |
|---|---|---|
| Příjem doménové události do bronze | — | Kafka `analytics-events-in` → `AnalyticsConsumer` |
| Karanténa nezpracovatelné / neznámé-schéma události | — | `DeadLetterSink` → `dead_letter_events` |
| Rekonciliace sklad vs zdroj pravdy | `POST /api/v1/analytics/reconciliation/run` | `ReconciliationJob` (i plánovaně, cron) |
| Čtení poslední rekonciliační evidence | `GET /api/v1/analytics/reconciliation/last` | — |
| Návrh recovery loadu (backfill/korekce) | `POST /api/v1/analytics/backfill/proposals` | stavový automat `Proposal` na čtyři oči |
| Schválení loadu (jiný operátor) | `POST /api/v1/analytics/backfill/proposals/{id}/approve` | maker-checker (samoschválení ⇒ 409) |
| Provedení schváleného loadu | `POST /api/v1/analytics/backfill/proposals/{id}/execute` | evidenční řádek `backfill_audit` |
| GDPR Art. 17 výmaz v analytice | `POST /api/v1/analytics/erasure` | crypto-shred, nebo odmítnutí pod zákonným hold |

## Volající / plniči

- **Plniči (Kafka, asynchronně):** account, transaction, balance, party, kyc, consent služby — přes své existující outbox topiky. analytics-sink je pasivní konzument.
- **Operátoři / auditoři / compliance (admin UI přes Keycloak token):** REST operátorský povrch (rekonciliace, backfill, výmaz).
- **BI nástroje (Metabase / Superset):** čtou gold/silver vrstvu přímo v ClickHouse — nikdy se nedotknou provozních databází.

## Závislosti

- **ClickHouse** (databáze `openbank_analytics`) — store of record skladu; aktivní při `openbank.analytics.sink.type=clickhouse` (výchozí sink je offline `LoggingAnalyticsSink`, takže služba startuje s nulovou infrou).
- **Kafka** — vstupní topiky doménových událostí, consumer group `analytics-sink`.
- **Keycloak** — OIDC auth pro REST operátorský povrch.
- **Vault (volitelné)** — crypto-erasure přes Transit (`erasure.backend=vault`).
- **S3 Object Lock (volitelné)** — WORM integrity kotvy (`worm.backend=s3`).
- **Apicurio (volitelné)** — zdroj katalogu schémat (`schema.backend=apicurio`).
- **openbank-libs** — `analytics.AnalyticsEnvelope`, `AnalyticsProjections`, `BackfillRequest`, `Proposal`/maker-checker, `RetentionPolicies`, `Integrity`, security `Roles`/`PiiMask`, BuildInfo, DocsResource.

## Obchodní hodnota

- **Reporting bez provozního rizika** — analytika je plněna z událostí, nikdy dotazováním živých bankovních databází, takže dashboardy nikdy nezpomalí ani nezamknou money-path.
- **10letý, přehratelný log of record** — bronze je append-only a tamper-evident; libovolný historický „report k datu" je přesný.
- **Připraveno pro regulátora už designem** — rekonciliační evidence, integrity kotvy, recovery na čtyři oči, kontrola rezidence a GDPR výmaz jsou vestavěné (ADR-0023), pokrývají nálezy CNB/EBA/DORA/GDPR/BCBS 239.
- **Levné škálování k nule** — protože je mimo request-path, je kandidátem na FinOps scale-to-zero (ADR-0057).
