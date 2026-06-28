# Runbook 0007 — OpenBao agent-identity PKI (ADR-0031 D3b)

Status: Draft (issuer + consumer implemented; e2e-verified in sandbox 2026-06-28)
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
path "auth/kubernetes/role/admin-ui-mcp"     { capabilities = ["create","update","read"] }
EOF

bao write auth/kubernetes/role/agent-identity-admin \
  bound_service_account_names=openbao-agent-identity-admin \
  bound_service_account_namespaces=vault \
  token_policies=agent-identity-admin \
  ttl=10m
```

## Consumer half (PR5b-2 — implemented)

The minter is the **admin-ui BFF** (the `/mcp` caller for the `ui-assistant` chat), NOT agent-service:
`/mcp` is called by external clients; agent-service's in-process loop calls the tool registry
directly. So minting + presentation belong to the caller. Chosen scheme: **bearer +
proof-of-possession** (no mTLS ingress plumbing); the D3a role binding stays as a defense-in-depth
backstop; mTLS / a self-minting agent workload is revisited when the loop moves onto Temporal (D6).

1. **Minter role (gitops, `openbao-agent-identity.yaml`).** `auth/kubernetes/role/admin-ui-mcp` binds
   the admin-ui SA (default `admin-ui`/`admin-ui`; override via `MCP_MINTER_SA`/`MCP_MINTER_NS`) to
   `agent-identity-issue`. The BFF (`src/lib/agent/svidMint.ts`) k8s-auth logs in, calls
   `pki-agent/issue/agent-run` with `common_name=ui-assistant`, signs `<ts>.<nonce>` (SHA256withECDSA)
   with the leaf key, and sends `X-Agent-Cert` (base64 PEM) + `X-Agent-PoP` + `-Ts` + `-Nonce`.
2. **Verifier CA (agent-service).** `AgentSvidVerifier` reads `agent.identity.svid.ca-cert`
   (`AGENT_IDENTITY_SVID_CA_CERT`) — the `pki-agent` CA, base64-encoded (an env / HTTP header cannot
   carry the PEM's newlines; the verifier base64-decodes). Wire it via an ESO ExternalSecret reading
   OpenBao `pki-agent/cert/ca` into an `agent-svid-ca` Secret, mounted as that env var, base64-encoded.
3. **Rollout (additive → enforced).** Deploy with `AGENT_IDENTITY_SVID_ENFORCED=false`: a valid SVID
   becomes the identity, anything else falls back to the D3a binding — chat is unaffected before the
   BFF presents certs. Once the minter is live and certs verify, flip `AGENT_IDENTITY_SVID_ENFORCED=true`
   to close the header path (the binding remains a backstop).

**Verified e2e (2026-06-28, sandbox):** `pki-agent` bootstrapped live; a real OpenBao-issued
`CN=ui-assistant` cert + PoP verified through the deployed `AgentSvidVerifier` →
`Verified(agentId=ui-assistant)`; the operator-bearer → `/mcp` HTTP path returns 200.

## Verify

```sh
bao read pki-agent/cert/ca                       # CA present
bao read pki-agent/roles/agent-run               # max_ttl 300s, client_flag true
# Smoke (after PR5b grants a consumer): issue + inspect TTL/CN
bao write pki-agent/issue/agent-run common_name=ui-assistant ttl=300s
```

## Rollback

`bao secrets disable pki-agent` removes the engine and every (ephemeral, `no_store`) cert. Remove the
`admin-ui-mcp` role binding and the agent-service CA config first so no consumer depends on it.
