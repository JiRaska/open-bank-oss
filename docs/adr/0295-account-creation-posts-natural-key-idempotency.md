---
date: 2026-09-07
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [resilience, authz]
summary: "Account-service creation POSTs dedup on natural keys: authorization grants and savings withdrawal proposals replay the original; pockets already conflict on (account, currency)."
---

# ADR-0295 — Account creation POSTs are idempotent on natural keys

## Context

The #8351 idempotency inventory requires every money-path POST to either enforce a declared
idempotency handle or carry an ADR-linked exception. Three account-service creation POSTs were
baselined as uncovered:

- `POST /api/v1/accounts/{accountId}/authorizations` — grant a signatory/mandate
- `POST /api/v1/accounts/{accountId}/pockets` — add a currency pocket
- `POST /api/v1/accounts/{accountId}/savings-goal/delegation/proposals` — delegate proposes a
  withdrawal (maker half of the ADR-0232 maker-checker flow)

## Decision

- **Pockets** — already enforced: `addPocket` checks the natural key `(accountId, currency)`
  first and refuses a duplicate loudly (400), backed by `uq_account_pockets_acc_ccy` (V7).
  Documented only.
- **Authorization grants** — the gap was real: a retried grant stacked a duplicate authority
  row. Fixed in the same change: `grantAuthorization` replays the identical still-ACTIVE grant
  (full caller-supplied tuple: party, role, limits compared scale-insensitively, validity). A
  revoke followed by an identical re-grant is a legitimate NEW grant and persists — the twin
  match is restricted to ACTIVE rows for exactly that reason.
- **Withdrawal proposals** — the gap was real and money-adjacent: a retried propose stacked a
  duplicate PENDING proposal with its own approval record, and two approved identical proposals
  are two executable withdrawal instructions. Fixed: `propose` replays the still-PENDING,
  unexpired identical proposal (account, delegate, amount, currency, note) together with its
  original approvalId. Once the original leaves PENDING (decided) or expires, the key no longer
  matches and a genuinely intended second identical proposal persists normally.

**No new DB backstop was added for either fix** (contrast the unique indexes in ADR-0287/0289):
for grants, a lost true-concurrency race stacks two identical authority rows, which the payment
guard reads as one authority — no double money, and grant management is an admin operation; for
proposals, a lost race stacks two PENDING rows, but each still requires its own owner SCA
decision, so nothing executes silently. Both races need two truly concurrent first attempts —
not the retry pattern these endpoints actually see.

## Alternatives considered

- **Synthetic `Idempotency-Key` header** — rejected: grants and pockets are identified completely
  by their natural keys; proposals carry a full business tuple. A synthetic key would identify
  nothing better and would burden the customer-edge callers with key minting for operations that
  are intrinsically replayable.
- **Unique partial index on PENDING proposals** — rejected: an expired-but-unswept PENDING row
  would then block the legitimate re-proposal the sweep window makes possible; the index would
  need to express `expiresAt > now()`, which no index can. The check-first read expresses it
  exactly.

## Consequences

- Three baseline entries re-point here; the OpenAPI descriptions name the replay semantics.
- A duplicate grant or proposal POST now returns the original instead of stacking a row —
  callers that treated the duplicates as "created" see one row where they may have seen two.
- Proposal replay returns the ORIGINAL approvalId, so the maker's polling loop attaches to the
  same approval it would have created.

## Compliance impact

Strengthens the delegated-access surface: a retried grant can no longer mint a second authority
row for the same party, and a retried proposal can no longer create a second executable
withdrawal instruction awaiting only owner approval. Both directions reduce fraud surface on
delegated money movement. No new obligation introduced.
