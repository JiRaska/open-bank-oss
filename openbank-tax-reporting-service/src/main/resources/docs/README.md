# Tax Reporting Service API

§38d Vyúčtování daně vybírané srážkou podle zvláštní sazby daně — the platform's system of record for the monthly withholding-tax return (ADR-0180).

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

The filing API lists monthly periods, their remittances, and overdue returns. An operator assembles a period before a different operator records it as filed. The export-capability endpoint reports whether EPO XML rendering is available; when it is unavailable, assembling a return does not submit it. Filing still requires an external submission and a recorded reference.

## Access and operations

Bearer tokens are validated against the identity provider. Reads and state changes also require the corresponding OPA decisions; assembling and recording a filing require a human operator. A service account with an operator role cannot perform those state changes. Reading filings, overdue periods, remittance trails and the export capability is authorised as `tax.filing.inspect`, a non-read verb, so the fleet-wide read grants for operator and compliance roles do not admit it: only human staff reach these per-taxpayer returns, never a service account. The application uses a PostgreSQL datasource and applies its Flyway migrations at startup. Its management port serves health and metrics separately from the filing API.

Withholding-remitted events feed the monthly totals. Events with an unexpected type are ignored. Target events that cannot be decoded are counted as malformed and sent to the configured dead-letter topic with their original Kafka key, value, and headers. If a valid remittance cannot be recorded, the write is retried and a persistent failure is also sent there. Both the malformed count and dead-letter backlog need investigation before filing: an affected remittance is absent from the period total until corrected or replayed. The source consumer starts at the latest offset and is not a historical backfill mechanism. A successful application rollout by itself does not prove that a return is complete or submitted.

## Catalogue-defined statutory returns

`/api/v1/statutory-returns/catalogues` exposes the versioned jurisdiction catalogue. The ČNB PSP/PEF definitions specify reporting scope, period, deadline, required figures, and arithmetic checks for pension-fund and pension-company returns. An operator can assemble a completed period, a different operator can approve its content hash, and a submitted return records the external submission reference. Corrections create another revision. `/breaches` includes due returns that were never assembled when a reporting start and the reporting entities are configured, and treats an overdue outstanding correction as a breach even if an earlier revision was submitted. Without the reporting start or fund roster it returns unavailable rather than zero. The hourly deadline sweep publishes the overdue count and a workflow liveness signal only after the complete check succeeds.

Statutory-return access requires a staff identity as well as the route's role and OPA decision. Inspection uses `tax.statutory-return.inspect`; the policy grants it to permitted staff and refuses service-account principals. Assembly, approval, and submission require `ROLE_OPERATOR` and their respective lifecycle decisions. The application also rejects a service-account actor on all three lifecycle routes, including when OPA enforcement is disabled: an operator role alone does not make a machine a maker, checker, or submitter. This guard checks the principal name and token claims, so a service-account token with a UUID principal name is still refused with HTTP 403. A human operator remains subject to the data-availability checks and the separate maker/checker requirement. Corporate-register inspection has its own OPA action; proposing, approving, and rejecting entries are operator actions. The application independently refuses service-account actors on both reads and writes, including a UUID principal identified by token claims and when OPA is advisory.

`/capability` is authoritative for the current deployment. Pension figures are read from pension-fund-service's period figures (fund balance sheet, year-to-date P&L, unit roll-forward, portfolio, entitlements, participant counts) and pension-service's participant aggregates (contributions by source, payouts, pensioners), using this service's own machine client. In production, the pension-ledger REST client uses mutual TLS: it presents its client certificate and verifies the ledger server against the configured private CA. Fund PSP 20-12 uses separate non-negative revaluation gains, revaluation losses and management fees, alongside signed other investment result and profit/loss. Fund PEF 13-04 uses classified closing loans; unknown positions or unavailable result lines make assembly unavailable rather than zero. The pension company's own balance sheet and P&L use its separate ledger's frozen closes; a returned close for a different requested period makes assembly unavailable. Capital, organisation and dividend facts require approved, effective corporate-register entries. The company's own PSP 34-12 portfolio remains unavailable: a ledger balance cannot supply its instruments and holdings count. Source mappings do not prove that an instance is deployed or populated; missing period data still refuses assembly. The regulator wire renderer is unavailable, and this service does not claim to have transmitted a regulator file; verified cell-level rendering is tracked separately. The configured reporting entity IDs and reporting start must be supplied before never-assembled pension returns can be monitored.

The corporate register is available at `/api/v1/corporate-register` for the reporting company. Entries name their source document and are versioned and effective-dated. Only approved entries feed a return; the proposer cannot decide their own entry, and decided entries are append-only. Missing approved values produce an unavailable return response rather than zero.

For fund PSP 20-12, the return uses the provider's year-to-date revaluation gains, revaluation losses, management fees, other investment result, and profit/loss figures. Revaluation gains, revaluation losses, and management fees are non-negative amounts; other investment result and profit/loss are signed. The catalogue checks the reported profit/loss against its component lines. If the provider cannot supply the required lines, assembly returns unavailable with the provider's reason.

Fund PEF 13-04 uses the closing portfolio's `loansOutstanding`. The provider reports it as unknown while any closing position is `UNCLASSIFIED`, because that position may be a loan; tax-reporting then refuses assembly with HTTP 503 and identifies the number of unclassified positions and the four-eyes classification path. A period from before position recording is also unavailable. Missing loan data is never converted to zero; zero is reported only when the provider has a known closing portfolio and no classified loans.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.

## Shared configuration defaults

This service's `application.yaml` does not declare the management interface (`quarkus.management.*`: enabled, port 8085, host 0.0.0.0, root path `/q`). These values come from the fleet defaults in `openbank-libs-runtime/src/main/resources/application.yaml`, which is on every service classpath at a lower precedence than the service's own file. The values are identical to what this service used to declare itself. To change one for this service only, set it in this service's `application.yaml`; the service file takes precedence.

## Test coverage floor

The module enforces a Kover LINE floor of **78 %** over its own sources (`build.gradle.kts`,
`kover { verify { … minValue = 78 } }`; the fleet filter excludes `com.openbank.libs.*`). It was
set on 2026-10-17 from a measured 80.94 % (1452/1794 lines) minus two points, ADR-0020 style, and
only ever moves up: lowering it needs a `coverage-floor-lowered: #<issue>` trailer on the PR (gate
`coverage-floor-ratchet`). Before this the module had no `verify {}` rule, so `koverVerify` passed
on any number.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.
