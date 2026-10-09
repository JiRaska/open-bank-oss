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

- 2026-10-09 — initial model with the S1 bootstrap (ADR-0334, #12350).
