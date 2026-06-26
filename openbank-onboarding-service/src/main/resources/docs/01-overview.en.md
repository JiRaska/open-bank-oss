# Overview

## What the service does

`openbank-onboarding-service` is the **read-model projection of the customer onboarding funnel** (ADR-0068). Customer onboarding is a distributed state machine with no orchestrator, choreographed across three services that each own one slice of the truth (party, KYC, SCA). This service observes all three and materialises a single, queryable view for the admin-UI onboarding cockpit. It holds:

- **OnboardingRecord** — one row per party: `legalName`, `email`, `partyStatus`, `kycCaseId`, `kycStatus`, `scaEnrolled`, `deviceCount`, the derived `funnelStage`, and `blockedReason`.
- **A pure, tested funnel-stage function** (`FunnelStage.derive`) that combines the party + KYC + SCA dimensions into one canonical stage for the cockpit board columns.

The record is assembled from inbound domain events; it is **never the source of truth**.

## What the service **does NOT** do

- ❌ Does not own party state — that is `party-service`.
- ❌ Does not own KYC cases or decide approval/rejection — that is `kyc-service`.
- ❌ Does not enroll SCA devices — that is `sca-service` (ADR-0066).
- ❌ Does not write back to party/kyc/sca — it only projects their events.
- ❌ Does not publish events — there is no outbox; it is a pure consumer.
- ❌ Does not (yet) host the four-eyes approval queue or operator step-up. ADR-0068 places those primitives in `openbank-libs` and enforces them inside the owning service; the projection observes them. In this version only the read API exists.

## Position in the domain

```
   ┌──────────────┐  party.events   ┌──────────────────────┐
   │ party-service│ ──────────────► │                      │
   └──────────────┘                 │                      │
   ┌──────────────┐  kyc.events     │ onboarding-service   │
   │ kyc-service  │ ──────────────► │ (read-model)         │
   └──────────────┘                 │                      │
   ┌──────────────┐  sca.events     │                      │
   │ sca-service  │ ──────────────► │                      │
   └──────────────┘                 └─────────┬────────────┘
                                              │ GET /api/v1/onboarding/*
                                              ▼
                                    ┌──────────────────────┐
                                    │ admin-ui cockpit      │
                                    └──────────────────────┘
                                              │
                                              ▼
                                       PostgreSQL
                                  (db: openbank_onboarding,
                                   table: onboarding_records)
```

## Key use cases

| Use case | API | Source event(s) |
|---|---|---|
| Register a new applicant in the funnel | — (projected) | `PARTY_CREATED` (`openbank.party.events`) |
| Move applicant on party status change | — (projected) | `PARTY_STATUS_CHANGED` |
| Track KYC case opening | — (projected) | `KYC_CASE_OPENED` (`openbank.kyc.events`) |
| Track KYC progress / decision | — (projected) | `KYC_CASE_STATUS_CHANGED` / `KYC_CASE_APPROVED` / `KYC_CASE_REJECTED` |
| Mark SCA passkey enrollment | — (projected) | `DEVICE_ENROLLED` (`openbank.sca.events`) |
| List applicants, optionally by stage | `GET /api/v1/onboarding/records?stage=…` | — |
| Applicant detail | `GET /api/v1/onboarding/records/{partyId}` | — |
| Funnel KPI tile counts per stage | `GET /api/v1/onboarding/funnel` | — |

## Callers

- **admin-ui** (via Keycloak token, through the BFF) — operators, compliance staff rendering the onboarding cockpit (KPI tiles, stage board, applicant drawer).

There are no machine consumers of the read API; downstream services consume the source events directly from party/kyc/sca, not from this projection.

## Dependencies

- **PostgreSQL** (database `openbank_onboarding`, table `onboarding_records`)
- **Kafka** — inbound only, topics `openbank.party.events`, `openbank.kyc.events`, `openbank.sca.events`
- **Keycloak** — OIDC auth (realm `openbank`, client `openbank-services`)
- **openbank-libs** — shared runtime plumbing (`ServiceInfoResource` `/api/v1/info`, `DocsResource` for this documentation, build metadata, API-version filter)

## Business value

- **One operational view of onboarding** — cheap per-stage counts and "where is each applicant stuck" without fanning out across three services.
- **Bounded-context hygiene** — the funnel is cross-cutting (party + KYC + SCA); keeping the read-model separate keeps KYC focused on the decision it owns and avoids bloating a money-path service (ADR-0068, alternatives considered).
- **Rebuildable** — being a pure projection, the read-model can be re-seeded by replaying the source event log; it can never corrupt domain invariants.
- **Compliance-adjacent** — surfaces the "approved but no passkey", "failed sanctions screening", and "stuck on documents" tails that operators and compliance need to act on.
