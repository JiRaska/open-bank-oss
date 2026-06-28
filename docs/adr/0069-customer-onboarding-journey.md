# 69. Customer onboarding journey — operator-assisted Phase 1, self-service Phase 2

Date: 2026-06-07
Status: Accepted
Delivery-Status: Partial
Author(s): OpenBank platform

## Context

ADR-0065 established the customer-facing edge and the `openbank-customers` Keycloak realm.
ADR-0066 described the passkey-first authentication flow and sketched the onboarding
sequence. Neither ADR explicitly answers: **who creates the party record and the Keycloak
user, and how?**

This left three unresolved questions that blocked the mobile app from reaching the sandbox:

1. **Party creation gate**: `POST /api/v1/parties` in party-service requires `ROLE_OPERATOR`.
   A customer who has not yet authenticated cannot call this directly.
2. **Keycloak user lifecycle**: the `openbank-customers` realm has `registrationAllowed: false`
   (to prevent self-signup without KYC). The Keycloak user must be created by someone who
   knows the partyId, so that `user.id == partyId` and the `party_id` JWT claim maps correctly.
3. **KYC gate before account opening**: AML/PSD2 requires a party to be `ACTIVE` (KYC approved)
   before receiving an IBAN. Nothing enforced this in the edge or services.

ADR-0066's onboarding sequence shows KYC happening *before* Keycloak registration, which
implies an unauthenticated "pre-auth" leg where the customer proves identity. The edge is the
only public entry point, so the pre-auth leg must live there.

## Decision

**We will implement onboarding in two phases, both using the customer-facing edge as the
single entry point.**

### Phase 1 — Operator-assisted (implemented in this PR)

The onboarding flow combines a self-service "party creation" step on the edge with an
**operator activation step** in the admin-UI / seed script before the customer can log in.

```
Mobile App                 customer-edge                 party-service   Keycloak
    │                           │                              │              │
    ├─POST /onboarding/start──►│                              │              │
    │  {partyType, legalName}   ├─M2M POST /parties ─────────►│              │
    │                           │◄── 201 {id: partyId} ───────┤              │
    │◄── 201 {partyId,          │                              │              │
    │    status:PENDING_ACT}    │                              │              │
    │                           │                              │              │
    ·  (KYC by operator in admin-UI cockpit → party ACTIVE)   │              │
    ·                           │                              │              │
    ·  (Operator creates KC user: id=partyId, party_id=partyId, via seed script or admin panel)
    ·                           │                              │              │
    ├─KC passkey registration──────────────────────────────────────────────►│
    │◄─── session (JWT with party_id = partyId) ────────────────────────────┤
    │                           │                              │              │
    ├─POST /onboarding/account─►│                              │              │
    │  {productId, accountType}  ├─GET /parties/{partyId}──────►│              │
    │                           │◄── party {status:ACTIVE} ────┤              │
    │                           ├─M2M POST /accounts ──────────►account-svc   │
    │◄── 201 {accountId, IBAN}  │◄── 201 {accountId} ──────────┤              │
```

**Party → Keycloak user invariant (B1 fix):**
When the operator creates a Keycloak user in `openbank-customers` realm, they MUST:
1. Set the Keycloak user `id` = partyId UUID (allows KC admin API `POST /users` with explicit `id`).
2. Set the user attribute `party_id` = partyId UUID.
3. The `party_id` user attribute mapper (added to `customers-realm-template.json`) includes
   `party_id` as a JWT claim in every access token issued to that user.

The `CustomerEdgeResource.customer()` method reads `party_id` claim first, falls back to
`sub` (pre-mapper tokens). Both `sub` and `party_id` equal partyId if the invariant is kept.

The `scripts/seed-test-customer.sh` enforces this invariant for sandbox test customers.

### Phase 2 — Self-service (follow-up, not in this PR)

