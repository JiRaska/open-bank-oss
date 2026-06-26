# Compliance

Toto je **money-path služba** (`rules.yaml: money_path_services`): každá změna vyžaduje 2 schválení + threat model (`docs/threat-models/openbank-lending-service.md`, ADR-0030).

## Regulatorní rámec

| Regulace | Vztah ke službě | Implementace |
|---|---|---|
| **IFRS 9** (Finanční nástroje) | Opravné položky úvěru — stage + očekávané úvěrové ztráty | `GET /loans/{id}/provisioning`; čistá `libs.lending.Ifrs9` ECL; `RiskParameterSource` (PD/LGD); Stage 3 → odpis a derecognition |
| **EBA/GL/2020/06** (Poskytování a monitorování úvěrů) | Čtyřoč úvěrové rozhodnutí + segregace odpovědností | maker/checker/disburser vynucené na serveru z JWT subjektu; `409` při porušení |
| **IAS 1 / akruální účetnictví** | Úrok uznán při vydělání, ne při inkasu | naplánovaný akruální průchod (`INTEREST_ACCRUAL`), idempotenční příznak `interest_accrued` |
| **AnaCredit (Reg. (EU) 2016/867)** | Granulární reporting úvěrů a zajištění | kategorie ochrany `collateral_type`; atributy úvěr/party/expozice uchovány |
| **AMLD** (Boj proti praní špinavých peněz) | Podezřelá úvěrová aktivita, auditovatelnost odpisů | každá peněžní událost + rozhodnutí emituje doménovou událost do audit pipeline; AML hold může prodloužit retenci |
| **GDPR** | `party_id` je pseudonymní reference; identity operátorů jsou osobní údaj | žádné jméno klienta/IBAN/rodné číslo neukládáno; 7letá retence záznamů překrývá výmaz |
| **DORA** (Reg. (EU) 2022/2554) | Provozní odolnost | health probes, BootstrapVerifier, fault-tolerant volání ledgeru, audit události, SLO, runbooky |
| **NIS2** | Bezpečnost sítí a informací | mTLS in-cluster, bezpečnostní hlavičky, JSON audit logging |
| **ČNB uchovávání úvěrových záznamů** | Retence úvěrové smlouvy | 7letá retenční politika (`governance.yaml`) |

## GDPR mapování

### Právní základ (čl. 6)

- **Smlouva** (čl. 6(1)(b)) — primární: správa úvěru je nezbytná pro plnění úvěrové smlouvy.
- **Právní povinnost** (čl. 6(1)(c)) — sekundární: IFRS 9 opravné položky, AnaCredit reporting, AML, účetní/daňové uchovávání záznamů.

### Práva subjektu údajů

| Právo | Aplikace |
|---|---|
| Přístup (čl. 15) | `GET /loans?partyId=…` a `GET /applications?partyId=…` vrací data subjektu |
| Oprava (čl. 16) | opravy přes admin UI (audit logováno) |
| Výmaz (čl. 17) | **Neaplikovatelné u aktivního/uzavřeného úvěru** — uchovávání záznamů (7 let) a AMLD má přednost |
| Omezení (čl. 18) | přechody stavu žádosti/úvěru (např. `REJECTED`); žádné další zpracování |
| Přenositelnost (čl. 20) | export dat dle `party_id` (CSV/JSON) — TBD jako formální endpoint |
| Námitka (čl. 21) | N/A (zde žádné marketingové zpracování) |

### Minimalizace dat

Tato služba ukládá pouze pseudonymní `party_id` plus ekonomiku úvěru a identity operátorů, kteří učinili jednotlivá rozhodnutí. Žádné jméno klienta, kontakt, IBAN ani rodné číslo se nedrží — ty žijí v `party-service`.

### Toky dat ven

- → **ledger-service** (REST `POST /api/v1/journals`): GL účty, částka, měna, system-actor — účetní zápisy, stejný správce, intra-OpenBank.
- → **Kafka `openbank.lending.events`** (audit / analytika): payload události s `loanId`, `partyId`, částkami — stejný správce.
- Žádná data neopouštějí region EU/EHP.

### Retence (čl. 5(1)(e))

| Stav úvěru | Retence |
|---|---|
| `ACTIVE` | průběžně |
| `CLOSED` | 7 let po uzavření (úvěrová smlouva / účetní uchovávání) |
| `WRITTEN_OFF` | 7 let; déle při AML případu |

