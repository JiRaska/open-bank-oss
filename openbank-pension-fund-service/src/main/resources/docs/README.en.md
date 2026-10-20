# openbank-pension-fund-service — Documentation

> **What it is:** the provider (fund) side of the pension platform ([ADR 0334](../../../../docs/adr/0334-pension-fund-platform.md)). It runs segregated funds, their strategies and glide paths, the NAV, and the unit register of each pension contract. **What it is NOT:** a bank book. Fund assets belong to the participants. Nothing here posts to the bank ledger or to treasury.

This documentation is published by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service, [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)).

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / Hibernate Reactive Panache / PostgreSQL (`pension-fund-db`, CNPG)
- **Ports:** 8162 (app), 8090 (management)
- **Events:** none yet
- **Auth:** fund administration is staff only (`ROLE_OPERATOR`/`ROLE_ADMIN`, never a service-account). Participant holdings, orders and transactions are readable by human `ROLE_OPERATOR`, `ROLE_ADMIN`, `ROLE_AUDITOR` or `ROLE_COMPLIANCE` staff and pension-service's own `ROLE_API` client. They use the action `pension-fund.holding.inspect` (not a `.read`), so fleet-wide operator/compliance read grants cannot admit a shared service-account.
- **Four-eyes:** the person who calculates a NAV or submits a strategy change cannot publish or approve it (403). This is enforced in the domain and by a DB CHECK.
- **Forward pricing:** an order is accepted unpriced (202). It settles at the fund's NEXT published NAV.

## API

| Method & path | What it does |
|---|---|
| `POST/GET /api/v2/funds`, `GET/PUT/DELETE /api/v2/funds/{id}` | Fund administration; closing is refused while units are outstanding |
| `POST/GET /api/v2/funds/{id}/navs` | Calculate a NAV (maker) or list them |
| `POST /api/v2/navs/{id}/approve` · `/reject` | Publish (checker): this settles queued orders, or re-prices transactions for a correction |
| `POST/GET /api/v2/strategies`, `GET /api/v2/strategies/{id}` | Strategies: target allocation, bands, glide path |
| `GET /api/v2/strategies/{id}/allocation?yearsToRetirement=` | The glide-path allocation for a participant |
| `POST /api/v2/strategies/{id}/changes`, `POST /api/v2/strategy-changes/{id}/approve` · `/reject` · `/apply` | Governed change: a second approver and a notice period before it applies |
| `POST /api/v2/contracts/{id}/orders` | Subscription, redemption or switch. Requires `Idempotency-Key` |
| `GET /api/v2/contracts/{id}/holdings` · `/orders` · `/transactions` | Holdings valued at the latest published NAV, and their history |

## Numbers

Every amount is a `BigDecimal` with an explicit scale:

- money: 2 dp, HALF_EVEN
- NAV: 6 dp, HALF_EVEN
- units issued: rounded DOWN
- units cancelled for a fee: rounded UP

The management fee accrues on gross assets, ACT/365, for the days since the previous NAV.

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


## Period reporting

`GET /api/v2/reporting/funds/{fundId}/period-figures` accepts required
`periodStart` and `periodEnd` dates. It exposes aggregate fund figures for
tax-reporting-service: balance sheet, year-to-date profit and loss, unit
roll-forward, portfolio and flows. The endpoint requires an allowed reporting
role and the `pension-fund.reporting.inspect` authorization policy.

The calculation uses published NAVs and transactions priced at those NAVs,
bucketed by valuation date. A period without a published NAV is not reportable;
it must not be presented as zero activity. Positions are stored with newly
calculated NAVs. For older NAVs without recorded positions, portfolio figures
are unknown rather than an empty portfolio. Basis NAV identifiers and a
fingerprint identify the report inputs. This read model supplies figures;
it does not submit a statutory return to the regulator.

### Reporting P&L and position classification

The period-figures read model includes year-to-date revaluation gains, revaluation
losses, other investment result and management fees. Consumers must check
`profitAndLossYtd.linesUnavailableReason` before treating the individual lines as
reportable; an unavailable breakdown is not a measured zero.

Recorded NAV positions carry an instrument class. Historical positions and feed
entries without a class remain `UNCLASSIFIED`. Propose a correction with
`POST /api/v2/position-classification-corrections` (`positionId`, `toClass`, `reason`),
then have a different checker approve or reject it via `/{correctionId}/approve`
or `/{correctionId}/reject`. Proposing requires `pension-fund.nav.calculate`; deciding
requires `pension-fund.nav.approve`. The proposer cannot decide their own correction.
These inputs support the reporting read model; they do not submit a statutory return.


### Classification correction retries

The proposal, approval, and rejection POSTs require a nonblank `Idempotency-Key` of at most 128 characters. A key is scoped to the authenticated caller and operation. Retrying the same instruction returns the original response, even after the correction has moved to another state; changed content with the same key returns 409. The replay snapshot and correction commit in one database transaction. Competing approval/rejection requests can commit only one decision.


## Integration tests and the fail-closed service-account flag

