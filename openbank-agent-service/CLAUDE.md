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

- **`suspend` on a `@Scheduled` method buys a VERT.X context — which is the fix for reactive Panache
  and the BUG when your collaborators are blocking.** This service has no reactive datasource
  (jdbc-postgresql + narayana) and its sweep calls blocking REST clients, so on the event loop each
  one throws `BlockingNotAllowedException`. Measured on the sandbox 2026-08-21, every scheduled
  oversight sweep logged `BlockedThreadChecker: blocked for 19s`, `Tool call failed:
  sanctions_list_pending: BlockingNotAllowedException`, and then the line that matters —
  `agent policy BLOCK but PDP errored — falling back to ADVISORY`: the OPA query failed for reasons
  that have nothing to do with OPA, so **enforcement silently downgraded on every sweep** while the
  run still reported "oversight sweep done" and the liveness heartbeat still recorded success. Wrap
  the scheduled body in `withContext(Dispatchers.IO)`. Only the scheduled path needs it — HTTP and
  Kafka triggers already arrive on a worker thread. Note what this means for the fleet rule: "make
  it a suspend fun" is right for a reactive service and insufficient here; the question is which
  thread YOUR clients need, not which annotation is fashionable.
  **A unit test cannot see this** — calling the method directly runs it on a test thread where
  blocking is allowed, so it passes against the broken code. The only witnesses are a real
  scheduled run's logs or a `@TestProfile` that re-enables the scheduler with a shrunken cron.

- **A reasoning model spends `max_tokens` on REASONING before it emits any answer, so a low cap
  returns an EMPTY `content` field — not a short answer.** Measured on `openai/gpt-oss-120b`
  through the gateway: `max_tokens=40` gives `content=''` with `finish_reason=length` (the whole
  budget went to reasoning), `128` truncates mid-sentence, `512` finishes normally in 189 tokens.
  Both paid models in the picker are reasoning models, and the loop's `MAX_OUTPUT_TOKENS` is 512,
  so this works today — but lowering that cap to save tokens turns replies into blanks, which the
  dock renders as "(no reply)" and reads as a broken assistant. The same trap makes model
  EVALUATION lie: `zai-org/GLM-5.2` and `moonshotai/Kimi-K3` were first measured at 120 tokens and
  looked broken (empty content; Kimi's chain of thought where the answer should be, which is just a
  truncated reasoning-prefixed stream). At 512 both answer cleanly. Measure a reasoning model at
  the cap the caller actually sends, or you are measuring the cap.

- `AGENT_MODEL_API_KEY` (or the legacy `GROQ_API_KEY` fallback) must be set for the
  `llama-3.3-70b-versatile` model; omit for mock-echo. Deployed, that key is the LiteLLM
  *virtual* key and `AGENT_MODEL_ENDPOINT` points at the in-cluster gateway — the provider
  key never lands in this pod (ADR-0174/0175).
- `AGENT_POLICY_ENFORCEMENT=block` requires a running OPA sidecar — without it the gate
  degrades to advisory and logs a WARN (fail-open by design).
- `AGENT_OVERSIGHT_ENABLED=true` enables the scheduled compliance-officer sweep (every 30 min).
