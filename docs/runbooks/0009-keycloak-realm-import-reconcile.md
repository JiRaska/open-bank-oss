# Runbook 0009 — Reconcile the Keycloak realm-import artifact to the committed template

Status: Ready (procedure written and measured; the Vault write itself is owner-gated)
Owner: Platform + Security
Related: #3246 (this measurement), #2540 (roles: repo 14 / Vault 4 / live 14),
#3244 (role assignments), ADR-0065 (the two-realm import), runbook 0005
(Vault → OpenBao / External Secrets).

## Why

Keycloak reads its realm JSON from the Secrets `keycloak-realm-import` and
`keycloak-customers-realm-import`, which External Secrets fills from Vault KV
(`openbank-infra/gitops/components/external-secrets/es-keycloak-realm-import.yaml`).
**The committed `realm-template.json` feeds nothing.** `--import-realm` also runs
on cold start only, so the artifact has had no effect since each realm first came
up — which is why the two could drift for months with ArgoCD `Synced/Healthy` and
every gate green.

Measured 2026-08-03 on the sandbox, all three layers, both realms:

| realm | artifact | roles | clients | users |
|---|---|---|---|---|
| `openbank` | repo template | 14 | 10 | 6 |
| `openbank` | import Secret | **4** | **2** | **1** |
| `openbank` | live realm | 14 | 10 | 4 (+2 service accounts) |
| `openbank-customers` | repo template | 2 | 3 | 0 |
| `openbank-customers` | import Secret | **1** | **1** | 0 |
| `openbank-customers` | live realm | 2 | 3 | 0 |

The live `/users` endpoint never returns service accounts, which is why `openbank`
reads 4 there against the template's 6; the two `service-account-*` entries the
template declares exist live and hold their mappings.

The shape that decides the direction: **the import artifact is a strict ancestor of
the template, not a divergent fork.** Every name in it is also in the template, in
both realms, in all three dimensions — `importedNotDeclared` is empty everywhere.
So converging Vault to the repo drops nothing that exists today, while the reverse
(scoping the enforced `rolesallowed-realm-parity` gate down to the import artifact)
would make the gate honest about a 4-role blob and immediately fail ten roles' worth
of `@RolesAllowed` sites the running system serves correctly.

