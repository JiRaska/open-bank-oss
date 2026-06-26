# Compliance

`openbank-ledger-service` je **money-path / T0** služba ([rules.yaml](../../../../openbank-libs/governance/rules.yaml) `money_path_services`). Změny vyžadují 2 schválení + udržovaný threat model ([docs/threat-models/openbank-ledger-service.md](../../../../docs/threat-models/openbank-ledger-service.md), ADR-0030). Jako kniha záznamů banky je v rozsahu účetní, AML a operačně-odolnostní regulace.

## Regulatorní rámec

| Regulace | Vztah k této službě | Implementace |
|---|---|---|
| **Zákon o účetnictví 563/1991 Sb.** | Podvojná hlavní kniha je zákonný účetní záznam | neměnné POSTED zápisy, oprava pouze stornem (`reversal_of`), 10letá retence partitionů, append-only `partition_lifecycle_audit` |
| **Vyhláška 501/2002 Sb. + ČÚS 108–110** | Účetní layout banky, analytická evidence po zákaznících, kurzové rozdíly | `sub_account_id` tie-outuje deposit-control účty HK (ADR-0039 fáze B); účet 5900 Kurzové rozdíly účtuje denní revalvace |
| **AMLD 6** | Uchovávání transakčních/účetních záznamů | 10letá retence (čl. 40); reference v knize (`transaction_id`, `sub_account_id`) podporují vyšetřování |
| **DORA** (nař. (EU) 2022/2554) | Operační odolnost kritické ICT funkce | health probes, single-writer regulatorní outbox (ADR-0050), kontrola integrity předvahy, SLO, runbooky, audit události |
| **GDPR** | Pseudonymní finanční data, žádné přímé identifikátory | jen UUID reference (žádné jméno/IBAN/RČ); 10letá zákonná retence přebíjí výmaz |
| **PSD2** (nař. (EU) 2015/2366) | Nepřímý — účetní páteř za vypořádáním plateb | kniha je interní; žádný přímý přístup TPP |
| **NIS2** | Síťová a informační bezpečnost | mTLS v clusteru, bezpečnostní hlavičky, žádný neautentizovaný endpoint (ADR-0018), audit log |
| **Kurz ČNB** | Zákonné denní ocenění devizových pozic | denní mark-to-ČNB revalvace (ADR-0046) z oficiálního kurzu z fx-service |

## Mapování GDPR

### Povaha dat

Hlavní kniha ukládá **účetní a finanční data, ne přímá osobní data**. V žádné tabulce není jméno, IBAN, e-mail, adresa ani rodné číslo. Pole relevantní pro soukromí jsou pseudonymní reference: `transaction_id`, `sub_account_id` (reference na zákaznický účet) a UUID aktérů zaměstnance/systému (`created_by`). Re-identifikace vyžaduje spojení přes `account-service` / `transaction-service`, což jsou v rámci OpenBank samostatní správci.

### Právní základ (čl. 6)

- **Právní povinnost** (čl. 6(1)(c)) — primární: vedení zákonné podvojné hlavní knihy (563/1991 Sb.) a AML záznamů.
- **Smlouva** (čl. 6(1)(b)) — sekundární: vypořádání transakcí zákazníka se zde účtuje.

### Práva subjektu údajů

| Právo | Aplikace |
|---|---|
| Přístup (čl. 15) | zápisy subjektu dosažitelné přes `sub_account_id` (`GET /journals/sub-ledger-balances?subAccountId=…`), vyřešeno přes account-service |
| Oprava (čl. 16) | **pouze storno** — zaúčtovaný zápis se nikdy needituje; účtuje se opravné vyvážené storno (audit zachován) |
| Výmaz (čl. 17) | **Neaplikovatelné** — zákonná účetní + AML retence (10 let) přebíjí |
| Omezení (čl. 18) | upstream (zmrazení účtu) — kniha samotná je append-only |
| Přenositelnost (čl. 20) | N/A — účetní záznam, ne data dodaná zákazníkem |
| Námitka (čl. 21) | N/A — žádné marketingové/profilovací zpracování |

