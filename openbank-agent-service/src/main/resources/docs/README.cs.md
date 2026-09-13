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

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3 (RESTEasy Reactive) / JDK 21+ / Kotlin coroutines — PostgreSQL přes **čisté JDBC + Flyway**, **bez JPA**
- **Port:** 8109 (app HTTP), 8085 (management — health, metriky, docs)
- **Perzistence:** PostgreSQL ukládá HITL frontu `agent_proposal` (ADR-0031 D4) a odolný `agent_audit_outbox`; stav rate-limiteru je pouze **v paměti**
- **Outbox:** `agent_audit_outbox` potvrdí AI-atribuovaný audit event před doručením do Kafky; runtime aktivace zůstává řízená do atestace
- **Idempotence:** N/A — všechny operace jsou read-only / neměnící stav
- **Auth:** Keycloak OIDC (resource server, realm `openbank`); odchozí MCP volání nesou `openbank-services` client-credentials Bearer (ADR-0031 / ADR-0034)
- **Governance:** každé volání nástroje prochází OPA-backed **AgentPolicyGate** (deny-by-default), ohraničeno charterem `ui-assistant` v `agents.yaml`; vynucení je defaultně `advisory`, přepíná se na `block` (ADR-0031 D9)
