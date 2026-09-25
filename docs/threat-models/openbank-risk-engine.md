<!-- SPDX-License-Identifier: Apache-2.0 -->
# Threat model — openbank-risk-engine

- **Status:** Phase 0 (ADR-0313 phase 0 and D4, ADR-0314 D1/D2/D3/D4/D6/D7), sandbox only
- **Last reviewed:** 2026-09-24
- **Owner:** risk-engine CODEOWNERS
- **Related ADRs:** ADR-0030, ADR-0034, ADR-0137, ADR-0313, ADR-0314

## Scope and assets

A read-only balance-sheet risk engine. It pulls the ledger trial balance and the deposit-control
sub-ledger balances for an as-of date, builds contract-level positions, ties them out to the
trial balance with zero tolerance, and stores the run manifest, its mismatches and its positions
in its own CNPG database. It consumes central-bank FX fixings from `openbank.fx.fixing.published`.
It never writes the ledger, moves no money and is outside every payment path, so it is not a
money-path service.

Assets: per-customer balances (sub-account id + amount, confidential), the run manifests that are
the evidence behind future risk figures, the FX reference rates, and the operator-uploaded yield-
curve sets every present value and floating-rate projection is computed from. Cash flows are
derived per request and never stored (ADR-0314 D6).

## Trust boundaries

1. An authenticated operator calls `POST /api/v1/risk/snapshots` with an as-of date. The date is
   the only input; everything else is read from the ledger.
2. The service calls ledger-service over the private-CA mTLS listener (8443, client certificate
   `risk-internal-tls`) with the shared M2M client-credentials token; ledger authorizes it as
   `ledger.read`.
3. An authenticated operator uploads a curve set with `POST /api/v1/risk/curve-sets`: an as-of
   date, a provenance label, a free-text source and money-market quotes per index. It is the
   service's only write endpoint whose payload is data rather than a date, and the numbers are
   taken on trust — there is no market-data feed to check them against yet.
4. FX fixings arrive over mTLS Kafka under the `risk-engine` KafkaUser, which holds Read on the
   one fixing topic and Write only on its own dead-letter topic.
5. (ADR-0314 D4) The service reads lending-service's loan book, `GET /api/v1/lending/loan-book`,
   with the shared M2M token; lending authorizes it as `lending.book.read` for that one service
   account. This is a PULL, a deliberate deviation from ADR-0314 D1 (positions from events): no
   event carries a loan's remaining schedule. The response is hashed into the run's input hash.
   **Off in the deployed sandbox** (`OPENBANK_RISK_LENDING_ENABLED=false`): lending serves no
   mTLS listener and ASVS V9.1 forbids a new plaintext edge, so the edge does not exist yet.

## STRIDE analysis

| Threat | Control | Residual risk |
|---|---|---|
| Spoofing — a caller triggers runs | `@RolesAllowed` on `RiskResource`, `@Authorize` per action, and `risk_rest_ext.rego` grants `risk.snapshot.create` to real staff only; the shared service-account is denied despite holding ROLE_OPERATOR | Reads go through the base `operator-read-any` rule, which a per-service extension cannot narrow |
| Tampering — positions that do not match the books are published | `TieOut` compares every (GL account, currency) exactly; an UNTIED run's positions answer 409 (`UntiedSnapshotException`); the migration's check constraint forbids a TIED_OUT row carrying mismatches | GL-level positions for accounts without a contract breakdown tie out by construction — the gate has teeth on deposit control and, with the loan-book read on, on Loans Receivable 1200–1203; interest receivable, allowance and treasury accounts are still GL-level |
| Tampering — a falsified curve set skews every PV and projection | `risk.curve-set.create` is granted by `risk_rest_ext.rego` to real staff only (shared service-account denied); quotes are validated (known index, tenor ≤ 1Y, fraction-sized rate, no duplicate tenor); a set is immutable once stored and every cash-flow response names the `curveSetId` and its `provenance`, so a figure can be traced to the set and uploader window it came from | No second-person approval and no market-data cross-check: one operator can store an implausible curve. Caller identity is not yet recorded on the set row |
| Tampering — a figure computed on assumptions nobody can name | Behavioural assumptions live in a named, versioned `BehaviouralModel` in code, reported (`model.id`, `model.version`) on every cash-flow response; a currency with no discounting curve is returned as `unpriced` with a null PV, never zero | A model change is a code change reviewed as such; there is no model-approval workflow yet (ADR-0313 model risk) |
| Tampering — lending's loan book disagrees with the ledger, or is malformed | Every loan is a LOAN position on its Loans Receivable account and Loans Receivable is never ALSO carried at GL level, so a missing, extra or mis-sized loan is a tie-out break (UNTIED, 409); a loan whose outstanding differs from its remaining schedule, or that floats on an unknown index, fails the run with 502 and nothing is stored; a book for another as-of date is refused | Trusts lending's GL code per loan (checked only through the tie-out); a compromised lending could present a book that ties out yet misstates terms (rate, dates) that the ledger does not carry |
| Tampering — forged FX fixing | Kafka mTLS + topic ACLs: only fx-service can write the topic; `FxFixingConsumer` validates every required field and first-writer-wins on (source, fixingDate, currency) | A compromised fx-service could publish a false first fixing for a day |
| Repudiation — which ledger state a run described | `InputHash` is SHA-256 over a canonical sorted serialisation of both ledger reads and, when read, every field of the loan book, stored with `recorded_at` and `provenance`; the natural key (as_of, input_hash) makes a replay return the same run | The raw ledger responses are not retained, only their hash |
| Information disclosure — per-customer balances and loan terms | No ingress (ClusterIP only), NetworkPolicy allow-list, mTLS to ledger, positions and instruments served only to authorized staff; a loan's counterparty is stored as lending's opaque party UUID, never a name | Positions and instruments (loan terms, schedule, IFRS 9 stage) are served in full to any operator; field-level minimisation is future work |
| Denial of service — a stalled consumer or a flood of runs | Malformed fixing events are acked and counted; failed writes retry then park in `openbank.dlq.risk-engine.fx-fixing-in` rather than stopping the channel; replayed snapshot requests are idempotent; curve uploads are bounded by the 1 MB body limit and tenors ≤ 1Y | Snapshot creation and cash-flow projection are synchronous and unthrottled beyond the fleet rate limiter; a projection is O(deposits × 61 flows) per request, cached nowhere |
| Elevation of privilege | `authz.enforce=true` in the Deployment with the OPA sidecar; the service has no write grant on any other service | Shared M2M client identity until a per-service Keycloak client exists |

