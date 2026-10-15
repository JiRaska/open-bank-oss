---
date: 2026-07-19
decision-status: proposed
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
followup: "#12748 — Phase 1 pilot config is committed (realm IdP + token-exchange permission, agent SA projection, rotator seed script) but the live realm application is owner-gated (runbook 0009 vault write + live kcadm/token-exchange verification); Phase 2 fleet rollout and Phase 3 secret retirement are unbuilt"
tags: [authn, secrets, kubernetes]
summary: "Services prove identity to peers with their platform-attested Kubernetes ServiceAccount token, exchanged at Keycloak per RFC 8693, retiring per-service static client secrets and the rotator; OPA authorization is unchanged."
---

# ADR-0177 — Workload-identity service-to-service auth

## Context

Every `openbank-*` service authenticates to its peers with a **Keycloak OIDC
client-credentials** grant (M2M). Each service is a confidential Keycloak client
with a static `client_secret`; the secret lives in OpenBao KV at
`openbank/<service>` under property `OIDC_CLIENT_SECRET`, is synced to a k8s
Secret by an External Secrets `<service>-oidc` ExternalSecret, and mounted as an
env var. This works and is live today (all `<svc>-oidc` ExternalSecrets report
`SecretSynced=True`).

The security of this design rests entirely on **how well we rotate a long-lived
shared secret** — and that machinery is broken in a way that is instructive:

- **ADR-0099 Tier 2** introduced a weekly `secret-rotator` CronJob to rotate the
  OIDC client secrets (and JWT signing keys) via the Keycloak Admin API. On
  2026-07-18 it was found to have failed *every run since it was written*: its
  fatal prerequisite `openbank/keycloak/admin` was never seeded, so `rotate.sh`
  died at step 2 under `set -eu` before doing anything (KubeJobFailed). Seeding
  it (`openbank-infra/scripts/seed-secret-rotator-keycloak-admin.sh`) stops the
  crash.
- **But the rotator is architecturally disconnected from reality.** It *reads and
  writes* `openbank/keycloak/<service>` (property `client_secret`), while the
  services *read* `openbank/<service>` (property `OIDC_CLIENT_SECRET`) — a
  different path **and** a different property name. So even fully wired, a
  successful rotation would change the client secret in Keycloak, write the new
  value to a path **nothing consumes**, and leave every service presenting the
  stale secret → **fleet-wide M2M auth outage**. The current "it skips everything
  because no per-service `client_id` is seeded" state is, by luck, the safe one.

The rotator bug is a *symptom*, not the disease. The disease is that we manage a
**long-lived shared symmetric credential per service** and try to make it safe by
rotating it — a treadmill that is fragile by construction (rotate-in-IdP and
propagate-to-consumer must be atomic and path-correct or auth breaks), scales
badly (30+ services × secrets × ESO wiring × a rotation job that must never race a
deploy), and still leaves a leak window equal to the rotation period.

**Why now:** the fleet is past 30 services and growing; the rotator incident
proves the current model is both unshipped *and* unsafe to "complete". We should
decide the target model before anyone "finishes" the rotator and breaks auth.

## Decision

**We will move service-to-service authentication off static client secrets and
onto workload identity: services authenticate to Keycloak with their platform-
attested Kubernetes ServiceAccount identity, not with a shared secret.**

Concretely, the target model:

1. Each pod requests a **projected ServiceAccount token** (a JWT, `audience`
   scoped to Keycloak, ~1h TTL, rotated by the kubelet, cryptographically bound
   to the pod).
2. Keycloak trusts the **cluster's OIDC issuer** (its JWKS) as a trusted token
   issuer / Identity Provider.
3. The service presents its SA token to Keycloak and obtains a service access
   token via **OAuth 2.0 Token Exchange (RFC 8693)**, carrying the same roles the
   service's Keycloak client carries today.
4. Downstream authorization is **unchanged** — the resulting token still flows
   through `AuthorizeInterceptor` + OPA (ADR-0034); only how the caller *proves
   who it is* changes.

Consequences of the decision:

- **Retire** the static `OIDC_CLIENT_SECRET` per service, the `<svc>-oidc`
  ExternalSecrets, and the `secret-rotator` CronJob (this ADR **amends
  ADR-0099**: the Tier-2 OIDC-secret-rotation objective is withdrawn, not fixed —
  there is no secret to rotate). JWT-signing-key rotation, if still needed, is
  handled separately.
