# openbank-agent-service — agent notes

MCP server exposing OpenBank banking tools to AI agents (**ADR-0031**), port **8109**.
Hexagonal per ADR-0002. Money-path adjacent — requires threat model + 2 approvals for
any change that touches the HITL approval queue or kill-switch paths.

## What it does

Implements the AI agent governance control plane (ADR-0031 D1–D9):
- **HITL approval queue** (D4): agent proposes an action → human approves/rejects before
  execution. Table: `agent_proposal`. The agent is otherwise stateless — this is its only
  persistence besides the kill switch.
- **Break-glass kill switch** (D7): `agent_kill_switch` table overrides `agents.yaml` config
  in real time — a row with `halted=true` suspends the scope; deleting the row resumes.
- **Model gateway** (D6): provider-agnostic routing, mock echo by default; real backends
  (Groq, self-hosted Ollama) configured in `application.yaml`.
- **Charter rate limiter** (D2): per-agent `tokens-per-run` / `runs-per-day` enforced in-process.
- **OPA policy gate** (D9): advisory by default, set `AGENT_POLICY_ENFORCEMENT=block` to enforce.
- **Guardrail** (D6 follow-up): prompt-injection detection, `block` mode by default.

## Database — applied migrations + rollback notes

Migrations run via Flyway JDBC (reactive Panache cannot drive the synchronous MCP tool path —
see `AgentProposalEntity` docs). **Never edit an applied migration file** (Flyway checksum
will mismatch → crashloop). Rollback notes for applied migrations are kept here, not in the
SQL file.

| Version | File | Creates | Rollback |
|---------|------|---------|---------|
| V1 | `V1__agent_proposals.sql` | `agent_proposal` table + `idx_agent_proposal_state` index | `DROP INDEX idx_agent_proposal_state; DROP TABLE agent_proposal;` |
| V2 | `V2__agent_kill_switch.sql` | `agent_kill_switch` table | `DROP TABLE agent_kill_switch;` |

## Layout

- `domain/model/` — `AgentProposal`, `KillSwitchEntry`, `AgentCharter`
- `application/` — `AgentProposalService`, `KillSwitchService`, `CharterRateLimiter`
- `infrastructure/persistence/` — `AgentProposalRepository` (JDBC), `KillSwitchRepository`
- `infrastructure/rest/` — `AgentProposalResource`, `KillSwitchResource`, `McpServerResource`
- `infrastructure/gateway/` — `ModelGateway` + provider adapters (mock, openai-compat)
- `infrastructure/policy/` — `AgentPolicyGate` (OPA), `PromptInjectionGuardrail`

## Build / test

```
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :openbank-agent-service:test --offline
```

## Config gotchas

- `AGENT_MODEL_API_KEY` (or the legacy `GROQ_API_KEY` fallback) must be set for the
  `llama-3.3-70b-versatile` model; omit for mock-echo. Deployed, that key is the LiteLLM
  *virtual* key and `AGENT_MODEL_ENDPOINT` points at the in-cluster gateway — the provider
  key never lands in this pod (ADR-0174/0175).
- `AGENT_POLICY_ENFORCEMENT=block` requires a running OPA sidecar — without it the gate
  degrades to advisory and logs a WARN (fail-open by design).
- `AGENT_OVERSIGHT_ENABLED=true` enables the scheduled compliance-officer sweep (every 30 min).
