# OPA (Open Policy Agent) for fine-grained per-resource authorization

Date: 2026-05-29
Status: Superseded by ADR-0034
Delivery-Status: N/A
Author(s): jiri.raska

## Context

K7 of the 2026-05-28 audit found 36 occurrences of `@PermitAll` on business
endpoints across the 28 services — far too coarse. Op-ex 3 (NetworkPolicy)
and Op-ex 5 (Istio mTLS + JWT) close the L3/L4 and the "is this caller
authenticated" question, but neither answers the next-layer question:

> Is *this principal* allowed to perform *this action* on *this specific resource*?

Examples that today fall back to "any authenticated principal":

- An OPERATOR can edit any party — including parties they did not onboard.
- A COMPLIANCE analyst can read every audit entry — across business units.
- A PSD2 TPP can initiate payments on any account — within the limits of the
  granted consent, but we do not check the consent scope per call.
- A PARTY can read their own party record, but the service has no native
  ownership check.

The `Roles` constants from libs (commit 12ddf72) and Istio `AuthorizationPolicy`
(Op-ex 5) handle role-based gates. They do not handle resource-scope checks
("this user owns this account") or context-sensitive policy ("after 6pm,
require two-person approval for transfers > €10k").

## Decision

We will adopt **Open Policy Agent (OPA)** for fine-grained authorization,
deployed as a **sidecar per service** in the `openbank` namespace, with
policies written in **Rego** and shipped from a single repo-local
`policy-bundle`. Quarkus services consult OPA via a thin in-libs client:

```kotlin
@RolesAllowed(Roles.OPERATOR)
@Authorize("party.update", resource = "#partyId")
suspend fun updateParty(@PathParam("partyId") partyId: PartyId, ...) { ... }
```

The `@Authorize` interceptor:
  1. Builds a query `{ principal, action, resource, attributes }`.
  2. Calls the local OPA sidecar (`http://localhost:8181/v1/data/openbank/allow`).
  3. Throws `ForbiddenException` if `allow == false`.

Policies live in `openbank-infra/opa/policies/` and bundle into an OCI
artefact that the OPA sidecar pulls on startup + refreshes every 60s.

## Alternatives considered

- **Quarkus security-jpa (declarative @RolesAllowed only)**. What we have
  today. Insufficient for ownership / scope / context checks.
- **Cedar (AWS)**. Newer, terser policy DSL, designed for this exact
  problem. Smaller community than OPA right now, no first-party Kotlin
  client, fewer Quarkus integrations. Worth re-evaluating in 12 months;
  pick OPA for the reference setup today.
- **In-code if/else policy in each service**. Pattern that bit the audit
  — 36 places where `@PermitAll` shortcuts the question. Reject.
- **Casbin / Keto**. Mature, but their policy models are matrix-shaped
  (subject × object × action) rather than rule-shaped. Cannot express
  context-aware policies cleanly.

## Consequences

**Positive**
- K7 closed: policy moves out of code into bundled Rego, reviewable as a
  single artefact.
- DORA Art. 8 (access control) + Art. 9 (cryptographic controls layered on)
  satisfied with auditable policy decision logs (OPA emits one per `allow`
  call).
- PSD2 consent-scope check becomes a Rego policy, not a per-controller if/else.
- Two-person rule, after-hours, geographic restrictions become editable
  policy without code redeploy.

**Negative**
- One more sidecar per pod (memory ≈ 30 MiB, p99 latency hit ≈ 1–2 ms).
- Rego learning curve for operators. Mitigated by a small policy DSL
  reference doc + reviewed templates.
- Policy bundle distribution needs an OCI registry (Harbor / GitHub
  Packages / ECR / GAR). One more piece of infra to operate.

**Neutral**
- Istio AuthorizationPolicy (Op-ex 5) is layered defence: Istio answers
  "can this caller reach this endpoint at all"; OPA answers "is the
  caller-action-resource combination permitted". Both stay.

## Migration plan

1. **libs/security**: `@Authorize` annotation + `OpaClient` (HTTP, 2s
   timeout, fail-closed). Tests against a dockerised OPA in `libs.testing`.
2. **openbank-infra/opa/**: policy-bundle skeleton with one starter
   policy (`party.read` checks `principal.id == resource.ownerId`). Build
   script that bundles to OCI artefact.
3. **openbank-infra/k8s/base/opa-sidecar.yaml**: shared sidecar template
   that every service deployment patches in via Kustomize.
4. **Reference rollout on party-service**: replace 6 `@PermitAll` /
   coarse `@RolesAllowed` annotations with `@Authorize("party.X", resource = "#id")`.
5. **Per-service rollout** (opportunistic, when touching auth on a service):
   audit, ledger, transaction, sca, consent, kyc, aml. Track in
   `docs/strategy/07-compliance-matrix.md` K7 row.

## Compliance impact

- **K7** (audit 2026-05-28): closed for migrated services.
- **DORA Art. 8** (asset and access governance): policy bundle is the
  asset, decision log is the evidence.
- **GDPR Art. 25** (privacy by design): ownership / scope checks become
  the default path, not an opt-in.
- **PSD2 RTS / CNB**: per-consent scope enforcement becomes a single
  policy file, not a 28-service grep.

## References

- ADR 0014 — openbank-libs centralization (where `@Authorize` will live).
- 2026-05-28 audit — K7 audit endpoint @PermitAll, 36 PermitAll occurrences.
- ADR 0017 — secrets via Vault (sibling Op-ex from the SBOM follow-up).
- [Open Policy Agent docs](https://www.openpolicyagent.org/docs/latest/)
