# Overview

## What the service does

`openbank-psd2-service` is the **PSD2 / Open Banking edge facade** of the OpenBank platform. It exposes the regulated Open Banking surface to licensed Third Party Providers (TPPs) and translates it into internal OpenBank calls. It offers three capability groups:

- **AIS — Account Information Service** — accessible accounts, balances and transactions for a PSU (Payment Service User), gated by an active consent.
- **PIS — Payment Initiation Service** — initiation and status polling for SEPA credit transfers, instant SEPA, Czech domestic payments and SIPO.
- **Consent lifecycle** — create / read / status / revoke a PSD2 consent (the facade delegates the authoritative consent store to `consent-service`).

Supported payment products (`PaymentProduct`): `SEPA_CREDIT_TRANSFERS`, `INSTANT_SEPA_CREDIT_TRANSFERS`, `DOMESTIC_CZ` (ČOBS Czech domestic), `SIPO` (Sdružené inkaso plateb obyvatelstva).

It is **stateless** apart from a transactional outbox; it owns no account, balance, transaction or consent data.

## What the service **does NOT** do

- ❌ Does not store accounts, balances or transactions — it reads them from `account-service` (via an outbound `AccountServiceClient` port).
- ❌ Does not store consents — `consent-service` is the authoritative store; this facade creates/validates/revokes through it.
- ❌ Does not execute or settle payments — it forwards initiation to `transaction-service` (which routes to the SEPA/instant/domestic executors, clearing and the ledger).
- ❌ Does not register or vet TPPs — `tpp-registry-service` holds the eIDAS roles (`AISP`/`PISP`); this service only queries it.
- ❌ Does not perform SCA — Strong Customer Authentication is delegated (see [ADR 0021](../../../../docs/adr/0021-sca-decoupled-device-approval-no-auto-approve.md)); the facade only surfaces the SCA links/status.
- ❌ Does not run AML/sanctions screening — that happens downstream in the payment flow.

## Position in the domain

```
   ┌──────────┐  QWAC / X-TPP-ID    ┌──────────────────────┐
   │   TPP    │ ──────────────────► │ tpp-registry-service │  (AISP/PISP role check)
   │ (AISP/   │                     └──────────────────────┘
   │  PISP)   │
   └────┬─────┘  Open Banking v2
        │ AIS reads / PIS / consents
        ▼
   ┌─────────────────┐  validate consent   ┌──────────────────┐
   │  psd2-service   │ ──────────────────► │ consent-service  │
   │  (facade)       │                     └──────────────────┘
   └──┬───────┬──────┘  read accounts      ┌──────────────────┐
      │       └───────────────────────────►│ account-service  │
      │   initiate payment                 └──────────────────┘
      │       ┌───────────────────────────►┌──────────────────┐
      │       │                             │transaction-service│→ SEPA/instant/domestic
      │       │                             └──────────────────┘   + clearing + ledger
      ▼ outbox → Kafka (openbank.psd2.events)
   ┌─────────────────┐
   │ audit / TPP     │  (event consumers, webhook delivery)
   │ webhooks        │
   └─────────────────┘
```

## Key use cases

| Use case | API | Downstream / Event |
|---|---|---|
| List accessible accounts (AIS) | `GET /open-banking/v2/accounts` | validate consent → `account-service` |
| Get balances (AIS) | `GET /open-banking/v2/accounts/{id}/balances` | validate consent → `account-service` |
| Get transactions (AIS, paged) | `GET /open-banking/v2/accounts/{id}/transactions` | validate consent → `account-service` |
| Create consent | `POST /open-banking/v2/consents` | `consent-service` (scopes derived from `access`) |
| Read / status / revoke consent | `GET`/`DELETE /open-banking/v2/consents/{id}` | `consent-service` |
| Initiate SEPA / instant / domestic-CZ / SIPO payment | `POST /open-banking/v2/payments/{product}` | validate consent → `transaction-service` |
| Poll payment status | `GET /open-banking/v2/payments/{product}/{id}/status` | `transaction-service` |
| Developer sandbox (fixtures) | `…/open-banking/sandbox/v2/…` | static fixtures, no downstream calls |

The outbox topic `openbank.psd2.events` carries asynchronous notifications (e.g. consent revoked, payment status changed, transaction report) for audit and TPP-webhook delivery. The TPP webhook event types are `TRANSACTION_REPORT`, `CONSENT_REVOKED`, `PAYMENT_STATUS_CHANGED`, `ACCOUNT_STATUS_CHANGED`.

## Callers

- **External TPPs (AISP / PISP)** — authenticated by eIDAS QWAC client certificate (`SSL-CLIENT-S-DN`, terminated at the gateway) or the `X-TPP-ID` header in non-mTLS topologies.
- **TPP developer integrations** — against the sandbox surface (`/open-banking/sandbox/v2`) which returns deterministic fixtures.

## Dependencies

- **consent-service** (REST, via `ConsentServiceClient` port) — consent create / validate / status / revoke.
- **account-service** (REST, via `AccountServiceClient` port) — read accounts, balances, transactions.
- **transaction-service** (REST, via `TransactionServiceClient` port) — initiate payment, poll status.
- **tpp-registry-service** (REST client `tpp-registry`, default `http://localhost:8108`) — TPP authorization / role check.
- **Kafka** (`openbank-kafka`, topic `openbank.psd2.events`) — outbox drain.
- **Redis (Valkey)** — idempotency cache.
- **Keycloak** — OIDC is configured (service-to-service), but does not authorize the Open Banking paths.
- **openbank-libs** — `IdempotencyStore` (Redis impl), outbox plumbing, `DocsResource`, build/service-info, security headers.

> All cross-service calls are wrapped in MicroProfile Fault Tolerance (timeout / retry / circuit breaker / fallback). The current downstream clients are **stub implementations** (`StubClients.kt`); the resilient wrappers (`ResilientClients.kt`) delegate to them until the real REST clients land.

## Business value

- **One regulated entry point** — a single PSD2-compliant surface (AIS + PIS + consents) instead of exposing internal core-banking services to TPPs.
- **Consent-first** — every AIS read and PIS initiation is gated by a consent validation call; on a consent-service failure the facade **fails closed** (denies access).
- **Resilient by design** — circuit breakers and fallbacks protect TPPs from internal partial outages; payment initiation never silently succeeds on a downstream failure.
- **Sandbox for onboarding** — TPP developers integrate against deterministic fixtures before going live.
