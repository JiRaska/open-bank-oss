# Overview

## What the service does

`openbank-customer-edge` is the **sole internet-facing entry point** for the retail customer app (KMP, ADR-0064). It is a backend-for-frontend (BFF) plus gateway (ADR-0065) that:

- **Validates the customer JWT** issued by the `openbank-customers` Keycloak realm (a separate realm from the operator `openbank` realm).
- **Extracts the caller's party identity** from the JWT `party_id` claim (falling back to `sub`) into a `CustomerIdentity`.
- **Enforces per-party ownership** on every read/write — a customer can only ever reach their own accounts, balances, transactions, statements, payments and SCA resources (IDOR defence; deny-by-default).
- **Proxies an explicit allow-list of routes** to backend services via the `UpstreamClient`. Any path not in the allow-list returns 404.
- **Re-authenticates outbound** — the customer token is *not* forwarded; the edge obtains its own machine-to-machine (M2M) service-account token (operator realm, `client_credentials`) and tags the request with `X-Customer-Party-Id` so upstreams can scope data independently.
- **Enriches lightweight client bodies** into the full instructions upstream services need (e.g. resolving a debtor's IBAN/BBAN and legal name for a payment).
- **Gates onboarding** — `POST /onboarding/start` creates a `PENDING_ACTIVATION` party (unauthenticated); `POST /onboarding/account` opens the first account only after the party is KYC-`ACTIVE` (ADR-0069).

## What the service **does NOT** do

- ❌ Does not hold any business state — no database, no outbox, no domain aggregate (it is stateless).
- ❌ Does not serve operators — that is the admin-UI BFF (ADR-0056); operators live in the `openbank` realm.
- ❌ Does not own accounts, balances, transactions, statements, or payments — it only proxies to the services that do.
- ❌ Does not move money — payment routes *create and screen* an instruction; settlement is a later, SCA-gated step.
- ❌ Does not run KYC/AML itself — it enforces the KYC gate by reading party status from party-service.
- ❌ Does not mint customer tokens or create Keycloak users (Phase 1: operator/seed script; Phase 2: a follow-up).

## Position in the domain

```
   ┌──────────────────┐  HTTPS (customer JWT)   ┌────────────────────┐
   │ retail app (KMP) │ ──────────────────────► │ ingress-nginx      │
   └──────────────────┘                         │ (per-IP rate limit)│
                                                └─────────┬──────────┘
                                                          │ /customer/v1/*
                                                          ▼
                                            ┌─────────────────────────┐
                                            │  openbank-customer-edge  │
                                            │  validate JWT · IDOR ·   │
                                            │  M2M token · allow-list  │
                                            └───────────┬─────────────┘
                       M2M token + X-Customer-Party-Id  │
        ┌──────────────┬──────────────┬─────────────────┼───────────────┬───────────────┐
        ▼              ▼              ▼                  ▼               ▼               ▼
   account-svc    balance-svc    transaction-svc    sca-svc       party-svc      statement-svc
                                                  (+ domestic-payment, sepa-payment, notification)
```

## Key use cases

The edge is a proxy: it emits **no events of its own**. The "upstream" column names the service the route is forwarded to.

| Use case | API (base `/customer/v1`) | Upstream |
|---|---|---|
| List my accounts | `GET /accounts` | account-service |
| Get one of my accounts | `GET /accounts/{accountId}` | account-service (ownership enforced here) |
| Get a balance | `GET /balances/{accountId}` | balance-service (ownership enforced here) |
| List my transactions | `GET /transactions?accountId=…` | transaction-service (ownership enforced here) |
| List statement periods | `GET /statements/{accountId}` | statement-service (ownership enforced here) |
| Render a statement (camt.053/MT940/PDF) | `GET /statements/{accountId}/{currency}/{legalSequence}` | statement-service |
| Get my profile | `GET /profile` | party-service (party-scoped) |
| List my notifications | `GET /notifications` | notification-service (party-scoped) |
| Initiate a domestic payment | `POST /domestic-payments` | domestic-payment-service (enriched, SCA-gated) |
| Initiate a SEPA credit transfer | `POST /sepa-payments` | sepa-payment-service (enriched, SCA-gated) |
| Enrol an SCA device | `POST /sca/parties/{partyId}/devices` | sca-service (ADR-0021) |
| Initiate / read / decide an SCA challenge | `POST,GET /sca/challenges[/{id}][/decision]` | sca-service |
| Register / list a push device | `POST,GET /devices` | notification-service |
| Start onboarding (anonymous) | `POST /onboarding/start` | party-service (M2M, no party header) |
| Open first account after KYC | `POST /onboarding/account` | account-service (KYC gate) |

## Callers

- **retail customer app** (KMP, ADR-0064) — the only intended caller, over the public internet, carrying a `openbank-customers` realm JWT.

## Dependencies

- **Keycloak** — inbound JWT validation (`openbank-customers` realm) **and** outbound M2M token (operator `openbank` realm).
- **party-service** — onboarding, profile, KYC-status gate, debtor legal-name resolution.
- **account-service** — account list / ownership lookup / first-account opening.
- **balance-service**, **transaction-service**, **statement-service** — read proxies (ownership enforced at the edge).
- **domestic-payment-service**, **sepa-payment-service** — payment initiation (instruction only).
- **sca-service** — device enrolment + challenge lifecycle (ADR-0021).
- **notification-service** — in-app feed + push-device registration.
- **openbank-libs** — ServiceInfoResource (`/api/v1/info`), Docs-as-Service (`/q/openbank/docs`), BuildInfo, health.

## Business value

- **Single, narrow trust boundary** between untrusted retail devices and the internal fleet — deny-by-default allow-list, one place to apply rate limits, abuse defence and attestation (ADR-0065).
- **Realm separation** — customers never receive an operator-realm token; staff and customer trust domains stay disjoint.
- **IDOR containment** — ownership is enforced at the edge even where an upstream scopes only by id, so a guessed account id cannot leak another party's data.
- **Client simplification** — the app sends lightweight bodies; the edge enriches them into the full upstream contract, keeping banking detail out of the mobile client.
- **Compliance gate** — an un-KYC'd party cannot obtain an IBAN (AML/PSD2), enforced before forwarding to account-service.
