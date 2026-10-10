<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-card-processing-service

- **Date:** 2026-10-03
- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). Money-path service from its first commit.
- **Service ADR:** [ADR-0283](../adr/0283-card-platform-scheme-agnostic-capability-ports.md) (card platform — scheme-agnostic capability ports and card-processing as a bounded context)

## 1. Scope & purpose

The card money path: it takes an authorisation from an acquirer, measures what the card has already
spent, asks card-issuance for the decision (ADR-0194 D3), holds the funds an approval implies,
applies clearing presentments against that hold, releases what is never presented, and posts cleared
spend to the books on the `CARD` rail (ADR-0103).

**Not** an issuer-processor: no 3-D Secure ACS, no PIN or HSM operation, no scheme connection. Those
stay behind the processor port (ADR-0283 D2, inherited from the superseded ADR-0190), whose only
binding in this repository is the sandbox acquirer.

**No PAN, no card credential, no CVV** is accepted, stored or logged. A card is referenced by its
card-issuance id. That is a security property, not an implementation detail: it is what keeps this
service outside the cardholder-data environment, and the migration says so where the table is
defined.

## 2. Data flow (DFD)

```
[Acquirer / sandbox]--OIDC-->(POST /api/v1/card-authorizations)-->[card-processing]
                                                                       |
       (1) counted spend  <---------------------------------- [(Postgres: card_authorizations)]
       (2) decision       --OIDC------> [card-issuance  POST /cards/{id}/authorizations]
       (3) shadow score   --OIDC------> [fraud-service  POST /fraud/score]        (verdict ignored)
       (4) on clearing    --OIDC------> [transaction-service POST /transactions]  (rail=CARD)
                                                                       |
                                                            [(card_outbox)]--outbox-->[Kafka]
                                                               card.authorised.v1
                                                               card.declined.v1
                                                               card.cleared.v1
                                                               card.hold_released.v1
```

## 3. Authn/Authz

- Every REST endpoint requires an OIDC bearer token and `ROLE_API`, `ROLE_OPERATOR` or `ROLE_ADMIN`,
  plus an `@Authorize` action evaluated by the OPA sidecar (ADR-0034): `cardprocessing.authorize`,
  `.clear`, `.reverse`, `.read`, and `.simulate` (ROLE_ADMIN only).
- Outbound calls authenticate as the service (`openbank-services` client credentials), because
  card-processing asks about a card the caller does not own.
- `AUTHZ_ENFORCE=false` today, joining the #3679 advisory cohort. Stated plainly: the OPA decision is
  currently advisory here, so the effective control is the role check.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | A caller impersonates an acquirer and authorises spend on someone's card | OIDC bearer + role + OPA action; the card's owner is resolved from card-issuance, never taken from the request |
| **T**ampering | Replaying an authorisation to take a second hold | `idempotency_key` is UNIQUE in the database, and the use case returns the first authorisation unchanged |
| **T**ampering | A repeated presentment of the same clearing (acquirer retry, duplicated network delivery, or a deliberate replay) applied again while it still fits inside the remaining hold — a second debit of the cardholder | Each applied clearing is recorded in `card_clearings` under UNIQUE `(authorization_id, idempotency_key)`, in the same transaction as the hold decrement and the event; concurrent clearings under different keys are serialised by an optimistic lock (`version`) and re-evaluated against the remaining hold; the ledger key is scoped per authorisation. Same key + same body replays (no second decrement, event or ledger posting); same key + different body is 409 `IDEMPOTENCY_KEY_REUSED`; concurrent duplicates are decided by the constraint, so exactly one commits (§6, 2026-10-03) |
| **T**ampering | A clearing for more than was authorised | Refused by `AuthorizationLifecycle.clear` **and** by a CHECK constraint on the table — the application rule alone can be forgotten by a future writer |
| **R**epudiation | "I was charged twice for one purchase" — a duplicate presentment that cannot be told apart from two real ones | Every applied clearing is a row keyed by the acquirer's clearing key with its amount, currency, request fingerprint and `applied_at`, so "one presentment delivered twice" and "two presentments" are distinguishable after the fact |
| **R**epudiation | "I never made that purchase" / "my card was refused and I was not told why" | Every decision is a row and an event, including declines, carrying the issuer's own reason name verbatim |
| **I**nformation disclosure | Card data leaking into logs or events | No PAN/CVV is accepted or stored; events carry the card id, amount, merchant and category only |
| **D**enial of service | An acquirer floods the authorisation endpoint | Short client timeouts (3 s issuer, 2 s fraud), bulkheads on the dispatcher; the endpoint itself is not rate limited today — see §5 |
| **E**levation of privilege | The sandbox acquirer used in a real environment to move money | Disabled by default, ROLE_ADMIN only, and answers **404** when disabled, so it is indistinguishable from an endpoint that was never deployed |

