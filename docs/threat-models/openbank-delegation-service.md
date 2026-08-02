# Threat model — openbank-delegation-service (ADR-0030, ADR-0232)

Status: draft for first deploy. Money-path-adjacent: the service mints payment rights,
so abuse cases are authorization failures, not data-loss failures.

Rows T1, T2, T4 and T8 were rewritten after review found they described mitigations the code did
not implement — the T1 and T2 claims were not merely incomplete, they were the reasoning that let
an account-takeover chain and an unlimited-SCA-replay ship as "mitigated". Where a gap remains it
is now stated in the row rather than implied away.

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
| T1 | Grantor offers a grant for a resource they do not own | **Offer-time ownership gate** (`ResourceOwnershipClient`): the grantor's ownership of ACCOUNT/SAVINGS_GOAL is confirmed against account-service and of CARD against card-issuance-service; UNVERIFIABLE (outage, or an object type with no lookup wired) refuses the offer. The previous mitigation was **wrong**: it claimed a product service's `owner OR grant` check would reject a foreign resource id, but that check is a DISJUNCTION — an active grant row is authority by itself, and the projection carries no grantor to compare. Two consenting parties could therefore mint payment rights over a third party's account with nothing but their own valid SCA. Residual: the consuming projection still does not compare the grant's grantor to the resource owner (defence in depth, tracked for the account/card projection PRs). |
| T2 | SCA ceremony replay / cross-party substitution | Challenge must be COMPLETED, purpose-bound (`DELEGATION_GRANT`/`DELEGATION_ACCEPT`) and party-bound; accept requires the *grantee's* challenge — **and the challenge is then SPENT** through sca-service's compare-and-consume gate (RTS Art. 5 single-use, atomic on `consumedAt`). Reading `status == COMPLETED` alone did not mitigate replay at all, which this row previously claimed: completion is a fact that stays true, so one ceremony authorised unlimited grants of arbitrary scope. Residual: the challenge is not dynamic-linked to the grant's *content* (resource, capabilities, ceilings) — that needs a delegation-shaped `DynamicLinkingData` in sca-service. |
| T3 | Eligibility bypass (un-KYC'd or sanctioned grantee) | Fail-closed eligibility gate at offer (D5); KycLevel.FULL for execution capabilities. Follow-up: continuous re-screening on party status change events → auto-suspend. |
| T4 | Repudiation ("I never granted that") | Both ceremonies SCA-bound with session ids persisted on the aggregate and single-use; every transition emits an outbox event; `closedBy` is now derived from the authenticated caller, not from a query parameter the caller chose. **Gap, stated honestly:** ADR-0133 tamper-evident audit-chain threading is NOT implemented — this service publishes no audit envelope at all. This row previously cited it as a mitigation. Tracked with the money-leg/onBehalfOf slice. |
| T5 | Outbox/event forgery feeding product projections | Kafka mTLS (ADR-0137 pattern); projections key on aggregate id and re-check status transitions monotonically. |
| T6 | Revocation delay (eventual consistency) | Documented seconds-level propagation (ADR-0232 Negative); fraud suspend hook consumes the same topic; grantor UX states the window honestly. |
| T7 | Idempotency replay duplicating offers | `X-Request-ID` idempotency store (Redis) on POST /delegations. |
| T8 | Excessive grant harvesting (`/grantor`, `/grantee` lists, `GET /{id}`) | Every customer-facing handler is scoped by `X-Customer-Party-Id` — the party customer-edge authenticated, the one identity in the request the caller cannot choose. A list for another party is 403; a grant the caller is not party to is **404, not 403**, so the endpoint is not an existence oracle. `delegation_rest_ext.rego` grants the customer actions to the edge principal only, and excludes `service-account-*` from the operator write rule (the shared `openbank-services` identity carries ROLE_OPERATOR in the realm — a role-only rule would hand every backend service the power to mint or revoke payment rights). `/check` returns a decision, never the grant. |
| T9 | Panache/native-SQL injection in claim query | Static SQL constants, bound parameters only (fleet-wide #1201 pattern). |
| T10 | Object-level grant used as execution channel | Aggregate invariant: EXECUTION capabilities rejected on object resource types; object grants are read-only disclosure. |
| T11 | Unauthorized revoke / forged revoker | `revokedBy` is derived: a customer revoke acts as the party the edge authenticated and must be the grantor; the query parameter survives only on the role-gated bank path. Previously it was a required, trusted query parameter that both authorised the act and wrote the audit field, so any authenticated caller could revoke any grant and sign it as somebody else. `suspend`/`reinstate` are additionally narrowed to ROLE_OPERATOR/ROLE_ADMIN and reject a customer-scoped call. |
| T12 | A grant outliving its `validTo` in the product projections | Hourly expiry sweep flips ACTIVE → EXPIRED and enqueues `DelegationExpired`, so projections close their rows. The sweep is a `suspend fun` (rules.yaml: scheduled_methods): as a plain `@Scheduled` method it ran with no Vert.x context, so every reactive Panache call threw HR000068 into a single ERROR line and the sweep had **never expired a grant**. A grant past `validTo` still reads as inactive to anything that computes it, so the exposure was the projections that need the event. |

## Out of scope (tracked as follow-ups)

- External disclosure links (D7b): OTP, expiry, watermark, view counting — threat model
  update required when that lands (leaked-link = leaked-document analysis).
- EUDI verifiable-credential delivery channel (follow-up ADR on ADR-0094).
- `AccountAuthorization` migration dual-run window (two grant sources for accounts).
- **Cumulative daily/monthly ceilings are not enforced anywhere.** The grant carries them and
  the event publishes them, but no projection counts spend against them, so a `dailyLimit` shown
  to a customer ("max 5 000 Kč/den") is not a control today. Only `perTransactionLimit` binds.
- **No notification on any lifecycle transition** (ADR-0232 D4 requires both parties be told).
- **No sanctions/PEP screening at grant time** (ADR-0232 D5); the eligibility gate checks party
  status and KYC level only.
- **The ADR-0232 D5 SME bridge is unimplemented**: nothing requires a LEGAL_ENTITY grantor's
  acting person to hold `delegation.manage` on that entity.