## Invariants

1. No position of an UNTIED run is ever returned by the API.
2. The ledger is only ever read, never written.
3. A fixing row, once stored, is never overwritten by a redelivery.
4. No cash flow or PV is served for an UNTIED run, and none is served under a curve set of a
   different as-of date than the run.
5. A stored curve set is never modified; reads rebuild curves from the stored pillars.
6. When the loan book is read, Loans Receivable is carried ONLY by loan instruments, never also as
   a GL-level position — so a loan lending does not report is a tie-out break, never absorbed.
7. No instrument of an UNTIED run is ever returned by the API.

## Change log

- **2026-09-25** — Bounded run and curve-set lists for the admin console (#10618): `GET /api/v1/risk/snapshots?limit=` and `GET /api/v1/risk/curve-sets?limit=` (1..100, default 25), on the existing read actions `risk.snapshot.read` / `risk.curve-set.read` (resource ""). Summaries only — no positions, instruments or mismatch rows. New caller: the admin-ui BFF, relaying the operator's own bearer (the `admin-ui` namespace was already admitted by the generated NetworkPolicy). No write path. Rollback: revert.
- **2026-09-25** — Department roles (#10618). ROLE_RISK and ROLE_FINANCE join the class-level `@RolesAllowed` of `RiskResource` and `CurveSetResource` (reads), and are granted `risk.snapshot.read` / `risk.curve-set.read` through `rules.yaml` `role_action_matrix` (reads only — the matrix has no service-account exclusion). ROLE_RISK alone joins the two writes (`risk.snapshot.create`, `risk.curve-set.create`) at method-level `@RolesAllowed` and in `risk_rest_ext.rego`, whose `not startswith(input.principal.id, "service-account-")` is unchanged: a curve set and a snapshot run remain inputs whose author must be a person. ROLE_FINANCE is read-only here. ROLE_TREASURY_DEALER / ROLE_TREASURY_APPROVER are declared in the realm and grant nothing. No new endpoint, edge or data. Rollback: revert.
- **2026-09-24** — Loan-book read switched ON in the deployed sandbox (ADR-0314 D4, #10618). `LENDING_SERVICE_URL=https://lending-service.lending.svc:8443` over lending's private-CA mTLS listener (#10732), client auth with the existing `risk-internal-tls` pair in a dedicated `lending-authority` TLS bucket; `OPENBANK_RISK_LENDING_ENABLED=true`. New egress edge risk -> lending (the generated lending NetworkPolicy admits the `risk` namespace). The read stays a single GET authorised by OPA action `lending.book.read`; no write path. Loans Receivable now ties out per loan in sandbox runs, so a disagreement between lending and ledger surfaces as an UNTIED run — the intended signal, not a defect of this service. Rollback: set the env back to `false`.
- **2026-09-24** — Loans as contract-level instruments (ADR-0314 D4, #10618). New outbound edge
  to lending-service's READ-ONLY `GET /api/v1/lending/loan-book` (trust boundary 5), off in the
  deployed sandbox until lending has an mTLS listener. New read `GET
  /api/v1/risk/snapshots/{id}/instruments` (base `operator-read-any`; 409 for UNTIED). Flyway V3
  adds `snapshot_instrument`, `snapshot_instrument_installment` and the LOAN position kind. The
  cash-flow endpoint now expands loans (inflows). The loan book is part of the input hash.
  **Deviation from ADR-0314 D1, recorded:** loans are pulled, not consumed from events, because no
  event carries a remaining schedule. Added the loan-book tampering row and invariants 6–7.

- **2026-09-24** — Curve sets and snapshot cash flows (ADR-0313 D4, ADR-0314 D6, #10618). New
  operator WRITE endpoint `POST /api/v1/risk/curve-sets` (action `risk.curve-set.create`, real
  staff only, as snapshot creation) and reads `GET /api/v1/risk/curve-sets/{id}` and
  `GET /api/v1/risk/snapshots/{id}/cash-flows` (base `operator-read-any`). Flyway V2 adds
  `curve_set`, `curve_set_quote`, `curve_set_pillar`. Added the curve-tampering and
  unnamed-assumption rows and invariants 4–5.
