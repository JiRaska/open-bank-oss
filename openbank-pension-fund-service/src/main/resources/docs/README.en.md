# openbank-pension-fund-service — Documentation

> **What it is:** the provider (fund) side of the pension platform ([ADR 0334](../../../../docs/adr/0334-pension-fund-platform.md)). It runs segregated funds, their strategies and glide paths, the NAV, and the unit register of each pension contract. **What it is NOT:** a bank book. Fund assets belong to the participants. Nothing here posts to the bank ledger or to treasury.

This documentation is published by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service, [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)).

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / Hibernate Reactive Panache / PostgreSQL (`pension-fund-db`, CNPG)
- **Ports:** 8162 (app), 8090 (management)
- **Events:** none yet
- **Auth:** fund administration is staff only (`ROLE_OPERATOR`/`ROLE_ADMIN`, never a service-account). Reads are also open to `ROLE_AUDITOR` and `ROLE_API`.
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
