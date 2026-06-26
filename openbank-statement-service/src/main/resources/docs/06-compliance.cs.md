# Compliance

statement-service je služba **domény compliance** (governance.yaml: `dataDomain: compliance`, `dataClassification: restricted`, retence 10 let). Produkuje právně významný výpis z účtu. **Není** to money-path služba (`rules.yaml: money_path_services`) — rekonciliuje proti zůstatkům, ale s penězi nehýbe.

## Regulatorní rámec

| Regulace | Vztah ke službě | Implementace |
|---|---|---|
| **PSD2** (Reg. (EU) 2015/2366) | čl. 58(2): výpis platebních transakcí poskytnut *nebo zpřístupněn* alespoň měsíčně, reprodukovatelný beze změny | model "zpřístupnit" — ukládá se jen záznam uzávěrky; camt.053/MT940/PDF renderováno **deterministicky na vyžádání**, bajt po bajtu identicky |
| **ČNB / české účetnictví & AML retence** | 10leté uchování záznamu výpisu | retence na reprodukovatelném záznamu `statement_period` |
| **ISO 20022 / SWIFT MT** | strojově čitelné formáty výpisu | `Camt053Renderer` (camt.053.001.08), `Mt940Renderer` (MT940) |
| **GDPR** (Reg. (EU) 2016/679) | IBAN + jméno majitele jsou PII | neuchováváno jako řádky — přítomno jen přechodně při renderu; AML retence přebíjí výmaz pro záznam |
| **AMLD** (směrnice proti praní peněz) | výpis = finanční záznam v auditní kvalitě | fail-closed rekonciliace garantuje integritu; 10letá retence |
| **DORA** (Reg. (EU) 2022/2554) | provozní odolnost | health probes, metriky/alerting, durabilní telemetrie close-run, runbooky, odolný outbox |
| **NIS2** | bezpečnost sítí a informací | OIDC autentizace, mTLS v clusteru, bezpečnostní hlavičky, audit přes události |
| **PAD** (směrnice o platebních účtech) | čl. 5 roční výkaz poplatků | **mimo scope** — tato *push* povinnost patří doméně poplatků/billingu, zde se neprodukuje |

## Garance integrity — fail-closed rekonciliace (ADR-0035 §E)

Definující compliance kontrola: koncový zůstatek uzávěrky období je `opening + zaúčtovaný čistý pohyb` a **musí se rovnat** koncovému zůstatku nezávisle hlášenému balance-service pro danou kapsu.

```
opening ± Σ(zaúčtované položky)  ==  balance-service koncový zůstatek  ?
        │                                  │
        └────────── shoda ──────────────────┘ → uzavři (přiděl právní sekvenci, emituj událost)
        └────────── nesoulad ───────────────┘ → ReconciliationException → HTTP 409
                                                 (ŽÁDNÝ záznam období, ŽÁDNÁ událost, zaznamenáno jako selhání)
```

Samo-rozporný právní výpis se **nikdy** nevydá. Porovnání je přesné (`BigDecimal.compareTo`, necitlivé na scale). Selhání per-kapsa během plánované kadence jsou perzistována do `statement_close_failure` s `reason ∈ {RECONCILIATION, UPSTREAM, UNKNOWN}` a emitována jako `period.close_failed`.

## Garance determinismu (ADR-0035 §D/§F)

Každý vyrenderovaný formát bere všechna časová razítka z `StatementModel.closedAt` (razítkováno jednou při uzávěrce a uloženo), nikdy z hodin systému. Re-render téhož uzavřeného období je bajt po bajtu identický — hlídáno unit testy rendererů. To je to, co dělá model "ukládej záznam, ne soubor" legálním dle PSD2 čl. 58(2) "reprodukovatelné beze změny".

## Mapování GDPR

### Právní základ (čl. 6)
- **Právní povinnost** (čl. 6(1)(c)) — primární: poskytování výpisu dle PSD2, uchovávání záznamů dle ČNB/AML.
- **Smlouva** (čl. 6(1)(b)) — sekundární: výpis je součástí plnění smlouvy o účtu.

