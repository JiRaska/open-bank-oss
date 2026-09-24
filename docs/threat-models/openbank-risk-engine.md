<!-- SPDX-License-Identifier: Apache-2.0 -->
# Threat model — openbank-risk-engine

- **Status:** Phase 0 (ADR-0313 phase 0 and D4, ADR-0314 D1/D2/D3/D6/D7), sandbox only
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

## STRIDE analysis

| Threat | Control | Residual risk |
|---|---|---|
| Spoofing — a caller triggers runs | `@RolesAllowed` on `RiskResource`, `@Authorize` per action, and `risk_rest_ext.rego` grants `risk.snapshot.create` to real staff only; the shared service-account is denied despite holding ROLE_OPERATOR | Reads go through the base `operator-read-any` rule, which a per-service extension cannot narrow |
| Tampering — positions that do not match the books are published | `TieOut` compares every (GL account, currency) exactly; an UNTIED run's positions answer 409 (`UntiedSnapshotException`); the migration's check constraint forbids a TIED_OUT row carrying mismatches | GL-level positions for accounts without a sub-ledger breakdown tie out by construction in phase 0 — the gate has teeth only on deposit-control accounts until lending/treasury positions arrive |
| Tampering — a falsified curve set skews every PV and projection | `risk.curve-set.create` is granted by `risk_rest_ext.rego` to real staff only (shared service-account denied); quotes are validated (known index, tenor ≤ 1Y, fraction-sized rate, no duplicate tenor); a set is immutable once stored and every cash-flow response names the `curveSetId` and its `provenance`, so a figure can be traced to the set and uploader window it came from | No second-person approval and no market-data cross-check: one operator can store an implausible curve. Caller identity is not yet recorded on the set row |
| Tampering — a figure computed on assumptions nobody can name | Behavioural assumptions live in a named, versioned `BehaviouralModel` in code, reported (`model.id`, `model.version`) on every cash-flow response; a currency with no discounting curve is returned as `unpriced` with a null PV, never zero | A model change is a code change reviewed as such; there is no model-approval workflow yet (ADR-0313 model risk) |
| Tampering — forged FX fixing | Kafka mTLS + topic ACLs: only fx-service can write the topic; `FxFixingConsumer` validates every required field and first-writer-wins on (source, fixingDate, currency) | A compromised fx-service could publish a false first fixing for a day |
| Repudiation — which ledger state a run described | `InputHash` is SHA-256 over a canonical sorted serialisation of both ledger reads, stored with `recorded_at` and `provenance`; the natural key (as_of, input_hash) makes a replay return the same run | The raw ledger responses are not retained, only their hash |
| Information disclosure — per-customer balances | No ingress (ClusterIP only), NetworkPolicy allow-list, mTLS to ledger, positions served only to authorized staff | Positions are served in full to any operator; field-level minimisation is future work |
| Denial of service — a stalled consumer or a flood of runs | Malformed fixing events are acked and counted; failed writes retry then park in `openbank.dlq.risk-engine.fx-fixing-in` rather than stopping the channel; replayed snapshot requests are idempotent; curve uploads are bounded by the 1 MB body limit and tenors ≤ 1Y | Snapshot creation and cash-flow projection are synchronous and unthrottled beyond the fleet rate limiter; a projection is O(deposits × 61 flows) per request, cached nowhere |
| Elevation of privilege | `authz.enforce=true` in the Deployment with the OPA sidecar; the service has no write grant on any other service | Shared M2M client identity until a per-service Keycloak client exists |

## Invariants

1. No position of an UNTIED run is ever returned by the API.
2. The ledger is only ever read, never written.
3. A fixing row, once stored, is never overwritten by a redelivery.
4. No cash flow or PV is served for an UNTIED run, and none is served under a curve set of a
   different as-of date than the run.
5. A stored curve set is never modified; reads rebuild curves from the stored pillars.

## Change log

- **2026-09-24** — Curve sets and snapshot cash flows (ADR-0313 D4, ADR-0314 D6, #10618). New
  operator WRITE endpoint `POST /api/v1/risk/curve-sets` (action `risk.curve-set.create`, real
  staff only, as snapshot creation) and reads `GET /api/v1/risk/curve-sets/{id}` and
  `GET /api/v1/risk/snapshots/{id}/cash-flows` (base `operator-read-any`). Flyway V2 adds
  `curve_set`, `curve_set_quote`, `curve_set_pillar`. Added the curve-tampering and
  unnamed-assumption rows and invariants 4–5.
