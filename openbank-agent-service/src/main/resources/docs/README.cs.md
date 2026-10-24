# openbank-agent-service — Dokumentace

> **Co to je:** **AI agentní vrstva** platformy — MCP server (Model Context Protocol), který vystavuje doménové operace OpenBank jako nástroje volatelné AI **pouze pro čtení**, plus serverový **asistent admin-UI** (řízená smyčka model↔nástroj). **Co to NENÍ:** **není** to služba na peněžní cestě (money-path), **nedrží žádná vlastní bankovní data** a **nikdy nemění stav** — každý nástroj, na který dosáhne, je read-only a s maskovaným PII (ADR-0031).

Tuto dokumentaci publikuje služba přímo na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při vykreslení stránky Service Docs.

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sedí v doméně |
| [02 — Architektura](./02-architecture.md) | Inženýrství, tech leadi | C4 pohled, hexagonální vrstvy, model gateway, policy gate |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | MCP JSON-RPC kontrakt, `/agent/chat`, chybový model |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Co ukládá (téměř nic), audit/prompt-hash, PII postoj |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, FinOps tier, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, AI Act, mapování PSD2/AML read-surface |
| [08 — Workload identity](./08-workload-identity.md) | Inženýři, SRE | Pilot ADR-0177 fáze 1: projektovaný SA token, Keycloak token exchange, secret fallback |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3 (RESTEasy Reactive) / JDK 21+ / Kotlin coroutines — PostgreSQL přes **čisté JDBC + Flyway**, **bez JPA**
- **Port:** 8109 (app HTTP), 8085 (management — health, metriky, docs)
- **Perzistence:** PostgreSQL ukládá HITL frontu `agent_proposal` (ADR-0031 D4), odolný `agent_audit_outbox` a důkazní `agent_merge_record` fáze 3; stav rate-limiteru je pouze **v paměti**
- **Outbox:** `agent_audit_outbox` potvrdí AI-atribuovaný audit event před doručením do Kafky; runtime aktivace zůstává řízená do atestace
- **Idempotence:** ingest merge záznamů je idempotentní podle `prNumber` (`ON CONFLICT DO NOTHING` — znovu doručený webhook vrátí uložený záznam s `inserted=false`, bez druhého auditního řádku); volání asistenta/nástrojů jsou read-only a neměnící stav
- **Auth:** Keycloak OIDC (resource server, realm `openbank`); odchozí MCP volání nesou `openbank-services` client-credentials Bearer (ADR-0031 / ADR-0034)
- **Governance:** každé volání nástroje prochází OPA-backed **AgentPolicyGate** (deny-by-default), ohraničeno charterem `ui-assistant` v `agents.yaml`; vynucení je defaultně `advisory`, přepíná se na `block` (ADR-0031 D9)

## Consumer kontrakty nástrojů pro čtení účtů

`AgentAccountReadPactConsumerTest` vykonává produkční dispatch `RestDownstreamReadAdapter` pro `get_account` a `get_account_by_iban` přes anonymního klienta účtů napojeného na Pact mock server. Tři interakce pokrývají účet podle ID 200, účet podle IBAN 200 a neznámý IBAN 404; poslední musí předat klientskou výjimku místo vymyšlených dat účtu. Asserty zachovávají identifikační pole odpovědi, nikoli každé pole poskytovatele.

Testy nevolají anotace produkčního REST klienta ani OIDC filtry, MCP HTTP routing, model, charter/HITL kontroly, uložení auditu ani živou JWT/OPA autorizaci. Používají existující syntetické provider states účtu a neznámého IBAN. Samostatný provider replay a standardní CI zůstávají nutné; lokální úspěch mocku není důkazem dodání za běhu. Testovací Pact závislost používá existující katalog verzí.

`AgentTransactionReadPactConsumerTest` navíc pokrývá `get_transaction`: existující transakce podle ID s odpovědí 200 projde skutečným read adaptérem, zatímco neznámá transakce s 404 předá klientskou výjimku. Používá stejnou hranici Pact testu s ručně napsaným klientem a sdílí existující provider states vložené transakce a prázdného stavu s Party portability kontraktem. Tyto dvě interakce nedokládají autorizaci volajícího ani izolaci vlastnictví.


## Consumer kontrakty nástrojů pro čtení FX a AML

`AgentFxReadPactConsumerTest` navíc pokrývá `fx_get_rate` (EUR/CZK 200 a neznámý pár USD/JPY 404) a `fx_list_rates` (200, samotné pole kurzů). `AgentAmlReadPactConsumerTest` pokrývá `aml_get_case` (vložený případ 200 a neznámý případ 404) a `aml_list_cases` (200 s předáním query partyId/limit/offset). Všech šest interakcí volá skutečný `RestDownstreamReadAdapter` přes anonymní implementace klientů připojené k Pact mock serveru. URL a query vytvářejí ručně; neprovádějí produkční MicroProfile anotace REST klienta ani OIDC filtry. Volitelná FX query source není pokryta.

FX matchery kontrolují měny a desetinný tvar dat, nikoli správnost ceny nebo kladnost kurzů na straně poskytovatele; kontrakt seznamu nefixuje pořadí ani konkrétní počet řádků. AML provider twins vkládají idempotentní syntetický případ a výslovně registrují stav neznámého případu, aby folder filtr zachoval jeho interakci 404. Staff fixture TestSecurity/OidcSecurity nedokládají JWT service accountu agenta, vynucované OPA granty, izolaci vlastnictví/tenantů ani filtrování cizích party. Parita provider states, doložené počty skutečných replay bez přeskočení a standardní CI zůstávají nutné; nejde o výsledek živého brokeru, model/MCP požadavku nebo nasazené autorizace.

### Kontrakty čtení Catalog a Ledger

Agent Catalog Pact pokrývá seznam produktů, detail, poplatky a 404 neexistujícího produktu.
Tři provider stavy používají kanonicky nasazené testovací produkty v obou Catalog verifikátorech.
Ledger Pact zachovává tři čtecí interakce deníku/předvahy a přidává anonymní čtení deníku s 401
přes existující negativní auth stav a samostatný anonymní folder verifikátor.
Consumer fixtures volají skutečný read adapter přes ručně napsané HTTP klienty; nespouštějí
produkční MicroProfile anotace a nedokazují JWT, OPA, vlastnictví ani živou broker interoperabilitu.
Catalog/Ledger potřebují vlastní důkaz mapování skutečných klientů; výsledky FX/AML na ně nelze
přenést. Tyto doplňkové interakce zatím nebyly spuštěny.
