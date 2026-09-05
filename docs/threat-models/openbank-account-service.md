<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — account-service

- **Date:** 2026-05-30
- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). Money-path bounded context.
- **Service ADR:** see `docs/adr/` (account lifecycle); platform controls per ADR-0029/0030/0034.

## 1. Scope & purpose

BIAN-aligned account lifecycle: open / query / freeze / unfreeze / close, IBAN allocation,
multi-currency pockets. Source of truth for account *identity and state* (not balances —
that is balance-service).

## 2. Data flow (DFD)

```
[Operator/Admin UI] --OIDC--> (REST /api/v1/accounts*) --> [account-service] --> [(Postgres: accounts, account_pockets)]
                                                                |
                                                                +--> [(account_outbox)] --outbox--> [Kafka account events]
                                                                |
                                                                +--> [sanctions-service] (OIDC M2M, sync, ADR-0032)
                                                                |
                                                                +--> [product-catalog] (OIDC M2M read, sync, fail-open, ADR-0158)
[Kafka party events] --in--> [account-service PartyEventConsumer] --activate--> [account-service]
                                                                |
                                                                +--M2M client_credentials (ROLE_OPERATOR)--> [transaction-service POST /api/v1/transactions]   (welcome bonus, sandbox-only)
```

- **External entities:** operators/admins (human, OIDC via Keycloak), downstream consumers of account events, party-service (event source).
- **Trust boundaries:** UI↔service (mTLS + OIDC + OPA authz, ADR-0034); service↔Postgres; service↔Kafka (outbox + party-events-in); **service↔transaction-service (outbound M2M, new — welcome-bonus grant)**; **account-service↔sanctions-service** (new, OIDC M2M, ADR-0032 §C); **account-service↔product-catalog** (OIDC M2M read, ADR-0158 — fail-**open**, a deliberately different posture from the sanctions gate: an unreachable product catalogue is reference-data unavailability, not a compliance risk, and must never block account opening).
- **Assets:** account identity, IBAN, freeze/closure state, ownership linkage, **the oidc-client M2M secret** (grants ROLE_OPERATOR on the money path).

## 3. Authn/Authz

- Mutating endpoints: `@RolesAllowed("ROLE_OPERATOR", "ROLE_ADMIN")`. Read/info: `@PermitAll`.
- Centralized policy via OPA sidecar (ADR-0034, advisory→enforce).
- Four-eyes approval-decide endpoint: same role set as the gated action, plus a domain-level
  segregation-of-duties check (checker id != maker id) — see §4a.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Caller impersonates operator | OIDC bearer + mTLS; no anonymous mutation |
| **T**ampering | Forced freeze/close, IBAN reassignment | RBAC on all mutations; state-machine guards; DB constraints; audit trail |
| **T**ampering | Open an account in a currency incompatible with its selected product | Confirmed catalog product responses are checked server-side for product identity and currency. A missing product rejects the request; an unavailable catalog stays explicitly fail-open as reference-data unavailability, with skipped validation logged. |
| **R**epudiation | Operator denies freezing an account | AuditEvent per lifecycle transition (immutable, ADR audit) |
| **I**nfo disclosure | IBAN / account enumeration via `/iban/{iban}` | AuthZ on lookup; rate limiting at gateway; no PII in IBAN response beyond need |
| **I**nfo disclosure / **IDOR** | Customer reads another party's account/balance via a guessed id (reads are gated by role, not ownership; the edge calls with a ROLE_OPERATOR M2M token) | Primary control is at the customer-edge (resolves ownership before proxying, finding A1). **Defense-in-depth here:** when a call carries `X-Customer-Party-Id` the read must belong to that party, else 404 (no existence oracle) — catches an edge bug/new route that forwards the header but skips its own check. Operator/service reads (no header) unaffected. |
| **I**nfo disclosure | Domain metrics leak PII / enable per-customer inference via high-cardinality labels | `DomainMetrics` low-cardinality contract (ADR-0077): `openbank.accounts.created` tagged only by `product_type` (closed `AccountType` enum) + `currency`; `openbank.accounts.closed` adds a `reason` **normalized to a closed set** (`customer_request`/`regulatory`/`fraud`/`inactivity`/`unspecified`/`other`) — the operator-supplied free-text reason never becomes a label; outbox-backlog gauge tagged only by `service`. Never an account id, IBAN, party id, or balance. Counters increment only after the commit + publish. `/q/metrics` is cluster-internal |
| **I**nfo disclosure | Authorization-id enumeration via the revoke route: `DELETE /api/v1/accounts/{accountId}/authorizations/{authorizationId}` distinguishing "this id exists on another account" from "this id does not exist" | `AuthorizationNotFoundExceptionMapper` and `AuthorizationNotOnAccountExceptionMapper` both answer 404 with a byte-identical `AUTHORIZATION_NOT_FOUND` body, so an operator scoped to one account gets no existence oracle over ids on accounts they do not hold. The distinction is kept in a WARN log, not on the wire. Same posture as the `X-Customer-Party-Id` row above. |
| **D**oS | Mass open/close churn | Gateway rate limits; outbox decouples event load |
| **E**oP | Viewer escalates to freeze/close | Distinct roles; OPA enforce; deny-by-default |
| **S**poofing / **E**oP (M2M) | Compromise of the `oidc-client` secret → mint a ROLE_OPERATOR token → inject arbitrary transactions via transaction-service `POST /api/v1/transactions` | Secret held only in a K8s Secret (ExternalSecret from Vault), never in image/git; rotatable; least-privilege client (`openbank-services`); welcome-bonus call is **flag-gated default-OFF** and **sandbox-only**. Residual: transaction-service does not currently distinguish caller identity beyond the role — accepted residual risk in sandbox (see §5) |

