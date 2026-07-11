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
>
> Status (2026-07-11): §3/§4 as written were NOT sufficient — completing them exactly
> as documented still left every Phase 1 service unable to sync (a 6-day-silent outage
> that took down `ledger-service`, `audit-service` and others once node rotation forced
> pod restarts that needed a fresh credential). Three compounding bugs, all fixed below:
> the rotation script had wrong namespace/dbname mappings for fx/transaction and an
> unconditional call to a service that doesn't exist (`push-notifications`) that aborted
> the whole run under `set -eu` (PR #826); §3 never granted `vault_admin` the actual
> table-level privileges it needs to re-grant to dynamic roles (`CREATEROLE` alone isn't
> enough — added below); and §4 named the wrong OpenBao policy, plus never mentioned
> that `vault-kv` (KV v2) cannot serve the database secrets engine at all — a dedicated
> `ClusterSecretStore` is required (PR #828). See the 2026-07-11 entry in "Fixed items"
> for the full diagnosis.

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

For each Phase 1 service, create the `vault_admin` Postgres role and record its password in a
Kubernetes Secret in the `vault` namespace. **`CREATEROLE` alone is not enough** — the dynamic
role's `creation_statements` (`dynamic-db-credentials.yaml`) also runs
`GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "{{name}}"`, and in
Postgres a role can only grant a privilege it itself holds. Without `WITH GRANT OPTION`,
`database/creds/<service>-db-vault-role` fails at credential-generation time with
`permission denied for table flyway_schema_history (SQLSTATE 42501)` — found live 2026-07-11,
after §3 looked complete (`vault_admin` created, Secret populated, `verify-db-rotation` job
green) and the outage persisted. `ALTER DEFAULT PRIVILEGES` covers tables added by future
migrations; without it, a new Flyway migration silently drops out of the dynamic role's access
until this is re-run.

**Service mapping** (namespace/cluster/dbname corrected 2026-07-11, PR #826 — `fx-service` and
`transaction` were never real namespaces; `push-notifications`' CNPG cluster does not exist and
was removed from the rotation entirely, not just made optional):

| Service key | Namespace | CNPG cluster | Database |
|---|---|---|---|
| notifications | notifications | notifications-db | openbank_notifications |
| audit | audit | audit-db | openbank_audit |
| balance | balances | balances-db | openbank_balances |
| fx | fx | fx-db | openbank_fx |
| account | accounts | accounts-db | openbank_accounts |
| transaction | payments | transaction-db | openbank_transactions |
| ledger | ledger | ledger-db | openbank_ledger |

```sh
# Replace <namespace>, <cluster>, <dbname>, <password> per the table above.
kubectl exec -n <namespace> <cluster>-1 -c postgres -- psql -U postgres -d <dbname> -c \
  "CREATE ROLE vault_admin WITH LOGIN PASSWORD '<password>' CREATEROLE;"
kubectl exec -n <namespace> <cluster>-1 -c postgres -- psql -U postgres -d <dbname> -c \
  "GRANT ALL ON DATABASE <dbname> TO vault_admin;"
kubectl exec -n <namespace> <cluster>-1 -c postgres -- psql -U postgres -d <dbname> -c \
  "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO vault_admin WITH GRANT OPTION;"
kubectl exec -n <namespace> <cluster>-1 -c postgres -- psql -U postgres -d <dbname> -c \
  "ALTER DEFAULT PRIVILEGES FOR ROLE openbank IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO vault_admin;"

# Collect all 7 passwords into the Secret the CronJob reads (service keys, not namespaces):
kubectl create secret generic openbao-db-admin-passwords -n vault \
  --from-literal=notifications='<pass>' \
  --from-literal=audit='<pass>' \
  --from-literal=balance='<pass>' \
  --from-literal=fx='<pass>' \
  --from-literal=account='<pass>' \
  --from-literal=transaction='<pass>' \
  --from-literal=ledger='<pass>'
```

CNPG primary pod name: `<cluster-name>-1` (e.g. `notifications-db-1`). Use
`kubectl get pods -n <namespace> -l cnpg.io/instanceRole=primary` to confirm. The app's own DB
user (table owner, verified `openbank` for all 7 databases 2026-07-11) is `-c postgres` because
these CNPG pods run a `bootstrap-controller` init container alongside `postgres` — without
`-c postgres`, `kubectl exec` defaults to the wrong container.

## §4 — ESO `eso-read` policy extension + dedicated ClusterSecretStore (Tier 1 prerequisite — OUT-OF-BAND + PR)

Two separate things are required here, not one — completing only the policy write (as this
section previously said) leaves every dynamic-credential ExternalSecret failing identically.

**a) Policy name.** The OpenBao k8s-auth role `eso` (runbook 0005, `auth/kubernetes/role/eso`) is
bound to `token_policies=[eso-read]` — **not** a policy named `eso`. A policy named `eso` may
also exist in the cluster; it is unattached to anything ESO actually uses, and writing to it has
no effect. Confirm which policy is really bound before editing:
```sh
bao "bao read auth/kubernetes/role/eso"   # look at token_policies
```
Extend the **bound** policy (`eso-read` as of 2026-07-11) to include `database/creds/*`, keeping
its existing KV v2 grants intact — `bao policy write` fully replaces the policy, so read the
current content first and preserve it:
```sh
bao "bao policy read eso-read"    # note the existing lines before overwriting
bao "bao policy write eso-read -" <<'EOF'
# KV v2 (existing — keep whatever bao policy read eso-read actually showed)
path "openbank/data/*"     { capabilities = ["read"] }
path "openbank/metadata/*" { capabilities = ["read","list"] }
# database secrets engine — dynamic credentials (ADR-0099 Tier 1)
path "database/creds/*"    { capabilities = ["read"] }
EOF
```

**b) ClusterSecretStore.** `vault-kv` is configured for the KV v2 mount (`path: openbank,
version: v2`) — ESO's KV v2 client always constructs `<path>/data/<key>`, so a key like
`database/creds/ledger-db-vault-role` resolves to the nonsense path
`openbank/data/database/creds/ledger-db-vault-role`, which 404s regardless of policy. The
database secrets engine is not KV-versioned at all. A dedicated `vault-db` ClusterSecretStore
(`path: database`, `version: v1` — a plain `<path>/<key>` GET, no `data/`/`metadata/` wrapping)
is required; each `db-dynamic-externalsecret.yaml` must reference `secretStoreRef.name: vault-db`
and a `remoteRef.key` of `creds/<service>-db-vault-role` (without the leading `database/`, which
the store's `path` already supplies). Shipped in
`openbank-infra/gitops/components/external-secrets/clustersecretstore-db.yaml` (PR #828) — this
half needs no further operator action once that PR is merged.

Once **both** (a) and (b) are done, the `db-dynamic-externalsecret.yaml` resources in each service
namespace will sync (status: `SecretSynced`). Validate with:
```sh
kubectl get externalsecret notifications-db-dynamic -n notifications
# Expected: READY True, STATUS SecretSynced
```
If it still shows `SecretSyncedError` / "could not get secret data from provider" after both
fixes, force an immediate retry rather than waiting for the 1h `refreshInterval`:
```sh
kubectl annotate externalsecret notifications-db-dynamic -n notifications force-sync=$(date +%s) --overwrite
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
- ~~`vault_admin` Postgres role not yet created → complete §3 above before next run~~ DONE
  2026-07-11, though §3 itself was incomplete at the time — see the 2026-07-11 "Fixed items" below

~~**Tier 2 — `secret-rotator` (`secret-rotator-cronjob.yaml`):**~~
- ~~Script reads `secret/...`; KV mount is `openbank/`~~ FIXED (all paths corrected)
- No `openbank/keycloak/*` or `openbank/jwt-signing/*` KV tree yet → complete §5 above

## Fixed items (2026-07-11 — live incident: 6-day-silent rotation failure took down ledger-service, audit-service and others)

Completing §3+§4 exactly as this runbook documented (as of 2026-06-30) still left every Phase 1
service unable to sync — the design was written down but never validated end-to-end past the
policy-write step. Three independent, compounding bugs, found live while diagnosing the outage:

~~**`dynamic-db-credentials.yaml` / `db-rotation-job.yaml` service mapping (PR #826):**~~
- ~~`fx`: namespace `fx-service` (doesn't exist) → `fx`~~ FIXED
- ~~`transaction`: namespace `transaction` (doesn't exist) → `payments`, dbname
  `openbank_transaction` (doesn't exist) → `openbank_transactions`~~ FIXED
- ~~`push-notifications`: cluster doesn't exist anywhere in the cluster or this repo; under
  `set -eu` its unset `VAULT_ADMIN_PASS_PUSH_NOTIFICATIONS` aborted the ENTIRE script on the
  2nd service, so audit/balance/fx/account/transaction/ledger never even ran~~ FIXED (removed)

~~**§3 missing table grants:**~~
- ~~`vault_admin` created with `CREATEROLE` only — cannot re-grant privileges it doesn't hold,
  so every credential-generation attempt failed with `permission denied for table
  flyway_schema_history`~~ FIXED — §3 above now includes `WITH GRANT OPTION` +
  `ALTER DEFAULT PRIVILEGES`

~~**§4 wrong policy name + missing ClusterSecretStore (PR #828):**~~
- ~~Instructed editing policy `eso`; the `eso` k8s-auth role is actually bound to `eso-read`~~
  FIXED — §4 above corrected
- ~~`vault-kv` (KV v2) cannot serve the database secrets engine at all, regardless of policy —
  no dedicated store ever existed~~ FIXED — `vault-db` ClusterSecretStore added
- ~~`transaction-db-dynamic` ExternalSecret manifest never existed in `payments/` despite
  transaction-service being in the Phase 1 list~~ FIXED (added alongside PR #828)

**Lesson for future OpenBao/ESO wiring**: after any policy or ClusterSecretStore change, verify
with `bao read auth/kubernetes/role/<role>` which policy is *actually* bound before assuming a
policy name matches a role name, and confirm end-to-end with a live `ExternalSecret` sync
(`kubectl get externalsecret ... -o jsonpath='{.status.conditions}'`) — a successful root-token
`bao read` only proves the secrets engine itself works, not that ESO's own (non-root, policy- and
store-routed) path can reach it.
