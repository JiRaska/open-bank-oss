<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — swift-service

- **Date:** 2026-05-30
- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). **Money-path** bounded context (high-value wires).
- **Service ADR:** see `docs/adr/`; platform controls per ADR-0029/0030/0034.

## 1. Scope & purpose

SWIFT message handling: create, query, query-by-status, acknowledge, reject. Cross-border
high-value wire instructions — historically the highest-impact fraud target (cf. SWIFT-network
heists). Message authenticity is paramount.

## 2. Data flow (DFD)

```
[Operator/Payments] --> (REST /api/v1/swift) --> [swift-service] --> [(Postgres: swift messages)]
[Counterparty/gateway] --> (ack / reject) ----------^                    |
                                                                         +--> [(swift_outbox)] --> [Kafka swift events]
                                                                         |
                                                                         +--> [clearing-simulator] (pacs.008 out / pacs.002 in; OIDC CC; ADR-0104 D4; flag-gated; MT103→ISO20022)
```

- **External entities:** operators, SWIFT gateway/counterparty (ack/reject inbound),
  clearing-simulator (SWIFT SWIFTNet proxy; swap-point for real SWIFTNet connector).
- **Trust boundaries:** SWIFT network edge (highest scrutiny); caller↔service; service↔Postgres/Kafka;
  service↔clearing-simulator (OIDC client-credentials; cluster-internal; pilot flag off by default).
- **Assets:** SWIFT MT/MX messages, BIC routing, amounts, ack/reject state.

## 3. Authn/Authz

- Send/ack/reject must be role-gated (operator/payments) + OPA enforce; inbound ack/reject must be
  authenticated to the gateway identity (mTLS allow-list).
