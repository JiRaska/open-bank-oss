# Compliance

audit-service je služba **compliance domény** (`governance.yaml: dataDomain: compliance`, `dataClassification: restricted`). Je to evidenční úložiště platformy — sama neleží na money path, ale podpírá auditovatelnost každé služby, která tam leží. **Není** v `rules.yaml: money_path_services`.

## Regulatorní rámec

| Regulace | Vztah ke službě | Implementace |
|---|---|---|
| **DORA** (Reg. (EU) 2022/2554) | Evidence ICT incidentů a provozní resilience | neměnný event log, flagování security událostí, health/metriky, runbooky ([05](./05-operations.md)) |
| **EBA ICT & Security Risk Guidelines** | Požadavky na audit logging (citováno doslovně v migraci V2) | `is_security_event` pro SIEM, neměnné řádky, 10leté `retention_until` |
| **GDPR** (Reg. (EU) 2016/679) | Ukládá osobní údaje (IP, user-agent, actor, payloady událostí) | klasifikace `data_sensitivity`, omezené access role, retence překrývá výmaz |
| **AMLD / AML** | Dlouhodobá retence transakční evidence | 10letá retence (AMLD 6 čl. 40) vynucená triggerem + pravidly blokujícími delete |
| **PSD2** | Zaznamenává consent a payment-initiation události tekoucí fleetem | konzumuje `openbank.consent.events`, `openbank.transactions.*` |
| **NIS2** | Síťová a informační bezpečnost, záznam security událostí | `is_security_event`, mTLS in-cluster, striktní security headers, role-gated read API |
| **SOX-style interní kontroly** | Read-only, oddělená auditní evidence | segregace `ROLE_AUDITOR`, K7 regresní pojistka |

## GDPR mapping

### Právní základ (čl. 6)

- **Právní povinnost** (čl. 6(1)(c)) — primární: vedení auditní stopy bankovních operací je regulatorní požadavek (DORA, EBA ICT, AML, ČNB).
- **Oprávněný zájem** (čl. 6(1)(f)) — sekundární: bezpečnostní monitoring a detekce podvodu/zneužití (`is_security_event`, `risk_score`).

### Práva subjektu údajů

| Právo | Aplikace |
|---|---|
| Přístup (čl. 15) | Stopa pro agregát subjektu přes `GET /api/v1/audit/entries/{aggregateId}` (role-gated; přístup subjektu řeší odpovědná controller služba, ne self-serve) |
| Oprava (čl. 16) | **Nerelevantní** — auditní řádky jsou z principu neměnné; oprava je připojený kompenzační záznam |
| Výmaz (čl. 17) | **Nerelevantní / překryto** — AML & EBA ICT retence (10 let) je právní povinnost překrývající výmaz; DB pravidlo `no_delete` to vynucuje fyzicky |
| Omezení (čl. 18) | Omezení přístupu se dosahuje role gatingem, ne úpravou záznamů |
| Přenositelnost (čl. 20) | N/A — auditní evidence není subjektem poskytnutá smluvní data |
| Námitka (čl. 21) | N/A — žádné marketing/profiling zpracování |

### Datové toky

**Dovnitř (consume, Kafka):** account, transaction, balance, party, kyc, consent služby → audit-service. Audit store je downstream **processor** payloadů, jejichž controllery jsou zdrojové služby; klasifikace se řídí nejcitlivějším přítomným polem.

**Ven:**
- Read API → admin-ui (auditoři/admini/compliance), intra-OpenBank, role-gated.
- `audit_outbox` → Kafka re-emit (compliance/SIEM stream) — zapojeno v kódu, odchozí kanál zatím nenakonfigurován ([05](./05-operations.md)).

Žádná data neopouštějí region EU/EHP.

### Retence (čl. 5(1)(e))

| Data | Retence | Mechanismus |
|---|---|---|
| Každý audit záznam | 10 let od `occurred_at` | trigger `trg_audit_retention` + `audit-retention-days: 3650` |
| Security události | 10 let (stejný store, flagováno) | `is_security_event` |

Smazání před expirací je blokováno na úrovni databáze (pravidlo `no_delete_audit`). Purge po expiraci je samostatný auditovaný maintenance job.

## DORA mapping (Reg. (EU) 2022/2554)

| Článek | Téma | Implementace |
|---|---|---|
| čl. 9 | Identifikace | `BuildInfo` (gitCommit, buildTime, version) přes `openbank-libs` `/api/v1/info` |
| čl. 10 | Detekce | flag `is_security_event` + Prometheus metriky/alerting na ingest lag a error rate |
| čl. 11 | Odezva & obnova | runbooky v [05](./05-operations.md); Kafka `earliest` replay pro backfill (RPO 0 pro commitnuté řádky) |
| čl. 16 | Řízení incidentů | audit-service **je** evidenční store, do kterého ostatní služby emitují |
| čl. 17 | Reporting | stopa poskytuje neměnný evidenční základ pro reporty velkých incidentů |
| čl. 28 | Riziko třetích stran | žádný third-party SaaS — PostgreSQL/Kafka self-hosted in-cluster |

## Audit-of-the-auditor — integritní kontroly

- **Neměnnost z konstrukce** — PostgreSQL pravidla `DO INSTEAD NOTHING` na UPDATE/DELETE; integrita stopy nezávisí na disciplíně aplikace.
- **Append-only oprava** — chyby se opravují připojením, původní záznam zůstává.
- **K7 access control** — read API není nikdy `@PermitAll`; gated na `ROLE_AUDITOR` / `ROLE_ADMIN` / `ROLE_COMPLIANCE`, uzamčeno regresní pojistkou `AuditResourceSecurityTest`.
- **Žádné write API** — záznamy mohou přijít jen přes Kafku od autentických platformových producentů; neexistuje operátorská insert cesta k podvržení záznamů.

## Bezpečnostní kontroly

- ✅ AuthN: Keycloak OIDC, RS256 JWT
- ✅ AuthZ: `@RolesAllowed` (auditor/admin/compliance), K7-guarded
- ✅ Neměnné úložiště: odmítnutí UPDATE/DELETE na úrovni DB
- ✅ Rate limiting: `openbank.rate-limit` (200 concurrent)
- ✅ Security headers: CSP `default-src 'self'`, HSTS, `X-Frame-Options: DENY`, nosniff, referrer/permissions policy
- ✅ Resilience: outbox dispatcher s bulkhead/circuit-breaker/retry/timeout
- ✅ Observability: Prometheus + OpenTelemetry + SmallRye Health
- ✅ Secrets: env-injektované, dev placeholdery se nikdy nešipují (Vault, ADR-0017)
- ⚠️ Odchozí re-emit kanál (`audit-events-out`) zatím nenakonfigurován — spící ([05](./05-operations.md))
- ✅ Pole datastore v `governance.yaml` odpovídají kódu (`primaryDatastore: PostgreSQL`, `databaseName: openbank_audit`; tabulky v `public`)