## 4a. Four-eyes approval (ADR-0155) — STRIDE supplement

`POST /{accountId}/freeze` (`account.freeze`) is a money-path action OPA (`rest.rego`) can flag
`four_eyes_required`. New endpoint `PATCH /api/v1/accounts/approvals/{id}` lets a DIFFERENT
operator decide the resulting `PendingApproval`; the maker retries `POST /{accountId}/freeze`
with an `X-Approval-Id` header. **`authz.four-eyes.enforce` stays `false` in this PR** — the
`ApprovalStore`/endpoint are wired (mirroring the sepa-payment pilot, issue #413), but blocking
is a deliberate follow-up flip, not bundled here (see ADR-0155).

| STRIDE | Threat | Mitigation |
|---|---|---|
| **S**poofing | A caller other than an operator decides an approval | `@RolesAllowed(Roles.OPERATOR, Roles.ADMIN)` + OPA `@Authorize(action="account.approval.decide")` on the decide endpoint |
| **E**oP | The maker approves their own freeze request (self-approval defeats maker-checker) | `ApprovalStore.decide` throws `SelfApprovalNotAllowedException` (mapped to 403) when `decidedBy == makerId` — enforced in the domain port itself, not just the REST layer, and `makerId`/`decidedBy` both resolve via the same `.principal.name` extraction (interceptor vs. `SecurityIdentity`) so the comparison can't silently mismatch for the same real person |
| **T**ampering | A stale, mismatched, or already-consumed `X-Approval-Id` is replayed to unlock a different request | `AuthorizeInterceptor` requires the approval's `action` + `resourceId` + `makerId` to match the CURRENT request exactly, `status == APPROVED`, and marks it `EXECUTED` (one-time use) on success; any mismatch re-issues a fresh pending approval instead of proceeding |
| **R**epudiation | No record of who approved a gated freeze | `PendingApproval.decidedBy` + `decidedAt` recorded in the approval record itself (Redis, TTL-bounded — see ADR-0155 Negative consequences: not yet a permanent audit trail) |
| **I**nfo disclosure | Approval id enumeration reveals account/action metadata to an unauthorized caller | `find`/`decide` require the caller to already hold a valid, role-gated session; the id itself is a random id (`RedisApprovalStore`, not sequential) |
| **D**oS | Flooding `POST /{accountId}/freeze` to exhaust Redis with pending approvals | Bounded by the same rate-limit/idempotency controls as the gated endpoint itself; each `PendingApproval` is TTL-bounded (86400s) so abandoned records expire |

**DFD update:** adds `Operator (checker) → PATCH /api/v1/accounts/approvals/{id} → Redis (approval:*)`
alongside the existing `POST /{accountId}/freeze` edge; the maker's retry reuses the existing DFD edge.
**Risk class:** integrity (segregation of duties) + confidentiality (approval record scope).
**Rollback:** `authz.four-eyes.enforce=false` (default) — the endpoint and store exist but do
not change any existing request's outcome until explicitly flipped.

## 5. Residual risks / assumptions

- Relies on Keycloak realm integrity and OPA policy correctness.
- Freeze now has the four-eyes *mechanism* wired (§4a) but not enforced (`authz.four-eyes.enforce=false`);
  close remains single-actor — a candidate for a follow-up rollout under issue #413.
- **`closeAccount`'s balance guard (2026-08-16 entry below) is best-effort, not a guarantee:**
  (a) the balance check and the close persist are not atomic, so money settling in that window
  still lands on a closed account; (b) it only sees currently-booked/reserved balance, not money
  already in flight (a future-dated transfer reads as zero and still executes later). Closing (a)
  needs a lock spanning balance-service and this service, or a saga; closing (b) needs a check
  against every service that can schedule a future credit (standing-order-service, sepa-instant,
  domestic-payment). Neither exists yet — tracked as a follow-up, not blocking this change since
  it is a strict improvement over the prior state of no guard at all.

## 6. Change log

- **2026-09-05** — **Inbound REST error surface on the authentication boundary**, no new route,
  caller, edge or privilege. A security abort (anonymous or under-roled caller hitting a
  `@RolesAllowed` route) was rendered by Quarkus REST's built-in handling as the raw exception
  message in a plain-text body under the resource's negotiated `application/json` content-type —
  a 401 whose body was the literal text `Not Authenticated` while declaring itself JSON, which any
  JSON-parsing client (and the VoP consumer pact's anonymous-IBAN-lookup replay, #8803) breaks on.
  Three service-local mappers (`io.quarkus.security` Unauthorized / AuthenticationFailed → 401
  `UNAUTHORIZED`, Forbidden → 403 `FORBIDDEN`) now answer with the standard `ApiError` envelope and
  fixed messages. **Security-relevant half:** the fixed message names neither the failure detail
  nor the underlying mechanism, so the abort leaks strictly less than the raw exception text did;
  status codes are unchanged, so no client-visible signaling about account or authorization
  existence moves. The mappers live in the service rather than libs-runtime because a
  shared-library `@Provider` naming an `io.quarkus.security` type is the #6240 ArC boot-failure
  class (enforced by the `provider-type-classpath` gate). The anonymous 401 itself is now covered
  by contract: the VoP pact interaction is replayed with the `@TestSecurity` identity cleared
  (TestIdentityAssociation), so "no caller identity → 401" is verified, not assumed. RBAC, OPA and
  authentication itself are untouched. **Risk class:** availability/observability plus disclosure
  hardening; no money mutation and no new principal. Rollback: delete the three mappers and the
  aborts fall back to Quarkus' built-in text rendering. (#8803)

- **2026-09-04** — **Inbound REST error surface on account opening**, no new route, caller, edge
  or privilege. Two sanctions-screening outcomes rendered 500 INTERNAL_ERROR through the generic
  mapper: an unreachable screening service (the gate fails closed, ADR-0032 §C) and a policy
  refusal (HIT/REVIEW). They now answer 503 with `Retry-After: 30` and 422 respectively, via two
  dedicated mappers in `ExceptionMappers.kt`; the routine policy refusal also stops logging at
  ERROR with a full stack (WARN, no stack), so it no longer consumes this money-path service's
  5xx error budget. **Security-relevant half — the disclosure the naive fix would have shipped:**
  re-parenting `AccountOpeningBlockedByScreeningException` to `IllegalStateException` would have
  fixed the status and put the matched sanctions name plus partyId into the response body
  (libs-runtime's 422 mapper echoes `exception.message`), handing the caller a sanctions-list
  oracle. The mapper bodies are fixed strings naming neither; a unit test asserts the matched
  name and partyId appear nowhere in the body, and that a refusal with a match is
  indistinguishable from one without. RBAC, OPA and the screening call itself are untouched.
  **Risk class:** availability/observability plus disclosure hardening; no money mutation and no
  new principal. Rollback: delete the two mappers and both types fall back to the generic 500
  mapper. (#8512)

- **2026-09-03** — **Inbound REST error surface on the authorization routes**, no new route, caller,
  edge or privilege. Revoking an authorization that does not exist answered 500 INTERNAL_ERROR; it now
  answers 404, via two service-local mappers (`AuthorizationNotFoundExceptionMapper`,
  `AuthorizationNotOnAccountExceptionMapper`) added to the existing
  `com.openbank.account.infrastructure.rest.ExceptionMappers` file. RBAC, OPA action
  (`account.authorize`) and the revoke logic are untouched — only the status and body of an
  already-refused request change. **Security-relevant half:** the cross-account case
  (`AuthorizationNotOnAccountException`) previously leaked through the generic 500 and now returns a
  body byte-identical to the unknown-id case, closing an authorization-id existence oracle for an
  operator scoped to a different account; a unit test asserts the two bodies match field for field.
  **Risk class:** availability/observability (a client fault stops consuming this money-path service's
  5xx error budget) plus the enumeration hardening above; no money mutation and no new principal.
  Rollback: delete the two mappers and both types fall back to the generic 500 mapper. (#5913)