### Práva subjektu údajů
| Právo | Aplikace |
|---|---|
| Přístup (čl. 15) | `GET /api/v1/statements/{accountId}` + render na vyžádání vrátí výpisy subjektu |
| Oprava (čl. 16) | opravy se vydávají jako **nahrazující** uzávěrka (`SUPERSEDED` status, `supersedes_sequence`) — právní sekvence se nikdy tiše nepřepisuje |
| Výmaz (čl. 17) | **Neaplikovatelné** — 10letá AML/ČNB retence přebíjí pro záznam |
| Omezení (čl. 18) | řešeno upstream (zmrazení účtu v account-service) |
| Přenositelnost (čl. 20) | ad-hoc export (`/{accountId}/{currency}/export`) a strojové formáty camt.053/MT940 |
| Námitka (čl. 21) | N/A (žádné marketingové zpracování) |

### Stopa osobních údajů
- **Uloženo:** `account_id`, `party_id` (pseudonymní identifikátory), kotvy zůstatků, metadata sekvencí. **Žádný IBAN, žádné jméno majitele, žádné popisy řádkových položek nejsou uloženy jako řádky.**
- **Přechodné:** IBAN, jméno majitele a popisy položek se objevují jen v in-memory `StatementModel` během renderu a ve vyrenderovaném výstupu vráceném volajícímu.

### Toky dat ven
- → **Kafka** `openbank.statement.event` (`account.statement.period.closed.v1`): `accountId`, `iban`, `pocketCurrency`, období, sekvence, počáteční/koncové zůstatky, počet položek, `closedAt`. Stejný správce dat, intra-OpenBank (konzumováno auditem/downstreamem).
- → **volající** (admin-ui/customer app přes Keycloak): bajty vyrenderovaného výpisu pro autentizovaný subjekt.
- ← **příchozí** z account-service `AccountCreated` (`accountId`, `partyId`, `currency`) do lokálního registru.
- **Upstream čtení** (transaction/balance/account/party) zůstává intra-OpenBank přes M2M tokeny.

Žádná data neopouštějí region EU/EHP.

## Mapování DORA (Reg. (EU) 2022/2554)

| Článek | Téma | Implementace |
|---|---|---|
| čl. 5/6 | řízení ICT rizik | hexagonální izolace, centralizované `openbank-libs` |
| čl. 9 | identifikace | `BuildInfo` (gitCommit, buildTime, version) v `/api/v1/info` |
| čl. 10 | detekce | metriky Micrometer/Prometheus, čítače kadence uzávěrek, `ServiceMonitor`/`PrometheusRule` alerting na selhání uzávěrek |
| čl. 11 | reakce a obnova | self-healing kadence uzávěrek, durabilní `statement_close_run`/`failure`, runbooky v [05-operations](./05-operations.md), odolný outbox (retry/circuit-breaker/DEAD) |
| čl. 16/17 | řízení incidentů a reporting | události `period.close_failed`; WARN outbox DEAD; události do audit pipeline |
| čl. 28 | riziko třetích stran | žádný SaaS třetí strany — vše self-hosted |

## Bezpečnostní kontroly

- ✅ AuthN: Keycloak OIDC, RS256 JWT (příchozí bearer + odchozí client-credentials M2M)
- ✅ AuthZ: Quarkus `@RolesAllowed` — čtení vs mutace rozděleno podle role
- ✅ Integrita: fail-closed rekonciliace; transakční outbox (období + událost commitnou atomicky)
- ✅ Determinismus: bajt po bajtu identický re-render (žádný únik hodin systému), hlídáno testy
- ✅ Validace vstupu: typované path/query parametry (UUID, ISO datum, délka měny)
- ✅ TLS: mTLS v clusteru; bezpečnostní hlavičky (CSP, HSTS, X-Frame-Options, nosniff) nastaveny globálně
- ✅ Auditovatelnost: každá čistá uzávěrka emituje doménovou událost; každá selhaná kapsa je zaznamenána a emitována
- ⚠️ eIDAS-zapečetěné / stylované PDF a stylovaná konsolidovaná obálka jsou dokumentované follow-upy (ADR-0035)
