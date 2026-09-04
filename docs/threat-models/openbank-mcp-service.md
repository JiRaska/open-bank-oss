<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-mcp-service

STRIDE/DFD threat model for the first-party Model Context Protocol server, per ADR-0030 D2.
**Not** currently listed in `rules.yaml: money_path_services` (see §7); money-path-adjacent by
design intent, and — as of the phase-2 cutover recorded here — no longer inert: the read tools now
call real downstream services behind a live-validated PSD2 consent.

- **Status:** Draft (revised for the ADR-0195 phase-2 cutover — PR #2253 caller auth, #2262 real
  read ports, #2278 M2M client, #2316 atomic cutover; #3292 policy-input fix)
- **Last reviewed:** 2026-08-02
- **Owner:** mcp-service CODEOWNERS
- **Related ADRs:** ADR-0181 (this service), ADR-0031 (AI-agent governance / charters), ADR-0034
  (unified OPA authz — the shared PDP this service calls), ADR-0126 (consent lifecycle — the phase-2
  binding), ADR-0089 (customer-copilot propose-only regime), ADR-0086 (audit hash chain),
  ADR-0002 (hexagonal), ADR-0136 (AGPL carve-out for agent services)

## 0. Phase posture — read this before anything below

**The phase-2 cutover (ADR-0195) has landed.** Caller identity and the read tools are both real now;
`propose_payment` is not. Every row below is tagged:

- **[LIVE]** — implemented and reachable today, with the file that implements it.
- **[INTENT]** — described in an ADR, a KDoc, `agents.yaml`, or a rego comment, but **no code path
  enforces it**. These are not mitigations. They are documentation.

What is actually running (`openbank-infra/gitops/components/mcp/mcp-service.yaml`):

| Fact | State |
|---|---|
| JSON-RPC surface `initialize` / `ping` / `tools/list` / `tools/call` on `:8150/mcp` | **LIVE** (`infrastructure/mcp/McpEndpoint.kt`) |
| Five curated tools, deny-by-default capability map | **LIVE** (`application/McpToolRegistry.kt`) |
| Every `tools/call` gated on the shared ADR-0034 PDP as `AI_AGENT`, fail-closed | **LIVE** (`McpEndpoint.handleToolCall`, OPA sidecar in the pod) |
| Caller authentication on `/mcp` | **LIVE.** `quarkus.oidc.tenant-enabled: true`; a validated OAuth 2.1 bearer is required. No token ⇒ `resolveContext()` throws ⇒ the call is denied and metered `resolution_failed` (`McpEndpoint.kt`, ADR-0195 step 4, #2316) |
| Caller identity | **LIVE.** `CallerContextResolver.resolveOrNull()` reads the token's `sub` (`agent:<id>`) and `consent_id` claim (#2253); the PDP principal id is `ctx.agentId`, no longer a constant. **Residual:** every real caller still authenticates through the one M2M OIDC client provisioned so far, so in practice one charter (`mcp-anonymous`) still covers every caller — see T-S1 |
| Tool bodies — accounts, balance, transactions, consents | **LIVE.** `RealAccountReadPort` (#2262, wired as the CDI default in #2316) calls consent-service, account-service, balance-service and transaction-service over M2M OIDC client-credentials (#2278) |
| Tool body — proposal (`propose_payment`) | **REFUSES.** `UnwiredProposalPort` throws `UnsupportedOperationException` and `McpEndpoint` relays the reason verbatim as a tool error: no proposal store is wired, so nothing is recorded and the caller is told so (#2414). It previously returned the canned `{"phase":"1-stub","status":"PROPOSED"}` — a fabricated acknowledgement of a proposal no human would ever see; worse, the PII masker replaced the explanatory `note` with `***`, so `status: PROPOSED` was all the caller got. copilot-service's `ActionProposal` domain is still internal (not a callable port), so which service owns an MCP proposal remains undecided |
| Consent scoping | **LIVE.** `RealAccountReadPort.validate()` calls consent-service `POST /consents/{id}/validate` on every read, reads `grantedAccounts` from THAT response (never from the token), and fails closed on revoked/expired/out-of-scope — see T-I2 |
| Audit trail of tool calls / policy decisions | **LIVE** (`application/McpCallAuditor.kt`) — one canonical `AuditEvent` per `tools/call`, `actorType = AI_AGENT`, carrying tool, capability, charter, `policy_decision` and outcome. Emitted on ALL four outcomes (allow, policy deny, unmapped tool, PDP outage). Delivery is the shared `LoggingAuditEventPublisher` (log pipeline), as everywhere else in the fleet — no Kafka producer in the module |
| Rate limiting / budgets / idempotency | **NONE** in this service |
| NetworkPolicy | **LIVE.** `mcp-service-ingress-allow-list` (ADR-0081, derived) admits same-namespace + admin-ui:8150/8181 + observability/security-scanner:8085 and drops every other cross-namespace source. Ingress only — egress is unrestricted |
| Internet exposure | **NO** ingress, no `HTTPRoute`, `Service` is `ClusterIP` — in-cluster only |

**The single most important consequence, restated for phase 2:** the confused-deputy precondition
(T-E2) that §0 of the phase-1 model warned about is now live — the service holds and uses its own
M2M credential on every real caller's behalf. What closes it is exactly the sequencing that model
called for: identity (#2253) and consent-intersection enforcement (in the port, #2262) landed
*before* the real clients went live as the CDI default (#2316) — never the other way round. The
last policy-input gap closed after the cutover: `rest.rego`'s bridge into `agents.rego` now
forwards `attributes` (#3292, ADR-0195 step 5), so the PDP receives the consent id on every call —
though no policy consumes it yet, so consent enforcement still lives in the port, with the policy
plane now able to add a second layer. Still open, and never gated on the cutover: per-agent charter
provisioning (T-S1) has not shipped, so the distinguishable identity the token now carries is not
yet matched by a distinguishable authorization tier.

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
        │  JSON-RPC POST /mcp        ← validated OAuth 2.1 bearer required (LIVE, ADR-0195)
        ▼
┌─────────────────────── pod: mcp-service (ns platform) ───────────────────────┐
│  McpEndpoint                                                                 │
│    ├─ CallerContextResolver: sub → agentId, consent_id claim → consentId    │
│    │        no token / no consent_id claim ⇒ REFUSE (fail closed)           │
│    ├─ tools/list ─────────────────────────────► full schema, NO gate         │
│    └─ tools/call                                                             │
│         ├─ registry.capabilities[tool]  ── absent ⇒ REFUSE (deny-by-default) │
│         ├─ PDP.allow(Principal(ctx.agentId, AI_AGENT), capability)           │
│         │        └──localhost:8181──► [OPA sidecar]  rest.rego → agents.rego │
│         │                              (mcp-opa-bundle ConfigMap)            │
│         │            exception / timeout ⇒ DENY (fail closed)                │
│         │            rest.rego bridge forwards `attributes` (#3292) — PDP    │
│         │            sees consentId; no policy consumes it yet               │
│         └─ registry.call(...) ──► RealAccountReadPort (accounts/balance/      │
│              transactions/consents) — M2M OIDC client-credentials bearer      │
│              ├─ consent.validate(consentId, scope, iban) ── LIVE, fails      │
│              │    closed on revoked/expired/out-of-scope; grantedAccounts    │
│              │    read from THIS response, never from the caller's token     │
│              └─ propose_payment ──► UnwiredProposalPort — REFUSES; no        │
│                   proposal store, nothing recorded, caller told so           │
└──────────────────────────────────────────────────────────────────────────────┘
```

**Trust boundaries.** (a) MCP client → `/mcp`: **LIVE authentication boundary** — OIDC
resource-server validation of the bearer, enforced before `resolveContext()` returns; the
network-level fences (ADR-0081 ingress allow-list, cluster-internal `Service`) are now
defense-in-depth rather than the only control.
(b) service → OPA: localhost, same pod. (c) service → downstream banking services: **LIVE**, over an
M2M OIDC client-credentials bearer (#2278) — the confused-deputy precondition (T-E2) is now real,
and what bounds it is the live consent-intersection check inside the port, not the transport.

**External entities.** MCP clients (AI agents), the OPA sidecar, consent-service, account-service,
balance-service, transaction-service. The maker-checker queue is not yet an entity here —
`propose_payment` refuses.

## 3. Authn / Authz — what the PDP can and cannot see

Every `tools/call` builds one `AuthzQuery` (`McpEndpoint.handleToolCall`):

```
principal  = Principal(id = ctx.agentId, type = "AI_AGENT")   ← from the validated token (LIVE)
action     = registry.capabilities[toolName]                  ← one of 5 strings
resource   = null
attributes = {"tool": …, "consentId": …}
```

`rest.rego`'s `agent-charter-allows` rule bridges this to `agents.rego` by rebuilding the input as
`{agent, tool, resource, attributes}` — since #3292 (ADR-0195 step 5) the bridge **forwards
`attributes`**, defaulting to `{}` when the caller sends none. The consent id now reaches the
policy plane on every call; no rule in `agents.rego` consumes `consentId` yet, so the consent
check still lives in the port (§0, T-I2), not the policy. Therefore:

**The PDP CAN see:** the principal type (`AI_AGENT`), the *real* agent id from the validated token
(no longer a constant), which of the five capabilities is being invoked, and — since #3292 — the
presented consent id. That is enough for the hard-denied tool tier, the charter's `deny` globs
(`money.*`, `gh.pr.*`, `*.write`, `secrets.read.raw`) and its five-entry `allow` list — all
**[LIVE]** and all genuinely enforced — and it unblocks a future policy that gates on consent
state (none is written yet; today the id is observed, not acted on).

**The PDP CANNOT see:** the account id argument, the proposed amount, the payee, or any request
count (the endpoint never sends them). So no policy written in this plane can express "this
consent has expired" or "this is the 500th call this minute" — the consent-validity and
account-scope checks that *are* now enforced live entirely in `RealAccountReadPort`, not here; a
future tool whose author forgets to call `validate()` gets no backstop from the PDP until a
consent-state policy is authored on the now-forwarded input. Those remain **input** gaps, not
policy gaps to be closed by better rego.

**Relation to the `openbank-psd2-service` finding (PR #2166 / issue #2169).** The same limitation
applies here, and it applies *worse*. On psd2 the PDP also cannot distinguish one external caller
from another (a TPP authenticates by eIDAS QWAC mTLS, carries no OIDC bearer, and reaches OPA as
`ANONYMOUS`) — but there the coarse grant is defensible because `EidasMtlsFilter` has *already*
authenticated the TPP, checked its eIDAS role against tpp-registry, and 401'd an unknown or
unauthorized one before the interceptor runs. Issue #2169's complaint is only that no guard *ties*
the granted actions to that filter's path prefixes.

**openbank-mcp-service now has that upstream authenticator.** `CallerContextResolver` validates the
OAuth 2.1 bearer and requires a `consent_id` claim before any tool call is even considered (#2253),
and `RealAccountReadPort` live-validates that consent against consent-service on every read (#2262).
That closes the specific gap this section originally raised — the psd2 comparison no longer applies
the same way, because the precondition #2169 asks psd2 to rely on (an upstream authenticator having
already run) is now genuinely present here, not merely assumed. What #2169's underlying shape still
applies to: the coarse charter grant (§4 T-S1) — the PDP's `allow` decision is still made against
one shared charter regardless of which real agent id presented the token, exactly the "grant resting
on an invariant nothing distinguishes" pattern, just one layer up from where it was in phase 1.

## 4. STRIDE

### Spoofing

- **T-S1 — every real caller still shares one charter. [PARTIALLY CLOSED, LIVE gap remains]**
  `resolveContext()` no longer hardcodes an identity — the PDP principal id is `ctx.agentId`, read
  from the validated token's `sub` claim (#2253, #2316). Forging *that* identity now requires a
  valid OAuth 2.1 token, not merely network reachability — the original T-S1 forgery scenario is
  closed. **What remains open:** per-agent charter provisioning has not shipped. The `mcp-anonymous`
  charter's own comment in `agents.yaml` still says its `id` must stay `mcp-anonymous` "or every
  tools/call defaults to deny" — nothing has changed that. In practice every real caller today
  authenticates through the one M2M OIDC client provisioned so far, so distinguishable *identity*
  (LIVE) is not yet matched by distinguishable *authorization* — every real caller is still granted
  exactly the same five-tool, propose-only envelope regardless of who it actually is.
  **[INTENT]** ADR-0181 phase 2 / ADR-0126, still open: per-agent OAuth 2.1 client provisioning, one
  charter per real caller, `mcp-anonymous` retired as the universal fallback.
- **T-S2 — unauthenticated surface. [CLOSED]** `quarkus.oidc.tenant-enabled: true` (#2316); every
  `tools/call` requires a validated bearer, and `resolveContext()` throws — denying and metering
  `resolution_failed` — when none is presented or the token carries no `consent_id` claim
  (`McpEndpointTest`'s dedicated "no token → denied" case). The NetworkPolicy fence described in T-E1
  is now defense-in-depth rather than the only control.

### Tampering

- **T-T1 — policy-bundle tampering.** The rego + charter + `rules.yaml` are baked into the
  `mcp-opa-bundle` ConfigMap by `gen-mcp-opa-bundle.sh`, mounted read-only, and a checksum
  annotation rolls the pod on change. **[LIVE]** Changes go through the OPA-bundle CI gate; the
  bundle is derived, never hand-edited.
- **T-T2 — tool-argument tampering. [LIVE gap, narrowed for the read tools]** Arguments are still
  validated only for presence and JSON type at the transport layer (`McpToolRegistry.reqText`); the
  declared `inputSchema` is advertised to the client but is **not** schema-validated server-side.
  For the read tools this is now bounded, not harmless: a malformed or out-of-scope `accountId`
  reaches `RealAccountReadPort.validate()`, which live-checks it against the presented consent's
  `grantedAccounts` and fails closed (T-I2) — so the residual is a wasted downstream round-trip and
  an error, not a data leak. For `propose_payment` it is **closed at the tool boundary**: `ProposePaymentArgs.validate()`
  rejects a malformed amount, a non-mod-97 IBAN and a non-ISO-4217 currency before the port is
  reached (#2649), and the port itself now refuses outright (#2414).
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
  **Residual:** (a) attribution is only as good as the identity — now the real per-token `sub`
  rather than a constant (T-S1 partially closed), so the trail answers "by which authenticated
  caller" today; it still cannot distinguish *which agent product* that was, since every real caller
  shares the one M2M client provisioned so far (T-S1's remaining gap);
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
- **T-I2 — cross-consent data harvesting. [CLOSED]** `RealAccountReadPort.validate()` calls
  consent-service `POST /consents/{id}/validate` on every read (`listAccounts`, `getBalance`,
  `listTransactions`, `listConsents`), reads `grantedAccounts` from THAT live response — never from
  the caller's token — and throws (fails closed) on revoked, expired, or out-of-scope consent, or
  when the requested account IBAN is not within `grantedAccounts`. The exact scenario this row
  originally warned about — a bound `AccountReadPort` with no intersection check turning
  `get_balance(accountId)` into an unauthenticated read of any guessable account id — is what #2262
  built the check specifically to prevent, and #2316 only wired it as the default once that check
  existed. **Residual:** the check lives in this one port class; a future tool or port that forgets
  to call `validate()` gets no PDP backstop (§3) and no CI guard beyond code review.
- **T-I3 — tool-result exfiltration (AI-specific). [LIVE, now with real data]** A tool result is
  JSON serialized straight into a `ToolContent.text` block and handed to the model. There is **no**
  data-marker wrapping, **no** PII masking, and **no** instruction-stripping in this service.
  `agents.yaml` declares `data_scope: {pii: masked}` for the `mcp-anonymous` charter —
  **[INTENT]**, nothing in `openbank-mcp-service` reads `data_scope`. Contrast agent-service, which
  does wrap untrusted tool results in data markers (its T-I2). This residual, previously latent
  behind the stub, is now live: an agent induced to call a read tool relays real balances and
  transaction narratives verbatim to wherever its own client sends them; the bank's boundary ends at
  the response. Recommended follow-up (not opened by this document, see §7).
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

- **T-D1 — no rate limit, no budget, no idempotency. [LIVE gap, now live-exploitable]** The
  `mcp-anonymous` charter declares `limits: {tokens_per_run: 80000, runs_per_day: 1000}` —
  **[INTENT]**; that is agent-service's `CharterRateLimiter` vocabulary and **no code in
  `openbank-mcp-service` reads `limits`**. T-S2 (unauthenticated surface) is now closed, so this is
  no longer "any pod that can reach `:8150`" — a caller needs a valid bearer. But nothing throttles
  *what a validly authenticated caller* can do: every `tools/call` now fans out into
  consent-service, account-service, balance-service and/or transaction-service (§0), so a caller
  looping a read tool is an in-cluster amplifier against those money-path read services, not merely
  CPU on this one pod as it was while the port was a stub. The mitigating precondition changed from
  "no ingress" to "requires a token"; the amplification consequence this row warned about is the one
  that actually landed.
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
- **T-E2 — confused deputy (AI-specific). [LIVE, mitigated by sequencing]** `RealAccountReadPort`'s
  `@RegisterRestClient` adapters call account/balance/transaction/consent with an M2M OIDC
  client-credentials bearer (#2278) — the MCP server now holds the bank's own credential and acts on
  it on every real call. What prevents this from being the textbook confused-deputy exploit is
  exactly the sequencing this row called for: caller identity (#2253) and consent-intersection
  enforcement in the port (#2262) both landed *before* the real clients were wired as the CDI
  default (#2316) — enforced structurally by `check-mcp-stub-ports-vs-caller-auth.sh` (#2230), which
  fails the build if a non-stub port ships while the placeholder identity is still present.
  `grantedAccounts` is resolved from the verified consent-service response, never passed in by the
  caller, so a request cannot claim scope it was not granted. **Residual:** the guard only checked
  the two landed together; it does not (and cannot) verify the *logic inside* the port stays
  correct on future changes — that is T-I2's residual, restated from the deputy's-own-credential
  angle.
- **T-E3 — scope escalation across the five tools. [LIVE, partly mitigated]** The capability map is
  a genuine deny-by-default gate: a tool name with no entry has no OPA action and is refused before
  the PDP is consulted (`McpEndpoint.handleToolCall`), and the charter's `deny` globs (`money.*`,
  `gh.pr.*`, `*.write`, `secrets.read.raw`) plus the fleet-wide hard-denied tier apply on top via
  `agents.allow`. Unit-tested in `McpEndpointTest`. **The residual is still horizontal, not
  vertical, and is now the same fact as T-S1's remaining gap:** all five capabilities are granted to
  the one charter every real caller authenticates as, so there is no privilege *tier* to climb —
  reaching `propose_payment` (still a stub) requires exactly what reaching `list_accounts` (now
  live) requires. Per-caller charters, not yet shipped, are what would make this row's mitigation
  meaningful rather than coincidental.
- **T-E4 — `requires_human` is not enforced by anything. [INTENT]** The `mcp-anonymous` charter
  declares `requires_human: [every: proposal, sca: dynamic_linking, scope: consent_granted]`.
  `agents.rego` states this outright: *"same as every charter's `requires_human` block, which no
  code path reads either"*. The propose-only regime — the claim that this service can never move
  money — is still not a state machine. Two things have changed since this row was written, and
  neither closes it. `ProposedOnly.enforce` moved the PROPOSED-only check onto the CALL PATH, so a
  future port cannot hand a caller a disposed proposal. And `propose_payment` now REFUSES
  (`UnwiredProposalPort`, #2414) instead of returning a fabricated `PROPOSED` for a proposal nobody
  recorded — a control that reports success is worse than one that is absent, because everything
  upstream believes a human is reviewing something. What remains open is the whole of the real
  design: nothing structurally prevents a future `ProposalPort` from calling `transaction-service`
  directly, and the HITL + SCA guarantee must be enforced at the write boundary, not asserted in
  YAML.

## 5. Documented-but-unenforced controls (summary)

> **2026-07-31 — ADR-0224 D2 (agent sessions) landed.** The service is stateful now: the
> `agent_session` store backs a staff OBO session lifecycle (`McpSessionResource`). New trust
> notes: (a) issuance self-binds — subject is the authenticated operator and the role ceiling is
> intersected with held roles server-side, so a session can never exceed its owner's authority;
> (b) the OBO resolver validates the session **live** on every call (revoked/expired/mismatched
> → anonymous), so revocation has no propagation delay; (c) the session store is a new
> availability dependency for the OBO path — its outage fails closed (no OBO calls), mirroring
> the consent-validate stance.

Collected in one place because in this service they outnumber the enforced ones, and because a
reader skimming `agents.yaml` would reasonably assume all of them work:

| Claimed control | Where it is claimed | Enforced by |
|---|---|---|
| ~~Acting agent resolved from a validated token~~ | `CallerContextResolver` KDoc, ADR-0195 | **now enforced** — `sub` + `consent_id` claims, fail closed with no fallback (#2253, #2316) |
| ~~Consent-scoped reads / `grantedAccounts` intersection~~ | `RealAccountReadPort` KDoc, ADR-0181/ADR-0126 | **now enforced** — live `consent.validate()` call per read, fails closed (#2262, wired live in #2316); see T-I2 |
| Per-agent charter (one charter per real caller) | ADR-0181 phase 2 intent, `agents.yaml: mcp-anonymous` comment | **nothing** — every real caller still authenticates through the one provisioned M2M client; see T-S1 |
| `pii: masked` on the agent's data scope | `agents.yaml: mcp-anonymous.data_scope` | **nothing** in this service — see T-I3, now live-exploitable |
| `requires_human: {every: proposal, sca: dynamic_linking, scope: consent_granted}` | `agents.yaml` | **nothing** — stated explicitly in `agents.rego`; moot in practice while `propose_payment` refuses outright and records nothing (T-E4, #2414) |
| `limits: {tokens_per_run, runs_per_day}` | `agents.yaml` | **nothing** in this service (agent-service has a `CharterRateLimiter`; this one does not) — see T-D1, now live-exploitable |
| ~~Policy decision recorded to the audit chain~~ | `agents.rego` `decision` comment, ADR-0031 D5 | **now enforced** — `McpCallAuditor` (#2207); see T-R1 |
| Declared tool `inputSchema` | `McpToolRegistry.tools` | **nothing** server-side beyond presence + JSON type; narrowed by the live consent check for read tools (T-T2), fully open for `propose_payment` |
| 2 approvals on money-path changes | `CLAUDE.md`, `rules.yaml` | **nothing** — `main-protection` has `required_approving_review_count: 0` (issue #2183). Relevant here because §7 argues for the money-path listing |

Genuinely enforced today: caller-token validation with no fallback, per-caller PDP principal id,
live consent-intersection enforcement in the read port, the deny-by-default capability map, the
charter allow/deny evaluation via the shared PDP, fail-closed on PDP error, the AI-attributed audit
event on every tool call, the read-only signed OPA bundle with a pod-rolling checksum, and the
container hardening.

## 6. Residual risks & assumptions

1. **The stub boundary was the load-bearing control, and it is now retired for the read side.**
   The CI guard this section originally suggested shipped as `check-mcp-stub-ports-vs-caller-auth.sh`
   (#2230) and did exactly its job: it kept the real `AccountReadPort` from going live before T-I2
   and the caller-identity half of T-S1/T-E2 were closed, and now correctly reports "placeholder
   identity removed; real ports permitted." The remaining load-bearing stub is gone: `propose_payment` refuses
   (`UnwiredProposalPort`, #2414) rather than acknowledging a proposal it never wrote, so there is
   no longer a fabricated success for the guard to protect. The same class of guard should gate the
   eventual REAL binding on T-E4 actually being closed, not merely on identity/consent being
   present.
2. **No ingress today.** Still correct, and still should stay that way — authentication now exists,
   but nothing here has been evaluated against a hostile internet-facing client, only in-cluster
   ones.
3. **Real customer data now passes through this service.** The sandbox posture (no production
   customers) is what currently bounds the blast radius of T-I3, not any code-level control.
4. **`propose_payment` is money-adjacent by design and is unimplemented — and now says so.** The
   propose-only guarantee still rests on there being no implementation, not on a state machine
   (T-E4). What changed in #2414 is only honesty: the tool refuses and records nothing, instead of
   answering `PROPOSED` to a caller that will relay it to a person as a submitted proposal.
5. **The MCP protocol version is pinned** (`2025-06-18`) and the server advertises no capabilities
   beyond tools — no resources, prompts, or sampling, which keeps the surface small. Worth keeping.
6. **The AGPL carve-out (ADR-0136)** applies to this service's own code; the shared Apache-2.0 libs
   it consumes are unaffected. No security consequence, noted for completeness.

## 7. Should `openbank-mcp-service` be in `rules.yaml: money_path_services`? (not changed here)

**Conclusion: not today; yes once `ProposalPort` binds, and that trigger has still not fired.**

- **Against, today — narrower than before, but still true.** The list is "services that move
  funds" plus a few adjacent ones. As of the phase-2 cutover this service does call real services
  and hold real customer data in transit (§0) — the "calls nothing" half of the original argument no
  longer holds. But it still **moves nothing**: `propose_payment` now refuses outright
  (#2414), where it previously returned a literal `{"phase":"1-stub","status":"PROPOSED"}` object. The trigger this
  section named at first writing was deliberately the write side, not the read side, precisely
  because reads-only-adjacent-to-money is `openbank-psd2-service`'s own position and that service is
  not on the list either (see the next bullet). Adding this service now, on the strength of the read
  cutover alone, would still assert a risk profile broader than what the code does — it would add
  ceremony without adding the signal the list is for.
- **For, at phase 2.** Once `ProposalPort` writes a real maker-checker row, this service occupies
  exactly `openbank-psd2-service`'s position: it authorizes and shapes a payment instruction while
  the irreversible action lives downstream. `openbank-psd2-service` is *not* on the money-path list
  either, so the honest description for both is **money-path-adjacent**, and the two should be
  treated consistently — whichever way that is decided, decide it for both rather than for one.
- **Practical note.** Adding it satisfies `check-threat-models.py` immediately (this document
  exists and is structured). The 2-approval half of the money-path rule would add nothing, because
  it is not implemented — `main-protection` requires zero approvals (issue #2183). That is an
  argument for fixing #2183, not for skipping the listing.

Recommended follow-up issues (not opened by this PR): per-agent OAuth 2.1 client provisioning /
charter retirement of `mcp-anonymous` as the universal fallback (T-S1/T-E3); rate limiting or a
`CharterRateLimiter`-equivalent now that reads fan out into money-path services (T-D1); tool-result
data-marker wrapping / PII masking to match `agents.yaml`'s declared `data_scope` (T-I3);
server-side `inputSchema` validation + idempotency on `propose_payment`, when that port stops being
a stub (T-T2/T-D2).

## 8. Change log

- **2026-09-03** — Doc correction, no behavior change: the ADR-0181 phase-2 entry said
  "`propose_payment` / `StubProposalPort` were deliberately left untouched". `StubProposalPort` no
  longer exists — `git grep -n "class StubProposalPort" -- '*.kt'` returns nothing, and the four
  remaining mentions in Kotlin are KDoc prose referring to it in the past tense. It was replaced by
  `UnwiredProposalPort`
  (`openbank-mcp-service/src/main/kotlin/com/openbank/mcp/infrastructure/read/UnwiredProposalPort.kt`),
  whose own KDoc opens "This replaces `StubProposalPort`".

  So the *word* that went stale is "untouched": the port was in fact replaced. **The conclusion the
  entry draws is unaffected** — `propose_payment` still does not reach a real payment path, T-E4 is
  still open on the same terms, and §7's argument about `money_path_services` is unchanged. This is
  a naming correction inside a historical entry, not a change in posture; it is recorded rather than
  silently edited because "left untouched" is the kind of phrase a later reader would rely on when
  deciding whether the proposal path had been revisited.

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
- **2026-07-25 (ADR-0195 phase 2, PRs #2253/#2262/#2278/#2316)** — Revised against the phase-2
  cutover: caller identity now comes from a validated OAuth 2.1 token (#2253, T-S2 closed, T-S1
  partially closed), the read tools call real downstream services behind a live PSD2
  consent-intersection check in `RealAccountReadPort` (#2262, T-I2 closed, T-T2 narrowed), the M2M
  client-credentials bearer landed (#2278), and the placeholder identity + stub read ports were
  atomically removed/replaced as the CDI default (#2316, T-E2 now live and mitigated by the landing
  sequence). `propose_payment` was deliberately left untouched — T-E4 and §7's
  conclusion are unchanged. (The port named here as `StubProposalPort` has since been replaced
  by `UnwiredProposalPort`, which says so in its own KDoc; the tool's behaviour and this
  entry's conclusion are unaffected — see the 2026-09-03 entry.) Confirmed unchanged: `rest.rego`'s `agent-charter-allows` bridge still
  drops `attributes`, so the consent id still never reaches the policy plane (§3); every real caller
  still authenticates through one M2M client, so T-S1/T-E3's charter-granularity gap remains open.
  T-I3 and T-D1 move from latent-behind-the-stub to live-exploitable, since the tools now return real
  data and real downstream fan-out.
- **2026-08-02 (issue #3292, ADR-0195 step 5)** — The policy-input gap the phase-2 entry above
  recorded as "confirmed unchanged" is closed: `rest.rego`'s `agent-charter-allows` bridge now
  forwards `attributes` (defaulting to `{}` when absent), so the consent id the endpoint passes on
  every `tools/call` reaches the policy plane (§0, §2 DFD, §3 restated). No rule consumes
  `consentId` yet — enforcement stays in `RealAccountReadPort`; the change is that a consent-state
  policy is now *authorable* on real input, and the `run.skill` skill-check can no longer be
  silently denied when an AI_AGENT arrives via the REST bridge. Guarded by
  `rest_test.rego` bridge tests and two `build-bundle.sh` drift assertions against the real
  `agents.yaml`; fleet-wide OPA bundle restamp rides the same PR (mechanics of #2142/#2152).
