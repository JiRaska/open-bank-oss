# openbank-pension-service — Documentation

> **What it is:** the participant side of the pension platform: the `PensionContract` lifecycle, strategy elections, contribution schedule, beneficiaries, and evaluation of effective-dated jurisdiction packs ([ADR 0334](../../../../docs/adr/0334-pension-fund-platform.md)). **What it is NOT (slice S1):** a unit register or a payment engine. It collects no contribution and pays nothing out yet.

This documentation is published by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service, [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)).

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / Hibernate Reactive Panache / PostgreSQL (`pension-db`, CNPG)
- **Ports:** 8171 (app), 8090 (management)
- **Events:** none yet.
- **Auth:** `ROLE_API`, `ROLE_OPERATOR` or `ROLE_ADMIN`. A caller without a staff role must send `X-Customer-Party-Id` and only sees that participant's contracts; another party's contract answers 404. Staff read only.
- **Idempotency:** `Idempotency-Key` is required on every POST.
- **Jurisdiction packs:** `jurisdiction-packs/*.json`, loaded and validated at startup. A contract pins the pack version in force on its creation date. The CZ/DPS and CZ/DIP packs are reference data pending legal review.

## API

| Method & path | What it does |
|---|---|
| `GET /api/v2/pension/contracts/{id}` | Read a contract with its strategy history |
| `GET /api/v2/pension/transfers` | The participant's own transfers, in and out (pending by default) |
| `POST /api/v2/pension/transfers/{id}/withdrawal` | Withdraw a transfer-out (or the consent to one) before it is valued — SCA-signed |
| `PUT /api/v2/pension/contracts/{id}/strategy` | Elect or change the strategy (history kept) |
| `POST /api/v2/pension/contracts/{id}/suspend` / `resume` | Pause / resume contributions |
| `POST /api/v2/pension/contracts/{id}/incentive-evaluation` | Incentives of the pinned pack for one contribution |
| `GET /api/v2/pension/contracts` | The participant's own contracts (staff: by status, optional free-text `q`) |
| `POST /api/v2/pension/simulations` | Illustrative projection per strategy (not advice) |
| `PUT /api/v2/pension/contracts/{contractId}/exit/payouts/{payoutId}/account` | SCA-bound, held (3 days), notified payout-account change |
| `GET /api/v2/pension/operator/payouts`, `GET /api/v2/pension/death-claims` | Staff queues |
| `GET /api/v2/pension/reporting/participant-aggregates` | Period aggregates for the ČNB returns: counts and sums only, staff-reported (#12425) |

Activation happens only through the onboarding workflow (signed application, cooling-off, first
contribution or transfer-in); termination is the S5 quote/sign flow under `/exit`. Every POST
requires `Idempotency-Key` and a retry is answered from the first response.

## Payout sweep heartbeat

The payout recovery sweep registers workflow `pension-payout-schedule-sweep` at startup. Its expected interval comes from `openbank.pension.payout-sweep.every` (default `1h`), the same setting used by the scheduler. A completed sweep records a heartbeat even when there are no overdue installments. A failed repository read or failed workflow restart does not record success.

`openbank_workflow_success_recorded` distinguishes a registered workflow that has not completed from a successful run. `openbank_workflow_last_success_age_seconds` is seeded at registration, so its startup value alone is not proof that a sweep ran. The existing overdue-installment gauge measures workload; the heartbeat measures execution.

An `off` or `disabled` interval disables the schedule and publishes no workflow heartbeat.

## Staff contract search

Staff (`ROLE_OPERATOR`/`ROLE_ADMIN`) can pass a free-text `q` on
`GET /api/v2/pension/contracts`. It matches, case-insensitively, a fragment of the contract
id or participant party id, the product line, the participant birth date — both the ISO
form (`1985-05-05`) and the digit-only form (`850505`) — and the payment reference
(variabilní symbol) via `pension_contract_references`. The response carries
`participantBirthDate` so same-named participants can be told apart. A participant caller
without a staff role cannot use `q`; the parameter is ignored and only that party's own
contracts are returned.

`GET /api/v2/state-contributions/deadlines` returns the filing-deadline view of the state
contribution return for a reference date. The resource calls a no-argument service method;
resolving "today" inside the service (a default argument read on the CDI client proxy)
NPE'd, so the method resolves the clock on the real bean and only the scheduler passes an
explicit date.

### Multi-currency return reports

The monthly state-contribution return report (ZDPS §18(4), `POST .../state-contribution/return-reports`)
is filed **once per currency**. Every DUE return is grouped by its ISO 4217 currency and each group
goes out as its own report and file (`VR-<month>-<currency>-<id>.txt`). Amounts of different
currencies are never added together or converted: the report header names its currency
(`H;cz-mf-state-contribution-v1;RETURNS;<IČO>;<YYYY-MM>;<currency>;<count>;<total>`) and totals only
its own lines, computed with the libs `Money` type, which refuses a cross-currency sum. A return in
EUR therefore never blocks the CZK report. The response lists them in `reports` (`report` is the
first, kept for older clients); each carries `currency` and `total`, persisted on
`pension_return_reports` (`V16__return_report_currency`). A return whose currency is not a valid
ISO 4217 code is refused when it is created. The `openbank_pension_state_contribution_return_amount_total`
metric is labelled per currency; any value that is not ISO 4217 is recorded as `currency="INVALID"`,
so the label set stays bounded.

## Participant reporting for the ČNB returns

`GET /api/v2/pension/reporting/participant-aggregates` serves one period's aggregates for the
ČNB returns (#12425, ADR-0336 D4). It returns counts and sums only — no contract, party or
participant attribute. It counts contracts in force at period end by product line, age band and
status, and totals contributions (by source, including state contributions) and payouts. It is a
staff reporting read; it does not mutate contracts and is not a participant self-service endpoint.

Payout totals are measured by the persisted SETTLED transition time: the settlement record stores
the `settledAt` event time received from domestic-payment (migration `V15__payout_settlement_time`).
During a rolling deployment, older SETTLED events without `settledAt` remain in the consumer's DLQ
and are not silently re-dated.

### Additive internal TLS rollout

The GitOps deployment exposes TLS 1.3 on port 8443 alongside the existing HTTP
listener. A cert-manager Certificate supplies the server identity from the internal
CA; the directory-mounted PEM certificate and key reload every hour.

Deploy the Certificate and server listener first. Before switching a caller to
HTTPS, verify Certificate readiness, the service DNS SAN, a trusted TLS handshake,
and caller network-policy access to 8443. Keep HTTP during mixed-version operation;
removing it requires a separate caller inventory and rollout. If the new listener
fails, retain the existing caller URL and revert the additive server configuration.
This stage provides server authentication; it does not establish mutual TLS.

`ServerTlsIT` proves PEM loading, TLS 1.3 negotiation, rejection of an untrusted
certificate, and continued HTTP service with ephemeral test keys. Deployment
declarations and this test do not prove that a cluster Certificate is ready.

## Published DIP catalog regression

Run `./gradlew :openbank-pension-service:dipCrossServiceIT` from the repository root.
The task supplies `openbank.test.dip.fixture` from the product-catalog seed at
`openbank-product-catalog/src/main/resources/catalog-seeds/retirement/cz-illustrative-offerings.json`.
This synthetic fixture test does not verify a live catalog, publication or deployment.

## Repeatable synthetic demonstration

Run `python3 openbank-pension-service/demo/pension_demo.py run` from the repository root.
The runbook at `openbank-pension-service/demo/README.md` describes the presenter sequence,
reset and evidence. The runner creates separate disposable Alpha/Beta pension-service test
contexts, then verifies the fund service independently, and writes an HTML/JSON report under
`build/pension-demo/`. Failed, skipped or stale test results cannot produce a passed report.

The Gradle property `-PpensionDemoCompany=alpha|beta` selects only test fixtures; ordinary test
and runtime defaults remain unchanged. This demonstration needs no real provider identity or
production ownership approval. It uses synthetic data and simulated external collaborators.
It does not prove simultaneous deployment isolation with real OIDC/OPA tokens, real settlement
or a fully connected production money path. Those capabilities remain in #12472 and #12479.

## Contract testing (consumer pacts)

The consumer pact test `PensionFundPactConsumerTest` compiles in the isolated
`consumerPactTest` source set, which by design compiles only `**/*PactConsumerTest.kt`
plus helpers each service explicitly includes (CI isolation, see the
`openbank.quarkus-service` convention). Its in-memory fund-order plan backend
(`InMemoryFundOrderPlans`) therefore must stay listed in the
`consumerPactTest` include in `build.gradle.kts`; without it,
`compileConsumerPactTestKotlin` fails with an unresolved reference.

## Operational metrics

The management metrics endpoint exposes pension transition and outcome counters, including
SCA consumption, funding, state contributions and payout-account changes. Counters describe
observed application outcomes; they are not evidence of settlement on an external rail.
State gauges report contract and aggregate statuses, queue sizes and the oldest queued age.
The `pension-state-gauges` job refreshes them every 60 seconds by default
(`openbank.pension.state-gauges.every`). A new pod publishes no state series until its first
successful snapshot. A failed refresh leaves the previous values in place; check workflow
liveness before treating those values as current. Labels use bounded categories, never party
or contract identifiers.

## Annuity partner wire format

Requests to the reference annuity partner omit absent optional properties and
null-valued map entries; they do not send JSON null as an alternative value.
Partner responses may add fields without breaking parsing; decimal amounts
and date handling retain the protocol contract. The protocol contract tests
validate outgoing requests and parse the documented offer examples.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.
