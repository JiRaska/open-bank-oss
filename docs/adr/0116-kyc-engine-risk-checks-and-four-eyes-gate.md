---
date: 2026-06-25
decision-status: accepted
delivery-status: shipped
authors: [Claude (paired with Jiří Raška)]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [kyc, aml-sanctions, compliance]
summary: "The KYC engine runs five risk-based checks with a split opener/reviewer four-eyes gate, screens PEPs against the free OpenSanctions dataset (matches escalate to manual review, never auto-reject), with a sandbox straight-through mode."
---

# 116. KYC engine — risk-based checks, ČNB four-eyes gate, sandbox straight-through mode

**Delivery note (updated 2026-07-09):**
- **Role-split** — ✅ Shipped: `ROLE_KYC_OPENER` (case opener, check updates) and
  `ROLE_KYC_REVIEWER` (approve/reject) introduced in `openbank-libs-domain` `Roles.kt`.
  `KycResource.kt` updated: `openCase` + `updateCheck` require `KYC_OPENER`; `approveCase` +
  `rejectCase` require `KYC_REVIEWER`. Legacy `ROLE_KYC` kept on read-only endpoints and in
  the realm template (marked legacy) for backwards compat during Keycloak migration. Operator
  step: reassign existing `ROLE_KYC` users to the appropriate sub-role in Keycloak.
- **Periodic re-KYC** (§5) — Planned: requires a Temporal scheduled workflow. Still not
  implemented — the PEP screen below runs at case-open time (and on an operator-triggered manual
  re-screen), not on the LOW=5y/MEDIUM=3y/HIGH+=1y cadence this ADR describes.
- **PEP screening** — ✅ Shipped (first increment, free data source only): `PEP_SCREENING` (one of
  the five mandatory checks in §2) now actually screens, instead of being a manual-only check an
  operator updates by hand. `PartyEventConsumer` calls `PepScreeningService` when a case opens
  (using the party's `legalName` from the `PARTY_CREATED` event), which calls
  `openbank-sanctions-service`'s `POST /api/v1/sanctions/screen` scoped to `listTypes:
  ["PEP_GLOBAL"]` — the dataset sanctions-service already imports from OpenSanctions'
  free, public, dedicated PEP dataset (`https://data.opensanctions.org/datasets/latest/peps/targets.simple.csv`,
  ~757k targets), reusing its existing `pg_trgm` fuzzy-match engine rather than importing/matching
  independently. A match (or a downstream-unavailable outcome) sets `PEP_SCREENING` to
  `MANUAL_REVIEW` (never auto-rejects — still needs four-eyes) and escalates `riskLevel` to at
  least `HIGH`. An operator-triggered re-screen endpoint
  (`POST /api/v1/kyc/cases/{caseId}/pep-rescreen`) exists for when the PEP list has since been
  refreshed; it is NOT the periodic re-KYC programme above.
  **Explicitly NOT delivered by this increment:** a paid commercial vendor feed (Refinitiv,
  ComplyAdvantage, World-Check, etc.), identity-document verification, continuous/real-time
  monitoring, or automatic periodic re-screening on the ADR-0116 §5 cadence (still needs the
  Temporal workflow above). `openbank-kyc-service` has no threat model
  (`docs/threat-models/`) — it is not in `rules.yaml: money_path_services`, so one is not gated,
  but this is a compliance-relevant service handling PII and a threat model would still be good
  practice; not written here to keep this increment bounded.
- **External watchlist (sanctions)** — Planned (pre-production): `SANCTIONS_SCREENING` still uses
  an internal watchlist — unchanged by this increment, which only wires up `PEP_SCREENING`.

## Context

`openbank-kyc-service` implements KYC case management. ADR-0068 (onboarding cockpit) documents the
operator UI flow; ADR-0069 (customer onboarding journey) documents the customer-facing flow. Neither
documents what the KYC engine itself checks, how the four-eyes gate works mechanically, or why a
`openbank.kyc.auto-approve` feature flag exists.

