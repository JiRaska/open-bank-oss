# Threat model — openbank-pension-service

## 1. Scope & purpose

Participant side of the pension platform (ADR-0334): the `PensionContract` aggregate, its
lifecycle (`DRAFT → PENDING_ACTIVATION → ACTIVE ⇄ SUSPENDED → TERMINATING → PAID_OUT |
TRANSFERRED_OUT | CLOSED`), strategy elections, contribution schedule, beneficiaries, and the
evaluation of jurisdiction packs (state incentives, tax relief, payout and clawback rules).

Slice S1 moves **no money**: there is no contribution collection, no unit register, no payout and
no outbound call. The service is listed in `money_path_services` from the bootstrap because the
next slices add exactly those, and the review and threat-model rules must already hold when they
land. This model covers S1 and names what the money-moving slices must add.

## 2. Data flow (DFD)

```
participant ──(OIDC)──▶ customer-edge ──(bearer, X-Customer-Party-Id)──▶ pension-service ──▶ pension-db (CNPG)
                                                                   │
                                                                   └── OPA sidecar (localhost:8181)
jurisdiction-packs/*.json (classpath, reviewed in PR) ──load at startup──▶ JurisdictionPackRegistry
```

Trust boundaries: edge → service (in-cluster, NetworkPolicy admits customer-edge, admin-ui and the
platform scrapers only); service → database (single-owner CNPG cluster); repository → image (the
packs are code-reviewed data baked into the image, not runtime input).

## 3. Authn/Authz

- Every route is `@RolesAllowed(API, OPERATOR, ADMIN)` behind Keycloak OIDC; there is no
  anonymous surface.
