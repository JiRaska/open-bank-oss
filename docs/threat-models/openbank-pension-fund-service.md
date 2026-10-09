<!-- SPDX-License-Identifier: Apache-2.0 -->
# Threat model — openbank-pension-fund-service

- **Status:** bootstrap (ADR-0334 slice S4), sandbox only — no participant-facing caller yet
- **Last reviewed:** 2026-10-09
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
   (`service-account-openbank-pension`) may read holdings or place orders. `pension-fund.holding.read`
   is listed in `rules.yaml: authz.operator_read_any_excluded_actions`, so base rest.rego's
   `operator-read-any` no longer admits the shared service-account (which holds ROLE_OPERATOR) or
   any other service. Proven both ways by `opa test` and by `opa eval` on the materialised bundle.
3. Market prices enter through `MarketPricePort`. The shipped adapter (`StubMarketPriceAdapter`)
   knows no prices, so every position must be priced in the request by the calculating
   administrator; an unpriced position is refused, never valued at an invented number.

## STRIDE analysis

| Threat | Control | Residual risk |
|---|---|---|
| Elevation — one person calculates and publishes a NAV | `NavRecord.publish` throws `FourEyesViolationException` (403) when the approver is the calculator; DB CHECK `ck_fund_nav_four_eyes`; `PensionFundApiIT` proves the maker gets 403 over real HTTP and a second principal publishes | Identity is the token's principal name; two accounts held by one person defeat any four-eyes control |
| Elevation — one person changes a strategy | `StrategyChange.approve`/`reject` refuse the submitter (403); DB CHECK `ck_strategy_change_four_eyes`; asserted in `FundStrategyTest` and `PensionFundApiIT` | Same as above |
| Tampering — a strategy change applied without notice | `StrategyChange.submit` and `approve` require the effective date to leave `openbank.pension-fund.strategy-change.minimum-notice-days` after approval (the participant-notification date); `markApplied` refuses before the effective date (409) | The notice period is a deployment setting until jurisdiction packs (ADR-0334 §3) supply the statutory value; no notification is SENT yet — the date records when it may be, not that it was |
| Tampering — backward pricing (trading at a known price) | `ForwardPricer.settle` refuses a NAV published at or before the order's placement, or valuing a day before it; publication settles only orders placed before the publication instant; a switch's buy leg waits for the TARGET fund's next NAV (`ForwardPricerTest`, `UnitRegisterFlowTest`) | Placement time is the service clock; a skewed pod clock shifts the cut-off |
| Tampering — dilution through rounding | Units issued are rounded DOWN, units cancelled for a fee rounded UP, money HALF_EVEN at 2 dp, NAV HALF_EVEN at 6 dp (`Precision`); every amount is a `BigDecimal` with an explicit scale | — |
| Tampering — lost update on a holding | `unit_holdings.version` optimistic lock (`@Version`): a write from a stale read fails the whole commit with 409 and nothing is applied | Not exercised by a concurrent test; the version check is Hibernate's |
| Tampering — selling units twice | Placement refuses a redemption or switch beyond units held minus units already queued to leave; settlement re-checks the holding; `unit_holdings.units >= 0` CHECK | Two concurrent placements can each pass the availability check; the settlement re-check and the CHECK constraint then fail the NAV publication rather than over-sell |
| Tampering — a wrong NAV stays wrong | A correction is a NEW NAV for the same date that the original's units are re-valued with; its publication (four-eyes) supersedes the original, re-prices every transaction priced at it and adjusts holdings by the unit difference; the partial unique index `uq_fund_navs_published` allows one published NAV per fund and day | The cash difference on redemptions (`amountDelta`) is reported, not paid — compensation payment is a pension-service follow-up; a switch-out correction does not cascade into its already-settled switch-in leg |
| Tampering — fund assets reach the bank's books | No ledger or treasury client exists in this module; no GL account is referenced anywhere | A future integration must keep fund books on the provider entity's own GL (ADR-0334 §2) |
| Repudiation | Maker and checker principal names and times are stored on every NAV and strategy change; transactions keep the NAV they were priced at and, after a correction, the NAV they were corrected from | No events are published yet, so nothing reaches the tamper-evident audit trail; the database rows are the only record |
| Information disclosure — another service reads participants' holdings | ClusterIP only, generated NetworkPolicy allow-list, no participant PII; holdings readable only by staff and `service-account-openbank-pension` (declared exclusion from `operator-read-any`, must-deny tests for the shared and an unrelated service-account) | Every operator can read every contract's holdings; the pension-service Keycloak client is not registered yet, so until it is no machine can read holdings at all |
| Denial of service | 1 MB body limit, rate limit, a NAV publication settles in one transaction | A fund with very many queued orders settles them all in one request |

## Invariants

1. No order is priced at a NAV that was published when the order was placed.
2. A NAV is published, and a strategy change approved, only by a principal other than its maker
   (domain + DB CHECK).
3. At most one published NAV exists per fund and valuation date.
4. Holdings never go negative.
5. Nothing in this service posts to the bank ledger or treasury.

## Out of scope / follow-ups

Domain events and the audit subscription, the pension-service FundAdministrationPort adapter and
its machine grant, a real market-data adapter, investment orders and depositary reconciliation,
limit/concentration checks, scheduled NAV and strategy application, participant notification
delivery, compensation payments for NAV corrections, fund close/merge with unit migration.
