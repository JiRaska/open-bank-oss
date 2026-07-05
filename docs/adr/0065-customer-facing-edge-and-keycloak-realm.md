# 65. Customer-facing edge (BFF + gateway) and a dedicated Keycloak customer realm

Date: 2026-06-05
Status: Accepted
Delivery-Status: Shipped
Author(s): OpenBank platform

**Delivery note (updated 2026-07-05):** the "Implementation — Pending" note below was stale
— `openbank-customer-edge` (v0.17.1) has long since shipped: real gitops deployment
manifests (`openbank-infra/gitops/components/customer-edge/`), the `openbank-customers`
Keycloak realm (`openbank-infra/scripts/seed-customers-realm.sh`), an OPA ownership-check
bundle, and a route surface far beyond the original 7-route allow-list (accounts, balances,
pockets, savings goals, SCA device enrollment/challenge/decision, onboarding, and more — see
`CustomerEdgeResource.kt`/`OnboardingResource.kt`). The one item still exactly as originally
scoped is device attestation: it remains advisory-only behind a feature flag (no enforcing
App Attest/Play Integrity check found in the edge code), which is the explicit, reversible
sandbox posture this ADR always called for — not a regression, and not blocking Shipped
status for the rest of the decision.

<details>
<summary>Original delivery note (2026-06-30), superseded above</summary>

- **Decision and boundaries** — ✅ Complete: ADR records the decision; `openbank-customers` realm design (PKCE public client, party-scoped roles, WebAuthn), deny-by-default route allow-list, device attestation hook (advisory mode behind feature flag), and staff/customer trust-boundary separation are all specified.
- **Implementation** — ⬜ Pending: concrete artifacts (realm export, customer edge deployment manifests, OPA ownership policies per route, edge service code) are deferred as follow-up issues per ADR-0052; no customer-facing edge or `openbank-customers` realm exists in the cluster today.

</details>

## Context

The retail customer app (ADR-0064, Kotlin Multiplatform) needs a way to reach the cluster.
Today the only browser→cluster path is the **admin-UI BFF** (ADR-0056), which is deliberately
*operator-only*: it relays the operator's Keycloak session/bearer to in-cluster services it
resolves via discovery (ADR-0051). Reusing it for retail traffic would be a serious mistake:

- **Different principal, different realm.** Operators authenticate in the `openbank` realm with
  staff roles. Customers must authenticate as *parties* (account holders) with entirely
  different scopes. Mixing them in one realm/edge blurs the staff/customer trust boundary.
- **Different blast radius.** The admin BFF can fan out to *every* `openbank-*` management and
  business endpoint. A customer must only ever reach a narrow, explicitly allow-listed set of
  *their own* resources (their accounts, balances, transactions, payment initiation, SCA).
- **Different threat model.** The customer edge is internet-facing to untrusted devices at
  scale; the admin plane is a small, known operator population. They warrant different rate
  limits, bot/abuse defenses, and attestation requirements.

The README's honest status already records this gap: *"No customer-facing app — only an
operator Admin UI exists"* and *"SCA is a stub"*. ADR-0064 explicitly deferred this edge to its
own decision — this is it.

## Decision

**We will stand up a separate customer-facing edge as the sole path from the customer app to
the cluster, backed by a dedicated Keycloak `openbank-customers` realm. The customer edge and
the admin edge share no realm, no client, and no proxy.**

Concretely:

1. **Dedicated Keycloak realm `openbank-customers`.** Customers are *parties*, not staff.
   - A **public OAuth2 client** for the app using **Authorization Code + PKCE** (no client
     secret on the device); short-lived access tokens, rotating refresh tokens.
   - The token's `sub`/a `party_id` claim binds the session to a `openbank-party-service`
     party. Customer scopes (e.g. `accounts:read`, `payments:initiate`) are realm roles,
     entirely disjoint from operator roles.
   - WebAuthn/passkeys configured as a first-class authenticator (feeds ADR-0021 enrollment).

