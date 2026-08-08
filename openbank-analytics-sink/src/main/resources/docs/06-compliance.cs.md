# Compliance

> **Klasifikace money-path:** `openbank-analytics-sink` **NENÍ** money-path služba (chybí v `rules.yaml: money_path_services`). Je to downstream, asynchronní analytický konzument bez role v jakékoliv platební/zúčtovací cestě. Drží však dlouhožijící (10letý) PII-maskovaný, pseudonymní sklad, takže je pevně v rozsahu **GDPR + data-governance** a implementuje regulatorní zpevnění analytiky z [ADR 0023](../../../../docs/adr/0023-analytics-regulatory-hardening.md) (nálezy CNB/EBA/DORA/GDPR/BCBS 239).

## Regulatorní rámec

| Regulace | Vztah k této službě | Implementace |
|---|---|---|
| **GDPR** | 10letý PII-maskovaný pseudonymní sklad; právo na výmaz | `PayloadMasker` (maskování v sinku, Art. 25); `ErasureService` + crypto-shred (Art. 17); residency guard (Art. 44) |
| **DORA** (Reg. (EU) 2022/2554) | ICT odolnost, monitoring, RPO | readiness probe na ingest freshness/DLQ, metriky/alerting, rekonciliační evidence, runbooky |
| **AMLD** | Analytika uchovává AML-relevantní záznamy pod zákonným hold | `RetentionPolicies` odmítnou výmaz pro AML-held kategorie (Art. 17(3)(b)); 10letá bronze podlaha |
| **PSD2** | Žádný přímý PSD2/Open-Banking povrch | analytika je interní; žádný TPP přístup |
| **NIS2** | Síťová & informační bezpečnost | mTLS v clusteru, bezpečnostní hlavičky (CSP/HSTS/X-Frame-Options), role-gated REST, JSON audit logy |
| **BCBS 239** | Agregace rizikových dat: přesnost, úplnost, včasnost | rekonciliace vs zdroj pravdy, dead-letter karanténa (žádné tiché mezery), point-in-time `silver_as_of` |
| **Nálezy CNB/EBA** | 9 regulatorních nálezů k analytické vrstvě | řešeno feature-by-feature v ADR-0023 (F1–F9, níže) |

## ADR-0023 regulatorní zpevnění (F1–F9)

| Feature | Téma | Implementace v této službě |
|---|---|---|
| F1/F2 | Tamper-evidence | `record_hash` per bronze řádek + Merkle `integrity_anchors`, zřetězené; autoritativní kopie ve WORM (`S3WormArchive`, Object Lock COMPLIANCE) |
| F3 | Recovery loady na čtyři oči | `SensitiveReloadService` + stavový automat `Proposal`; samoschválení ⇒ 409; stopa `reload_proposals` |
| F6 | GDPR výmaz | `ErasureService` + `VaultCryptoErasure` (crypto-shred) / odmítnutí pod hold |
| F7 | Schema governance | `SchemaGovernance` karantenuje neznámé/novější schéma do DLQ při `strict` |
| F8 | Ingest freshness / RPO | `IngestFreshness` + `IngestHealthCheck` readiness na lag & DLQ |
| F9 | Datová rezidence | `DataResidencyValidator` startup guard (GDPR Art. 44) |
| — | Úplnost | dead-letter karanténa + rekonciliace proti zdroji pravdy |

## GDPR mapování

### Právní základ (Art. 6)
- **Oprávněný zájem / právní povinnost** — interní analytika, regulatorní reporting a agregace rizikových dat odvozené z událostí, které banka už zákonně zpracovává. Žádný nový sběr zde neprobíhá.

### Minimalizace dat & by-design (Art. 5, Art. 25)
Přímo identifikující PII je maskováno na hranici příjmu (`PayloadMasker`) ještě před jakýmkoliv trvalým zápisem; uchovává se jen **pseudonymní `aggregateId`**. Sklad je odvozený, maskovaný — ne druhá kopie surových zákaznických dat.

