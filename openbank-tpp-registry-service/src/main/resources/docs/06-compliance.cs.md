# Compliance

> **Money-path status:** `openbank-tpp-registry-service` **NENÍ** v `rules.yaml: money_path_services`. Patří do schopnosti **Open Banking (PSD2)** (kapabilita `open-banking-psd2` v `rules.yaml`, vedle psd2-service, consent-service, sca-service, pid-service; `regulatory_ref: PSD2 AISP/PISP`). Nepohybuje penězi; reguluje, kdo smí. Platí standardní review s 1 schválením, jde však o compliance-citlivý kontrolní bod.

## Regulatorní rámec

| Regulace | Vztah k této službě | Implementace |
|---|---|---|
| **PSD2** (Reg. (EU) 2015/2366) | Registr autorizací TPP — kdo smí uplatnit AISP/PISP/PIISP | `TppEntry` zrcadlí registr EBA/NCA; `GET /check` autorizuje každou roli per request |
| **PSD2 RTS** (Reg. (EU) 2018/389) — bezpečná komunikace & eIDAS | Identita & expirace QWAC/QSeal certifikátu | `qwac_subject_dn`, `qseal_subject_dn`, `qwac_expires_at`; check odmítá expirovaný QWAC |
| **GDPR** | Registr drží data právnických osob (TPP), ne zákaznické PII | `dataClassification: internal`; žádné party-id/IBAN/data fyzické osoby |
| **DORA** (Reg. (EU) 2022/2554) | Provozní odolnost control-plane služby | health probes, fault tolerance, OTel, runbooky, BuildInfo |
| **NIS2** | Síťová a informační bezpečnost | mTLS v clusteru, bezpečnostní hlavičky, OPA authz, audit přes outbox (čeká) |
| **AMLD** (nepřímo) | Screening onboardingu TPP / blacklist jako kontrola | blacklist API (`ACTIVE→BLACKLISTED`); `checkAuthorization` odmítá jakýkoli stav jiný než ACTIVE |
| **Autorizační registr CNB** | Pohled národního příslušného orgánu | `nca` + `tpp_id` klíčovány na identifikátor CNB/EBA |

## PSD2 — autorizační brána

Tato služba je **kotva důvěry** PSD2 stacku OpenBank. Tok:

```
TPP → psd2-service (Open Banking fasáda)
    → tpp-registry-service  GET /check?tppId=…&role=AISP|PISP|PIISP
        → ACTIVE? + má roli? + QWAC neexpirován? → autorizováno
    → consent-service (validuj souhlas zákazníka)
    → sca-service (silné ověření zákazníka, ADR-0021)
    → core banking (account/balance/payment)
```

`GET /check` je jediné úzké hrdlo: `403` zde zastaví TPP dříve, než se pokusí o jakýkoliv souhlas nebo bankovní operaci. Blacklisting je provozní kill-switch pro kompromitovaného nebo delicencovaného poskytovatele.

## eIDAS / práce s certifikáty

- Registr ukládá **identitu** certifikátu (Subject DN QWAC a QSeal) a **data expirace**, ne bajty certifikátu ani privátní klíče.
- Autorizace odmítá TPP, jehož `qwac_expires_at` je před dneškem.
- Živá validace řetězce certifikátu na TLS vrstvě a QWAC pinning je záležitost **edge/gateway**, zde se neprovádí (dokumentováno jako mimo rozsah v [01-overview](./01-overview.md)).

## Mapování GDPR

Registr se týká **právnických osob (TPP)**, takže expozice GDPR je minimální.

### Právní základ (čl. 6)
- **Právní povinnost** (čl. 6(1)(c)) — vedení registru autorizovaných TPP podporuje soulad s PSD2 a povinnosti CNB/EBA.

### Práva subjektu údajů
Obecně **nepoužitelná** na firemní data registru. Jediné pole, které by mohlo nechtěně nést osobní údaje, je `blacklist_reason` — operátoři musí udržovat reference na incidenty bez PII fyzických osob.

### Toky dat ven
- → **psd2-service** (synchronní API): autorizační rozhodnutí (`tppId`, `authorized`, `roles`, `reason`). Žádné zákaznické PII.
- → **Kafka** `openbank.tpp.registry.event` (uvnitř OpenBank, např. audit): události registrace/blacklist **až po zapojení** — aktuálně outbox transport existuje, ale žádné události se nevypouštějí.

Žádná data neopouštějí region EU/EHP.

### Retence (čl. 5(1)(e))
`governance.yaml: retentionPolicy: 5 years`. Záznamy registrace a blacklistu uchovávány 5 let; deautorizace je přechod stavu (`BLACKLISTED`), ne tvrdé smazání, čímž zachovává autorizační auditní stopu. `REVOKED` a `SUSPENDED` v enumu existují, ale dnes je nic nezapisuje (#6489), takže odejmutá autorizace se zaznamenává jako blacklisting.

## Mapování DORA (Reg. (EU) 2022/2554)

| Článek | Téma | Implementace |
|---|---|---|
| Čl. 5 | Řízení ICT rizik | control-plane služba v centrálním registru operací |
| Čl. 6 | Rámec řízení rizik | závislost = openbank-libs (centralizováno) |
| Čl. 9 | Identifikace | `BuildInfo` (gitCommit, buildTime, version) v `/api/v1/info` |
| Čl. 10 | Detekce | OTel metriky + alerting na error rate / latenci `/check` |
| Čl. 11 | Odezva & obnova | runbooky v [05-operations](./05-operations.md); RTO 15 min, RPO 5 min |
| Čl. 16 | Řízení incidentů | blacklist kill-switch; události do auditu (čeká na zapojení) |
| Čl. 28 | Riziko třetích stran | sync registru EBA je plánovaný feed dat třetí strany (aktuálně stub, fault-tolerant obal připraven) |

## Autorizace a bezpečnostní kontroly

- ✅ **AuthN:** Keycloak OIDC (RS256 JWT). Vypnuto jen v `%dev`/`%test`.
- ✅ **AuthZ:** OPA sidecar (ADR-0034) přes `@Authorize` na blacklistu; defaultně **advisory** režim (`authz.enforce=false`) — přepnutí na enforce přes `AUTHZ_ENFORCE=true`.
- ✅ **Idempotence:** `Idempotency-Key` na všech mutacích (Redis-backed).
- ✅ **Rate limiting:** max 50 souběžných požadavků (`openbank.rate-limit`).
- ✅ **Bezpečnostní hlavičky:** CSP `default-src 'self'`, HSTS, X-Frame-Options DENY, X-Content-Type-Options nosniff, Referrer-Policy, Permissions-Policy.
- ✅ **TLS:** mTLS v clusteru (Istio), TLS terminace na gateway.
- ✅ **Tajemství:** dev placeholdery (`CHANGE_ME_LOCAL_DEV_ONLY`) musí být v prod nahrazeny (ADR-0017 Vault).
- ✅ **Odolnost:** MicroProfile Fault Tolerance na EBA syncu (`@Timeout`/`@Retry`/`@CircuitBreaker`) a outbox publisheru (`@Bulkhead`/`@CircuitBreaker`/`@Retry`/`@Timeout`).
- ⚠️ **Auditní události:** vypouštění doménových událostí do outboxu **zatím není zapojeno** — akce registrace/blacklist nejsou zatím na Kafka auditní stopě. Sledováno jako hlavní follow-up.
- ⚠️ **Pokrytí OPA:** `@Authorize` nese jen mutace blacklist; register a EBA-sync mutace dnes spoléhají jen na OIDC.
