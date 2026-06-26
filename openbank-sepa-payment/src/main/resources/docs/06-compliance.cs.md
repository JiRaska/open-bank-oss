# Compliance

`openbank-sepa-payment` je **money-path** služba (`rules.yaml: money_path_services`). Nese plnou sadu money-path kontrol: review se 2 schváleními, aktuální threat model ([`docs/threat-models/openbank-sepa-payment.md`](../../../../docs/threat-models/openbank-sepa-payment.md), ADR-0030) a synchronní screening gate (ADR-0032).

## Regulatorní rámec

| Regulace | Vztah ke službě | Implementace |
|---|---|---|
| **SEPA Credit Transfer Rulebook (EPC)** | struktura SCT instrukce, charge bearer, end-to-end id | `charge_bearer` defaultně `SLEV` s `chk_sepa_charge_bearer`; sloupce `end_to_end_id`, `purpose_code`, `category_purpose` |
| **ISO 20022 (pain/pacs)** | sémantika platebních polí | `purpose_code`, `regulatory_reporting`, `instructed_agent_bic` v souladu s ISO 20022 |
| **PSD2 (Nař. (EU) 2015/2366) + RTS** | SCA, TPP consent, kontroly podvodů | `sca_reference` (RTS čl. 97), `consent_id` (TPP), role-gateovaná iniciace; samotné SCA v `sca-service` (ADR-0021) |
| **AMLD / sankce** | screening jmen před uvolněním; zmrazení při zásahu | synchronní sankční screening při create, fail-closed (ADR-0032); AML případ otevřen při zásahu/zadržení |
| **GDPR** | IBANy a jména jsou PII | klasifikace confidential, 7letá retence přebíjející výmaz u platebních záznamů, maskování v logu |
| **DORA (Nař. (EU) 2022/2554)** | provozní odolnost platebního skoku | T0 always-on (ADR-0057), fault-tolerant klienti, health probes, audit události, runbooky |
| **NIS2** | bezpečnost sítě a informací | mTLS uvnitř clusteru, OIDC, OPA authz, bezpečnostní hlavičky (CSP/HSTS/X-Frame-Options) |

## ADR-0032 — synchronní sankční/AML screening gate (jádro kontroly)

Při create jsou **obě** jména (plátce i příjemce) prověřena synchronně proti sankčním seznamům **až poté**, co je řádek `RECEIVED` trvale uložen, takže hodnotu nesoucí instrukce není nikdy uvolněna neprověřená a nikdy se neztratí:

- **CLEAR** → platba přejde do `VALIDATED`.
- **REVIEW** (podprahový potenciální zásah) → platba **držena v `RECEIVED`** k lidskému rozhodnutí; otevřen případ `AML_HOLD` (HIGH).
- **BLOCK** (HIT / ESCALATED / score > 0,85) → platba `REJECTED` s `SANCTIONS_HIT`; otevřen případ `SANCTIONS_HIT` (CRITICAL).
- **Screening nedostupný** → **fail-closed**: platba držena v `RECEIVED`, otevřen případ `SCREENING_UNAVAILABLE` (MEDIUM). Gate při výpadku screeningu nikdy neuvolní.

Otevření AML případu je best-effort a nikdy nepřeklopí verdikt již vynesený `ScreeningPolicy`.

## Mapování GDPR

### Právní základ (čl. 6)
- **Smlouva** (čl. 6 odst. 1 písm. b) — provedení platební instrukce zákazníka.
- **Právní povinnost** (čl. 6 odst. 1 písm. c) — AML screening, PSD2/SEPA record-keeping, dodržování sankcí.

### Práva subjektu údajů

| Právo | Uplatnění |
|---|---|
| Přístup (čl. 15) | `GET /api/v1/sepa-payments?debtorAccountId=…` vrací platby subjektu |
| Oprava (čl. 16) | platební instrukce jsou po přijetí neměnné; korekce kompenzačním stavovým přechodem |
| Výmaz (čl. 17) | **Neuplatňuje se** na settlované platební záznamy — record-keeping plateb/AML (7 let) přebíjí |
| Omezení (čl. 18) | platbu lze držet (`RECEIVED`) nebo zamítnout; žádné další zpracování |
| Přenositelnost (čl. 20) | export seznamu plateb subjektu (admin nástroje) |

