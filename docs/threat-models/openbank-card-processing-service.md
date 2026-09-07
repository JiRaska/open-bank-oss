<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-card-processing-service

- **Date:** 2026-09-05
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
       (5) agent mandate  --OIDC------> [ap2-service    POST /ap2/verify]  (only when one is presented)
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
  `.clear`, `.reverse`, `.read`, `.token`, `.dispute`, and `.simulate` (ROLE_ADMIN only).
- The **agent mandate hop (5)** forwards the ACTING agent's id as `X-Agent-Id`, so ap2-service
  authorises `verify.mandate` as that agent rather than as this service (ADR-0193, ADR-0283 D6).
  Attributing it to the bank's own service account would make every agent's calls identical in the
  audit trail — the one record that says which agent tried to spend.
- Outbound calls authenticate as the service (`openbank-services` client credentials), because
  card-processing asks about a card the caller does not own.
- `AUTHZ_ENFORCE=false` today, joining the #3679 advisory cohort. Stated plainly: the OPA decision is
  currently advisory here, so the effective control is the role check.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | A caller impersonates an acquirer and authorises spend on someone's card | OIDC bearer + role + OPA action; the card's owner is resolved from card-issuance, never taken from the request |
| **T**ampering | Replaying an authorisation to take a second hold | `idempotency_key` is UNIQUE in the database, and the use case returns the first authorisation unchanged |
| **T**ampering | A clearing for more than was authorised | Refused by `AuthorizationLifecycle.clear` **and** by a CHECK constraint on the table — the application rule alone can be forgotten by a future writer |
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

**An agent's mandate fails closed the same way, in two distinguishable ways.** When an authorisation
carries an AP2 mandate (ADR-0283 D6) it is verified against THIS payment before card-issuance is
asked, and anything but a verified mandate declines:

- `AGENT_MANDATE_REJECTED` — ap2-service answered and the mandate does not authorise this payment.
  An answer about the agent.
- `AGENT_MANDATE_UNVERIFIABLE` — nobody answered, the policy denied the verification, or the body
  was unreadable. Not evidence about the agent at all.

Merging them into one reason would put "the agent exceeded its authority" and "we could not tell"
behind the same word in a dispute, a metric and the customer's app. The verification is sent the
ACQUIRER's amount, currency and merchant — never the mandate's own figures, which would ask the
verifier to compare a value with itself and pass by construction.

## 5. Residual risks / assumptions

- **The agent mandate hop trusts ap2-service's verdict entirely.** This service does not check the
  signature, hold a trust list or parse the JOSE encoding — a second verifier would be a second
  opinion about whether an agent may spend, free to disagree with the first. The consequence is that
  ap2-service's trust list is part of this path's attack surface (ADR-0193).
- **The acting agent's identity is self-asserted today.** `agentId` arrives in the authorisation
  request and is forwarded; per-agent OAuth binding is ADR-0181 phase 2, and until it lands the
  anonymous stand-in principal is what the policy can grant. The mandate's signature is what
  actually constrains the spend; the id is attribution, not authentication.

- **No rate limit on the authorisation endpoint.** Acquirer traffic is authenticated and bounded by
  the scheme in reality; in this repository the sandbox is the only caller. A real processor binding
  should add one, and this line is what says it is missing rather than handled.
- **Fraud scoring is shadow only**, like every other rail (ADR-0084, #4403). No fraud verdict
  declines a card transaction today. Nothing here should be read as fraud enforcement.
- **`AUTHZ_ENFORCE=false`** — the OPA decision is advisory; the role check is the live control.
- **The ledger posting is not two-phase.** A posting that fails is visible and retriable by
  operations, but there is no automatic compensation. Adding one needs the processor binding's own
  reconciliation file, which does not exist yet.
- **No PAN today, by design.** If a real processor is ever bound, the cardholder-data environment
  question reopens — HSM/P2PE and full PCI DSS 4.0.1 scope — and ADR-0283 D7 says that is a separate
  decision, not a config change.

## 6. Change log

- **2026-09-05** — Initial threat model, authored with the service (ADR-0283 phase 1, #8809).
  Money-path from the first commit: `rules.yaml: money_path_services`, an SLO pair, a journey
  accountability entry and this document all land in the same PR as the code, rather than being
  retrofitted after the service is already carrying traffic.
