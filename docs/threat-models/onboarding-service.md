<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — onboarding-service

- **Date:** 2026-06-30
- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). AML/compliance-sensitive read-model with four-eyes orchestration.
- **Service ADR:** ADR-0068 (onboarding operations cockpit).

## 1. Scope & purpose

`onboarding-service` is a **read-model and four-eyes orchestration layer** for the customer onboarding
funnel. It projects events from party-service, kyc-service, and sca-service into a unified
`OnboardingRecord` read view and surfaces a funnel stage (Registered → Documents Required →
Screening Running → Manual Review → Approved/Awaiting Passkey → Onboarded).

**Critical point:** onboarding-service never owns system-of-record state. It holds only a projection.
All mutating authority stays with the owning services (party, kyc, sca). The most sensitive action
in the platform — overriding a failed sanctions/PEP check — is enforced four-eyes inside kyc-service,
not in this service.

**Port:** 8130 (HTTP), 8085 (management). Kafka consumer on `openbank.party.events` and
`openbank.kyc.events`.

## 2. Data flow (DFD)

```
[Kafka openbank.party.events] ─────────────────────────────┐
[Kafka openbank.kyc.events]   ─────────────────────────────┤
[Kafka openbank.sca.events]   ─────────────────────────────┤
                                                            ▼
                                              [OnboardingEventConsumer]
                                                            │
                                              [OnboardingProjectionService]
                                                            │
                                              [(Postgres: onboarding_applicant_view)]
                                                            │
[Operator/Admin UI] ──OIDC─▶ (REST /api/v1/onboarding*) ──┘
                                  ROLE_VIEWER / ROLE_OPERATOR / ROLE_COMPLIANCE
[party-service] ──M2M OIDC──▶ [PartyServiceClient (read-only)]
[Prometheus/Observability] ──▶ :8085/q/metrics (cluster-internal)
```

- **External entities:** operators/admins/compliance staff (human, OIDC via Keycloak); downstream
  event producers (party-service, kyc-service, sca-service).
- **Trust boundaries:** UI↔service (OIDC bearer + OPA authz, ADR-0034); service↔Postgres (CNPG,
  cluster-internal); service↔Kafka (Strimzi mTLS consumer, ADR-0137); service↔party-service
  (OIDC M2M read).
- **Assets:** PII-containing projection (legal name, email masked; full name/email only in the
  detail endpoint); funnel stage; KYC sub-check outcomes; device enrollment status.

## 3. Authn/Authz

- All read endpoints: `@RolesAllowed(ROLE_VIEWER)` minimum. Detail + filtering by PII fields:
  `ROLE_OPERATOR`.
- OPA sidecar (ADR-0034) enforces policy at the perimeter — deny-by-default.
- Kafka consumer uses Strimzi mTLS KafkaUser credentials (ADR-0137 T2c); topics are consumed
  read-only from the perspective of this service.
- No mutation endpoints on onboarding-service itself — it emits no outbox, no Kafka producers.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Caller impersonates compliance officer to view flagged applicants | OIDC bearer validated by Keycloak; `ROLE_COMPLIANCE` required for sensitive filter paths; OPA deny-by-default (ADR-0034) |
| **S**poofing | Kafka producer injects false party/kyc events to advance a rejected applicant | Kafka mTLS KafkaUser ACLs (ADR-0137); consumer is read-only; projection re-computes stage from raw event fields — a forged event still requires valid OIDC credentials on the producing service |
| **T**ampering | Direct DB write to flip `party_status` in the projection | No ingress to Postgres outside cluster; CNPG row-level not needed (projection is append-only via event consumer); DB user has read+insert only |
| **R**epudiation | Operator denies viewing a flagged sanctions case | **OPEN — not mitigated.** This row previously claimed read access was audited via an `@Audited` annotation on the detail endpoint. That annotation had no interceptor, was applied nowhere, and has been removed (#4011); this service emits no audit event at all. The `AuditEvent` envelope (`actorId`, `resourceId`, `traceId`) exists and is what a fix would publish, explicitly, from the detail use case. Consistent with ADR-0176, which declines to claim GDPR Art. 5(2) coverage for operator reads until real wiring lands. |
| **I**nfo disclosure | Funnel list exposes PII (names, emails) to VIEWER-only callers | List endpoint returns masked email/name; full PII only on detail with `ROLE_OPERATOR`; no PII in Prometheus metrics (ADR-0077 low-cardinality contract) |
| **I**nfo disclosure | Existence oracle via 404 vs 403 on applicant detail | Returns 404 (not 403) for unknown `partyId` regardless of caller role — no existence oracle |
| **I**nfo disclosure | Kafka event replay surfaces historic PII to compromised consumer | Strimzi ACLs restrict consumer group to `onboarding-consumer-group`; topic retention is bounded (GDPR); PARTY_ERASED events trigger projection erasure |
| **D**oS | Mass concurrent funnel queries scan full projection table | Pagination enforced (max 100 per page); `stage` index on `onboarding_applicant_view`; Prometheus rate-limit alerts |
| **E**oP | VIEWER-role caller escalates to read COMPLIANCE-gated paths (e.g. override audit log) | Separate role guards per endpoint; OPA enforce mode from day one (greenfield, no `@PermitAll` legacy) |

## 5. AML / sanctions override — special controls

The **most sensitive action** covered by this cockpit is overriding a failed sanctions or PEP
check. This action is enforced inside **kyc-service** (the system of record), not here. Controls:

- `kyc.check.override` requires `ROLE_COMPLIANCE` proposer + `ROLE_SUPERVISOR` confirmer
  (four-eyes, `openbank-libs` ApprovalEntry pattern, ADR-0068 §4).
- A mandatory free-text `reason` is required and stored in the immutable `AuditEvent` chain.
- `confirmedBy ≠ proposedBy` is a hard invariant enforced in `ApprovalEntry`.
- onboarding-service surfaces the "pending override approval" queue but cannot approve it —
  the confirmation request travels directly to kyc-service.

## 6. GDPR

- `PARTY_ERASED` event triggers `OnboardingProjectionService.erase()`: the projection row is
  anonymised in-place (name → `[ERASED]`, email → null, PEP/sanctions flags cleared).
- The erased artefact retains `party_id` (for audit consistency) but no linkable PII.
- AuditEvent for the erasure is emitted with `actorType = GDPR_PROCESSOR`.

## 7. Residual risks

| Risk | Severity | Note |
|---|---|---|
| Projection lag | Low | Event consumer delay means the funnel count lags real state by seconds. Acceptable for an operational dashboard; never used for a real-time compliance decision. |
| Kafka event ordering | Low | Party + KYC events arrive on separate topics; brief ordering inversion is possible. `stage` function is idempotent and converges; `updated_at` guards staleness. |
| No operator step-up auth | Medium | ADR-0068 §5 calls for operator step-up before irreversible actions (GDPR erasure). Not yet implemented — currently mitigated by four-eyes (confirmer ≠ proposer). Step-up is a planned follow-up. |
