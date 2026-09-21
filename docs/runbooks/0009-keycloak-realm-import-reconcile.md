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
   repo is public). The substitution below must never be committed, echoed, or left
   on disk — see the script in step 4, which enforces this rather than relying on
   the operator remembering it.

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

4. **Render and verify against a LOCAL Keycloak before writing anything to
   Vault — use `openbank-infra/scripts/render-verify-keycloak-realm-import.sh`,
   not a hand-run `docker` command.** Import runs on cold start only, so a
   malformed template ships silently and is discovered by the rebuild it was meant
   to survive; the realm-JSON shapes are version-specific traps (authorization
   policies want `config` maps; a scope permission is `type: "scope"`; the
   `token-exchange` scope must be declared before a permission references it; a
   client description over 255 chars fails the whole import). The script exists
   because those traps, and "don't leave the substituted file on disk," are easy to
   get right once and easy to skip under time pressure the next time:

   ```sh
   ADMINUI_CLIENT_SECRET=... ARGOCD_CLIENT_SECRET=... EDGE_CLIENT_SECRET=... \
   GLITCHTIP_CLIENT_SECRET=... GOALERT_CLIENT_SECRET=... MCP_OBO_CLIENT_SECRET=... \
   OPENBAO_CLIENT_SECRET=... SERVICES_CLIENT_SECRET=... INTEREST_CLIENT_SECRET=... ADMIN_USER_PASSWORD=... \
   ACCOUNT_CLIENT_SECRET=... SDD_CLIENT_SECRET=... STANDING_ORDER_CLIENT_SECRET=... \
   SEPA_PAYMENT_CLIENT_SECRET=... LENDING_CLIENT_SECRET=... CLEARING_CLIENT_SECRET=... \
   DOMESTIC_PAYMENT_CLIENT_SECRET=... SEPA_INSTANT_CLIENT_SECRET=... SWIFT_CLIENT_SECRET=... \
   TRANSACTION_CLIENT_SECRET=... SETTLEMENT_CLIENT_SECRET=... \
   DEMO_USER_PASSWORD=... COMPLIANCE_USER_PASSWORD=... COMPLIANCE2_USER_PASSWORD=... \
   ADMIN_HOST=admin.openbank.local \
     ./openbank-infra/scripts/render-verify-keycloak-realm-import.sh openbank

   CUSTOMER_EDGE_ADMIN_CLIENT_SECRET=... EDGE_WEBAUTHN_CLIENT_SECRET=... \
     ./openbank-infra/scripts/render-verify-keycloak-realm-import.sh openbank-customers
   ```

   It fails closed: any placeholder without an env var stops the run before
   anything is written to disk, and the rendered file is a mode-600 temp file
   removed by a trap that fires on every exit path (success, failure, or Ctrl-C) —
   never a file you have to remember to delete. It resolves the Keycloak version to
   test against from `keycloak.yaml` itself, boots that version against the
   rendered file, and reports PASS only on `KC-SERVICES0032: Import finished
   successfully` with zero `ERROR` lines. What it does **not** replace: minting a
   token and inspecting the JWT's roles by hand for at least one client per realm —
   an import that "succeeds" with a dropped role block prints nothing useful, and
   only a real token proves the role block survived. (Verified this way for both
   realms while preparing this runbook update — see the PR that landed the script.)

## Procedure (owner-gated — a Vault write, not a PR)

Run from a trusted shell. Use the step-4 script with `--out` to produce a verified
(boot-tested) render and keep it only as long as the write needs it — that is the
one deliberate exception to "never leave the substituted file on disk," and the
script still refuses to hand you a file that skipped the boot test (see
`OutFlagRefusedWithoutBootVerification` in
`render_verify_keycloak_realm_import_test.py`).

