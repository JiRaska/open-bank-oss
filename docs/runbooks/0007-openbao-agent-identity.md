# Runbook 0007 — OpenBao agent-identity PKI (ADR-0031 D3b)

Status: Draft (issuer half landed; consumer is PR5b)
Owner: Platform + Security
Related: ADR-0031 (AI agent governance — D3 verifiable identity), ADR-0017 (Vault/OpenBao secrets),
ADR-0034 (zero-standing-token), ADR-0099 (dynamic DB creds — the k8s-auth pattern reused here),
runbook 0005 (Vault→OpenBao), runbook 0006 (db-admin out-of-band bootstrap precedent).

## Why

The `/mcp` surface authenticates the *operator* (Keycloak bearer, `@RolesAllowed`) but the
`X-Agent-Id` header — which selects *which agent charter runs* — was forgeable. D3a (PR #2403)
bound the assertable agent id to the operator's verified roles (deny-by-default). D3b replaces the
header with a **cryptographic, short-TTL, per-run credential**: a client certificate whose CN is
the agent id, signed by an OpenBao-rooted CA. A compromised run can mint forged identities only for
the lifetime of its current cert (≤5 min), never indefinitely; the signing root stays in OpenBao,
never in the agent's reach (ADR-0017).

**Reuse, don't duplicate.** OpenBao already runs (runbook 0005) and already issues short-TTL
material via the Kubernetes-auth pattern (ADR-0099 dynamic DB creds). D3b adds a dedicated
`pki-agent` engine — **no SPIRE server, no second Vault/PKI**.

**Why PKI, not Identity-OIDC tokens.** The agent id varies *per run* and the cert CN is a
per-request parameter. An Identity-OIDC token only carries claims templated from the caller's
*static* entity metadata, so it cannot express the per-run charter. PKI fits; OIDC does not.

## What landed (this PR — issuer half, INERT)

- `gitops/components/openbao/openbao-agent-identity.yaml`: a dedicated `pki-agent` engine, an
  internal root CA (generated once, guarded), a `agent-run` issuing role (client-auth, `allow_any_name`
  for the CN, `ttl=max_ttl=300s`, `no_store=true`), and a narrow `agent-identity-issue` policy.
- A weekly `openbao-agent-identity-sync` CronJob (SA `openbao-agent-identity-admin`) that re-asserts
  the config idempotently — same shape as the Tier-1 db-rotation sync.

It is **inert**: the `agent-identity-issue` policy is **not bound to any Kubernetes-auth role**, so
nothing can call `pki-agent/issue/agent-run` yet.

## Out-of-band bootstrap (one-time, operator)

Mirrors the `db-admin` bootstrap (runbook 0006). The sync CronJob logs in as the
`agent-identity-admin` k8s-auth role, which must exist first:

```sh
# Narrow admin policy for the sync job: manage ONLY the pki-agent engine + the issue policy.
bao policy write agent-identity-admin - <<'EOF'
path "sys/mounts/pki-agent"      { capabilities = ["create","read","update"] }
path "sys/mounts/pki-agent/tune" { capabilities = ["update"] }
path "pki-agent/*"               { capabilities = ["create","read","update","list"] }
path "sys/policies/acl/agent-identity-issue" { capabilities = ["create","update","read"] }
EOF

bao write auth/kubernetes/role/agent-identity-admin \
  bound_service_account_names=openbao-agent-identity-admin \
  bound_service_account_namespaces=vault \
  token_policies=agent-identity-admin \
  ttl=10m
```

## Consumer half (PR5b — design for review)

PR5b makes the agent runtime an active OpenBao client and verifies the cert:

1. **Dedicated identity for the minting workload.** agent-service today runs under the `default` SA
   in `platform`. PR5b creates a dedicated SA and binds the consumer k8s-auth role to it
   (least privilege — `default` must not be able to mint agent identities):
   ```sh
   bao write auth/kubernetes/role/agent-service \
     bound_service_account_names=agent-service \
     bound_service_account_namespaces=platform \
     token_policies=agent-identity-issue ttl=10m
   ```
2. **Per-run mint.** The run initiator authenticates to OpenBao (k8s auth) and calls
   `pki-agent/issue/agent-run` with `common_name=<agent-id>`, receiving a ≤5-min client cert.
3. **Verify at `/mcp`.** McpEndpoint validates the cert against the `pki-agent` CA and reads the
   agent id from the CN — replacing the `X-Agent-Id` header. The D3a role-binding (#2403) stays as a
   defense-in-depth backstop.

### ⚠️ Open decision (resolve before PR5b)

**Who mints the cert, and how is it presented to `/mcp`?** `/mcp` is called by *external* MCP clients
(the admin-ui BFF for `ui-assistant`; a developer agent runtime), not by agent-service itself — the
in-process loop calls the tool registry directly. So the minting + presentation belongs to the
**caller**:

- **Option 1 — mTLS:** the caller presents the OpenBao-issued cert as a client cert; the gateway/
  service terminates mTLS and McpEndpoint reads the verified CN. Strongest, but needs mTLS plumbing
  on the `/mcp` ingress path.
- **Option 2 — bearer + proof-of-possession:** the caller sends the cert + a signature over the
  request in a header; McpEndpoint verifies chain + PoP. No mTLS, more custom code.
- **Coupling to D6:** a fully self-minting *agent workload* identity only exists once the reasoning
  loop runs as a separate workload (D6, Temporal). Until then D3b hardens the **external** `/mcp`
  callers; the in-process path keeps the trusted in-process identity.

Recommendation: start with **Option 2 for the BFF→/mcp path** (no mTLS infra), keep the D3a binding,
and revisit mTLS + workload identity when the loop moves onto Temporal (D6).

## Verify

```sh
bao read pki-agent/cert/ca                       # CA present
bao read pki-agent/roles/agent-run               # max_ttl 300s, client_flag true
# Smoke (after PR5b grants a consumer): issue + inspect TTL/CN
bao write pki-agent/issue/agent-run common_name=ui-assistant ttl=300s
```

## Rollback

`bao secrets disable pki-agent` removes the engine and every (ephemeral, `no_store`) cert. Safe while
inert — no consumer depends on it until PR5b.
