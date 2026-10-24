# Data

## Postoj k perzistenci — tato služba nevlastní žádná bankovní data

`openbank-agent-service` je **bezstavová vrstva uvažování a směrování**. Má:

- ❌ **Žádné JPA / Hibernate entity** — vlastní tabulky čte a zapisuje čistým JDBC.
- ✅ **Flyway migrace** — `agent_proposal` je HITL schvalovací fronta (ADR-0031 D4); `agent_audit_outbox` je odolné předání AI provenance; `agent_merge_record` je důkaz zaznamenaného lidského merge fáze 3.
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

> **Rozsah úložiště:** `PostgreSQL` / `openbank_agent` pokrývá HITL frontu, odolné předání AI provenance (`agent_proposal`, `agent_audit_outbox`) i důkaz zaznamenaného lidského merge (`agent_merge_record`) ve schématu `public` služby. Rate-limit čítače v `CharterRateLimiter` (`ConcurrentHashMap`, vynulované při restartu podu) zůstávají **in-memory** a nejsou distribuované. **V kódu není žádné zapojení Redisu**; distribuovaný charter/run stav zůstává follow-upem.

## `agent_merge_record` — zaznamenané lidské merge (ADR-0031 fáze 3)

Jeden řádek na každý mergovaný PR autora agentem, ingestovaný mimo pásmo CI workflow `record-agent-merge` přes `POST /api/v1/agent-merge-records` ([03 — API](./03-api.md)). Žádná bankovní data, žádný obsah PR (tělo/diff) — i auditní payload nese pouze fakta o merge.

| Sloupec | Typ | Poznámky |
|---|---|---|
| `id` | UUID PK | |
| `agent_id` | varchar(128) | agent, jehož PR byl mergován |
| `pr_number` | bigint **UNIQUE** | pojistka proti znovudoručení — insert je `ON CONFLICT DO NOTHING`; constraint zároveň slouží jako lookup index pro `pr_number` |
| `pr_url` | text | |
| `head_branch` | varchar(256) | |
| `merged_by` | varchar(128) | člověk, který merge provedl — smysl existence tabulky |
| `merged_at` | timestamptz | čas merge (event time) |
| `title` | text | titulek PR |
| `proposal_id` | UUID, nullable | měkký odkaz na `agent_proposal`, pokud ho volající dokáže zjistit — bez FK |
| `recorded_at` | timestamptz | čas ingestu (processing time) |
| `source` | varchar(64) | např. `github-actions` |

Index `(agent_id, merged_at DESC)` obsluhuje historii merge jednoho agenta. Migrace `V6__agent_merge_record.sql`; rollback `DROP TABLE agent_merge_record;` (řádky případně nejdřív exportujte — poznámka k rollbacku žije v migraci).

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

## Audit trail (trvalé důkazy)

Služba kromě vlastních důkazních tabulek emituje **AI-atribuované audit eventy** přes `openbank-libs` `AuditEventPublisher`, perzistované službou `audit-service`:

| Emitor | Operace | actorType | Klíčový payload |
|---|---|---|---|
| `ModelGateway` | `agent.model.complete` | `AI_AGENT` | `model_id`, `model_provider`, `model_version`, `sensitivity`, **`prompt_hash` (SHA-256, ne syrový prompt)**, `stop_reason`, `input_tokens`, `output_tokens` |
| `AgentPolicyGate` | `agent.mcp.tool_call` | `AI_AGENT` | `tool`, `capability`, `policy_decision` (ALLOW/DENY), `reason`, `resourceId` |
| `MergeRecordService` | `agent.pr.merged` | `HUMAN` (actorId = `merged_by`) | `pr_number`, `head_branch`, `agent_id`, `merged_at`, `source` — publikováno **pouze když byl řádek skutečně vložen**, takže znovu doručený webhook neemituje duplicitní auditní záznam |

Retenci a tamper-evident řetězec vlastní `audit-service`, ne tato služba.

## PII postoj

- Charter (`ui-assistant`) je **`pii: masked`** — syrové PII nikdy není v rozsahu asistenta; downstream čtení vrací maskované PII.
- Model gateway ukládá **SHA-256 `prompt_hash`**, nikdy syrový text promptu, takže provenience promptu je auditovatelná bez perzistence (potenciálně PII) obsahu.
- Audit `resourceId` může nést `accountId` / `transactionId` / `iban` převzaté z argumentů nástroje pro dohledatelnost; jde o stejnou třídu identifikátorů, kterou vlastnící služby již auditují, držené uvnitř hranice správce OpenBank.
- Tato služba neukládá žádné zákaznické PII v klidu — vlastní tabulky drží důkazy o návrzích a merge (metadata PR, uživatelská jména na GitHubu), nikdy zákaznická data.

## Retence

`retentionPolicy: 1 year` v `governance.yaml` se vztahuje na jakýkoli budoucí agentem vlastněný stav (charter/run záznamy). Audit eventy se řídí statutární retencí `audit-service`, ne touto hodnotou.
