---
date: 2026-06-06
decision-status: accepted
delivery-status: partial
authors: [OpenBank platform]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [onboarding, admin-ui, kyc, audit]
summary: "OpenBank builds an onboarding cockpit as a read-model projection plus a four-eyes maker-checker workflow; party, kyc and sca services keep sole authority over their own state transitions."
---

# 68. Onboarding operations cockpit — a read-model and four-eyes workflow over the onboarding state machine

**Delivery note (updated 2026-06-30):**
- **Governance primitives** — ✅ Ready (domain layer): read-model projection + canonical funnel-stage function, `com.openbank.libs.foureyes` maker-checker primitive (wired into kyc approve/reject), and audited/reasoned kyc decisions. ⚠️ Designed-not-wired on the onboarding read API: role-based `PiiMask` (PII still returned plaintext) and OPA `@Authorize` enforce (endpoints are `@RolesAllowed`).
- **Admin-UI cockpit (read)** — ✅ Shipped: `/onboarding` "Onboarding Cockpit" — per-stage funnel KPI tiles, stage-filtered records board, per-applicant drill-down drawer (nav-registered, BFF-wired to onboarding-service).
- **Action surface, step-up & read-API authz** — ⬜ Pending: the four-eyes approval-queue tab, operator action buttons + mandatory-reason modal, drill-down audit timeline, operator step-up SCA, and — on the onboarding read API — OPA `@Authorize` enforce and role-based `PiiMask`.

## Context

Customer onboarding is a **distributed state machine with no orchestrator**. It is
choreographed across three services, each owning one slice of the truth:

- **party-service** — `PartyStatus` (`PENDING_KYC → ACTIVE | SUSPENDED | CLOSED`) and
  `KycStatus` (`NOT_STARTED → IN_PROGRESS → APPROVED | REJECTED | EXPIRED`).
- **kyc-service** — `KycCaseStatus` (`OPEN → DOCUMENTS_REQUIRED → UNDER_REVIEW →
  APPROVED | REJECTED | EXPIRED`) with four mandatory checks (`IDENTITY`, `ADDRESS`,
  `PEP_SCREENING`, `SANCTIONS_SCREENING`, each `PENDING → PASSED | FAILED | MANUAL_REVIEW`).
  The KYC case is the real onboarding checkpoint: its approval flips the party to `ACTIVE`.
- **sca-service** — passkey/SCA device enrollment, which happens *after* KYC approval
  (ADR-0066), and is what makes a party fully operational.