The edge will acquire a Keycloak Admin API client (`openbank-edge-admin`) in the
`openbank-customers` realm with `manage-users` scope. The `POST /onboarding/start` route
will be extended to create the Keycloak user automatically after party creation, returning
a registration URL for passkey enrollment. The operator KYC step remains (KYC cannot be
automated for AML compliance without a regulated third-party provider).

Phase 2 is tracked as a follow-up issue and does NOT change the onboarding URL shape or
the KYC gate — only the operator activation step is automated.

### KYC gate (enforced in both phases)

`POST /onboarding/account` MUST:
1. Verify party status is `ACTIVE` via `GET /api/v1/parties/{partyId}` (M2M token).
2. Return `422 Unprocessable Entity` if status is `PENDING_KYC` or `REJECTED`.
3. Only forward to account-service if status is `ACTIVE`.

This gate is the AML/PSD2 compliance enforcement point in the edge. Party-service's own
`POST /accounts` also validates partyId ownership, but the edge gate prevents unnecessary
downstream traffic and provides a clear customer-facing error.

## Alternatives considered

- **Operator creates party in admin-UI, no edge route**: simplest, but makes the mobile
  app onboarding flow 100% offline from the app's perspective. Customers cannot self-initiate.
  Rejected: poor UX for any real user; also makes automated testing harder.
- **Edge creates Keycloak user immediately in POST /onboarding/start**: cleanest end-to-end,
  but requires the KC Admin API client with `manage-users` scope — non-trivial secret
  management. Deferred to Phase 2.
- **Party creation requires authentication (token from pre-auth flow)**: would need a
  temporary "onboarding" Keycloak client with no party binding. Adds complexity without
  real security benefit (party creation is idempotent by design). Rejected.
- **Use `registrationAllowed: true` in KC realm**: allows self-signup via Keycloak's own
  registration form. Rejected: no KYC integration in the KC registration flow; a user
  could register and then try to open an account without KYC. The edge-side
  `/onboarding/start` + KYC gate is the correct pattern.

## Consequences

**Positive**
- Mobile app can initiate onboarding without any pre-existing credentials.
- KYC gate enforced at the edge prevents non-compliant account opening.
- `party_id` JWT claim fix (B1) makes the principal binding robust against Keycloak
  default `sub` = internal UUID rather than partyId.
- Clean two-phase roadmap: Phase 1 lands the architecture, Phase 2 automates the
  operator step without changing the API contract.

**Negative**
- Phase 1 requires an operator to manually create the Keycloak user after party creation.
  Until Phase 2 lands, there is a manual activation step.
- OPA enforcement for ownership checks on `/onboarding/account` is inline (ad-hoc) not
  policy-as-code. This is documented as tech debt (ADR-0065 §3, ADR-0034 fleet sweep).

**Neutral**
- The edge's M2M token already has `ROLE_OPERATOR` via the existing `openbank-edge`
  service account in the `openbank` realm. No new IAM surface is added in Phase 1.
- The `readEntity()` JSON parsing in the onboarding routes uses substring extraction
  (no JSON library dep) consistent with existing UpstreamClient implementation.

## Compliance impact

- **PSD2**: KYC gate at `POST /onboarding/account` enforces that account opening follows
  successful identity verification. Party `ACTIVE` status = KYC approved.
- **AML6D**: party-service records the KYC outcome; the edge gate prevents an account
  from being opened for a non-verified party.
- **GDPR**: legalName and email collected in `/onboarding/start` are stored in
  party-service (the authoritative PII store). The edge does not persist PII.
- **DORA**: onboarding is routed through the single edge choke point — rate-limiting,
  abuse defense, and observability already attached to the edge apply here too.

## References

- ADR-0065 — Customer-facing edge + `openbank-customers` Keycloak realm.
- ADR-0066 — Passwordless passkey-first customer authentication.
- ADR-0068 — Onboarding operations cockpit (operator KYC review).
- ADR-0021 — SCA device enrollment (follows account opening).
- ADR-0034 — OPA unified authz (Phase 2 will add OPA ownership policies to edge routes).
