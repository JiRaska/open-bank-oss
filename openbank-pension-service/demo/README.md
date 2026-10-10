# Repeatable synthetic pension demo

This is the first executable technical demonstration of the target pension platform. It reuses
production HTTP handlers and workflows with disposable PostgreSQL databases. It is not yet a
complete browser presentation or a fully connected deployment of all banking services.

## Run

Prerequisites: Python 3.10+, the repository's supported JDK/Gradle environment, and Docker capable
of starting `postgres:18.6-alpine`. Run from a dedicated checkout without another Gradle invocation.
No real provider identity, bank connection, production credentials or ownership approval is needed.

```sh
# Generate a NOT_RUN presentation describing the scope; start no containers.
python3 openbank-pension-service/demo/pension_demo.py plan

# Run Alpha, Beta, then fund/API/contract evidence sequentially.
python3 openbank-pension-service/demo/pension_demo.py run

# Test the report's refusal to accept incomplete or stale evidence.
python3 -m unittest discover -s openbank-pension-service/demo -p 'test_*.py'
```

The command prints a unique `build/pension-demo/<run-id>/index.html`. The directory also contains
`report.json`, Gradle logs and copied JUnit XML. Generated evidence remains in ignored build output.
A run succeeds only when every required suite is fresh, complete and has no failed or skipped tests.
A Gradle failure cannot reuse an old green XML. The report records the source revision, dirty state,
start time and actual provider/database context verified by the test. Runtime-generated contract IDs
and timestamps may differ between runs; the business assertions and synthetic inputs are repeatable.

Each company gets a fresh test JVM, PostgreSQL container/database and in-process Temporal server.
Queues are company-specific. Alpha uses synthetic provider `00000000-0000-4000-8000-000000000001`;
Beta uses `00000000-0000-4000-8000-000000000002`. Boundary scenarios deliberately reuse customer IDs
and idempotency keys in both isolated contexts. The runner checks a runtime marker after database
and provider assertions, so running Alpha twice cannot masquerade as Alpha and Beta.

Reset means rerunning into new disposable contexts. The runner does not reset a shared database,
delete earlier reports, or accept a production URL. Testcontainers cleans up its own resources;
an interrupted Docker session may require normal Testcontainers cleanup. A process lock prevents
two demo runners using this checkout simultaneously; it cannot prevent unrelated manual Gradle runs.
Normal tests retain their existing database name and synthetic provider default.

## Presenter walkthrough

Use the HTML report as a technical evidence companion. Expand each company's steps and narrate
these scenes; client/operator screens and an interactive exception workbench are backlog work.

| Scene / persona | Observable outcome | Evidence |
| --- | --- | --- |
| Participant joins DPS | Questionnaire, KID and SCA gates; first contribution activates contract; cooling period | `PensionFullLifecycleJourneyE2E` a-series |
| Participant saves | Contributions, state incentive and tax-year processing; DIP suitability and no DPS state incentive for DIP | Journey a/f-series |
| Participant transfers and changes strategy | Transfer-in history and strategy change | Journey b/h-series |
| Participant exits | Quoted surrender/state contribution return, lump-sum, phased payout and death handling | Journey d/e-series |
| Operator and second approver | Annuity partner approval, comparison and purchase; one person cannot self-approve | Journey g-series |
| Errors and privacy | Missing/expired assessment and invalid suitability refused; other participant receives 404 | Journey + `PensionDemoBoundaryIT` |
| Company separation | Own provider persisted in own database; foreign provider creation rejected with no foreign row | `PensionDemoBoundaryIT`, separately Alpha/Beta |
| Fund operation and failures | HTTP fund lifecycle, NAV/register rules, replay/idempotency, concurrent outgoing reservations and unsupported FX refusal | `PensionFundApiIT`, `OutgoingReservationIT`, `UnitRegisterFlowTest` |
| Effective strategy | Today uses the current election; a future election starts on its business date; missing effective election refuses allocation | `PensionFundRestAdapterTest` (unit proof with fake REST client) |
| Service contract | Pension consumer expectations verified against fund provider; negative authorization contract | Folder Pact + negative-auth verification |

The DIP journey uses the real catalog HTTP client with the existing WireMock catalog/token resource.
Its product/legal approvals are synthetic fixtures, not actual human approvals or proof of reviewer
expertise. The journey verifies that the catalog and approval endpoints were read; production approval
controls remain unchanged.

The same lifecycle runs for both companies. Different commercial product configurations are not
claimed by this first slice. Check exact passing test names in the generated report before presenting
an individual capability; the report is evidence for that source revision, not a permanent certification.

## What is real, simulated and still planned

| Status | Scope |
| --- | --- |
| Real in this demo | Pension HTTP handlers, PostgreSQL persistence and production workflow implementations in an in-process Temporal test server; separate fund HTTP/DB and Pact suites |
| Simulated | Identity/SCA, approved product-catalog projections and reviewer evidence, payment execution, documents, state agency, annuity partners and the pension journey's fund collaborator/valuation |
| Planned | Simultaneously running company deployments with genuine OIDC/relay/OPA identities and A-to-B/B-to-A rejection plus positive controls |
| Planned | One connected pension-to-fund-to-payment journey, durable retry/compensation and an operator exception queue |
| Planned | Portfolio migration rehearsal, NAV corrections, failure recovery/load proof, browser presenter flow and production readiness evidence |

A successful test identity check is not a proof of real token isolation. Sequential disposable
company contexts prove their configured storage/creation boundary; they do not prove all queues,
exports, logs, backups or external integrations are isolated in a live deployment. Likewise a stub
payment response does not prove settlement. The report explicitly preserves these limitations.

## Existing backlog (no parallel demo epic)

- [#12479](https://github.com/JiRaska/open-bank-oss/issues/12479): repeatable demo, connected journey and release evidence.
- [#12472](https://github.com/JiRaska/open-bank-oss/issues/12472): full two-company isolation and genuine-token tests.
- [#12474](https://github.com/JiRaska/open-bank-oss/issues/12474): durable allocation and operator exceptions.
- [#12478](https://github.com/JiRaska/open-bank-oss/issues/12478): life events and portfolio migration rehearsal.
- [#12475](https://github.com/JiRaska/open-bank-oss/issues/12475): product capability/responsibility matrix.
- [#12473](https://github.com/JiRaska/open-bank-oss/issues/12473): NAV correction and compensation.

Synthetic development demonstrations remain independent of real-money production approval.
