# Overview

## What the service does

`openbank-notification-service` is the **outbound customer-communication hub** of the OpenBank platform. It:

- **Consumes notification requests** from the Kafka topic `openbank.notification.requests` — each request is a `NotificationRequest` (partyId, channel, template, recipient, template variables) emitted by an originating domain service.
- **Renders a template** into a subject + body. Templates are an enum (`ACCOUNT_OPENED`, `TRANSACTION_COMPLETED`, `KYC_APPROVED`, `KYC_REJECTED`, `OTP_CODE`, `WELCOME`, `CONSENT_REVOKED`, `ACCOUNT_FROZEN`, …).
- **Persists** every notification (`notifications` table) with its delivery status (PENDING → SENT / FAILED / BOUNCED).
- **Delivers** per channel: EMAIL via the Quarkus reactive mailer (SMTP), PUSH by fanning out to every ACTIVE device token registered for the party (FCM / APNs). SMS and IN_APP are stubs.
- **Manages a push device-token registry** — the customer app registers FCM/APNs tokens via `POST /api/v1/devices`; PUSH delivery reads ACTIVE tokens for the party.
- **Emits an anonymized oversight signal** to Slack/Teams for a small allow-list of risk templates ([ADR 0059](../../../../docs/adr/0059-outbound-oversight-webhooks-slack-teams.md)) — off by default, PII-free by construction.
- **Exposes a break-glass dispatch control** ([ADR 0047](../../../../docs/adr/0047-governed-runtime-operational-control-plane.md)) — an operator can halt all outbound dispatch immediately (single actor, deferred review) and resume only via four-eyes.

## What the service **does NOT** do

- ❌ Does not decide *when* to notify — originating services (account, transaction, kyc, consent) produce the request.
- ❌ Does not handle money, balances, payments, or ledger entries — it is **not** a money-path service.
- ❌ Does not run KYC/AML/screening — it only relays the *result* as a message.
- ❌ Does not store or echo the raw push token over REST — the provider token is write-only (PII-adjacent).
- ❌ Does not provide inbound idempotency — at-least-once delivery is accepted (no money path).

## Position in the domain

```
   ┌──────────────────┐                        ┌─────────────────────────┐
   │ account-service  │                        │ notification-service     │
   │ transaction-svc  │  openbank.notification │  ─ render template       │
   │ kyc-service      │  .requests (Kafka)     │  ─ persist (PENDING)     │
   │ consent-service  │ ─────────────────────► │  ─ deliver:              │
   └──────────────────┘                        │      EMAIL → SMTP        │
                                                │      PUSH  → FCM/APNs    │
   ┌──────────────────┐  POST /api/v1/devices  │  ─ oversight → Slack     │
   │ customer app     │ ─────────────────────► │      (anonymized)        │
   │ (via edge)       │   register push token  └────────────┬────────────┘
   └──────────────────┘                                     │
                                                            ▼
                                                   PostgreSQL (openbank_notifications)
```

## Key use cases

| Use case | API / trigger | Channel(s) |
|---|---|---|
| Notify customer of an event | Kafka `openbank.notification.requests` (`NotificationRequest`) | EMAIL / SMS / PUSH / IN_APP |
| Register a push device token | `POST /api/v1/devices` | — |
| List a party's devices | `GET /api/v1/devices?partyId=…` | — |
| List / read notifications | `GET /api/v1/notifications`, `GET /api/v1/notifications/{id}` | — |
| Break-glass halt of dispatch | `POST /api/v1/ops/dispatch/halt` | — |
| Resume dispatch (four-eyes) | `POST /api/v1/ops/dispatch/resume/propose` + `/approve` | — |
| Anonymized oversight to Slack | automatic for risk templates (ADR-0059) | webhook |

## Callers

- **Producing domain services** — account-service, transaction-service, kyc-service, consent-service publish notification requests to Kafka.
- **customer app** (via `openbank-customer-edge`) — registers push device tokens; the edge injects the authoritative `partyId` from the customer JWT (IDOR prevention).
- **admin-ui** (operators / auditors via Keycloak) — read notifications, drive the dispatch-control break-glass workflow.

## Dependencies

- **PostgreSQL** (database `openbank_notifications`)
- **Kafka** — inbound topic `openbank.notification.requests`
- **SMTP mailer** (Quarkus Mailer) — email delivery; mocked in dev/test
- **FCM / APNs** — push providers, off by default, credentials from Vault
- **Slack/Teams incoming webhook** — oversight side-channel, off by default
- **Keycloak** — OIDC auth
- **openbank-libs** — audit (`AuditEventPublisher`), governance (`Proposal` four-eyes), `PiiMask`, `ServiceInfoResource`, `DocsResource`

## Business value

- **Single, governed egress** for all customer communications — one place to template, throttle and audit messaging.
- **Resilient delivery** — outbox + scheduled dispatch with circuit-breaker/retry/bulkhead/timeout fault-tolerance; halts cleanly under the break-glass control.
- **Privacy-by-construction oversight** — operational risk signals reach the ops channel with no customer data leaving the cluster (positive allow-list + defense-in-depth PII scrubber).
- **Multi-device push** — fan-out to all of a customer's registered devices, with automatic retirement of provider-rejected tokens.
