<!-- SPDX-License-Identifier: Apache-2.0 -->
# Threat model — openbank-pension-fund-service

- **Status:** integration (ADR-0334 slices S4/S8), sandbox only — pension-service machine caller
- **Last reviewed:** 2026-10-11 (#12425 reporting read model, #12435 integration, #12442 conflict metric on the REST mappers)
- **Owner:** pension-fund-service CODEOWNERS
- **Related ADRs:** ADR-0030, ADR-0034, ADR-0315, ADR-0334

## Scope and assets

The provider side of the pension platform (ADR-0334 §1): segregated funds (`funds`), fund
strategies with rebalancing bands and lifecycle glide paths (`fund_strategies`), governed strategy
changes (`strategy_changes`), NAV records (`fund_navs`) and the unit register — orders
(`unit_orders`), priced transactions (`unit_transactions`) and holdings per (contract, fund)
(`unit_holdings`). **Money-path** (`rules.yaml: money_path_services`): every settled order converts
a participant's money into or out of units at a NAV, so a wrong NAV or a double settlement is a
money movement for every participant who traded at it.

Fund assets belong to the participants and are **segregated from the bank**: nothing in this
service posts to the bank ledger or to treasury, and no table references a bank GL account. A fund
carries its depositary and custody-account references instead. Participants are identified only by
an opaque `contractId` owned by pension-service; this service holds no names, birth numbers or
contact data.

## Trust boundaries

1. Fund administrators call `/api/v1/funds`, `/navs`, `/strategies` and `/strategy-changes` with
   their OWN bearer token. RBAC per method (`@RolesAllowed`, ROLE_OPERATOR / ROLE_ADMIN to write,
   plus ROLE_AUDITOR / ROLE_API to read), then OPA (`pension_fund_rest_ext.rego`: writes for real
   staff only, every `service-account-` excluded), then the domain (four-eyes).
2. `/api/v1/contracts/{contractId}/*` is the unit-register surface pension-service calls through
   its FundAdministrationPort (ADR-0334 §1). Only real staff and pension-service's own client
   (`service-account-openbank-pension`) may read holdings or place orders. The read action is
   `pension-fund.holding.inspect`, deliberately not a `.read`/`.list` verb: base rest.rego's
   `operator-read-any` (ROLE_OPERATOR/ROLE_ADMIN) and `compliance-read-any` (ROLE_COMPLIANCE)
   admit any HUMAN principal to every such action, and the shared service-account holds
   ROLE_OPERATOR and ROLE_COMPLIANCE in the CI realm. The earlier remedy, an entry in
   `rules.yaml: authz.operator_read_any_excluded_actions`, vetoed only the first of those rules,
   so the shared account still read holdings through `compliance-read-any`. Proven both ways by
   `opa test` (must-DENY the shared account under ROLE_OPERATOR, ROLE_COMPLIANCE and ROLE_ADMIN;
   must-ALLOW staff and pension-service's client) and by `opa eval` on the materialised bundle.
3. The integration admits the `pension` namespace in pension-fund's ingress NetworkPolicy.
   This is network reachability, not authorization: the provider still checks the bearer identity,
   RBAC and OPA for each request. `PensionFundRestClient` uses `@OidcClientFilter` and the
   `openbank-pension` client-credentials identity to read holdings, place orders with an
   `Idempotency-Key`, and read strategies. A caller compromised inside the admitted namespace
   must not acquire fund-administrator NAV or strategy-change permissions. The exact machine
   principal grant is a trust boundary; it does not establish participant ownership by itself.
4. Market prices enter through `MarketPricePort`. The shipped adapter (`StubMarketPriceAdapter`)
   knows no prices, so every position must be priced in the request by the calculating
   administrator; an unpriced position is refused, never valued at an invented number.
5. `/api/v1/reporting/funds/{fundId}/period-figures` (#12425) is the period-end read model
   tax-reporting-service assembles the ČNB returns from. Aggregate only — sums and counts, no
   contract id. `pension-fund.reporting.inspect` is admitted for real staff and for
   tax-reporting-service's OWN client (`service-account-openbank-tax-reporting`, ROLE_API) alone,
   and, like `holding.inspect`, uses a non-read verb so neither `operator-read-any` nor
   `compliance-read-any` lets the shared service-account reach it. Proven by `opa test` (must-ALLOW/must-DENY) and by `opa eval`
   on the materialised bundle, including the sabotage run with the exclusion removed (the shared
   account is then admitted by `operator-read-any`).
5. The REST exception mappers (#12442 observability) now count optimistic-lock conflicts on the
   unit holding (`optimisticLockConflict("unit_holding")`) before answering 409. The boundary
   itself is unchanged: same status, same body, no new route or caller; the metric carries a
   fixed aggregate label only, never a contract id or a request value.

## STRIDE analysis

| Threat | Control | Residual risk |
|---|---|---|
| Elevation — one person calculates and publishes a NAV | `NavRecord.publish` throws `FourEyesViolationException` (403) when the approver is the calculator; DB CHECK `ck_fund_nav_four_eyes`; `PensionFundApiIT` proves the maker gets 403 over real HTTP and a second principal publishes | Identity is the token's principal name; two accounts held by one person defeat any four-eyes control |
| Elevation — one person changes a strategy | `StrategyChange.approve`/`reject` refuse the submitter (403); DB CHECK `ck_strategy_change_four_eyes`; asserted in `FundStrategyTest` and `PensionFundApiIT` | Same as above |
| Tampering — a strategy change applied without notice | `StrategyChange.submit` and `approve` require the effective date to leave `openbank.pension-fund.strategy-change.minimum-notice-days` after approval (the participant-notification date); `markApplied` refuses before the effective date (409) | The notice period is a deployment setting until jurisdiction packs (ADR-0334 §3) supply the statutory value; no notification is SENT yet — the date records when it may be, not that it was |
| Tampering — backward pricing (trading at a known price) | `ForwardPricer.settle` refuses a NAV published at or before the order's placement, or valuing a day before it; publication selects orders placed before the publication instant whose UTC placement date is no later than the valuation date; a switch's buy leg waits for the TARGET fund's next NAV (`ForwardPricerTest`, `UnitRegisterFlowTest`) | Placement time is the service clock; a skewed pod clock shifts the cut-off |
| Tampering — dilution through rounding | Units issued are rounded DOWN, units cancelled for a fee rounded UP, money HALF_EVEN at 2 dp, NAV HALF_EVEN at 6 dp (`Precision`); every amount is a `BigDecimal` with an explicit scale | — |
| Tampering — lost update on a holding | `unit_holdings.version` optimistic lock (`@Version`): a write from a stale read fails the whole commit with 409 and nothing is applied | Not exercised by a concurrent test; the version check is Hibernate's |
| Tampering — selling units twice | `reserveOutgoing` locks the holding row, rechecks idempotency and queued outgoing units, and inserts the reservation in one transaction. `OutgoingReservationIT` uses concurrent real HTTP requests and a PostgreSQL row lock; settlement also rechecks holdings and the non-negative CHECK remains | Quiesce all older reservation writers before enabling writes on this release; older binaries do not participate in this locking protocol |
| Tampering — switch changes currency without FX accounting | Placement rejects source/target currency mismatch before persistence; unit and HTTP regressions prove unchanged register state | Cross-currency switching remains unavailable until an approved FX accounting flow exists |
| Tampering — a wrong NAV stays wrong | A correction is a NEW NAV for the same date that the original's units are re-valued with; its publication (four-eyes) supersedes the original, re-prices every transaction priced at it and adjusts holdings by the unit difference; the partial unique index `uq_fund_navs_published` allows one published NAV per fund and day | The cash difference on redemptions (`amountDelta`) is reported, not paid — compensation payment is a pension-service follow-up; a switch-out correction does not cascade into its already-settled switch-in leg |
| Tampering — fund assets reach the bank's books | No ledger or treasury client exists in this module; no GL account is referenced anywhere | A future integration must keep fund books on the provider entity's own GL (ADR-0334 §2) |
| Repudiation | Maker and checker principal names and times are stored on every NAV and strategy change; transactions keep the NAV they were priced at and, after a correction, the NAV they were corrected from | No events are published yet, so nothing reaches the tamper-evident audit trail; the database rows are the only record |
| Information disclosure — another service reads participants' holdings | ClusterIP only, generated NetworkPolicy allow-list admitting only the `pension` namespace and the staff BFF (`admin-ui`) on the API ports (customer-edge declares no route here; customer reads go via pension-service), no participant PII; holdings readable only by staff and `service-account-openbank-pension` (declared exclusion from `operator-read-any`, must-deny tests for the shared and an unrelated service-account) | Every operator can read every contract's holdings; the pension-service Keycloak client is not registered yet, so until it is no machine can read holdings at all |
| Information disclosure — the reporting read model leaks participants | The response carries counts and sums only; `PensionFundApiIT` asserts the seeded contract id is absent from the payload and the golden test asserts no contract id appears in the report | Small counts (one holder) are inferable; acceptable for a filing the regulator receives anyway |
| Tampering — a filed figure silently restated | Period figures are derived only from PUBLISHED NAVs and the transactions priced at them, bucketed by valuation date; the response carries `basisNavIds` and a `fingerprint` that changes when, and only when, those inputs change (a NAV correction), so tax-reporting can file a revision rather than overwrite; every roll-forward's closing figure is computed independently of its movements (`FundReportingGoldenTest`, with a sabotage run that drops fee cancellations and fails 3 of 4 tests) | The read model is computed on request, not snapshotted; the fingerprint detects a restatement, it does not prevent one |
| Tampering — a position's instrument class changed by one person, or a loan reported as zero | A recorded position's class (ECB/2018/2 category) changes only through `position_classification_corrections`: maker proposes (`nav.calculate`), a different checker decides (`nav.approve`); domain `FourEyesViolationException` (403) plus DB CHECK `ck_classification_four_eyes`; one pending correction per position (partial unique index); approval re-checks the class the maker saw and refuses a stale proposal (409); the position row is never rewritten. Positions recorded before the attribute are `UNCLASSIFIED`, and `loansOutstanding` (PEF 13-04) is null while any closing position is — unknown, never zero (`FundLossAndClassificationTest`, `PensionFundApiIT`; sabotage of the four-eyes check and of the unknown-loans rule each fails the suite) | Classification of a NEW position is what the maker submits with the NAV; the NAV's own four-eyes covers it, nothing checks it against an external instrument master |
| Tampering — a loss year hidden in a netted income | The YTD P&L reports revaluation gains and losses as separate non-negative lines, fees on their own, and the signed profit measured from net assets and flows; lines are null (with the reason) when a NAV of the year predates position recording (sabotage: netting losses into gains fails the golden loss quarter) | Interest, the result on disposals and cash movements are one signed "other" line — there is no cash or trade ledger to separate them |
| Denial of service | 1 MB body limit, rate limit, a NAV publication settles in one transaction | A fund with very many queued orders settles them all in one request |

## Invariants

1. No order is priced at a NAV that was published when the order was placed.
2. A NAV is published, and a strategy change approved, only by a principal other than its maker
   (domain + DB CHECK).
3. At most one published NAV exists per fund and valuation date.
4. Holdings never go negative.
5. Nothing in this service posts to the bank ledger or treasury.
6. A reporting period with no published NAV is not reportable (409), never a report of zeroes.
7. A position's instrument class changes only by an approved four-eyes correction; an
   UNCLASSIFIED closing position makes the fund's loans unknown, never zero.

## Out of scope / follow-ups

Domain events and the audit subscription, live deployment verification of the pension-service
FundAdministrationPort adapter (consumer/provider Pact tests are present), a real market-data adapter, investment orders and depositary reconciliation,
limit/concentration checks, scheduled NAV and strategy application, participant notification
delivery, compensation payments for NAV corrections, fund close/merge with unit migration.

## Required client authentication on the parallel TLS listener

The 8443 listener requires a client certificate chaining to `openbank-ca`, using
the mounted certificate Secret's `ca.crt` as its trust anchor. The existing HTTP
listener remains for current callers during the reviewed cutover. Client identity
does not confer permission to read holdings or place orders: OIDC principal and
contract-scoped business authorization remain mandatory. Verify issued certificates,
listener readiness and the reviewed pension client image before migrating its URL.
Any additional caller using 8443 must supply a trusted certificate. Rollback reverts
client URL/policy cutovers first; then required-client-auth can be removed while
the HTTP compatibility listener remains. No deployment readiness is inferred from
this manifest or a local health test.

Client authentication is fixed at Quarkus augmentation time. The `%prod` profile therefore
sets `quarkus.http.ssl.client-auth=required` and retains the HTTP compatibility listener;
the matching deployment variable alone cannot establish required client authentication.
Rollout verification must use the reviewed image built from this configuration.

## 2026-10-17 - Fund management fee pinned to a published catalog revision (ADR-0341)

New outbound edge: pension-fund-service -> product-catalog (`accounts` namespace, private-CA mTLS
8443, client cert `pension-fund-service-client-tls`, %prod TLS bucket `catalog-authority`) to read
ONE immutable published revision by id (`GET /api/v2/revisions/{id}`) when a fund is created. The
fund stores `product_revision_id` (V5) and the fee derived from the revision's
`feeSchedule.managementFeeAnnualPercent`; NAV accrues exactly that stored rate.

| Threat | Control |
|---|---|
| **Tampering** - an operator sets an arbitrary fee on a fund (the previous `managementFeeRate` request field priced every NAV) | The field remains deprecated: creation accepts it only if it equals the resolved revision fee, and amendment only if it equals the stored fee. It never selects the fee; that comes from the catalog revision. Amend rejects any `productRevisionId` that differs from the pinned one (a re-pin is not an amendment). Funds created before pinning keep their stored rate, read-only through the API. |
| **Spoofing** - another service reads or authors catalog terms with this credential | Own Keycloak client `openbank-pension-fund`: no realm role, only the `catalog:read` scope. product-catalog's OPA bundle admits `catalog.read` for `service-account-openbank-pension-fund` alone (`machine_named` + `CATALOG_SCOPE_READ`); `catalog.author`/`publish`/approvals stay denied (rego tests). |
| **Elevation** - unpublished or foreign terms priced as a fund fee | `FundRevisionTerms.parse` fails closed unless the revision id matches, `state=PUBLISHED`, schema `org.openbank.retirement.pension-savings` version >= 2, and a fee string is present. |
| **Denial of service** - catalog down | Fund CREATION fails closed (no fund without a pinned revision); NAV, register, orders and reporting read the stored rate and never call the catalog. |
| **Repudiation** | The revision id on the fund row is the audit link from a NAV fee back to the approved terms. |

Rollback: revert the commit; `product_revision_id` stays (nullable, additive) and `management_fee_rate`
remains correct as cached. Rollout order: seed Vault `keycloak/pension-fund-service`, create the
Keycloak client, then deploy (the env ref is `optional: true`, so an unseeded secret fails creation
closed rather than the pod).


### Versioned compatibility boundary (draft API v2 transition)

Create v2 requires an explicit published revision; amendment can omit or repeat the existing pin. The 27 retained v1 operations declare the same role/action, actor and four-eyes boundaries directly, rather than relying on resource inheritance. Their existing idempotency behavior is preserved; nine v2 writes additionally require durable response replay as described below. New reflection parity and real HTTP/OIDC/OPA/four-eyes regressions must pass native CI; source correspondence is not runtime proof. The owner approved only the draft exception excluding the two old manual-fee definition writes, not their live retirement. Other v1 operations retain deprecation headers and at least 180 days of configured sunset before an approved rollout.

Known callers are migrated explicitly; external writers remain an inventory gap. The v2 provider must be available across active backends before callers switch. The proposed certificate/network/pod-template changes can have same-image GitOps effects independently of image promotion; draft and blocked controls remain. No merge, provisioning, credentials, policy activation or deployment is approved here. Rollback preserves revision pins and numeric precision and does not re-enable arbitrary fees for pinned funds.

### Durable v2 write replay (draft implementation)

Nine v2 POST operations for fund creation, NAV calculation/decisions and strategy creation/change
now require a validated `Idempotency-Key`. A SHA-256 caller/service-scoped key indexes V7 receipts;
the canonical fingerprint binds method, concrete resource path and request DTO. A transaction-scoped
PostgreSQL advisory try-lock excludes concurrent attempts; a busy key returns 409, without claiming
the write failed permanently. A matching committed receipt replays the original status and JSON,
even after later changes. A different path or body under the same caller key returns 409.

The operation and receipt must commit in the same context-bound reactive transaction. HTTP/JDBC
regressions include an injected failure at receipt insertion, after the domain write, to distinguish
atomic rollback from a separately committed mutation. These new tests require native CI; source
review and standalone formatting are not execution evidence. Legacy v1 handlers do not gain this
new requirement. Roles and four-eyes checks execute before replay, so a cached result grants no
new permission. No new Redis infrastructure or in-memory production fallback is introduced.

Receipts contain response snapshots, including possible audit personal data. Hashing keys is
pseudonymization, not anonymization. V7 has no automatic expiry: deleting receipts can re-enable
duplicate writes. Application rollback retains the table and history; schema removal/retention
requires separate review. A 64-bit advisory hash collision can cause a transient busy response,
but the full SHA-256 receipt key still distinguishes stored results. No deployment, migration
activation or live replay is authorized by this draft.
