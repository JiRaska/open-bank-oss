# Compliance

> **Status money-path:** `openbank-psd2-service` **není** uveden v `rules.yaml: money_path_services`. Money path vede přes `consent-service`, `transaction-service` a exekutory plateb (`sepa-payment`, `sepa-instant`, `domestic-payment`), `clearing-service` a `ledger-service` — na které tato fasáda *deleguje*. PSD2 je regulovaný **přístupový kanál** před nimi; jeho útočný povrch je významný (je vystaven do internetu a iniciuje platby), proto je s ním zacházeno s rigorózností přiléhající k money-path, i když nenese label `money-path` a neperzistuje žádnou hodnotu.

## Regulatorní rámec

| Regulace | Vztah k této službě | Implementace |
|---|---|---|
| **PSD2** (Směr. (EU) 2015/2366) + RTS pro SCA/CSC | Toto **je** PSD2 povrch — AIS, PIS, životní cyklus souhlasů pro TPP | `/open-banking/v2` AIS/PIS/souhlasy; eIDAS QWAC / `X-TPP-ID` autentizace TPP; kontrola role AISP/PISP přes tpp-registry; čtení/iniciace podmíněné souhlasem |
| **ČOBS** (Czech Open Banking Standard) | Lokální verze Open Bankingu | produkty `DOMESTIC_CZ` + `SIPO`; variabilní/specifický/konstantní symbol; rozšíření souhlasu standing-orders / direct-debits; TPP webhook události |
| **eIDAS** (Nař. (EU) 910/2014) | Identifikace TPP přes QWAC certifikáty | `EidasMtlsFilter` čte `SSL-CLIENT-S-DN` (mTLS terminované na bráně) |
| **GDPR** | IBANy a data PSU procházejí kanálem | PII maskováno v logu (`****<last4>` pro IBANy); žádné PII v klidu; práva obsluhují vlastnící služby |
| **AMLD** | Iniciace plateb krmí AML/sankční screening downstream | screening běží v exekutor/clearing cestě, ne zde; události emitovány pro audit |
| **DORA** (Nař. (EU) 2022/2554) | Provozní odolnost kritického přístupového kanálu | circuit breakery / retry / fallbacky, health probes, metriky, audit události, SLO, runbooky |
| **NIS2** | Síťová a informační bezpečnost | mTLS v clusteru, striktní bezpečnostní response hlavičky, omezený CORS, audit log |

## PSD2 specifika

### Autorizace TPP (čl. 30, RTS čl. 34)

Identita TPP pochází z eIDAS QWAC certifikátu (subject DN přes `SSL-CLIENT-S-DN`) nebo `X-TPP-ID`; role (`AISP` pro AIS/souhlasy, `PISP` pro platby) je ověřena proti `tpp-registry-service` před spuštěním jakékoli byznysové logiky. Žádná identita ⇒ `401 CERTIFICATE_MISSING`; neautorizováno ⇒ `401 CERTIFICATE_INVALID`; registr nedostupný ⇒ `503` (fail closed).

### Souhlas (čl. 64–67)

Každé AIS čtení a PIS iniciace volá `consent-service.validateConsent(consentId, tppId, scope, iban)`. **Fallback odpírá přístup** (`false`), když je consent-service nedostupná — kanál selhává uzavřeně. `validUntil` souhlasu je při vytvoření omezeno na 90 dní; `frequencyPerDay` a `recurringIndicator` se přenášejí dál. ČOBS rozšíření přístupu se mapují na scopes `STANDING_ORDERS_READ` / `DIRECT_DEBITS_READ`.

### SCA (RTS čl. 4, ADR-0021)

Silné ověření zákazníka se **zde neprovádí**. Fasáda vystavuje SCA odkazy (`scaRedirect`, `startAuthorisation`) a `scaStatus`; decoupled flow schvalování na zařízení (bez auto-approve) žije v `sca-service` — viz [ADR 0021](../../../../docs/adr/0021-sca-decoupled-device-approval-no-auto-approve.md).

### Iniciace platby (PIS, čl. 66)

Podporované produkty: SEPA úhrada, instant SEPA, tuzemská CZ, SIPO. Iniciace je přeposlána do `transaction-service`; `initiatePayment` **nemá fallback odolnosti**, takže downstream selhání se projeví jako chyba, nikoli falešný úspěch. Idempotence (`Idempotency-Key`) činí opakování replay-safe.

## Mapování GDPR

### Správce / zpracovatel

