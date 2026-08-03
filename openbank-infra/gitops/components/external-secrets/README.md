# secrets — Vault + ESO bootstrap runbook

This component holds the **references** ESO uses to project Vault KV entries into
the Kubernetes Secrets workloads already consume. The **values never live in
git**. Two things must be done out-of-band, by a human, exactly once:

1. `tofu apply` of `aws/envs/sandbox-vault` (GATE 1) — applies the `vault-kms`
   module: KMS unseal key + Pod Identity role. Isolated state, so the plan is a
   clean `5 to add, 0 to destroy`. Vault cannot auto-unseal without it.
2. The Vault one-time init + secret seeding below (GATE 2).

ArgoCD reconciles in this order: `vault` and `external-secrets` (sync-wave 0),
then `secrets` (sync-wave 1). The `ClusterSecretStore`/`ExternalSecret` objects
stay in a failed/`SecretSyncedError` state until steps below are done — that is
expected, not a deploy failure.

## Prerequisites

- `envs/sandbox-vault` applied; the `vault` ns has the Pod Identity association.
- `vault` and `external-secrets` ArgoCD apps Synced + Healthy (pods running).
- `kubectl`/`vault` CLI access. All `vault` commands run via a port-forward or
  `kubectl exec` into the `vault-0` pod. Do **not** commit any token or value
  printed by these commands.

## 1. Initialize Vault (one time)

Vault boots sealed. With KMS auto-unseal, init produces *recovery* keys (for
break-glass), not unseal keys; KMS handles routine unseal.

```sh
kubectl -n vault exec -it vault-0 -- sh
export VAULT_ADDR=http://127.0.0.1:8200

# 1 recovery share is fine for sandbox; raise shares/threshold for prod.
vault operator init -recovery-shares=1 -recovery-threshold=1
#   -> prints Recovery Key 1 + Initial Root Token.
#   STORE BOTH IN A PASSWORD MANAGER. Never paste into git/chat/state.
#   KMS auto-unseals; `vault status` should show Sealed: false.

export VAULT_TOKEN=<initial-root-token>
```

## 2. Enable the KV v2 mount + Kubernetes auth + ESO policy/role

```sh
# KV v2 at path matching the ClusterSecretStore (spec.provider.vault.path).
vault secrets enable -path=openbank kv-v2

# Kubernetes auth so ESO authenticates with its own SA token (no static token).
vault auth enable kubernetes
vault write auth/kubernetes/config \
  kubernetes_host="https://kubernetes.default.svc"

# Read-only policy scoped to openbank/* — least privilege for ESO.
vault policy write eso-read - <<'EOF'
path "openbank/data/*"     { capabilities = ["read"] }
path "openbank/metadata/*" { capabilities = ["read", "list"] }
EOF

# Bind ESO's ServiceAccount (external-secrets/external-secrets) to that policy
# via role `eso` — must match ClusterSecretStore auth.kubernetes.role.
# NOTE: use token_policies=, NOT the deprecated policy= (silently ignored on
# Vault >=1.15, which yields a successful login but 403 on every read).
vault write auth/kubernetes/role/eso \
  bound_service_account_names=external-secrets \
  bound_service_account_namespaces=external-secrets \
  token_policies=eso-read \
  ttl=1h
```

## 3. Seed the real secret values (GATE 2 — out-of-band, never in git)

Pull each current value from the live hand-made Secret and write it to Vault.
The KV keys/properties below MUST match the `remoteRef` in each ExternalSecret.