- `pension_rest_ext.rego` grants the participant actions (`create`, `submit`, `activate`,
  `strategy`, `suspend`, `resume`, `terminate`, `read`) to `service-account-openbank-edge` only,
  keyed on `principal.id`, and grants real staff (`HUMAN`, not `service-account-*`) **read only**.
  No action is placed in `rules.yaml: authz.role_action_matrix`, because a matrix line is a grant
  to every service-account holding the role and cannot be vetoed (#3765/#3734).
- `AUTHZ_ENFORCE=true` from the first rollout. Policy tests carry a must-deny for every allow.

## 4. STRIDE

| Threat | Vector | Mitigation in S1 |
|---|---|---|
| **Spoofing** | A caller impersonates the participant to open or terminate a contract | Writes reachable only through the edge principal, which authenticates the human and stamps `X-Customer-Party-Id`; an absent header is a 400 (nullable param + `requireNotNull`, #3104) |
| **Spoofing / IDOR** | A customer reads or acts on another participant's contract by id | Every route resolves the caller: a caller without a staff role must carry `X-Customer-Party-Id` and is confined to that party's contracts; someone else's contract answers **404**, identical to an unknown id, so ids cannot be enumerated. Staff may read without the header; every change requires a participant caller. Covered by `PensionContractApiIT` (stranger → 404 on read and every action, owner → 200, operator → 200 read / 400 write), falsified by removing the check |
| **Tampering** | Retried requests double-create or double-apply | `Idempotency-Key` is required on every POST; create deduplicates per participant (unique index), lifecycle actions are idempotent by state |
| **Tampering** | Oversized or pathological amounts | Every caller-supplied amount bounded (≤ 1e9, ≤ 4 decimals); list/map inputs capped; codes restricted to `[A-Za-z0-9_-]{1,64}` |
| **Tampering** | Request sets lifecycle state, pack version or a backdated strategy | Status is never a request field; transitions go through `ContractStatus.canMoveTo` and an illegal edge is 409; the pack version is resolved server-side by date and pinned; strategy changes cannot take effect in the past; DB `CHECK` constraints mirror the status vocabulary and the start-date invariant |
| **Tampering** | Pack data edited to inflate incentives | Packs are repository data reviewed in PR, loaded through a strict mapper (unknown key = boot failure), validated per rule type; every pack carries a legal-review status |
| **Repudiation** | Participant disputes a strategy change | Strategy elections are append-only rows with `elected_at`; nothing overwrites a previous election. Signed client intent (SCA) arrives with the onboarding workflow slice |
| **Information disclosure** | Contract and beneficiary data leak | Confidential classification; no ingress; staff read only via the narrow rule; service accounts other than the edge excluded; beneficiaries stored only in this service's database |
| **Denial of service** | Request floods or oversized bodies | Fleet rate limit (100 concurrent), 1 MB body limit, read/idle timeouts from `application.yaml` |
| **Elevation of privilege** | A backend service-account holding `ROLE_OPERATOR` writes a contract | `operator-pension-read` excludes `service-account-*` and is read-only; the edge grant is keyed on one principal id |

## 4a. Trust boundary — pension-service → pension-fund-service (fund-administration port)

pension-fund-service (ADR-0334) owns the unit register: holdings, unit orders and fund prices.
pension-service reaches it through its `FundAdministrationPort` as the Keycloak client
`openbank-pension` (`service-account-openbank-pension`, `ROLE_API`). The server side of this
edge is declared in this PR; the client adapter in pension-service lands with the integration
slice, and this section must be re-read when it does.

**Assets:** a contract's unit holdings and order history (confidential participant data), and the
unit orders themselves — an order converts contributed money into units, so a wrong or duplicated
order mis-states a participant's savings.

**Authorization on the fund side:** `pension_fund_rest_ext.rego` (reason
`service-pension-unit-register`) admits that one principal id, and only with `ROLE_API`, to
`pension-fund.holding.inspect`, `pension-fund.order.place` and the reference reads `fund.read`,
`strategy.read`, `nav.read` — never NAV calculation/approval, fund or strategy administration.
Every staff write excludes `service-account-*` principals, and the holdings action uses a non-read
verb so neither base `operator-read-any` nor `compliance-read-any` admits it: the shared M2M
account is denied whether it holds `ROLE_OPERATOR`, `ROLE_COMPLIANCE` or `ROLE_ADMIN`. Policy tests:
`pension_fund_rest_ext_test.rego` (`test_pension_service_places_orders_but_cannot_administer`).

| Threat | Vector | Mitigation |
|---|---|---|
| **Spoofing** | Another workload calls the fund API as pension-service | Grant keyed on the exact `principal.id` plus `ROLE_API`; the Keycloak client secret is held only by pension-service; every other service-account, including the shared one, is denied (rego + tests above) |
| **Spoofing / IDOR** | A request carries a `contractId` the participant does not own | The fund side cannot judge ownership — it trusts pension-service for it. pension-service must only send a `contractId` it has resolved for the authenticated participant (the 404-confinement in §4, `PensionContractApiIT`); the port must never forward a client-supplied id unchecked |
| **Tampering / replay** | A retried or replayed order is executed twice | `Idempotency-Key` is required on `POST /api/v1/contracts/{contractId}/orders` (`ContractUnitResource`); `uq_unit_orders_idempotency UNIQUE (contract_id, idempotency_key)` (`V1__init_pension_fund.sql`); reusing a key for a different instruction is rejected (`UnitRegisterService`). The adapter must derive the key deterministically from the pension-side operation, not mint one per attempt |
| **Tampering** | Order amount or fund altered or out of range | Amounts normalised to money precision server-side (`Precision::money`); `fundId`/`type` required; prices come from the fund side's own published NAV, never from the caller |
| **Information disclosure** | Holdings read beyond the participant's contract | Same ownership dependency as IDOR above; holdings excluded from `operator-read-any` |
| **Denial of service** | pension-service floods the fund API, or a slow fund API stalls pension-service | Fund side: 1 MB body limit and 100-concurrent rate limit (`application.yaml`). Client side: the adapter must carry timeouts and must not retry an order without the same idempotency key |
| **Elevation of privilege** | The pension client is used to approve a NAV or administer a fund | Those actions are outside the reason's action set and are staff-only with `service-account-*` excluded |

**Residual risks:**
- Contract ownership is enforced only in pension-service; a defect there is not caught by the fund side.
- The fund-side ingress NetworkPolicy (`pension-fund/network-policies.yaml`) does not yet admit the
  `pension` namespace, so the edge is authorised but not network-reachable until the adapter PR
  adds that rule — that change must update this model.
- No mTLS client identity between the two services; caller identity rests on the OIDC client
  credential alone.

## 5. Residual risks / assumptions

- **Surrender preview inputs are caller-supplied.** Until pension-fund-service owns the unit
  register, the current value and incentive history in an early-termination request come from the
  caller; the preview is arithmetic over the pinned pack and moves nothing. Confirming moves the
  contract to `TERMINATING` only — no payout exists in S1.
- **Pack activation is a deploy, not a four-eyes runtime act** in S1. ADR-0212 D4's maker-checker
  activation is a follow-up; until then the pull-request review is the second pair of eyes.
- **Concurrent creates with one key.** Two simultaneous first attempts with the same key race to
  the unique index; the loser fails at insert rather than returning the winner's contract.
- **Pack values pending legal review.** The CZ/DPS and CZ/DIP packs are reference data.
- **Money-moving slices must extend this model** before they merge: contribution collection
  (SDD/standing order), incentive claims to a state agency, payouts, transfers, and the
  fund-administration port each add a trust boundary.

## 6. Change log

- 2026-10-10 — §4a: pension → pension-fund trust boundary (fund-administration port, #12355).
- 2026-10-09 — initial model with the S1 bootstrap (ADR-0334, #12350).
