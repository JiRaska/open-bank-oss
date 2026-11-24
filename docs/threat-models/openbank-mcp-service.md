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

## Customer MCP V2 read boundary

ADR-0383 D4 uses a bank-issued, certificate-bound token for balance reads. The assistant
presents its customer token and a DPoP proof bound to the published MCP URL. MCP and the
issuer independently verify the original evidence. A signed resource nonce binds the URL,
client key and expiry. Delegation commits the issuer's first use of that proof in PostgreSQL.

Keycloak authorizes one grant, account and read action. Its token expires within 60 seconds
and carries the exact connection and workload certificate binding. Balance-service verifies
the TLS peer certificate, account ownership and live grant. The live checks fail closed when
authorization state is unavailable.

V2 connect binds the grant and exact online refresh family before token delivery. Revocation
closes that connection's grants and pending proposals atomically. Later reads and refreshes
are denied. A separate app SCA ceremony governs payment approval.

The isolated customer journey exercises PAR, a genuine WebAuthn assertion, app SCA, token
issuance, a certificate-bound balance read, refresh rotation and revocation across the real
services. The separate proposal journey verifies creation and owner access for a pending
proposal. These checks use synthetic data. Shared sandbox enablement requires the reviewed
realm, workload certificate trust and network policy.

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

- **T-S3 — an MCP client connected by a stolen SSO session, a fresh passkey or an unapproved
  request. [BUILT, #16672; live once the realm and Keycloak config are applied]** The customers-realm
  flow `openbank-mcp-fresh-passkey` has no `auth-cookie` alternative (an SSO cookie never skips the
  passkey) and ends in the bank's `openbank-mcp-withheld-code` authenticator (ADR-0385 D3): the
  authorization code is issued only after sca-service CONSUMED an `MCP_CLIENT_CONNECT` challenge whose
  `payloadSha256` is the canonical connect context recomputed from the PAR-held request (client,
  redirect host, resource, scopes, party), single-use, for that party. The authenticator also refuses a
  **passkey enrolled < 72 h** (ADR-0385's passkey-age rule, now enforced; the SCA device-age half was
  already in sca-service), so an account takeover that enrols its own passkey cannot connect an
  assistant for three days. Fail closed: unconfigured, or sca-service unreachable, no code is issued.
  Residual: the customer approves an attacker-initiated connect on their device (ADR-0385 D5) — the
  app must render only the signed context. Proof: `keycloak_mcp_client_admission_test.py`
  (`test_code_not_issued_before_consume`, `test_young_passkey_refused`, `test_sso_cookie_*`).

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
  `mcp-service-ingress-allow-list` (`open-bank.tech/derived: gen-network-policies`) has been live since
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

- **T-E5 — an MCP client's token turned into a broader one by token exchange. [BUILT, #16667]**
  Keycloak's legacy token-exchange v1 does not fire the `grant-type` client-policy condition, so the
  bank's `openbank-mcp-client-guard` executor is bound by an `any-client` policy and refuses, for
  every exchange, (a) an MCP client as requester, (b) the MCP resource as target, and (c) a subject
  token issued to an MCP client. Keycloak's built-in `pkce-enforcer`, `reject-implicit-grant`,
  `reject-ropc-grant` (both tiers) and `dpop-bind-enforcer` (Tier L) are bound by tier-scoped
  policies as a second layer under the per-client switches, so an admin-console edit of one client
  cannot reopen those grants. Proof: `test_token_exchange_refused_for_mcp_client`,
  `test_no_exchange_may_target_the_mcp_resource`,
  `test_builtin_policies_hold_when_client_attributes_are_weakened` (each red with its control removed);
  `check-mcp-client-realm-parity.py` holds the realm shape.
- **T-E6 — a customer sets the `party_id` their MCP token asserts. [BUILT, #16672]** The token's
  `party_id` claim and the withheld-code consume's `expectedPartyId` both come from the user attribute
  `party_id`, so it is a declared user-profile attribute that only `admin` may view or edit (account
  console, registration and update-profile cannot write it). customer-edge's service account still
  writes it, and writes ONLY the profile fields — see customer-edge T-6. Proof:
  `test_customer_cannot_set_party_id`, `test_edge_service_account_can_still_write_party_id`.

## 5. Documented-but-unenforced controls (summary)

> **2026-07-31 — ADR-0224 D2 (agent sessions) landed.** The service is stateful now: the
> `agent_session` store backs a staff OBO session lifecycle (`McpSessionResource`). New trust
> notes: (a) issuance self-binds — subject is the authenticated operator and the role ceiling is
> intersected with held roles server-side, so a session can never exceed its owner's authority;
> (b) the OBO resolver validates the session **live** on every call (revoked/expired/mismatched
> → anonymous), so revocation has no propagation delay; (c) the session store is a new
> availability dependency for the OBO path — its outage fails closed (no OBO calls), mirroring
> the consent-validate stance.

The OBO boundary also requires positive human-token evidence before making a staff principal for
OPA: the verified JWT must come through an interactive staff client with a human username, in
addition to the live subject/JTI session, MCP audience and bounded role checks. Tokens without that
evidence are refused before a PDP query; only admitted OBO contexts send
`input.principal.service_account=false`. Agent contexts keep the machine default. Phase-2 policy
rules must wait for the separate verified image rollout before reading this flag.

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
6. **The pinned gateway SAN can be minted by any namespace — accepted until go-live (#16847).**
   `openbank-ca` is a ClusterIssuer and the repo carries no cert-manager approver policy, so whoever
   can create a `Certificate` can obtain the `mcp.edge.gateway-identity` SAN and set the edge
   client-address header. Bounded impact: that header only feeds the subject-less audit / throttle
   window key, never authentication, authorization or DPoP. **Go-live blocker:** constrain issuance
   of that SAN (approver policy or a dedicated issuer) before the customer route is enabled.
7. **The AGPL carve-out (ADR-0136)** applies to this service's own code; the shared Apache-2.0 libs
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

- **2026-11-22 (#16590 follow-up, #17099)** — Staff-root `Authorization` parsing is an ALLOWLIST.
  - *Spoofing / tenant confusion.* The DPoP check was a denylist (`startsWith("DPoP ")`), so
    `DPoP<TAB>x`, a bare `DPoP`, `DPoP<NBSP>x`, or a comma list such as `Bearer a, DPoP b` was not
    recognised as a customer credential and reached the anonymous path. On `/mcp` an `Authorization`
    header, when present, must now be exactly ONE header whose whole value is `Bearer <b64token>`
    (RFC 6750 §2.1: scheme case-insensitive, one SP, `[A-Za-z0-9-._~+/]+=*`, nothing else). A value
    starting with the DPoP scheme after any whitespace (Unicode included), or a valid Bearer from the
    customers realm, gets the 401 that points at `/customer/mcp`. Every other shape (comma lists,
    tabs, double or leading/trailing spaces, non-ASCII whitespace, `Basic` and other schemes) is
    400 `invalid_request`. Staff JWTs are base64url with dots, so they are inside the allowlist.
  - *Fail open on a read error or missing request.* An `Authorization` header that throws on read,
    and a hand-built endpoint with no injected request, are refused 400 instead of reading as
    "no credential". `httpRequest` is CDI-`@Inject`ed on every served request.
  - *Tests.* `McpStaffRootCredentialParsingTest` (each shape above, legit JWT and `bearer` controls)
    and two real-HTTP cases in `McpStaffRootCustomerCredentialIT` (`DPoP<TAB>x` → 401,
    `Bearer a, DPoP b` → 400). Each guard was sabotaged (missing request read as no header, b64token
    loosened to `.*`, DPoP match ASCII-only) and its tests went red.

- **2026-11-22 (ADR-0385 D3, #16667, review of #16672)** — The authorization server side of the
  customer connect. *Spoofing:* T-S3 (withheld code + 72 h passkey age, now enforced).
  *Elevation:* T-E5 (token exchange refused for and of MCP clients; Keycloak built-in executors bound
  per tier) and T-E6 (`party_id` admin-only). *Deployment:* the authenticator's server-level config
  (`KC_SPI_AUTHENTICATOR__OPENBANK_MCP_WITHHELD_CODE__*`) is wired in `keycloak.yaml`, its client
  secret from OpenBao `keycloak/keycloak-mcp` through the `keycloak-mcp-sca-client` ExternalSecret
  (optional ref; the factory logs a WARN at boot when unconfigured, and every connect is refused).
  The realm changes reach the live realm only by the owner step in
  `openbank-infra/gitops/components/keycloak/README.md` — import runs on cold start only, so until
  then nothing in this entry is live.
- **2026-11-22 (ADR-0383 D1, #16590)** — Staff `/mcp` refuses customer credentials outright.
  - *Elevation / tenant confusion (defence in depth).* Before this change a customers-realm token
    on the staff root was not refused at the door: with the channel on, only the RFC 8707 audience
    check ran there, and a customer token carries this server's audience, so the request was
    dispatched — and served as ANONYMOUS, because `CallerContextResolver` never resolves a
    customers-realm token (empty `tools/list`, `tools/call` denied before the PDP). Not
    exploitable, but the only barrier was one `null` return deep in the resolver. `McpEndpoint`
    now refuses on the staff root any token whose `iss` is the customers realm, and any
    `Authorization: DPoP` credential, with 401 pointing the client at `/customer/mcp`, regardless
    of channel state (reasons `customer_channel_off` / `customer_root_required`, layer `token`).
  - *Repudiation / audit flooding.* The credential is unverified at that point, so a refused
    `tools/call` spends the shared anonymous bucket before its audit row; beyond the budget only
    the throttle's once-per-window row is written. A request with more than one `Authorization`
    header is refused 400 (`invalid_request`), and a DPoP scheme in ANY of them counts.
  - *Tests.* `McpStaffRootCustomerCredentialIT` (channel off, shipped `mcp.customer.issuer`, two
    `Authorization` headers in both orders, metric, audit budget), `McpCustomerChannelIT` (channel on,
    both schemes, list and call), `McpCustomerTenantResolutionIT` (a token signed by the real
    customers-realm key on `/mcp`). With the refusal removed the first two went red (200, served
    anonymous); the third stays green either way, because the OIDC layer already refuses it (the
    customers tenant is path-bound to `/customer/mcp`, and the staff tenant rejects its `iss`).

- **2026-11-21 (ADR-0383 D4) — residual: the exchanged token's `act` claim is not asserted.**
  `KeycloakCustomerTokenExchange.validated` checks `sub` (the customer), `aud` (one downstream),
  `azp` (mcp-service's exchange client), the scope set and the lifetime, but not an RFC 8693 `act`
  claim naming mcp-service as the actor. Keycloak's standard token exchange (V2) does not emit `act`,
  so requiring it would refuse every real exchange; `azp` is today's actor binding. Revisit if the
  realm gains actor-token support or a downstream starts reading `act`.
- **2026-11-21 (ADR-0383 D5, #16847, owner decision)** — The edge hop is trusted by its mTLS
  IDENTITY, never by source IP; `quarkus.http.proxy.trusted-proxies` stays unset.
  - *Spoofing (forwarded headers).* `ProxyForwardingGuard` now refuses to boot with ANY
    `quarkus.http.proxy.*` switch on or ANY trusted-proxies value, so no listener rewrites scheme,
    host or address from a peer header. The customers tenant's DPoP `htu` check needs none: on the
    8443 listener (client auth REQUIRED) the request absolute URI is `https://<Host>/customer/mcp` with the Host the
    edge passes through, and on plain 8150 it is `http://…`, which no published-resource proof
    matches. Proven by `McpGatewayMtlsIT` over real TLS 1.3: admitted via the gateway certificate;
    refused at the tenant over plain HTTP (forged `X-Forwarded-Proto` ignored) and with another Host.
  - *Spoofing (client address).* `ClientAddressResolver` honours the one edge header only over TLS
    from a verified leaf whose DNS SAN equals `mcp.edge.gateway-identity` (CN never consulted).
    Every fleet workload holds an `openbank-ca` certificate, so the SAN pin is the control, not the
    CA: `McpGatewayMtlsIT` shows another trusted certificate (whose CN even names the gateway) and
    plain HTTP leave the key on the TCP peer. The guard refuses the header without the pin or
    without client-auth REQUIRED (one removal test per element).
  - *Spoofing (authentication scheme).* Measured: with the listener-parity permission
    `auth-mechanism: bearer` on the customer path, an `Authorization: DPoP` request was never
    authenticated by the customers tenant (a proof for a foreign `htu` answered 200, as an
    anonymous caller). The customer channel therefore has its OWN root, `/customer/mcp`, with an
    `auth-mechanism: DPoP` permission and `tenant-paths` binding it to the customers tenant; staff
    `/mcp` keeps `bearer`. `CustomerMcpEndpoint` serves the root (and its RFC 9728 metadata) only on
    the mTLS listener, `ProxyForwardingGuard` refuses the channel without client-auth REQUIRED, a
    non-customer token on the customer root is refused (never dispatched), and the staff path
    refuses a customers-realm token. `McpGatewayMtlsIT` holds the must-reject cases (foreign `htu`,
    foreign Host, plain HTTP) and staff Bearer on `/mcp`.
  - *Tampering (path spelling).* Review of #17004: the customer branch was chosen by the RAW path,
    so `/customer/mcp;a=b`, `/customer/%6dcp`, `/customer/./mcp` or `/x/../customer/mcp` reached
    `CustomerMcpEndpoint` and took the staff dispatch (no DPoP challenge, customer refusal, throttle
    or audit). The branch is now decided by which resource matched (`McpEndpoint.handleCustomer`),
    and `McpPathCanonicalFilter` answers 400 to any non-canonical raw path before authentication on
    both listeners. `McpGatewayMtlsIT` holds every spelling as a must-reject case.
  - *Residual.* `openbank-ca` is a ClusterIssuer: whoever can create a cert-manager `Certificate`
    can mint the gateway SAN. Constrain issuance of that SAN (approver policy or a dedicated
    issuer) before go-live. The gateway presents its client certificate to every
    BackendTLSPolicy backend (option A, owner decision); that discloses no key.
- **2026-11-21 (#16847 follow-up)** — Subject-less audit key budget per allocation and region.
  - *Repudiation / audit suppression.* The per-minute cap of 4096 distinct subject-less keys could
    be spent by one party holding one IPv6 allocation (a /48 is 65 536 /64s), pushing every other
    subject-less caller into the shared overflow key for the rest of the minute. Distinct keys are
    now capped per allocation (IPv6 /48, IPv4 /24: 64 keys, then one shared allocation key) and per
    IPv6 region (/32: 256 keys including allocation keys, then one shared region key). One
    allocation therefore admits at most 65 keys a minute and one /32 at most 257, so filling the
    global budget needs at least 16 distinct /32s (RIR scale) or a spread of IPv4 /24s. The first
    global overflow per minute is logged; every refusal remains metered regardless of key.
  - *Tradeoff.* Once an allocation (or region) has spent its slots, a new neighbour inside it
    shares that allocation's (region's) key, and so its one-row-per-minute refusal audit window,
    for at most 60 s. Neighbours admitted earlier in the minute keep their own key. The key feeds
    only that audit window, not a rate limit.

- **2026-11-20 (ADR-0383 D5, #16847)** — Forwarded-header trust and the subject-less audit key.
  - *Spoofing (forwarded headers).* Quarkus forwarding (`quarkus.http.proxy.*`) is needed behind the
    edge because quarkus-oidc's bearer authentication mechanism (a framework class, not code in this repo; customers tenant,
    `authorization-scheme: DPoP`) compares `htu` with the request's absolute URI — measured; this service's own
    admission compares with the static published resource, but with
    `trusted-proxies` unset Quarkus applies `X-Forwarded-*` from ANY peer — a direct caller could
    then choose its own scheme, host and client address. `ProxyForwardingGuard` refuses to boot with
    forwarding on and no list (or a trust-all one). Superseded the next day by the mTLS-identity
    entry above (2026-11-21), whose `McpGatewayMtlsIT` replaces the IP-trust ITs.
  - *Repudiation / audit suppression.* A refusal with no token subject was keyed by one shared
    value, so one caller's flood could spend every subject-less caller's audit window. It is now
    keyed by `ClientAddressKey` (HMAC under a per-boot key; IPv4 /32, IPv6 /64 so rotation inside one
    /64 shares a window; strict literal parse; distinct keys capped per minute) over
    the address `ClientAddressResolver` picks: the raw TCP peer, or one edge-set header from a
    trusted peer — never Quarkus' leftmost `X-Forwarded-For`, which an appending edge leaves under
    the client's control (IT: an appended forged entry does not become the key).
  - *Residual / route prerequisite.* Forwarding stays OFF in the deployment until the route PR, which
    must narrow `trusted-proxies` to the gateway's source (not the pod CIDR), make the edge overwrite
    client-supplied `X-Forwarded-*`, set the edge client-address header, and restrict the
    NetworkPolicy on `:8150` to the gateway and known callers.

- **2026-11-19 (ADR-0383 D6, #16590) — customer `propose_payment` writes a PENDING proposal to
  delegation-service's store. Still behind `mcp.customer.enabled` (off).** This is the customer
  channel only; the staff-path `propose_payment` still refuses (T-E4 unchanged).
  - *Elevation of privilege.* The tool is offered and run only for the DEBTOR account an active
    `MCP_CLIENT` grant carrying `ACCOUNT_PROPOSE_PAYMENT` covers; a read grant cannot propose, and a
    propose grant over one account cannot propose from another. `mcp_rest_ext.rego` now admits
    exactly one write on the channel, `propose.payment` with that capability; `approve`,
    `execution` and every other action stay under the channel veto (`opa test` 17/17; removing the
    tool entry, the capability check or the action match each turned the matching tests red). The
    store call (`PaymentProposalStoreClient`) declares ONE method, create, on mcp-service's own
    `m2m` identity, which the store admits to create and to nothing else; any answer other than
    `PENDING` is refused, fail closed. No customer token is exchanged for it.
  - *Tampering.* Arguments are model-composed and validated before any call to the store's own
    rules: IBAN via `McpInputBounds.requireIban`, amount a plain decimal string (positive, <= 2
    decimals, <= 1,000,000.00, never a JSON number), currency CZK|EUR fixing the rail (CZK needs a
    CZ IBAN), creditor name and reference trimmed, NFC-normalised and REFUSED on Cc/Cf/Zl/Zp,
    markup brackets, backtick or `://`; unknown arguments refused.
  - *Repudiation / duplicates.* The store's `Idempotency-Key` is derived, never client-supplied:
    SHA-256 over (party, `azp`, `cnf.jkt`, token `sid`, the model's `clientRequestId`), so an MCP
    retry replays the first answer and two clients or customers cannot share a key. Every call is
    audited and metered on `openbank_mcp_tool_calls` (unknown tool names as the unmapped label).
  - *Information disclosure.* The result is the proposal id, PENDING, amount, currency, masked
    creditor IBAN and a bank-rendered summary containing no model-composed text, through
    `McpPiiMasker` then `McpUntrustedData.wrap`. Store refusals map to fixed texts (limit reached,
    request id reused, in progress, invalid instruction, not permitted); a 403 also drops the cached
    grant, so a revoked or expired grant is re-resolved on the next call.
  - *Contract.* Consumer pact for `POST /api/v1/payment-proposals` (literal path, 201 PENDING and
    403 no grant) in `pacts/openbank-mcp-service-openbank-delegation-service.json`. The
    delegation-side HTTP replay covers proposal creation and refusal alongside grant resolution.

- **2026-11-18 (ADR-0383 D3/D4, #16590) — customer grant lookup, exchanged downstream tokens, read
  tools. Still behind `mcp.customer.enabled` (off).** An admitted customer now sees only the read
  tools its active `MCP_CLIENT` grants allow, and every read runs on the customer's own exchanged
  token.
  - *Spoofing / confused deputy.* The grant is found from `(party_id of the verified token, admitted
    azp, verified cnf.jkt)` — `CustomerGrantCache` -> `DelegationGrantSource` ->
    delegation-service `POST /api/v1/delegations/mcp-client/resolve`. Nothing a client sends is a
    lookup key, and the party is part of the key, so another customer's grant to the same client
    never matches (unit + IT `a foreign party's grant ...`). A token with no UUID `party_id` is
    refused before any lookup.
  - *Elevation of privilege.* `CustomerReadTools` offers `list_accounts` / `get_balance` /
    `list_transactions` only for capabilities a grant carries, and checks the ONE named account
    against the grant carrying that tool's capability before anything else happens; a balance grant
    cannot list transactions or reach another account, and no write tool exists on this channel
    (`propose_payment` refused). The exchanged token asks the customers realm for ONE audience and ONE
    read scope (`DownstreamAudience`), and its answer is checked: lifetime <= 60 s, `sub` == the
    customer, `aud` contains the requested service. The customer-path REST clients
    (`CustomerDownstream.kt`) carry no OIDC client filter, so mcp-service's own credential cannot
    ride on them. The grant LOOKUP uses mcp-service's own `m2m` identity, which delegation-service
    admits for that one action only (rego reason `mcp-client-grant-resolve` + an in-code client-id
    check); it reads no account data.
  - *Policy (D4.1).* After the grant check and before any exchange, every customer call is
    evaluated by mcp-service's own PDP as principal HUMAN (id = customer `sub`, not a service
    account, no roles) with `channel = mcp-customer`, `client = azp`, the tool and the grant's
    server-resolved capabilities. `mcp_rest_ext.rego` admits only the three read tools under their
    own action and matching capability, and VETOES every other action on the channel, so no base
    reason (e.g. `operator-read-any`) can widen it. Deny or PDP error is a refusal. Proven by
    `opa test` (10/10; veto and grant check each removed went red) and by `opa eval` against the
    regenerated `mcp-opa-bundle` (must-ALLOW balance read; must-DENY scope escalation,
    propose_payment, operator on the channel; control operator read off-channel ALLOW).
  - *Unbound exchanged token (accepted, ADR-0383 D4).* The downstream token is a plain bearer, not
    DPoP-bound, and it DOES leave mcp-service: it is sent as `Authorization: Bearer` to exactly one
    downstream service over in-cluster HTTP. It never leaves the cluster and is never returned to the
    MCP client. D4 accepts the missing sender binding on that basis ("in-cluster only ... does not
    need to be sender-constrained"). The protections are: lifetime `expires_in` <= 60 s (refused
    otherwise); a single audience, the one service it is sent to; a single read scope; `azp` = the
    exchange client (each verified on the answer, any failure refuses); and the downstream PDP's
    read-only rule. A capture from pod memory, from the receiving service, or from in-cluster traffic
    therefore yields at most 60 s of reads of that one service for one customer. Residual: the
    in-cluster hop is plain HTTP unless the mesh/mTLS posture of the target namespace encrypts it.
  - *Fail-closed.* Lookup error, empty grant set, exchange failure (including an unconfigured
    exchange secret), a downstream 401/403 and a downstream 5xx/timeout are all refusals, audited `DENIED`
    (`UNAVAILABLE` for lookup/exchange outages). The grant lookup TTL defaults to zero so the next
    call sees a committed revocation. With a nonzero diagnostic TTL only positive lookups are cached
    for up to `mcp.customer.grant-cache-ttl` (capped at 30 s in code) and never past a grant's
    `validTo`; a downstream 401/403 or 5xx/timeout drops the entry. With the channel OFF, a customers-realm token (and any `DPoP`-scheme credential) is refused 401 `invalid_token` and never served on the anonymous path. At the cache cap the oldest stored entry is evicted (insertion order, O(1)); storing a key already held evicts nothing; expired entries are dropped when next read. A lookup in flight across an invalidation does not re-cache its answer. Results pass `McpPiiMasker` then
    `McpUntrustedData.wrap`, as on the staff path.
  - **Residual, and why the channel stays off:** (1) downstream services validate only the STAFF
    realm today, so an exchanged customers-realm token is refused by balance/transaction-service until
    they accept that issuer and enforce party ownership on it (ADR-0383 P5) — the path fails closed
    until then; (2) Keycloak standard exchange of a DPoP-bound subject token has not been run against
    a live Keycloak of the deployed version (realm template checked statically only); (3) a nonzero
    diagnostic cache TTL delays revocation and must stay disabled for customer use; (4) no deployed route, netpol or env wiring for
    delegation-service / the token endpoint is added; the exchange secret must be provisioned in
    OpenBao (`keycloak/mcp-service-customer-exchange`) before the channel can work.
    Rollback: revert the commit; nothing is persisted.
- **2026-11-18 (ADR-0383 P4 / D5.3, #16590)** — Shared, fail-closed customer-channel state. Still
  **off by default**; this closes the first residual of the entry below.
  - *Replay across replicas / pod rolls.* `mcp.customer.shared-store=valkey` puts the DPoP `jti`
    claim in Valkey (`SET key 1 NX PX <proof window>`), so a proof admitted by one replica is
    REPLAYED on every other one and a roll does not reopen the window. The non-mutating peek is a
    real `EXISTS` (not the interface's `false` default), so the "refuse a replay before spending the
    victim's budget" ordering holds across pods too. Proven by `ValkeyCustomerStoresIT` with two
    independent clients against a real Valkey: cross-pod replay refused, 50 concurrent claims of one
    `jti` across two pods admit exactly one.
  - *Budget multiplication (D5.3).* The proof-key, per-client and per-(subject, client) windows move
    to shared Valkey counters (one Lua `INCR` + first-hit `PEXPIRE`, so no counter is left without a
    TTL); N replicas no longer give a caller N budgets. Same IT: the limit holds for the sum of two pods.
  - *Fail closed.* Any store failure (refused, timeout after `shared-store-timeout-ms`, odd reply) on
    the peek, the claim or a budget is a **503** and never an admission; there is no per-pod fallback.
    The Valkey client has no readiness check, so the outage is contained to the customer channel.
  - *Boot gate.* With the channel on, `shared-store` must be set; in a production launch
    (`LaunchMode.NORMAL`) anything but `valkey`, or `valkey` with no Redis client, refuses to boot.
    `memory` remains for tests and dev.
  - *Review hardening (#16791).* A malformed reply is parsed inside the same guard, so it is a 503, not
    a 500; the boot gate checks `quarkus.redis.hosts` itself (not only that a client bean resolves) and
    is proved on the built fast-jar in `LaunchMode.NORMAL` (`CustomerChannelBootGuardIT`, with a
    positive control that must boot); a hung Valkey (container paused) answers 503 within the bounded
    command timeout, capped at 2000 ms; subject, proof key and client id are HMAC-SHA-256'd with a
    deployment secret in every key name (required with `valkey`, boot refused otherwise).
  - *Operational requirement.* The replay Valkey MUST run `maxmemory-policy noeviction`: an evicting
    policy silently drops live `jti` entries and reopens their replay window with no error anywhere.
  - *Store hardening (#16791, GitOps).* `mcp-valkey` (namespace `platform`) runs with `requirepass`
    from the `mcp-customer-store-valkey` Secret. It and the key HMAC secret
    (`mcp-customer-store-hmac`) are generated in-cluster by two independent ESO `Password`
    generators (`CreatedOnce`, 48 letters/digits each; no OpenBao copy, no human holder). The ref is
    required: without the Secret the pod does not start, so it never runs unauthenticated, `--maxmemory 128mb` under a 192Mi limit and an explicit
    `--maxmemory-policy noeviction`; its ingress admits only `app.kubernetes.io/name: mcp-service`
    (`open-bank.tech/same-namespace-ingress-from`, derived by `gen-network-policies.py`), not every
    co-tenant of the shared namespace. mcp-service refuses to boot with `valkey` and no password.
  - **Residual — store restart or OOM-kill.** The store is ephemeral (no RDB/AOF). A restart, a
    reschedule or an OOM-kill empties it: every proof still inside its window (up to 365 s) can be
    replayed once more, and every D5.3 budget restarts from zero. `noeviction` and the maxmemory
    margin make OOM unlikely but not impossible. Prod needs an operator-managed HA store with
    persistence; until then this is accepted for the sandbox and called out in 05-operations.
  - *Password handling (#16791 reviews).* The Valkey container gets its two passwords as FILES from
    the Secrets (secret volumes are tmpfs, mode 0440 to the pod's fsGroup), never as env vars or argv —
    both of which land in the OCI runtime spec (`crictl inspect`). A POSIX-sh wrapper reads the files,
    refuses to start unless each is non-empty and `[A-Za-z0-9]+`, writes the config into a
    memory-medium emptyDir and `exec`s `valkey-server`. Measured on the digest-pinned 9.1.3 image:
    `/proc/1/cmdline` = `valkey-server *:6379`; no password in `/proc/1/environ` or the container's
    configured env; unauthenticated `PING` → `NOAUTH`; a password with `"` or a space → refuses (exit 1).
  - **Residual:** the exporter's `metrics` password is still an env var in ITS container spec:
    redis_exporter's `--redis.password-file` only feeds the multi-target `/scrape` map (measured: a
    plain file is rejected as non-JSON; a JSON map leaves the main target `NOAUTH`), and the URI form
    would put it in argv. That user can run only introspection commands (no data, no `CONFIG GET`),
    and the slowlog is off. mcp-service's `QUARKUS_REDIS_PASSWORD` and HMAC secret are env vars in its
    own pod spec, like the fleet's other Quarkus secrets.
  - *Images* are pinned by tag and digest (valkey `9.1.3-alpine@sha256:bf09d58e…`, redis_exporter
    `v1.92.1@sha256:7fbc93d3…`). The Password generators and ExternalSecrets sync at wave `-1`, before
    the workloads that mount them.
  - *Exporter hardening (#16791 security review).* The `redis_exporter` sidecar never holds the
    store password. It logs in as a dedicated `metrics` ACL user with its own generated password
    (`mcp-customer-store-metrics`), allowed only `PING`, `INFO`, `CLIENT SETNAME`, `SLOWLOG
    GET|LEN`, `LATENCY LATEST|HISTOGRAM`, `COMMAND INFO` and `COMMANDLOG LEN`, with no key or channel access
    (measured: with exactly that set, `ACL LOG` stays empty across scrapes; without the last two it
    records a denial on every scrape and those metrics silently go missing). Its multi-target
    `/scrape?target=` endpoint is disabled (`--disable-scrape-endpoint`), so reaching :9121 cannot point
    the exporter and its credential at another host. `CONFIG GET` is deliberately NOT granted, and the
    exporter runs `--config-command=-`: measured on 9.1.3, `CONFIG GET requirepass` returns the
    default user's password in clear to any user allowed `config|get`. Measured as `metrics`: `GET`,
    `SET`, `DEL`, `FLUSHALL`, `KEYS`, `EVAL`, `CONFIG GET|SET` and `ACL LIST` all answer `NOPERM`,
    while `redis_up 1` and `redis_memory_max_bytes` are still exported and `/scrape` answers 404.
    Port 6379 admits only mcp-service pods, and only on 6379; port 9121 admits only the observability Prometheus pods (`open-bank.tech/metrics-ingress-prometheus-only`,
    derived by `gen-network-policies.py`), not admin-ui. The same exporter pattern on `copilot-redis`
    is a fleet follow-up.
  - *Slowlog disabled.* The store runs `slowlog-log-slower-than -1`, so no command — and none of its
    arguments (`jti`, HMAC'd key names) — is ever recorded. Measured: after a deliberately slow `EVAL`
    returning a marker string, `SLOWLOG LEN` is 0 and the `metrics` user's `SLOWLOG GET` contains no
    marker; with slowlog enabled the same run logs it and the marker is readable. `slowlog|get` stays
    granted only because redis_exporter v1.92.1 calls it on every scrape with no flag to stop it
    (dropping it fills `ACL LOG` with a denial per scrape); it now reads an empty log. `CLIENT LIST`
    is not granted (the exporter only calls it with `--export-client-list`, which stays off).
  - *Detection.* `MCPValkeyMemoryNearMax` / `MCPValkeyMemoryFull` (redis_exporter sidecar,
    promtool-tested including the cold value) warn before `noeviction` turns a full store into a
    channel-wide 503.
  - **Residual:** replication is asynchronous: if the primary
    acknowledges a `SET NX` and fails over before the replica has it, the promoted replica has no
    entry and that one proof can be accepted once more (a single replayed proof, still within its
    `iat` window and still bound to the token's key). `PX` is relative, so clock OFFSET between pods
    and Valkey does not matter; only clock RATE drift does, and the entry's 365 s life against the
    verifier's 360 s acceptance span (`maxAge` 300 s + `futureSkew` 60 s) leaves a 5 s margin that
    any realistic drift stays far inside. No per-key replay quota in Valkey (memory is bounded by the
    proof-key bucket that runs first).
- **2026-11-18 (ADR-0383 D1/D2, ADR-0385 D1, #16590)** — Customer-channel admission, **off by
  default** (`mcp.customer.enabled`). What it adds, and what it deliberately does not:
  - *Spoofing / tenant confusion.* The customers realm is a separate OIDC tenant selected by `iss`
    (`quarkus.oidc.customers`, issuer resolution enabled only with the channel). A customers-realm
    token is decided ONLY by `CustomerChannelAdmission`; `CallerContextResolver` returns null for it
    before the `agent:` prefix or OBO path is inspected, so a customer token shaped like an agent
    token cannot reach a staff charter's tools (unit + IT, both proven red with the guard removed).
    A staff-realm token is never admitted as a customer, whatever its `azp`/`cnf`.
  - *Token theft / replay.* Admission requires `aud` = this server, an `azp` declared AND enabled in
    the MCP client allowlist (tier from the allowlist, never from the token), `cnf.jkt`, the `DPoP`
    authorization scheme, and an RFC 9449 proof verified by the ADR-0320 P2 primitive (`htm`, `htu`
    against the allowlist's published resource rather than the request's `Host`, `iat` window,
    `ath`, `cnf.jkt`, `jti` replay). Tier H bearer tokens are NOT admitted in this slice.
  - *Discovery.* RFC 9728 metadata at `/.well-known/oauth-protected-resource[/mcp]`, 404 while off;
    an unauthenticated call gets 401 with `resource_metadata`. Values come from config, never the request.
  - *Boot gate.* `CustomerChannel` is `@Startup`: with the channel on, a missing issuer or a
    missing/malformed allowlist stops the service from starting.
  - *Review hardening (same PR).* The fleet NUL guard now runs before the customer branch and the
    audience check, so no path echoes a U+0000 id. Admission order (re-review of #16721): token
    and structural checks, then the STATELESS proof check (`DpopProofVerifier.check`: signature over
    the header key, `htm`, `htu` against the published resource, `iat`, `ath`, proof key == `cnf.jkt`),
    then a non-mutating replay peek (a captured proof replayed on a victim's token is refused
    REPLAYED before any budget, so replays cannot drain the victim's proof-key bucket; two
    CONCURRENT copies of one proof can both pass the peek and both spend, and the claim still
    admits only one), then — only for a proof signed by the token's own key — the proof-key bucket and a coarse
    per-client cap (minute-only), then the replay claim, then the per-(subject, client) window. A
    missing, duplicate or unparseable proof, a proof from another key, or a junk proof carrying a
    victim's PUBLIC key therefore spends no one's budget and writes nothing; raw volume before that
    point is the edge's per-IP limit (D5.1), since nothing stateful has happened yet. Refused customer calls write at most one audit row per
    subject per minute, and that audit-window check also prunes closed windows, at most one full scan per minute. Boot also refuses an allowlist whose `audience` differs from the staff path's, and
    any parser failure (including a YAML syntax error). Measured with real signed tokens: Quarkus's
    issuer resolver returns no tenant for an unknown `iss` and the request FALLS BACK to the default
    (staff) tenant, so a foreign or forged issuer is stopped by that tenant's signature and issuer
    checks — with the issuer check off (`token.issuer=any`) a third-issuer token signed by the staff
    key is accepted. Production relies on the discovered issuer for that check.
  - **Residual, and why the channel must stay off:** the replay store is the single-pod
    in-memory one (ADR-0383 P4 — a pod roll reopens the replay window; superseded by the shared
    Valkey store above when `shared-store=valkey`); no server nonce; the
    D5.3 windows are the in-process limiter, not the shared Valkey bucket (likewise superseded); the per-client cap is shared
    by all of a client's customers and is spent only by proofs signed by a key some token of that
    client is bound to, so exhausting it needs many valid tokens (a coarse cap by design, sized well
    above one customer's budget); the tenant-level DPoP check compares `htu` with the request URL,
    so the edge must forward the external scheme and host (proved end to end in
    `McpCustomerTenantResolutionIT` with `X-Forwarded-*`); an admitted customer reaches NO
    tool (empty `tools/list`, refused `tools/call`), because the `MCP_CLIENT` grant lookup (P2/D3)
    and downstream party enforcement (P5) do not exist; the customers tenant config is not yet
    verified against a live Keycloak (P3).

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
- **2026-09-21** — **Own machine identity for the charter-gated read ports (#10486 batch 7).** `TransactionServiceClient`, `SepaPaymentServiceClient` and `DomesticPaymentServiceClient` now mint from a NAMED oidc-client `m2m`, Keycloak client `openbank-mcp` (`ROLE_API` only). transaction-service grants it `transaction.list` (mcp-anonymous holds `query.transaction.readonly`). The payment-confirmation reads get **no** grant: no charter holds `query.payment_confirmation.readonly`, so the tool was already refused at the MCP gate. `McpMachineGrantCharterAlignmentTest` pins both facts. **STRIDE-S:** a new credential at Vault KV `keycloak/mcp-service`, projected by `mcp-service-m2m-oidc`, env ref `optional: false`. The account, balance, statement and consent clients stay on the shared client. Rollback: revert the commit.

- **2026-10-31** — **account-service reads now use the generated client (#13169, ADR-0319).** The hand-written `GET /api/v1/accounts/iban/{iban}` and `GET /api/v1/accounts/{accountId}` interface is replaced by `AccountServiceClient : AccountsApi`, generated from account-service's own `openapi.yaml` (`openbank-client-account`), read through the shared `AccountClient` facade in `AccountDirectory`. **No trust-boundary change:** same routes, same `account-service` config key and URL, same M2M OIDC filter, same synthetic-taint filter, no new header; the consent gate still runs before any account read and its scope intersection is untouched. A 404 still denies the tool call (`NotFoundException`), any other status or failed call still propagates as an error, and nothing in the seam swallows an exception (a cancellation passes through unchanged). **Residual risk:** `id` and `partyId` now bind as UUIDs, so a non-UUID value from account-service fails the read instead of being passed along as text. `list_accounts` now serialises the typed account, so its output carries exactly the fields account-service's spec declares (null-valued ones included) rather than the raw upstream document; a field the spec does not declare no longer reaches the MCP client. Rollback: revert the commit.

- **2026-11-17** — **Token audience enforced on `/mcp` (#16622, ADR-0383 D1).** Before this change only the OBO path checked `aud`; an agent token that Quarkus OIDC accepted for the realm reached `CallerContextResolver` whatever resource it was issued for, which is token passthrough. `McpEndpoint` now answers 401 (`invalid_token`) on every method when a presented token's `aud` does not contain `openbank-mcp-service`, and `CallerContextResolver` refuses such a token on the agent path as well, so a call reaching the resolver by another route still fails closed. Refused tool calls are audited as DENIED. Anonymous calls keep their existing tool-less answer. Not done with `quarkus.oidc.token.audience`, because the tenant also serves `/api/v1/mcp/sessions`, which receives the operator's own token. **Residual risk:** no realm client mints `agent:` tokens yet, so the agent branch has no live caller; a future issuer must add an `openbank-mcp-service` audience mapper. An unreachable issuer still yields 500 for a presented token (fail-closed). Rollback: revert the commit.

- **2026-11-17** — **JSON-RPC input hardening (#16628).** Five input bounds the MCP fuzz suite marked as deviations now hold at the endpoint. **STRIDE-T/D/I:** (1) a request whose decoded body carries U+0000 anywhere (keys, values, method, tool name, at any depth) is refused with HTTP 400 and JSON-RPC `-32602` before dispatch, so no tool, consent call or audit write sees it (only the bounded counter `openbank.mcp.input_refused{reason="nul"}` ticks); the fleet NUL guard (#5913) hooks the String deserializer, which a `JsonNode`-bound body never uses, hence a tree scan in `McpInputBounds`. The refusal is logged without the payload and is deliberately not audited (an unauthenticated flood must not become audit writes). (2) The tool name, unknown method and invalid `paymentId` are no longer reflected beyond 64 characters, so a response cannot grow with the input. (3) `list_transactions.limit` must be an integer in 1..200 (parity with transaction-service) (default 50 when absent) or the call is `-32602`. (4) Audit `argument_keys` is bounded to 32 keys of 64 characters each. (5) `get_balance`, `list_transactions` and `get_statement` require the `accountId` in canonical form (uppercase, no spaces; refused, never normalised, because consent compares the exact IBAN) and pass libs-domain `Iban.isValid` (mod-97) before the consent-service call. Rollback: revert the commit.

- **2026-11-17** — **ADR-0383 D7 tool-result sanitisation (#16620, refs #16590).** A new
  `McpToolResultSanitiser` runs at the existing single seam in `McpToolRegistry.call`, after
  `McpPiiMasker` and before `McpUntrustedData.wrap`. It covers every string value and object key in
  a tool result:
  - Invisible code points are removed and the input cut to 560 surviving code points in one linear
    pass, and all patterns are linear. 1 MiB of each
    pathological pattern is processed in well under 200 ms, against about 30 s for one before.
  - Strings are NFKC-normalised and lose control, format, tag and separator code points.
  - Percent and HTML-entity encodings are decoded first, to a fixpoint of at most 3 rounds. Link and image markup, HTML and URLs
    (including `www.` and protocol-relative forms) are then replaced with a placeholder.
  - IBANs (registry country and length, any grouping) and phone numbers (3-3-3, 3-2-2-2, `+CC`)
    inside free text are masked, and so is an IBAN fragment left at a cut. Only allow-listed
    system id fields holding a UUID, and amount fields holding a plain decimal, are exempt, so ids
    chain unchanged while a payer-controlled field such as `endToEndId` is still masked.
  - Object keys are sanitised and masked. A key that cleans to another entry's name is dropped.
  - Marker look-alikes are neutralised on a UTS #39-style skeleton covering case, separators,
    combining marks, digits between words, and Cyrillic, Greek, Armenian, Cherokee, Lisu,
    small-capital and fullwidth forms.
  - Strings are capped at 140 characters.
  - `logoUrl` is kept only as `https` on the bank CDN host allow-list and is dropped otherwise.

  This narrows T-I3 and T-I4 (exfiltration through rendered URLs, marker breakout and oversized
  instructions). It does not change the security boundary, which is still D4, D6 and OPA. The
  layer-4 red-team pack closes 52 of its original 53 recorded gaps, and its 17 added payloads all
  pass. **Residual:** a natural-person name embedded in a free-text field the masker does not
  redact wholesale (for example a payment reference) still passes, because a name has no shape to
  match. It stays in `known-gaps.json`. The marker skeleton is a vendored confusables subset for the
  marker's own letters, not the full UTS #39 table (ICU4J is not on the runtime classpath, and no
  dependency was added for it), so a look-alike outside the subset still passes. IBAN detection deliberately skips mod-97: an RF
  reference passes it and a mistyped IBAN fails it. Rollback: revert the commit. The red-team
  ratchet then fails until `known-gaps.json` is restored.
- **2026-11-19** — **Caller-scoped account handles (#16678, refs #16590).** Since #16644 the read
  tools took the account IBAN as `accountId`, while the masker returns it as `****1234`, so a model
  could not chain `list_accounts` into `get_balance`. Results now carry `accountRef`, an HMAC-SHA256
  under a server key over (agentId, consentId, session id, IBAN), and the tools accept it.
  **STRIDE-I:** the IBAN stays masked towards the model (D7), and the ref names no account outside
  MCP. **STRIDE-S/E:** a ref is never decoded. It is resolved only by recomputing the refs of the
  caller's own consent-validated `list_accounts` for the caller's own scope. A token without a
  session `sid` gets no ref and has every ref refused, so the sessions of one agent cannot share refs.
  Each such case is counted (`openbank_mcp_account_ref_unavailable_total{reason="no_session"}`).
  A ref replayed by
  another agent, under another consent or in another session, or a tampered one, is refused with
  `-32602` before any read port is called. The consent gate in the port still applies to the
  resolved IBAN. The ref is attached after masking and sanitising, so no field is exempt from the
  sanitiser: an upstream `accountRef` carrying an IBAN is masked like any other value. **Key:** `openbank.mcp.account-ref.secret`, generated in-cluster by an ESO `Password` generator (48 characters, `CreatedOnce`, no holder outside the cluster), at least 32 bytes,
  checked at boot (`@Startup`); a set-but-short value refuses boot, an absent one falls back to a per-process random key (WARN + gauge `openbank_mcp_account_ref_ephemeral_key`), under which refs fail closed across pods/restarts (alert `McpAccountRefEphemeralKey`, warning, after 15m); never logged. A raw IBAN
  `accountId` is still accepted, because D7 minimises what the bank sends, not what the caller
  already holds. **Residual:** a leaked key lets someone forge a ref, but only for an IBAN they
  already know and only usable by that same caller, whose consent still bounds the read. Rotation
  invalidates all refs. Rollback: revert the commit (chaining breaks again, nothing else changes).