This ADR captures those decisions so they can be audited against ČNB AML requirements.

## Decision

**1. KYC case lifecycle.**

```
OPEN → DOCUMENTS_REQUIRED → UNDER_REVIEW → APPROVED (terminal)
                                          → REJECTED  (terminal)
OPEN / DOCUMENTS_REQUIRED / UNDER_REVIEW → EXPIRED   (terminal, time-driven)
```

> **Correction, 2026-09-05.** Three gaps between this lifecycle and the code, found together:
>
> - **`DOCUMENTS_REQUIRED` was never implemented** (#8535). No operation in `KycService` sets it,
>   and the service has no concept of a document type or any upload path. Removing it from the
>   enums and contracts is blocked by two mutually exclusive CI gates (#8618); the dead cockpit
>   column it fed has been removed, and the `KYC_DOCUMENT_REQUIRED` notification template with it.
> - **`ESCALATED` was never a KYC case status at all.** The lifecycle diagram drew it with four
>   transitions; it is not in `KycCaseStatus`. Escalation here is a check in `MANUAL_REVIEW` plus
>   `due_diligence_level = EDD`, with the case staying `UNDER_REVIEW`; MLRO escalation is a state
>   of the *AML* case in aml-service (ADR-0032).
> - **`EXPIRED` was declared and nothing set it** (#8548). `expires_at` was written on every case
>   and never acted on, and because `uq_kyc_cases_active_party` treats only
>   `APPROVED`/`REJECTED`/`EXPIRED` as terminal, an abandoned case blocked that party from ever
>   opening another. Fixed by the daily sweep in #8562.

Terminal states: `APPROVED`, `REJECTED`, `EXPIRED`. A party with an active (non-terminal) case
cannot have a second case opened (409 Conflict from the operator-facing path; idempotent reuse
on the event-driven path).

**2. Mandatory check set.**

Every case opens with five checks in `PENDING` status:

| CheckType | What it validates |
|-----------|------------------|
| `IDENTITY` | Government-issued ID document verification |
| `ADDRESS` | Proof of address |
| `PEP_SCREENING` | Politically Exposed Person screening |
| `SANCTIONS_SCREENING` | Sanctions list check (delegates to openbank-sanctions-service) |
| `ADVERSE_MEDIA` | Negative news screening |

Initial `riskLevel` is `MEDIUM` for all new parties — the conservative default. An operator may
downgrade to `LOW` or escalate to `HIGH`/`VERY_HIGH` based on check outcomes.

**3. Four-eyes gate — state-based, not role-based.**

The four-eyes requirement (ADR-0068) is enforced by the **state machine**, not by role separation:

- `approve` and `reject` are only valid from `UNDER_REVIEW`. Attempting either on a case in any
  other state throws `InvalidStateTransitionException` → HTTP 422.
- The reviewer identity (`approvedBy`/`reviewedBy`) must come from the authenticated security
  context — it is never accepted from the request body.
- Minimum reason length is **10 characters** (ČNB AML Act No. 253/2008 Coll. §8 audit trail).

**Important limitation:** there is a single `ROLE_KYC` role. The system does not technically
prevent the same user who opened a case from also approving it. Maker-checker separation at the
user level relies on **organisational control** (assigning different operators to opener and
reviewer roles in Keycloak), not on a code-level enforcement. A future hardening step should
introduce `ROLE_KYC_OPENER` and `ROLE_KYC_REVIEWER` as distinct roles.

**4. Sandbox straight-through processing (STP).**

`openbank.kyc.auto-approve` (default: `false`) enables sandbox onboarding without an operator:

- When `true`, a case opened via the `PARTY_CREATED` Kafka event is auto-evaluated (all checks →
  `PASSED`) and immediately approved (`reviewedBy = "sandbox-auto-approval"`).
- **MUST remain `false` in production.** Flipping it in production bypasses the four-eyes gate
  entirely and violates the AML Act.
- This is a SmallRye config property, not an OpenFeature feature flag (ADR-0067). Changing it
  requires a service restart.

**5. Periodic re-KYC.**

Approved KYC cases are not permanent. A regulatory re-KYC programme (cadence: LOW=5 years,
MEDIUM=3 years, HIGH/VERY_HIGH=1 year) is out of scope for this ADR. It requires a Temporal
scheduled workflow to identify expired approvals and re-open cases.

## Alternatives considered

- **External KYC provider (Onfido, Jumio, Sum Sub).** Faster time-to-live, managed identity
  verification, but vendor lock-in and data residency risk for ČNB/GDPR. Deferred as a future
  integration option.
- **Fully automated KYC (no four-eyes).** Eliminates manual review but violates ČNB AML
  requirements for MEDIUM+ risk parties.
- **Role-split enforcement now.** `ROLE_KYC_OPENER` vs `ROLE_KYC_REVIEWER` at code level would
  be the strongest control, but requires Keycloak role restructuring across the fleet. Deferred
  to a follow-up hardening ADR.

## Consequences

**Positive**
- Four-eyes state gate meets the baseline ČNB AML four-eyes requirement.
- STP flag enables sandbox e2e onboarding without operator intervention.
- Minimum reason length enforces an auditable paper trail for every approval/rejection.

**Negative**
- A single `ROLE_KYC` user can both open and approve a case — maker-checker is organisational
  convention, not a technical guarantee. This is a gap for a production audit.
- `PEP_SCREENING` now screens a free, public PEP dataset (see delivery note) — a real
  improvement, but NOT equivalent to a licensed commercial KYC vendor (no paid feed, no document
  verification, no continuous monitoring). `SANCTIONS_SCREENING` is unaffected by this increment
  and still delegates to `openbank-sanctions-service`'s internal watchlist only.
- Periodic re-KYC is not implemented — the PEP screen above runs at case-open time and on
  operator-triggered manual re-screen only, not on the ADR §5 cadence.

**Neutral**
- `riskLevel = MEDIUM` as default is conservative; operators can downgrade after check completion.
- The event-driven open path (`openCaseForParty`) is idempotent (reuse on retry); the operator
  path (`openCase`) rejects duplicates with 409.

## Compliance impact

- GDPR Art. 9: KYC data are sensitive personal data → legal basis is AML Act obligation;
  retention is 5 years after end of business relationship (AML Act §16) per ADR-0117.
- AML Act No. 253/2008 §8: four-eyes gate + auditable reason enforced.
- PSD2: KYC is a prerequisite for onboarding, not a payment flow control.
- ČNB: full AML compliance requires integration with a certified external watchlist provider
  before production go-live.
- DORA: not applicable to this ADR specifically.

## References

- `openbank-kyc-service/src/main/kotlin/.../application/KycService.kt`
- `openbank-kyc-service/src/main/kotlin/.../application/PepScreeningService.kt` (first-increment PEP check orchestration)
- `openbank-kyc-service/src/main/kotlin/.../application/port/out/PepScreeningPort.kt`
- `openbank-kyc-service/src/main/kotlin/.../infrastructure/client/SanctionsScreeningAdapter.kt` (calls openbank-sanctions-service, PEP_GLOBAL only)
- `openbank-kyc-service/src/main/kotlin/.../domain/model/KycCase.kt`
- `openbank-libs/src/main/kotlin/com/openbank/libs/security/Roles.kt` (single `ROLE_KYC`)
- ADR-0068 (onboarding cockpit — four-eyes UI)
- ADR-0069 (customer onboarding journey)
- ADR-0032 (synchronous AML/sanctions screening gate)
- AML Act No. 253/2008 Coll. §8 (Czech AML — audit trail requirement)
