# Compliance

`openbank-kyc-service` je platformní služba pro správu KYC/CDD případů. **Není** na seznamu `money_path_services` v `rules.yaml` (nepřesouvá peníze), ale je to **compliance-kritická služba s restricted daty**: je to audit-grade záznam o tom, jak klient prošel Customer Due Diligence.

## Regulatorní rámec

| Regulace | Vztah ke službě | Implementace |
|---|---|---|
| **AMLD (4/5/6)** — směrnice proti praní peněz | Jádro: CDD/EDD, PEP screening, kontrola sankcí, vedení záznamů | životní cyklus KYC případu, `pep_declaration`, `due_diligence_level` (SDD/CDD/EDD), 10letá retence |
| **EBA AML/CFT Guidelines** | Rizikově orientovaná hloubková prověrka, periodická revize | `risk_level`, `next_review_date` (HIGH 1r / MEDIUM 2r / LOW 3r), pole eskalace |
| **FATF doporučení** (zejm. R.10, R.12) | CDD, zdroj prostředků/majetku, PEP | `source_of_funds`, `source_of_wealth`, `business_purpose`, `expected_turnover` |
| **ČNB / český AML zákon (253/2008 Sb.)** | Národní transpozice AML — identifikace a kontrola | kontroly totožnosti/adresy, skutečný majitel (`beneficial_owner_id`) |
| **GDPR** | KYC data jsou restricted / blízko zvláštní kategorii | role-gated přístup, pseudonymní `party_id`, 10letá AML retence přebíjí výmaz |
| **PSD2** | KYC clearance předchází onboardingu účtu/plateb | KYC stav konzumován onboardingem; ne přímý PSD2 povrch |
| **DORA** (Nař. (EU) 2022/2554) | Provozní odolnost | health probes, odolnost outboxu, audit události, SLO, runbooky |
| **NIS2** | Bezpečnost sítí a informací | mTLS v clusteru, bezpečnostní response hlavičky, OPA autorizace, audit log |

## Kontrola čtyř očí (ADR-0068)

Schválení a zamítnutí jsou akce **dvojí kontroly**: `POST /cases/{id}/approve` a `/reject` vyžadují `ROLE_ADMIN`/`ROLE_KYC` **a** procházejí přes `@Authorize` (OPA, ADR-0034). Operátor, který případ otevřel/zpracoval, jej nesmí schvalovat — vynuceno politikou čtyř očí onboarding cockpitu. Sandboxová straight-through cesta (`openbank.kyc.auto-approve`) toto obchází a **v produkci musí být false**; schválení připsané `sandbox-auto-approval` mimo sandbox je compliance incident.

## Mapování GDPR

### Právní základ (čl. 6)

- **Právní povinnost** (čl. 6(1)(c)) — primární: AML/CFT identifikace a hloubková prověrka jsou zákonné povinnosti (AMLD, český AML zákon).
- **Smlouva** (čl. 6(1)(b)) — sekundární: KYC je předpoklad bankovní smlouvy.

KYC zjištění se mohou dotýkat dat **blízkých zvláštní kategorii** (nepříznivá média, PEP). Přístup je omezen na role KYC/compliance/admin.

### Práva subjektu údajů

| Právo | Aplikace |
|---|---|
| Přístup (čl. 15) | `GET /api/v1/kyc/cases/party/{partyId}` vrací stav případu subjektu |
| Oprava (čl. 16) | přehodnocení kontroly přes `PUT …/checks/{checkType}` (auditováno) |
| Výmaz (čl. 17) | **Neaplikovatelné** během zákonné lhůty — 10letá AML retence přebíjí |
| Omezení (čl. 18) | případ lze držet v neterminálním stavu (např. `UNDER_REVIEW` / eskalace) |
| Přenositelnost (čl. 20) | N/A — AML záznamy jsou zpracování z právní povinnosti, nepřenositelné |
| Námitka (čl. 21) | N/A — žádné marketingové/souhlasové zpracování zde |