## DORA mapování (Reg. (EU) 2022/2554)

| Článek | Téma | Implementace |
|---|---|---|
| čl. 5 | Řízení ICT rizik | money-path, T0 vždy zapnuté (ADR-0057) |
| čl. 6 | Rámec řízení rizik | závislost = openbank-libs (centralizováno) |
| čl. 9 | Identifikace | `BuildInfo` (gitCommit, buildTime, version) v `/api/v1/info` |
| čl. 10 | Detekce | Micrometer/Prometheus metriky, OpenTelemetry trasy, alerting |
| čl. 11 | Reakce a obnova | runbooky v `05-operations.md`, RTO 15 min / RPO 5 min |
| čl. 16 | Řízení incidentů | doménové události do audit-service pro evidenci |
| čl. 28 | Riziko třetích stran | žádná třetí strana SaaS — ledger/Keycloak/Kafka vše self-hosted |

## Čtyřoč princip & segregace odpovědností (EBA/GL/2020/06, ADR-0028 D5)

```mermaid
sequenceDiagram
  participant Maker as úvěrový pracovník (maker)
  participant Checker as credit-risk (checker)
  participant Disburser as úvěrový pracovník (disburser)
  participant Svc as lending-service
  participant Led as ledger-service

  Maker->>Svc: POST /applications  (proposed_by = JWT subjekt)
  Note over Svc: stav PROPOSED
  Checker->>Svc: POST /applications/{id}/decision {approve}
  Note over Svc: 409 pokud checker == maker (čtyřoč)
  Note over Svc: stav APPROVED
  Disburser->>Svc: POST /applications/{id}/disburse
  Note over Svc: 409 pokud disburser == checker (SoD)
  Svc->>Svc: zaúčtuj úvěr + kalendář, zapiš outbox
  Svc->>Led: DISBURSEMENT zápis (MD Loans Receivable / DAL Funding Clearing)
```

Všechny tři identity jsou ověřený JWT subjekt zachycený na serveru — nikdy z klienta — takže oddělení nelze zfalšovat.

## IFRS 9 opravné položky

`GET /loans/{id}/provisioning?asOf=` vrací bodový snímek: zbývající zůstatek, dny po splatnosti, bucket delikvence, IFRS 9 stage, ECL horizont a očekávanou úvěrovou ztrátu. ECL matematika je čistá primitiva `libs.lending.Ifrs9` živená `RiskParameterSource` (dnes konzervativní no-op výchozí: PD12m 0.03, PD-lifetime 0.20, LGD 0.45 — reálný PD model je otázka zapojení). Stage 3 nevymahatelné úvěry končí přes `POST /loans/{id}/writeoff` → zápis `WRITE_OFF` (MD Loan Loss Expense / DAL Loans Receivable) a derecognition.

## Auditní stopa

Každá stavotvorná operace (disburse, accrue, write-off) emituje doménovou událost do `lending_outbox` → Kafka `openbank.lending.events`, persistovanou `audit-service`. Metadata rozhodnutí (`proposed_by`, `decided_by`, `decision_reason`, `decided_at`) zůstávají na řádku žádosti.

## Bezpečnostní kontroly

- AuthN: Keycloak OIDC, RS256 JWT; service-to-ledger přes OIDC-client (client-credentials).
- AuthZ: Quarkus `@RolesAllowed` (žádné `@PermitAll`); jednající principal = JWT subjekt, nezfalšovatelný.
- Čtyřoč + segregace odpovědností vynucené v aplikační službě.
- Validace vstupu (kladná částka, term ≥ 1, sazba ≥ 0, haircut `[0,1]`).
- Idempotentní účetní zápisy (reference = idempotency key ledgeru) a idempotentní akruální průchod.
- Bezpečnostní hlavičky (HSTS, CSP, X-Frame-Options, nosniff); TLS terminace na bráně, mTLS in-cluster.
- Tajemství: BootstrapVerifier blokuje dev placeholdery v prod.
- Odolnost: fault-tolerant volání ledgeru (`LedgerCallGuard`), ohraničené REST timeouty.
- ⚠️ `RiskParameterSource` / `CreditBureauPort` aktuálně používají konzervativní no-op výchozí hodnoty — reálný PD model a integrace registru jsou sledované roadmapové položky, ne regrese kontroly.
