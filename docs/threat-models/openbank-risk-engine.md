<!-- SPDX-License-Identifier: Apache-2.0 -->
# Threat model — openbank-risk-engine

- **Status:** Phase 0 skeleton (ADR-0313 phase 0, ADR-0314 D1/D2/D3/D7), sandbox only
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
the evidence behind future risk figures, and the FX reference rates.

## Trust boundaries

1. An authenticated operator calls `POST /api/v1/risk/snapshots` with an as-of date. The date is
   the only input; everything else is read from the ledger.
2. The service calls ledger-service over the private-CA mTLS listener (8443, client certificate
   `risk-internal-tls`) with the shared M2M client-credentials token; ledger authorizes it as
   `ledger.read`.
3. FX fixings arrive over mTLS Kafka under the `risk-engine` KafkaUser, which holds Read on the
   one fixing topic and Write only on its own dead-letter topic.

## STRIDE analysis

| Threat | Control | Residual risk |
|---|---|---|
| Spoofing — a caller triggers runs | `@RolesAllowed` on `RiskResource`, `@Authorize` per action, and `risk_rest_ext.rego` grants `risk.snapshot.create` to real staff only; the shared service-account is denied despite holding ROLE_OPERATOR | Reads go through the base `operator-read-any` rule, which a per-service extension cannot narrow |
| Tampering — positions that do not match the books are published | `TieOut` compares every (GL account, currency) exactly; an UNTIED run's positions answer 409 (`UntiedSnapshotException`); the migration's check constraint forbids a TIED_OUT row carrying mismatches | GL-level positions for accounts without a sub-ledger breakdown tie out by construction in phase 0 — the gate has teeth only on deposit-control accounts until lending/treasury positions arrive |
| Tampering — forged FX fixing | Kafka mTLS + topic ACLs: only fx-service can write the topic; `FxFixingConsumer` validates every required field and first-writer-wins on (source, fixingDate, currency) | A compromised fx-service could publish a false first fixing for a day |
| Repudiation — which ledger state a run described | `InputHash` is SHA-256 over a canonical sorted serialisation of both ledger reads, stored with `recorded_at` and `provenance`; the natural key (as_of, input_hash) makes a replay return the same run | The raw ledger responses are not retained, only their hash |
| Information disclosure — per-customer balances | No ingress (ClusterIP only), NetworkPolicy allow-list, mTLS to ledger, positions served only to authorized staff | Positions are served in full to any operator; field-level minimisation is future work |
| Denial of service — a stalled consumer or a flood of runs | Malformed fixing events are acked and counted; failed writes retry then park in `openbank.dlq.risk-engine.fx-fixing-in` rather than stopping the channel; replayed snapshot requests are idempotent | Snapshot creation is synchronous and unthrottled beyond the fleet rate limiter |
| Elevation of privilege | `authz.enforce=true` in the Deployment with the OPA sidecar; the service has no write grant on any other service | Shared M2M client identity until a per-service Keycloak client exists |

## Invariants

1. No position of an UNTIED run is ever returned by the API.
2. The ledger is only ever read, never written.
3. A fixing row, once stored, is never overwritten by a redelivery.
