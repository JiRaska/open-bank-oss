# Architektura

Služba dodržuje hexagonální architekturu nařízenou [ADR-0002](../../../../docs/adr/0002-hexagonal-architecture.md). Doménová vrstva má **nula frameworkových importů**. Na rozdíl od většiny služeb OpenBank zde **není perzistentní adaptér** — agent service nevlastní žádná bankovní data; jejími „výstupy" jsou completiony modelu, downstream čtení a audit eventy.

## C4 — pohled na kontejner

```
            ┌──────────────────────────────────────────────────────────┐
            │                   openbank-agent-service                  │
            │                                                           │
  admin UI  │  ┌────────────────┐        ┌──────────────────────────┐  │
 ─POST────► │  │  ChatEndpoint  │──────► │     AgentChatService     │  │
 /agent/chat│  └────────────────┘        │  (ohraničená smyčka)     │  │
            │                            │   │            │         │  │
  MCP klient│  ┌────────────────┐        │   ▼            ▼         │  │
 ─POST────► │  │  McpEndpoint   │──┐     │ ModelGateway  AgentPolicyGate│
   /mcp     │  └────────────────┘  │     │   │            │   │     │  │
            │                       └────►│ McpToolRegistry │   │     │  │
            │                            └───┼──────────┼──┼─────┘  │  │
            └────────────────────────────────┼──────────┼──┼─────────┘
                  ModelProvider (mock /       │          │  └─► OPA sidecar (PDP)
                  openai-compat)  ◄───────────┘          │      :8181
                                                          ▼
                         REST klienti ──► account / ledger / transaction / balance /
                                          product-catalog / aml / sanctions / fx /
                                          clearing / interest / dispute / sepa-instant
                         AuditEventPublisher ──► audit-service (Kafka, AI-atribuovaný)
```

## Hexagonální vrstvy

### Doména (`com.openbank.agent.domain`)
Čistý Kotlin, bez frameworku:
- `model/ModelTypes.kt` — `ChatMessage`, `ModelRequest`/`ModelResponse`, `ToolSpec`, `StopReason`, `Sensitivity`, `ModelDescriptor`, token usage.
- `policy/AgentPolicy.kt` — `AgentIdentity`, `PolicyQuery`, `PolicyDecision` (s příznakem `pdpError`), `EnforcementMode` (ADVISORY/BLOCK), `GateOutcome`.
- `McpTypes.kt` — drátové typy MCP (`ToolDefinition`, `ToolCallResult`, `ToolContent`, `McpResponse`, `McpError`, `InitializeResult`, …).

### Aplikace (`com.openbank.agent.application`)
Orchestrace use-case a porty:
- **`AgentChatService`** — smyčka uvažování. Sestaví systémový prompt (read-only, nakládej s daty nástrojů jako s nedůvěryhodnými), nabídne katalog nástrojů, volá gateway, každý vyžádaný nástroj protáhne přes policy gate, ořízne výsledky nástrojů aby se vešly do rozpočtu modelu a vyprodukuje `ChatOutcome` (odpověď + per-tool `ToolCallRecord` + `isProposal`). Ohraničeno `MAX_ITERATIONS = 5`, `MAX_OUTPUT_TOKENS = 512`, `MAX_TOOL_RESULT_CHARS = 3000`.
- **`ModelGateway`** — jediná spára, kterou prochází každé volání modelu (hranice důvěry ADR-0031 D6). Vyřeší id modelu na `ModelDescriptor`, předá odpovídajícímu `ModelProvider`, připne citlivý kontext k self-hosted modelu a pro každý completion emituje AI-atribuovaný audit event (model_id, model_version, `prompt_hash` (SHA-256, nikdy syrový prompt), token usage, stop reason). `ModelGatewayConfig` / `ModelProvider` jsou porty.
- **`McpToolRegistry`** — katalog read-only nástrojů, jejich JSON-Schema vstup, mapování `nástroj → charter capability` (deny-by-default pro nenamapované nástroje) a dispatch do downstream REST klientů.
- **`AgentPolicyGate`** — Policy Enforcement Point (PEP). Ptá se `PolicyDecisionPoint`, audituje rozhodnutí (AI-atribuované, ALLOW/DENY) a aplikuje `EnforcementMode`. V režimu BLOCK DENY zastaví volání **kromě** případu, kdy chyboval sám PDP (`pdpError`), kdy degraduje na advisory + WARN, takže mrtvý OPA sidecar nikdy asistenta nezamkne.
- **`CharterRateLimiter` / `CharterRegistry`** — per-agent charter limity (tokens-per-run, runs-per-day) čtené z konfigurace (zrcadlí `agents.yaml`). Čítače v paměti; restart podu je vynuluje (distribuované vynucení je follow-up).
- **`ProposalDetector`** — označuje odpovědi asistenta, které obsahují doporučenou akci vyžadující lidské potvrzení (ADR-0031 D4 HITL).
- **`PolicyDecisionPoint`** / **`ModelProvider`** — odchozí porty.