> **Correction, 2026-09-05 (issue #8535).** The `DOCUMENTS_REQUIRED` stage described above, and the
> `KYC_DOCUMENTS_REQUIRED` funnel column in §4.2, were never implemented: no kyc-service operation
> ever set that status, so the cockpit column could only ever read zero — which reads as "nobody is
> stuck on documents" rather than "this is not built". The column has been removed from the cockpit.
> The status itself is still declared in the enums and contracts: removing it there is blocked by
> two mutually exclusive CI gates (#8618). The decision recorded below is preserved as written;
> collecting documents from a customer needs an operator endpoint, an upload path and storage, and
> would be a new ADR.

There is no operational view over this. An operator cannot answer "how many applicants
are stuck waiting on documents", "who failed sanctions screening", or "which approved
customers never finished passkey enrollment". Concretely:

- **Counts and stage filtering are not cheap.** The party and kyc list endpoints have no
  `?status=` filter and no group-by-state aggregate. Any funnel view would today require
  fetching the whole dataset and folding it client-side.
- **The decision endpoints are not audit-safe.** `POST /kyc/cases/{id}/approve` and
  `/reject` are guarded only by `@RolesAllowed`; they emit **no `AuditEvent`, capture no
  mandatory `reason`, and enforce no four-eyes** — even though approving a case activates a
  bank account, and overriding a failed sanctions check is the single most compliance-
  sensitive action in the platform.
- **sca-service emits no event** when a device is enrolled, so the "approved but no passkey
  yet" tail is invisible to any read-model.
- **There is no operator step-up.** ADR-0021 SCA is customer-side; no mechanism forces an
  operator to re-authenticate before an irreversible action (party erasure).

We want an admin-UI **onboarding cockpit**: a live funnel of stages with per-stage counts,
a drill-down per applicant with the full audit timeline, and the ability for staff to move
an applicant forward, correct a case, or cancel onboarding — with every action being audit-,
security-, and process-correct. The infrastructure primitives all already exist in
`openbank-libs` (`AuditEvent` + `AuditEventPublisher`, `@Authorize`/OPA, `IdempotencyStore`,
the outbox — all used explicitly; the `@Audited` and `@Idempotent` annotations this line once
named were inert and are gone, #4011); they
are simply not wired onto this flow. What is genuinely missing is (a) a unified read surface,
(b) a four-eyes maker-checker primitive, and (c) an operator step-up.

## Decision

**We will build an onboarding operations cockpit as a read-model plus a four-eyes workflow,
without moving any system-of-record state out of party/kyc/sca.** The owning service stays
the sole authority for each state transition; the cockpit observes and orchestrates, it does
not own.

### 1. `openbank-onboarding-service` — a projection, never a system of record

A new released component (`version.txt`, release-please entry, `openapi.yaml`, contract
test, threat model). It consumes the existing `openbank.party.events` and
`openbank.kyc.events` topics (and a new sca event, §3) and materialises a read view:

```
onboarding_applicant_view
  party_id PK, party_type, name_masked, email_masked
  party_status, kyc_status, kyc_case_id, kyc_case_status
  check_identity / check_address / check_pep / check_sanctions   ∈ {PENDING,PASSED,FAILED,MANUAL_REVIEW}
  risk_level, device_count
  stage              -- the canonical funnel stage (§2)
  stage_entered_at   -- drives "stuck > N days"
  last_actor, last_actor_type, last_action, updated_at
```

Read API (all new endpoints ship `@Authorize` in **enforce** mode from day one — greenfield,
no legacy `@PermitAll` to migrate; cf. ADR-0034):

- `GET /api/v1/onboarding/funnel` — counts per stage + "stuck" buckets.
- `GET /api/v1/onboarding/applicants?stage=&stuckOverDays=&risk=&page=` — filtered list.
- `GET /api/v1/onboarding/applicants/{partyId}` — detail + audit timeline + pending approvals.

The service **never writes** party/kyc/sca state. It holds only the projection and the
approval-queue view (§4).

### 2. One canonical funnel stage — a pure, versioned, tested function

The cockpit presents a single `stage` derived from the combined party + kyc + sca state by
one pure function, owned by onboarding-service and unit-tested against every state
combination. The admin-UI may mirror it for display only; onboarding-service is authoritative.

| # | Stage | Derived from |
|---|-------|--------------|
| 1 | Registered | party `PENDING_KYC` + kyc `OPEN` |
| 2 | Documents required | kyc `DOCUMENTS_REQUIRED` |
| 3 | Screening running | PEP/Sanctions checks `PENDING` |
| 4 | Manual review | kyc `UNDER_REVIEW` or any check `MANUAL_REVIEW` |
| 5 | Approved, awaiting passkey | party `ACTIVE` + `device_count = 0` |
| 6 | Onboarded | party `ACTIVE` + `device_count ≥ 1` |
| — | Rejected / Suspended | kyc `REJECTED` / party `SUSPENDED` |
| — | Expired | kyc/party `EXPIRED` |

### 3. Close the three backend gaps

- **sca-service** emits `openbank.sca.events` `DEVICE_ENROLLED` (via the shared outbox) and
  exposes `GET /api/v1/sca/parties/{partyId}/devices`, so stages 5→6 become observable.
- **party / kyc list endpoints** gain `?status=` / `?kycStatus=` filters (the read-model is
  the primary consumer, but the filters are generally useful and contract-tested).
- **kyc approve/reject** gain an `AuditEvent` and a mandatory `reason` (§5/§6) — closing a
  pre-existing audit hole independent of the cockpit.

### 4. Four-eyes as a shared `openbank-libs` primitive, enforced in the owning service

Maker-checker lives in **`com.openbank.libs.foureyes`** and is enforced **inside the service
that owns the state**, not in onboarding-service. This is deliberate: centralising the queue
in onboarding-service would require a cross-service trust token and leave the owning
endpoints bypassable. Co-locating the check with the data removes both problems.

```
com.openbank.libs.foureyes
  ApprovalRequest{ id, operation, resourceType, resourceId,
                   proposedBy, proposedAt, reason, payload,
                   status ∈ {PENDING, CONFIRMED, REJECTED, EXPIRED},
                   confirmedBy, confirmedAt }
  invariant:  confirmedBy ≠ proposedBy                 (hard)
  confirmer role per operation                         (OPA, §7)
  TTL / expiry; emits ApprovalProposed/Confirmed/Rejected
  the real state mutation runs ONLY on confirm → then the owning service's
  normal AuditEvent + outbox fire as today
```

onboarding-service consumes the approval events and renders the "awaiting second approval"
queue. The admin-UI posts maker and checker actions through the BFF to the owning service.

### 5. Operator action classification — segregation of duties

| Action | Min. role | Four-eyes | Step-up | Notes |
|--------|-----------|:--:|:--:|-------|
| request document, resend passkey link | `OPERATOR` | — | — | soft, single-actor |
| approve / reject case | `COMPLIANCE` | ✅ | — | decision |
| **override `SANCTIONS`/`PEP` `FAILED → PASSED`** | `COMPLIANCE` + `SUPERVISOR` | ✅ | — | **most sensitive action in the system** |
| reopen `REJECTED` / `EXPIRED` | `COMPLIANCE` | ✅ | — | |
| cancel / erase party (GDPR Art. 17) | `ADMIN` | ✅ | ✅ | irreversible |

**Hard rule:** an applicant who failed sanctions or PEP screening can **never** be advanced
by a single click. Overriding a failed screening check is a distinct audited operation
(`kyc.check.override`), requires a `COMPLIANCE` proposer **and** a `SUPERVISOR` confirmer
(four-eyes), and a mandatory free-text justification. This is the AML control regulators
inspect first.

### 6. Audit and PII on every action

- Every operator action emits an `AuditEvent` to `audit-events-out` (hash-chained, ADR-0029)
  with `before`/`after`, the mandatory `reason`, and the `traceId`.
- When the actor is an AI agent (Claude), `actorType = AI_AGENT` with `model_id` and a
  `human_approver` (ADR-0031). The cockpit timeline distinguishes **human vs. agent** actors.
- PII in the cockpit is masked by role (`PiiMask`): `COMPLIANCE` sees unmasked, `OPERATOR`
  and `VIEWER` do not. The applicant detail shows that person's audit timeline.

### 7. Authorization

Each new action gets an OPA rule under `data.openbank.rest.allow` keyed by action, role,
the four-eyes attribute, and current state. Because these endpoints are greenfield they ship
in **enforce** mode immediately, rather than going through the advisory→enforce migration the
existing `@PermitAll` fleet requires (ADR-0034).

### 8. Operator step-up via Keycloak re-authentication

For irreversible actions (cancel/erase) the admin-UI forces a fresh Keycloak authentication
(`max_age=0` / an ACR step-up) before the action is accepted; the backend verifies the token
`auth_time`. This is new — no operator step-up exists today — and is independent of the
customer-side SCA in ADR-0021.

### 9. Admin-UI `/onboarding` section

A new section following the established admin-UI patterns (app-router `layout.tsx` shell,
`AuthGuard`, BFF `/api/svc/onboarding-service/...`, `DataUnavailable`, `KpiCard`): KPI tiles
(per-stage counts + stuck), a stage board with filters, an applicant detail drawer (state,
checks, risk, audit timeline, action buttons gated by permission, a mandatory-reason modal),
and an "approval queue" tab. New UI permissions: `onboarding:view`, `onboarding:act.soft`,
`onboarding:decide`, `onboarding:override`.

### Delivery order

ADR (this) + libs `foureyes` → backend gaps (§3) → onboarding-service projection + read API
→ wire four-eyes into kyc/party + OPA enforce → admin-UI section → operator step-up. Each is
its own PR with its own version bump, OpenAPI/contract test where applicable, and a threat
model where it touches the decision path.

## Alternatives considered

- **Projection inside kyc-service, no new service** — fewer moving parts. Rejected: the
  funnel is cross-cutting (party + kyc + sca), and mixing an operational read-model and an
  approval queue into the KYC domain blurs the bounded context and bloats the money-path
  service. A dedicated read-model keeps KYC focused on the decision it owns.
- **BFF-only aggregation in admin-UI, no read-model** — fastest to a first screen. Rejected
  as the *target* (kept only as a possible throwaway spike): per-stage counts and filtering
  do not scale via fan-out, and there would be no single audit/authz surface for actions.
- **Centralise four-eyes in onboarding-service as an orchestrator** that mints a signed grant
  the owning services trust. Rejected: introduces a cross-service trust token and leaves the
  owning endpoints bypassable unless locked to a single caller. Co-locating the four-eyes
  check with the state (via a libs primitive) is simpler and safer.
- **Single-actor sensitive actions with strong audit, no four-eyes** — less to build.
  Rejected: for sanctions override and irreversible cancel, audit-after-the-fact is not a
  substitute for maker-checker prevention in a banking-grade control environment.

## Consequences

**Positive**
- One operational view of onboarding with cheap per-stage counts and "stuck" detection.
- Closes a real audit hole: kyc approve/reject become audited, reasoned, and four-eyed.
- The `foureyes` primitive is reusable for any future sensitive cross-service action.
- System-of-record stays in party/kyc/sca; the cockpit cannot corrupt domain invariants.
- AI-attributed audit makes agent-driven onboarding actions first-class and traceable.

**Negative**
- A new released service (build, deploy, threat model, on-call surface) plus a new libs
  module and a Keycloak step-up flow — non-trivial scope across several PRs.
- The read-model is eventually consistent with party/kyc/sca; the cockpit must render
  "as of last event" and reconcile (a projection rebuild path is required).
- Operator step-up adds a re-authentication round-trip to cancel/erase (acceptable for an
  irreversible action).

**Neutral**
- onboarding-service is KYC-decision-adjacent, so it inherits money-path review rigour
  (2 approvals + threat model) even though it holds no money and no system-of-record state.
- The canonical stage function is a new piece of shared truth that must be kept in sync if
  the underlying party/kyc/sca state machines evolve (mitigated by exhaustive unit tests).

## Compliance impact

- PCI DSS: not applicable (no cardholder data in the onboarding read-model).
- DORA:    Art. 17 — every operator/agent action is reconstructable from the hash-chained
           audit trail within the incident-response window; the projection is rebuildable
           from the source event log.
- GDPR:    Art. 17 erasure is an explicit, four-eyed, step-up-gated action; the cockpit
           applies `PiiMask` by role so PII is shown only to `COMPLIANCE`.
- PSD2:    not applicable at the operational-cockpit layer (login/SCA are ADR-0021/0066);
           the cockpit only observes when SCA device enrollment completes.
- CNB:     supports the AML/KYC obligation — sanctions/PEP screening results cannot be
           overridden without compliance + supervisor four-eyes and a recorded justification.

## References

- ADR-0021 — SCA decoupled device approval (customer-side; separate from operator step-up).
- ADR-0029 — Governance as code (hash-chained audit, derived catalog/coverage).
- ADR-0030 — Threat models / money-path review rigour.
- ADR-0031 — AI agent governance (AI-attributed audit, approval queue concept).
- ADR-0034 — Unified OPA authz for REST and MCP (advisory→enforce; new endpoints enforce).
- ADR-0048 — Two version axes (release vs. API contract).
- ADR-0064 / 0065 / 0066 — Customer app, customer edge + realm, passwordless onboarding.
