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
- `pension_rest_ext.rego` grants the participant actions (`strategy`, `suspend`, `resume`,
  `inspect`, the onboarding steps, `transfer.request-out`/`.consent`/`.withdraw`) to
  `service-account-openbank-edge` only. The S1 draft routes (`create`, `submit`) and the S1
  `activate`/`terminate` shortcuts are retired: no route serves them and no rule grants them,
  keyed on `principal.id`, and grants real staff (`HUMAN`, not `service-account-*`) **read only**.
  No action is placed in `rules.yaml: authz.role_action_matrix`, because a matrix line is a grant
  to every service-account holding the role and cannot be vetoed (#3765/#3734).
- A transfer-out is withdrawable by its participant only before valuation (`AWAITING_CONSENT`
  or `REQUESTED`): SCA-signed over `pension-transfer-withdraw:<id>`, another party's transfer
  answers 404, and a concurrent valuation is ordered by the optimistic version, so a withdrawal
  can never land after units were sold. A receiving provider or staff cannot withdraw for them.
- `AUTHZ_ENFORCE=true` from the first rollout. Policy tests carry a must-deny for every allow.
- **No pension action ends in `.read` or `.list`.** Base `rest.rego` grants every such action to
  any HUMAN-classified principal holding `ROLE_OPERATOR`/`ROLE_ADMIN` (`operator-read-any`) or
  `ROLE_COMPLIANCE` (`compliance-read-any`), and the shared M2M account
  `service-account-openbank-services` is classified HUMAN and holds those roles in at least one
  realm. A per-service rule cannot veto a base allow, so the reads are named `*.inspect`
  (`pension.contract.inspect`, `.exit.inspect`, `.funding.inspect`, `.death.inspect`,
  `.onboarding.inspect`, `.transfer.inspect`, `.operator.inspect`) and reach only the edge and real
  staff through the narrow rules. Measured with `opa eval` on the bundle: before the rename the
  shared account was allowed all seven reads, after it none. `pension_rest_ext_test.rego` carries
  a must-deny per action for that account with each of the three roles.

## 4. STRIDE

Questionnaire appropriateness for investment products uses the effective published catalog
instrument mapping for each offered strategy. Submission stores the mapping revisions and classes
in the immutable assessment JSON; strategy choice stores the selected per-class decision in the
application JSON. Choice and signature reject a missing, ambiguous, or changed mapping. Historical
assessments without this evidence must be answered again before a DIP strategy can be chosen.
Legacy scale-only submissions are rejected for an appropriateness regime because they cannot
provide per-class evidence.
The pre-SCA signature check prevents spending a challenge after the mapping has changed. DPS
packs without an appropriateness test retain their existing risk-profile warning flow.

Residual: the separate post-onboarding contract strategy-election endpoint does not yet resolve
the assessment or apply this instrument gate. It must be gated before investment strategy changes
can use this evidence.

| Threat | Vector | Mitigation in S1 |
|---|---|---|
| **Spoofing** | A caller impersonates the participant to open or terminate a contract | Writes reachable only through the edge principal, which authenticates the human and stamps `X-Customer-Party-Id`; an absent header is a 400 (nullable param + `requireNotNull`, #3104). The header is trusted only from the edge **client** identity — a verified JWT whose `azp` is a configured relay client and whose `preferred_username` is that client's own `service-account-<azp>` — never from the principal name alone, which derives from a username claim (`ContractAccessGuard`; `ContractAccessGuardTest`, `PensionContractApiIT`, falsified by reverting to a name match) |
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
| **Tampering / replay** | A retried or replayed order is executed twice | `Idempotency-Key` is required on `POST /api/v1/contracts/{contractId}/orders` (`ContractUnitResource`); `uq_unit_orders_idempotency UNIQUE (contract_id, idempotency_key)` (`V1__init_pension_fund.sql`); reusing a key for a different instruction is rejected (`UnitRegisterService`). The adapter persists the full subscription/redemption plan in `pension_fund_order_plans` (V14) before sending any leg, and replays its exact instructions and deterministic leg keys after response loss, restart, strategy changes or settlement. An atomic insert selects one complete plan for concurrent callers; a parent key reused with different contract, operation, amount or currency is rejected before sending |
| **Tampering** | Order amount or fund altered or out of range | Amounts normalised to money precision server-side (`Precision::money`); `fundId`/`type` required; prices come from the fund side's own published NAV, never from the caller |
| **Information disclosure** | Holdings read beyond the participant's contract | Same ownership dependency as IDOR above; the non-read holdings action avoids both generic staff-read grants |
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

The unit register these exits redeem from (pension-fund-service) is reachable over the network
only from the `pension` namespace: its generated ingress NetworkPolicy admits `pension` (and the
staff BFF in `admin-ui`) on the API ports, and customer-edge no longer declares a route to it, so
the participant-facing edge cannot reach holdings or place orders even with a valid token. What
remains is pension-service's own client: a compromised pension-service pod can read every
contract's holdings and place redemptions within OPA's grant to
`service-account-openbank-pension`. The app's participant valuation and unit transactions come
from pension-service's own participant reads (`/contracts/{id}/valuation`, `/transactions`,
ownership-checked there), relayed by the edge — never from the fund API.

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
| **Tampering** | Concurrent operator assignments or an assignment racing a return resolve one parked payment inconsistently with its credited contract | An `OPEN` compare-and-set and contribution insert share one Postgres transaction; the losing decision returns 409. A conflicting existing credit rolls back the decision. Real HTTP/Postgres tests cover assign/assign, assign/return and rollback. Activation and subscription run after commit; this transaction does not claim durable delivery of the onboarding signal |
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

## 4e. Reporting read model (#12425)

`GET /api/v2/pension/reporting/participant-aggregates` serves the participant aggregates
tax-reporting-service assembles the ČNB returns from (PSP 31-04 and kin). New machine caller:
tax-reporting-service's OWN client, `service-account-openbank-tax-reporting` (ROLE_API).

| STRIDE | Threat | Mitigation |
|---|---|---|
| **Information disclosure** | The read model leaks participants (ids, birth dates, amounts per person) | Every statement in `PgParticipantReportingQueries` is an aggregate (COUNT/SUM ... GROUP BY); age leaves the database already in years and is banded before it leaves the service; `ParticipantReportingApiIT` asserts none of the six seeded contract ids appears in the payload |
| **Elevation of privilege** | The shared service-account (HUMAN + ROLE_OPERATOR) or another service reads the aggregates through base `operator-read-any` | The action is `pension.reporting.aggregate` — deliberately not a `.read`/`.list` verb, which the generic rule grants to any operator-role principal; admitted only for real staff and the tax-reporting client (`pension_rest_ext.rego`), must-ALLOW/must-DENY in `opa test` and `opa eval` on the bundle; sabotage (dropping the principal-id condition) fails the must-DENY test |
| **Tampering** | A closed period answers differently on each read | Intervals are closed on dates and timestamps are bucketed by UTC day, independent of the session time zone (the IT's 1 April 00:30Z instruction lands in April); a period booked in more than one currency is refused (409), never summed |

Residual: contract status at period end is only knowable while the contract has not changed since
— later changes are reported under `CHANGED_AFTER_PERIOD_END`, not guessed; there is no status
history table.

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

- 2026-10-10 — §3: pension reads renamed `*.read` → `*.inspect` so the shared M2M account no longer reaches them through base `operator-read-any`/`compliance-read-any` (#12371).
- 2026-10-10 — §4a: pension → pension-fund trust boundary (fund-administration port, #12355).
- 2026-10-09 — initial model with the S1 bootstrap (ADR-0334, #12350).
- 2026-10-09 — S5 exits: termination, payout, death claims (§4b).
- 2026-10-09 — S8 integration: fund REST client identity, onboarding-only activation, account-bound SCA, held account change, optimistic locking, POST replay (§4c).
- 2026-10-09 — annuity partner integration: registry with four-eyes activation, adapter SPI, SCA-bound selection, premium/compensation flow (§4d, #12383).
- 2026-10-10 — network reach of the unit register: only `pension` (plus the staff BFF) is admitted; customer-edge's direct route removed (§4b, #12359).
- 2026-10-10 — reporting read model for the ČNB returns: aggregate-only route, tax-reporting client (§4e, #12425).

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


## Catalog transport migration

The catalog adapter reads the API v2 offering and approval projection using pension's own OIDC
client. The catalog's KEDA interceptor routes only API v1; the supported v2 target is the direct
8443 listener. Catalog currently retains at least one replica.

The expansion stage provisions a dedicated outbound `pension-service-client-tls` certificate with
client-auth usage, separate from pension's inbound server key. The production `catalog-authority`
bucket loads this identity and the private CA trust anchor from `/mnt/catalog-tls`, with TLS 1.3
and certificate/hostname verification. OIDC authorization remains required: transport identity
does not grant catalog business permissions. A missing identity or trust file must fail rather
than silently use an anonymous or trust-all client.

Cutover must follow delivery of a reviewed image containing the client TLS configuration. Only
then change the catalog URL to its direct HTTPS listener and regenerate catalog ingress policy
to admit pension. Switching the URL while the old image is pinned would fail mTLS and interrupt
onboarding. The source expansion alone does not establish that production uses mTLS.

Verification requires the actual catalog REST client to complete a client-authenticated handshake
and reject a wrong CA and hostname, plus authenticated API v2 and network-policy reachability
after deployment. Rollback of the cutover restores the prior URL and generated policy together;
it restores the former transport/routing limitations and is not a security completion claim.
The additive client identity and listener compatibility can remain during rollback. Other
plaintext integrations, including pension-fund, are separate remaining transport risks.

The same expansion sequence prepares account, party and SCA clients using their own named TLS
authorities and mount paths for the dedicated outbound pension identity. Their existing 8443
listeners and ingress policies already admit pension; this does not establish deployed reachability.
Each client must be verified through its actual REST interface with client authentication and
OIDC before cutover. Keep the current URLs until the reviewed configuration image is delivered.
The shared CA identifies transport peers; account ownership, party validation and SCA consumption
continue to enforce their existing business authorization and participant constraints.

Payment clients additionally prepare separate `sdd-authority`, `domestic-payment-authority`
and `standing-order-authority` TLS buckets using pension's dedicated client-auth identity
and explicit CA trust from their own read-only mount aliases. The provider manifests prepare
parallel required-client-auth listeners with distinct server identities. No source expansion
proves those listeners have issued certificates or are serving in the deployment. Deliver and
verify both provider listeners and the reviewed pension image before changing caller URLs
and regenerating ingress policies; existing HTTP edges remain until that reviewed cutover.
Transport identity does not authorize mandate registration, standing orders or payment creation;
OIDC, request bindings and each provider's business authorization remain independent controls.

The fund adapter additionally prepares `fund-authority` with explicit private-CA
trust and pension's client-auth identity from `/mnt/fund-tls`. Its provider already
declares a TLS 1.3 listener and exact Service DNS certificate SANs, but current
server configuration does not require client certificates. Client preparation does
not establish server-side mTLS enforcement. Verify TLS readiness and independent
OIDC/business authorization before the staged HTTPS URL cutover; require provider
client authentication only with verified compatibility for its other callers.

## Receiving-provider identity binding

The transfer-out resource requires `ServiceAccountIdentity.isPrincipal`: a verified JWT
with a nonblank subject, `azp` bound to the requested provider client and matching
service-account `preferred_username`. A matching display principal alone grants nothing;
foreign-client, missing-subject and non-JWT identities are rejected before transfer writes.
Participant SCA consent remains required before funds can move. Rollback of this guard
restores username-only authentication and must not precede an equivalent replacement.

## Idempotency replay telemetry boundary — #12424

The idempotency replay filter increments `openbank.pension.idempotency.replays` only after finding a
stored response and accepting the existing request fingerprint. The metric's sole label is the HTTP
method. The filter accepts only POST, PUT and PATCH in its existing replay scope, so the label has
three values; principal, participant, path, key, fingerprint and request/response bodies are not
metric labels or values.

The existing scope still binds principal, customer-party header, method, path and key; a mismatching
stored fingerprint is still rejected before a replay is counted. Counting an accepted replay does
not repeat the business operation, alter authorization, weaken fingerprint checks, or add a new data
destination beyond the existing metrics registry. The counter describes stored-response reuse, not
successful new money movement. Existing concurrent-first-attempt limitations and lower-layer
financial idempotency remain as described above.

The observability adapter and service tests provide source evidence for the metric contract. This
document does not establish deployed metric availability, scraper access control, cardinality
measurements on running pods or successful hosted CI on a later documentation-only head. Rollback of
this observation removes the counter call and metric wiring while retaining the replay scope and
fingerprint checks. The separate pending staff-search assessment in #12943 is not part of this
change; a future merge must preserve both independent threat-model additions.

## Staff contract search and participant birth-date response — #12942

The staff contract-list surface accepts an optional free-text `q` covering contract and participant
identifiers, product line, participant birth date, and payment reference.
`ContractResponse.participantBirthDate` is personal data. Existing contract-read authorization still
runs before the use case; search does not introduce a new role grant. The use case ignores `q` for a
participant caller and loads only that participant's contracts. Only the staff path can invoke the
cross-contract search, while the existing status filter narrows results and the result limit remains
capped at 100.

The repository binds the search pattern and digit pattern as SQL parameters; the optional status
fragment comes only from the `ContractStatus` enum. Payment-reference matching selects contract IDs
before normal aggregate hydration. These controls address SQL injection and cross-participant
disclosure; they do not establish indexed or constant-time search. Search input, birth dates,
payment references, and returned records must remain confidential. This change adds no application
logging of those values and no new retention or external data destination; operational access-log
configuration requires its own assessment and is not proven by the source tests.

Evidence: the real HTTP integration test exercises birth-date, identifier, product and reference
matches plus status narrowing. Existing participant ownership checks remain in the read path; that
scope is also enforced independently of the UI's request-ordering guard. The UI guard suppresses
stale completions only and is not an authorization control. The deadline endpoint regression
exercises the injected CDI bean and prevents the default-argument proxy failure; it changes no
deadline rule or financial operation. These are source/test claims, not proof of deployment, access-
log redaction or runtime enforcement. Rollback: revert the search parameter/response addition and UI
changes together; no migration or stored-data rewrite is introduced.

- **2026-10-17** — **OPA PolicyDecisionPoint now comes from libs-runtime (#13136).** The per-service AuthzProducer is removed and the service opts into the shared `OpaPolicyDecisionPointProducer` with `openbank.authz.opa-pdp-producer.enabled: true` (a build-time switch). `opa.url` and `opa.path` keep their keys and defaults. The timeout key is renamed from `opa.timeout` (`PT0.5S`) to `opa.timeout-ms` (`500`); no manifest or environment sets either, so the effective OPA timeout stays 500 ms. No change to policy, actions, roles, the sidecar edge or fail-closed behaviour; the PDP object is the same `OpaSidecarPolicyDecisionPoint`. Rollback: revert the PR.
