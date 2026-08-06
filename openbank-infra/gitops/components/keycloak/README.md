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
`keycloak.yaml` runs) on 2026-08-06, `customers-realm-template.json` carried five
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
  quay.io/keycloak/keycloak:26.6.3 start-dev --import-realm
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

## Relocated prose

The five comments removed from `customers-realm-template.json`, by path.

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

### `clients[openbank-edge-webauthn].description`

Shortened to fit Keycloak's 255-char cap. In full: service-account client for
customer-edge's own WebAuthn RP (ADR-0066 F2, variant B1, `WebAuthnKeycloakClient`).
Creates the Keycloak user for a party enrolling a native passkey (`manage-users`), then
mints that user a real session via RFC 8693 token-exchange scoped to the `openbank-app`
audience (impersonation + a token-exchange fine-grained-authz permission on
`openbank-app`). Kept separate from `customer-edge-admin`, whose blast radius is
deliberately narrower (`party_id` attribute writes only).

## What this does NOT fix

The committed templates feed **nothing** today. Keycloak reads the Secrets
`keycloak-realm-import` / `keycloak-customers-realm-import`, which External Secrets fills
from Vault KV, and those are a strict stale ancestor of these files (measured in #3246:
4 roles / 2 clients against 14 / 10). Making the templates importable is the precondition
for the reconcile, not the reconcile itself — that is an owner-gated `vault kv put`,
runbook 0009.
