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
`pension-fund.holding.read`, `pension-fund.order.place` and the reference reads `fund.read`,
`strategy.read`, `nav.read` — never NAV calculation/approval, fund or strategy administration.
Every staff write excludes `service-account-*` principals, and `holding.read` is excluded from
base `operator-read-any`, so the shared M2M account (`ROLE_OPERATOR`) is denied. Policy tests:
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
- The fund-side ingress NetworkPolicy (`pension-fund/network-policies.yaml`) admits the `pension`
  namespace since the integration slice (S8) wired the REST adapter (`PensionFundRestAdapter`, §4c);
  the rule is generated from pension-service's rest-client config by `gen-network-policies.py`.
- No mTLS client identity between the two services; caller identity rests on the OIDC client
  credential alone.

## 4b. Exits — termination, payout, death (slice S5)

New trust boundaries: the fund unit register (redeem), the payment rail (payout), the tax authority
(withholding remittance), the incentive ledger (clawback), an annuity insurer, sca-service and
account-service. All are behind ports; in S5 they are `@DefaultBean` stubs.

| STRIDE | Threat | Mitigation |
|---|---|---|
| **Spoofing** | A caller names another participant's party to cash out their contract | `ContractAccessGuard`: the party header is trusted only from the edge relay; another party's contract is 404 |
| **Tampering** | Amounts changed between the preview and the payout | The quote is stored and signed: SCA dynamic linking over `quoteHash`; execution pays only stored quote amounts (IT asserts preview == executed) |
| **Repudiation** | Participant denies requesting the termination | SCA challenge id, signing time and quote hash are stored on the notice |
| **Information disclosure** | Enumerating notices / payouts / claims | Every exit aggregate is looked up under its contract after the ownership check; mismatch is 404 |
| **Denial of service** | A retried activity or replayed request pays twice | Deterministic idempotency keys per step, `pension_payment_instructions.idempotency_key` UNIQUE, workflow id = aggregate id, `Idempotency-Key` on every money-moving command |
| **Elevation of privilege** | One operator registers a death and pays the claimants alone | Death routes: staff roles + `operator-pension-death-claim` (no service-account, no edge); approval is four-eyes in `DeathClaim.approve` |

Residual: the SCA, own-account and beneficiary-KYC stubs FAIL CLOSED outside %dev/%test, so a
deployment refuses every exit until sca-service / account-service adapters are wired. A binding
quote moves NAV risk to the provider for the notice period (`navVariance` is recorded, never
charged to the participant). A death during a phased withdrawal stops the remaining installments;
handing the unpaid remainder to the claim is a follow-up.

## 4c. Integration (slice S8)

S8 joins S1/S2/S3/S5 into one service and adds the routes customer-edge needs. New trust boundary:
pension-fund-service (the unit register), reached over REST as pension-service's OWN Keycloak
client `openbank-pension` (client_credentials, ROLE_API only); pension-fund-service admits holdings
and orders for that identity alone, never the shared `openbank-services` account.

| STRIDE | Threat | Mitigation |
|---|---|---|
| **Elevation of privilege** | A payment quoting a contract's reference activates a contract whose onboarding was never signed (no KID, no SCA, no cooling-off) — S3's default activation adapter applied S1's `activate` directly | Activation is the onboarding workflow's alone: a contribution only SIGNALS a SIGNED new-contract application; a pending contract with no such application parks the money (`CONTRACT_NOT_ACCEPTING`), also for operator assignment and employer lines. S1's participant `/activate` and caller-valued `/early-termination` routes are retired |
| **Tampering** | The payout account is swapped after the participant approved the amount | The SCA challenge signs the quote AND the destination (`signingHash(iban)`) for termination and payout; execution pays only the stored, signed account |
| **Tampering** | A payout-account change redirects money (account takeover) | SCA-bound to this payout and IBAN; own verified account only; never on an unsigned or single-payment payout; HELD 3 days and applied only to installments due after that; the participant is notified at once on the known channel (fails closed if the notice cannot be sent); a second change while one is pending is refused |
| **Tampering** | Check-then-act races on money-moving state (confirm vs account change, two confirms, activity vs operator) lose an update | Optimistic locking on every exit aggregate and on the contract row (`row_version`, checked on save and by Hibernate `@Version` at flush): the loser gets 409, an activity retries on a fresh read. Two parallel confirmations: exactly one wins (IT) |
| **Denial of service / repudiation** | A retried POST runs twice (second application, second order) | `Idempotency-Key` required on every POST; the first 2xx response is stored per (principal, participant, method, path, key) and replayed |
| **Information disclosure** | The simulation is mistaken for advice | Always `illustrative: true` with a disclaimer; assumed returns are configuration; nothing about the caller is read or stored |
| **Spoofing** | The fund register is called with a borrowed identity | Own client, own Vault entry (`keycloak/pension-service`), env ref `optional: false`; in-memory register exists only in dev/test builds |

