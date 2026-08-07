# openbank-copilot-service

Customer-facing AI assistant (mobile copilot) for the KMP app (ADR-0064/0065). **ADR-0089** is the
source of truth; **money-path adjacent**: action tools *propose* state changes that other services
execute — this service never moves funds and never completes SCA, and it is deliberately NOT in
`rules.yaml: money_path_services` (#2352). Threat model:
[`docs/threat-models/openbank-copilot-service.md`](../docs/threat-models/openbank-copilot-service.md).

## The one principle

> **The model proposes; the bank disposes.** The assistant holds *less* privilege than the
> authenticated customer. Every figure shown comes from a tool result, never model generation;
> money never moves on the model's word.

## Non-negotiable invariants (see threat model §4)

- **Money never moves on the model's word** — action tools emit a *proposal* gated by HITL **and**
  SCA with dynamic linking bound to amount+payee (ADR-0021/0073). Closed action whitelist.
- **Untrusted content is data, never instructions** — no tool fires from model free-text; every
  `tools/call` passes OPA (deny-by-default, ADR-0034) + schema validation.
- **Least privilege** — on-behalf-of token, audience-scoped to the one customer; downstream
  re-enforces ownership.
- **No `@PermitAll`** — the chat surface is `@Authenticated`, customer-scoped.
- **Sandbox = synthetic data only; production model = in-cluster (vLLM) or EU zero-retention** —
  enforced by feature flag + config, never a code path (ADR-0089 D6, FinOps ADR-0027).
- **Domain layer is framework-free** (ADR-0002).

## Status

Gated off by the `copilot-assistant` feature flag (ADR-0067, off by default) / `copilot.enabled`.

**Phase 1 (done):** governed reasoning loop (`CopilotChatService`) behind `CopilotChatResource`
(`@Authenticated`, customer `sub` from JWT, `runBlocking` on a worker thread — not `suspend`, so the
blocking OPA/REST clients are allowed); `ModelGateway` seam + `MockModelProvider`; `PromptInjectionGuard`
(D3); AI-attributed audit (ADR-0031 D5); deny-by-default `CopilotPolicyGate`; READ tools via the
customer edge (`BalanceTool`, `TransactionTool` — run AS the customer, ownership enforced at the edge);
RAG `search_help` over the bundled help corpus (D4 grounding, with citations).

**Phase 2 (done):** money-path ACTION tools — `propose_payment`, `propose_card_freeze`,
`propose_dispute` (`ActionProposalTool`). Each produces a structured `ActionProposal` on the reply —
**propose-only, never executes** (the interface has no execute path). Execution boundary (ADR-0089 D2):
the app routes the proposal into the **existing edge payment / card / dispute + SCA flow**; this service
never touches money or SCA.

**D4 router + narrator rollout (done):** `CopilotPolicyGate` now wires `OpaToolGate` as a second
layer (advisory by default, `copilot.opa.enforce=false`; flip via `COPILOT_OPA_ENFORCE=true` in gitops
once `copilot-opa-bundle.yaml` is deployed). OPA rego tool names synced to actual Kotlin `name` values.
`ScheduledPaymentsTool` capability corrected to `account.scheduled-payments.read`.

## Conversation history is personal data — and is now erasable (#3870)

Durable chat transcripts live in Postgres (`conversation_history`, `PostgresConversationStore`).
Free-text chat is where the least predictable personal data lands, so the retention path matters:

- **`expires_at` is a read-side filter, not deletion.** `load` filters on `expires_at > now()`, so an
  expired conversation stops being *served* while the row stays on disk and in every base backup.
  `ConversationRetentionScheduler` is what actually removes it (daily 03:30 UTC,
  `copilot.retention.conversation.enabled`).
- **`PartyErasureConsumer`** consumes `PARTY_ERASED` and hard-deletes the party's rows (GDPR Art. 17,
  ADR-0117), matching the shape the other seven consumers of that topic use.
- **The erasure ops on `ConversationStore` are `suspend`, deliberately.** Both callers already run on
  a Vert.x context, where the blocking `VertxContextSupport.subscribeAndAwait` bridge that `load` and
  `append` use would throw. For the same reason the sweep is a `suspend @Scheduled` method — a plain
  one runs on a bare `executor-thread` and dies with `HR000068` on the first reactive call.
- **The header comment in `V1__conversation_history.sql` is now stale** — it says there is no sweep
  and no `PARTY_ERASED` consumer "yet". It is deliberately NOT corrected: Flyway checksums the whole
  file including comments, so editing an applied migration fails the service at boot. Believe this
  file, not that one.
- **Known gap:** the event carries `partyId`, but history is keyed on the OIDC `sub`. The customers
  realm also defines a separate `party_id` claim, so where those differ the erasure matches nothing.
  Tracked separately — do not read the consumer as complete coverage.

## Build

```
./gradlew :openbank-copilot-service:build
```

HTTP port **8131**. Two version axes (ADR-0048): `version.txt` (release) and
`openapi.yaml:info.version` (API contract) — independent.
