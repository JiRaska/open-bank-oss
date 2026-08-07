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

## Convention: the OIDC client secret lives in ONE KV entry (issue #3485)

A new service's ExternalSecret **must** read the OIDC client secret from
`remoteRef.key: account-service`. Do not invent a per-service KV key.

The whole fleet authenticates as a single Keycloak confidential client,
`openbank-services` — check any service's `quarkus.oidc.client-id` — so there is
exactly one secret value and a per-service key name promises a credential that
does not exist. What it does cost is a `vault kv put` nobody prompts you for:
`openbank-delegation-service` shipped `remoteRef.key: delegation-service`, ESO
answered `Secret does not exist`, and the pod sat in `CreateContainerConfigError`
for its entire life behind ~12 alerts that named everything except the cause
(fixed in #3471).

Enforced by `.github/scripts/check-oidc-secret-convention.py`
(gate `oidc-secret-convention`, `mode: enforced`). Ten pre-existing per-service
entries are baselined in that script and are shrink-only — they are frozen, not
blessed; migrating one means deleting its baseline entry in the same commit.
The rule itself is stated in `openbank-libs/governance/rules.yaml:
oidc_secret_convention`, which is authoritative if this file disagrees.

Note the seed commands below predate the convention: `openbank/balance-service`
and `openbank/audit-service` are two of the ten baselined entries, and every
other service takes the value from `openbank/account-service` with no seed step
of its own.

## 3. Seed the real secret values (GATE 2 — out-of-band, never in git)

Pull each current value from the live hand-made Secret and write it to Vault.
The KV keys/properties below MUST match the `remoteRef` in each ExternalSecret.

```sh
# account-service OIDC client secret
vault kv put openbank/account-service \
  OIDC_CLIENT_SECRET="$(kubectl -n accounts get secret account-service-oidc \
    -o jsonpath='{.data.OIDC_CLIENT_SECRET}' | base64 -d)"

# balance-service OIDC client secret
vault kv put openbank/balance-service \
  OIDC_CLIENT_SECRET="$(kubectl -n balances get secret balance-service-oidc \
    -o jsonpath='{.data.OIDC_CLIENT_SECRET}' | base64 -d)"

# audit-service OIDC client secret (shared openbank-services realm client)
vault kv put openbank/audit-service \
  OIDC_CLIENT_SECRET="$(kubectl -n audit get secret audit-service-oidc \
    -o jsonpath='{.data.OIDC_CLIENT_SECRET}' | base64 -d)"

# keycloak bootstrap admin
vault kv put openbank/keycloak-bootstrap \
  admin-username="$(kubectl -n iam get secret keycloak-bootstrap \
    -o jsonpath='{.data.admin-username}' | base64 -d)" \
  admin-password="$(kubectl -n iam get secret keycloak-bootstrap \
    -o jsonpath='{.data.admin-password}' | base64 -d)"

# keycloak realm import — whole JSON as a single property; KEY MUST be the
# filename (openbank-realm.json) because the Deployment mounts it as a file.
#
# ONE-WAY CUTOVER ONLY. This reads the live Secret back into Vault, so it can
# only ever re-store what is already there — and what is already there is a
# stale ancestor of the committed template (#3246: 4 roles and 2 clients against
# the template's 14 and 10). Re-running it as maintenance freezes that gap in
# place. To CHANGE the realm-import content, follow
# docs/runbooks/0009-keycloak-realm-import-reconcile.md, which writes the
# committed template with its __PLACEHOLDER__ tokens substituted.
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

## Rotation (later)

To rotate a secret: `vault kv put openbank/<entry> <key>=<new-value>`. ESO
re-syncs within `refreshInterval` (1h) and updates the Secret; roll the
consuming Deployment to pick up env-mounted values.
