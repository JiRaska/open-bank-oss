# Overview

## What the service does

`openbank-agent-service` is the **AI agent layer** of the OpenBank platform. It implements [ADR-0031 (AI agent governance & operations)](../../../../docs/adr/0031-ai-agent-governance-and-operations.md) and exposes two surfaces:

- **MCP server** (`POST /mcp`) — a Model Context Protocol 2.0 JSON-RPC server that publishes a catalogue of **read-only** OpenBank domain operations as AI-callable *tools* (`tools/list`, `tools/call`). An external MCP client (e.g. an IDE agent) can enumerate and invoke them.
- **Admin-UI assistant** (`POST /agent/chat`) — a **server-side** reasoning loop. The model call never happens in the browser: the admin UI posts the conversation, the service resolves a model through a provider-agnostic **model gateway**, runs a bounded model↔tool loop, gates every tool call through the **OPA policy gate**, and returns the reply plus a transparent record of each tool call and whether policy allowed it.

The assistant runs under a single fixed control-plane identity, **`ui-assistant`** (charter in `openbank-libs/governance/agents.yaml`): **read-only, proposal-only, never a money-path tool**.

## What the service **does NOT** do

- ❌ Does not hold any banking data — it has no domain aggregate of its own.
- ❌ Does not mutate state — there is **no write tool**; the charter denies `money.*`, `*.write`, `gh.pr.*`, `secrets.read.raw`.
- ❌ Does not execute payments, post to the ledger, or move money — it is **not** in `rules.yaml: money_path_services`.
- ❌ Does not run the LLM in the browser — the model is called server-side, behind the gateway trust boundary.
- ❌ Is **not** exposed to external TPPs via the API Gateway — it is an internal service.
- ❌ Does not act autonomously on state changes — any state-changing follow-up an operator asks for is surfaced as a **proposal** for human review (ADR-0031 D4), executed by other services, not here.

## Position in the domain

```
   ┌────────────┐  POST /agent/chat   ┌───────────────────────────────┐
   │  admin UI  │ ──────────────────► │       agent-service           │
   └────────────┘                     │  ┌─────────────────────────┐  │
   ┌────────────┐  POST /mcp          │  │ AgentChatService loop   │  │
   │ MCP client │ ──────────────────► │  │  model gateway ─┐       │  │
   └────────────┘                     │  │  policy gate ───┤       │  │
                                       │  └────────────────┼───────┘  │
                                       └───────────────────┼──────────┘
                  model completion ◄── (mock / openai-compat)
                                       │ read-only MCP tools (Bearer: openbank-services)
                                       ▼
        account · transaction · balance · ledger · product-catalog
        aml · sanctions · fx · clearing · interest · dispute · sepa-instant
                                       │ every tool call + every model call
                                       ▼
                              audit-service (AI-attributed AuditEvent)
```

## Key use cases

| Use case | API | Governance |
|---|---|---|
| List the tools an agent may call | `POST /mcp` `{method: tools/list}` | — |
| Invoke a read-only tool | `POST /mcp` `{method: tools/call}` | OPA gate + audit per call |
| Ask the admin assistant a question | `POST /agent/chat` | charter limits + per-tool OPA gate + AI-attributed audit |
| List registered models | `GET /agent/models` | — |
| Protocol handshake / liveness | `POST /mcp` `{method: initialize \| ping}` | — |

## Callers

- **admin-ui** — operators / compliance, via the server-side `/agent/chat` endpoint (Keycloak token).
- **External MCP clients** — an IDE or agent runtime speaking MCP 2.0 over `POST /mcp`, asserting an `X-Agent-Id` identity (deny-by-default when absent).

## Dependencies

- **Downstream banking services** (read-only REST clients): account (8100), ledger (8101), transaction (8102), balance (8103), product-catalog (8104), consent (8106), psd2 (8107), aml (8117), sanctions (8118), fx (8119), clearing (8124), interest (8125), dispute (8135), sepa-instant (8127).
- **OPA sidecar** (8181) — the policy decision point for the agent policy gate.
- **Keycloak** — OIDC resource server (inbound) and `openbank-services` client-credentials (outbound service-to-service).
- **Model backends** — provider-agnostic via the gateway: an offline `mock` provider (default) and an `openai-compat` adapter (e.g. Groq, or a self-hosted vLLM/Ollama for the sensitive tier). No backend is required for build/test.
- **openbank-libs** — `AuditEventPublisher` (AI-attributed audit), `BuildInfo`, `DocsResource`, security plumbing.

## Business value

- **Single governed seam for AI** — every model call and every tool call passes through one trust boundary (gateway + policy gate), so AI access to the bank is least-privilege, auditable, and revocable without redeploy.
- **Provider independence** — adding or swapping a model is a config entry, not a code change; no single-vendor lock-in (ADR-0031 D6).
- **Operator productivity** — the admin assistant answers cross-domain questions from live, read-only data across accounts, payments, compliance, ledger and more — without ever holding write access.
- **Transparency by construction** — the assistant returns a per-tool allow/deny record; every decision is an AI-attributed audit event for the regulator.
