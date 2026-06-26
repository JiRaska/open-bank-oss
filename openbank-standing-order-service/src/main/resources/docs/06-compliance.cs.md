# Compliance

> **Money-path status:** standing-order-service **NENÍ** v `rules.yaml: money_path_services`. Zaznamenává *záměr* opakovaně platit, ale sama nepřesouvá peníze, nedrží zůstatky ani neúčtuje do ledgeru — vlastní odepsání/platba se materializuje navazujícími službami (`transaction-service` → SEPA/tuzemské platby), kde platí money-path kontroly (2 schvalovatelé, threat model, AML/sankční gate). Tato služba proto vyžaduje review s jedním schvalovatelem a bez threat modelu, ale stále zpracovává **confidential** PII (IBAN a jména příjemců).

## Regulatorní rámec

| Regulace | Vztah k této službě | Implementace |
|---|---|---|
| **PSD2** (Reg. (EU) 2015/2366) | Trvalý příkaz je platební nástroj; *mandát* žije zde, provedení je navazující úhrada | API životního cyklu příkazu; SCA a kontroly provedení vynucuje navazující platební povrch |
| **GDPR** (Reg. (EU) 2016/679) | Ukládá IBAN/jméno příjemce + referenci na plátce (confidential PII) | klasifikace `confidential`, maskování v logu, AML-omezená retence |
| **AMLD** (směrnice proti praní peněz) | Opakované instrukce jsou věcí evidence/uchovávání; screening se vynucuje při materializaci platby (ADR-0032) | navazující screening gate; 5letá retence záznamu mandátu |
| **DORA** (Reg. (EU) 2022/2554) | Provozní odolnost | health probes, resilience stack outboxu (circuit breaker/retry/bulkhead/timeout), metriky, identifikace buildu přes `/api/v1/info` |
| **NIS2** | Síťová a informační bezpečnost | OIDC autentizace, OPA autorizace, in-cluster mTLS, bezpečnostní hlavičky (CSP/HSTS/…) |
| **ČNB / ISO 13616** | Struktura IBAN příjemce | `creditor_iban` validován dle formátu IBAN (autoritativní mod-97 kontrolu dělá navazující platební služba) |

## Mapování GDPR

### Právní základ (čl. 6)

- **Smlouva** (čl. 6(1)(b)) — primárně: udržování opakované platební instrukce zákazníka je nezbytné pro plnění smlouvy o platebních službách.
- **Právní povinnost** (čl. 6(1)(c)) — sekundárně: AML/platební evidence.

### Uchovávaná osobní data

| Data | Role | Zdroj |
|---|---|---|
| `creditor_iban`, `creditor_name`, `creditor_bic` | identifikátory příjemce (PII) | požadavek klienta |
| `remittance_info` | volný text, potenciálně PII | požadavek klienta |
| `party_id` | plátce (pseudonymizovaná ref na party-service) | požadavek klienta |
| `debit_account_id` | pseudonymizovaná ref na account-service | požadavek klienta |

### Práva subjektu údajů

| Právo | Aplikace |
|---|---|
| Přístup (čl. 15) | `GET /api/v1/standing-orders/party/{partyId}` vrátí příkazy subjektu |
| Oprava (čl. 16) | zrušit + znovu vytvořit (příkazy jsou immutable instrukce; endpoint pro úpravu na místě zatím není — TBD) |
| Výmaz (čl. 17) | omezen AML evidenční povinností v aktivním retenčním okně; zrušení nastaví terminální stav, data se uchovají jako důkaz |
| Omezení (čl. 18) | `pause` vyřadí příkaz z provádění bez smazání |
| Přenositelnost (čl. 20) | hromadný export — zatím neimplementováno (TBD) |
| Námitka (čl. 21) | N/A (žádné marketingové zpracování) |

### Toky dat ven

- → **transaction-service** (Kafka `openbank.standing-orders.order.event`): záměr příkazu/provedení — stejný správce, intra-OpenBank, materializuje vlastní platbu.
- → **audit-service** (Kafka): payload události pro auditní stopu — stejný správce.
- Žádná data neopouštějí region EU/EHP (jen intra-OpenBank).

### Retence (čl. 5(1)(e))

`governance.yaml: retentionPolicy: 5 years`.

| Stav příkazu | Retence |
|---|---|
| ACTIVE / PAUSED | průběžně po dobu živého mandátu |
| CANCELLED / COMPLETED | 5 let po ukončení (důkaz mandátu, spory, AML evidence) |
| `standing_order_outbox` | jen provozní; purge po doručení |

## Mapování DORA (Reg. (EU) 2022/2554)

| Článek | Téma | Implementace |
|---|---|---|
| čl. 5 / 6 | Rámec řízení ICT rizik | závislost na centralizovaném `openbank-libs`; per-service governance manifest |
| čl. 9 | Identifikace | `BuildInfo` (gitCommit, buildTime, version) přes `/api/v1/info` |
| čl. 10 | Detekce | Micrometer metriky + Prometheus; pozorovatelné zpoždění outboxu |
| čl. 11 | Reakce & obnova | resilience stack outboxu (circuit breaker/retry/bulkhead/timeout); plánovač pohltí chyby a pokračuje; runbooky v [05 — Provoz](./05-operations.md) |
| čl. 16/17 | Řízení a hlášení incidentů | doménové události do audit-service jako důkaz |
| čl. 28 | Riziko třetích stran | žádné SaaS třetích stran — vše self-hosted |

## PSD2 — trvalé příkazy

Trvalý příkaz je platební nástroj zákazníka. Tato služba je **úložiště mandátu**; silné ověření zákazníka (SCA, ADR-0021) a AML/sankční screening gate (ADR-0032) se vynucují v **bodě provedení** v navazujícím platebním povrchu, ne zde. Při zapojení zákaznicky orientovaného vytváření přes zákaznickou app je SCA na nastavení příkazu věcí navazující orchestrace.

## Autorizace (ADR-0034)

- Rozhodnutí jsou delegována na **OPA sidecar** přes `openbank-libs` `@Authorize`.
- Režim je **ve výchozím stavu advisory** (`authz.enforce=false`) — rozhodnutí se logují, ale nevynucují, dokud prostředí nepřepne `AUTHZ_ENFORCE=true`.
- Aktuální pokrytí: `pause` je anotován (`standingOrder.pause`). `create`, `resume`, `cancel` a read endpointy **zatím nejsou anotovány** — dokončení pokrytí autorizace je sledovaný follow-up (TBD).

## Bezpečnostní kontroly

- ✅ AuthN: Keycloak OIDC bearer token (RS256), realm `openbank`.
- ✅ AuthZ: OPA sidecar (`@Authorize`), advisory→enforce fázově (ADR-0034).
- ✅ Idempotentní vytvoření: klientský `idempotencyKey`, DB-unikátní (bezpečné při opakování).
- ✅ Transakční outbox s at-least-once doručením + resilience stack.
- ✅ Bezpečnostní hlavičky odpovědi: CSP `default-src 'self'`, HSTS, X-Frame-Options DENY, nosniff, Referrer-Policy, Permissions-Policy.
- ✅ Rate limiting: `openbank.rate-limit` (max 200 souběžných).
- ✅ Audit: události životního cyklu vydávány do audit-service.
- ⚠️ Pokrytí autorizace neúplné (anotován jen `pause`) — TBD.
- ⚠️ Drift OpenAPI kontraktu vůči implementaci (pole, port, chybějící list endpoint) — sladit, viz [03 — API](./03-api.md).
- ⚠️ Plánovač provádění / SCA-on-setup zatím v tomto buildu nezapojen — TBD.
