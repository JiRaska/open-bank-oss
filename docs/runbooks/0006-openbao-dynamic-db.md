# Runbook 0006 — OpenBao dynamic-DB / rotation bootstrap (`db-admin` + `agent-identity-admin`)

Out-of-band operator bootstrap referenced by the openbao-config CronJobs
(`db-rotation-job.yaml`, `secret-rotator-cronjob.yaml`,
`openbao-agent-identity.yaml`). These jobs authenticate to OpenBao via the
Kubernetes auth method using k8s-auth **roles that are not gitops-managed** —
creating them needs a privileged token, so it can't live in the kustomize
overlay. This runbook is that step. It mirrors the `eso` role setup in
runbook 0005 (Vault→OpenBao) and the `agent-identity-admin` recipe in
runbook 0007.

> Status: applied to **sandbox** 2026-06-29. Both roles + policies exist live.
> `openbao-agent-identity-sync` runs green. The two rotation CronJobs remain
> **suspended** pending feature completion (see "Remaining work" below).

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
```

## Remaining work — why the two rotation jobs are still suspended

Creating `db-admin` removes the auth blocker, but both rotation jobs have
unfinished prerequisites (all verified 2026-06-29). They are `suspend: true`
in gitops so they stop firing `KubeJobFailed`; remove `suspend` once fixed.

**Tier 1 — `openbao-db-rotation-sync` (`dynamic-db-credentials.yaml`):**
1. `connection_url` host is wrong: `<svc>-service-rw` → the CNPG primary Service
   is `<svc>-db-rw` (e.g. `notifications-db-rw`).
2. No `vault_admin` Postgres role exists in the target DBs (needs a CREATEROLE
   user, password stored for OpenBao's `database/config`).
3. `database/config` is written with `username=vault_admin` but no password.
4. No ExternalSecret consumes `database/creds/*` yet.

**Tier 2 — `secret-rotator` (`secret-rotator-cronjob.yaml`):**
1. Script reads `secret/...`; the real KV mount is `openbank/`.
2. No `keycloak/*` (admin creds, client_id mappings) or `jwt-signing/*` KV tree
   exists — there is nothing to rotate until it is provisioned.
