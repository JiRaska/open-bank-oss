<!-- SPDX-License-Identifier: Apache-2.0 -->
# Threat model — openbank-treasury-service

- **Status:** MVP (ADR-0315 D1–D6, D9), sandbox only — money-market deals against a SYNTHETIC counterparty set
- **Last reviewed:** 2026-09-25
- **Owner:** treasury-service CODEOWNERS
- **Related ADRs:** ADR-0003, ADR-0030, ADR-0031, ADR-0034, ADR-0313, ADR-0314, ADR-0315

## Scope and assets

The bank's own money-market book: MM placements with other banks (`MM_PLACEMENT`), MM borrowings
from other banks (`MM_BORROWING`) and the ČNB overnight deposit facility (`CNB_DEPOSIT_FACILITY`).
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
postings to 1520/1521/2310/2311, a holiday calendar, the agent charter (D10), nostro
reconciliation (D7), minimum reserves (D8), and real market connectivity (D9).
