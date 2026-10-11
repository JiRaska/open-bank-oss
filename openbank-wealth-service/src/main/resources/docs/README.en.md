# openbank-wealth-service — Documentation

> **What it is:** the owner of customer-declared, off-platform holdings and liabilities: property, collectibles, external securities, company stakes, private loans and similar items the bank does not hold ([ADR 0301](../../../../docs/adr/0301-wealth-service-declared-holdings-and-net-worth-composition.md)). **What it is NOT:** a source of bank positions. It moves no money, and a declared figure is never a balance.

This documentation is published by the service at the management endpoint `/q/openbank/docs` (Docs-as-Service, [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)).

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / Hibernate Reactive Panache / PostgreSQL (`wealth-db`, CNPG)
- **Ports:** 8154 (app), 8090 (management)
- **Events:** transactional outbox (ADR-0003) to `openbank.wealth.events`; synthetic traffic is tainted in the outbox (ADR-0252)
- **Auth:** M2M only, `ROLE_API`, `ROLE_OPERATOR` or `ROLE_ADMIN`. The owner arrives in the `X-Customer-Party-Id` header.
- **Synthetic taint (ADR-0252):** a holding declared by a trusted canary principal is stored with `synthetic = true`, and every event about it carries the `x-openbank-synthetic` Kafka header, revalue and withdraw included. The flag belongs to the holding, not to the request that touched it.
- **Canary identity test:** the HTTP integration test supplies OIDC `azp`, subject and service-account username to exercise the trusted-principal path; a role by itself is insufficient. It checks the holding and outbox rows, including revaluation. These local test claims do not prove live identity-provider configuration or a deployed canary run.
- **Trust boundary:** by-id routes act on the holding id alone and do NOT check the owner. Customers reach this service only through customer-edge, which proves ownership before every by-id call.

## API

| Method & path | What it does |
|---|---|
| `POST /api/v1/holdings` | Declare a holding for the party in `X-Customer-Party-Id` |
| `GET /api/v1/holdings` | List that party's active and pledged holdings |
| `GET /api/v1/holdings/{id}` | Read one holding |
| `PUT /api/v1/holdings/{id}/valuation` | Restate its value; the previous value is kept |
| `GET /api/v1/holdings/{id}/valuations` | Every value ever asserted, newest first |
| `DELETE /api/v1/holdings/{id}` | Withdraw it; 409 while it is pledged as lending collateral |

Every holding carries `valuationSource` (`CUSTOMER_DECLARED`, `EXPERT_APPRAISAL`, `MARKET_REFERENCE`) and `valuationAgeDays`. A customer-typed figure must never be mistaken for a checked one, and a years-old figure must never look fresh.

The valuation history is append-only (`declared_holding_valuations`). It is the one record here that cannot be rebuilt if it is lost.

## Callers and contracts

The only caller is **customer-edge**. It uses the list read to compose `GET /customer/v1/net-worth`, and all six routes behind `/customer/v1/holdings`.

That relationship is a Pact contract (`pacts/openbank-customer-edge-openbank-wealth-service.json`), replayed by three provider classes:

- `WealthPactFolderProviderVerificationTest` replays it on every PR against a real Postgres.
- `WealthNegativeAuthProviderVerificationTest` proves that a caller with no identity gets 401.
- `WealthPactBrokerProviderVerificationTest` publishes the verification on main-push, so `can-i-deploy` can answer about customer-edge.
