# Data

## Persistence posture — this service owns no banking data

`openbank-agent-service` is a **stateless reasoning and routing layer**. It has:

- ❌ **No JPA / Hibernate entities** — the owned tables are read and written with plain JDBC.
- ✅ **Flyway migrations** — `agent_proposal` is the HITL approval queue (ADR-0031 D4); `agent_audit_outbox` is the durable handoff for AI provenance; `agent_merge_record` is the phase-3 recorded-human-merge evidence.
- ✅ **Audit outbox** — it records the producer event id and supplied audit envelope before a retrying Kafka delivery attempt.

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

> **Scope of that datastore:** `PostgreSQL` / `openbank_agent` covers the HITL proposal queue and durable AI-provenance handoff (`agent_proposal`, `agent_audit_outbox`) plus the recorded-human-merge evidence (`agent_merge_record`) in the service's `public` schema. Rate-limit counters in `CharterRateLimiter` (`ConcurrentHashMap`, reset on pod restart) remain **in-memory** and undistributed. There is **no Redis wiring in the code**; distributed charter/run state remains a follow-up.

## `agent_merge_record` — recorded human merges (ADR-0031 phase 3)

One row per merged agent-authored PR, ingested out-of-band by the `record-agent-merge` CI workflow over `POST /api/v1/agent-merge-records` ([03 — API](./03-api.md)). No banking data, no PR body/diff content (the audit payload too carries merge facts only).

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `agent_id` | varchar(128) | the agent whose PR was merged |
| `pr_number` | bigint **UNIQUE** | redelivery-dedupe guard — insert is `ON CONFLICT DO NOTHING`; the constraint doubles as the `pr_number` lookup index |
| `pr_url` | text | |
| `head_branch` | varchar(256) | |
| `merged_by` | varchar(128) | the human who merged — the evidentiary point of the table |
| `merged_at` | timestamptz | merge event time |
| `title` | text | PR title |
| `proposal_id` | UUID, nullable | soft link to `agent_proposal` when the caller can establish it — no FK |
| `recorded_at` | timestamptz | ingest (processing) time |
| `source` | varchar(64) | e.g. `github-actions` |

Index `(agent_id, merged_at DESC)` serves the per-agent merge history. Migration `V6__agent_merge_record.sql`; rollback `DROP TABLE agent_merge_record;` (export the rows first if the evidence must be kept — the rollback note lives in the migration).

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

## Audit trail (durable evidence)

Alongside the owned evidence tables, the service emits **AI-attributed audit events** via `openbank-libs` `AuditEventPublisher`, persisted by `audit-service`:

| Emitter | Operation | actorType | Key payload |
|---|---|---|---|
| `ModelGateway` | `agent.model.complete` | `AI_AGENT` | `model_id`, `model_provider`, `model_version`, `sensitivity`, **`prompt_hash` (SHA-256, not raw prompt)**, `stop_reason`, `input_tokens`, `output_tokens` |
| `AgentPolicyGate` | `agent.mcp.tool_call` | `AI_AGENT` | `tool`, `capability`, `policy_decision` (ALLOW/DENY), `reason`, `resourceId` |
| `MergeRecordService` | `agent.pr.merged` | `HUMAN` (actorId = `merged_by`) | `pr_number`, `head_branch`, `agent_id`, `merged_at`, `source` — published **only when the row was actually inserted**, so a redelivered webhook emits no duplicate audit event |

Retention and the tamper-evident chain are owned by `audit-service`, not here.

## PII posture

- The charter (`ui-assistant`) is **`pii: masked`** — raw PII is never in the assistant's scope; downstream reads return masked PII.
- The model gateway stores a **SHA-256 `prompt_hash`**, never the raw prompt text, so prompt provenance is auditable without persisting (possibly PII) content.
- The audit `resourceId` may carry an `accountId` / `transactionId` / `iban` taken from tool arguments for traceability; this is the same identifier class the owning services already audit, kept inside the OpenBank controller boundary.
- No customer PII is stored by this service at rest — the owned tables hold proposal/merge evidence (PR metadata, GitHub user names), never customer data.

## Retention

`retentionPolicy: 1 year` in `governance.yaml` applies to any future agent-owned state (charter/run records). Audit events follow the `audit-service` statutory retention, not this value.
