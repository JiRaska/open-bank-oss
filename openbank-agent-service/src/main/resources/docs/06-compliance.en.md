# Compliance

`openbank-agent-service` is **not** a money-path service (`rules.yaml: money_path_services` does not list it) and holds **no banking data of its own**. Its compliance posture is therefore about **governing AI access to the bank**, not about safeguarding account state. The governing decision is [ADR-0031 (AI agent governance & operations)](../../../../docs/adr/0031-ai-agent-governance-and-operations.md); authorisation is [ADR-0018](../../../../docs/adr/0018-opa-for-fine-grained-authz.md) / [ADR-0034](../../../../docs/adr/0034-unified-opa-authz-mcp-and-rest.md).

> **Governing principle (ADR-0031): agents propose, governance disposes.** An agent never holds more privilege than a human — it holds less. Default is DENY; an allow requires a matching charter rule.

## Regulatory framework

| Regulation | Relation to this service | Implementation |
|---|---|---|
| **EU AI Act** | An AI system operating inside a bank — needs human oversight, logging, transparency | deny-by-default policy gate; AI-attributed audit of every model + tool call; HITL via proposal detection (D4); per-agent kill-switch (D7, via `agents.yaml`/OPA) |
| **DORA** (Reg. (EU) 2022/2554) | ICT/operational resilience of an internal service | health probes, fail-closed PDP, graceful model-outage degradation, audit evidence, SLO/runbooks ([05 — Operations](./05-operations.md)) |
| **GDPR** | The assistant can read customer data through tools | charter is `pii: masked`; gateway stores only a SHA-256 `prompt_hash`, never raw prompts; no PII persisted at rest (no datastore) |
| **PSD2 / AML** | The assistant can *read* AML/sanctions/payments surfaces | read-only capabilities only (`query.compliance.readonly`, `query.payments.readonly`); never a write/decision tool; **not** the SAR/screening system of record |
| **NIS2** | Network & info security | OIDC inbound, least-privilege `openbank-services` Bearer outbound, mTLS in-cluster (Istio), security response headers |
| **ADR-0030 SSDLC** | Supply-chain / secure SDLC | signed commits, dependency governance, no third-party agent SDK lock-in (open runtime, ADR-0031 D6) |

## The AI-governance controls (ADR-0031)

| Decision | Control in this service |
|---|---|
| **D2 — policy gate** | `AgentPolicyGate` (PEP) → OPA PDP. Every `tools/call` is authorized against the `agents.yaml` charter; unmapped tool → deny-by-default. |
| **D2 — charter limits** | `CharterRateLimiter`: `tokens_per_run` (100k) and `runs_per_day` (500) for `ui-assistant`. |
| **D4 — human-in-the-loop** | `ProposalDetector` flags replies that recommend an action; the admin UI renders them as proposals for approval. The assistant itself never acts. |
| **D5 — AI attribution** | `ModelGateway` and `AgentPolicyGate` emit `AuditEvent {actorType=AI_AGENT}` for every completion / decision (model_id, model_version, prompt_hash, tokens, policy_decision, reason). |
| **D6 — model gateway** | the single trust boundary every model call passes through; provider-agnostic, sensitive context pinned to a self-hosted model. |
| **D9 — phased enforcement** | `EnforcementMode` ADVISORY (audit-only, default) → BLOCK; fail-closed PDP with a `pdpError` safety fallback so a dead OPA never locks the assistant out. |

## The `ui-assistant` charter (agents.yaml)

| Aspect | Value |
|---|---|
| Plane | `control` |
| Read scope | account, transaction, balance, catalog, ledger, aml, sanctions, fx, clearing, interest, dispute, sepa-instant |
| PII | masked |
| Allowed capabilities | `query.ledger.readonly`, `read.catalog`, `query.compliance.readonly`, `query.payments.readonly`, `query.interest.readonly`, `query.disputes.readonly` |
| **Denied** | `money.*`, `gh.pr.*`, `*.write`, `secrets.read.raw` |
| Human required | every proposal (HITL) |
| Limits | tokens_per_run 100000, runs_per_day 500 |

The charter is the single source of truth; the code reads the limits from config and maps tools to capabilities, but **what an agent may do is defined once in `agents.yaml`**, never duplicated as prose.

## GDPR mapping

### Lawful basis (Art. 6)
- **Legitimate interest** (Art. 6(1)(f)) — back-office operational support; the assistant only reads data operators are already authorised to see, PII-masked.

### Data subject rights
The agent service is **not** a system of record and stores no customer data, so subject-rights requests are served by the **owning** services (account, transaction, …). The assistant itself has nothing to export, rectify, or erase.

### Data flows
- **In:** read-only tool calls to downstream services, carrying a least-privilege `openbank-services` Bearer (not the operator's token).
- **Out:** the model completion (to the configured backend — `mock` offline by default; an `openai-compat` hosted backend or a self-hosted model for the sensitive tier) and AI-attributed audit events to `audit-service`.
- **Sensitive routing:** the gateway pins sensitive (PII / money-path) context to a `self-hosted` model when one is registered (ADR-0031 D6) — keeping sensitive content off third-party hosted APIs.
- No customer PII is persisted at rest by this service.

## DORA mapping (Reg. (EU) 2022/2554)

| Article | Topic | Implementation |
|---|---|---|
| Art. 5/6 | ICT risk management framework | dependency = openbank-libs (centralized); no third-party SaaS agent stack (in-cluster runtime, ADR-0031 D6) |
| Art. 9 | Identification | `BuildInfo` (gitCommit, buildTime, version) on `/api/v1/info`; AI attribution on every action |
| Art. 10 | Detection | metrics + alerting; WARN logs on policy BLOCK denials and PDP-unreachable fallback |
| Art. 11 | Response & recovery | graceful model-outage degradation; fail-closed PDP; runbooks in [05 — Operations](./05-operations.md); stateless ⇒ cheap recovery |
| Art. 16/17 | Incident management & reporting | AI-attributed audit events → audit-service evidence pipeline |
| Art. 28 | Third-party risk | provider-agnostic model gateway — no single-vendor lock-in; a hosted model can be replaced by a self-hosted one with a config change |

## Security controls

- ✅ AuthN: Keycloak OIDC (RS256 JWT) inbound; `openbank-services` client-credentials Bearer outbound.
- ✅ AuthZ: deny-by-default OPA policy gate per tool call; unmapped tool fails closed.
- ✅ Least privilege: read-only tool surface; charter denies all writes/money/secrets.
- ✅ Prompt-injection posture: system prompt instructs the model to treat tool data as untrusted and never follow instructions inside it (ADR-0031 D6 guardrails — llama-guard / injection filter are the runtime target).
- ✅ Audit: every model call + every policy decision is AI-attributed.
- ✅ No raw prompt at rest: SHA-256 `prompt_hash` only.
- ✅ Response headers: CSP, X-Frame-Options DENY, nosniff, HSTS, Referrer/Permissions-Policy.
- ✅ Rate limiting: `openbank.rate-limit` (max-concurrent) + per-agent charter limits.
- ⚠️ Distributed charter enforcement: rate-limit counters are in-memory (reset on restart); multi-replica distributed enforcement is a tracked follow-up.
- ⚠️ Enforcement default is `advisory` — flip to `block` once an OPA sidecar is present in every target (ADR-0031 D9).
