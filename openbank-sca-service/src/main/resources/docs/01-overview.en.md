# Overview

## What the service does

`openbank-sca-service` is the **Strong Customer Authentication (SCA) engine** of the OpenBank platform. It performs step-up authentication when another service needs to be sure a real customer is present and has approved a sensitive action. It holds:

- **ScaChallenge aggregate** — a single authentication challenge: party, purpose (PAYMENT_INITIATION / CONSENT_GRANT / LOGIN / AGENT_ACTION / SENSITIVE_DATA_ACCESS), method (TOTP / PUSH_NOTIFICATION / BIOMETRIC), status (PENDING / COMPLETED / FAILED / EXPIRED / CANCELLED), attempt counter, expiry, and optional **dynamic-linking data** (amount, currency, creditor IBAN/name, reference) per PSD2 RTS Art. 5.
- **EnrolledDevice** — a device credential (public key + algorithm ES256/ED25519) enrolled to a party, used to verify later out-of-band approval signatures (ADR-0021). The private key never leaves the device's hardware keystore (Secure Enclave / Android Keystore).
- **DeviceApprovalDecision** — a signature-verified, dynamic-linking-bound APPROVED/DENIED decision recorded out-of-band by the enrolled device; stored transiently (it only needs to outlive its challenge).

## What the service **does NOT** do

- ❌ Does not authenticate the primary login — that's Keycloak (OIDC). SCA is *step-up* on top of an authenticated session.
- ❌ Does not store consent — `consent-service` owns consent records; it triggers SCA.
- ❌ Does not execute or authorise the payment — the payment services initiate an SCA challenge and gate on its `COMPLETED` status.
- ❌ Does not auto-approve push/biometric — by design (ADR-0021). Decoupled methods require a real, signed, dynamically-linked decision from the enrolled device.
- ❌ Does not send push/SMS itself in production — it delegates to the notification path; locally the `LoggingNotificationSender` only logs.

## Position in the domain

```
   ┌──────────────────┐  initiate SCA     ┌──────────────┐
   │ payment / consent│ ───────────────►  │              │
   │ / psd2 / agent   │  POST /challenges │  sca-service │
   └──────────────────┘                   │              │
                                          └──────┬───────┘
   ┌──────────────────┐  verify / poll          │ outbox → Kafka
   │ caller polls     │ ◄───────────────────────┤ (DEVICE_ENROLLED)
   │ status==COMPLETED│                          ▼
   └──────────────────┘                   ┌───────────────────┐
                                          │ onboarding cockpit│
   ┌──────────────────┐  decision (signed)│ (ADR-0068)        │
   │ enrolled device  │ ───────────────►  └───────────────────┘
   │ (customer app)   │  POST /decision
   └──────────────────┘
                          PostgreSQL (openbank_sca) + Redis (OTP / idempotency / decisions)
```

## Key use cases

| Use case | API | Event |
|---|---|---|
| Initiate an SCA challenge | `POST /api/v1/sca/challenges` | — |
| Verify a challenge (OTP, or poll decoupled decision) | `POST /api/v1/sca/challenges/{id}/verify` | — |
| Get challenge status | `GET /api/v1/sca/challenges/{id}` | — |
| Enrol a device credential | `POST /api/v1/sca/parties/{partyId}/devices` | `DEVICE_ENROLLED` |
| List a party's enrolled devices | `GET /api/v1/sca/parties/{partyId}/devices` | — |
| Record an out-of-band device approval/denial | `POST /api/v1/sca/challenges/{id}/decision` | — |

## Callers

- **payment services** (sepa, domestic, sepa-instant, …) — initiate SCA for PAYMENT_INITIATION and gate on `COMPLETED` before releasing funds.
- **consent-service / psd2-service** — initiate SCA for CONSENT_GRANT (declared upstream in `governance.yaml`).
- **agent gateway** (ADR-0031) — initiate SCA for AGENT_ACTION (human-in-the-loop approval of an AI-initiated action).
- **customer app** (ADR-0064/0065/0066) — enrols devices and posts the signed out-of-band decision; lists its own devices.
- **admin-ui / onboarding cockpit** (ADR-0068) — reads enrolled devices to advance the onboarding read-model.

## Dependencies

- **PostgreSQL** (`openbank-postgres`, database `openbank_sca`)
- **Kafka** (`openbank-kafka`, topic `openbank.sca.challenge.event`)
- **Redis (Valkey)** — transient OTP store, idempotency keys, decoupled decisions
- **Keycloak** — OIDC auth
- **OPA sidecar** — `@Authorize` policy evaluation (ADR-0034, advisory by default)
- **openbank-libs** — `IdempotencyStore`, `@Authorize`/authz, `ApiError`/`ErrorCode`, outbox plumbing, BuildInfo, DocsResource

## Business value

- **PSD2 compliance** — SCA with dynamic linking is a hard regulatory requirement for electronic payments and account access; this service is the single, audited place that enforces it.
- **Fail-closed by design** — push/biometric never auto-approve (ADR-0021 closes critical audit finding K2); an unusable factor is strictly safer than a bypassable one.
- **Replay-resistant** — every decoupled approval is signed over the exact challenge id + decision + amount + payee, so a captured signature cannot be replayed for a different amount, creditor, or to flip a DENIED into an APPROVED.
- **Reusable across surfaces** — one engine serves payments, consent, login, agent actions, and sensitive-data access.
