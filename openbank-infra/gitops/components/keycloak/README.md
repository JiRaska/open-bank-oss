<!-- SPDX-License-Identifier: Apache-2.0 -->

# Keycloak realm templates

Two realms, two committed templates:

| template | realm | what reads it |
|---|---|---|
| `realm-template.json` | `openbank` | operator/staff realm (ADR-0065) |
| `customers-realm-template.json` | `openbank-customers` | retail customer realm (ADR-0065) |

## These files must stay IMPORTABLE, and that is not free

Keycloak deserializes a realm JSON with Jackson and **fails the whole import on an
unrecognized field** — it does not warn and skip. There is no comment syntax in JSON and
none in Keycloak's schema, so any `"comment"`, `"note"` or `"_doc"` key added for the
benefit of a human reader makes the file unimportable. Prose about these templates goes
**here**, keyed by path, never inside the JSON.

That is not hypothetical. Measured against `quay.io/keycloak/keycloak:26.6.3` (the version
`keycloak.yaml` ran at the time) on 2026-08-06, `customers-realm-template.json` carried five
`"comment"` keys and the import died on the first one:

```
ERROR: Failed to run import
ERROR: Unrecognized field "comment" (class org.keycloak.representations.idm.ProtocolMapperRepresentation),
  not marked as ignorable ... ["clients"]->[0]->["protocolMappers"]->[1]->["comment"]
```

That was only the first of **four** independent import-blockers in that one file. Each was
found by removing the previous one and running the container again:

1. five `"comment"` keys — `Unrecognized field "comment"`;
2. `openbank-edge-webauthn` `description` at 490 chars — over the 255 column cap;
3. the flow execution authenticator `webauthn-register-passwordless-action` (37 chars) —
   `Value too long for column "AUTHENTICATOR CHARACTER VARYING(36)"`. The same constraint
   applies to the deployed Postgres, which proves the live realm cannot contain this value
   either;
4. `default-roles-openbank-customers` composing `offline_access` and `uma_authorization`
   without declaring them — `Unable to find composite realm role: uma_authorization`. A
   full import with an explicit `roles.realm` list does not get Keycloak's built-in roles.

Plus two confidential clients with no `secret` at all (below). So the customers template
could not have rebuilt its realm at all — which is what issue #3246 means by "the realm is
not reproducible from git", one layer deeper than the stale-import-artifact measurement it
started from. `realm-template.json` (the `openbank` realm) imported cleanly throughout.

`check-realm-template-importable.py` (gate `realm-template-importable`) now enforces the
static half of this. **A static gate cannot enumerate Keycloak's schema**, so it only
catches the classes of defect listed in its header. Before changing a template, still run
the real thing — import runs on **cold start only**, so a malformed template ships in
total silence and is discovered by the rebuild it existed to survive:

```sh
# substitute the __PLACEHOLDER__ tokens into a scratch copy first (never commit it)
docker run --rm -p 8080:8080 \
  -v /tmp/realm.json:/opt/keycloak/data/import/realm.json \
  quay.io/keycloak/keycloak:<version in keycloak.yaml's openbank-keycloak tag> start-dev --import-realm
```

Look for `Realm '<name>' imported` **and** `Import finished successfully`, then mint a
token and inspect the JWT. An import that "succeeds" having silently dropped a block
prints nothing useful.

### Version-specific shapes that fail the whole import

- Authorization policies take a `config` map, not first-class fields.
- A scope permission is `type: "scope"`, not `scope-permission`.
- The `token-exchange` authorization scope must be declared **before** a permission
  references it.
- The requesting client needs `standard.token.exchange.enabled=true`.
- A client `description` caps at **255 characters**; a longer one fails the entire import.
- A user needs `firstName`/`lastName`/`email`, or Keycloak 26 answers "Account is not
  fully set up" with no hint why.

### Client scopes and the reserved `openid` protocol scope

Every `defaultClientScopes` / `optionalClientScopes` entry must resolve to a
`clientScopes[].name` in the same template (rule R12). An explicit realm import does not
make an undeclared reference work: Keycloak logs `Referenced client scope ... doesn't
exist` and silently drops the assignment. In particular, `openid` is the reserved OIDC
**protocol** scope, not a client scope to define or assign. Clients still request it in the
authorization request's `scope` parameter; removing its invalid client-scope assignment
does not remove OIDC authentication or the `openid` token scope. Built-in client scopes
such as `email` and `offline_access`, by contrast, need their standard definitions when an
explicit template assigns them.

