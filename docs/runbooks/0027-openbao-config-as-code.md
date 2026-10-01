# Runbook 0027 — OpenBao access configuration as code

Status: Ready (bootstrap is owner-gated, one time)
Owner: Platform + Security
Related: #11707, #10896, runbook 0009

## Why

OpenBao's policies and auth roles used to exist only in the running cluster. Every change
needed a privileged token held by a human: a new policy, a new auth role, or a write to a KV
path the read-only SSO policy (`openbank-sso`) does not cover. Nothing recorded who changed
what, and no review happened before the change.

`openbank-infra/aws/envs/sandbox-openbao` declares them instead:

| Object | Purpose |
|---|---|
| policy `openbao-config-admin` | manage ACL policies, `auth/aws/*`, `auth/oidc/role/*`; **no** secret paths, no unseal/rekey/root |
| policy `openbank-sso-writer` | create/update/read on the realm-import DR blobs, `keycloak/*`, `delegation-disclosure-service` |
| auth `aws`, role `openbao-config-admin` | the operator's AWS SSO AdministratorAccess role logs in; 15-minute token |
| oidc role `openbank-sso-writer` | one Keycloak subject; 15-minute token with `openbank-sso-writer` |

Secret **values** are never managed here, because they would land in state.

## Bootstrap (one time)

The `aws` auth method does not exist yet, so the first apply needs a token that can write
policies and enable auth methods. Use the privileged identity you already have, apply with
`bootstrap=true`, then revoke that token.

```sh
cd openbank-infra/aws/envs/sandbox-openbao
kubectl -n vault port-forward svc/openbao-active 8200:8200 &
export AWS_PROFILE=openbank
export TF_VAR_sso_writer_subject=<your Keycloak user id>   # the `sub` claim, a UUID
bao login -address=http://127.0.0.1:8200            # privileged identity; bao prompts
export VAULT_TOKEN="$(bao print token)"
tofu init
tofu plan  -var bootstrap=true    # expect: 2 imports, aws auth + role + client + admin policy created
tofu apply -var bootstrap=true
unset VAULT_TOKEN
```

Then revoke the bootstrap token. If it was a root token, revoke it the same way you created it.

## Every later change

```sh
cd openbank-infra/aws/envs/sandbox-openbao
kubectl -n vault port-forward svc/openbao-active 8200:8200 &
export AWS_PROFILE=openbank TF_VAR_sso_writer_subject=<uuid>
aws sso login                      # if the session expired
tofu plan && tofu apply            # logs in through auth/aws as openbao-config-admin
```

No OpenBao token is typed, stored or printed. The provider signs an STS `GetCallerIdentity`
request with the AWS SSO credentials, and OpenBao verifies it.

## Verify by effect

```sh
bao login -method=aws role=openbao-config-admin header_value=openbao.sandbox.open-bank
bao token capabilities sys/policies/acl/x                 # create, delete, list, read, update
bao token capabilities openbank/data/keycloak/anything    # deny — no secret access
bao login -method=oidc role=openbank-sso-writer
bao token capabilities openbank/data/delegation-disclosure-service   # create, read, update
bao token capabilities openbank/data/some-other-service              # deny
```

`tofu plan` on a clean main must report no changes. Anything else means someone edited OpenBao
by hand. Reconcile it here, not there.

## What this does NOT cover

- The existing `oidc` mount, the `openbank-sso` policy and the ESO/Kubernetes auth roles are
  not yet in the stack. Adopt them with `import` blocks the same way, one PR each.
- There is no CI plan yet. OpenBao has no ingress, and a hosted runner cannot reach it. A
  scheduled in-cluster drift check is the follow-up.
