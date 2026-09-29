<!-- SPDX-License-Identifier: Apache-2.0 -->
# Threat model — openbank-treasury-service

- **Status:** MVP (ADR-0315 D1–D6, D9), sandbox only — money-market deals against a SYNTHETIC counterparty set
- **Last reviewed:** 2026-09-26
- **Owner:** treasury-service CODEOWNERS
- **Related ADRs:** ADR-0003, ADR-0030, ADR-0031, ADR-0034, ADR-0313, ADR-0314, ADR-0315

## Scope and assets

The bank's own money-market book: MM placements with other banks (`MM_PLACEMENT`), MM borrowings
from other banks (`MM_BORROWING`), the ČNB overnight deposit facility (`CNB_DEPOSIT_FACILITY`) and
overnight borrowing from the ČNB marginal lending (lombard) facility (`CNB_LOMBARD`).
A deal moves `DRAFT → PENDING_APPROVAL → BOOKED → SETTLED → MATURED`, or to `CANCELLED` (before
booking) or `REVERSED` (after). Every settlement, maturity and reversal of a settled deal posts a
balanced journal to the ledger through its public API. **Money-path** (`rules.yaml:
money_path_services`): a wrong or duplicated posting misstates the bank's own balance sheet and
its liquidity (1510 is the risk engine's only HQLA account).

Assets: the deal book and its append-only timeline (`deal_transitions`), the counterparty master
and its credit limits, the ledger journals the deals produce (`deal_journals` holds their ids and
idempotency keys), and the `openbank.treasury.deal.events` stream the audit trail records.
Counterparties are banks and the central bank — no natural persons, no customer data.

## Trust boundaries

1. Treasury staff call `/api/v1/treasury/*` through admin-ui's BFF with their OWN bearer token.
   `ROLE_TREASURY_DEALER` drafts, submits and cancels; `ROLE_TREASURY_APPROVER` approves, rejects,
   settles, matures and reverses. RBAC (`TreasuryResource`), then OPA (`treasury_rest_ext.rego`,
   human principals only, every service-account excluded), then the domain (`Deal`).
   `GET /limits/utilisation` (#10896) is read-only and gated by the SAME `treasury.counterparty.read`
   action/roles as `GET /counterparties` — no new rego rule. Its `utilised`/`breached` fields are
   never computed independently: both it and the booking-time limit check read
   `DealRepository.exposure`, whose state/product filter derives from the single
   `Deal.LIMIT_CONSUMING_STATES` constant, so the view cannot silently diverge from what actually
   blocks booking.
2. The service posts journals to ledger-service's private-CA mTLS listener (8443, client
   certificate `treasury-internal-tls`) with its OWN machine identity: the named oidc-client `m2m`
   = Keycloak client `openbank-treasury`, ROLE_API only (`LedgerRestClient`,
   `@OidcClientFilter("m2m")`). `ledger_rest_ext.rego` grants `service-account-openbank-treasury`
   exactly `ledger.create` — no reverse, read, trigger or close action.
3. Deal events leave through the transactional outbox (`TreasuryOutboxDispatcher`) over mTLS
   Kafka as KafkaUser `treasury-service`, Write on its one topic only. audit-service reads it.
4. The in-process simulated market (`SimulatedMarketScheduler`, `Actor.SIMULATED_MARKET`) stands in
   for counterparty confirmation and settlement (ADR-0315 D9). It is SYNTHETIC; real market
   connectivity does not exist.

## Posting table (ADR-0315 D5)

Key `treasury:<dealId>:<event>`, balanced per currency, `transactionId` = deal id. CZK / EUR
accounts; the ČNB facility is CZK only. Seeded by ledger migration
`V29__treasury_money_market_accounts.sql` with fixed ids `a0000000-0000-0000-0000-00000000<code>`.

| Product | SETTLED (value date) | MATURED (maturity date) |
|---|---|---|
| `MM_PLACEMENT` | Dr 1500/1501 placements · Cr nostro 1001/1002 — P | Dr nostro P+I · Cr 1500/1501 P · Cr 4200/4201 MM interest income I |
| `CNB_DEPOSIT_FACILITY` | Dr 1510 deposit facility at ČNB · Cr 1001 — P | Dr 1001 P+I · Cr 1510 P · Cr 4200 I |
| `MM_BORROWING` | Dr nostro · Cr 2300/2301 borrowings — P | Dr 2300/2301 P · Dr 5200/5201 MM interest expense I · Cr nostro P+I |
| `CNB_LOMBARD` | Dr 1001 · Cr 2320 borrowings from ČNB — P | Dr 2320 P · Dr 5200 I · Cr 1001 P+I |

`BOOKED` posts nothing (no off-balance commitment in the MVP). `REVERSED` from `SETTLED` posts the
settlement journal with every side flipped under key `...:reversed`; from `BOOKED` it posts
nothing. I = principal × rate/100 × days/360 (ACT/360), half-up to 2 dp; a zero-rate deal posts no
interest line. 1520/1521 and 2310/2311 (accrued interest) are seeded but unused until daily
accrual lands. Implemented in `PostingRules`, asserted row by row in `PostingRulesTest`.

## STRIDE analysis

| Threat | Control | Residual risk |
|---|---|---|
| Spoofing — a non-person books a deal | `Deal.approve` refuses any actor whose type is not HUMAN (`ActorNotPermittedException`, 403); `Actor.fromPrincipalName` classifies `agent:` as AI_AGENT and `service-account-` as SERVICE, the interceptor's own convention; `treasury_rest_ext.rego` excludes every service-account and grants AI_AGENT nothing; DB CHECK `deals_approver_human` | Principal classification is by name prefix; a human account named `agent:…` would be treated as an agent (fail-closed) |
| Elevation — self-approval (four-eyes) | Enforced in the domain, not only in OPA: `Deal.approve` throws `FourEyesViolationException` (422) when the approver is the creator or the submitter; DB CHECK `deals_four_eyes`; `TreasuryDealApiIT` proves a user holding BOTH roles still gets 422 | Identity is the token's principal name; two accounts held by one person defeat any four-eyes control |
| Elevation — AI agent books, settles or reverses | ADR-0315 D3: an agent may only draft, with a rationale; submit/approve/reject/cancel/settle/mature/reverse all require HUMAN (SYSTEM additionally for settle/mature by the simulated market); `DealTest` and `TreasuryDealApiIT` run as `agent:treasury-drafter` holding ROLE_TREASURY_APPROVER and assert 403 | No agent charter exists yet (ADR-0315 D10), so OPA denies agents even the draft; the domain would allow it |
| Tampering — a deal booked beyond the counterparty limit | `LimitCheck` at submission AND again at approval over outstanding placed principal (PENDING_APPROVAL/BOOKED/SETTLED placements and ČNB deposits); a breach throws `LimitBreachedException` (422) and nothing books | No senior-approver override yet (ADR-0315 D4 — follow-up); limits are per counterparty and currency only, no product or ADR-0313 risk limits; two concurrent approvals could each see the other's exposure as absent |
| Tampering — a double or partial posting | The journal is posted BEFORE the state change commits, under a stable key; ledger's `postJournal` returns the original entry for a repeated key; deal row, timeline, journal id and outbox event commit in ONE transaction (`DealRepositoryImpl.save`); `deal_journals.idempotency_key` is UNIQUE. `TreasuryDealApiIT` loses the ledger's response after commit and proves the retry yields ONE journal | A lost response leaves the journal in the ledger while the deal still reads BOOKED until someone (or the simulated market) retries |
| Tampering — posting before the deal is booked | Only `settle`, `mature` and `reverse` call `LedgerPostingPort`; each is reachable only from BOOKED or SETTLED (`Deal.settle` / `Deal.mature` / `Deal.reverse` state checks); the IT asserts zero ledger calls through draft, submit, a refused self-approval, an agent's attempt and the approval itself | — |
| Tampering — wrong GL account | Accounts are fixed ids from one table (`TreasuryChart`); the ledger rejects (422) a line whose currency does not match its account; CZK nostro 1001 is re-keyed to its fixed id only while unreferenced, else the CZK posting fails loudly rather than landing elsewhere | An environment where 1001 was already referenced needs a reviewed manual re-key before CZK deals can settle |
| Repudiation — who did what | `deal_transitions` is append-only (one row per transition: actor id, actor type, time, note incl. the limit figures and any rejection/reversal reason); `treasury.deal.*` events carry `createdBy`/`approvedBy`/`reversedBy` and `sourceService` and reach the tamper-evident audit trail (`audit-money-path-subscription`) | The ledger records the service's fixed actor id (`LedgerPostingAdapter.SYSTEM_ACTOR`), not the human approver; the link is the deal id (`transactionId`) |
| Information disclosure | ClusterIP only, NetworkPolicy allow-list, mTLS to ledger and Kafka; reads require a treasury role or ROLE_ADMIN; no customer data is held. OIDC token-issuer TLS verification stays at the default `required` outside `%dev` (security review HIGH, fixed 2026-09-25) | Counterparty limits and the whole book are visible to every dealer |
| Denial of service | The simulated market runs one pass at a time (`ConcurrentExecution.SKIP`), counts a failing deal and continues; outbox dispatch is claimed with `FOR UPDATE SKIP LOCKED`; 1 MB body limit | The ledger call is synchronous inside the request; a slow ledger holds the approver's request |

## Invariants

1. No journal is posted for a deal that has not been BOOKED by a second human.
2. A deal's approver is never its creator or submitter, and is always HUMAN (domain + DB CHECK).
3. Every journal key is `treasury:<dealId>:<settled|matured|reversed>`; each is posted at most
   once per deal in the ledger, however often it is retried.
4. Every posted journal balances per currency.

## Out of scope / follow-ups

Senior-approver limit override (ADR-0315 D4), product and ADR-0313 risk limits, daily accrual
postings to 1520/1521/2310/2311, a holiday calendar, the agent charter (D10),
minimum reserves (D8), and real market connectivity (D9).
- **2026-09-26** — **Senior override of a counterparty-limit breach (ADR-0315 D4).** `POST /api/v1/treasury/deals/{id}/override-limit` (required `Idempotency-Key`, `reason` body) records an override on a PENDING_APPROVAL deal whose limit check is breached. It needs a new realm role, `ROLE_TREASURY_SENIOR_APPROVER`, and its own OPA action, `treasury.deal.override-limit`, granted only to that role for a HUMAN principal that is not a `service-account-`. A senior gets read, and neither drafts nor books. The domain enforces the rest, not OPA alone. The override is human-only, carries a non-blank reason and exists only for a breached check. The overrider is never the deal's creator or submitter, and the senior who overrode may not also book the deal, so a breach booking involves three people. The override is bounded to the exposure it was granted for (`coversExposureUpTo`): if exposure grows before booking, approval refuses again. A rejection drops it. **STRIDE-E:** closed by the separate role and action, the service-account and agent exclusions (`opa test`: dealer, approver, admin, a machine and an agent holding the senior role are all denied), and the domain four-eyes rules. **STRIDE-R:** who, why, when, the exposure covered and the limit are persisted (V4, complete-or-absent CHECK) and written to the deal timeline. Rollback: remove the endpoint and the rego rule; V4 columns are nullable.
- **2026-09-27** — **ČNB lombard (marginal lending) borrowing, `CNB_LOMBARD` (#10896).** A new product on the existing deal lifecycle, no new endpoint, role or OPA action: it is drafted, four-eyes booked, settled, matured and reversed by exactly the `MM_BORROWING` code paths. The domain pins its terms (`Deal` init): CZK only, counterparty `CNB` (and no interbank product may face `CNB`), maturity forced to the next business day whatever the request says, and a rate strictly > 0 (the dealer enters the ČNB lombard rate; nothing fetches it). It posts to a new CZK-only liability, 2320 "Borrowings from CNB (lombard)", seeded by ledger migration `V30` with the V29 fixed-id convention; accrual and maturity use the MM expense and accrued-payable accounts. **Limits:** as a borrowing it consumes no credit limit (`LimitCheck` deal amount 0); the `CNB` counterparty row and its limit are the deposit facility's, unchanged. **STRIDE-T (wrong GL account):** the ledger refuses a 2320 line in EUR (422, `TreasuryAccountsPostingIT`); `PostingRulesTest` asserts the settlement, maturity, accrual and reversal rows. **Residual / not modelled:** the collateral pledge that backs a real lombard loan (eligible securities, haircuts, the pledge itself) is NOT modelled — a lombard deal here is unsecured on the books; the lombard rate is not validated against the ČNB's published rate; there is still no holiday calendar, so a Friday deal matures Monday and a pre-holiday deal matures on the holiday. Rollback: treasury `V7` and ledger `V30` carry their rollback notes (only while no `CNB_LOMBARD` deal / 2320 line exists).
- **2026-09-26** — **Nostro reconciliation (ADR-0315 D7, #10896).** `POST /api/v1/treasury/nostro/statements` (camt.053 XML, required `Idempotency-Key`, OPA `treasury.nostro.upload` — `ROLE_TREASURY_APPROVER`, human, never a `service-account-`) stores a correspondent's end-of-day statement for an account configured in `openbank.treasury.nostro.accounts` (IBAN -> nostro GL 1001/1002; a mapping to any other GL refuses to start). `GET /api/v1/treasury/nostro/statements/{id}/reconciliation` (`treasury.nostro.read`) compares it with the ledger's nostro GL over the statement's booking-date span (≤ 31 days) and lists matched items, unmatched items on BOTH sides and the opening/closing differences. **Nothing is posted**: the service holds no reconciling-entry path, and the new ledger grant (`service-treasury-ledger-read`, `ledger.list` + `ledger.read` for `service-account-openbank-treasury` only) is read-only. **STRIDE-T (untrusted XML):** DOCTYPE refused and external entities off (XXE; `Camt053ParserTest`), exactly one `Stmt`, balances must foot or the upload is a 400, 1 MB body limit. **STRIDE-T (false match):** exact match needs amount, direction, date AND reference, the reference equal to the ledger transaction id or to a WHOLE token run of the journal description (never a substring); the amount/date fallback pairs only a unique candidate on each side and is labelled `AMOUNT_DATE`; a tampered amount is unmatched on both sides (`NostroMatcherTest`, `NostroReconciliationApiIT`). **STRIDE-R:** the uploader, time and SHA-256 of the exact bytes are stored with the statement (V8); an Idempotency-Key replay with different bytes, or the same (IBAN, statement id) under a new key, is 409, so a statement cannot be silently replaced — including under a concurrent upload, where the unique constraints (not the pre-check) decide and the loser gets the same replay/409; amounts beyond NUMERIC(19,4) are a 400, never rounded. **STRIDE-S:** the statement is not signature-verified — anyone holding the approver role can upload a fabricated file; the uploader is recorded, the file is not authenticated (follow-up: correspondent channel / signature). **Initial residual (superseded by the native-currency phase below):** the ledger exposes only base-currency (CZK) balances, so a EUR nostro shows no ledger balance, no difference and `reconciled: null` with a `balanceNotStated` reason — items are still matched on native amounts, balances are not compared. reconciliation is computed on read against the live ledger, so a later back-dated journal changes the answer; synthetic (canary) journals are excluded on both the line and balance reads (ledger `REAL_ONLY`). Rollback: remove the endpoints and the two rego rules; V8 is additive (drop the two tables).
- **2026-09-26** — **FX spot deals (`FX_SPOT`, #10896).** The bank buys or sells EUR against CZK with a counterparty through the SAME lifecycle and endpoints as the money-market products: no new route, role or OPA action (draft/submit/approve/settle/reverse reuse `treasury.deal.*`). `currency`/`principal` are the foreign leg, `rate` the dealer's rate in CZK per unit; the CZK leg is `principal × rate`, half-up to 2 dp, stored (V9 `fx_counter_amount`) and re-validated on every load. Value date defaults to T+2 business days (weekends only — still no holiday calendar) and may not be later (a later date is a forward, not built). **Posting:** SETTLED posts ONE journal in two currencies through the ledger's existing FX position accounts (V5 of ledger-service, fixed ids 1990/1991): BUY = Dr 1002 / Cr 1991 (EUR) and Dr 1990 / Cr 1001 (CZK), SELL = the same lines flipped; balanced within each currency, key `treasury:<dealId>:settled`. No ledger migration was needed. A spot never accrues or matures (`maturity_date = value_date`, excluded from the simulated market's maturity pass, `mature` is 409); reversal from SETTLED posts the flipped four lines. **STRIDE-T (limit):** a spot carries settlement risk, so it consumes the counterparty's **CZK** limit by its CZK equivalent while PENDING_APPROVAL or BOOKED — the same `LimitCheck.of` and the same breach/override path, sharing one CZK line with CZK placements; it releases on settlement. **STRIDE-T (rate):** the dealer-entered rate can be checked against fx-service's mid within `openbank.treasury.fx-spot.rate-check.tolerance-percent`; outside tolerance the deal is FLAGGED on the deal and its timeline, never blocked, so the four-eyes approver sees it. **Residual:** treasury has no fx-service client yet, so the check is OFF by default and nothing validates the dealer's rate except the approver; switched on without a client, every FX deal is flagged "mid unavailable". **Out of scope:** revaluation of the open position (the ledger's own daily FX revaluation, ADR-0031, marks 1991 against ČNB fixings; treasury adds nothing), FX forwards and swaps, currencies other than EUR. Rollback: V9 columns are nullable; roll back only while no FX_SPOT row exists.
- **2026-09-27** — **Nostro balances in the statement currency (#11107, supersedes the base-currency residual above).** Reconciliation now reads both ledger balances from ledger's `GET /api/v1/journals/accounts/{code}/balance?asOf=&currency=` (native `amount`, never `base_amount`; ledger `ledger.read`, already granted to `service-account-openbank-treasury`) — one path for CZK and EUR: opening as at the day before the earliest booking date, closing as at the statement date. The trial-balance read is gone. **STRIDE-T (wrong figure compared):** the adapter refuses (500, no report) a response whose echoed code/currency/asOf differs from what it asked, so a balance for another account, currency or day can never produce a difference; the pact pins the literal path and the echo, replayed by ledger's `@PactFolder` provider test. **Residual:** `balanceNotStated` remains only for a ledger 404 on the GL (chart not migrated) — `reconciled: null`, never a pass; any other ledger failure fails the request. Rollback: revert the read path; V10 adds `opening_date` and must be removed only after preserved statement periods are accounted for. The ledger grant is unchanged.