2. **A customer edge tier — its own BFF + gateway, mirroring the ADR-0056 *pattern* but not the
   instance.** The customer app issues only same-origin/`api.` requests; the edge does all
   in-cluster egress, resolving services via the same discovery source of truth (ADR-0051) and
   injecting the *customer* bearer. The edge is the single north-south entry point for retail
   where authn/z, rate-limiting, abuse defense, and observability attach (consistent with the
   gateway role in README's stack table).

3. **Explicit, narrow allow-list — deny by default.** Unlike the admin BFF's broad fan-out, the
   customer edge exposes only an enumerated route set, each scope-gated and **ownership-checked**
   (the party in the token must own the resource). Initial set:
   `accounts:read`, `balances:read`, `transactions:read`, `statements:read`,
   `payments:initiate`, `sca:enroll-device`, `sca:decide`. Everything else → 404/403.
   Per-resource ownership is enforced via OPA (ADR-0018/0034), not ad-hoc code.

4. **Edge auth fails closed.** A request with neither a valid customer session nor a verified
   bearer is rejected (401) *before* any backend is touched — defense-in-depth on top of
   per-service RBAC, exactly as ADR-0056 mandates for the admin proxy.

5. **Device attestation hook.** The edge is where App Attest / Play Integrity assertions
   (ADR-0064) are checked. **Sandbox shortcut:** attestation runs in *advisory* mode behind a
   feature flag (logged, not enforced); SMS-OTP step-up is skipped entirely. Both are explicit
   and reversible — production posture flips the flags.

This ADR records the **decision and boundaries** only. Concrete artifacts (realm export, edge
deployment manifests, OPA policies, route handlers) are follow-up implementation work tracked
as issues (ADR-0052), one PR per concern.

## Alternatives considered

- **Reuse the admin-UI BFF (ADR-0056) for customers** — fastest, no new component. Rejected:
  collapses the staff/customer trust boundary into one realm and one over-broad proxy; a
  customer would transit the same tier that can reach every management endpoint. Categorically
  wrong for an internet-facing retail channel.
- **One Keycloak realm with both staff and customer roles** — simpler IAM ops. Rejected:
  shared realm = shared password policy, shared brute-force/lockout surface, and role-confusion
  risk; PSD2/operational separation wants distinct realms.
- **Direct app→service calls (no edge), tokens straight to services** — no extra hop. Rejected:
  no single place for rate-limiting/abuse defense/attestation; pushes ownership checks into
  every service; same "direct fan-out is the wrong topology" failure ADR-0051 already named.

## Consequences

**Positive**
- Clean staff/customer separation at both the IAM (realm) and network (edge) layers.
- Deny-by-default, ownership-checked retail surface — minimal blast radius.
- One place to attach rate-limiting, abuse defense, and device attestation (ADR-0064).
- Reuses existing building blocks: discovery (ADR-0051), OPA (ADR-0018/0034), BFF pattern (0056).

**Negative**
- A new realm + edge tier to deploy, secure, and operate (cost, ops surface).
- Per-resource ownership policies must be authored in OPA for each exposed route.
- Two BFF codepaths (admin + customer) to keep patterns consistent.

**Neutral**
- The edge can be a thin BFF in the app's stack or a gateway config; choice is an
  implementation follow-up, not part of this decision.

## Compliance impact

- **PSD2**: dedicated customer realm + edge enforce SCA scoping and the ADR-0021 device
  channel; SMS-OTP skip is **sandbox-only**, flagged.
- **GDPR**: ownership checks ensure a party can reach only their own data; edge is the audit
  choke point for customer access.
- **DORA**: single internet-facing choke point for retail → rate-limiting, abuse defense, and
  monitoring concentrate here.
- **PCI DSS**: card surfaces behind the edge must use tokenization; no PAN at the edge.
- **CNB**: not applicable at the edge-topology level.

## References

- ADR-0018 / ADR-0034 — OPA fine-grained / unified authz (ownership checks).
- ADR-0021 — SCA decoupled device approval (enrollment + decision routed via this edge).
- ADR-0051 — Generic service discovery + single north-south gateway.
- ADR-0056 — Admin-UI BFF as the sole browser→cluster path (the pattern mirrored here).
- ADR-0064 — Customer app on Kotlin Multiplatform (the client this edge serves).
