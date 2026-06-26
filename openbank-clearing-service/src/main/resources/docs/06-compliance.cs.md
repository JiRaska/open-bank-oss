# Compliance

`openbank-clearing-service` je **money-path** služba (`rules.yaml: money_path_services`). Agreguje mnoho plateb do zúčtování — operace s vysokým dopadem — proto platí přísnější governance režim: **2 schválení + udržovaný threat model** (`docs/threat-models/openbank-clearing-service.md`, ADR-0030) u každé změny `src/main/**`.

## Regulatorní rámec

| Regulace | Vztah ke službě | Implementace |
|---|---|---|
| **PSD2 / SEPA nařízení (260/2012)** | Clearing & zúčtování SEPA SCT / SCT Inst plateb; rail-aware cykly | enum `payment_rail`, per-rail cycle trigger, čisté pozice |
| **SEPA Instant / SCT Inst scheme** | Clearing instant railu | rail `SEPA_SCT_INST` |
| **AMLD (směrnice proti praní špinavých peněz)** | Zúčtovací záznamy jsou AML-relevantní důkaz; screening je vynucen výše (gate ADR-0032 na platebních površích) | 7letá retence; neměnný audit přes outbox události |
| **GDPR** | IBAN + remitanční info jsou PII na clearingových položkách | klasifikace confidential, maskování v logu, retence nad výmazem |
| **DORA** (Reg. (EU) 2022/2554) | Provozní odolnost kritické zúčtovací funkce | health probes, outbox circuit-breaker/bulkhead/retry, metriky, runbooky |
| **NIS2** | Síťová a informační bezpečnost | mTLS v clusteru, OIDC, OPA authz, bezpečnostní response hlavičky |
| **Finalita zúčtování (Dir. 98/26/ES)** | Sémantika net/gross zúčtování, finalita pozic | `settlement_type` (GROSS/NET/DEFERRED_NET), `SettlementPosition.settled` |

## Řízení přístupu (money-path hardening)

Per-operace least-privilege (ADR-0018, nahradilo dřívější class-level `@PermitAll`):

| Operace | Role | Navíc |
|---|---|---|
| `submit` | `SERVICE`, `PAYMENTS`, `ADMIN` | service/payment-ops identita |
| čtení (batches/items/positions) | `SERVICE`, `VIEWER`, `OPERATOR`, `PAYMENTS`, `ADMIN` | široké čtení |
| `settle` | `PAYMENTS`, `ADMIN` | `@Authorize(clearingBatch.settle)` (OPA, advisory → enforce ve fázi 5) |
| `cycle/trigger` | `PAYMENTS`, `ADMIN` | vysoký dopad |

Settle a cycle-trigger jsou akce s vysokým dopadem; four-eyes (MakerChecker, ADR-0034) je sledovaný follow-up. Vynucení je zamčeno testy `ClearingResourceSecurityTest` / `ClearingSecurityContractTest`.

## Mapování GDPR

### Právní základ (čl. 6)
- **Smlouva** (čl. 6 odst. 1 písm. b) — clearing platby je nezbytný k provedení platební instrukce, kterou zákazník inicioval.
- **Právní povinnost** (čl. 6 odst. 1 písm. c) — AML a uchovávání platebních záznamů.

### Uchovávaná osobní data
- `debtor_iban`, `creditor_iban` — identifikátory účtů (PII).
- `remittance_info` — volnotextová platební reference, může obsahovat PII.
- BICy a `participant_bic` jsou identifikátory institucí (nízká citlivost).

### Práva subjektu údajů

| Právo | Aplikace |
|---|---|
| Přístup (čl. 15) | clearingové položky dohledatelné přes `GET /items/by-payment/{paymentId}` (napojené upstream na subjekt) |
| Oprava (čl. 16) | neaplikuje se — clearingové položky jsou neměnné záznamy provedené instrukce |
| Výmaz (čl. 17) | **Neaplikuje se** — AML + uchovávání zúčtovacích záznamů (7letá retence) má přednost |
| Omezení (čl. 18) | hold na upstream platbě; clearing pracuje s již autorizovanými instrukcemi |
| Přenositelnost (čl. 20) | N/A (zde není přímý zákaznický vztah) |