### Toky dat ven

- → **party-service** (události / API): výsledek KYC řídí aktivaci party — `partyId`, `status`. Stejný správce, intra-OpenBank.
- → **aml-service / sanctions-service** (Kafka `openbank.kyc.events`): `kycCaseId`, `partyId`, `status`, `riskLevel` pro spuštění / korelaci screeningu.
- → **notification-service** (Kafka): metadata události pro notifikace klientovi/ops.
- → **audit-service** (Kafka / události): kompletní stopa rozhodnutí pro důkazy.

Příchozí: ← **party-service** (`openbank.party.events`, `PARTY_CREATED`) pro auto-otevření případu.

Žádná data neopouštějí region EU/EHP. Žádné third-party SaaS v cestě požadavku (skutečná integrace poskytovatele screeningu je v `sanctions-service`).

### Retence (čl. 5(1)(e))

| Stav případu | Retence |
|---|---|
| Aktivní (OPEN … UNDER_REVIEW) | průběžně |
| APPROVED / REJECTED / EXPIRED | **10 let** po ukončení vztahu (vedení záznamů AMLD, governance.yaml) |

## Mapování DORA (Nař. (EU) 2022/2554)

| Článek | Téma | Implementace |
|---|---|---|
| Čl. 5 | Řízení ICT rizik | služba v centrálním provozním registru |
| Čl. 6 | Rámec řízení rizik | závislost = openbank-libs (centralizováno) |
| Čl. 9 | Identifikace | `BuildInfo` (gitCommit, buildTime, verze) na `/api/v1/info` |
| Čl. 10 | Detekce | Micrometer metriky + alerting na error rate / latenci / zpoždění outboxu |
| Čl. 11 | Reakce a obnova | runbooky v `05-operations.md`, RTO 15 min / RPO 5 min; outbox `@CircuitBreaker`/`@Retry` |
| Čl. 16 | Řízení incidentů | doménové události do audit-service jako důkaz |
| Čl. 28 | Riziko třetích stran | žádné third-party SaaS v této službě — poskytovatel screeningu izolován v sanctions-service |

## Audit trail

Každý přechod stavu emituje doménovou událost (`KYC_CASE_OPENED` / `_STATUS_CHANGED` / `_APPROVED` / `_REJECTED`) přes transakční outbox → `audit-service`, který ji uchovává po zákonnou dobu. Rozhodnutí čtyř očí (`reviewed_by`, `reviewed_at`, `notes` zamítnutí) je uloženo na samotném případu.

## Bezpečnostní kontroly

- ✅ AuthN: Keycloak OIDC, RS256 JWT bearer
- ✅ AuthZ: Quarkus `@RolesAllowed` per endpoint + `@Authorize` (OPA, ADR-0034) na mutacích čtyř očí / kontrol
- ✅ Dvojí kontrola: schválit/zamítnout čtyřma očima (ADR-0068)
- ✅ Doménová idempotence: `uq_kyc_cases_active_party` brání duplicitním aktivním případům při replayi/scale-outu
- ✅ Validace vstupu: Bean Validation na request DTO; enum-omezené `checkType` / status
- ✅ Výstupní bezpečnostní hlavičky: HSTS, CSP `default-src 'self'`, X-Frame-Options DENY, X-Content-Type-Options nosniff
- ✅ Odolnost: outbox `@Bulkhead`/`@CircuitBreaker`/`@Retry`/`@Timeout`, konzument odolný vůči poison-pill
- ✅ Secrety: DB/OIDC secrety přes env, Vault v prod; dev placeholdery musí být přepsány
- ✅ Audit: každá změna stavu → audit-service přes událost
- ⚠️ OPA autorizace je defaultně **advisory** (`authz.enforce=false`) — přepni na enforce dle rolloutu ADR-0034
- ⚠️ Sandbox auto-approve (`openbank.kyc.auto-approve`) musí být ověřen jako `false` v každém ne-sandbox prostředí