### Toky dat ven

- → **balance-service** / **audit-service** (Kafka `openbank.ledger.journal.posted`): události zaúčtování (id agregátu, id transakce, číslo zápisu, počet řádků) — stejný správce, intra-OpenBank.
- → **balance-service** (REST rekonciliační čtení): agregáty předvahy a analytiky — stejný správce.
- → **fx-service** (REST, odchozí): jen kód měny + datum k načtení kurzu ČNB — žádná zákaznická data neodchází.
- Topic `openbank.ledger.fx.revalued`: souhrn revalvace (bez zákaznické dimenze).

Žádná data neopouští region EU/EHP (Česká republika primárně, Irsko DR).

### Retence (čl. 5(1)(e))

| Data | Retence |
|---|---|
| Zápisy / řádky | 10 let (563/1991 Sb. + AMLD 6 čl. 40) přes životní cyklus ročních partitionů |
| `partition_lifecycle_audit` | 10 let (důkaz detach/drop) |
| `ledger_outbox` (odeslané) | krátkodobě provozní; trimováno po úspěšném dispatchi |

## Mapování DORA (nař. (EU) 2022/2554)

| Článek | Téma | Implementace |
|---|---|---|
| Čl. 5 | Řízení ICT rizik | money-path/T0 služba v centrálním registru |
| Čl. 6 | Rámec řízení ICT rizik | centralizovaná závislost `openbank-libs` |
| Čl. 9 | Ochrana & prevence | invariant per-měnového vyvažování; neměnná kniha; single-writer outbox |
| Čl. 9 | Identifikace | `BuildInfo` (gitCommit, buildTime, version) v `/api/v1/info` |
| Čl. 10 | Detekce | kontrola integrity předvahy, Prometheus metriky + alerting na zpoždění outboxu / chybovost |
| Čl. 11 | Reakce & obnova | runbooky v [05-operations](./05-operations.md); oprava pouze stornem; RTO/RPO dle T0 |
| Čl. 16/17 | Incident management & reporting | události JournalPosted do audit-service; nenulová předvaha je P1 |
| Čl. 28 | Riziko třetích stran | žádný third-party SaaS — vše self-hosted; kurz ČNB z interní fx-service |

## Bezpečnostní kontroly

- ✅ AuthN: Keycloak OIDC, RS256 JWT.
- ✅ AuthZ: Quarkus `@RolesAllowed` z `libs.security.Roles`; **žádný neautentizovaný endpoint** (ADR-0018); čtení omezeno na service/auditor/viewer/operator/admin; účtování/storno/FX-revalvace jen operátor. Zamčeno `LedgerSecurityContractTest`.
- ✅ Integrita účetnictví: per-měnové podvojné vyvažování vynucené v agregátu (`JournalEntry.validateBalance()`); neměnná oprava pouze stornem.
- ✅ Idempotence: tabulka `ledger_idempotency` deduplikuje at-least-once retry upstreamu (money-path pojistka).
- ✅ Regulatorní doručení: single-writer transakční outbox, izolovaná/ohraničená selhání → DEAD (ADR-0050).
- ✅ Validace vstupů (Bean Validation + doménové invarianty), output encoding (Jackson).
- ✅ Rate limiting: `openbank.rate-limit` (100 souběžných).
- ✅ Bezpečnostní hlavičky: CSP, HSTS, X-Frame-Options DENY, X-Content-Type-Options nosniff, Referrer-Policy, Permissions-Policy.
- ✅ TLS: mTLS v clusteru; secrets přes Vault (dev placeholdery fail-fast v prod).
- ✅ Audit: každé zaúčtování → audit-service přes událost; životní cyklus partitionů → neměnná audit tabulka.
- ✅ Udržovaný threat model (ADR-0030, požadavek money-path).