OPA treats any principal whose token is not verifiably a person as a service account (`input.principal.service_account`, fail closed). A plain `@TestSecurity` identity carries no JWT, so it counts as a machine and loses human-only grants. Integration tests that act as real staff therefore add `@OidcSecurity` human claims, which is why this module tests with `quarkus-test-security-oidc`.

## Observability

The service publishes `openbank.pension_fund.*` metrics: NAV publication lag,
age and corrections, order and strategy-change outcomes, settled amounts,
order replays, market-price lookups and optimistic-lock conflicts on unit
holdings (counted by the REST mappers before they answer 409). State gauges
(net assets, NAV per unit, units outstanding, pending orders and queue ages)
are refreshed every minute by a scheduled job; a refresh that fails keeps the
previous values and stops recording success, so the workflow-liveness gauge
goes stale and alerts. Alert rules live in `prometheus-rules-pension-fund.yaml`
and the runbook in `docs/runbooks/pension-alerts.md`.

## Shared build dependencies

This service's `build.gradle.kts` does not list the dependencies every Quarkus service shares: the Quarkus BOM, quarkus-kotlin, RESTEasy Reactive with Jackson, SmallRye Health and OpenAPI, OIDC, the Micrometer Prometheus registry and config-yaml, plus the quarkus-junit5, AssertJ and REST Assured test libraries. The `openbank.quarkus-service` convention plugin in `build-logic` declares them. The resolved classpath is unchanged. `build.gradle.kts` lists only what this service adds.

## Management fee from a pinned catalog revision (ADR-0341)

A fund is now created against a published product catalog revision. The create request carries `productRevisionId`, and the service reads that revision from product-catalog once, at creation, and derives the annual management fee from its fee schedule. The fund stores the revision id in `product_revision_id` (migration V5, nullable) and caches the derived rate in `management_fee_rate`; the fee is never edited afterwards. The create request answers 400 without a revision id. `managementFeeRate` is deprecated as an input: if supplied it must equal the pinned revision's fee, otherwise the request is rejected with 400. Funds created before pinning have a null revision, keep their stored rate read-only through the API, and stay that way until a maker-checker grandfathering revision is created and they are re-pinned. The API contract version is 2.0.0; creation and amendment use separate schemas.

The catalog lookup uses the service's own machine identity, the Keycloak client `openbank-pension-fund` with the `catalog:read` scope, over the `catalog-authority` TLS configuration (client certificate, private CA, TLS 1.3) in production. Connect and read timeouts are 2 s and 5 s. If fund creation fails with a catalog error, check the product-catalog URL, the client secret and the TLS mount together. The OIDC client is disabled in the test profile. To roll back the schema change, drop the `product_revision_id` column; the cached rate stays correct.


### API v2 transition and rollback (draft delivery plan)

API v2 requires `productRevisionId` in `CreateFundRequest`; `AmendFundRequest` can omit it to retain the existing pin, or repeat that same id. Neither request silently chooses a current revision, changes the pin or edits the cached fee. Missing/null revision, an unresolved published revision or a conflicting fee is refused. V6 widens the stored fee to eight fractional digits so six-digit percentage terms survive persistence; it does not reconstruct historical rounded fees.

The seven v1 resource roots retain all 27 unchanged operations as explicit deprecated application boundaries, with the same roles, authorization actions, actor attribution, idempotency keys and four-eyes checks. They return `X-API-Version: v1`, `Deprecation`, `Sunset` and a v2 successor link. The configured default sunset is 30 June 2027; an owner-approved rollout must verify that the actual configured date is at least 180 days ahead.

The owner approved only a **draft transition proposal** excepting `POST /api/v1/funds` and `PUT /api/v1/funds/{id}` from that compatibility window: those two old manual-fee writes are not registered and return 405, instead of silently selecting a revision or accepting editable pinned fees. This proposal is not permission to deploy the retirement. External writers have not been exhaustively inventoried.

Known readers/writers move explicitly: pension-service's fund client and mTLS fixture, tax-reporting's fund-period client, their consumer Pact tests, and the Admin UI's pension-fund URL builder use v2. Consumer Pact JSON must be regenerated by the native Pact workflow and imported unchanged; old tracked v1 pacts are not proof of v2 compatibility.

Before activation, separately approve the exact provider/client images, legacy-writer cutover and existing TLS/certificate/network policy changes. First verify the provider's v2 capability across every active backend; old v1-only pods cannot safely receive v2 traffic. Retained aliases allow existing unchanged-operation callers to continue while their images are updated. Do not activate new callers against an old provider. Existing draft, blocked and automatic-merge-off controls remain until those decisions and native tests are complete.

For rollback, restore callers before reverting to a v1-only provider, or retain a v2-capable provider. Keep the additive revision column and widened numeric precision; do not drop pins, narrow stored fees or restore fee editing on pinned funds. A safe binary/data rollback must be accepted before rollout. Source merge, provisioning, credentials, policy activation and financial execution are not authorized by this draft plan.