### Práva subjektu údajů

| Právo | Aplikace |
|---|---|
| Přístup (Art. 15) | Servíruje se z provozních zdrojových služeb, ne z analytiky (analytika drží jen maskované pseudonymy). |
| Výmaz (Art. 17) | `POST /api/v1/analytics/erasure` → crypto-shred, pokud mazatelné; **odmítnuto** s doloženým právním základem pod AML/účetním zákonným hold (Art. 17(3)(b)). |
| Omezení (Art. 18) | Dosaženo upstream; analytika je read-derived. |
| Přenositelnost (Art. 20) | N/A — analytika není systémem záznamu osobních údajů. |

### Mezinárodní přenosy (Art. 44)
`DataResidencyValidator` přeruší boot, pokud region skladu není na allow-listu (výchozí `eu-north-1`, ADR-0175 §1). Žádná osobní data neopouštějí schválené EU regiony.

### Retence (Art. 5(1)(e))

| Data | Retence |
|---|---|
| `bronze_events` (log of record) | 10 let (podlaha; AMLD/účetnictví). |
| `backfill_audit`, `reload_proposals` | 10 let (evidence). |
| `integrity_anchors` | neomezeně (tamper-evidence musí přežít každý záznam). |
| `dead_letter_events` | 1 rok (provozní, ne log of record). |

## DORA mapování (Reg. (EU) 2022/2554)

| Článek | Téma | Implementace |
|---|---|---|
| Art. 9 | Identifikace | `BuildInfo` (gitCommit/buildTime/version) přes `/api/v1/info` (openbank-libs). |
| Art. 10 | Detekce | readiness probe na ingest lag/DLQ; Prometheus metriky + alerting. |
| Art. 11 | Reakce & obnova | runbook backfill na čtyři oči; rekonciliační evidence; RPO vázané na `max-lag-seconds`. |
| Art. 16/17 | Řízení & reporting incidentů | viditelnost dead-letterů + audit/evidenční tabulky. |
| Art. 28 | Riziko třetích stran | všechny závislosti self-hosted (ClickHouse, Kafka, Vault, Apicurio); S3 Object Lock je jediná managed primitiva, S3-standard (cloud-agnostic, ADR-0027). |

## Toky dat ven

- → **BI nástroje (Metabase / Superset):** čtou maskovaný gold/silver v ClickHouse — žádný přístup do provozní DB, žádné surové PII.
- → **WORM / S3 Object Lock (`eu-north-1`):** integrity kotvy (jen hashe, žádné PII).
- → **Vault (volitelně):** operace s crypto-erasure klíčem — žádný PII payload.

Žádné surové PII se neukládá ani nepřenáší; žádná data neopouštějí schválený EU region.

## Bezpečnostní kontroly

- ✅ AuthN: Keycloak OIDC (RS256 bearer).
- ✅ AuthZ: `@RolesAllowed` na každém slovesu (`ROLE_ADMIN`/`ROLE_AUDITOR`/`ROLE_COMPLIANCE`); žádné `@PermitAll` mutace.
- ✅ Maskování PII na hranici příjmu (nevratné).
- ✅ Oddělení povinností: maker-checker na čtyři oči u recovery loadů.
- ✅ Tamper-evidence: per-řádek hash + Merkle kotvy ve WORM.
- ✅ Datová rezidence: startup guard.
- ✅ HTTP hardening: CSP, HSTS, X-Frame-Options DENY, nosniff, restriktivní Permissions-Policy; omezené CORS.
- ✅ Žádná odchozí zátěž na provozní databáze (event-fed, žádné CDC).
- ⚠️ Adaptérové bindingy (ClickHouse/Vault/S3/Apicurio) jsou v devu výchozí offline no-op; trvalou + WORM + Vault cestu je nutné explicitně zapnout a ověřit per prostředí.
