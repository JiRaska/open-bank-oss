# Data

## Postoj k perzistenci — tato služba nevlastní žádná bankovní data

`openbank-agent-service` je **bezstavová vrstva uvažování a směrování**. Má:

- ❌ **Žádné JPA / Hibernate entity** — obě vlastní tabulky čte a zapisuje čistým JDBC.
- ✅ **Flyway migrace** — `agent_proposal` je HITL schvalovací fronta (ADR-0031 D4); `agent_audit_outbox` je odolné předání AI provenance.
- ✅ **Auditní outbox** — před retry doručením do Kafky ukládá producer event id a předaný auditní envelope.

Per-service governance manifest ([`governance.yaml`](../../../../openbank-agent-service/governance.yaml)) deklaruje:

| Pole | Hodnota |
|---|---|
| `dataDomain` | `platform` |
| `primaryDatastore` | `PostgreSQL` |
| `databaseName` | `openbank_agent` |
| `dataLineageRole` | `internal` |
| `dataClassification` | `internal` |
| `retentionPolicy` | `1 year` |
| `evidenceExported` | `false` |

> **Rozsah úložiště:** `PostgreSQL` / `openbank_agent` pokrývá HITL frontu i odolné předání AI provenance (`agent_proposal`, `agent_audit_outbox`) ve schématu `public` služby. Rate-limit čítače v `CharterRateLimiter` (`ConcurrentHashMap`, vynulované při restartu podu) zůstávají **in-memory** a nejsou distribuované. **V kódu není žádné zapojení Redisu**; distribuovaný charter/run stav zůstává follow-upem.

## Přechodný / in-process stav

| Stav | Kde | Životnost | Poznámky |
|---|---|---|---|
| Čítač runs-per-day | in-memory mapa `CharterRateLimiter`, klíč `agentId:YYYY-MM-DD` | do restartu podu / půlnoci UTC | nedistribuované; multi-replica vynucení je follow-up |
| Registr modelů | in-memory mapa `ModelGateway` | životnost procesu | sestaven v `@PostConstruct` z konfigurace `model-gateway.models` |
| Historie konverzace | pouze request-scoped | jedno volání `/agent/chat` | historii vlastní klient (admin UI); služba neukládá tahy |

## Data, která *protékají* (nikdy se neukládají)

Služba čte bankovní data z downstream služeb v čase požadavku a podává (oříznuté) výsledky modelu. Nic z toho **neukládá**. Schema lineage v `governance.yaml`:

- **Vlastněná schémata:** `agent_schema` (rezervováno, viz výše).
- **Závislá schémata (čtení):** `accounts_schema`, `transactions_schema` (a dle capability i read povrchy balance, ledger, product-catalog, aml, sanctions, fx, clearing, interest, dispute, sepa-instant).

## Audit trail (jediný trvalý výstup)

Místo doménové tabulky služba emituje **AI-atribuované audit eventy** přes `openbank-libs` `AuditEventPublisher`, perzistované službou `audit-service`:

| Emitor | Operace | actorType | Klíčový payload |
|---|---|---|---|
| `ModelGateway` | `agent.model.complete` | `AI_AGENT` | `model_id`, `model_provider`, `model_version`, `sensitivity`, **`prompt_hash` (SHA-256, ne syrový prompt)**, `stop_reason`, `input_tokens`, `output_tokens` |
| `AgentPolicyGate` | `agent.mcp.tool_call` | `AI_AGENT` | `tool`, `capability`, `policy_decision` (ALLOW/DENY), `reason`, `resourceId` |

Retenci a tamper-evident řetězec vlastní `audit-service`, ne tato služba.

## PII postoj

- Charter (`ui-assistant`) je **`pii: masked`** — syrové PII nikdy není v rozsahu asistenta; downstream čtení vrací maskované PII.
- Model gateway ukládá **SHA-256 `prompt_hash`**, nikdy syrový text promptu, takže provenience promptu je auditovatelná bez perzistence (potenciálně PII) obsahu.
- Audit `resourceId` může nést `accountId` / `transactionId` / `iban` převzaté z argumentů nástroje pro dohledatelnost; jde o stejnou třídu identifikátorů, kterou vlastnící služby již auditují, držené uvnitř hranice správce OpenBank.
- Tato služba neukládá žádné zákaznické PII v klidu, protože v klidu neukládá nic.

## Retence

`retentionPolicy: 1 year` v `governance.yaml` se vztahuje na jakýkoli budoucí agentem vlastněný stav (charter/run záznamy). Audit eventy se řídí statutární retencí `audit-service`, ne touto hodnotou.
