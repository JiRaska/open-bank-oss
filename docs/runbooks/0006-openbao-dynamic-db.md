# Runbook 0006 — OpenBao dynamic-DB / rotation bootstrap (`db-admin` + `agent-identity-admin`)

Out-of-band operator bootstrap referenced by the openbao-config CronJobs
(`db-rotation-job.yaml`, `secret-rotator-cronjob.yaml`,
`openbao-agent-identity.yaml`). These jobs authenticate to OpenBao via the
Kubernetes auth method using k8s-auth **roles that are not gitops-managed** —
creating them needs a privileged token, so it can't live in the kustomize
overlay. This runbook is that step. It mirrors the `eso` role setup in
runbook 0005 (Vault→OpenBao) and the `agent-identity-admin` recipe in
runbook 0007.

> Status (2026-06-29): roles `db-admin` + `agent-identity-admin` applied to sandbox.
> `openbao-agent-identity-sync` runs green.
>
> Status (2026-06-30): Tier 1 + Tier 2 `suspend: true` removed from gitops manifests.
> All script path bugs fixed (connection_url, KV mount). `suspend` will be re-added
> only if the out-of-band prerequisites below (§3 vault_admin bootstrap) are not met
> before the next Sunday 02:00 UTC run. Track completion with §4 ESO policy extension.

## Why it was missing

The CronJobs shipped referencing `role=db-admin` (and `role=agent-identity-admin`),
but the roles were never created — every weekly run failed at step 1 with
`invalid role name "db-admin"` (Tier 1/Tier 2) and the equivalent for agent-identity.
The manifests pointed at this file (`runbook 0006`) which did not exist; this
fills the gap.

## Access

The bootstrap needs an OpenBao token with `root` (or equivalent). The break-glass
material lives in AWS Secrets Manager (`openbank/openbao/break-glass`, keys
`root_token` / `recovery_key_b64`). The `openbank` SSO profile has admin.

```sh
export AWS_PROFILE=openbank AWS_REGION=eu-north-1
RT=$(aws secretsmanager get-secret-value --secret-id openbank/openbao/break-glass \
       --query SecretString --output text | python3 -c 'import sys,json;print(json.load(sys.stdin)["root_token"])')
bao() { kubectl exec -i -n vault openbao-0 -- sh -c "BAO_ADDR=http://127.0.0.1:8200 BAO_TOKEN='$RT' $*"; }
```

Note the KV v2 mount is **`openbank/`** (not `secret/`), and the database engine
mount is **`database/`**. Confirm with `bao "bao secrets list"` before running.

## 1. `db-admin` (Tier 1 db-rotation-sync + Tier 2 secret-rotator)

```sh
bao "bao policy write db-admin -" <<'EOF'
# Tier 1: database secrets engine
path "sys/mounts/database"  { capabilities = ["create","read","update"] }
path "database/config/*"    { capabilities = ["create","read","update"] }
path "database/roles/*"     { capabilities = ["create","read","update"] }
# Tier 2: OIDC client secrets + JWT signing keys (KV v2 mount is openbank/)
path "openbank/data/keycloak/*"      { capabilities = ["create","read","update","patch"] }
path "openbank/metadata/keycloak/*"  { capabilities = ["read","list"] }
path "openbank/data/jwt-signing/*"     { capabilities = ["create","read","update","patch"] }
path "openbank/metadata/jwt-signing/*" { capabilities = ["read","list"] }
path "sys/tools/random/*"            { capabilities = ["update"] }
EOF

bao "bao write auth/kubernetes/role/db-admin \
  bound_service_account_names=openbao-db-admin \
  bound_service_account_namespaces=vault \
  token_policies=db-admin ttl=20m"
```

## 2. `agent-identity-admin` (openbao-agent-identity-sync) — see runbook 0007

```sh
bao "bao policy write agent-identity-admin -" <<'EOF'
path "sys/mounts/pki-agent"      { capabilities = ["create","read","update"] }
path "sys/mounts/pki-agent/tune" { capabilities = ["update"] }
path "pki-agent/*"               { capabilities = ["create","read","update","list"] }
path "sys/policies/acl/agent-identity-issue" { capabilities = ["create","update","read"] }
path "auth/kubernetes/role/admin-ui-mcp"     { capabilities = ["create","update","read"] }
EOF

bao "bao write auth/kubernetes/role/agent-identity-admin \
  bound_service_account_names=openbao-agent-identity-admin \
  bound_service_account_namespaces=vault \
  token_policies=agent-identity-admin ttl=10m"
```

## Verify

