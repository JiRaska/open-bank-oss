# Architecture

The service follows the hexagonal architecture mandated by [ADR-0002](../../../../docs/adr/0002-hexagonal-architecture.md). The domain layer has **zero framework imports**. Unlike most OpenBank services there is **no persistence adapter** — the agent service owns no banking data; its "outputs" are model completions, downstream read calls, and audit events.

## C4 — container view

```
            ┌──────────────────────────────────────────────────────────┐
            │                   openbank-agent-service                  │
            │                                                           │
  admin UI  │  ┌────────────────┐        ┌──────────────────────────┐  │
 ─POST────► │  │  ChatEndpoint  │──────► │     AgentChatService     │  │
 /agent/chat│  └────────────────┘        │  (bounded reasoning loop)│  │
            │                            │   │            │         │  │
  MCP client│  ┌────────────────┐        │   ▼            ▼         │  │
 ─POST────► │  │  McpEndpoint   │──┐     │ ModelGateway  AgentPolicyGate│
   /mcp     │  └────────────────┘  │     │   │            │   │     │  │
            │                       └────►│ McpToolRegistry │   │     │  │
            │                            └───┼──────────┼──┼─────┘  │  │
            └────────────────────────────────┼──────────┼──┼─────────┘
                  ModelProvider (mock /       │          │  └─► OPA sidecar (PDP)
                  openai-compat)  ◄───────────┘          │      :8181
                                                          ▼
                         REST clients ──► account / ledger / transaction / balance /
                                          product-catalog / aml / sanctions / fx /
                                          clearing / interest / dispute / sepa-instant
                         AuditEventPublisher ──► audit-service (Kafka, AI-attributed)
```

## Hexagonal layers

### Domain (`com.openbank.agent.domain`)
Pure Kotlin, no framework:
- `model/ModelTypes.kt` — `ChatMessage`, `ModelRequest`/`ModelResponse`, `ToolSpec`, `StopReason`, `Sensitivity`, `ModelDescriptor`, token usage.
- `policy/AgentPolicy.kt` — `AgentIdentity`, `PolicyQuery`, `PolicyDecision` (with `pdpError` flag), `EnforcementMode` (ADVISORY/BLOCK), `GateOutcome`.
- `McpTypes.kt` — MCP wire types (`ToolDefinition`, `ToolCallResult`, `ToolContent`, `McpResponse`, `McpError`, `InitializeResult`, …).

### Application (`com.openbank.agent.application`)
The use-case orchestration and ports:
- **`AgentChatService`** — the reasoning loop. Builds the system prompt (read-only, treat tool data as untrusted), offers the tool catalogue, calls the gateway, runs each requested tool through the policy gate, caps tool results to fit the model budget, and produces a `ChatOutcome` (reply + per-tool `ToolCallRecord` + `isProposal`). Bounded by `MAX_ITERATIONS = 5`, `MAX_OUTPUT_TOKENS = 512`, `MAX_TOOL_RESULT_CHARS = 3000`.
- **`ModelGateway`** — the single seam every model call passes through (ADR-0031 D6 trust boundary). Resolves a model id to a `ModelDescriptor`, dispatches to the matching `ModelProvider`, pins sensitive context to a self-hosted model, and emits an AI-attributed audit event (model_id, model_version, `prompt_hash` (SHA-256, never raw prompt), token usage, stop reason) for every completion. `ModelGatewayConfig` / `ModelProvider` are the ports.
- **`McpToolRegistry`** — the catalogue of read-only tools, their JSON-Schema input, the `tool → charter capability` mapping (deny-by-default for unmapped tools), and the dispatch into the downstream REST clients.
- **`AgentPolicyGate`** — the Policy Enforcement Point (PEP). Asks the `PolicyDecisionPoint`, audits the decision (AI-attributed, ALLOW/DENY), and applies the `EnforcementMode`. In BLOCK mode a DENY stops the call **unless** the PDP itself errored (`pdpError`), in which case it degrades to advisory + WARN so a dead OPA sidecar never locks the assistant out.
- **`CharterRateLimiter` / `CharterRegistry`** — per-agent charter limits (tokens-per-run, runs-per-day) read from config (mirrors `agents.yaml`). In-memory counters; a pod restart resets them (distributed enforcement is a follow-up).
- **`ProposalDetector`** — flags assistant replies that contain a recommended action requiring human confirmation (ADR-0031 D4 HITL).
- **`PolicyDecisionPoint`** / **`ModelProvider`** — outbound ports.

### Adapters (`com.openbank.agent.infrastructure`)
- **Inbound:** `mcp/McpEndpoint` (`/mcp` JSON-RPC dispatcher), `chat/ChatEndpoint` (`/agent/chat`, `/agent/models`).
- **Outbound — model:** `model/MockModelProvider` (offline default), `model/OpenAiCompatibleModelProvider` (any OpenAI-shaped backend — Groq, vLLM, Ollama).
- **Outbound — policy:** `policy/OpaPolicyDecisionPoint` (+ `OpaClient`) active when `agent.policy.opa.enabled=true`, fail-closed; `policy/DenyByDefaultPolicyDecisionPoint` as the safe fallback.
- **Outbound — banking:** `client/ServiceClients.kt` — the read-only REST clients, each carrying a client-credentials Bearer via the OIDC client filter (ADR-0031 / ADR-0034).

## Identity & authorization flow

1. **Inbound identity.** On `/mcp` the agent identity is asserted by the `X-Agent-Id` (+ `X-Agent-Plane`) header (phase 1, ADR-0031 D9; SPIFFE/SPIRE SVID is the target, D3). Absent header → `null` → deny-by-default. On `/agent/chat` the loop runs under the fixed `ui-assistant` control-plane identity.
2. **Capability translation.** `McpToolRegistry.capabilityOf(tool)` maps the MCP wire name to a charter *capability* (e.g. `get_account` → `query.ledger.readonly`). An unmapped tool has no capability → DENY.
3. **Policy decision.** `AgentPolicyGate` builds a `PolicyQuery {agent, tool=capability, resource, plane, attributes}` and asks the OPA PDP, which evaluates against the `agents.yaml` charter bundle.
4. **Audit.** Every decision (ALLOW/DENY) becomes an `AuditEvent` with `actorType=AI_AGENT`, operation `agent.mcp.tool_call`.
5. **Outbound auth.** If allowed, the tool's REST client mints an `openbank-services` token (client credentials) and attaches it as a Bearer to the downstream call — least-privilege service principal, never the operator's token.

## Durable audit outbox

The service has no domain-event outbox, but its `agent_audit_outbox` durably records AI-attributed `AuditEvent`s before Kafka delivery. The **model gateway** and **policy gate** publish through `AuditEventPublisher`; a dispatcher retries broker delivery and `audit-service` deduplicates the producer event id before extending its hash chain. Kafka activation remains explicitly controlled until the runtime rollout is attested.

## Resilience

- `quarkus-smallrye-fault-tolerance` on the outbound calls.
- The chat loop degrades gracefully: a model 429 / "request too large" / backend outage returns a friendly message rather than a 5xx; a whole tool round of errors stops offering tools so the model must answer in text.
- Fail-closed PDP: any OPA transport error is a DENY (with `pdpError=true` for the BLOCK-mode safety fallback).