### Toky dat ven

- → **clearing-service / ledger-service** (Kafka `openbank.sepa.payment.events`): id platby, částky, IBANy — intra-OpenBank, stejný správce.
- → **audit-service** (Kafka): plný payload události pro tamper-evident audit trail.
- → **sanctions-service** (REST, synchronní): **jména** plátce/příjemce k screeningu — vztah zpracovatele, intra-OpenBank.
- → **aml-service** (REST): zákaznická reference (jméno / IBAN), matched entity, alert kód — pro AML případ.

Žádná data neopouštějí region EU/EHP.

### Retence (čl. 5 odst. 1 písm. e)

Deklarováno **7 let** (`governance.yaml`). Platební záznamy a důkazy screeningu se uchovávají po AML/PSD2 zákonnou dobu bez ohledu na žádosti o výmaz dle GDPR.

## Mapování DORA (Nař. (EU) 2022/2554)

| Článek | Téma | Implementace |
|---|---|---|
| čl. 5/6 | řízení ICT rizik | money-path služba v centrálním registru; závislost = openbank-libs |
| čl. 9 | identifikace | `BuildInfo` (gitCommit, buildTime, version) v `/api/v1/info` |
| čl. 10 | detekce | Micrometer/Prometheus metriky; alerting na outbox lag + 5xx |
| čl. 11 | reakce a obnova | T0 always-on; fault-tolerant screening klienti; runbooky v `05-operations.md` |
| čl. 16/17 | řízení a hlášení incidentů | doménové události do audit-service jako důkaz; major incidenty hlášeny přes audit pipeline |
| čl. 28 | riziko třetích stran | žádný third-party SaaS — sanctions/AML jsou self-hosted OpenBank služby |

## PSD2 — SCA a TPP

- **SCA** (RTS čl. 97): zákazníkem iniciované převody referencují SCA evidenci přes `sca_reference`; decoupled approval flow žije v `sca-service` (ADR-0021, no auto-approve).
- **TPP consent**: `consent_id` referencuje souhlas validovaný `consent-service`.
- **Role**: iniciace je omezena na `ROLE_OPERATOR` / `ROLE_ADMIN` / `ROLE_PAYMENTS`; `ROLE_VIEWER` je read-only — odlišná payments role brání viewerovi iniciovat převod (EoP kontrola z threat modelu).

## Shrnutí STRIDE (z threat modelu)

| Hrozba | Mitigace |
|---|---|
| Spoofing | OIDC + role; mTLS pro service volající |
| Tampering | server-validováno, po přijetí neměnné; audit trail |
| Repudiation | AuditEvent + SCA evidence + correlation id |
| Info disclosure | role-scoped čtení; maskování PII |
| DoS | rate limit (`openbank.rate-limit`), idempotence |
| Elevation of privilege | odlišná `ROLE_PAYMENTS`, deny-by-default, OPA enforce |

## Audit trail

Každý create a každá změna stavu produkuje doménovou událost vyprázdněnou do `audit-service`, který ji perzistuje s tamper-evident řetězcem. Verdikt screeningu a případný AML případ jsou součástí důkazů (`evidenceExported: true`).

## Bezpečnostní kontroly

- ✅ Validace vstupů (DTO + guardy doménového stavového automatu)
- ✅ AuthN: Keycloak OIDC (JWT)
- ✅ AuthZ: `@RolesAllowed` + OPA `@Authorize` (ADR-0034, advisory→enforce)
- ✅ Idempotence: povinná při create (Redis + DB UNIQUE)
- ✅ Synchronní sankční/AML screening gate, fail-closed (ADR-0032)
- ✅ Transakční outbox (žádná ztracená ani neauditovaná změna stavu)
- ✅ Bezpečnostní hlavičky: CSP, HSTS, X-Frame-Options DENY, X-Content-Type-Options nosniff
- ✅ Tajemství: dev placeholdery MUSÍ být v prod přepsány přes Vault
- ⚠️ Tokenizace IBAN: neimplementováno — vedeno jako reziduální riziko v threat modelu