```sh
# 1. openbank realm — render, boot-verify, and keep the render just long enough
#    to write it (re-supply the same env vars as the pre-flight step above)
./openbank-infra/scripts/render-verify-keycloak-realm-import.sh \
  --out /tmp/ob-realm-verified.json openbank
vault kv put openbank/keycloak-realm-import \
  openbank-realm.json=@/tmp/ob-realm-verified.json
shred -u /tmp/ob-realm-verified.json 2>/dev/null; rm -f /tmp/ob-realm-verified.json

# 2. openbank-customers realm. The KV key and property are declared in
#    es-keycloak-customers-realm.yaml (`keycloak-customers-realm-import` /
#    `openbank-customers-realm.json`); re-read them before writing, and note the
#    ClusterSecretStore's mount path prefixes the key. The property name IS the
#    mounted filename and Keycloak scans the directory for *.json.
./openbank-infra/scripts/render-verify-keycloak-realm-import.sh \
  --out /tmp/ob-customers-verified.json openbank-customers
vault kv put openbank/keycloak-customers-realm-import \
  openbank-customers-realm.json=@/tmp/ob-customers-verified.json
shred -u /tmp/ob-customers-verified.json 2>/dev/null; rm -f /tmp/ob-customers-verified.json
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

Then re-run the pre-flight comparison. It must now go **red** with a "the import
artifact now carries ..." finding for every reconciled name — that is the check
telling you the artifact has moved off its recorded baseline, not a regression.
(Measured 2026-08-13 against the rendered templates: 27 such findings across both
realms.)

## Close-out (a PR, and it is required)

**Delete** the two realm entries from `IMPORT_BASELINE` in
`.github/scripts/check-realm-import-parity.py` — do not merely empty their dimension
sets. `IMPORT_BASELINE` records what the artifact *carries*, so an entry with empty
sets asserts an empty artifact; an absent entry is what means "no known gap, hold
this realm to full parity".

Until that PR lands, the `keycloak-realm-drift` CronJob fails nightly and
`KubeJobFailed` fires. The failure is deliberate: a baseline that survives its own
reconcile is a gate that is green about nothing, and the only way anyone learns the
write happened is for the detector to say so.

With both entries gone, the next role, client or user added to a template and not
propagated to Vault is red on the following night's run — which is the property this
whole exercise buys, and the thing neither of the two older comparisons can provide.

## Per-service M2M client — adding one to the LIVE realm (#10486)

A new confidential client in `realm-template.json` reaches **nothing** that runs: the template
feeds no import (see Why), and `--import-realm` would skip an existing realm anyway. So each
per-service client from #10486 (first: `openbank-interest`) is created in the live realm by the
owner, with its secret generated **by Keycloak** and moved into Vault **without being printed**.
Do this BEFORE the PR that consumes it syncs: the consumer's env ref is `optional: false`, so an
unseeded entry holds the new pod in `CreateContainerConfigError`.

Trusted shell, owner's own admin login (`kcadm.sh config credentials ...` inside the pod). Values
below are for `openbank-interest`; the client representation is the template's own entry.

```sh
NS=iam; POD=$(kubectl -n $NS get pod -l app.kubernetes.io/name=keycloak -o name | head -1)
KC="kubectl -n $NS exec -i $POD -- /opt/keycloak/bin/kcadm.sh"
# 1. create the client from the committed template entry, WITHOUT its placeholder secret —
#    Keycloak then generates one server-side.
jq '.clients[] | select(.clientId=="openbank-interest") | del(.secret)' \
  openbank-infra/gitops/components/keycloak/realm-template.json \
  | $KC create clients -r openbank -f -
ID=$($KC get clients -r openbank -q clientId=openbank-interest --fields id --format csv --noquotes)
# 2. the ONLY role its service account gets (template: realmRoles [ROLE_API])
$KC add-roles -r openbank --uusername service-account-openbank-interest --rolename ROLE_API
# 3. secret Keycloak -> Vault KV through a pipe; it never reaches the terminal or a file.
#    `client_secret=-` reads the value from stdin. KV layout is ADR-0099's keycloak/<service>.
$KC get clients/$ID/client-secret -r openbank | jq -r .value \
  | bao kv put openbank/keycloak/interest-service client_id="$ID" client_secret=-
