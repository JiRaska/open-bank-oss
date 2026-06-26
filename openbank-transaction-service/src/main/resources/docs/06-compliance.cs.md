# Compliance

`openbank-transaction-service` je **money-path služba** (`rules.yaml: money_path_services`). Každá změna vyžaduje label `money-path`, **2 schválení + threat model** (`docs/threat-models/<service>.md`, ADR-0030). Sedí na synchronní platební cestě a je systémem záznamu pro transakce vůči regulátorovi, takže compliance plocha je široká.

## Regulační rámec

| Regulace | Vztah k této službě | Implementace |
|---|---|---|
| **PSD2** (Reg. (EU) 2015/2366) | Provedení transakce + Open Banking historie; zachycen end-to-end id, kanál, IP | sloupce `end_to_end_id`, `channel`, `ip_address`, `correlation_id`; autentizované čtecí API |
| **AMLD** (směrnice proti praní peněz) | Transakce jsou screenovány výše; výsledek evidován pro důkaz | sloupce `aml_screened` + `aml_screened_at`, partial index na neproscreenované řádky; screening gate žije v platebních službách (ADR-0032) |
| **GDPR** | IBAN/BBAN, jméno protistrany, IP jsou PII | `PiiMask` v logech, 7letá retence přebíjí výmaz pro zaúčtované transakce |
| **DORA** (Reg. (EU) 2022/2554) | Provozní odolnost kritické money-path funkce | health probes, fault-tolerant klienti ledger/balance, circuit breaker outboxu, audit události, SLO, runbooky |
| **NIS2** | Bezpečnost sítí a informací | mTLS v clusteru, security hlavičky, audit log, žádné `@PermitAll` endpointy |
| **ČNB** (knihy účetnictví, reporting) | Sklad transakcí + cross-border reporting kódy | partitionovaný sklad s 7letou retencí, `regulatory_reporting_code`, ISO 20022 pole |
| **ISO 20022 / BIAN** | Sladění transakční zprávy + vyhledávání | `bank_transaction_code`, `purpose_code`, `category_purpose`, `mandate_id`, `creditor_scheme_id`, atd. |

## Mapování GDPR

### Právní základ (čl. 6)
- **Smlouva** (čl. 6 odst. 1 písm. b) — provádění a evidence transakcí zákazníka je nezbytné pro plnění platební smlouvy.
- **Právní povinnost** (čl. 6 odst. 1 písm. c) — uchovávání záznamů AML, knihy účetnictví ČNB, daňový / cross-border reporting.

### Práva subjektů údajů

| Právo | Aplikace |
|---|---|
| Přístup (čl. 15) | `GET /api/v1/transactions?accountId=…` a `/search` vrací transakční data subjektu |
| Oprava (čl. 16) | zaúčtování jsou neměnné finanční záznamy; korekce se dělají **reverzními/úpravnými** transakcemi, nikdy editací na místě |
| Výmaz (čl. 17) | **Neaplikovatelné** — uchovávání záznamů dle AMLD / ČNB přebíjí (7 let) |
| Omezení (čl. 18) | vynuceno výše (zmrazení účtu v account-service blokuje nové transakce) |
| Přenositelnost (čl. 20) | export historie transakcí přes čtecí API (CSV/JSON downstream) |
| Námitka (čl. 21) | N/A (žádné marketingové zpracování) |

### Toky dat ven

- → **ledger-service** (REST, synchronní): id transakce, částky, datumy, řádky journalu — stejný správce, intra-OpenBank.
- → **balance-service** (REST, synchronní): id účtu, částka, měna, reference id (hold/debet/kredit) — stejný správce.
- → **fx-service** (REST, synchronní): jen měnový pár — žádný účet / PII.
- → **audit-service** (Kafka, outbox): payload události životního cyklu transakce — stejný správce.
- → **notification-service** (Kafka, outbox): událost životního cyklu pro notifikaci zákazníka.

Žádná data neopouštějí region EU/EHP.

### Retence (čl. 5 odst. 1 písm. e)