Residual: two concurrent FIRST attempts with one idempotency key can both run (the store is written
after the response) — every money step beneath is idempotent on its own key/unique index. The stub
SCA does not compare the signed hash; the binding is enforced by sca-service's consume once the
real adapter replaces the stub (fails closed until then). The notification port is a stub that
refuses outside dev/test. Orders are forward-priced: a redemption returns the amount ordered, the
proceeds settle at the next NAV.

## 4d. Annuity partners (#12383)

The ANNUITY payout form buys a policy from a partner insurer chosen by the participant among
offers from every eligible partner. New trust boundary: external insurers, reached through the
`AnnuityProviderAdapter` SPI — the `reference-rest` adapter speaks the published reference
protocol to an operator-registered HTTPS endpoint with a per-partner credential from
configuration; `SimulatorAnnuityAdapter` exists only in dev/test builds.

| STRIDE | Threat | Mitigation |
|---|---|---|
| **Elevation of privilege** | One operator registers or re-points a partner (new premium IBAN, new endpoint) and activates it alone | Four-eyes in the `AnnuityProvider` aggregate: the approver must differ from the terms editor and the activation requester (403 otherwise); any amendment returns the partner to DRAFT; only real human staff reach the registry routes (OPA `operator-pension-annuity-manage`, `ContractAccessGuard.staffActor`) |
| **Tampering** | The participant approves one offer and another insurer, offer or amount is bought | The selection SCA challenge signs `selectionHash` (payout, contract, partner, offer id, premium, monthly amount, type); the payout confirmation requires that binding selection, unexpired and for the same net premium (`requireBindingSelection`) |
| **Tampering** | A partner answers with an offer for another premium, currency or type | Normalisation is enforced in `AnnuityMarketplaceService.offerFits`, not trusted from the adapter; such offers are dropped and listed as partner failures |
| **Repudiation / tampering** | A policy is recorded for a premium that never left, or money is lost when a partner refuses | Application first; the premium leaves once (`PaymentInstructionRepository`, key `pension-annuity-<payout>-premium`); a definitive payment rejection cancels the application; `markActive` is refused unless the premium was sent; a refusal after payment waits for the partner's `refundRef`, then the pack decides (contract re-subscription or the signed client account) |
| **Spoofing / SSRF** | An operator-supplied endpoint targets an internal address or leaks credentials | HTTPS only outside dev/test, no user-info/query/fragment, redirects not followed; the credential is configuration keyed by partner id, never stored in the registry; no credential → no call |
| **Denial of service** | A slow partner blocks the quote round | Partners are asked in parallel, each bounded by `openbank.pension.annuity.quote-timeout`; partial results are returned |
| **Information disclosure** | A partner receives more personal data than needed to quote | The quote carries premium, date(s) of birth, jurisdiction and dates; the application a pseudonymous holder reference (party id) — identity data is exchanged under the partner agreement |

Residual: the partner licence and legal-entity reference are recorded, not verified against the
supervisor's register or party-service (operator check at activation). A partner's
pre-contractual documents are not yet attached to offers (docs/compliance/pension-annuity-distribution.md,
legal review pending). Withholding tax already remitted on a premium returned to the contract is
not reversed automatically.

## 5. Residual risks / assumptions

- **(Resolved in S8)** S1's caller-valued surrender preview is retired; termination is S5's quote
  over the unit register and the incentive ledger.
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
- 2026-10-09 — S5 exits: termination, payout, death claims (§4b).
- 2026-10-09 — S8 integration: fund REST client identity, onboarding-only activation, account-bound SCA, held account change, optimistic locking, POST replay (§4c).
- 2026-10-09 — annuity partner integration: registry with four-eyes activation, adapter SPI, SCA-bound selection, premium/compensation flow (§4d, #12383).

## Creation and strategy decision controls

- Draft and onboarding creation require the configured provider legal-entity UUID before
  application work. A draft idempotency replay must also match that provider. Production requires
  explicit configuration. Development, tests and sandbox use a synthetic fixture identity without
  requiring production ownership evidence. This invariant does not establish tenant authorization
  or ownership of existing data. Full production isolation and its launch evidence are tracked
  in #12472.
- Fund contribution routing selects a strategy effective at the date supplied by the injected
  clock; no effective strategy refuses placement. Domain and actual-adapter tests cover the
  effective-date boundary. Durable decision snapshots across retries remain tracked in #12474.
- Before real-money production launch, reconcile the configured provider with existing data
  and prove identity, database and workflow isolation (#12472). A configuration edit does not
  relabel existing data; synthetic development fixtures are not production ownership evidence.