## Role scope: `fullScopeAllowed: false` plus explicit `scopeMappings`

Keycloak 26 deprecates the per-client "Full scope allowed" switch and logs a WARN for every
client that leaves it on (its default). Every client in both templates therefore sets
`fullScopeAllowed: false`, and the top-level `scopeMappings` array grants each of those
clients every realm role the template declares. That is deliberately the same set full
scope allowed: a token still carries exactly the user's effective realm roles, and a role
added to a user later still reaches the token.

**Turning the switch off without the mapping strips every role from every token** — M2M
and user alike — and every `@RolesAllowed` endpoint answers 403. So:

- A new realm role must be appended to every `scopeMappings` entry in the same change.
  `check-realm-template-importable.py` rule R11 enforces it: roles added after the first
  version of this note (`ROLE_MARKETING`, `ROLE_MERGE_RECORDER`) were missing from all 41
  entries until it did, and a cold-start import would have dropped them from every token.
- A new client gets `fullScopeAllowed: false` and its own `scopeMappings` entry.
  `openbank-synthetic-catalog-read` is the one client with no entry: it is meant to carry
  no realm roles at all.

The equivalence was measured, not argued: import the template before and after into a
local Keycloak at the `keycloak.yaml` version, then for every client × every user
(service-account users included) call
`GET /admin/realms/<r>/clients/<id>/evaluate-scopes/generate-example-access-token?userId=<u>`
and compare `realm_access`, `resource_access`, `aud` and `scope`. All 1,769 pairs were
identical. With `scopeMappings` removed, all 1,435 pairs that carry roles lost every one of
them, which shows the comparison can see the failure it guards against.

Like everything else here, this is a cold-start-only change. A running realm keeps its
current switches until someone applies the same mapping with `kcadm` (see
`openbank-infra/CLAUDE.md`). Add the mappings **before** you turn the switch off.

## Secrets

Every client secret and user password is a `__PLACEHOLDER__` token. **This repo is
public** — a literal must never land here. Real values live in Vault KV and reach the
cluster through `components/external-secrets/es-*.yaml`; the substitution happens in a
trusted shell at reconcile time, per
[`docs/runbooks/0009-keycloak-realm-import-reconcile.md`](../../../../docs/runbooks/0009-keycloak-realm-import-reconcile.md).

A **confidential** client (`publicClient: false`) with no `secret` field is not a
harmless omission: Keycloak generates a random secret on import, so a rebuilt realm issues
credentials the service does not present, and the failure appears as an authentication
outage rather than as a missing field. Public clients correctly carry no secret —
`openbank-app`, `openbank-grafana` and `apicurio-registry` are the three that legitimately
have none.

A placeholder here must use the same Vault value as the `es-*-oidc.yaml` entry the
consuming service reads, or a cold-started realm and the running service disagree.

## Synthetic customer identity (ADR-0331) — live provisioning

`clients[openbank-synthetic-retail]` in `customers-realm-template.json` is a bank-owned canary
customer: a confidential client whose service-account user carries `ROLE_CUSTOMER` and the
`party_id` of one SYNTHETIC party (ADR-0252). The template is the shape the
`synthetic-customer-identity` gate holds. It does not create anything, because the live realm
is imported from Vault on cold start only. Provisioning is an owner step:

1. **Create the party.** `POST /api/v1/parties` on party-service with `classification: SYNTHETIC`
   and no personal data. The call needs `ROLE_ADMIN`. Record the party id.
2. **Create the client** from the template entry with `kcadm create clients -r openbank-customers`,
   **without** `secret`, so Keycloak generates one.
3. **Shape the service-account user.** Grant the realm role `ROLE_CUSTOMER` and nothing else. Set
   the user attribute `party_id` to the id from step 1.
4. **Store the secret.** Put the generated secret in Vault KV under
   `keycloak/synthetic-retail`, property `client_secret`. Then add the client, the
   service-account user, the secret and the party id to the Vault realm-import JSON, following
   [runbook 0009](../../../../docs/runbooks/0009-keycloak-realm-import-reconcile.md), so a
   cold-started realm keeps the identity.
