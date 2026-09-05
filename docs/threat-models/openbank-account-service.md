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

- **2026-08-27** — **New tightly scoped inbound diagnostic edge:** the sandbox's
  `journey-product-catalog-read` k6 CronJob may make one read-only request to
  product-catalog `:8104`. The additive NetworkPolicy selects both its `observability`
  namespace and its immutable journey pod labels; it does not admit Grafana, Prometheus,
  or any other observability workload. The Job has no ServiceAccount token or credentials
  and the endpoint returns only the public product list. This is a production-like
  availability assertion, not an access-control bypass: Product Catalog still owns
  request handling, and removing the policy restores fail-closed connection denial.

- **2026-08-26** — The operator approval inbox gains a bounded, read-only
  `GET /api/v1/accounts/approvals` edge. It exposes only the pending approval id, action,
  resource id, maker id and creation time to callers already holding `ROLE_OPERATOR` or
  `ROLE_ADMIN`, with the same OPA policy boundary as the checker decision endpoint. The result is
  capped at 200 and ordered oldest first; it cannot approve, reject or execute an action. The
  existing random ids, 24-hour Redis TTL, maker/checker separation and one-time execution checks
  remain unchanged. **Risk class:** confidentiality (operator workflow metadata) and bounded Redis
  read load; no new principal, service-to-service edge or money mutation. Rollback: remove the GET
  route and let the admin UI report the account source unavailable; the decision path is unaffected.

- **2026-08-24** — Synthetic-journey taint now propagates over this service's existing internal REST clients through `SyntheticTaintClientFilter` (ADR-0252, #4348). This adds no caller, endpoint, network-policy edge, privilege or control bypass: sanctions, SCA and all other downstream controls still see the journey. It prevents synthetic activity from becoming indistinguishable before a downstream persistence/event boundary; a separate fleet gate requires every new client to choose propagation or a reasoned external boundary.

- **2026-08-20** — Product-catalog product/currency enforcement (#668). This existing reference-data
  edge now authenticates with the `openbank-services` OIDC client and validates a confirmed product's
  currency before account creation. It deliberately preserves ADR-0158's fail-open posture only for
  network/5xx unavailability: a definite 404 or incompatible currency cannot create an account.
  Product-catalog cannot mutate accounts, and account-service never accepts a catalog currency from the
  caller. Tests cover compatible, missing and mismatched currency responses. Rollback: revert the
  validation while retaining the OIDC client filter required by catalog reads.

- **2026-08-17** — **New inbound trust edge: the `lending` namespace.** #3931 added `lending` as
  an allowed ingress peer in this component's `network-policies.yaml`, so `lending-service` can
  now reach this service's `GET /api/v1/accounts/iban/{iban}` from inside the cluster —
  resolving the borrower's own CURRENT account for a loan disbursement (`account.read`, the same
  M2M `openbank-services` client already trusted by ~10 other callers, §2). Read-only; no
  mutation reachable from this edge. Risk class = **information disclosure** at most (an
  IBAN→account/party lookup), bounded the same way as every other `openbank-services` caller —
  the NetworkPolicy decides reach, `@RolesAllowed`/OPA still decide permission. Rollback: drop
  the `namespaceSelector` entry for `lending`. See `docs/threat-models/openbank-lending-service.md`
  §2 items 7-8 for the calling side.
