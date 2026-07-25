<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-mcp-service

STRIDE/DFD threat model for the first-party Model Context Protocol server, per ADR-0030 D2.
**Not** currently listed in `rules.yaml: money_path_services` (see §7); money-path-adjacent by
design intent, inert by implementation today.

- **Status:** Draft (first pass, written against phase 1 as shipped — PR #2104 service, #2134/#2136
  deploy, #2142 OPA sidecar)
- **Last reviewed:** 2026-07-25
- **Owner:** mcp-service CODEOWNERS
- **Related ADRs:** ADR-0181 (this service), ADR-0031 (AI-agent governance / charters), ADR-0034
  (unified OPA authz — the shared PDP this service calls), ADR-0126 (consent lifecycle — the phase-2
  binding), ADR-0089 (customer-copilot propose-only regime), ADR-0086 (audit hash chain),
  ADR-0002 (hexagonal), ADR-0136 (AGPL carve-out for agent services)

## 0. Phase posture — read this before anything below

**Almost nothing behind the tools is wired.** Writing this model as if the intended system were the
shipped one would be worse than having no model, so every row below is tagged:

- **[LIVE]** — implemented and reachable today, with the file that implements it.
- **[INTENT]** — described in an ADR, a KDoc, `agents.yaml`, or a rego comment, but **no code path
  enforces it**. These are not mitigations. They are documentation.

What is actually running (`openbank-infra/gitops/components/mcp/mcp-service.yaml`, image
`sandbox-c04790d2`):

| Fact | State |
|---|---|
| JSON-RPC surface `initialize` / `ping` / `tools/list` / `tools/call` on `:8150/mcp` | **LIVE** (`infrastructure/mcp/McpEndpoint.kt`) |
| Five curated tools, deny-by-default capability map | **LIVE** (`application/McpToolRegistry.kt`) |
| Every `tools/call` gated on the shared ADR-0034 PDP as `AI_AGENT`, fail-closed | **LIVE** (`McpEndpoint.handleToolCall`, OPA sidecar in the pod) |
| Tool bodies — accounts, balance, transactions, consents, proposal | **STUB** (`infrastructure/read/StubReadPorts.kt` returns a fixed `"phase":"1-stub"` note; **no downstream call is made, no customer data is read, no proposal row is written**) |
| Caller authentication on `/mcp` | **NONE.** `quarkus.oidc.tenant-enabled: false` (`src/main/resources/application.yaml`); no `@RolesAllowed`, no `@Authorize`, no mTLS, no API key |
| Caller identity | **NONE.** `McpEndpoint.resolveContext()` returns a hardcoded `ConsentContext("agent:mcp-anonymous", "none", emptyList())` — the `X-Agent-Id` / `X-Consent-Id` headers its own KDoc describes are **not read** |
| Consent scoping | **[INTENT]** — `ReadPorts.kt` KDoc assigns the granted-account intersection to "the port implementation"; the only implementation is the stub, which enforces nothing |
| Audit trail of tool calls / policy decisions | **LIVE** (`application/McpCallAuditor.kt`) — one canonical `AuditEvent` per `tools/call`, `actorType = AI_AGENT`, carrying tool, capability, charter, `policy_decision` and outcome. Emitted on ALL four outcomes (allow, policy deny, unmapped tool, PDP outage). Delivery is the shared `LoggingAuditEventPublisher` (log pipeline), as everywhere else in the fleet — no Kafka producer in the module |
| Rate limiting / budgets / idempotency | **NONE** in this service |
| NetworkPolicy | **LIVE.** `mcp-service-ingress-allow-list` (ADR-0081, derived) admits same-namespace + admin-ui:8150/8181 + observability/security-scanner:8085 and drops every other cross-namespace source. Ingress only — egress is unrestricted |
| Internet exposure | **NO** ingress, no `HTTPRoute`, `Service` is `ClusterIP` — in-cluster only |

**The single most important consequence:** the service is unauthenticated *and* has no caller
identity, so the PDP's `AI_AGENT` decision is made about a constant. It is safe today only because
the tools are stubs and nothing routes to it from outside the cluster. Both of those are phase-2
changes. **Neither the stub boundary nor the absent ingress is enforced by a gate** — a phase-2 PR
that binds the real REST clients behind the same ports (explicitly the plan in `ReadPorts.kt`)
turns every threat in §4 from theoretical to live *without touching the endpoint, the policy, or
this document*, which is exactly the change class that slips through review.

## 1. Scope & assets

The MCP server is the bank's **agent-facing protocol edge**: a stateless JSON-RPC surface that
offers an AI agent a curated, consent-scoped tool set instead of a general API. It is a *router*, not
an authority — it owns no database (`governance.yaml: primaryDatastore: none`, `stateless: true`)
and reimplements no domain read.

Assets, in priority order:

1. **Customer funds.** `propose_payment` is the money-adjacent tool. It must never become a debit;
   the intended regime (ADR-0089/ADR-0181) is propose-only, disposed by a human + SCA dynamic
   linking downstream.
2. **Customer data confidentiality.** Balances, transactions, IBANs, consent records — the entire
   value of the read tools, and the entire prize for an attacker.
3. **Scope integrity.** The reachable set must be the intersection of the agent's charter
   (`agents.yaml: mcp-anonymous`) and the presented PSD2 consent's `grantedAccounts`.
4. **Agent attribution.** Which agent, acting for which customer, under which consent, called which
   tool — the non-repudiation record a bank needs for an AI-initiated action (ADR-0031 D5).
5. **Policy-plane integrity.** The OPA bundle and the charter that define what any agent may do.

## 2. Data flow (DFD)

```
[AI agent / MCP client]
        │  JSON-RPC POST /mcp        ← NO authentication, NO caller identity  (phase 1)
        ▼
┌─────────────────────── pod: mcp-service (ns platform) ───────────────────────┐
│  McpEndpoint                                                                 │
│    ├─ tools/list ─────────────────────────────► full schema, NO gate         │
│    └─ tools/call                                                             │
│         ├─ registry.capabilities[tool]  ── absent ⇒ REFUSE (deny-by-default) │
│         ├─ PDP.allow(Principal("agent:mcp-anonymous", AI_AGENT), capability) │
│         │        └──localhost:8181──► [OPA sidecar]  rest.rego → agents.rego │
│         │                              (mcp-opa-bundle ConfigMap)            │
│         │            exception / timeout ⇒ DENY (fail closed)                │
│         └─ registry.call(...) ──► StubReadPorts  ── returns a canned note     │
│                                    ▲ phase 2: @RegisterRestClient to         │
│                                      account / balance / transaction /       │
│                                      consent, + a maker-checker PROPOSED row │
└──────────────────────────────────────────────────────────────────────────────┘
```

**Trust boundaries.** (a) MCP client → `/mcp`: **no authentication boundary** — the only fences are
the ADR-0081 ingress allow-list (which admits the whole `platform` namespace, since same-namespace
traffic is unconditional) and the fact that the `Service` is cluster-internal with nothing routed to
it.
(b) service → OPA: localhost, same pod. (c) service → downstream banking services: **does not exist
yet**; phase 2 creates it and it will carry a service bearer, at which point the confused-deputy
threat (T-E2) becomes real.

**External entities.** MCP clients (LLM agents), the OPA sidecar, and — phase 2 —
account/balance/transaction/consent services and the maker-checker queue.

## 3. Authn / Authz — what the PDP can and cannot see

Every `tools/call` builds one `AuthzQuery` (`McpEndpoint.handleToolCall`):

```
principal  = Principal(id = "agent:mcp-anonymous", type = "AI_AGENT")   ← CONSTANT
action     = registry.capabilities[toolName]                            ← one of 5 strings
resource   = null
attributes = {"tool": …, "consentId": …}
```

`rest.rego`'s `agent-charter-allows` rule bridges this to `agents.rego` by rebuilding the input as
`{agent, tool, resource}` — **it drops `attributes` entirely**
(`openbank-libs/governance/policies/rest.rego`). Therefore:

**The PDP CAN see:** the principal type (`AI_AGENT`), the constant agent id, and which of the five
capabilities is being invoked. That is enough for the hard-denied tool tier, the charter's `deny`
globs (`money.*`, `gh.pr.*`, `*.write`, `secrets.read.raw`) and its five-entry `allow` list — all
**[LIVE]** and all genuinely enforced.

**The PDP CANNOT see:** *who is calling*. There is one identity for every caller. It also cannot see
the consent id (dropped by the bridge), the account id argument, the proposed amount, the payee, or
any request count. So no policy written in this plane can express "this agent may read only these
accounts", "this consent has expired", or "this is the 500th call this minute" — those are not
policy gaps to be closed by better rego, they are **input** gaps.

**Relation to the `openbank-psd2-service` finding (PR #2166 / issue #2169).** The same limitation
applies here, and it applies *worse*. On psd2 the PDP also cannot distinguish one external caller
from another (a TPP authenticates by eIDAS QWAC mTLS, carries no OIDC bearer, and reaches OPA as
`ANONYMOUS`) — but there the coarse grant is defensible because `EidasMtlsFilter` has *already*
authenticated the TPP, checked its eIDAS role against tpp-registry, and 401'd an unknown or
unauthorized one before the interceptor runs. Issue #2169's complaint is only that no guard *ties*
the granted actions to that filter's path prefixes.

**openbank-mcp-service has no equivalent upstream authenticator at all.** There is no filter, no
mTLS, no bearer, no header check — `resolveContext()` does not even read the header its own KDoc
names. So the mitigating precondition that makes the psd2 anonymous grant acceptable is *absent*
here, and the shape of the risk is the same one #2169 describes: a policy grant resting on an
invariant that nothing enforces. Phase 2 must land the OAuth 2.1 → PSD2-consent binding
(ADR-0126) **before** the read ports are bound to real services, not after.

## 4. STRIDE

### Spoofing

- **T-S1 — every caller is the same principal. [LIVE gap]** `resolveContext()` hardcodes
  `agent:mcp-anonymous`. Any process that can reach `:8150` is that agent, with that charter. There
  is no agent identity to forge because there is no agent identity. *Mitigating today:* the tools are
  stubs, so the granted capabilities reach no data; the pod has no ingress. *Not mitigated by:*
  the OPA gate, which authorizes the constant faithfully.
  **[INTENT]** ADR-0181 phase 2 / ADR-0126: per-agent OAuth 2.1 identity, one charter per real
  caller, `mcp-anonymous` retired. Compare `openbank-ap2-service`, which at least reads `X-Agent-Id`
  and falls back to an anonymous id; this service does not read it.
- **T-S2 — unauthenticated surface. [LIVE gap]** `quarkus.oidc.tenant-enabled: false` and no
  `@RolesAllowed`. Every in-cluster workload — including any compromised pod anywhere in the
  cluster that the ADR-0081 allow-list admits — every pod in `platform`, plus `admin-ui` — can call
  `tools/call` directly, with no credential. **See T-E1 for what the NetworkPolicy does and does not
  fence.**

### Tampering

- **T-T1 — policy-bundle tampering.** The rego + charter + `rules.yaml` are baked into the
  `mcp-opa-bundle` ConfigMap by `gen-mcp-opa-bundle.sh`, mounted read-only, and a checksum
  annotation rolls the pod on change. **[LIVE]** Changes go through the OPA-bundle CI gate; the
  bundle is derived, never hand-edited.
- **T-T2 — tool-argument tampering. [LIVE gap, latent]** Arguments are validated only for
  presence and JSON type (`McpToolRegistry.reqText`). Nothing validates that `accountId` is within
  the consent, that `amount` is a well-formed non-negative decimal, that `toIban` is a valid IBAN,
  or that `currency` is ISO 4217 — the declared `inputSchema` is advertised to the client but is
  **not** validated server-side. Harmless while the port is a stub that echoes the request; it is
  the first thing to fix when `propose_payment` writes a real row.
- **T-T3 — runtime tampering.** `readOnlyRootFilesystem`, `runAsNonRoot: true`, `runAsUser: 100`,
  all capabilities dropped, `seccompProfile: RuntimeDefault`, image cosign-signed and
  Kyverno-verified at admission (ADR-0030 D4). **[LIVE]**

### Repudiation

- **T-R1 — tool-call attribution. [LIVE — closed by issue #2207]** `agents.rego`'s `decision`
  object exists precisely so "a DENY is auditable, not silent", and its own comment says the MCP
  endpoint records it into `AuditEvent.payload.policy_decision` (ADR-0031 D5). `McpCallAuditor` now
  does exactly that: every `tools/call` emits one canonical `AuditEvent` with
  `actorType = AI_AGENT`, `operation = mcp.tool.call`, `resourceId = <tool>`, and a payload of
  tool / capability / charter / consent id / `policy_decision` / reason. All four outcomes are on
  the record — an unmapped tool and a PDP outage included, because a trail that shows only
  successes hides the interesting half. `tools/list`, `initialize` and `ping` are deliberately not
  audited (static catalogue, no customer data).
  **Residual:** (a) attribution is only as good as the identity, and the actor is the constant
  `agent:mcp-anonymous` until T-S1 lands — the trail answers "what was done" but not yet "by whom";
  (b) delivery is the shared `LoggingAuditEventPublisher`, so entries reach the ADR-0086 hash chain
  via the log pipeline rather than a durable Kafka producer — the fleet-wide posture, not specific
  to this service; (c) the payload deliberately carries **no** tool arguments and **no** tool
  output, only argument key names, so the trail cannot be used to reconstruct the customer data an
  agent saw (GDPR data minimisation). That last one is a trade: it bounds the blast radius of the
  audit store at the cost of not recording *which* account was queried.

### Information disclosure

- **T-I1 — tool-schema disclosure.** `tools/list` is served with no PDP call and no auth, returning
  all five tool names, descriptions and schemas. Low severity (names, not data) and consistent with
  agent-service's accepted residual (its T-I1), but note that here it is *fully* unauthenticated
  rather than role-gated. **[LIVE gap, accepted]**
- **T-I2 — cross-consent data harvesting. [INTENT only]** The claim "a tool never sees an account
  the consent did not grant" lives in `ReadPorts.kt`'s KDoc and delegates enforcement to "the port
  implementation". The shipped implementation is `StubReadPorts`, which reads nothing and enforces
  nothing, and `grantedAccounts` is always an empty list. So there is currently **no consent
  enforcement code anywhere in this service**, and the PDP cannot supply it (§3 — the consent id is
  dropped before the policy sees it). This is the threat that phase 2 must answer first: binding a
  real `AccountReadPort` without an implemented intersection check turns `get_balance(accountId)`
  into an unauthenticated read of any account id an attacker can guess or enumerate.
- **T-I3 — tool-result exfiltration (AI-specific).** A tool result is JSON serialized straight into
  a `ToolContent.text` block and handed to the model. There is **no** data-marker wrapping, **no**
  PII masking, and **no** instruction-stripping in this service. `agents.yaml` declares
  `data_scope: {pii: masked}` for the `mcp-anonymous` charter — **[INTENT]**, nothing in
  `openbank-mcp-service` reads `data_scope`. Contrast agent-service, which does wrap untrusted tool
  results in data markers (its T-I2). Once the ports return real data, an agent that has been
  induced to call a read tool can relay balances and transaction narratives verbatim to wherever its
  own client sends them; the bank's boundary ends at the response.
- **T-I4 — prompt injection reaching a tool call (AI-specific).** The bank does not control the
  model, the system prompt, or the conversation on the other side of this protocol — that is
  inherent to being an MCP *server* rather than the agent runtime. Attacker-controlled text that
  reaches the model (a transaction narrative, a merchant name, a document) can therefore induce a
  tool call. **What actually bounds this — and it is the correct control — is not prompt hygiene but
  the capability surface:** the five-entry `capabilities` map plus the charter's `deny` globs mean
  the *worst* a successful injection can achieve is a tool that already exists. **[LIVE]** The
  residual is precisely T-I3 (relay what the reads return) and T-E3 (get a proposal created), not
  arbitrary action. This is the strongest argument for keeping the tool list curated and short, and
  for never adding a tool whose blast radius exceeds what an injected agent may safely be trusted to
  invoke unattended.

### Denial of service

- **T-D1 — no rate limit, no budget, no idempotency. [LIVE gap]** The `mcp-anonymous` charter
  declares `limits: {tokens_per_run: 80000, runs_per_day: 1000}` — **[INTENT]**; that is
  agent-service's `CharterRateLimiter` vocabulary and **no code in `openbank-mcp-service` reads
  `limits`**. Combined with the unauthenticated surface (T-S2), any caller the ADR-0081 allow-list
  admits (T-E1) can loop `tools/call` freely. Bounded today by the stub (no
  downstream fan-out, so the damage is one pod's CPU at `limits: 1 CPU / 512Mi`); phase 2 makes
  every call a fan-out into account/balance/transaction/consent, making this service an in-cluster
  amplifier against the money-path read services.
- **T-D2 — replay.** There is no idempotency key, no nonce and no request-id binding on `/mcp`.
  Replaying a captured `tools/call` re-executes it. Reads are idempotent, so this is inert while the
  tools are reads and stubs; it stops being inert the moment `propose_payment` writes a
  maker-checker row, where a replayed proposal becomes N proposals for a human to dispose of — a
  queue-flooding and approval-fatigue vector, not a direct debit. `X-Request-ID`-style idempotency
  (as `openbank-psd2-service` uses) must land with the real `ProposalPort`.
- **T-D3 — PDP outage.** `McpEndpoint` catches every PDP exception and denies. **[LIVE]** Correct
  posture; the cost is that an OPA-sidecar failure is a full outage of `tools/call`, which is the
  right trade for a money-adjacent surface.

### Elevation of privilege

- **T-E1 — NetworkPolicy present, but the same-namespace rule is the whole fence. [LIVE, partial]**
  `mcp-service-ingress-allow-list` (`openbank.io/derived: gen-network-policies`) has been live since
  the phase-1 deploy (#2134); it was authored into `components/agent/network-policies.yaml` because
  `gen-network-policies.py` keyed its output by *namespace* and `platform` is shared by agent, ap2,
  copilot and mcp — which is why the first draft of this model recorded it as absent (issue #2207).
  It is now generated into `components/mcp/`. What it actually fences: every cross-namespace source
  except `admin-ui` (8150/8181), `observability` and `security-scanner` (8085) is DROPPED. What it
  does **not** fence: the unconditional `podSelector: {}` same-namespace rule, so any pod in
  `platform` — agent-service, ap2-service, copilot-service and their sidecars — reaches `:8150`
  unauthenticated (T-S2). `policyTypes: [Ingress]` only, so egress is unrestricted. Closing the
  remaining exposure is an authentication problem, not a network one.
- **T-E2 — confused deputy (AI-specific). [INTENT — the core phase-2 design risk]** Phase 2 binds
  `@RegisterRestClient` adapters that will call account/balance/transaction/consent with a **service
  bearer**. At that point the MCP server holds the bank's own credential and acts on it in response
  to an unauthenticated, unidentified request — the textbook confused deputy, and the reason
  `grantedAccounts` must be resolved from a *verified* token rather than passed in by the caller.
  The correct sequencing is: OAuth 2.1 → consent binding **first**, consent-intersection enforcement
  **in the port** second, real clients **third**. Building them in any other order ships an
  authenticated read proxy with no authorization.
- **T-E3 — scope escalation across the five tools. [LIVE, partly mitigated]** The capability map is
  a genuine deny-by-default gate: a tool name with no entry has no OPA action and is refused before
  the PDP is consulted (`McpEndpoint.handleToolCall`), and the charter's `deny` globs (`money.*`,
  `gh.pr.*`, `*.write`, `secrets.read.raw`) plus the fleet-wide hard-denied tier apply on top via
  `agents.allow`. Unit-tested in `McpEndpointTest`. **The residual is horizontal, not vertical:**
  all five capabilities are granted to the one charter every caller assumes, so there is no
  privilege *tier* to climb — reaching `propose_payment` requires exactly what reaching
  `list_accounts` requires. Per-caller charters (phase 2) are what make this row meaningful.
- **T-E4 — `requires_human` is not enforced by anything. [INTENT]** The `mcp-anonymous` charter
  declares `requires_human: [every: proposal, sca: dynamic_linking, scope: consent_granted]`.
  `agents.rego` states this outright: *"same as every charter's `requires_human` block, which no
  code path reads either"*. The propose-only regime — the claim that this service can never move
  money — is therefore a property of the *stub* today and, in phase 2, a property of whatever the
  `ProposalPort` implementation happens to do. Nothing structurally prevents a future
  `ProposalPort` from calling `transaction-service` directly. The HITL + SCA guarantee needs to be
  enforced at the write boundary (a `PROPOSED`-only state machine that has no transition this
  service can trigger), not asserted in YAML.

## 5. Documented-but-unenforced controls (summary)

Collected in one place because in this service they outnumber the enforced ones, and because a
reader skimming `agents.yaml` would reasonably assume all of them work:

| Claimed control | Where it is claimed | Enforced by |
|---|---|---|
| Acting agent resolved from `X-Agent-Id` | `McpEndpoint` KDoc | **nothing** — headers are not read; the id is hardcoded |
| Consent-scoped reads / `grantedAccounts` intersection | `ReadPorts.kt` KDoc, ADR-0181 | **nothing** — only the stub implements the port |
| `pii: masked` on the agent's data scope | `agents.yaml: mcp-anonymous.data_scope` | **nothing** in this service |
| `requires_human: {every: proposal, sca: dynamic_linking, scope: consent_granted}` | `agents.yaml` | **nothing** — stated explicitly in `agents.rego` |
| `limits: {tokens_per_run, runs_per_day}` | `agents.yaml` | **nothing** in this service (agent-service has a `CharterRateLimiter`; this one does not) |
| ~~Policy decision recorded to the audit chain~~ | `agents.rego` `decision` comment, ADR-0031 D5 | **now enforced** — `McpCallAuditor` (#2207); see T-R1 |
| Declared tool `inputSchema` | `McpToolRegistry.tools` | **nothing** server-side beyond presence + JSON type |
| 2 approvals on money-path changes | `CLAUDE.md`, `rules.yaml` | **nothing** — `main-protection` has `required_approving_review_count: 0` (issue #2183). Relevant here because §7 argues for the money-path listing |

Genuinely enforced today: the deny-by-default capability map, the charter allow/deny evaluation via
the shared PDP, fail-closed on PDP error, the AI-attributed audit event on every tool call, the
read-only signed OPA bundle with a pod-rolling checksum, and the container hardening.

## 6. Residual risks & assumptions

1. **The stub boundary is the load-bearing control and no gate protects it.** Every read tool
   returns a canned note. If `StubReadPorts` is replaced without T-I2, T-E2 and T-S1 being closed
   first, this service becomes an unauthenticated read proxy over customer accounts. Suggested
   guard: a CI check that binding a non-stub `AccountReadPort` requires `resolveContext()` to no
   longer be a constant.
2. **No ingress today.** Correct, and it should stay that way until authn exists. An
   internet-exposed MCP endpoint with `tenant-enabled: false` would be a critical exposure.
3. **Phase-1 sandbox only.** No real customer data has passed through this service.
4. **`propose_payment` is money-adjacent by design.** The propose-only guarantee currently rests on
   an unimplemented port, not on a state machine (T-E4).
5. **The MCP protocol version is pinned** (`2025-06-18`) and the server advertises no capabilities
   beyond tools — no resources, prompts, or sampling, which keeps the surface small. Worth keeping.
6. **The AGPL carve-out (ADR-0136)** applies to this service's own code; the shared Apache-2.0 libs
   it consumes are unaffected. No security consequence, noted for completeness.

## 7. Should `openbank-mcp-service` be in `rules.yaml: money_path_services`? (not changed here)

**Conclusion: not today; yes at phase 2, and the trigger should be the `ProposalPort` binding.**

- **Against, today.** The list is "services that move funds" plus a few adjacent ones. This service
  moves nothing, calls nothing, and holds no state: `propose_payment` returns a literal
  `{"phase":"1-stub","status":"PROPOSED"}` object. Adding it now would assert a risk profile the
  code does not have, and — because the list drives the `check-threat-models.py` gate, the pitest
  matrix and the 2-approval rule — it would add ceremony without adding signal.
- **For, at phase 2.** Once `ProposalPort` writes a real maker-checker row, this service occupies
  exactly `openbank-psd2-service`'s position: it authorizes and shapes a payment instruction while
  the irreversible action lives downstream. `openbank-psd2-service` is *not* on the money-path list
  either, so the honest description for both is **money-path-adjacent**, and the two should be
  treated consistently — whichever way that is decided, decide it for both rather than for one.
- **Practical note.** Adding it satisfies `check-threat-models.py` immediately (this document
  exists and is structured). The 2-approval half of the money-path rule would add nothing, because
  it is not implemented — `main-protection` requires zero approvals (issue #2183). That is an
  argument for fixing #2183, not for skipping the listing.

Recommended follow-up issues (not opened by this PR): OAuth 2.1 → consent binding before the
read ports are bound (T-S1/T-E2); server-side `inputSchema` validation + idempotency on
`propose_payment` (T-T2/T-D2).

## 8. Change log

- **2026-07-25 (ADR-0181 phase 1, issue #1922)** — First model. Written against the shipped phase-1
  code (PR #2104 service, #2134/#2136 deploy, #2142 OPA sidecar), not against ADR-0181's intent.
  Records that the endpoint is unauthenticated with a constant principal, that the read/proposal
  ports are stubs, and that no audit event is emitted; separates the five genuinely enforced
  controls from the eight documented-but-unenforced ones (§5). Establishes the phase-2 sequencing
  constraint: identity + consent enforcement must precede binding the real read ports.
- **2026-07-25 (issue #2207)** — Corrected T-E1: the NetworkPolicy was never missing. It shipped
  with the phase-1 deploy (#2134) as `mcp-service-ingress-allow-list` and is live; the original
  finding read `components/mcp/` for a file that `gen-network-policies.py` had written into
  `components/agent/` (one file per *namespace*, first directory alphabetically). Restated as the
  narrower, true risk: the unconditional same-namespace rule leaves the whole `platform` namespace
  able to reach an unauthenticated `:8150`.
- **2026-07-25 (issue #2207)** — T-R1 closed: `McpCallAuditor` emits an AI-attributed `AuditEvent`
  for every `tools/call` outcome, so the ADR-0031 D5 `policy_decision` the rego was written to
  expose is now on the record. Residuals restated (constant actor id until T-S1, log-pipeline
  delivery, deliberately no arguments/results in the payload).