Pro PSD2 přístupový kanál je OpenBank **správcem** podkladových dat; tato služba jedná jako **pass-through** — neperzistuje osobní data držitelů účtů. Osobní data procházejí jen za letu při obsluze AIS čtení nebo PIS iniciace, pod explicitním souhlasem PSU.

### Právní základ (čl. 6)

- **Souhlas / smlouva** (čl. 6 odst. 1 písm. a)/b)) — PSD2 přístup je uplatňován pod explicitním souhlasem PSU a podkladovou smlouvou o účtu.
- **Právní povinnost** (čl. 6 odst. 1 písm. c)) — samotná PSD2 nařizuje přístupový kanál pro licencované TPP.

### Práva subjektu údajů

| Právo | Kde obsluhováno |
|---|---|
| Přístup (čl. 15) | vlastnící služby (`account-service`, `consent-service`, `party-service`) — tato fasáda neukládá PII |
| Oprava (čl. 16) | vlastnící služby |
| Výmaz (čl. 17) | vlastnící služby; AMLD má kde aplikovatelné přednost |
| Omezení (čl. 18) | zrušení souhlasu (`DELETE /open-banking/v2/consents/{id}`) zastaví přístup TPP |
| Přenositelnost (čl. 20) | N/A zde (žádná uložená data) |

### Datové toky

- → **tpp-registry-service** (REST): `tppId`, role — autorizace TPP.
- → **consent-service** (REST): `consentId`, `tppId`, scope, IBAN — ověření / životní cyklus souhlasu.
- → **account-service** (REST): `partyId`, `accountId` — AIS čtení (účty/zůstatky/transakce vráceny za letu).
- → **transaction-service** (REST): IBAN plátce/příjemce, částka, remittance — PIS iniciace.
- → **Kafka** `openbank.psd2.events` (outbox): asynchronní notifikace (zrušený souhlas, změna stavu platby, hlášení transakcí) pro audit a doručení TPP webhooků.

Žádná data neopouštějí region EU/EHP. Vnější hranicí je TPP, dosažený až po eIDAS autentizaci a ověření souhlasu.

## Mapování DORA (Nař. (EU) 2022/2554)

| Článek | Téma | Implementace |
|---|---|---|
| čl. 5/6 | Řízení ICT rizik | centralizovaná závislost na `openbank-libs`; v provozním registru |
| čl. 9 | Ochrana a prevence | striktní bezpečnostní hlavičky, omezený CORS, mTLS v clusteru, fail-closed souhlas |
| čl. 10 | Detekce | Prometheus metriky, OpenTelemetry trasy, alerting na chybovost / latenci / stav circuitu |
| čl. 11 | Odezva a obnova | circuit breakery + retry + fallbacky izolují downstream poruchy; runbooky v [05 — Provoz](./05-operations.md) |
| čl. 16/17 | Řízení a hlášení incidentů | outbox události do audit pipeline jako důkaz |
| čl. 28 | Riziko třetích stran | TPP prověřeni přes tpp-registry; žádný third-party SaaS v request cestě (vše self-hosted) |

## Bezpečnostní kontroly

- ✅ AuthN TPP: eIDAS QWAC (`SSL-CLIENT-S-DN`) nebo `X-TPP-ID`, kontrola role přes tpp-registry (`EidasMtlsFilter`).
- ✅ AuthZ na zdroj: ověření souhlasu u každého AIS čtení / PIS iniciace; **fail closed** při výpadku consent-service.
- ✅ Idempotence: povinná u PIS a vytvoření souhlasu, Redis-backed, replay-safe.
- ✅ Odolnost: MicroProfile Fault Tolerance (timeout / retry / circuit breaker / bulkhead / fallback) u každého výstupního volání.
- ✅ Bezpečnostní response hlavičky: CSP, HSTS, `X-Frame-Options: DENY`, `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`.
- ✅ Minimalizace PII: IBANy maskovány v logu; žádné PII v klidu.
- ✅ Audit: notifikace emitovány přes outbox → Kafka do audit pipeline.
- ✅ TLS: mTLS v clusteru; QWAC terminované na bráně.
- ⚠️ **Stub downstream klienti:** aktuální account/consent/transaction klienti jsou stuby (`StubClients.kt`); skuteční REST klienti jsou čekající follow-up před produkčním provozem TPP.
- ⚠️ **Drift OpenAPI ↔ kód:** názvy hlaviček a port serveru se liší mezi `openapi.yaml` a zdroji (viz [03 — API](./03-api.md)); sladit před publikací kontraktu TPP.
