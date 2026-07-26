---
date: 2026-07-26
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [authz, security-ops]
summary: "Extend @Authorize resource extraction to a dotted field path, and scope consent.grant/consent.revoke for the shared M2M principal to grantee=party-service:marketing-comms only, instead of opening those actions fleet-wide."
---

# ADR-0206 — Scope M2M consent grant/revoke by grantee resource

## Context

ADR-0205 D5 requires `openbank-party-service` to forward the mobile app's
marketing-consent toggle to `openbank-consent-service` (`POST /api/v1/consents`,
`DELETE /api/v1/consents/{id}`) instead of writing the `parties` table
directly. Both endpoints are `@Authorize`-gated: `consent.grant` and
`consent.revoke`.

Reading `openbank-infra/gitops/components/consent/consent-opa-bundle.yaml`
(`consent_rest_ext.rego`) before writing any forwarder code surfaced a
deliberate, documented restriction: the shared M2M principal
(`service-account-openbank-services` — nearly every backend service
authenticates through this one Keycloak client, so OPA cannot distinguish
party-service from psd2-service or sca-service at this layer) is granted
`consent.read`, `consent.validate`, `consent.activate`, `consent.reject` —
but explicitly **not** `consent.grant`/`consent.revoke`. The rego comment
cites the `edge-service-notification` lesson (issue #266): a blanket
`SERVICE`-type allow opens every `@Authorize` endpoint on that action to any
M2M caller holding the shared client's credentials.

Widening that rule naively (adding `consent.grant`/`consent.revoke` to the
existing `service-consent-m2m` reason) would grant grant/revoke of *any*
consent for *any* party to *every* service on the shared client — the exact
blanket-allow this policy already rejected once. The narrower, correct fix
is a per-service Keycloak client so OPA can key on `principal.id` alone —
but that is a standalone workload-identity project
(see `project-m2m-auth-workload-identity-2026-07-19` in this contributor's
private working notes), not something to fold into a consent-forwarding
feature PR.

`AuthorizeInterceptor.extractResource` (`openbank-libs-runtime`) only
supports `#paramName`, taking the *whole* parameter and rendering it via
`.toString()` into `ResourceRef.id`. There is no dotted-path extraction
(`#request.granteeId`), so a resource-scoped OPA rule cannot see a
structured field of the request body today — only the Kotlin
`data class.toString()` output, which a rego rule would have to
string-match. That is not a policy any reviewer should sign off on: a
`toString()` format change in an unrelated refactor would silently change
what the parser sees.

## Decision

We will:

1. Extend `AuthorizeInterceptor.extractResource` to accept a dotted
   suffix — `#paramName.fieldName` — resolved via Kotlin reflection on the
   parameter's own primary-constructor properties, one level deep only (no
   nested paths). `ResourceRef.id` becomes the field's own `.toString()`,
   not the whole object's.
2. Add `@Authorize(action = "consent.grant", resource = "#request.granteeId")`
   to `ConsentResource.create` and pass `granteeId` explicitly as a query
   attribute on `ConsentResource.revoke` (resolved from the loaded consent
   before the authorize check runs, since the revoke path only carries a
   consent id today).
3. Add a new, narrowly-scoped `allowed_reasons` rule to
   `consent_rest_ext.rego`: the shared M2M principal may `consent.grant` /
   `consent.revoke` **only when** `input.resource.id == "party-service:marketing-comms"`.
   Every other `granteeId` continues to be denied for that principal.

This keeps the shared-client blast radius bounded to one specific,
low-risk consent purpose (a party's own marketing preference, which the
domain layer already restricts to `GDPR_ONLY_SCOPES`/SCA-exempt auto-activate
per ADR-0205 D1) instead of either (a) leaving the forwarder unbuildable, or
(b) opening grant/revoke to the whole fleet.

## Alternatives considered

- **Per-service Keycloak client for party-service** — the structurally
  cleanest fix (OPA keys on `principal.id` directly, no resource-string
  scoping needed). Rejected for *this* PR: it is a cross-cutting
  workload-identity change (new Keycloak client, OIDC credentials,
  ExternalSecrets, `rules.yaml` M2M identity table) touching infra outside
  consent/party-service, already tracked as its own gap. Revisit there
  once that project exists — this ADR's rule should be narrowed further
  (or removed) once party-service has its own principal id to key on.
- **Widen `service-consent-m2m` to include `consent.grant`/`consent.revoke`
  unscoped** — rejected: reopens exactly the blanket-M2M-allow class of bug
  this policy was written to avoid (issue #266 precedent).
- **Do nothing; keep party-service writing `parties.consentMarketing`
  directly** — rejected: this is the split-brain ADR-0198/0205 exists to
  close; D4's Kafka-driven tracking table already assumes consent-service is
  the sole source of truth for marketing consent.

## Consequences

**Positive**
- Unblocks ADR-0205 D5 without a fleet-wide M2M identity project.
- `AuthorizeInterceptor`'s dotted-path extraction is generically useful for
  any future resource-scoped M2M rule, not single-purpose.
- Blast radius of the shared M2M client is *reduced* in absolute terms for
  this action pair (previously: not grantable at all by M2M; now: grantable
  only for one fixed granteeId), not increased.

**Negative**
- `AuthorizeInterceptor` is shared, fleet-wide security code — this PR adds
  a new code path to it and needs its own unit tests (a wrong reflection
  lookup fails closed via `extractResource` returning `null`, i.e. no
  resource scoping, so a bug here would widen access, not narrow it —
  tests must cover that specifically).
- The rego rule is grantee-string-keyed, not identity-keyed — a party-service
  compromise (or a bug that lets another `openbank-services` caller pass
  `granteeId = "party-service:marketing-comms"`) still grants/revokes that
  one specific consent purpose. Accepted risk given the purpose's narrow
  scope (SCA-exempt marketing toggle only).

**Neutral**
- Does not change `consent.read`/`consent.validate`/`consent.activate`/
  `consent.reject` M2M grants — those stay exactly as ADR-0034 D5 phase 5
  left them.

## Compliance impact

- PCI DSS: not applicable — no cardholder data path.
- DORA:    not applicable — not an ICT third-party or resilience change.
- GDPR:    Art. 7(3) (withdrawal of consent) — this ADR is what makes the
  party-service revoke path actually reach consent-service's authoritative
  consent record instead of only a local column, per ADR-0198.
- PSD2:    not applicable — marketing consent is outside AISP/PISP scope
  (`GDPR_ONLY_SCOPES`, ADR-0205 D1).
- CNB:     not applicable.

## References

- ADR-0198 (marketing consent as a first-class consent-service scope)
- ADR-0205 (marketing consent forwarder and SCA-exempt activation)
- ADR-0034 (OPA authorization), D5 phase 5 (M2M principal grants)
- issue #266 (edge-service-notification blanket SERVICE-allow lesson)
- `openbank-infra/gitops/components/consent/consent-opa-bundle.yaml` — `consent_rest_ext.rego`