**Nothing is broken today. This is disaster-recovery preparation.** A green-field
rebuild — new cluster, deleted realm, deliberate re-import into an empty DB — would
produce a realm with 4 roles and 2 clients, so every `@RolesAllowed` naming one of
the ten missing roles would match nothing and eight clients (ArgoCD, Grafana and
OpenBao SSO, the customer edge's own M2M identity, `openbank-mcp-service`) would not
exist. A DB restore is safe: `keycloak-db` has a barman S3 backup, and the realm
survives in it.

## Blast radius

- Writes: two Vault KV properties. Nothing in the live realm changes.
- Propagation: External Secrets refreshes hourly (`refreshInterval: 1h`), so both
  Secrets update within the hour. Keycloak does **not** re-read them — no restart,
  no rollout, no session impact. That is the point: the change is inert until a
  cold start, which is precisely the event it exists for.
- Not in scope: the live realm, `kcadm`, any role or client creation.

## Pre-flight

1. **Confirm the current gap is still what this runbook describes.** The detector
   shipped with #3246 prints it, and running it is cheaper than re-deriving it:

   ```sh
   kubectl -n iam get secret keycloak-realm-import \
     -o jsonpath='{.data.openbank-realm\.json}' | base64 -d > /tmp/ob-realm.json
   kubectl -n iam get secret keycloak-customers-realm-import \
     -o jsonpath='{.data.openbank-customers-realm\.json}' | base64 -d > /tmp/ob-customers.json
   python3 .github/scripts/check-realm-import-parity.py \
     --import openbank=/tmp/ob-realm.json \
     --import openbank-customers=/tmp/ob-customers.json
   ```

   Exit 0 with a non-empty `declaredNotImported/*` = the measured baseline, proceed.
   Exit 1 = something moved since; read the findings before writing anything.

2. **Read the committed templates, and know they carry placeholders.** Every client
   secret and user password in `realm-template.json` is a `__PLACEHOLDER__` token
   (this is asserted by `check_realm_import_parity_test.py` and must stay true — the
   repo is public). The write below substitutes real values in a local shell; the
   substituted file must never be committed, echoed, or left on disk.

3. **Collect the real values from the live realm, not from memory.** For each client
   the template declares, the live secret is the authority:

   ```sh
   # per client, from a trusted shell with a bootstrap admin token
   # GET /admin/realms/openbank/clients/<id>/client-secret
   ```

   A client whose secret is also held in a Vault KV entry that a service reads
   (`es-*-oidc.yaml`) must use the SAME value, or the cold-started realm issues a
   secret the service does not present.

   **Two confidential clients in the customers template have no secret field at
   all** — `customer-edge-admin` and `openbank-edge-webauthn` are
   `publicClient: false` and carry neither a literal nor a `__PLACEHOLDER__`. On
   import Keycloak generates a random secret for each, so a cold-started realm would
   issue credentials neither service presents. (The two secret-less clients in the
   `openbank` template, `openbank-grafana` and `apicurio-registry`, are
   `publicClient: true` and correct as they stand.) Add placeholders for those two
   in the same change as the reconcile, or the customers realm is reproducible in
   name only. This is a template defect the reconcile surfaces; it is not caused by
   it.

4. **Verify the substituted file against a LOCAL Keycloak before writing it to
   Vault.** Import runs on cold start only, so a malformed template ships silently
   and is discovered by the rebuild it was meant to survive. The realm-JSON shapes
   are version-specific traps (authorization policies want `config` maps; a scope
   permission is `type: "scope"`; the `token-exchange` scope must be declared before
   a permission references it; a client description over 255 chars fails the whole
   import):

   ```sh
   docker run --rm -v /tmp/ob-realm-substituted.json:/opt/keycloak/data/import/realm.json \
     quay.io/keycloak/keycloak:<the version keycloak.yaml runs> start-dev --import-realm
   ```

   Watch for `Realm 'openbank' imported` and no `ERROR`. Then mint a token for one
   client and inspect the JWT's roles — an import that "succeeds" with a dropped
   role block prints nothing useful.

## Procedure (owner-gated — a Vault write, not a PR)

Run from a trusted shell. Do not echo the values; do not leave the substituted file
behind.

```sh
# 1. openbank realm
vault kv put openbank/keycloak-realm-import \
  openbank-realm.json=@/tmp/ob-realm-substituted.json

# 2. openbank-customers realm. The KV key and property are declared in
#    es-keycloak-customers-realm.yaml (`keycloak-customers-realm-import` /
#    `openbank-customers-realm.json`); re-read them before writing, and note the
#    ClusterSecretStore's mount path prefixes the key. The property name IS the
#    mounted filename and Keycloak scans the directory for *.json.
vault kv put openbank/keycloak-customers-realm-import \
  openbank-customers-realm.json=@/tmp/ob-customers-substituted.json

# 3. clean up
shred -u /tmp/ob-realm-substituted.json /tmp/ob-customers-substituted.json
```

> The `vault kv put` recipe in
> `openbank-infra/gitops/components/external-secrets/README.md` reads the LIVE
> Secret back and writes it to Vault. That round-trip is what has kept the stale
> ancestor alive: it can only ever re-store what is already there. Use this runbook
> for the realm-import entries instead.

## Verify

```sh
# ESO should re-sync within the hour; force it if you do not want to wait.
kubectl -n iam annotate externalsecret keycloak-realm-import \
  force-sync="$(date +%s)" --overwrite
kubectl -n iam get externalsecret keycloak-realm-import \
  -o jsonpath='{.status.conditions}'
```

Then re-run the pre-flight comparison. It must now go **red** with a "listed in
KNOWN_STALE but the import artifact now carries it" finding for every reconciled
name — that is the check telling you the baseline has outlived its gap, not a
regression.

## Close-out (a PR, and it is required)

Empty `KNOWN_STALE` in `.github/scripts/check-realm-import-parity.py`. Until that
lands, the `keycloak-realm-drift` CronJob fails nightly and `KubeJobFailed` fires.
The failure is deliberate: a baseline that survives its own reconcile is a gate that
is green about nothing, and the only way anyone learns the write happened is for the
detector to say so.

With `KNOWN_STALE` empty, the next role, client or user added to a template and not
propagated to Vault is red on the following night's run — which is the property this
whole exercise buys, and the thing neither of the two older comparisons can provide.

## What this does NOT fix

The two artifacts can still diverge tomorrow; this reconciles them once and detects
the next divergence. Making them structurally unable to diverge is a larger change
with its own decision to take — the shape is an ExternalSecret whose
`spec.target.template.templateFrom` renders the committed template out of a
ConfigMap ArgoCD manages, substituting only the `__PLACEHOLDER__` tokens from Vault
KV. That makes the repo the structure of record and Vault the credential store,
which is what each is actually good for. It also changes what a cold start produces,
so it wants verification against a local Keycloak and a deliberate go-ahead, not a
drive-by.