5. **Verify the token's claims.** Mint a token for the client and decode the claims without
   printing the token: `iss` is `https://kc.open-bank.tech/realms/openbank-customers` (the realm's
   discovery document reports this frontend issuer through both the in-cluster and the public
   URL, measured 2026-10-04), `party_id` is the SYNTHETIC party,
   `realm_access.roles` holds `ROLE_CUSTOMER` and nothing beyond the realm defaults, and
   `preferred_username` is `service-account-openbank-synthetic-retail`.

`openbank-infra/scripts/provision-synthetic-customer.sh` does steps 1-5 in order and never prints a
secret or a token. Step 1 signs you in through the browser with the operator CLI client
`openbank-ops-cli` (#10905); the live realm predates that client, so the script first creates it
from its reviewed `realm-template.json` entry if it is missing.

The taint switches on when that principal is honoured by customer-edge's
`OPENBANK_SYNTHETIC_TRUSTED_PRINCIPALS`. It is already listed, inert until step 2 exists.
Removing it from that list switches the canary back to real, which is the containment lever.

## Passkeys for the staff realm (`openbank`)

The `openbank` realm turns on Keycloak's native passkey support
(`webAuthnPolicyPasswordlessPasskeysEnabled`). The browser flow does not change:
username + password (+ OTP for users who configured it) still works, and the demo account
keeps signing in with a password only. The login page additionally offers a passkey, both
as username-field autofill (conditional UI) and as a button.

- **Cross-device / QR.** `AuthenticatorAttachment` is `not specified` on purpose. `platform`
  would refuse the WebAuthn hybrid transport, which is the flow where the desktop browser shows a QR
  code and the phone camera completes the sign-in with its own passkey.
- **RP ID is empty**, so it defaults to the Keycloak host the login page is served from. The same
  template then works for `*.local` and the deployed host.
- **Enrolment** is per user, opt-in: account console, then *Signing in*, then *Passkey*, then *Set up*.
  Nothing forces it, so no existing login breaks.
- The template is imported on cold start only (see above). On a running realm, apply the same
  attributes with `kcadm.sh update realms/openbank -s webAuthnPolicyPasswordlessPasskeysEnabled=true ...`.

## Admin UI login theme

`openbank-admin-ui` selects `openbank-admin` through its `login_theme` client attribute.
Keycloak uses the shared `openbank` theme for clients without an override. The Admin UI
theme inherits Keycloak's PatternFly 5 templates, including the password and WebAuthn
forms. The theme bundles Czech and English copy. The language selector follows the realm's
localization settings.

Run `bash gen-theme-configmap.sh` from this directory after changing theme sources or
the Prague lion in `openbank-admin-ui/public/brand/`. The generated checksum rolls the
Keycloak pod. Verify the mount paths, asset and client scope with
`python3 openbank-infra/scripts/keycloak_theme_configmap_test.py` from the repository root.

After the reviewed GitOps change syncs and Keycloak is ready, set `attributes.login_theme`
to `openbank-admin` on the existing Admin UI client in the staff realm. Preserve the other
client attributes. An existing realm skips startup import, so apply the same attribute to
the disaster-recovery import through [runbook 0009](../../../../docs/runbooks/0009-keycloak-realm-import-reconcile.md).
To roll back the running client, remove the attribute so it inherits the realm theme.

## Relocated prose

The five comments removed from `customers-realm-template.json`, by path.

### `identityProviders[kubernetes]` (realm-template.json, ADR-0177 Phase 1)

First identity provider in either template: the cluster's Kubernetes OIDC issuer,
trusted so a pod's projected ServiceAccount token can be submitted to the native
JWT Authorization Grant, followed by standard RFC 8693 exchange of the returned
Keycloak access token (see `docs/adr/0177-workload-identity-m2m-auth.md`).
The import shape is verified against Keycloak 26.8.0 (`firstBrokerLoginFlowAlias`,
not the `…FlowId` a realm export from older versions shows). `config.issuer` and
`config.jwksUrl` carry `__KUBERNETES_OIDC_ISSUER__` — the cluster's
`--service-account-issuer` URL, which is deployment-specific, so the owner supplies it
at render time (it becomes one more required env var in
`render-verify-keycloak-realm-import.sh` runs). `config.clientSecret` is a placeholder
for the same reason; if the API server does not authenticate JWKS clients, any non-empty
value works — Keycloak requires the key to exist, the cluster does not check it. The IdP
is `enabled: true` and `hideOnLogin: true`: workload authentication remains available
without a Kubernetes sign-in button. The EKS JWKS path is `<issuer>/keys`.
The JWT grant validates `RS256`, requires a pre-provisioned federated user and checks
the audience; `disableTypeClaimCheck` stays false. Assertion reuse is allowed for
kubelet rotation, maximum assertion age is 7200 seconds, and access-token lifetime
is capped by assertion expiration.

### `clients[openbank-agent].attributes[standard.token.exchange.enabled]` + `authorizationSettings` (realm-template.json, ADR-0177 Phase 1)

The client enables the native JWT grant for the `kubernetes` IdP. Its
`oauth2.jwt.authorization.grant.audience` is serialized as
`[{"key":"kubernetes","value":"keycloak"}]`, not `["keycloak"]`.
The projected JWT itself correctly carries `aud: ["keycloak"]`.
`users[wl-openbank-agent]` supplies the explicit ServiceAccount federation link and
only `ROLE_API`. Unknown subjects are rejected instead of registered.

The returned access token can be exchanged using standard token exchange V2 on
`openbank-agent`; this path needs neither `subject_issuer` nor FGAP IdP permissions.
The existing `authorizationSettings` block is not what authorizes the native path.
Client authentication and the `__AGENT_CLIENT_SECRET__` client-credentials fallback
remain available; this configuration does not switch the service's outbound client.

Run `python3 openbank-infra/e2e/keycloak_workload_identity_test.py` before changing
this contract. It exercises real Keycloak with synthetic assertions, including
negative audience, identity and signature cases. Apply the reviewed attributes to
the live realm separately; persist the verified template using runbook 0009 so a
fresh realm has the same behavior. No custom provider or image rebuild is needed.

### `clients[openbank-app].redirectUris` / `webOrigins`

Exactly two callbacks, both matched literally: the claimed HTTPS link
`https://open-bank.tech/app/oauth/callback` (App Links / AASA) and the private-scheme floor
`tech.openbank.app://oauth/callback`. Those are the only two the app sends (its
`RedirectUriNegotiator` picks one of them). There is no loopback entry: a `http://localhost:*`
pattern let any local process start an authorize request, ride the browser SSO session and mint
a token with `azp=openbank-app`, which defeats the app-session checks downstream (#16612).
A local or desktop flow that needs loopback gets its own dev-only client, never this one.
`webOrigins` is empty because the app is a native client and makes no CORS requests; `+` would
have widened CORS to every redirect origin.

### `clients[openbank-app].protocolMappers[sub]`

This realm import defines explicit (non-default) client scopes, so Keycloak does NOT
attach the built-in `basic` and `roles` scopes that normally carry `sub` and
`realm_access.roles`. Both are declared directly on the client so customer-edge can
resolve identity (sub→partyId, ADR-0065/0069) and authorize (`@RolesAllowed ROLE_CUSTOMER`).

### `clients[openbank-app].protocolMappers[openbank-edge-audience]`

Adds `openbank-edge` to the JWT `aud` claim so that the copilot-service (OIDC
client-id=`openbank-edge`, application-type=service) accepts customer bearer tokens.
Quarkus service-type rejects JWTs whose `aud` is present but does not include client-id;
tokens without an explicit audience mapper carry only Keycloak's built-in `account`
audience, which causes Quarkus to fall back to introspection (denied — public client, no
secret). `id.token.claim=false` keeps the ID token lean.

### `clients[openbank-app].protocolMappers[openbank-rum-audience]`

Adds `openbank-rum` to the JWT `aud` claim. The mobile-RUM ingest gateway (ADR-0088 §D4)
is an OTel collector whose OIDC authenticator requires `audience=openbank-rum`, and
`openbank-rum` is not a Keycloak client — it is a resource name — so it needs a CUSTOM
audience, not a client one. Without this the app's session token carries only
`openbank-edge` and every OTLP export is rejected 401: mobile RUM produced literally zero
spans from the day it was switched on, and a crash's `trace_id` pointed at a trace that
did not exist. `id.token.claim=false` keeps the ID token lean.

### `clientScopes[profile]`

This explicit-scope realm import does NOT get Keycloak's built-in client scopes. The app's
authorize request includes `scope=openid profile` (OAuth2/OIDC standard), so `profile`
must exist as an assigned scope or Keycloak rejects the request (`Invalid scopes: openid
profile`). The app reads identity via the `sub` + realm-roles mappers on `openbank-app`,
not profile claims, so this scope is intentionally claim-less — it only satisfies request
validation.

### `clientScopes[telemetry:rum]`

RUM O4 consent enforcement (ADR-0088/O4). When the app requests this optional scope and
the user consents, Keycloak fires the `openbank-rum` audience mapper, adding
`openbank-rum` to the JWT `aud` claim. The RUM gateway OIDC extension accepts only
`audience=openbank-rum`; tokens with only `aud=openbank-app` are rejected 401 —
server-side enforcement without custom OTel processors.

### `roles.realm[CATALOG_SCOPE_*]` — removed, deliberately

`realm-template.json` declared `CATALOG_SCOPE_READ`, `CATALOG_SCOPE_AUTHOR` and
`CATALOG_SCOPE_PUBLISH` as realm roles. They are gone as of 2026-08-16 and must not come
back. They are **runtime-derived internal roles, not grantable realm roles**:
`CatalogScopeIdentityAugmentor` in openbank-product-catalog synthesises them per request
from the token's OAuth `scope` claim (`catalog:read` / `catalog:author` /
`catalog:publish`). The narrow pension-review exception below derives READ from a
verified, named human realm approver role. Neither path grants a realm role named
`CATALOG_SCOPE_READ`.

Declaring them in the realm makes them assignable in the Keycloak admin console, so an
operator could grant `CATALOG_SCOPE_PUBLISH` to a user directly and reach every
`@RolesAllowed(CATALOG_SCOPE_PUBLISH)` endpoint with a token carrying no catalog scope
at all. That is the exact bypass the augmentor exists to prevent, which makes "create the
role" the wrong half of the parity checker's own remediation hint (*"Create the role …
or delete the declaration"*) — here it is always the delete.

Removing them also clears three `check-realm-role-parity` findings that had been failing
the `keycloak-realm-drift` CronJob (`declaredNotLive` + `namedByCodeButNotLive`, 9/1/15
`@RolesAllowed` sites respectively).

Pension revision approval uses two separately assignable human realm roles,
`PENSION_LEGAL_APPROVER` and `PENSION_PRODUCT_OWNER`. Neither role is assigned to a
user, group, or client by the committed templates. Product Catalog derives its internal
`CATALOG_SCOPE_READ` role for a verified human token carrying either approver role;
Admin UI makes the same display-only derivation. A service-account identity cannot use
those roles to decide an approval. Assign the two roles to separately authorized people
through the approved identity process and verify their live access tokens and the
two-person approval flow before enabling publication. Do not make either approval role
or `catalog:read` a default scope of the Admin UI client.

**Catalog read and write grants remain separate.** `catalog:read` is declared in the
staff realm imports and assigned to `openbank-admin-ui` as an optional scope in both
Docker and GitOps. The Admin UI explicitly requests it during login; it is not a default
grant. This enables a cold staff realm to issue the read scope consumed by the augmentor.
`catalog:author` and `catalog:publish` are still absent, and no catalog scope is assigned
by the customer realm. Write-scope provisioning remains a separate authorization decision.

These source declarations and their offline parity regression do not establish the live
realm's assignments or a deployed Admin UI image. The role-parity gate does not verify
client-scope assignments; runtime access and import reconciliation require separate evidence.

### `clients[openbank-edge-webauthn].description`

Shortened to fit Keycloak's 255-char cap. In full: service-account client for
customer-edge's own WebAuthn RP (ADR-0066 F2, variant B1, `WebAuthnKeycloakClient`).
Creates the Keycloak user for a party enrolling a native passkey (`manage-users`), then
mints that user a real session via RFC 8693 token-exchange scoped to the `openbank-app`
audience (impersonation + a token-exchange fine-grained-authz permission on
`openbank-app`). Kept separate from `customer-edge-admin`, whose blast radius is
deliberately narrower (`party_id` attribute writes only).

## MCP connect (ADR-0385 D3, #16672) — REQUIRED owner apply step

Nothing in this section is live until an owner does it: realm import runs on cold start
only, and there is no in-repo job that applies a realm change to a running Keycloak (by
design — the admin API is 403 at the edge and reached only through a port-forward). Until
every step is done the MCP connect fails CLOSED: the authenticator refuses every connect.
Do them in this order; each is idempotent.

1. **Image.** Build and push the Keycloak image that carries the extension
   (`openbank-infra/scripts/build-push-keycloak.sh`), then bump the `openbank-keycloak` tag in
   `keycloak.yaml` in a PR. The `KC_SPI_AUTHENTICATOR__OPENBANK_MCP_WITHHELD_CODE__*` env in
   `keycloak.yaml` is runtime config read by that extension at boot.
2. **Client + secret (`openbank` realm).** Create client `openbank-keycloak-mcp` from
   `realm-template.json` **without** its `secret` (Keycloak generates it), give
   `service-account-openbank-keycloak-mcp` the realm role `ROLE_API` and nothing else, and write
   the generated secret to OpenBao `openbank/keycloak/keycloak-mcp`, property `client_secret`,
   with the narrow SSO writer role (runbook 0027 — never the root token).
   `provision-synthetic-customer.sh` is the worked example of this exact sequence. External Secrets
   then fills `iam/keycloak-mcp-sca-client` within the hour (`refreshInterval: 1h`); the
   Keycloak pod reads it at boot, so **restart the Deployment** after the Secret exists
   (`kubectl -n iam rollout restart deploy/keycloak`). Then move `keycloak/keycloak-mcp` from
   `openbao.unprovisioned` to `openbao.keys` in `.github/config-inventory.yaml`.
3. **Customers realm (`openbank-customers`).** Over `kubectl -n iam port-forward svc/keycloak 8080:8080`,
   apply from `customers-realm-template.json`, in this order (a policy naming a missing profile,
   or a client override naming a missing flow, is refused):
   - the user profile (`PUT /admin/realms/openbank-customers/users/profile`) — `party_id`
     admin-view/admin-edit only;
   - the flow `openbank-mcp-fresh-passkey` and its executions;
   - client profiles, then client policies (`PUT .../client-policies/profiles`, then
     `PUT .../client-policies/policies`) — profiles `openbank-mcp-oauth-hardening`,
     `openbank-mcp-dpop`, `openbank-mcp`; policies `openbank-mcp-tier-h`, `openbank-mcp-tier-l`,
     `openbank-mcp`; these PUTs REPLACE the realm's whole list, so send the template's full list;
   - the MCP clients and their `offline_access` scope mappings (they ship `enabled: false`).
   Rehearse the exact calls against a local Keycloak first (recipe at the top of this file);
   `openbank-infra/e2e/keycloak_mcp_client_admission_test.py` is the end-to-end check of the result.
4. **Import artifact.** Add the same client (with its real secret) and the customers-realm
   changes to the OpenBao realm-import blobs (runbook 0009), so a cold start keeps them.

Verify: the Keycloak boot log carries **no** `openbank-mcp-withheld-code is NOT configured`
WARN, and an MCP connect against the live realm reaches the in-app approval.

## What this does NOT fix

The committed templates feed **nothing** today. Keycloak reads the Secrets
`keycloak-realm-import` / `keycloak-customers-realm-import`, which External Secrets fills
from Vault KV, and those are a strict stale ancestor of these files (measured in #3246:
4 roles / 2 clients against 14 / 10). Making the templates importable is the precondition
for the reconcile, not the reconcile itself — that is an owner-gated `vault kv put`,
runbook 0009.

## Customers realm: MCP clients and the MCP token exchange (ADR-0385 D1, ADR-0383 D4)

Every `customers-realm-template.json` entry carrying the `openbank.mcp.tier` or `openbank.mcp.exchange`
attribute is DERIVED from `openbank-mcp-service/src/main/resources/mcp-client-allowlist.yaml` by
`.github/scripts/gen-mcp-realm-clients.py`; never hand-edit them, and the `mcp-client-realm-parity`
gate fails on any drift. The exchange entries are: `openbank-mcp-service`, the confidential RFC 8693
requester (no flow, no service account, exchange enabled on this client only, 60 s tokens, optional
scopes = the declared read scopes, secret `__MCP_CUSTOMER_EXCHANGE_CLIENT_SECRET__` from OpenBao
`keycloak/mcp-service-customer-exchange`); one bearer-only audience client per downstream service;
and one client scope per downstream carrying exactly one audience mapper. Adding the requester to the
OpenBao import artifact is an operator step (runbook 0009). The exchange of a DPoP-bound subject token
has not yet been run against a live Keycloak of the deployed version.

### Customer MCP certificate-bound read grant

The staff realm template declares two disabled confidential service accounts. Both have only
`ROLE_API`, with browser, implicit and password flows disabled. `openbank-mcp-read-issuer`
requests a balance-read permit from delegation-service. `openbank-balance-mcp-reader` rechecks
the live grant there. The delegation policy limits each to its own action.

For a separate, owner-approved sandbox rollout, provision their credentials at the OpenBao
sources declared in `keycloak-realm-import-secret-sources.yaml`. Configure the issuer's
`MCP_READ_ISSUER_TOKEN_URL` and the reader's `BALANCE_MCP_SERVICE_OIDC_URL` against the staff
realm. The customer access and certificate-bound balance tokens still use the customers realm.
The two service clients remain disabled on import, and this template adds no route.

The Keycloak extension registers the customer MCP read grant. It starts disabled. The local
sandbox configuration must supply all of the following before enabling it:

- `MCP_READ_GRANT_ENABLED=true` and `MCP_READ_CONNECTION_BINDING_ENABLED=true`
- The HTTPS `MCP_READ_CUSTOMER_ISSUER_URL`, `MCP_READ_RESOURCE_URL` and
  `MCP_READ_DELEGATION_ORIGIN`
- Match `MCP_READ_RESOURCE_URL` to the canonical resource in the MCP client allowlist.
  The customer route change (#16996) aligns that allowlist and the generated customer realm
  with the external route. Keep customer admission and the read grant disabled until the route,
  allowlist and deployed realm agree.
- The HTTPS `MCP_READ_ISSUER_TOKEN_URL` and `MCP_READ_ISSUER_CLIENT_SECRET` for the dedicated
  `openbank-mcp-read-issuer` service account
- Separate base64url-encoded `MCP_READ_NONCE_KEY` and `MCP_READ_PROOF_KEY` values of at least 32
  bytes, and a unique `MCP_READ_DEPLOYMENT` namespace

The requester uses the dedicated confidential X.509 client. The issuer verifies the original
proof and the resource nonce. The resource nonce authenticates version, exact resource URI,
DPoP key thumbprint, expiry and random challenge. PostgreSQL independently enforces first use of
the original proof via a reference derived with the separate proof key. The nonce key is shared
between the MCP resource and Keycloak. Both services must use the same key and resource URI.

### Admin REST service accounts on Keycloak 26.8.2

Keycloak 26.8.2 requires its built-in `kc-admin-api` client scope and realm Admin
REST audience on Admin REST bearer tokens. The customers template assigns the scope
as an optional scope to `customer-edge-admin` and `openbank-edge-webauthn`. Their
Admin REST clients request it when obtaining a service token. Inherited default
scopes, role mappings, and the WebAuthn token exchange scope remain in place.

When upgrading an existing realm, first upgrade Keycloak and use the built-in
administrator to add the new scope as a default on both clients. The upgrade
assigns the scope automatically only to the built-in administrator clients.
The default assignment lets the previous customer-edge release obtain usable
tokens while the new release rolls out. Replace it with the optional assignment
after every customer-edge instance requests the scope. Verify each workload's
normal Admin REST operation with a fresh token before reopening traffic.
Invalidate cached tokens minted before the upgrade by restarting the affected clients.

### Verify the pinned ChatGPT client metadata

Before approving a ChatGPT static CIMD pin or key rotation, run
`python3 .github/scripts/verify-chatgpt-cimd-pin.py` from an approved operator workstation.
It reads the two exact public vendor documents, compares them with the reviewed registration
and inline public key, and prints the digest to compare with the sandbox deployment pin.

Promote and verify the signed, optimized Keycloak image containing the scoped static-CIMD
provider before applying its digest pin in the shared sandbox Deployment. The exact registered
ChatGPT client must independently pass the enabled, callback, native browser flow, confidential
RS256, S256 and PAR checks. Vendor activation and a successful authenticated PAR remain separate
from the pin rollout.

After the new pod is Ready and the old pod is gone, GET the customers realm's OpenID
configuration and issuer-rooted OAuth authorization-server metadata. Both omit CIMD while the
exact client is disabled. During the approved pilot, both advertise CIMD with the same issuer,
PAR endpoints and authentication methods. Confirm an unrelated realm still omits CIMD. Roll back
by reverting only the pin and verifying omission after the replacement pod is Ready.
