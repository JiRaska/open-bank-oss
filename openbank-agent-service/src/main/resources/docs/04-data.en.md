# Data

## Persistence posture — this service owns no banking data

`openbank-agent-service` is a **stateless reasoning and routing layer**. It has:

- ❌ **No JPA / Hibernate entities** — the one table it does own is read and written with plain JDBC.
- ✅ **Flyway migrations** — `src/main/resources/db/migration/` versions the single `agent_proposal` table (ADR-0031 D4, the HITL approval queue). That table is the service's only persistence.
- ❌ **No domain outbox table** — the two trust boundaries publish audit events instead (see below).

The per-service governance manifest ([`governance.yaml`](../../../../openbank-agent-service/governance.yaml)) declares:

| Field | Value |
|---|---|
| `dataDomain` | `platform` |
| `primaryDatastore` | `PostgreSQL` |
| `databaseName` | `openbank_agent` |
| `dataLineageRole` | `internal` |
| `dataClassification` | `internal` |
| `retentionPolicy` | `1 year` |
| `evidenceExported` | `false` |

> **Scope of that datastore:** `PostgreSQL` / `openbank_agent` covers the HITL proposal queue only — the tables live in the `public` schema of the service's own database. Everything else the service keeps is **in-memory**: the rate-limit counters in `CharterRateLimiter` (`ConcurrentHashMap`, reset on pod restart) are not persisted and not distributed. There is **no Redis wiring in the code**; distributed charter/run state remains a follow-up.

## Transient / in-process state

| State | Where | Lifetime | Notes |
|---|---|---|---|
| Runs-per-day counter | `CharterRateLimiter` in-memory map, key `agentId:YYYY-MM-DD` | until pod restart / midnight UTC | not distributed; multi-replica enforcement is a follow-up |
| Model registry | `ModelGateway` in-memory map | process lifetime | built at `@PostConstruct` from `model-gateway.models` config |
| Conversation history | request-scoped only | single `/agent/chat` call | the client (admin UI) owns history; the service does not persist turns |

## Data that flows *through* (never stored)

The service reads banking data from downstream services at request time and feeds (capped) results to the model. It does **not** persist any of it. Schema lineage in `governance.yaml`:

- **Owned schemas:** `agent_schema` (reserved, see above).
- **Dependent schemas (read):** `accounts_schema`, `transactions_schema` (and, by capability, the read surfaces of balance, ledger, product-catalog, aml, sanctions, fx, clearing, interest, dispute, sepa-instant).

## Audit trail (the only durable output)

Instead of a domain table, the service emits **AI-attributed audit events** via `openbank-libs` `AuditEventPublisher`, persisted by `audit-service`:

| Emitter | Operation | actorType | Key payload |
|---|---|---|---|
| `ModelGateway` | `agent.model.complete` | `AI_AGENT` | `model_id`, `model_provider`, `model_version`, `sensitivity`, **`prompt_hash` (SHA-256, not raw prompt)**, `stop_reason`, `input_tokens`, `output_tokens` |
| `AgentPolicyGate` | `agent.mcp.tool_call` | `AI_AGENT` | `tool`, `capability`, `policy_decision` (ALLOW/DENY), `reason`, `resourceId` |

Retention and the tamper-evident chain are owned by `audit-service`, not here.

## PII posture

- The charter (`ui-assistant`) is **`pii: masked`** — raw PII is never in the assistant's scope; downstream reads return masked PII.
- The model gateway stores a **SHA-256 `prompt_hash`**, never the raw prompt text, so prompt provenance is auditable without persisting (possibly PII) content.
- The audit `resourceId` may carry an `accountId` / `transactionId` / `iban` taken from tool arguments for traceability; this is the same identifier class the owning services already audit, kept inside the OpenBank controller boundary.
- No customer PII is stored by this service at rest, because nothing is stored at rest.

## Retention

`retentionPolicy: 1 year` in `governance.yaml` applies to any future agent-owned state (charter/run records). Audit events follow the `audit-service` statutory retention, not this value.
