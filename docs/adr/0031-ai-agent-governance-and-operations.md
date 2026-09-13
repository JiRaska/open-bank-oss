---
date: 2026-05-30
decision-status: accepted
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, authz, governance, audit]
summary: "AI agents run as least-privilege workloads declared in agents.yaml, passing the same PR, CI and OPA gates as humans, with every MCP tools/call policy-gated and every action recorded as AI-attributed audit."
---

# 31. AI agent governance and operations: agents-as-code, policy-gated MCP, human-in-the-loop, AI-attributed audit

**Delivery note (updated 2026-08-03, issues #3669 #3676):**
- **D1 (agents-as-code)** — ✅ Shipped: `agents.yaml` charter registry; `CharterRegistry` + `CharterRateLimiter` in `agent-service`.
- **D2 (policy-gated MCP)** — ✅ Shipped: `OpaPolicyDecisionPoint` + `agents.rego` deny-by-default; `AGENT_POLICY_ENFORCEMENT=block` in gitops (PR #638). See ADR-0034 for sidecar details.
- **D4 (human-in-the-loop)** — ✅ Shipped end-to-end: `ProposalResource` (`GET /api/v1/proposals`, `POST /{id}/decision`) with segregation-of-duties; admin-UI `/approvals` page + BFF routes; federated money-path inbox per ADR-0227 D2 (PRs #600/#2297/#2792/#3022). Issue #3668 closed as already-implemented.
- **D5 (AI-attributed audit)** — 🟡 Partial. Per-operation capture matrix (verified 2026-08-03):
  - `agent.model.complete` (`ModelGateway`): captures `model_id`, `model_version`, `prompt_hash` — ✅
  - `agent.run` (`AgentRunAuditor`): captures `model_id`, `prompt_hash`, `tool_calls[]` — ✅
  - `agent.mcp.tool_call` (`AgentPolicyGate`): captures `policy_decision` (ALLOW/DENY) — ✅; `model_id` NOT YET captured — fix in flight on branch `feat/agent-charter-model-id` (issue #3667; `prompt_hash` and `tool_calls` are architecturally N/A at the gate — the gate runs before any LLM call)
  - Production KMS/cosign-keyed anchor signer (asymmetric) — 🟢 Built. `AwsKmsAnchorSigner`
    (ECC_NIST_P256, ECDSA_SHA_256) under Pod Identity holding only Sign/Verify/GetPublicKey;
    `AUDIT_ANCHOR_SIGNING_REQUIRED=true` in gitops, so a signer failure aborts capture rather
    than storing an unsigned row that would later read as a checkpoint.
  - Offline third-party verification — 🟢 Built (issue #5838).
    `.github/scripts/verify-audit-anchors.py` recomputes each anchor digest and checks its
    signature from **public material only**, importing no OpenBank code; both canonical forms are
    pinned to shared test vectors from either side (`OfflineVerifierConformanceTest` in Kotlin,
    `--self-test` in Python). See runbook 0014. `GET /api/v1/audit/anchors/verify` is explicitly
    NOT this control: it is the suspect component grading itself against the very database whose
    tampering the anchor exists to detect.
  - **D5 stays 🟡 Partial, and the public control score does not advance.** Two gaps are
    structural, not backlog: (a) the verifier can reject a forged anchor but cannot reject an
    ABSENT one — a period never anchored, or a range dropped before export, presents nothing to
    reject, so completeness rests on reading the capture cadence, not on the signature; and
    (b) `signedAt` is the producer's own claim, so a backdated history signed with a live key
    still verifies. Closing either needs an external RFC 3161 timestamping authority or a public
    transparency log — ⬜ Planned, neither exists in this platform today. Describing what ships
    here as full independent verification would overstate it.
- **D6 (technology stack)** — See D6 section; amended 2026-08-03 (issue #3676).
- **D8 (AGPL agent-runtime public repo)** — ⬜ Planned (issue JiRaska/open-bank#224).

> **Ratification (2026-06-10).** The load-bearing controls are in place and deployed, so the
> decision is ratified and the phasing (D9) becomes the execution plan:
> - **D1** — agents-as-code: canonical `openbank-libs/governance/agents.yaml` charters consumed by
>   both the OPA bundle and the runtime; `CharterRegistry` + `CharterRateLimiter` in service.
 > - **D2** — policy-gated MCP: `OpaPolicyDecisionPoint` + `agents.rego` (deny-by-default PEP) on
 >   every `tools/call`. `AGENT_POLICY_ENFORCEMENT=block` in gitops — enforcement is live, not advisory.
 >   (PR #638; see ADR-0034 D2.)
 > - **D4** — human-in-the-loop: proposal queue + admin-UI approvals shipped end-to-end (PRs
 >   #600/#2297/#2792/#3022; `ProposalResource`, segregation-of-duties, federated money-path inbox per
 >   ADR-0227 D2). Issue #3668 closed as already-implemented 2026-08-03.
 > - Read-only MCP oversight tools across 7 domains (#639); `agent-service` deployed at v1.6.0.
>
> Ratification does **not** widen blast radius: agents stay `advisory` + proposal-only until the D9
> gates flip. Acceptance unblocks the remaining tail — enforcement (D2 sidecar), the oversight
> trigger (D9 phase 2), AI-attributed tool-execution audit (D5), and the AGPL agent-runtime repo
> (D8, issue JiRaska/open-bank#224) — without re-litigating the decision each time.

## Context

OpenBank already has the load-bearing pieces for AI agents but no decision that ties them into a
governed system:

- An **MCP endpoint** exists (`openbank-agent-service`, JSON-RPC 2.0 at `/mcp`, `tools/list` +
  `tools/call` via `McpToolRegistry`) — but every tool is reachable by anyone who can call it; there is
  no per-agent authorization, no least-privilege tool catalog, no kill switch.
- The **audit envelope** (`openbank-libs/audit/AuditEvent`) already models `actorType`, `actorId`,
  `operation`, `result`, `traceId` and a sanitized payload, mapped to GDPR Art. 30 / DORA Art. 17 — but
  nothing emits an AI actor, captures the model id, the prompt hash, or the policy decision behind an
  action.
- **Governance is already code** (`rules.yaml`, ADR-0029): money-path services, coverage floors, vuln
  SLAs, the "change X ⇒ require Y" rules. **OPA/Rego is already an accepted policy engine** (ADR-0018,
  sidecar per service) and is reused as admission control (ADR-0030 D4). Yet no policy describes what an
  *agent* may do.
- **Skills** (`/ship-check`, `/bump`, `/open-pr`, `/release`) already encapsulate the recurring
  engineering workflow and mirror the CI gates — the natural action surface for a development agent.
- The release **evidence bundle** already reserves an `ai_attribution` field (ADR-0029 D6) that is today
  empty.

What is missing is the **governing decision**: who the agents are, what each may see and do, where a
human must decide, and how every agent action becomes tamper-evident audit. Two distinct agent
populations are in scope and must share one governed substrate rather than fork into two systems:

1. **Control / oversight agents** — business & risk roles that watch the *running bank* (AML/sanctions
   signals, ledger integrity, vuln-SLA breaches, SLOs) and produce **proposals**, never direct action on
   money. A human assigns work and approves outcomes through the admin UI.
2. **Development / domain agents** — one agent per bounded context that maintains *its own services* by
   following the existing skills, `rules.yaml` and ADRs: drafts ADRs, opens PRs. Merge stays human.

The new regulatory driver is the **EU AI Act**: AI used for creditworthiness assessment is high-risk
(Annex III), which mandates human oversight, record-keeping, transparency and logging. These are exactly
the human-in-the-loop and immutable-audit controls below — they are not optional ergonomics, they are the
compliance surface. DORA treats the agent runtime as an ICT component; GDPR/PSD2/CNB apply unchanged.

Status legend: 🟢 GREEN = built + tested; 🟡 YELLOW = scaffolded; ⬜ PLANNED = scoped here.

## Decision

We will run AI agents as **governed, least-privilege workloads** whose every action passes through the
**same governance gates as a human** (PR → CI gates → OPA policy → required approvals) and is recorded as
**AI-attributed audit**. The governing principle:

> **Agents propose; governance disposes. An agent never holds more privilege than a human — it holds
> less.** An agent is just another `actor` in the audit envelope (`actorType = AI_AGENT`).

### D1 — Agents as code: `agents.yaml` + per-agent charter

- A new machine-readable **`openbank-libs/governance/agents.yaml`** (sibling of `rules.yaml`, same
  authoritative-source convention) declares every agent's **charter** answering five questions:
  *plane* (control | development), *data scope* (what it may read, PII masked via existing `PiiMasking`),
  *tools* (which MCP tools it may call — allow/deny), *requires_human* (what must be approved), and
  *limits* (token/run budgets, kill switch).
- `agents.yaml` consumes `rules.yaml` (money_path_services, review approvals, vuln SLAs) as input so agent
  policy cannot drift from governance. ⬜ PLANNED.

### D2 — Policy-gated MCP: OPA in front of every `tools/call`

- The MCP endpoint becomes a **policy enforcement point**: before dispatching any `tools/call`, it queries
  OPA (`/v1/data/openbank/agents/allow`) with `{ agent, tool, resource, plane, attributes }`. Deny is the
  default; an allow requires a matching rule. Reuses the ADR-0018 OPA deployment and Rego bundle — **no
  new policy engine**.
- The MCP **tool catalog is tiered**: `read` (query ledger read-only, read catalog/logs — PII masked),
  `write-proposal` (draft ticket, open PR, draft ADR), and a hard-`deny` tier (move money, approve/merge
  own PR, read raw secrets). Forbidden tools are never registered for an agent identity. ⬜ PLANNED.

### D3 — Verifiable agent identity and least privilege

- Each agent runs as an in-cluster workload (consistent with ADR-0027) with a **SPIFFE/SPIRE workload
  identity**. Signing material is a **short-TTL SVID / ephemeral credential issued per run**, not a
  long-lived key sitting in the agent's reach — a compromised agent must not be able to mint validly
  signed malicious commits indefinitely (the signing root stays in Vault, ADR-0017; the agent never holds
  it). This gives non-repudiation — an action traces to a specific agent identity, not a shared account.
- Segregation of duties is enforced in policy: a development agent may *open* a PR but may not *merge* or
  *approve* it; an author identity ≠ an approver identity. 🟡 enforced today by GitHub branch protection
  + CODEOWNERS; codifying it in the agent policy is planned (D3b).
- 🟢 **Identity binding (D3a, live):** the `/mcp` surface already requires an authenticated operator
  (Keycloak bearer, `@RolesAllowed`); `AgentIdentityBinding` now binds WHICH agent identity that
  operator may assert via `X-Agent-Id` to their **verified roles** (deny-by-default), so a
  lower-privileged operator can no longer select a higher-privileged charter. A rejected assertion is
  audited (`agent.identity.rejected`) and discloses no tools.
- 🟢 **D3b (live, enforced as of 2026-06-29):** `pki-agent` OpenBao PKI engine issues short-TTL X.509
  certificates (CN=agent-id, 300 s, `no_store`). `McpEndpoint` verifies the chain against the pki-agent
  CA, validates `SHA256withECDSA` proof-of-possession over `method|path|sha256(body)|timestamp`, and
  cross-checks CN→charter role (`svid_cn_binding` audit). `AGENT_IDENTITY_SVID_ENFORCED=true` in gitops
  — no header fallback. E2E verified in sandbox: T1 no-SVID→0 tools, T2 CN=ui-assistant→12 tools,
  T3 CN=compliance-officer→0 tools + DENIED audit. The D3a binding remains defense-in-depth backstop.
  Remaining: author≠approver GitHub branch protection rule (Free plan constraint — not addressable in code).

### D4 — Human-in-the-loop, two channels

- **Development plane** reuses the existing HITL: GitHub PR + CODEOWNERS + branch protection. Agents work
  **only through skills** (`/ship-check`, `/bump`, `/open-pr`, `/release`) — the skills already mirror the
  CI gates, so an agent that uses them inherits governance for free. Money-path merge still needs **2
  human approvals + threat model** (ADR-0030 D2). New ADRs are drafted `Status: Proposed`; a human accepts.
- **Control plane** adds a lightweight **approval queue** surfaced in the admin UI governance view (the
  same place vuln SLAs and coverage already surface, ADR-0029 D3 / ADR-0030 D1): the agent writes a
  proposal, a human approves/rejects **with a recorded reason**, and only then may any downstream action
  run. A human can also **assign work** to an agent from this view. ✅ **Shipped end-to-end** (PRs
  #600/#2297/#2792/#3022; federated money-path inbox per ADR-0227 D2; `ProposalResource` with
  segregation-of-duties). Issue #3668 closed as already-implemented 2026-08-03.

### D5 — AI-attributed, tamper-evident audit

- The existing `AuditEvent` is emitted for every agent action with `actorType = AI_AGENT` and a payload
  carrying `model_id`, `model_version`, `prompt_hash`, `tool_calls[]`, `policy_decision` (ALLOW/DENY),
  and — for approved proposals — `human_approver` and `reason`. No new audit infrastructure; new fields in
  the sanitized payload only.
- Events flow over the existing `audit-events-out` Kafka channel to an append-only store; the chain is
  made **tamper-evident** by hash-chaining and cosign (reusing ADR-0029/0030 signing). This populates the
  hitherto-empty `ai_attribution` field of the release evidence bundle (ADR-0029 D6). 🟡 audit envelope
  exists; 🟢 per-event hash chain live (`audit_entries.record_hash`/`prev_hash`, V5) **and** periodic
  externally-signed anchors over the chain head (`audit_anchor`, V6) — `GET /api/v1/audit/anchors/verify`
  detects a wholesale rewrite that an internal-consistency walk alone would miss; the default in-cluster
  signer is HMAC-SHA256 with the key held outside the audit DB.

**Per-operation capture status (verified 2026-08-03):**

| Audit event | `model_id` | `model_version` | `prompt_hash` | `tool_calls[]` | `policy_decision` |
|---|---|---|---|---|---|
| `agent.model.complete` (`ModelGateway`) | ✅ | ✅ | ✅ | N/A | N/A |
| `agent.run` (`AgentRunAuditor`) | ✅ | N/A | ✅ | ✅ | N/A |
| `agent.mcp.tool_call` (`AgentPolicyGate`) | ⬜ (issue #3667, in flight on `feat/agent-charter-model-id`) | N/A (gate precedes LLM call) | N/A (gate precedes LLM call) | N/A (gate precedes LLM call) | ✅ |

`prompt_hash` and `tool_calls` are architecturally N/A at the policy gate — the gate runs before any
LLM call, so there is no prompt or tool invocation to hash. `model_id` threading to the gate is being
fixed (issue #3667).

⬜ Remaining: AI-attribution payload fields on `agent.mcp.tool_call` (issue #3667) and the production
KMS/cosign-keyed anchor signer (asymmetric, third-party verifiable).

### D6 — Open, model-agnostic technology stack

**Amendment 2026-08-03 (issue #3676):** The original D6 listed an aspirational stack. The items below
are corrected to reflect what is deployed, what is deferred, and what remains an open gap.

**Load-bearing and shipped (deny-by-default OPA + charters + MCP gating + kill switch + HITL):**
- OPA/Rego deny-by-default + `agents.yaml` charters: ✅ Shipped (D1/D2, ADR-0034)
- MCP gating in block mode (`AGENT_POLICY_ENFORCEMENT=block`): ✅ Shipped (D2)
- Kill switch (per-agent + global, `AGENT_KILL_SWITCH` in gitops): ✅ Shipped (D7)
- Human-in-the-loop approval queue + admin-UI `/approvals`: ✅ Shipped (D4)

**Deferred (not deployed; no regulatory requirement satisfied by these in the current sandbox):**
- **Reasoning loop: LangGraph** — DEFERRED. The current implementation uses hand-written Kotlin
  activities; no graph framework is deployed. May be revisited when multi-step long-running agent
  workflows justify the dependency.
- **Memory / RAG: pgvector** — DEFERRED. Not deployed. ADR-0183 tracks this; tracked issue #3599
  (LiteLLM choke-point work). Optional until agents need persistent grounding in rules/ADRs.
- **Guardrails: Llama Guard / NeMo Guardrails** — DEFERRED. The current mitigation is a targeted
  `PromptInjectionGuard` in `copilot-service`. Full Llama Guard / NeMo stack not deployed.
- **Durable orchestration: Temporal for agents** — DEFERRED for agent workflows. Temporal is deployed
  for payments (ADR-0101) but agents currently run as stateless activities, not durable workflows. This
  becomes a required maturity step once agents are long-running multi-step actors that wait on
  approval-queue decisions between steps.
- **Self-hosted vLLM tier** — DEFERRED. Not deployed. Required before any deployment processing real
  personal or regulated data (see ADR-0175 D3, issue #3599). Optional for the sandbox where all data
  is synthetic. All inference currently routes to hosted providers (Groq / DeepInfra).

**Open observability gaps:**
- **Langfuse / LLM observability** — Not deployed. The approval-without-edit (rubber-stamp) metric
  remains an OPEN observability gap and a known risk to the human-oversight control (see Consequences).
  Tracked as a roadmap item; not marked delivered.

**Deferred identity hardening:**
- **SPIFFE/SPIRE** — DEFERRED. OpenBao PKI-agent CA (300 s TTL SVIDs, `no_store`) is the accepted
  interim workload identity. SPIRE is a future hardening step once the sandbox grows multiple trust
  domains.

**Current deployed stack (load-bearing):** deny-by-default OPA, `agents.yaml` charters, MCP gating in
block mode, kill switch, HITL approval queue, OpenBao short-TTL SVIDs (D3b), OpenTelemetry traces (D7),
Groq/DeepInfra hosted LLMs via model-gateway (synthetic data only, see ADR-0175).

### D7 — Observability, budgets, kill switch

- Every agent run is an OpenTelemetry trace (ADR-0008; `CorrelationIdFilter` already provides the
  correlation id used as `traceId`). Per-agent **token/cost budgets** and **run quotas** from `agents.yaml`
  are enforced at the gateway, and a per-agent and global **kill switch** halts agents without a redeploy.
  ⬜ PLANNED.

### D8 — Licensing and IP strategy (deviates from Apache-2.0)

The agent component is the part of OpenBank we intend to **commercialize**, so it does **not** ship under
the repo-wide Apache-2.0 (ADR-0123, superseding ADR-0012). We will:

- License the agent component under **AGPL-3.0** — the network/SaaS copyleft is the moat: a competitor who
  runs it as a service must publish their modifications, which blocks silent strip-mining.
- Offer a parallel **commercial license** (open-core / dual-licensing). AGPL alone is still free software
  and does not by itself generate revenue; the revenue lever is selling a commercial exception + closed
  enterprise features on top.
- This dual-license requires that the project **own the copyright in all contributions**, so the agent
  component switches from the repo's **DCO to a CLA**. This is itself an amendment to **ADR-0012** and must
  be recorded there, scoped to this component only.
- The agent component lives in a **separate repository / module with its own LICENSE and CLA**, not mixed
  into the Apache-2.0 services. `rules.yaml`'s `license_denylist` (which lists AGPL-3.0 as incompatible with
  Apache-2.0 *in the same file*) gets an explicit, documented **carve-out** for this component so governance
  and reality agree. Combining AGPL agent code with Apache-2.0 `openbank-libs` is one-directional and acceptable
  (Apache-2.0 files stay Apache-2.0; the conveyed agent work is AGPL); the carve-out makes that intentional, not a leak.

**Seam (resolved 2026-06-01):** the Apache/AGPL boundary is the **`ModelProvider` port** in
`openbank-agent-service`. The Apache-2.0 monorepo keeps only the **governance plumbing** that belongs in a
banking codebase — the model gateway *port*, the OPA policy gate, AI-attributed audit, the `agents.yaml`
charters, and reference/mock provider adapters. The **commercialised agent runtime** — autonomous
multi-step orchestration and proprietary model adapters — is implemented behind that port in the
**separate AGPL-3.0 + CLA repository** and plugged in at deploy time. This keeps the demo
(provider-agnostic gateway + offline mock, no production runtime) cleanly Apache-2.0 inside the monorepo
without prejudging the runtime's license, and gives the `license_denylist` carve-out a precise edge to
sit on: nothing AGPL is conveyed from the monorepo; the AGPL work *consumes* the Apache-2.0 port, one-directional.

✅ — seam decided and demonstrated (PR #216); separate AGPL repo created: JiRaska/openbank-agent-runtime (AGPL-3.0 + commercial dual-license, CLA, CODEOWNERS scaffold); `license_boundary_exceptions` carve-out added to `rules.yaml`. (Considered and rejected for now: BSL 1.1 / FSL — source-available with direct
commercial control but not OSI-"open"; revisit if AGPL+dual-license friction proves too high.)

### D9 — Phasing (blast radius increases only as controls land)

1. **Policy skeleton** (D1+D2): `agents.yaml` + Rego, OPA gating `tools/call`, **deny-by-default + audit
   only** — no agent acts yet.
2. **Read-only oversight** (control plane): one or two agents (e.g. compliance, SRE-watch) that only read
   and write proposals to the queue. Fully HITL, near-zero blast radius.
3. **One development agent on one NON-money-path service**: opens PRs via skills; a human merges.
4. **Expand** to more domains and, with 2 approvals + threat model, to money-path services.
5. **Provenance + tamper-evidence** (D5) and EU AI Act technical documentation.

## Alternatives considered

- **Let agents call MCP tools directly without a policy layer.** Pros: simplest, the endpoint exists.
  Cons: no least privilege, no segregation of duties, no record of *why* an action was permitted — fails
  DORA/AI Act. Rejected — OPA gating (D2) is the enforcement point, mirroring ADR-0030's "produced but not
  enforced is theatre".
- **A bespoke agent permission model in code.** Cons: re-invents what OPA/Rego already does (ADR-0018) and
  the exact pattern the 2026-05-28 audit punished (`@PermitAll` shortcuts). Rejected — reuse OPA.
- **Single hosted model, no gateway.** Pros: fastest to build. Cons: vendor lock-in and no path for
  air-gapped / sovereign data — unacceptable for a bank's reference architecture. Rejected — hybrid via
  gateway (D6).
- **Give agents autonomy on money-path with post-hoc review.** Cons: violates the EU AI Act human-
  oversight requirement and the existing 2-approval money-path rule. Rejected — agents propose, humans
  dispose (the core principle).
- **Two separate stacks for oversight vs. development agents.** Cons: duplicates identity, policy, audit;
  doubles the attack surface. Rejected — one governed substrate, two planes.

## Consequences

**Positive**
- Every agent action is least-privilege, policy-checked, and recorded with model attribution and the human
  who approved it — the exact evidence chain DORA / EU AI Act / CNB expect.
- Reuses existing assets (MCP endpoint, `AuditEvent`, `rules.yaml`, OPA, skills, evidence bundle); little
  net-new infrastructure, mostly wiring and policy.
- The empty `ai_attribution` field becomes real data; releases gain provable AI provenance.
- Model-agnostic by construction — sovereign/air-gapped option without re-architecting.

**Negative**
- **Prompt injection is the primary residual risk and OPA does not stop it.** An agent that *reads* bank
  data (transactions, tickets, logs) can ingest adversarial text that hijacks its reasoning into misusing
  a *permitted* tool. OPA bounds *which* tools, not *whether a permitted tool is used maliciously*.
  Mitigation (D6): treat all read data as untrusted, instruction/data separation, guardrails + injection
  filter — defence-in-depth, not elimination.
- **LLM output is non-deterministic — `prompt_hash` does not buy reproducibility.** The same input need
  not yield the same action; a regulator expecting reproducible decisions will not get it. Pinned model
  versions, temperature 0 and full I/O logging narrow this but do not guarantee it. Stated plainly so the
  ADR does not overclaim.
- **The approval queue can degrade into rubber-stamping**, hollowing out the very human-oversight control
  the EU AI Act argument rests on. Must rate-limit proposals, aggregate, and track approval-without-edit
  rate as a red flag (D6/D7) — same bypass risk as ADR-0030 triage.
- **Signing-key custody.** Even with short-TTL SVIDs (D3), a compromised agent can sign actions within its
  credential window; blast radius is bounded by TTL + per-action issuance, not zero.
- OPA gating adds latency and a hard, fail-closed dependency on the policy bundle; a bad or unavailable
  bundle blocks all agents (also a DoS surface).
- The LLM gateway concentrates every (masked) prompt and is in every action path — an availability and
  data-exposure single point unless HA + sensitive-data routing hold (D6).
- Dual-licensing requires a CLA and copyright ownership (D8) — contributor friction and an ADR-0012
  amendment; a wrong carve-out risks licence leakage between AGPL and Apache-2.0 code.

**Neutral**
- Agents become first-class `actor`s in the same governance machinery as humans; no parallel rulebook.
- Reuses ADR-0018 OPA, ADR-0017 Vault, ADR-0027 in-cluster runtime, ADR-0008 OTel; adds Temporal /
  LangGraph / vLLM+LiteLLM / Langfuse / guardrails / pgvector (D6) — net-new but all open.
- **EU AI Act scope is per-agent, not blanket.** Devops/oversight agents that only produce proposals are
  likely *limited/minimal* risk, not Annex III high-risk; only an agent touching a creditworthiness/
  scoring decision about a person is high-risk. Over-claiming high-risk status manufactures needless
  compliance burden — classify each agent's charter individually.

## Compliance impact

- PCI DSS: Req. 7 (least privilege) and Req. 10 (audit trail) — agent tool gating + AI-attributed audit.
- DORA:    Art. 8–10 (ICT risk + change governance for the agent runtime), Art. 17 (incident
  reconstruction via traceId), Art. 28–30 (the model provider as ICT third party) — directly supported.
- GDPR:    Art. 30 (records of processing — agent actions in the audit log), Art. 25/32 (PII masking on
  agent data scope) — supported.
- PSD2:    SCA/consent paths remain human-gated; agents may propose but not act on money-path.
- CNB:     supports auditability and operational-resilience expectations for automated decisioning.
- EU AI Act: classified **per agent**, not blanket (see Consequences). Oversight/devops agents are
  proposal-only and likely limited/minimal risk. For any agent that touches a high-risk flow (Annex III,
  e.g. creditworthiness), Art. 14 (human oversight), Art. 12 (record-keeping/logging) and Art. 13
  (transparency) are implemented by the HITL channels (D4) and AI-attributed audit (D5), and such agents
  are proposal-only by policy.

## References

- ADR-0002 — Hexagonal architecture (agents act through ports/skills, not around them).
- ADR-0012 — MPL license + DCO (this ADR deviates: AGPL + CLA for the agent component, D8 — amend there).
- ADR-0008 — OpenTelemetry (agent-run tracing).
- ADR-0017 — Secrets via Vault (agent signing-key custody).
- ADR-0018 — OPA for fine-grained authz (reused as the agent policy engine, deny-by-default).
- ADR-0027 — Cloud-agnostic in-cluster substrate (where agent workloads + open-weight models run).
- ADR-0029 — Versioning, release and governance as code (`rules.yaml`, evidence bundle `ai_attribution`).
- ADR-0030 — Supply-chain security (admission control / OPA pattern reused; "enforce, don't just produce").
- `openbank-agent-service/.../mcp/McpEndpoint.kt` — the MCP endpoint this ADR turns into a policy enforcement point.
- `openbank-libs/audit/AuditEvent.kt` — the audit envelope extended with AI attribution.
- `openbank-libs/governance/rules.yaml` — governance input consumed by `agents.yaml`.
- `.claude/skills/{ship-check,bump,open-pr,release}` — the development-agent action surface.
- EU AI Act (Reg. 2024/1689) Annex III, Art. 12–14; NIST AI RMF — external references.
- Temporal (durable workflow orchestration), LangGraph, vLLM + LiteLLM, Langfuse, Llama Guard /
  NeMo Guardrails, SPIFFE/SPIRE, pgvector — open runtime components (D6).
- AGPL-3.0 + commercial dual-licensing, CLA vs DCO — licensing/IP strategy (D8).
