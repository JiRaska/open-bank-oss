# Threat model — openbank-delegation-service (ADR-0030, ADR-0232)

Status: draft for first deploy. Money-path-adjacent: the service mints payment rights,
so abuse cases are authorization failures, not data-loss failures.

## Assets

- `DelegationGrant` records — who may do what on whose resources (confidential; contract
  evidence under the ADR-0118 retention schedule).
- Enforcement integrity of the whole platform: every product service's delegation
  projection trusts this service's event stream.
- SCA ceremony integrity (grant + acceptance).

## Trust boundaries

1. Customer edge → REST API (OIDC JWT, coarse `ROLE_API` + OPA sidecar per ADR-0034).
2. delegation-service → sca-service / pid-service (REST, fail-closed on outage).
3. delegation-service → Kafka (`openbank.delegation.events`) → product-service
   projections (ADR-0232 D3).

## Threats and mitigations

| # | Threat | Mitigation |
|---|--------|-----------|
| T1 | Grantor offers a grant for a resource they do not own | Product services enforce `owner OR grant` at the resource; a grant over a foreign resource id never matches an ownership check. Follow-up: offer-time ownership callback per resource type. |
| T2 | SCA ceremony replay / cross-party substitution | Challenge must be COMPLETED, purpose-bound (`DELEGATION_GRANT`/`DELEGATION_ACCEPT`) and party-bound; accept requires the *grantee's* challenge. |
| T3 | Eligibility bypass (un-KYC'd or sanctioned grantee) | Fail-closed eligibility gate at offer (D5); KycLevel.FULL for execution capabilities. Follow-up: continuous re-screening on party status change events → auto-suspend. |
| T4 | Repudiation ("I never granted that") | Both ceremonies SCA-bound with session ids persisted on the aggregate; every transition emits an outbox event; audit chain threading (ADR-0133). |
| T5 | Outbox/event forgery feeding product projections | Kafka mTLS (ADR-0137 pattern); projections key on aggregate id and re-check status transitions monotonically. |
| T6 | Revocation delay (eventual consistency) | Documented seconds-level propagation (ADR-0232 Negative); fraud suspend hook consumes the same topic; grantor UX states the window honestly. |
| T7 | Idempotency replay duplicating offers | `X-Request-ID` idempotency store (Redis) on POST /delegations. |
| T8 | Excessive grant harvesting (`/grantor`, `/grantee` lists) | OPA action `delegation.list` scoped to the named party; `/check` returns a decision, never the grant (mirrors ADR-0198 D4 reasoning). |
| T9 | Panache/native-SQL injection in claim query | Static SQL constants, bound parameters only (fleet-wide #1201 pattern). |
| T10 | Object-level grant used as execution channel | Aggregate invariant: EXECUTION capabilities rejected on object resource types; object grants are read-only disclosure. |

## Out of scope (tracked as follow-ups)

- External disclosure links (D7b): OTP, expiry, watermark, view counting — threat model
  update required when that lands (leaked-link = leaked-document analysis).
- EUDI verifiable-credential delivery channel (follow-up ADR on ADR-0094).
- `AccountAuthorization` migration dual-run window (two grant sources for accounts).
