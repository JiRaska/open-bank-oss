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
| `POST/GET /api/v1/funds`, `GET/PUT/DELETE /api/v1/funds/{id}` | Fund administration; closing is refused while units are outstanding |
| `POST/GET /api/v1/funds/{id}/navs` | Calculate a NAV (maker) or list them |
| `POST /api/v1/navs/{id}/approve` · `/reject` | Publish (checker): this settles queued orders, or re-prices transactions for a correction |
| `POST/GET /api/v1/strategies`, `GET /api/v1/strategies/{id}` | Strategies: target allocation, bands, glide path |
| `GET /api/v1/strategies/{id}/allocation?yearsToRetirement=` | The glide-path allocation for a participant |
| `POST /api/v1/strategies/{id}/changes`, `POST /api/v1/strategy-changes/{id}/approve` · `/reject` · `/apply` | Governed change: a second approver and a notice period before it applies |
| `POST /api/v1/contracts/{id}/orders` | Subscription, redemption or switch. Requires `Idempotency-Key` |
| `GET /api/v1/contracts/{id}/holdings` · `/orders` · `/transactions` | Holdings valued at the latest published NAV, and their history |

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