- `send`/`ack`/`reject`/`get`/`listAll`/`listByStatus` all carry `@RolesAllowed` (2026-07-08 fix,
  see §6) in addition to the OPA `@Authorize` check. No `ROLE_SERVICE` is granted on any endpoint:
  a repo-wide sweep (issue #266, `security/swift-opa-enforce`) found no in-repo REST client or
  Kafka consumer calling `openbank-swift-service` — every endpoint is human-only today. Adding
  `ROLE_SERVICE` without a caller or a matching OPA rule would be an untested, misleading grant;
  revisit if/when a real M2M caller (e.g. a SWIFT gateway adapter) is introduced, gating the
  corresponding OPA rule on `principal.id`, not `principal.type == "SERVICE"`
  (`AuthorizeInterceptor.principalType()` never emits `SERVICE`).
- Four-eyes approval-decide endpoint: `@RolesAllowed("ROLE_OPERATOR","ROLE_ADMIN","ROLE_PAYMENTS")`
  (the standard checker role set), plus a domain-level segregation-of-duties check (checker id !=
  maker id) — see §4a.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Forged outbound wire / spoofed inbound ack | mTLS gateway identity; message authentication; operator role |
| **S**poofing | Forged `pacs.002` ACSC from clearing-simulator (ADR-0104 D4) | clearing-simulator is cluster-internal only; OIDC CC verifies identity; `Pacs002Reader` validates XML schema before parsing; scheme accept moves message to SENT (no funds move until correspondent settlement) |
| **T**ampering | Alter amount/BIC of a message | Message integrity (signing/HMAC); immutable once submitted; audit |
| **R**epudiation | Deny sending/acking a message | AuditEvent per create/ack/reject with actor + message id |
| **I**nfo disclosure | Message content / counterparty leakage | AuthZ scoping; encryption at rest; least-privilege read |
| **D**oS | Message flooding | Rate limit; bounded queue |
| **E**oP | Unauthorized send or false ack/reject | Distinct roles; four-eyes (MakerChecker) for high-value send recommended |

## 4a. Four-eyes approval (ADR-0155) — STRIDE supplement

`POST /api/v1/swift` (`swift.send`) is a money-path action; when OPA (`rest.rego`) flags it
`four_eyes_required`, `AuthorizeInterceptor` pauses the maker's call with HTTP 202 and a
`PendingApproval` id instead of invoking `SwiftResource.send()`. New endpoint `PATCH
/api/v1/swift/approvals/{id}` lets a DIFFERENT operator decide the resulting `PendingApproval`;
the maker retries `POST /api/v1/swift` with an `X-Approval-Id` header. **`authz.four-eyes.enforce`
stays `false` in this PR** — the `ApprovalStore`/endpoint are wired, but blocking is a deliberate
follow-up flip, not bundled here (see ADR-0155), matching the sepa-payment pilot (issue #413).

`swift.send` has no `@PathParam` — unlike the resource-scoped services in this rollout (e.g.
sepa-payment's `sepaPayment.transitionStatus` on `#id`), there is no message id to gate on until
after the message is created. `AuthorizeInterceptor` therefore calls
`store.create(action, resourceId = null, maker)`, so the resulting `PendingApproval.resourceId` is
always `null` — the approval binds on **action + maker only**, not action + resource + maker (see
residual risk below).

| STRIDE | Threat | Mitigation |
|---|---|---|
| **S**poofing | A caller other than an operator decides an approval | `@RolesAllowed("ROLE_OPERATOR","ROLE_ADMIN","ROLE_PAYMENTS")` + OPA `@Authorize(action="swift.approval.decide")` on the decide endpoint |
| **E**oP | The maker approves their own request (self-approval defeats maker-checker) | `ApprovalStore.decide` throws `SelfApprovalNotAllowedException` (mapped to 403) when `decidedBy == makerId` — enforced in the domain port itself, not just the REST layer, and `makerId`/`decidedBy` both resolve via the same `.principal.name` extraction (interceptor vs. `SecurityIdentity`) so the comparison can't silently mismatch for the same real person |
| **T**ampering | A stale, already-consumed, or unrelated `X-Approval-Id` is replayed to unlock a different send | `AuthorizeInterceptor` requires the approval's `action` + `makerId` to match the CURRENT request (there is no `resourceId` to also match here — see residual risk), `status == APPROVED`, and marks it `EXECUTED` (one-time use) on success; any mismatch re-issues a fresh pending approval instead of proceeding |
| **R**epudiation | No record of who approved a gated send | `PendingApproval.decidedBy` + `decidedAt` recorded in the approval record itself (Redis, TTL-bounded — see ADR-0155 Negative consequences: not yet a permanent audit trail) |
| **I**nfo disclosure | Approval id enumeration reveals action metadata to an unauthorized caller | `find`/`decide` require the caller to already hold a valid, role-gated session; the id itself is a random UUID (`RedisApprovalStore`, not sequential) |
| **I**nfo disclosure | (issue #5679) `GET /api/v1/swift/approvals` lists every pending four-eyes request with its `makerId` and age | Role-gated `ROLE_OPERATOR`/`ROLE_ADMIN`/`ROLE_PAYMENTS` + `@Authorize(action = "swift.approval.read")`; the payload carries approval metadata only — the action name, the resource id (always `null` here, see §4a) and who asked — never message/BIC/amount details. Limit clamped to 200 — an unbounded query parameter over a Redis scan is a trivially reachable amplification. Deliberately NOT filtered to exclude the caller's own requests: hiding a maker's request from them would not stop them attempting it (the guard is in `RedisApprovalStore.decide`, server-side) and would only make the queue lie about its own depth |
| **D**oS | Flooding `POST /api/v1/swift` to exhaust Redis with pending approvals | Bounded by the same rate-limit/idempotency controls as the gated endpoint itself; each `PendingApproval` is TTL-bounded (86400s) so abandoned records expire |

**DFD update:** adds `Operator (checker) → GET /api/v1/swift/approvals → Redis (approval:*)` and
`Operator (checker) → PATCH /api/v1/swift/approvals/{id} → Redis (approval:*)` alongside the
existing `POST /api/v1/swift` edge; the maker's retry reuses the existing DFD edge.
**Risk class:** integrity (segregation of duties) + confidentiality (approval record scope).
**Rollback:** `authz.four-eyes.enforce=false` (default) — the endpoint and store exist but do
not change any existing request's outcome until explicitly flipped.

## 5. Residual risks / assumptions

- **Four-eyes for high-value sends** strongly recommended (ADR-0034 MakerChecker).
- Message-level authenticity (signing) is the dominant control against wire fraud.
- Sanctions screening expected upstream before release.
- **`swift.send`'s `PendingApproval` has no `resourceId` binding** (action + maker only, per §4a) —
  unlike the resource-scoped services in the ADR-0155 rollout (e.g. sepa-payment), a checker cannot
  distinguish between two concurrently-pending sends from the same maker by looking at the approval
  alone; the approval id itself (opaque, out-of-band, e.g. via the request/notification the checker
  reviews) is the only disambiguator. Acceptable for the pilot rollout but worth revisiting if a
  maker can have multiple simultaneous pending sends in practice.
- **Four-eyes `PendingApproval` records are TTL-bounded (Redis), not a permanent audit
  trail** (ADR-0155) — a durable-audit requirement for "who approved what, forever" would
  need an additional store; not implemented in this PR.
- **`SwiftResource` now has `@RolesAllowed` on every method** (`send`, `ack`, `reject`, reads) —
  closed by PR #568/#571 (2026-07-08, see §6), landed separately from and just ahead of this
  ADR-0155 wiring. The new `ApprovalResource.decide` endpoint added here is role-gated the same
  way (`@RolesAllowed("ROLE_OPERATOR","ROLE_ADMIN","ROLE_PAYMENTS")`, see §3/§4a).

## 6. Change log

- **2026-08-24** — Synthetic-journey taint now propagates over this service's existing internal clearing and transaction REST clients through `SyntheticTaintClientFilter` (ADR-0252, #4348). This adds no caller, endpoint, network-policy edge, privilege or payment-control bypass. It preserves the marker before a downstream persistence/event boundary; a fleet gate requires every new client to choose propagation or a reasoned external boundary.

- **2026-05-30** — Added `swift_outbox_seq` (Hibernate fix). Additive DDL only — no new flow/surface/
  boundary. Risk class = **availability**, mitigated by `HibernateSequenceGuardTest`.
  Rollback: `DROP SEQUENCE`.
- **2026-06-23** — ADR-0104 D4: real ISO 20022 `pacs.008` submission to SWIFT SWIFTNet via
  `clearing-simulator`. New outbound trust boundary: `swift-service → clearing-simulator`
  (POST `/api/v1/clearing/credit-transfers`, pacs.008 XML; pacs.002 XML response; OIDC CC).
  MT103 fields (sender/receiver BIC, value date, amount, beneficiary account, charge code) are
  converted to a `CreditTransferInstruction` and the resulting pacs.008 XML is persisted as
  `rawMt` on the `SwiftMessage` for audit tracing. **Flag-gated**
  (`openbank.swift.scheme-submission.enabled`, off by default; only MT103 messages eligible). Fails
  **closed**: gateway unreachable → message stays VALIDATED. `ACSC` → SENT (rawMt populated),
  `RJCT` → REJECTED. **New STRIDE row**: forged `pacs.002` ACSC → mitigated by cluster-internal
  isolation, OIDC CC, schema validation. **Risk class = integrity** (scheme verdict gates
  high-value wire state) and **confidentiality** (BIC, amount, account sent to simulator; mitigated
  by OIDC CC + cluster-only ingress). **DFD update**: added `clearing-simulator` edge (see §2).
  Added `quarkus-oidc-client-reactive-filter` + `quarkus-rest-client-reactive` deps to
  `build.gradle.kts`. No DB schema change; rollback = flag OFF.
- **2026-07-08** — Fixed **E**oP gap found during the issue #413 four-eyes audit: `POST
  /api/v1/swift` (`send`, action `swift.send`) had no `@RolesAllowed` at all — this section
  already documented "role-gated (operator/payments)" as a control, but it was never wired on
  the endpoint, so any caller clearing the OPA `@Authorize` check could initiate an outbound
  wire regardless of role. Added `@RolesAllowed(Roles.OPERATOR, Roles.PAYMENTS, Roles.ADMIN)` to
  `send`, matching the `create` convention on sibling money-path payment services
  (domestic-payment, sepa-payment). No DFD/schema change; rollback = revert the annotation.
  same resource are also missing `@RolesAllowed` and rely on OPA alone (PR #568).
- **2026-07-08** — Follow-up to the `send` **E**oP fix (PR #568, same day): `ack`, `reject`, `get`,
  `listAll`, `listByStatus` on `SwiftResource` also had no `@RolesAllowed` and relied on OPA
  `@Authorize` alone. Added `@RolesAllowed(Roles.OPERATOR, Roles.PAYMENTS, Roles.ADMIN)` to the
  mutating `ack`/`reject` endpoints (matching `send`'s role set and the OPA `operator-swift-write`
  rule's operator/admin gate) and `@RolesAllowed(Roles.VIEWER, Roles.OPERATOR, Roles.PAYMENTS,
  Roles.ADMIN)` to the read/list endpoints (`get`, `listAll`, `listByStatus`), matching the
  `getPayment`/`listPayments` convention on `DomesticPaymentResource`/`SepaPaymentResource` minus
  `ROLE_SERVICE` — investigated and deliberately omitted (see §3): no in-repo caller of any
  SWIFT endpoint exists yet, human-only per the OPA policy landed the day before
  (`security/swift-opa-enforce`, issue #266). No DFD/schema change; rollback = revert the
  annotations. Also updated `SwiftResourceAuthzTest`'s anonymous-access assertion (404 -> 401)
  to match the new `@RolesAllowed` outer gate.
- **2026-07-08** — ADR-0155 four-eyes enforcement rollout (issue #413), mirroring the
  sepa-payment pilot. New endpoint `PATCH /api/v1/swift/approvals/{id}` + `ApprovalConfig`
  (Redis-backed `ApprovalStore`) + `AuthorizeInterceptor` four-eyes gate (openbank-libs-runtime,
  shared, opt-in) on `swift.send`. New STRIDE supplement §4a; new residual risk noting
  `swift.send`'s `PendingApproval` has no `resourceId` binding (action-scoped only, no path
  param on `send`). The `@RolesAllowed` gap on `ack`/`reject`/`get`/`listAll`/`listByStatus` noted
  as open in earlier drafts of this entry was closed by the two entries above (PR #568/#571,
  landed just ahead of this one) before merge. `authz.four-eyes.enforce` defaults `false` — no
  behavior change to any existing request in this PR; flipping it is a tracked follow-up. No DB
  schema change (Redis, TTL-bounded); rollback = revert the commit (or leave
  `authz.four-eyes.enforce=false`, its default).
- **2026-08-19** — `ApprovalResource` served only `PATCH /{id}` (decide), so a `swift.send`
  four-eyes decision parked at 202 was discoverable only by whoever had been handed its approval
  id out of band — the ceremony completed only if the two operators were already talking, and the
  24h Redis TTL then expired the request silently otherwise (issue #5679, mirroring sanctions
  #3472, ledger, domestic-payment and sepa-instant). Added `GET /api/v1/swift/approvals` (§4a new
  I row); additive-only OpenAPI change (1.2.0 -> 1.3.0, ADR-0048).
  - **Checked the existing decide endpoint's own authz posture while here** (verify-by-effect, not
    by appearance): `opa eval` against the real `rest.rego` + `swift_rest_ext.rego` +
    `rules-opa-data.yaml` bundle showed `swift.approval.decide` resolving **`allow=false` for a
    real ROLE_OPERATOR** — the action was entirely absent from `rules.yaml`'s
    `role_action_matrix` (present for the sibling `sepaPayment.approval.decide`, absent for this
    service's own decide action since the four-eyes gate was wired on 2026-07-08) and
    `swift_rest_ext.rego`'s own `operator-swift-write` rule only ever covers `send`/`acknowledge`/
    `reject`, never `approval.decide`. The decide endpoint has therefore been 403ing every
    operator in any `AUTHZ_ENFORCE=true` environment since it shipped — same shape as the
    balance-service gap found by #5686/#5690 and the sepa-instant gap found by #5694. **Fixed** by
    adding the matrix grant (mirroring `sepaPayment.approval.decide`'s entry) to both
    `role_action_matrix.ROLE_OPERATOR` and `shared_m2m_matrix_write_grants.declared`, then
    regenerating `rules-opa-data.yaml` and every service's OPA bundle (a `role_action_matrix` edit
    restamps the fleet). Verified with `opa eval` (loaded so `data.rules.*` actually resolves —
    the bundle's own `rules/data.yaml` layout, not a bare directory named `rules`, which silently
    drops the prefix and would have made the "before" reading a false positive too):
    `swift.approval.decide` now resolves `allow=true`/`reason=matrix-allows` for ROLE_OPERATOR,
    and a non-operator role (`ROLE_KYC_OPENER`) still resolves `allow=false`. The new `GET`
    (`swift.approval.read`) needed no matrix entry — `operator-read-any` in base `rest.rego`
    already grants any `ROLE_OPERATOR`/`ROLE_ADMIN` action ending in `.read`, confirmed the same
    way.
  - **Known residual, not fixed here**: `matrix-allows` is role-only, and the deployed realm
    template gives `service-account-openbank-edge` `ROLE_OPERATOR` in at least one environment
    (see root `CLAUDE.md`'s realm-drift note) — the same exposure balance-service closed with a
    per-service `prohibited` rule in `balance_rest_ext.rego`. `swift_rest_ext.rego`'s own header
    already documents that a repo-wide sweep found no in-repo REST client or Kafka consumer
    calling `openbank-swift-service` at all today, so there is no verified in-repo M2M caller to
    scope a prohibition against; `rules.yaml`'s `shared_m2m_write_prohibition.deferred` already
    lists `operator-swift-write: "no identity-scoped rule; callers unverified"` for the same
    reason. Building a new prohibition mechanism was out of scope for this PR's mirrored fix;
    tracked as follow-up under issue #5679's own money-path-first ordering.
  - **Rollback:** revert both commits independently — the matrix grant only changes an OPA
    `allow` decision (advisory in this environment, `authz.four-eyes.enforce=false`), and the new
    `GET` is additive.