### Tok dat ven
- → **transaction-service** (deklarovaný downstream, vztah `api`, „settles") — výsledky zúčtování.
- → **Kafka** `openbank.clearing.batch.event` (přes `clearing_outbox`) — události zúčtování dávek pro downstream/audit konzumenty; stejný správce, intra-OpenBank.
- Žádná data neopouštějí region EU/EHP.

### Retence (čl. 5 odst. 1 písm. e)
`governance.yaml: retentionPolicy: 7 years`. Clearingové položky, dávky a zúčtovací pozice se uchovávají po zákonnou AML/účetní dobu. Outbox řádky jsou provozní a po úspěšném doručení se prořezávají.

## Mapování DORA (Reg. (EU) 2022/2554)

| Článek | Téma | Implementace |
|---|---|---|
| Čl. 5/6 | Rámec řízení ICT rizik | centrální `openbank-libs`, governance.yaml, money-path klasifikace |
| Čl. 9 | Identifikace | `BuildInfo` v `/api/v1/info` |
| Čl. 10 | Detekce | Micrometer/Prometheus metriky, FAILED řádky outboxu s `last_error` |
| Čl. 11 | Reakce & obnova | outbox `@CircuitBreaker`/`@Bulkhead`/`@Retry`/`@Timeout`, runbooky v `05-operations.md`, Flyway repair procedura |
| Čl. 16/17 | Řízení & reporting incidentů | zúčtovací události emitované do audit pipeline přes outbox |
| Čl. 28 | Riziko třetích stran | žádný third-party SaaS — Postgres/Kafka/Keycloak/OPA vše self-hosted |

## Kontroly integrity zúčtování

- ✅ **Invariant kladné částky** — DB CHECK `amount > 0` na položkách, `total_debit/credit >= 0` na dávkách (V4).
- ✅ **Transakční outbox** — zúčtovací události zapsány ve stejné transakci jako změna agregátu, vyprazdňovány at-least-once do Kafky.
- ✅ **Idempotentní alokace id** — V3 oprava sekvence předchází selhání INSERTů za běhu.
- ✅ **Unique constrainty** — `batch_reference` unique; `(participant_bic, currency, cycle_id)` unique na pozicích.
- ✅ **Reaktivní resilience** — `@Retry`/`@Timeout` na submit/cycle; circuit-breaker na dispatcheru.

## Bezpečnostní kontroly

- ✅ AuthN: Keycloak OIDC, bearer JWT.
- ✅ AuthZ: per-operace `@RolesAllowed` (least-privilege) + `@Authorize`/OPA na settle (advisory, ADR-0034).
- ✅ Bezpečnostní hlavičky: nosniff, DENY framing, CSP `default-src 'self'`, HSTS.
- ✅ Tajemství: placeholdery `CHANGE_ME_LOCAL_DEV_ONLY` → Vault v produkci (ADR-0017).
- ✅ Threat model: udržovaný STRIDE/DFD na `docs/threat-models/openbank-clearing-service.md`.
- ⚠️ Guard idempotency-store na mutacích: hlavička + Redis zapojeny, explicitní vynucení částečné — sledováno.
- ⚠️ Four-eyes (MakerChecker) na settle/trigger: sledovaný follow-up (ADR-0034).
- ⚠️ OPA enforce mód: dnes advisory (`AUTHZ_ENFORCE=false`), graduuje do enforce ve fázi 5.

## Audit trail

Zúčtování dávky vyprodukuje doménovou událost zapsanou do `clearing_outbox` a publikovanou do `openbank.clearing.batch.event`; downstream audit konzumenti ji uchovávají po zákonnou dobu. Outbox řádky nesou `event_id`, `aggregate_id`, `event_type`, `attempt_count` a `last_error` pro forenzní dohledatelnost.
