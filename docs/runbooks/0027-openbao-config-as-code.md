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
| policy `openbao-config-admin` | manage ACL policies and `auth/oidc/role/*`; **no** secret paths, no auth-method changes, no unseal/rekey/root |
| policy `openbank-sso-writer` | create/update/read on the realm-import DR blobs, `keycloak/*`, `delegation-disclosure-service` |
| oidc role `openbao-config-admin` | the operator's Keycloak SSO login; one subject; 15-minute token |
| oidc role `openbank-sso-writer` | same subject; 15-minute token with `openbank-sso-writer` |

The admin identity is the existing Keycloak `oidc` mount, not an `aws` auth method. OpenBao
does not ship cloud auth plugins (`sys/auth/aws` answers `plugin not found in the catalog`),
and Keycloak is already the operator's identity for OpenBao.

Secret **values** are never managed here, because they would land in state.

## Bootstrap (one time)

The `openbao-config-admin` OIDC role does not exist yet, so the first apply needs a token that
can write policies and OIDC roles:

```sh
cd openbank-infra/aws/envs/sandbox-openbao
kubectl -n vault port-forward svc/openbao-active 8200:8200 &
export BAO_ADDR=http://127.0.0.1:8200 VAULT_ADDR=$BAO_ADDR AWS_PROFILE=openbank
export TF_VAR_operator_subject=<your Keycloak user id>   # the `sub` claim, a UUID
bao login                                                # privileged identity; bao prompts
VAULT_TOKEN="$(bao print token)" tofu init
VAULT_TOKEN="$(bao print token)" tofu apply
```

Revoke the bootstrap token afterwards if it was a one-off root token.

## Every later change

```sh
cd openbank-infra/aws/envs/sandbox-openbao
kubectl -n vault port-forward svc/openbao-active 8200:8200 &
export BAO_ADDR=http://127.0.0.1:8200 VAULT_ADDR=$BAO_ADDR AWS_PROFILE=openbank TF_VAR_operator_subject=<uuid>
bao login -method=oidc role=openbao-config-admin         # browser SSO, 15-minute token
VAULT_TOKEN="$(bao print token)" tofu plan
VAULT_TOKEN="$(bao print token)" tofu apply
```

Nobody types an OpenBao token. The admin token comes from SSO and expires in 15 minutes.

**The admin cannot change itself.** The `openbao-config-admin` policy and OIDC role are read-only
to the admin token, so the token cannot widen its own grant. A change to either one is a
bootstrap-class change: review the PR, then apply with the privileged identity, as in Bootstrap.

## Verify by effect

```sh
bao login -method=oidc role=openbao-config-admin
bao token capabilities sys/policies/acl/x                 # create, delete, list, read, update
bao token capabilities openbank/data/keycloak/anything    # deny — no secret access
bao token capabilities sys/auth/x                         # deny — cannot add auth methods
bao login -method=oidc role=openbank-sso-writer
bao token capabilities openbank/data/delegation-disclosure-service   # create, read, update
bao token capabilities openbank/data/some-other-service              # deny
```

`tofu plan` on a clean main must report no changes. Anything else means someone edited OpenBao
by hand. Reconcile it here, not there.

## What this does NOT cover

- The `oidc` mount itself, the `openbank-sso` policy and the ESO/Kubernetes auth roles are
  not yet in the stack. Adopt them with `import` blocks the same way, one PR each.
- There is no CI plan yet. OpenBao has no ingress, and a hosted runner cannot reach it. A
  scheduled in-cluster drift check is the follow-up.