```

Verify without revealing: `$KC get users -r openbank -q username=service-account-openbank-interest`
then `.../role-mappings/realm` lists exactly `ROLE_API` (plus `default-roles-openbank`);
`kubectl -n interest annotate externalsecret interest-service-ledger-oidc force-sync="$(date +%s)" --overwrite`
and `kubectl -n interest get externalsecret interest-service-ledger-oidc` reports `SecretSynced`.
The DR copy (the realm-import blob) picks the client up at the next reconcile above — pass the
SAME value as `INTEREST_CLIENT_SECRET` to the render script, read from `keycloak/interest-service`.

### Batch 1 (money-path writers)

Six more clients follow the identical recipe, one per caller, each consumed by a named
oidc-client `m2m` in that service. The owner provisions them in one pass with a generic,
idempotent script kept outside this repo (it drives `kcadm` and `bao` with the owner's own
credentials, so it is not a tracked artefact); it reads the client list from the committed
template, skips a client or KV entry that already exists unless told to overwrite, moves each
secret with `jq -j` (no trailing newline), and verifies the stored length equals Keycloak's and
that each service account holds exactly `ROLE_API`. Same ordering rule as above: provision
BEFORE the consuming PR syncs.

| Keycloak client | Vault KV (`openbank/`) | ExternalSecret (namespace) | Render-script variable |
|---|---|---|---|
| `openbank-account` | `keycloak/account-service` | `account-service-m2m-oidc` (accounts) | `ACCOUNT_CLIENT_SECRET` |
| `openbank-sdd` | `keycloak/sdd-service` | `sdd-service-m2m-oidc` (sdd) | `SDD_CLIENT_SECRET` |
| `openbank-standing-order` | `keycloak/standing-order-service` | `standing-order-m2m-oidc` (payments) | `STANDING_ORDER_CLIENT_SECRET` |
| `openbank-sepa-payment` | `keycloak/sepa-payment` | `sepa-payment-m2m-oidc` (payments) | `SEPA_PAYMENT_CLIENT_SECRET` |
| `openbank-lending` | `keycloak/lending-service` | `lending-service-m2m-oidc` (lending) | `LENDING_CLIENT_SECRET` |
| `openbank-clearing` | `keycloak/clearing-service` | `clearing-service-m2m-oidc` (payments) | `CLEARING_CLIENT_SECRET` |

Two traps already paid for on the interest client, both silent: a client created WITHOUT the
`profile` scope issues tokens with no `preferred_username`, so the principal id falls back to the
subject UUID and every identity-gated rego rule simply never matches (a 403, not an error); and
a secret piped with `jq -r` carries a trailing newline into Vault, which Keycloak then rejects as
a different secret (`unauthorized_client`). `jq -j`, then compare lengths.

### Batch 2 (money-path writers, continued)

Same recipe, same script, five more clients — all consumed in the `payments` namespace by the
named oidc-client `m2m`. interest-service's remittance leg needs no new client: it reuses the
existing `openbank-interest` (named oidc-client `ledger`), so nothing is provisioned for it.

| Keycloak client | Vault KV (`openbank/`) | ExternalSecret (namespace) | Render-script variable |
|---|---|---|---|
| `openbank-domestic-payment` | `keycloak/domestic-payment` | `domestic-payment-m2m-oidc` (payments) | `DOMESTIC_PAYMENT_CLIENT_SECRET` |
| `openbank-sepa-instant` | `keycloak/sepa-instant` | `sepa-instant-m2m-oidc` (payments) | `SEPA_INSTANT_CLIENT_SECRET` |
| `openbank-swift` | `keycloak/swift-service` | `swift-service-m2m-oidc` (payments) | `SWIFT_CLIENT_SECRET` |
| `openbank-transaction` | `keycloak/transaction-service` | `transaction-service-m2m-oidc` (payments) | `TRANSACTION_CLIENT_SECRET` |
| `openbank-settlement` | `keycloak/settlement-service` | `settlement-service-m2m-oidc` (payments) | `SETTLEMENT_CLIENT_SECRET` |

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