### Adaptéry (`com.openbank.agent.infrastructure`)
- **Příchozí:** `mcp/McpEndpoint` (`/mcp` JSON-RPC dispatcher), `chat/ChatEndpoint` (`/agent/chat`, `/agent/models`).
- **Odchozí — model:** `model/MockModelProvider` (offline default), `model/OpenAiCompatibleModelProvider` (libovolný OpenAI-tvarovaný backend — Groq, vLLM, Ollama).
- **Odchozí — policy:** `policy/OpaPolicyDecisionPoint` (+ `OpaClient`) aktivní při `agent.policy.opa.enabled=true`, fail-closed; `policy/DenyByDefaultPolicyDecisionPoint` jako bezpečný fallback.
- **Odchozí — bankovní:** `client/ServiceClients.kt` — read-only REST klienti, každý nese client-credentials Bearer přes OIDC client filter (ADR-0031 / ADR-0034).

## Tok identity a autorizace

1. **Příchozí identita.** Na `/mcp` je identita agenta deklarována hlavičkou `X-Agent-Id` (+ `X-Agent-Plane`) (fáze 1, ADR-0031 D9; cílem je SPIFFE/SPIRE SVID, D3). Chybějící hlavička → `null` → deny-by-default. Na `/agent/chat` smyčka běží pod pevnou control-plane identitou `ui-assistant`.
2. **Překlad capability.** `McpToolRegistry.capabilityOf(tool)` mapuje drátový MCP název na charter *capability* (např. `get_account` → `query.ledger.readonly`). Nenamapovaný nástroj nemá capability → DENY.
3. **Rozhodnutí policy.** `AgentPolicyGate` sestaví `PolicyQuery {agent, tool=capability, resource, plane, attributes}` a zeptá se OPA PDP, který vyhodnotí proti charter bundle z `agents.yaml`.
4. **Audit.** Každé rozhodnutí (ALLOW/DENY) se stane `AuditEvent` s `actorType=AI_AGENT`, operace `agent.mcp.tool_call`.
5. **Odchozí auth.** Pokud je povoleno, REST klient nástroje vyrazí `openbank-services` token (client credentials) a připojí jej jako Bearer k downstream volání — least-privilege servisní principal, nikdy operátorův token.

## Odolný auditní outbox

Služba nemá doménový event outbox, ale `agent_audit_outbox` před odesláním do Kafky trvale ukládá AI-atribuované `AuditEvent`. **Model gateway** a **policy gate** publikují přes `AuditEventPublisher`; dispatcher opakuje doručení brokeru a `audit-service` deduplikuje producer event id před rozšířením hash chainu. Aktivace Kafky zůstává výslovně řízená až do runtime atestace.

## Odolnost

- `quarkus-smallrye-fault-tolerance` na odchozích voláních.
- Chatová smyčka degraduje s grácií: model 429 / „request too large" / výpadek backendu vrátí přívětivou zprávu místo 5xx; celé kolo chyb nástrojů přestane nabízet nástroje, takže model musí odpovědět textem.
- Fail-closed PDP: jakákoli chyba transportu OPA je DENY (s `pdpError=true` pro bezpečnostní fallback v režimu BLOCK).