```sh
# auth as the rotation SA succeeds and has the expected caps
kubectl create job -n vault verify-agent --from=cronjob/openbao-agent-identity-sync   # → Complete

# Verify Tier 1 CronJob auth (once §3 is done, trigger manually):
kubectl create job -n vault verify-db-rotation --from=cronjob/openbao-db-rotation-sync
kubectl logs -n vault job/verify-db-rotation
# Expected: "[openbao-db] all services configured."

# Verify Tier 2 CronJob auth (OIDC/JWT paths will WARN+skip until §4 provisioned):
kubectl create job -n vault verify-secret-rotator --from=cronjob/secret-rotator
kubectl logs -n vault job/verify-secret-rotator
# Expected: "[rotator] Tier 2 rotation complete" with WARNs for missing paths.
```

## §3 — vault_admin role bootstrap (Tier 1 prerequisite — OUT-OF-BAND, OPERATOR ACTION)

For each Phase 1 service, create the `vault_admin` Postgres role with `CREATEROLE` privilege
and record its password in a Kubernetes Secret in the `vault` namespace:

```sh
# For each service: notifications, audit, balance, fx-service, accounts, transaction, ledger
# Replace <service>, <cluster>, <namespace>, <dbname>, <password> appropriately.
kubectl exec -n <namespace> <cluster>-1 -- psql -U postgres -c \
  "CREATE ROLE vault_admin WITH LOGIN PASSWORD '<password>' CREATEROLE;"
kubectl exec -n <namespace> <cluster>-1 -- psql -U postgres -c \
  "GRANT ALL ON DATABASE <dbname> TO vault_admin;"

# Collect all passwords into the Secret the CronJob reads:
kubectl create secret generic openbao-db-admin-passwords -n vault \
  --from-literal=notifications='<pass>' \
  --from-literal=audit='<pass>' \
  --from-literal=balance='<pass>' \
  --from-literal=fx='<pass>' \
  --from-literal=account='<pass>' \
  --from-literal=transaction='<pass>' \
  --from-literal=ledger='<pass>'
# push-notifications is optional: only add if the cluster exists.
```

CNPG primary pod name: `<cluster-name>-1` (e.g. `notifications-db-1`). Use
`kubectl get pods -n <namespace> -l cnpg.io/instanceRole=primary` to confirm.

## §4 — ESO `eso` policy extension (Tier 1 prerequisite — OUT-OF-BAND)

The ESO ClusterSecretStore `vault-kv` uses OpenBao role `eso` (runbook 0005). That role's
policy currently covers only `openbank/*` (KV v2). To allow ESO to read dynamic credentials,
extend the `eso` policy to include `database/creds/*`:

```sh
bao "bao policy write eso -" <<'EOF'
# KV v2 (existing)
path "openbank/data/*"     { capabilities = ["read"] }
path "openbank/metadata/*" { capabilities = ["read","list"] }
# database secrets engine — dynamic credentials (ADR-0099 Tier 1)
path "database/creds/*"    { capabilities = ["read"] }
EOF
```

Once this is applied, the `db-dynamic-externalsecret.yaml` resources in each service
namespace will begin syncing (status: `SecretSynced`). Validate with:
```sh
kubectl get externalsecret notifications-db-dynamic -n notifications
# Expected: READY True, STATUS SecretSynced
```

## §5 — Keycloak/JWT KV tree provisioning (Tier 2 prerequisite — OUT-OF-BAND)

To activate full OIDC rotation, provision the `openbank/keycloak/*` tree:

```sh
# Keycloak admin creds (for the rotator to call the KC Admin API):
bao "bao kv put openbank/keycloak/admin username=admin password='<kc-admin-pass>'"

# For each service with a KC confidential client:
bao "bao kv put openbank/keycloak/<service> client_id='<uuid-from-keycloak>'"
```

To activate JWT key rotation, bootstrap initial keys:
```sh
INITIAL_KEY="$(bao 'bao write -field=random_bytes sys/tools/random/32 format=hex')"
NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
bao "bao kv put openbank/jwt-signing/<service> current_key='${INITIAL_KEY}' previous_key='' rotated_at='${NOW}'"
```

## Fixed items (2026-06-30 — all addressed in this PR)

~~**Tier 1 — `openbao-db-rotation-sync` (`dynamic-db-credentials.yaml`):**~~
- ~~`connection_url` host wrong: `<svc>-service-rw` → corrected to `<svc>-db-rw`~~ FIXED
- ~~`database/config` written with `username=vault_admin` but no password~~ FIXED (§3 Secret ref)
- ~~No ExternalSecret consumes `database/creds/*` yet~~ FIXED (per-service db-dynamic-externalsecret.yaml)
- `vault_admin` Postgres role not yet created → complete §3 above before next run

~~**Tier 2 — `secret-rotator` (`secret-rotator-cronjob.yaml`):**~~
- ~~Script reads `secret/...`; KV mount is `openbank/`~~ FIXED (all paths corrected)
- No `openbank/keycloak/*` or `openbank/jwt-signing/*` KV tree yet → complete §5 above