- **2026-08-16** — Account close (TOP-10 #10, part 2): `POST /{accountId}/close` gains a balance
  guard (`AccountNotEmptyException`, mapped to 422) and threads `customerPartyId` +
  `denyIfNotOwner` through the endpoint the same way the goal/nickname endpoints already do, so
  `requestedBy` on `AccountClosedEvent` reflects the real caller instead of always being stamped
  as the operator. **No new trust boundary and no new edge route** — this endpoint stays
  operator/admin-only (`@RolesAllowed(OPERATOR, ADMIN)`); customer-edge deliberately does NOT
  gain a close proxy in this change (see the 2026-08-05 entry below — `account.close` is
  explicitly `prohibited` for the edge's M2M principal after #3734, and reopening that needs its
  own explicit decision, not a side effect of this PR).
  - **S (Spoofing):** unchanged — same OIDC bearer + role gate as before; the new
    `customerPartyId` header path only narrows who a call can act as (via `denyIfNotOwner`), it
    does not widen who can call the endpoint.
  - **T (Tampering):** the new guard is itself the tampering-relevant change — it prevents a
    close from silently discarding a nonzero balance. **Known limitation, not a full fix:**
    the balance read and the close persist are not atomic (no lock spans balance-service and this
    service's own row), so a credit settling in that window still lands on the now-CLOSED
    account; and the guard only sees *currently booked/reserved* balance, not money already in
    flight (a future-dated standing order or a transfer on its documented value-date delay reads
    as zero today and still executes later against a closed account). Closing either gap needs
    cross-service coordination (balance-service locking, or a standing-order-service/sepa-instant
    check) that does not exist yet — flagged as a residual risk in §5, not silently absorbed into
    this entry's risk class.
  - **R (Repudiation):** `AccountClosedEvent.reason` and (now) the real `requestedBy` give a
    better audit trail than before, not a worse one — this is a strict improvement on the
    pre-existing gap where every close was attributed to a generic operator id.
  - **I (Information disclosure):** the 422 error message echoes the account's currency + booked
    amount back to the caller (`"still holds money in CZK 1250.00"`). Only reachable by an
    OPERATOR/ADMIN-role caller today (endpoint is not edge-exposed), so no new exposure to an
    unauthenticated or customer-scoped principal.
  - **D (Denial of service):** one additional synchronous `balancePort.getByAccount` call per
    close attempt — bounded, same trust boundary as the existing balance reads this service
    already makes.
  - **E (Elevation of privilege):** none — `@RolesAllowed`/`@Authorize(action = "account.close")`
    unchanged; `account.close` remains in the `prohibited` set for the edge M2M principal.
  **Risk class = low-to-medium** (prevents a real money-stranding gap, but the fix is best-effort
  — see §5 residual risk). Money-path service (`rules.yaml: money_path_services`) — this entry
  satisfies the threat-model requirement for the 2-approval gate (CLAUDE.md rule 7 / rule #8
  above); PR review confirmed the two limitations above before this entry was written, they are
  not something a later audit had to discover.

- **2026-08-16** — Account rename (TOP-10 #10, part 1): `PATCH /api/v1/accounts/{accountId}/nickname`
  (`nickname` — nullable, VARCHAR(60)). No new trust boundary, no new service, no money movement —
  same shape as the 2026-07-03 savings-goal entry below, which this mirrors field for field.
  - **S (Spoofing):** identical path — the edge authenticates the customer JWT and forwards
    `X-Customer-Party-Id`; account-service's `denyIfNotOwner` re-checks ownership as
    defense-in-depth before accepting operator/service-role requests.
  - **T (Tampering):** nickname is customer-authored free text — bounded to 60 chars (VARCHAR(60)
    + app-side `require`), forwarded through the edge via Jackson re-serialization (not string
    interpolation) so it cannot break out of the JSON body.
  - **R (Repudiation):** no new event/audit trail — cosmetic customer preference, not a money-path
    state transition; the `accounts.updated_at` / optimistic `version` bump is the only trace, same
    as the goal fields.
  - **I (Information disclosure):** returned only on the owning customer's own `GET /accounts/{id}`
    (or operator/admin reads) — no new read endpoint. PII-adjacent (customer-authored text) —
    nulled on GDPR Art. 17 erasure alongside `legalName`/goal fields (`anonymizeByPartyId`
    extended, not a new erasure path).
  - **D (Denial of service):** plain synchronous DB write through the existing `update()` path — no
    new external call, no new failure mode beyond "account not found" (404).
  - **E (Elevation of privilege):** none — reuses `account.update` (service-side) and
    `customer.accounts.nickname.write` (edge-side, under the existing `customer.*` self-service OPA
    rule — no new policy rule needed).
  **Risk class = low** (customer-preference metadata, no money movement, no new trust boundary).
  Flyway `V20__account_nickname.sql`: `nickname VARCHAR(60)`, nullable. Rollback: `ALTER TABLE
  accounts DROP COLUMN nickname` + revert commit.
  Money-path service (account-service is in `rules.yaml: money_path_services`) — this entry
  satisfies the threat-model requirement for the 2-approval gate (rule #8); the change itself moves
  no money and needs no additional compensating control beyond what's documented above.

- **2026-08-09** — **`BalanceServiceClient` now reads and forwards a spendable figure the raw projection did not carry (#1745).** `effectiveAvailableAmount` on the wire is nullable and defaults to `availableAmount` when absent, so this service tolerates talking to an older balance-service during a rollout. **Risk class = none new**: this is a client-side interpretation change on an existing inbound field from an already-trusted M2M dependency (balance-service is inside this service's own trust boundary, not a new edge), no new endpoint, no new listener, no authorization change. The only new datum this service now trusts is a number it already trusted the shape of. Rollback: revert the mapping; the field is additive on balance-service's side and nothing here persists it.


- **2026-08-09** — New inbound caller: `fraud-service` (ADR-0220 D3.5, issue #2749). Its new
  `AccountServiceClient` (fraud-service's first-ever outbound rest-client) calls
  `GET /api/v1/accounts/{accountId}` — an existing read-only, already-`@PermitAll` lookup, so no
  endpoint or authz rule changed — to resolve the `partyId` a repeated-REVIEW fraud-hold applies
  to. **New trust boundary**: fraud-service's namespace now has NetworkPolicy ingress to
  account-service (`openbank-infra/gitops/components/accounts/network-policies.yaml`), and OIDC
  M2M via the shared `openbank-services` client (already trusted by ~10 other callers on this
  same read). Plaintext in-cluster (V9.1 baseline, same as every other caller here) — no TLS
  listener exists to point at instead. account-service's own state, endpoints and mutation authz
  are unchanged; this adds a reader, not a writer.

- **2026-08-05** — Trust-boundary change (#3734): `operator-account-write` now excludes `service-account-*` principals, and a new `prohibited` veto closes `account.{close, freeze, unfreeze, authorize, approval.decide}` to `service-account-openbank-edge`. The role_action_matrix grants ALL ten account.* actions to ROLE_OPERATOR (which the edge service-account carries) and matrix-allows bypasses rule-level exclusions, so both paths needed closing. The edge's verified customer self-service — `account.{create, update}` via `service-edge-account-m2m` (onboarding open-account, pocket add/close, savings goal, ADR-0104/ADR-0153) — is preserved; the shared client keeps `account.read`. Ext moved from generator heredoc to standalone `account_rest_ext.rego` with a 13-test opa suite.

- **2026-08-07** — No trust boundary moved: the `sanctions-service` and `product-catalog`
  rest-client **defaults** in `application.yaml` were changed from `http://openbank-sanctions-service:8123`
  and `http://openbank-product-catalog:8080` to `http://localhost:8123` / `http://localhost:8104`
  (issue #3931). Neither `openbank-` name is a Service in any namespace, and the product-catalog
  port was wrong as well (the Service is `product-catalog:8104` in `accounts`). **These defaults
  were dead in every deployed environment** — the account-service Deployment sets
  `SANCTIONS_SERVICE_URL` and `PRODUCT_CATALOG_SERVICE_URL` (the latter at the KEDA HTTP
  interceptor, with `PRODUCT_CATALOG_API_HOST_OVERRIDE`) — so no deployed request path changes,
  and both edges keep their existing postures (sanctions fails **closed**, product-catalog fails
  **open**, §6 2026-07-09 and 2026-06-06 entries). The change matters for local dev, where the
  screening gate previously failed closed against a name that could never resolve, and it clears
  the last findings blocking `incluster-hostname-resolution` from `mode: advisory` to `enforced`.
  Sibling services on the same edges (`fx`, `kyc`, `sepa-payment`, `billing`, `document-service`)
  already used these localhost defaults; account-service was the outlier.
- **2026-08-03** — Missing required query/header parameter answered 500, not 400 (#3104). A required `@QueryParam`/`@HeaderParam` declared with a non-nullable Kotlin type was fed `null` by JAX-RS when the caller omitted it, and answered **500** rather than 400 (#3104). Kotlin's null-safety is compile-time only, so the declared type only decided where the failure landed: a non-suspend handler threw `Intrinsics.checkNotNullParameter` at the method boundary, and a **suspend** handler got no intrinsic at all, so the null flowed into the body. Six parameters: `Idempotency-Key` on openAccount, `currency` on resolvePocket, and the `partyId`/`role` and `partyId`/`intent` pairs on the two authorization-check endpoints. The authorization pairs are the security-relevant ones: they are the INPUTS to an access decision, so a null reaching the use case is a decision taken on an absent subject rather than a rejected request. No new caller, no new boundary; the endpoints and their `@RolesAllowed`/`@Authorize` gates are unchanged, and every guard runs AFTER authorization. Rollback: revert the commit (restores the 500).
- **2026-08-03** — Propose-only savings withdrawal: the owner's approval now SPENDS an SCA
  challenge (ADR-0232 AC8). **New outbound trust boundary** `account-service → sca-service`
  (`POST /api/v1/sca/challenges/{id}/consume`) — this service can now consume a customer's
  single-use authentication factor, which it previously could not do at all.

  Three defects made the flow impossible and each is a threat in its own right:

  - **Replay (fixed).** The challenge was READ and never consumed, so one approved ceremony
    authorised unlimited proposals. RTS Art. 5 single-use was not met. `consume` is atomic on
    `consumedAt`, so two concurrent approvals cannot both win.
  - **Availability (fixed).** `verifyOwnerSca` pre-checked `status == "COMPLETED"` before consuming
    — the same defect as #3537 in delegation-service, in a second service. Nothing a customer can
    reach promotes a decoupled challenge (customer-edge exposes create/read/decision only;
    `decision` records the signed device decision without promoting), so every owner approval
    failed. Party and purpose are checked here; promotion and approval enforcement belong to
    `consume`, which owns them.
  - **`SAVINGS_WITHDRAW_APPROVAL` was absent from `ScaPurpose`**, a closed enum, so the challenge
    could not be created. Found only by trying to falsify the fix; the suite was green either way.

  Expiry: a 7-day window with a `suspend fun` sweep (`rules.yaml: scheduled_methods` — a plain
  `@Scheduled` method has no Vert.x context and would never run). The decision path reads the
  window rather than the stored status, and expiry is checked BEFORE the SCA leg so a doomed
  decision does not burn the owner's one-shot factor.

  **Residual, stated plainly: no money moves.** Nothing consumes `SavingsWithdrawalApproved`, and
  customer-edge exposes no route for these paths, so approval produces an event and a row. AC8 is
  not done. `consume` is exercised only against a stub — no consumer pact, no provider replay — so
  the outbound edge introduced here is the least verified part of the change.

- **2026-07-09** — Account opening validates against product-catalog (ADR-0158, issue #668).
  New outbound trust boundary: `account-service → product-catalog` (`GET /api/v1/products/{id}`,
  unauthenticated, sync). `openAccount` now rejects a `productId` product-catalog confirms does
  not exist, or that exists but is not `ACTIVE`. **Deliberately fail-open** on product-catalog
  unavailability (timeout/5xx) — a different posture from the adjacent sanctions gate, which
  fails closed: product-catalog is reference data (`rules.yaml: money_path_services` does not
  list it), not a regulatory control, so an outage there must never block account opening.
  New `ProductNotEligibleException` maps to 422 via the existing libs-runtime
  `IllegalStateExceptionMapper`. No DB schema change; rollback = revert the commit.
- **2026-06-09** — Customer-mediated ownership guard on the read endpoints (`getAccount`,
  `getAccountByIban`, `getBalance`): when `X-Customer-Party-Id` is present the account must belong to
  that party (else 404). Defense-in-depth for the edge IDOR fix (security finding A1). No data-flow or
  trust-boundary change; operator/service reads unaffected. Tested by `AccountSecurityContractTest`
  (decision) + `AccountApiIT` (end-to-end header behaviour).
- **2026-05-30** — Added `account_outbox_seq` (Hibernate sequence fix). Additive DDL only:
  no new data flow, external surface or trust boundary. Risk class = **availability** (a missing/
  wrong sequence breaks INSERTs); mitigated by `HibernateSequenceGuardTest`. Rollback: `DROP SEQUENCE`.
- **2026-06-01** — IBAN generation correctness (`IbanGenerator` + new `CzechAccountNumber` in
  openbank-libs). The previous generator emitted a padded `System.nanoTime()` tail under a hard-coded
  `0000` bank code: ISO 13616 mod-97-valid but with a **BBAN that no Czech bank could issue** (no ČNB
  169/2011 Sb. mod-11 on prefix/base) — i.e. a syntactically-valid IBAN naming a nationally
  **non-existent** account. New code composes `bankCode(4) + prefix(6) + base(10)` where the base
  satisfies the national mod-11 weighting, *then* adds the mod-97 check digits — both checks now hold.
  **Risk class = integrity / correctness** (a money-path actor relies on the IBAN resolving to a real
  national account number; a bad BBAN would fail downstream STEP2/CERTIS validation or mis-route a
  credit). No new data flow, endpoint, or trust boundary — the BBAN is generated server-side, never
  caller-supplied, so no new injection surface. Bank code is config (`openbank.account.bank-code`,
  placeholder `2010` in sandbox; production MUST set the real ČNB-assigned code). Mitigated by
  `CzechAccountNumberTest` (known-good/known-bad mod-11 vectors incl. the published `19-2000145399/0800`
  → `CZ65…` vector, and a 2 000-iteration generate-and-revalidate loop) and `IbanGeneratorTest`
  (every generated IBAN re-validated under **both** mod-97 and decomposed mod-11). No DB change;
  rollback = revert the commit (new IBANs only; previously-issued values are unaffected).
- **2026-06-01** — Added `GET /api/v1/accounts/search?q=` (trigram IBAN-fragment search; pg_trgm GIN
  index via migration `V10`). Touches the **I — information disclosure** row: substring search is an
  account-enumeration surface. New endpoint, **no new trust boundary** (same OIDC + RBAC as the existing
  reads). The surface is bounded in the application layer: endpoint is `@RolesAllowed(SERVICE, VIEWER,
  OPERATOR, ADMIN)` — never `@PermitAll`, locked by `AccountSecurityContractTest`; a minimum fragment
  length (≥2 chars after normalization) refuses near-full scans; page size is capped at 50; keyset
  cursor; gateway rate limits apply as for `/iban/{iban}`. User input is escaped for LIKE wildcards
  (custom `ESCAPE '!'`) so a typed `%`/`_` matches literally — no wildcard-injection broadening.
  **Risk class = confidentiality** (over-broad enumeration); the trigram index changes only the access
  path, not who may query. Mitigated by `AccountServiceTest` (normalization, min-length refusal,
  cursor/cap behaviour) + the contract test. Rollback: `DROP INDEX idx_accounts_account_number_trgm`.
- **2026-06-06** — Added sanctions screening gate at `openAccount` (ADR-0032 §C). New synchronous
  trust boundary: account-service → sanctions-service (OIDC M2M, client credentials, in-cluster).
  **STRIDE analysis:**
  - **S (Spoofing):** M2M call uses Keycloak-issued client credential token; the token is short-lived
    (60 s) and verified by sanctions-service. OidcClientRequestReactiveFilter handles refresh.
  - **T (Tampering):** the screening request is idempotent by `idempotencyKey`; the result cannot
    be replayed to open a second account (the key includes `partyId` + `openAccountIdempotencyKey`).
  - **R (Repudiation):** `sanctions_screened_at` + `sanctions_status` are persisted on the `accounts`
    row. The outbox `AccountCreatedEvent` carries the status so downstream can audit independently.
  - **I (Information disclosure):** the party name sent to the screening service is already an
    asset in party-service; sending it to sanctions-service adds a new recipient but not a new
    exposure to untrusted parties (M2M, in-cluster only).
  - **D (Denial of service):** gate **fails closed** — if sanctions-service is unreachable,
    `AccountScreeningUnavailableException` propagates and the account is NOT opened. The caller
    should retry after the availability event. Connect timeout 3 s, read timeout 5 s.
  - **E (Elevation of privilege):** no privilege change; the screening call is synchronous and
    blocking — the handler cannot be bypassed by an async race. A HIT result throws immediately
    before any DB write.
  **Risk class = AML/CFT compliance** (a missed HIT would allow a sanctioned entity to open an account).
  **Residual risk:** if sanctions-service itself returns a wrong CLEAR for a HIT, account-service
  cannot detect it. Mitigated by: sanctions-service has its own threat model; list refresh cadence
  is a compliance decision (outside scope here); manual periodic reconciliation via the admin-UI
  onboarding cockpit (ADR-0068).
  Flyway `V11__account_sanctions_screening.sql`: `sanctions_screened_at TIMESTAMPTZ`, `sanctions_status VARCHAR(20)`.
  Rollback: `ALTER TABLE accounts DROP COLUMN sanctions_screened_at, DROP COLUMN sanctions_status` + revert commit.
- **2026-06-08** — Welcome-bonus auto-grant (`PartyEventConsumer` → `WelcomeBonusPort`/
  `TransactionServiceClient`). On account activation, account-service makes its **first outbound
  service call**: an M2M `client_credentials` request (oidc-client `openbank-services`, ROLE_OPERATOR)
  to transaction-service `POST /api/v1/transactions` initiating a 100k CZK incoming credit. **New trust
  boundary** (service↔transaction-service, see §2 DFD) and a new STRIDE row (Spoofing/EoP via the M2M
  secret, see §4). **Risk class = integrity / elevation-of-privilege** (the secret can mint operator
  tokens on the money path). Mitigations: secret only in K8s Secret/Vault (never image/git), rotatable;
  the grant is **flag-gated `openbank.welcome-bonus.enabled` default-OFF and sandbox-only** (it conjures
  money from the bank clearing account — must never run in prod); idempotency-keyed on the account id so
  re-delivery cannot double-pay; grant is best-effort so a failure never blocks activation. Residual
  risk: transaction-service authorizes on role alone, not caller identity (accepted in sandbox; a
  per-caller allowlist / mTLS-SPIFFE identity is the production follow-up). Mitigated by
  `PartyEventConsumerTest` (enabled/disabled/grant-failure isolation). Rollback: flip the flag OFF (or
  revert the commit); no DB or schema change. Money-path PR — see PR #555 / #554.
- **2026-06-29** — Removed non-atomic pocket exchange (`POST /api/v1/accounts/{id}/pockets/{from}/exchange`,
  ADR-0110 §3). The endpoint had no live callers (the canonical path is the edge service using a
  single cross-currency TRANSFER through transaction-service, ADR-0110 §1). Removing it eliminates:
  the account-service → fx-service trust boundary, the dangling-debit failure mode (DEBIT without
  matching CREDIT on partial failure), and the dual-settlement complexity. The trust boundary entry
  above is removed from the DFD. No DB change; no Flyway migration needed.
- **2026-07-03** — Optional savings goal on `Account` (ADR-0153): `PUT`/`DELETE /api/v1/accounts/
  {accountId}/goal` (goal_name, goal_target_minor_units, goal_target_date — all nullable). No new
  trust boundary, no new service, no money movement.
  - **S (Spoofing):** same customer-mediated path as pocket add/close — the edge authenticates the
    customer JWT and forwards `X-Customer-Party-Id`; account-service's `denyIfNotOwner` re-checks
    ownership as defense-in-depth (the header is advisory per the edge's own comment) before
    accepting operator/service-role requests.
  - **T (Tampering):** goal name is customer-authored free text — bounded to 120 chars (VARCHAR(120)
    + app-side `require`), forwarded through the edge via Jackson re-serialization (not string
    interpolation) so it cannot break out of the JSON body. `targetMinorUnits` validated positive
    server-side (both edge and account-service) before persisting.
  - **R (Repudiation):** no new event/audit trail — a goal is customer preference metadata, not a
    money-path state transition (ADR-0153 "Neutral" consequence); the `accounts.updated_at` /
    optimistic `version` bump on the row is the only trace, same as any other field-level update
    via the existing generic `AccountRepository.update()`.
  - **I (Information disclosure):** goal name/target are returned only on the owning customer's own
    `GET /accounts/{id}` (or operator/admin reads) — no new read endpoint, no new exposure surface.
    PII-adjacent (customer-authored text) — nulled on GDPR Art. 17 erasure alongside `legalName`
    (`anonymizeByPartyId` extended, not a new erasure path).
  - **D (Denial of service):** plain synchronous DB write through the existing `update()` path — no
    new external call, no new failure mode beyond "account not found" (404) already handled by
    `requireAccount`.
  - **E (Elevation of privilege):** none — reuses `account.update` action already scoped to
    `ROLE_OPERATOR`/`ROLE_ADMIN` (service-side) and `customer.accounts.goal.write` (edge-side, under
    the existing `customer.*` self-service OPA rule — no new policy rule needed).
  **Risk class = low** (customer-preference metadata, no money movement, no new trust boundary).
  Flyway `V13__savings_goal.sql`: `goal_name VARCHAR(120)`, `goal_target_minor_units BIGINT`,
  `goal_target_date DATE` — all nullable. Rollback: `ALTER TABLE accounts DROP COLUMN goal_name,
  DROP COLUMN goal_target_minor_units, DROP COLUMN goal_target_date` + revert commit.
  Money-path service (account-service is in `rules.yaml: money_path_services`) — this entry
  satisfies the threat-model requirement for the 2-approval gate (rule #8); the change itself moves
  no money and needs no additional compensating control beyond what's documented above.
- **2026-07-08** — Wired the four-eyes (maker-checker) enforcement *mechanism* (ADR-0155) onto
  `account.freeze`, mirroring the sepa-payment pilot (issue #413). New `ApprovalConfig`
  (`RedisApprovalStore` producer) and `PATCH /api/v1/accounts/approvals/{id}` checker-decide
  endpoint (`@RolesAllowed(Roles.OPERATOR, Roles.ADMIN)`, `@Authorize(action =
  "account.approval.decide")`); two new exception mappers (`SelfApprovalNotAllowedMapper` → 403,
  `InvalidApprovalStateMapper` → 409). STRIDE supplement added in §4a above. **`authz.four-eyes.
  enforce` stays `false`** — no behavior change to any existing request; this PR only wires the
  mechanism. Rollback: revert the commit (no DB/schema change; `ApprovalStore` records live in
  Redis with a TTL).
- **2026-07-10** — Added `GET /api/v1/accounts/active` (fleet-wide ACTIVE-account sweep,
  cursor-paginated; ADR-0143: the "list every billable account" read billing-service's cycle
  scheduler discovers its batch from, issue #548). Touches the **I — information disclosure**
  row: like `/search`, this is an account-enumeration surface — and a deliberately *broader* one
  (no fragment required). Bounded the same way: `@RolesAllowed(SERVICE, VIEWER, OPERATOR,
  ADMIN)` — never `@PermitAll`, asserted end-to-end by `ActiveAccountsApiIT` (401/403/200);
  page size capped at 200 (`MAX_ACTIVE_LIST_LIMIT`) so one request cannot dump the whole book;
  keyset cursor (stable under concurrent open/close); returns only ACTIVE rows, so a billing
  sweep can never pick up a CLOSED/FROZEN account. No query input beyond limit/cursor — no
  injection surface (status is a server-side constant, cursor decodes to a UUID or 500s before
  any query). **Risk class = confidentiality** (fleet-wide enumeration by an over-privileged or
  compromised staff/service token). Residual: any caller with an allowed role sees the whole
  active book — acceptable because the roles are staff/M2M only and the same information is
  already reachable via repeated `/search` paging; gateway rate limits apply. No DB change, no
  new index (status + PK keyset uses the existing primary key). Rollback: revert the commit.
- **2026-07-17** — Fixed `AccountAuthorizationRepositoryImpl`: every method ran without a reactive
  session/transaction, and `save` mapped the domain to a fresh entity and `persist`ed it. Against a
  real DB the whole authorization feature was non-functional — grant returned 422 (`No current
  Mutiny.Session found`) and revoke/suspend/reinstate would have hit `account_authorizations_pkey`
  on the application-assigned `@Id` (INSERT scheduled for an existing row). Masked because
  `AuthorizationServiceTest` mocks the repository and no IT exercised the flow. Reads now wrap
  `Panache.withSession`; `save` wraps `Panache.withTransaction` + `session.merge` (upsert). This is
  an **integrity/availability** fix — access-grant/revocation on an account is a security control
  (an un-revocable authorization is a real EoP risk); it must actually persist. No API/DB change.
  Verified by `AccountAuthorizationLifecycleIT` (real Postgres: grant→revoke→REVOKED; fails-first on
  the old code with 422). Same `persist`-vs-`merge` class as consent-service #1553; tracked in #1600.
  Rollback: revert the commit.
- **2026-08-01** — Delegation-grant enforcement projection (ADR-0232 D3, issue #2990):
  `account_delegation_projection` fed by `DelegationEventConsumer` from
  `openbank.delegation.events`, and `AuthorizationService.isAuthorized` gains a third disjunct —
  owner OR legacy `AccountAuthorization` OR an ACTIVE in-window delegation grant. **Risk class =
  elevation of privilege / confused deputy.** Key properties: **a grant only counts when the party
  who ISSUED it owns the account** — the projection carries `grantor_party_id` and the guard
  compares it to `account.partyId` on every call. Without that the disjunct made a projection row
  authority in itself: matching on (accountId, granteePartyId) alone meant a grant naming somebody
  else's account was enforced against that account, so two colluding parties could mint payment
  rights over a stranger's money using nothing but their own valid SCA. delegation-service also
  verifies ownership at offer time; this is the half that re-evaluates per request rather than
  trusting a verdict reached once, and it is the last check before the money path. Further:
  enforcement is local-only (no synchronous call to delegation-service on the request path —
  guarded by `NoDelegationRestClientTest`); the guard is additive (delegation can only ADD access,
  never remove the owner's); per-transaction ceilings and currency match are enforced for
  PAYMENT_ONLY; a missed close event would leave access open, so consumer failures dead-letter
  instead of being swallowed (the worst drift direction is a REVOKED grant staying enforceable —
  the DLQ preserves the close for replay); projection rows key on the grant id, so redelivered
  activates are idempotent. Residual: seconds-level revoke propagation documented in ADR-0232;
  card/savings/propose-only scopes land in their owning services' slices. Rollback: revert the
  commits; the projection table is droppable without touching `account_authorizations`.
- **2026-08-01 (savings slice)** — SAVINGS_GOAL grants join the delegation projection
  (issue #2990): the projection gains `resource_type` (V17), the consumer projects
  ACCOUNT and SAVINGS_GOAL events into typed rows, and `SavingsGoalDelegationGuard`
  answers DEPOSIT / WITHDRAW / PROPOSE_WITHDRAW as owner OR an ACTIVE in-window grant
  via `GET /api/v1/accounts/{id}/savings-goal/delegation/check` (reuses `account.read`
  OPA action). **Risk class = elevation of privilege across resource types**: a savings
  grant must never satisfy an account question — enforced by resource-type filtering in
  every guard query and proven by the IT (savings grant → account READ_ONLY stays
  denied). Savings goals are account metadata (ADR-0153), so SAVINGS_GOAL grants key on
  the owning account id by convention — which means a grant naming a stranger's account
  reaches this guard exactly as it reached the account guard, and `SAVINGS_WITHDRAW`
  moves money. The same issuer-must-own-the-account check therefore applies here:
  `grant.grantorPartyId == account.partyId`, evaluated per request.
  PROPOSE_WITHDRAW answers the maker-half of the propose-only flow only; the
  approval-inbox execution half is the AC8 follow-up.
  Rollback: revert; V17 is a droppable column.
- **2026-08-01 (propose-only slice)** — AC8 propose-only withdrawal flow (ADR-0232 D8,
  issue #2990): a delegate holding only SAVINGS_PROPOSE_WITHDRAW creates a
  `WithdrawalProposal` (V18) paired with an ADR-0155 `PendingApproval`; the owner's
  SCA-bound decision (purpose `SAVINGS_WITHDRAW_APPROVAL`, party-bound to the owner)
  is the ONLY path to APPROVED, which emits `SavingsWithdrawalApproved` as the
  executable instruction for the payments path. **Risk class = social-engineering /
  maker-checker bypass.** Structural properties: the delegate can never decide (owner
  check at the service + store-enforced segregation of duties), never executes (no
  execution endpoint exists for delegates at all — approval IS the execution
  trigger), approval and instruction share one outbox transaction (no
  approved-but-uninstructed state), SCA is purpose- and party-bound (a stolen or
  cross-purpose challenge fails). Residual: the Redis PendingApproval TTL (24h) is
  the proposal's effective expiry — a decided-late approval still fails at the store
  (PENDING-only decide). Rollback: revert; V18 is a droppable table.

- **2026-08-02** — **New inbound trust edge: the `delegation` namespace.** `#3414` added
  `delegation` as an allowed ingress peer in this component's `network-policies.yaml`, so
  `delegation-service` can now reach this service's API from inside the cluster. A NetworkPolicy is
  coarse — it decides *reach*, not *permission* — so the actual authorization is unchanged and still
  rests on OIDC (`@RolesAllowed`) plus the OPA sidecar (ADR-0034); this edge widens who may attempt a
  call, not who may succeed. Risk class = **elevation of privilege** if a policy gap exists on an
  endpoint that previously had no in-cluster caller: network reach was an implicit second control for
  such endpoints and is now gone for this peer. Per ADR-0232 delegation-service holds
  `DelegationGrant` and enforcement stays with the product services, which build their own local
  projection — so a compromised or buggy delegation-service should not be able to grant access it
  never had, and that property is the mitigation this edge depends on. Rollback: drop the
  `namespaceSelector` entry for `delegation`. Recorded here because #3431's measurement showed this
  change landed with no threat-model update.

- **2026-08-03** — **The delegation projection reaches the money path** (ADR-0232 D3/D5, issue
  #2990 AC9). `AuthorizationService.authorizeDelegatedPayment` and
  `GET /api/v1/accounts/{accountId}/delegation/payment-authorization` (Authorize action
  `account.read`, edge-proxy role set — same gate as the savings-goal `/check`) answer whether a
  NON-OWNER may debit an account, and customer-edge's domestic-payment route now calls it. Until
  now `isAuthorizedForAmount` had **zero callers**: the grant, the events and the projection were
  live while a delegate could not actually pay, so this is the change that turns a stored grant
  into money movement. Risk class = **elevation of privilege**. Structural properties relied on:
  (a) the decision stays HERE, because this is the only service holding both the projection and the
  account's true owner — a caller-side copy would be a second rule free to drift, with the
  money-path copy the stale one; (b) the `issuedBy(ownerPartyId)` gate is re-evaluated on every
  request, so a revoked or non-owner-issued grant cannot authorise a debit even if it once did;
  (c) the response carries `delegationId`/`grantorPartyId` **only** on an authorising DELEGATED
  outcome, so a refusal does not disclose that a grant exists; (d) refusals are classified
  (NO_GRANT / LIMIT_EXCEEDED / ACCOUNT_NOT_FOUND) for the audit trail, and the edge is required to
  collapse all of them to one opaque 403 — if a future caller surfaces the outcome verbatim this
  becomes an enumeration oracle for other parties' accounts and sharing arrangements, which is the
  most likely way to regress this design. Deliberate narrowing vs `isAuthorizedForAmount`: the
  legacy `account_authorizations.transaction_limit` **is** enforced on this path, because wiring
  the old behaviour to a live debit route would have made an operator-set per-transaction ceiling
  decoration. Rollback: revert the edge call site — the endpoint alone moves no money.
- **2026-08-06** — **Error-envelope disclosure: `ApiError.timestamp` now carries a real
  clock reading.** `#3874` — the shared `ApiError` envelope (openbank-libs-domain) defaulted
  `timestamp` to `Instant.EPOCH` and no call site passed it, so every error this service served
  carried `1970-01-01T00:00:00Z`. The field is now a required constructor argument, stamped
  `Instant.now()` at construction in this service's mappers. **Risk class = information
  disclosure**, and it is a deliberate, bounded increase: error responses now reveal the server's
  wall-clock time to any caller who can provoke an error, including an unauthenticated one on
  endpoints that answer 401/403 through this envelope. Assessed as acceptable — the value is
  second-resolution UTC already implied by the HTTP `Date` header on the same response, so it
  discloses nothing a caller could not already read, and it is what makes the envelope's own
  instruction ("contact support with traceId=…") actionable by letting support bind a trace to a
  moment. No new field, no new endpoint, no authorization or ingress change; the response SHAPE is
  unchanged (`string`/`date-time`), so no API-contract bump under ADR-0048. Not a timing oracle:
  the stamp is taken when the error object is built, not measured against request start, so it
  does not expose per-request processing duration. Rollback: revert; the field is
  serialisation-only and nothing persists it.
## Delegation lifecycle ordering

The local enforcement projection accepts authority-opening events only with a positive,
monotonically increasing `lifecycleRevision`. A revisionless close remains accepted and installs a
permanent legacy tombstone; a revisionless activate/reinstate is ignored. This deliberately favors
temporary unavailability during a consumer-first rolling upgrade over resurrecting revoked access.
Recovery from a legacy tombstone is a newly issued grant, never replaying the same grant id.