- Identity comes from **attestation** (the platform vouches for the workload),
  not from possession of a secret. The best rotation is no shared secret.

**Interim, only if a bridge period is unavoidable:** switch the Keycloak clients
to **`private_key_jwt` (RFC 7523)** — the service holds a private key, Keycloak
holds only the public key (JWKS), rotation = publish a new public key with an
overlap window; there is no shared secret to mis-propagate. Or, as a pure
stop-gap, correct the existing rotator to be **atomic and path-accurate** (target
`openbank/<svc>` / `OIDC_CLIENT_SECRET`, use Keycloak's rotated-secret + old-
secret-expiry overlap so there is no auth gap, force an ESO refresh after write).
These are bridges, not the destination.

**North star:** if/when a service mesh is adopted, graduate to **SPIFFE/SPIRE +
mTLS** (X.509 SVID per workload at the network layer, JWT-SVID at the app layer).
The SA-token federation above is the same philosophy realised with the components
already in the stack (Keycloak + Kubernetes), at ~20% of the effort; SPIRE is the
end state once a mesh exists.

**Rollout (per-service, behind a flag, never big-bang):**
- Phase 0 (done): `keycloak/admin` seeded → rotator no longer crashes and skips
  all rotation → nothing breaks. **Do not** seed per-service `client_id` into
  `openbank/keycloak/<svc>` — that is the dangerous half-fix. The seed script is
  `openbank-infra/scripts/seed-secret-rotator-keycloak-admin.sh` (idempotent; reads
  the out-of-band `keycloak-bootstrap` secret and copies the admin credentials to
  OpenBao KV `openbank/keycloak/admin` — it does not invent a password the live
  Keycloak would not accept).
- Phase 1: stand up the Keycloak↔cluster-issuer trust + token-exchange config in
  a non-prod realm; migrate 1–2 pilot services, keeping the `oidc-client` secret
  path as a fallback.
- Phase 2: roll across the fleet service-by-service.
- Phase 3: delete the static OIDC secrets, the `<svc>-oidc` ExternalSecrets, and
  the `secret-rotator`.

### Phase 1 delivery note (pilot: openbank-agent-service, 2026-10-14)

What is committed (this repo, no owner credentials needed):

- `openbank-infra/gitops/components/keycloak/realm-template.json`: the realm's
  first identity provider — alias `kubernetes` (OIDC), `__KUBERNETES_OIDC_ISSUER__`
  placeholder for the cluster's `--service-account-issuer`, and on the
  `openbank-agent` client the `standard.token.exchange.enabled` attribute plus a
  `token-exchange` scope permission granted to requester client `openbank-agent`
  (the `authorizationSettings` shape `openbank-mcp-service` carries for the
  ADR-0224 OBO exchange, self-referential here). Template import verified against
  `quay.io/keycloak/keycloak:26.8.0` with zero ERROR lines.
- `openbank-infra/gitops/components/agent/`: ServiceAccount `openbank-agent` and a
  projected `serviceAccountToken` volume (audience `keycloak`, ~1h TTL) mounted at
  `/var/run/secrets/tokens/sa-token` in the agent-service Deployment. The
  `oidc-client` secret path is untouched as the fallback; nothing consumes the
  token file yet — this is the pilot wiring.

What remains owner-gated (needs cluster/credentials only the owner holds):

1. **Render + write the realm import** per runbook 0009
   (`docs/runbooks/0009-keycloak-realm-import-reconcile.md`): substitute all
   `__PLACEHOLDER__` tokens (including the two new `KUBERNETES_OIDC_*` ones),
   verify with `render-verify-keycloak-realm-import.sh openbank` (the boot test
   alone is not sufficient per the runbook — mint a token and inspect the JWT),
   then `vault kv put openbank/keycloak-realm-import`. Because `--import-realm`
   runs on **cold start only**, the live realm additionally needs the IdP and the
   `openbank-agent` token-exchange permission applied to the *running* Keycloak
   (kcadm or admin console) — a template write alone does not touch it.
2. **Verify the exchange end-to-end in the non-prod realm**: project a token from
   an agent-service pod, exchange it at
   `{issuer}/protocol/openid-connect/token` with grant
   `urn:ietf:params:oauth:grant-type:token-exchange` (subject_token =
   SA JWT, subject_issuer = `kubernetes`, requested_token_type =
   `urn:ietf:params:oauth:token-type:access_token`), confirm the returned token
   carries `openbank-agent`'s roles, and confirm a client without the permission
   is denied. Only then wire the service code to prefer the exchanged token.