| Data | Retence po `booking_date` |
|---|---|
| Zaúčtovaná transakce | 7 let (`governance.yaml`; knihy účetnictví ČNB + uchovávání záznamů AMLD) |
| Transakce označená AML-relevantní | 7 let (nebo dle životního cyklu AML případu) |

Partitionování podle `booking_date` činí z archivace v roční granularitě odpojení partitiony, ne hromadné mazání.

## Mapování DORA (Reg. (EU) 2022/2554)

| Článek | Téma | Implementace |
|---|---|---|
| čl. 5 / 6 | Rámec řízení ICT rizik | hexagonální služba, závislost na centralizovaném `openbank-libs` |
| čl. 9 | Ochrana & prevence | `/api/v1/info` build identita (gitCommit, buildTime), security hlavičky, OIDC |
| čl. 10 | Detekce | Micrometer/Prometheus metriky, OpenTelemetry traces, alerting na chybovost / latenci |
| čl. 11 | Reakce & obnova | **kompenzace** platební ságy (reverze journalu, vrácení na kapsu, uvolnění holdu); retry outboxu; runbooky v [05 — Provoz](./05-operations.md) |
| čl. 16 | Řízení incidentů | události životního cyklu emitované do audit-service pro důkaz |
| čl. 28 | Riziko třetích stran | žádné SaaS třetích stran — ledger/balance/fx jsou self-hosted interní služby |

Kontroly odolnosti na peněžní cestě: SmallRye Fault Tolerance na ledger klientovi (`LedgerCallGuard`) a dispatcheru outboxu (`@CircuitBreaker`, `@Retry`, `@Timeout`, `@Bulkhead`); TTL holdu 300 s, aby rezervace nikdy neunikla; idempotentní zaúčtování v ledgeru a kompenzační vrácení.

## AML — hranice screeningu

Screening (sankce / AML) je **gatovaný výše** na plochách platebních služeb (sepa-payment, sepa-instant, domestic-payment, fx — ADR-0032), nikoli uvnitř transaction-service. Záznam transakce nese `aml_screened` / `aml_screened_at`, takže výsledek screeningu je součástí neměnného důkazu o transakci, a partial index (`idx_transactions_aml … WHERE aml_screened = FALSE`) vynáší případná neproscreenovaná zaúčtování pro rekonciliaci.

## Auditní stopa

Každá změna životního cyklu transakce produkuje doménovou událost (`TransactionInitiated` → `TransactionCompleted` / `TransactionFailed`) zapsanou do transakčního outboxu a publikovanou do Kafky, kde ji `audit-service` persistuje s tamper-evident řetězcem. `governance.yaml` značí `evidenceExported: true`. Zaúčtování samotná jsou neměnná — korekce jsou nové reverzní/úpravné transakce, zachovávají úplnou historii.

## Bezpečnostní kontroly

- ✅ AuthN: Keycloak OIDC, RS256 JWT; service-to-service přes `oidc-client`
- ✅ AuthZ: Quarkus `@RolesAllowed` — čtení gatováno na SERVICE/VIEWER/OPERATOR/ADMIN, iniciace jen OPERATOR; **žádné `@PermitAll`** (K7 / ADR-0018), zajištěno `TransactionSecurityContractTest`
- ✅ Validace vstupu → 400; porušené invarianty → 422 (sdílené `CommonExceptionMappers`)
- ✅ Idempotence: klíč volajícího + unique DB constrainty + idempotentní zaúčtování v ledgeru + otagované kompenzační vrácení
- ✅ Rate limiting: 150 max souběžných požadavků
- ✅ Security hlavičky: CSP, HSTS, X-Frame-Options DENY, X-Content-Type-Options nosniff, Referrer-Policy, Permissions-Policy
- ✅ TLS: mTLS v clusteru, terminace TLS na gateway
- ✅ Audit: každá změna stavu → audit-service přes outbox událost
- ⚠️ Tokenizace IBAN/protistrany: spoléhá na maskování v logech (`PiiMask`); column-level tokenizace neimplementována (sledováno jako reziduální riziko v threat modelu)
