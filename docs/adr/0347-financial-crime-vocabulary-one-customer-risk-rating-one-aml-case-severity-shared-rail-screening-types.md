---
date: 2026-10-20
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [aml-sanctions, kyc, fraud, libs]
summary: "Customer risk rating and AML case severity are two concepts, not one scale. kyc's rating is canonical; pid's never-computed score is deprecated. Rail copies of aml/sanctions/fraud enums move to openbank-libs-fincrime."
---

# ADR-0347 — Financial-crime vocabulary: one customer risk rating, one AML case severity, shared rail screening types

## Context

ADR-0116 decided the KYC engine and its `riskLevel` (`LOW/MEDIUM/HIGH/VERY_HIGH`, default
`MEDIUM`, re-KYC cadence LOW=5y / MEDIUM=3y / HIGH+=1y). ADR-0032 decided the synchronous
sanctions/AML screening gate in each payment rail, and ADR-0084 the fraud verdict
`ALLOW/CHALLENGE/REVIEW/DECLINE`. ADR-0317 decided that business vocabulary shared by a few
services lives in a per-bounded-context, framework-free libs module. ADR-0319 (proposed)
would generate provider clients from `openapi.yaml`, starting with non-money-path services.
None of them decides which risk scale is canonical or where the rail copies live; the
fleet audit of 2026-10-20 (#13435, #13439) and the `enum-reference-data-ratchet` gate (#13443)
found the copies. Measured on `origin/main` 84e3f7c773:

| Type | Values | Owner / producer | Crosses the wire | Branches on values |
|---|---|---|---|---|
| `aml` `AmlRiskLevel` | LOW MEDIUM HIGH CRITICAL | aml-service (case severity) | aml `openapi.yaml` enum; `aml_cases.risk_level VARCHAR(32)`, no CHECK; AML case event `riskLevel`; 4 pacts (`CRITICAL`); admin-ui `amlCaseContract.ts`, `amlCaseEvidence.ts` (HIGH/CRITICAL ⇒ UNDER_REVIEW) | aml-service status on open |
| `AmlCaseRiskLevel` ×4 | LOW MEDIUM HIGH CRITICAL | copy in `port/out/ScreeningPorts.kt` of domestic-payment, fx, sepa-instant, sepa-payment | sent by name to aml `POST` cases | rails choose MEDIUM (screening unavailable), HIGH (review), CRITICAL (block / sanctions hit) |
| `kyc` `RiskLevel` | LOW MEDIUM HIGH VERY_HIGH | kyc-service (customer rating) | kyc `openapi.yaml` enum; `kyc_cases.risk_level VARCHAR(20) DEFAULT 'MEDIUM'`, no CHECK; KYC event `riskLevel`; admin-ui `kycEvidenceContract.ts` | `KycService` PEP escalation floors at HIGH |
| `pid` `AmlRiskScore` | LOW MEDIUM HIGH UNACCEPTABLE | pid-service | pid `openapi.yaml` enum; `chk_aml_risk` CHECK constraint | **none** — the only writers set `LOW` (`IdentityRegistrationService`, `PartyService`); nothing reads it to decide anything |
| `FraudVerdict` ×4 + fraud-service | ALLOW CHALLENGE REVIEW DECLINE (+ UNKNOWN in rails) | fraud-service | fraud `POST /api/v1/fraud/score` | rail adapters map unrecognised ⇒ UNKNOWN, never ALLOW (#4403) |
| `ScreeningRole`, `ScreeningMatchStatus`, `ScreeningDecision`, `ScreeningPolicy` ×4 | DEBTOR CREDITOR / CLEAR POTENTIAL_HIT HIT WHITELISTED ESCALATED / CLEAR REVIEW BLOCK | copies in each rail's `domain/screening` | sanctions-service response, mapped in each rail's adapter (unknown ⇒ ESCALATED ⇒ BLOCK) | threshold `0.85` duplicated from sanctions `SanctionsCheck.isHighRisk` four times |

The issue framed this as "three incompatible scales". Measured, it is two different
concepts sharing words: **customer risk rating** (how risky is this relationship, which
decides due-diligence depth and review cadence — kyc and pid) and **AML case severity**
(how urgent is this alert — aml and its four rail callers). The case-severity copies are
value-identical; only the customer rating actually disagrees, and one side of it (pid) is
never computed.

## Decision

1. **Two concepts, never mapped onto each other.** A case's severity does not set a
   customer's rating or the reverse; no code converts between them. Names make this
   explicit: `CustomerRiskRating` and `AmlCaseSeverity`.
2. **Customer risk rating: kyc-service's scale is canonical** — `LOW, MEDIUM, HIGH,
   VERY_HIGH`, owned by kyc-service (ADR-0116), unchanged on the wire and in the DB.
   It is the scale with an owner that computes it, a default, an escalation rule and a
   review cadence attached. pid-service's `amlRiskScore` is **not** a second rating: it is
   deprecated (openapi `deprecated: true`, KDoc), its value set and CHECK constraint stay as
   they are, and removal is a later API-major. If its data is ever carried over, the mapping
   is LOW→LOW, MEDIUM→MEDIUM, HIGH→HIGH, UNACCEPTABLE→VERY_HIGH plus a refused
   relationship — `UNACCEPTABLE` is a decision about the relationship, not a level, and
   kyc expresses it as a REJECTED case.
3. **AML case severity: aml-service's `LOW, MEDIUM, HIGH, CRITICAL` is canonical**, unchanged.
4. **New module `openbank-libs-fincrime`** (ADR-0317 context module, framework-free, package
   `com.openbank.libs.fincrime`) holds the published language the rails share:
   `AmlCaseSeverity`, `ScreeningRole`, `ScreeningMatchStatus`, `ScreeningDecision`,
   `ScreeningPolicy` (one copy of the 0.85 threshold) and `FraudVerdict` including
   `UNKNOWN`. Producers adopt it too (aml-service for `AmlRiskLevel`, fraud-service for
   `FraudVerdict`); fraud-service's REST adapter refuses to emit `UNKNOWN` (fail closed).
   `CustomerRiskRating` stays in kyc-service — one producer, no shared consumer yet.
5. **Every mapping from a wire value is total and fails closed**: an unrecognised sanctions
   status ⇒ `ESCALATED`, an unrecognised fraud verdict ⇒ `UNKNOWN`, an unrecognised severity
   from aml ⇒ reject the response. These exist today in each adapter and move unchanged.
6. **No wire or DB value changes in this ADR.** Only the pid deprecation flag is externally
   visible (an `oasdiff` non-breaking change → MINOR on pid's API axis).

## Alternatives considered

- **One merged scale (e.g. LOW/MEDIUM/HIGH/CRITICAL everywhere).** Rejected: it would map a
  customer's rating onto an alert's urgency, which mean different things, and would force a
  wire+DB migration on kyc, pid and admin-ui for no behavioural gain.
- **Adopt pid's `UNACCEPTABLE` as the top customer level.** Rejected: pid never computes it;
  the scale that drives behaviour is kyc's.
- **Wait for ADR-0319 generated clients to replace the mirrors.** Rejected for now: 0319 is
  `proposed`, starts non-money-path, and would not cover `ScreeningPolicy`, which is logic, not
  contract. When a generated aml/fraud client lands, the pure mirrors can come from it.
- **Put the types in `openbank-libs-domain` core.** Rejected by ADR-0317: 5-6 consumers do not
  justify a 57-module rebuild per change.

## Consequences

**Positive**
- The 0.85 block threshold and the fail-closed mappings exist once; a rail cannot drift.
- 17 `enum-reference-data-ratchet` baseline entries for #13435/#13439 can be deleted.

**Negative**
- One more libs module; the four money-path rails gain a dependency (2 approvals each).

**Neutral**
- Wire values, DB columns and pacts are unchanged; Pact provider replays must stay green.
- Gate: `enum-reference-data-ratchet` (#13443) is the enforcement — a re-introduced copy fails it.

## Delivery check

- `git grep -nE 'enum class (AmlCaseRiskLevel|FraudVerdict|ScreeningDecision|ScreeningMatchStatus|ScreeningRole)\b' -- 'openbank-*/src/main'`
  prints only paths under `openbank-libs-fincrime/`.
- `grep -cE '#1343(5|9)' .github/scripts/enum-reference-data-baseline.txt` prints `0`.
- `git grep -n 'POTENTIAL_HIT_BLOCK_THRESHOLD = ' -- '*/src/main'` prints one line.

## Compliance impact

- PCI DSS: not applicable — no card data.
- DORA: not applicable — no change to ICT risk controls or incident handling.
- GDPR: not applicable — no personal data field added or moved.
- PSD2: not applicable — fraud verdict semantics unchanged.
- CNB: AML/KYC supervision — the customer risk rating that drives due-diligence depth and the
  re-KYC cadence keeps one owner (kyc-service, ADR-0116); no change to the four-eyes gate.

## References

- #13435, #13439, #13443; ADR-0032, ADR-0084, ADR-0116, ADR-0260, ADR-0317, ADR-0319.