## Alternatives considered

- **Option A — Keep static client secrets, fix + finish the rotator.** Lowest
  concept change. Rejected as the *destination*: it keeps the fragile shared-secret
  treadmill (the exact class of bug found here — path/property mismatch, non-atomic
  rotate-then-propagate), still leaks over the rotation window, and scales poorly.
  Acceptable only as a short bridge (see Decision).
- **Option B — `private_key_jwt` (RFC 7523) client authentication.** Removes the
  *shared* secret (asymmetric: service holds private key, IdP holds public). A real
  improvement and a good bridge, but still per-service key material to distribute,
  store, and rotate — it relocates the key-management problem rather than deleting
  it.
- **Option C — Workload identity federation: k8s SA token → Keycloak token
  exchange (CHOSEN).** No static or per-service secret at all; credential is a
  short-lived, pod-bound, kubelet-rotated SA token the platform already issues.
  Revocation is immediate (delete/rotate the SA). Uses only components already in
  the stack. Cost: Keycloak issuer-trust + token-exchange configuration, and token
  exchange is a comparatively young Keycloak feature that needs operational
  validation.
- **Option D — SPIFFE/SPIRE + mTLS service mesh.** The purest zero-trust end
  state. Rejected *for now* only because there is no service mesh today
  (NetworkPolicies + OPA sidecars, no Istio/Linkerd); adopting SPIRE + a mesh is a
  much larger programme. Retained as the north star (Decision).

## Consequences

**Positive**
- Eliminates an entire bug class: no shared secret to leak, no rotation job, no
  path/property mismatch, no rotate-then-propagate race with deploys.
- Blast radius ≈ 0: a captured token lives ~1h and is bound to its pod; useless
  elsewhere. Revocation is immediate (SA delete/rotate) instead of "wait for the
  next weekly run".
- Less operational surface: drop the `secret-rotator` CronJob, its OpenBao KV
  paths, and the `<svc>-oidc` ExternalSecrets.
- Aligns with zero-trust / workload-identity best practice — a credible
  "top of market" posture for a regulated platform.

**Negative**
- Migration is real work: Keycloak issuer trust + token-exchange config, and a
  per-service cutover (kept safe by the flagged, phased rollout).
- Introduces a dependency on Keycloak token exchange (a younger feature) — needs
  load/failure validation before fleet rollout.
- The Kubernetes OIDC issuer becomes a trust anchor for M2M auth; its JWKS
  availability and key rotation must be operationalised.

**Neutral**
- Authorization (OPA, ADR-0034) is unchanged; role mapping moves from the Keycloak
  client to the SA→role mapping.
- JWT-signing-key rotation (rotate.sh step 4) is orthogonal and decided separately.

## Compliance impact

- PCI DSS: Req 8 (identify/authenticate access) and Req 3 (keys) — short-lived,
  non-shared, workload-bound credentials materially strengthen both vs. rotated
  static secrets.
- DORA:    ICT risk management — reduced credential blast radius and immediate
  revocation improve operational resilience.
- GDPR:    not applicable (no personal data in the M2M credential path).
- PSD2:    SCA not applicable to M2M; strong service authentication supports the
  overall control environment.
- CNB:     operational-resilience expectations — fewer standing secrets, faster
  revocation.

## References

- ADR-0099 — OpenBao secret tiers; **this ADR amends its Tier-2 OIDC-secret-
  rotation objective** (withdrawn in favour of workload identity).
- ADR-0034 — OPA authorization (`AuthorizeInterceptor`, `service-account-<clientId>`
  M2M classification) — unchanged by this decision.
- RFC 8693 — OAuth 2.0 Token Exchange.
- RFC 7523 — JWT profile for OAuth 2.0 client authentication (the `private_key_jwt`
  bridge).
- SPIFFE/SPIRE — workload identity (the north star).
- Finding (2026-07-18): `secret-rotator` reads/writes `openbank/keycloak/<svc>`
  (`client_secret`) while services read `openbank/<svc>` (`OIDC_CLIENT_SECRET`) —
  the path/property mismatch that motivated this ADR.