## 4a. The two ways card spend can go missing (D1) — STRIDE supplement

This is the failure the service exists to prevent, and both halves are silent by nature.

1. **A clearing that never reaches the books.** The posting happens after the clearing has committed
   and is deliberately not rolled back on failure — the acquirer has asserted the fact and refusing
   to record it would lose it. So a failed posting leaves money spent and unbooked. Mitigated by
   making the outcome a three-valued enum (`POSTED | SKIPPED_DISABLED | FAILED`), never a boolean,
   counted per value in `openbank_card_processing_ledger_postings_total`. The precedent is exact:
   the push-notification fan-out returned `success = true` for a skipped send and reported
   deliveries that never left the process (#4348). **The alert that matters is
   `SKIPPED_DISABLED > 0`, not an error rate** — the quiet outcome is the dangerous one.
2. **A hold that is never released.** An approved authorisation nobody presents against would freeze
   the customer's funds for ever, with no error anywhere. Mitigated by `expires_at` on every
   authorisation and a sweep that releases past it; the sweep is a `suspend fun` because a plain
   `@Scheduled` method has no Vert.x context and would abort silently (#2148), and its coverage is a
   profile that runs the real cron, not a direct call to the method.

## 4b. Spend counting (D2) — STRIDE supplement

The issuer endpoint takes the spend figures as arguments. Before this service existed it had no
caller, so those arguments were never anything; a limit evaluated against a number the requester
supplies is not a limit. The counters are therefore computed here, in the database, over the
authorisation rows — not held in a running-total column, because a stored counter that drifts from
the rows is invisible: both numbers look plausible.

Two consequences a reviewer should check:

- A hold counts in **full** until it clears, so the unpresented remainder cannot be spent twice.
- The windows are the **accounting** day and month (ADR-0207), not UTC midnight. A limit is a promise
  to a customer in a country; deriving it from UTC puts two hours of every summer evening in the
  wrong day.

## 4c. Failing closed (D3) — STRIDE supplement

If card-issuance cannot be reached, the authorisation is **declined**, under its own reason
`ISSUER_UNAVAILABLE`. Two properties matter. An issuer that cannot evaluate its controls must not let
spend through — otherwise "payments abroad off" holds only while the network is healthy. And the
unavailability reason is never one of the policy's own reasons: in a dispute, in a metric and in the
customer's app, "the issuer was down" and "you turned gambling off" must not look alike.

This is the opposite of VoP's deliberate fail-open (ADR-0171) because the two answer different
questions — VoP warns, this authorises.

## 4d. Network tokens and chargebacks (ADR-0283 phase 3) — STRIDE supplement

`POST /api/v1/card-tokens`, `POST /api/v1/card-disputes` and `POST /api/v1/card-disputes/{id}/evidence`
each make a call to a card network that is NOT idempotent at the network: asking twice mints a second
wallet credential, opens a second chargeback or files the evidence twice.
`POST /api/v1/card-tokens/{tokenReference}/status` and `POST /api/v1/card-disputes/{id}/refresh` reach
the network too and take the same reservation: a replayed SUSPEND arriving after a RESUME would
silently re-suspend a credential the customer was just given back.

| Threat | STRIDE | Mitigation |
|---|---|---|
| Two concurrent requests with one `Idempotency-Key` both reach the network (duplicate credential / chargeback), the loser 500s | T, D | `LifecycleIdempotencyRepositoryImpl.reserve` — an `INSERT ... ON CONFLICT DO NOTHING` into `card_lifecycle_idempotency` committed BEFORE the network call; the loser replays the winner (`Reservation.Completed`) or gets 409 `IDEMPOTENCY_REQUEST_IN_PROGRESS`. Completion flips the row in the same transaction as the result row and its outbox event (`completeInTransaction`). Proven by `CardLifecycleIdempotencyIT` (two requests released by one latch, network held open). |
| A token status change or dispute refresh retried (or double-clicked) reaches the network twice | T, D | Both now require `Idempotency-Key` (absent → 400, before any lookup) and reserve it exactly like provisioning (`TOKEN_STATUS_CHANGE`, `DISPUTE_REFRESH`); a refresh that moved nothing, or of a CLOSED case, completes the reservation standalone (`LifecycleIdempotencyPort.complete`) so a retry replays the stored case. Proven by `CardLifecycleIdempotencyIT` (latch race, replay, reuse). |
| A crash after the network acted, then a retry, acts twice | T | A PENDING reservation never expires; it is released only when the network was provably not asked or refused. A stuck key answers 409 until an operator reconciles it (`ix_card_lifecycle_idempotency_pending`). |
| The same key replayed for a different request returns someone else's result | I, T | The reservation stores a SHA-256 request fingerprint; a mismatch is 409 `IDEMPOTENCY_KEY_REUSED`. |
| A network token minted for a blocked/suspended/expired card — a working credential for a card the bank stopped | E, S | `CardTokenService.refuseBeforeNetwork` refuses any card whose card-issuance `status` is not `ACTIVE` (an absent status counts as not active), 409 `CARD_NOT_ACTIVE`, before the network is asked. Pinned by the consumer pact (`status` type matcher). |
| card-issuance unreachable presented as "no such card" | R | `CardIssuerUnavailableException` → 503 `ISSUER_UNAVAILABLE`, fails closed like authorisation (§4c). |
| A chargeback filed in a currency other than the transaction's, compared as raw minor units | T | `CardDisputeService.eligibility` compares `CurrencyCode` then `Money`; a mismatch is 409 `CURRENCY_MISMATCH` and the network is never asked. |
| A late or wrong network read flips a closed case's outcome (WON → LOST after funds moved) | T, R | Closed cases are terminal in `refreshStatus`: never mutated; a disagreement is counted on `openbank_card_dispute_terminal_mismatches_total` and logged for investigation. |
| Evidence silently overwritten, so the file a scheme ruled on cannot be reconstructed | R | `card_dispute_evidence` is append-only, one row per filing, written in the filing's transaction; `GET .../evidence` reads it. |
| A token the network holds never appears in the bank's record, or a token the network lost disappears from view | R | A NETWORK read adopts unknown tokens into the mirror (`adoptNetworkSeen`, `ON CONFLICT (token_reference) DO NOTHING`, stable id) and flags mirror-only tokens `absentAtNetwork` instead of dropping them. |

## 5. Residual risks / assumptions

- **No rate limit on the authorisation endpoint.** Acquirer traffic is authenticated and bounded by
  the scheme in reality; in this repository the sandbox is the only caller. A real processor binding
  should add one, and this line is what says it is missing rather than handled.
- **Fraud scoring is shadow only**, like every other rail (ADR-0084, #4403). No fraud verdict
  declines a card transaction today. Nothing here should be read as fraud enforcement. A shadow
  control fails open by design, so its failure must be *visible* rather than quiet: every attempt is
  counted on `openbank_card_processing_fraud_scores_total{outcome}` and a failure is logged at WARN
  (at most once a minute). `outcome="FAILED"` climbing while `SCORED` stays flat is the signal that
  scoring has stopped, which is exactly what went unseen until #12064.
- **`AUTHZ_ENFORCE=false`** — the OPA decision is advisory; the role check is the live control.
- **The ledger posting is not two-phase.** A posting that fails is visible and retriable by
  operations, but there is no automatic compensation. Adding one needs the processor binding's own
  reconciliation file, which does not exist yet.
- **A stuck PENDING idempotency reservation blocks its key indefinitely.** Deliberate (expiring it
  could repeat a network action); there is no automated reconciliation yet, only the partial index
  an operator query uses.
- **The authorisation path still answers an unreachable card-issuance lookup with a generic 500**;
  only token provisioning maps it to 503 today.
- **No PAN today, by design.** If a real processor is ever bound, the cardholder-data environment
  question reopens — HSM/P2PE and full PCI DSS 4.0.1 scope — and ADR-0283 D7 says that is a separate
  decision, not a config change.

## 6. Change log

- **2026-10-08** — **OPA authorization ENFORCED (`AUTHZ_ENFORCE=true`, #12325).** Every `@Authorize` method now blocks on a deny (403) and fails closed when the PDP is unreachable (503); before this the decisions were advisory. Checked with `opa eval` against the deployed `card-processing-opa-bundle` ConfigMap (identical to the repo copy): staff `ROLE_OPERATOR`/`ROLE_ADMIN` may authorise, clear, reverse, read and work the token and dispute desks; `cardprocessing.simulate` is `ROLE_ADMIN` only; `ROLE_VIEWER` and `ROLE_COMPLIANCE` read only; a customer bearer, an AI agent, an anonymous caller and every `ROLE_API` service account (including `openbank-services` as the deployed realm defines it) are denied. With `card_processing_rest_ext.rego` removed, the operator write and the viewer read cells turn DENY. Live evidence: no `openbank_authz_decisions_total` series for this service over the 8-day Prometheus window and no business-endpoint requests at all, and the NetworkPolicy admits only same-namespace pods (none calls this service) and admin-ui. Residual: the workload is a plain Deployment with no canary analysis. The acquirer/processor adapter the rego anticipates does not exist yet; when it lands as `service-account-openbank-services` holding only `ROLE_API` (the deployed realm), it will be DENIED and needs an identity-scoped rule naming its own client first. The write grant is still role-based: any identity holding `ROLE_OPERATOR` (including `service-account-openbank-edge` in the deployed realm, and `openbank-services` in the CI/docker realms) reaches `cardprocessing.authorize/clear/reverse`. Rollback: set `AUTHZ_ENFORCE` back to `"false"` and restore the allowlist entry in `check-authz-enforce-money-path.py`.
- **2026-09-05** — Initial threat model, authored with the service (ADR-0283 phase 1, #8809).
  Money-path from the first commit: `rules.yaml: money_path_services`, an SLO pair, a journey
  accountability entry and this document all land in the same PR as the code, rather than being
  retrofitted after the service is already carrying traffic.
- **2026-10-03** — Token and dispute lifecycle (#8864, before merge): §4d added. Idempotency keys are
  reserved in Postgres before any network call; tokens are refused for non-ACTIVE cards and fail
  closed (503) when card-issuance is unreachable; disputes must match the authorisation currency
  (compared as Money); closed cases are terminal on refresh; evidence history is append-only and
  idempotent; network-seen tokens are adopted into the mirror with stable ids and mirror-only ones
  are flagged `absentAtNetwork`.
- **2026-10-03** — Idempotency keys on token status change and dispute refresh (Refs #11996): the two
  POSTs #8864 left without a key now require `Idempotency-Key` and reuse the `card_lifecycle_idempotency`
  reservation (no new migration — `operation` is unconstrained `VARCHAR(32)`). Closes the gap the
  `idempotency-coverage-money-path` gate reports once the service is classified money-path.
- **2026-10-03** — Duplicate presentment (STRIDE-T/R). Found while documenting the service (#8858):
  the clearing request carried an `Idempotency-Key` that was never looked up, so a repeated
  presentment that still fitted inside the remaining hold was applied again — a second hold
  decrement, a second `card.cleared.v1` and a second debit of the cardholder; only the downstream
  ledger posting (`card-clearing:<key>`) was deduplicated. Fixed by V4 `card_clearings` (V3 is #8864's token and dispute lifecycle) with UNIQUE
  `(authorization_id, idempotency_key)` plus a fingerprinted lookup (libs `RequestFingerprint`,
  `IdempotencyKeyReusedException` → 409). Proven by `CardClearingIdempotencyIT` over real HTTP and
  Postgres; with the constraint removed, eight concurrent duplicates all applied. Same entry, same
  failure class, also fixed: (a) concurrent clearings under DIFFERENT keys on one authorisation were a
  lost update — both read one cleared amount and the second write overwrote the first, so the hold
  under-counted while every clearing still reached the ledger (with the lock removed, eight 10 000
  presentments on a 30 000 hold all applied and posted 80 000 while the hold recorded 10 000). Now an
  optimistic lock (`card_authorizations.version`, V4): the loser is re-evaluated once against the real
  remaining hold, then answers 409 `IDEMPOTENCY_REQUEST_IN_PROGRESS`; nothing applies past the
  authorised amount. (b) The ledger key `card-clearing:<key>` was not scoped per authorisation, so two
  authorisations sharing a clearing key collided in transaction-service and the second posting was
  deduplicated away; it is now `card-clearing:<authorizationId>:h:<base64url(SHA-256(key))>`. A
  security review of the first version of that fix found the remaining holes, all closed in the same
  PR: (c) the ledger key was verbatim when it fit and a `sha256-` digest otherwise — one namespace for
  two encodings, so a client could pick a clearing key spelling another key's digest; now ALWAYS the
  full 256-bit digest (fixed 96 chars, inside transaction-service's VARCHAR(100)). (d) Reversal and
  expiry wrote through a path with no version gate, so either could overwrite a concurrent clearing
  computed after its read; every write now carries the version, and the loser re-reads and
  re-evaluates once (reversal then 409, expiry skipped to the next sweep) — never a 500. (e) Only a
  `PersistenceException` was translated, so a commit-time optimistic-lock failure in another shape
  became a 500; the cause chain is now inspected for every write failure (proven by an IT that blocks
  the UPDATE on a row lock and moves the version underneath it). Residual: a replay does not re-attempt a `FAILED` ledger posting
  — operations re-drive it (§5, runbook). The 2026-09-05 entry says the service was listed in `rules.yaml: money_path_services`; it was
  not, and is added alongside this fix.
- **2026-10-03** — **SENT outbox rows are purged after 7 days** (ADR-0329, ADR-0327 D8). `card_outbox` kept every SENT
  row, payload included, indefinitely: `purgeSent` existed and nothing called it. The shared libs-runtime
  `OutboxSentRetentionJob` now deletes SENT rows whose `sent_at` is older than
  `openbank.outbox.retention.sent-days` (default 7) nightly in bounded batches; the v1 repository opts in by
  delegating `SentOutboxRetention` to `PanacheOutboxRetention`. PENDING, FAILED, DISPATCHING and DEAD rows are
  never touched. Information disclosure: shrinks the window in which a database read exposes past
  authorisation-event payloads. No new endpoint, caller or privilege; replaying an event older than 7 days now
  comes from the broker or audit-service, not this table.
- **2026-10-04** — Shadow fraud scoring had never scored a card authorisation (STRIDE-R, #12064).
  The fraud client sent `currencyCode` with no `rail`, both non-null in fraud-service's
  `ScoreFraudRequest`, so every call was refused with a 400; it also sent the amount in MINOR units
  and read `decision` where the provider answers `verdict`. The broad catch that keeps a shadow
  control from failing the authorisation logged it at debug level, so nothing surfaced. Fixed: the
  client mirrors the provider DTOs (`currency`, `rail = CARD`, major units, `verdict`), failures are
  WARN-logged with a rate limit, and consumer pacts to fraud-service and transaction-service (each
  with a recorded 401) are replayed by both providers on every PR — the CARD-rail ledger posting
  had no contract at all before this.