```sh
# The shared openbank-services client secret. ONE entry, read by every service — see
# "One credential, one entry" below. Historic path name; it is not account-service's own.
vault kv put openbank/account-service \
  OIDC_CLIENT_SECRET="$(kubectl -n accounts get secret account-service-oidc \
    -o jsonpath='{.data.OIDC_CLIENT_SECRET}' | base64 -d)"

# keycloak bootstrap admin
vault kv put openbank/keycloak-bootstrap \
  admin-username="$(kubectl -n iam get secret keycloak-bootstrap \
    -o jsonpath='{.data.admin-username}' | base64 -d)" \
  admin-password="$(kubectl -n iam get secret keycloak-bootstrap \
    -o jsonpath='{.data.admin-password}' | base64 -d)"

# keycloak realm import — whole JSON as a single property; KEY MUST be the
# filename (openbank-realm.json) because the Deployment mounts it as a file.
vault kv put openbank/keycloak-realm-import \
  openbank-realm.json=@<(kubectl -n iam get secret keycloak-realm-import \
    -o jsonpath='{.data.openbank-realm\.json}' | base64 -d)
```

> Run these from a trusted shell. The values are printed only into Vault; do not
> echo them, log them, or commit the command output.

## 4. Verify the cutover

```sh
# Each ExternalSecret should flip to SecretSynced / Ready=True.
kubectl get externalsecrets -A
kubectl -n external-secrets get clustersecretstore vault-kv -o jsonpath='{.status.conditions}'
```

Every `ExternalSecret` uses `creationPolicy: Owner`: ESO creates and owns the
target Secret with the same name/key the workloads already read, and re-creates
it if anything deletes it. The Secret carries
`argocd.argoproj.io/compare-options: IgnoreExtraneous` (set via the ESO target
template) so ArgoCD's prune never removes the ESO-owned Secret — that is what
the original hand-made Secrets lacked, which let a prune delete them and left
`Merge` unable to recreate them. Already-mounted env/volume secrets keep working
across the cutover; a pod restart picks up future rotation.

## One credential, one entry — the OIDC client secret convention

The `openbank` realm defines exactly **one** confidential M2M client,
`openbank-services` (`components/keycloak/realm-template.json`). Every service
that mints a client-credentials token authenticates as it; there is no
per-service realm client. So **every `OIDC_CLIENT_SECRET` ExternalSecret reads
`remoteRef.key: account-service`** — the shared entry — and there is nothing to
seed when a new service ships.

That is the rule, not a habit. It is declared in `rules.yaml:
oidc_client_secret_storage` and enforced by
`.github/scripts/check-oidc-client-secret-wiring.py` (gate
`oidc-client-secret-wiring`), which fails a manifest naming any other entry.

Why not one entry per service: a per-service entry cannot hold a per-service
credential — there is only one — so it holds a hand-copied duplicate. That buys
no blast-radius isolation (rotating the client invalidates every copy at once),
turns one rotation into N writes with nothing enumerating N, and adds a manual
seed step per service. The seed step is the half that goes missing:
`delegation-service` shipped pointing at an entry nobody had written and sat in
`CreateContainerConfigError` for its whole life behind ~12 alerts naming
everything but the cause (#3471); `mcp-service` had the same gap open, masked by
`optional: true` (#3485). The estate was converged to the shared entry in #3485.

A workload authenticating as a **different** realm client (admin-ui,
customer-edge, the WebAuthn client) legitimately keeps its own entry. Those store
the value under `client-secret`/`kc-client-secret`, and that property name is
what puts them out of the rule's scope.

## Rotation (later)

To rotate a secret: `vault kv put openbank/<entry> <key>=<new-value>`. ESO
re-syncs within `refreshInterval` (1h) and updates the Secret; roll the
consuming Deployment to pick up env-mounted values.

Rotating the shared M2M client is therefore: set the new secret on
`openbank-services` in Keycloak, `vault kv put openbank/account-service
OIDC_CLIENT_SECRET=<new>` — **one** write — then roll every consuming Deployment.
Env-mounted values do not hot-reload, so until the roll each pod keeps presenting
the old secret and Keycloak rejects it: expect 401s on M2M calls between the KV
write and the last roll. There is no way to make that window zero with a single
client credential; sequence the roll money-path last.
