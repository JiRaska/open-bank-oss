# ADR-0135: Push notification token security and lifecycle

Date: 2026-06-29
Status: Accepted
Decision-Status: Accepted
Delivery-Status: Partial
Author(s): Jiri Raska

## Context

OpenBank's mobile app (iOS + Android, ADR-0064) sends push notifications for payment confirmations,
SCA challenges, fraud alerts, and copilot responses. Push tokens (APNs device tokens, FCM registration
IDs) are sensitive: a token tied to a banking app is a direct channel to a customer's device. Risks:

- **Token harvesting** — a compromised `notification-service` database exposes all tokens, enabling
  targeted phishing via push.
- **Stale token accumulation** — tokens are never invalidated when a user logs out, uninstalls the
  app, or switches devices. Mass delivery to stale tokens increases noise and delivery failure rates.
- **No binding to the authenticated session** — a push token registered without proof of the
  current SCA session could be injected by a malicious actor who briefly had device access.
- **Notification payload in transit** — payment amounts and account numbers must not appear in the
  APNs/FCM payload (notification content is visible in the OS notification centre without unlocking).

ADR-0021 covers SCA device approval, ADR-0073 covers hardware-backed credential storage, and
ADR-0099 covers automated secret rotation. None codifies the push token lifecycle end-to-end.

## Decision

We will implement the following controls in `openbank-notification-service` and the mobile app:

### 1. Token binding to authenticated session

Push token registration (`POST /api/v1/notifications/devices`) requires a valid SCA-passed JWT.
The token is stored against `partyId` + `deviceFingerprint` (ADR-0073 hardware attestation). A
device can have at most **one active token per platform** (APNs vs. FCM); a second registration
from the same device replaces the first.

### 2. Token invalidation on logout and uninstall

- **Explicit logout** — the mobile app calls `DELETE /api/v1/notifications/devices/{deviceId}` as
  part of the logout flow. ✅ Endpoint implemented in `notification-service` (#2527). Best-effort
  on offline devices; the TTL below covers the gap.
- **Silent token TTL** — all tokens carry a `registeredAt` timestamp. Tokens not refreshed within
  **90 days** are marked `STALE` and excluded from delivery. The mobile app refreshes the token
  on every app foreground event.
- **APNs/FCM feedback integration** — both platforms signal token invalidation via delivery
  receipts (`Unregistered` / `NotRegistered` error codes). `notification-service` handles these
  callbacks and marks tokens `REVOKED` immediately.

### 3. Notification payload minimisation

Push payloads must not contain:
- Account numbers, IBANs, or balances.
- Transaction amounts or merchant names.
- PII beyond a non-identifying reference ID.

The mobile app fetches the full notification detail via an authenticated API call on tap
(`GET /api/v1/notifications/{notificationId}`). APNs `content-available: 1` (background fetch)
or FCM data message is used to wake the app silently.

### 4. Token storage hygiene

Token values are stored encrypted-at-rest using the same column-level encryption key as other PII
fields (Vault-managed, ADR-0007). The `GET /admin/notifications/devices` endpoint returns a masked
token (`****{last4}`) — the raw token is never returned via API.

### 5. Audit

Every token registration, refresh, revocation, and delivery failure is emitted as an `AuditEntry`
(ADR-0133) with `eventType = PUSH_TOKEN_REGISTERED / REVOKED / DELIVERY_FAILED`.

## Alternatives considered

- **WebSocket / SSE instead of push** — eliminates token lifecycle concern. Rejected: background
  delivery (device locked, app not running) requires APNs/FCM. SSE/WebSocket is used for in-app
  real-time but cannot replace native push for SCA challenges.
- **Hash token before storage** — one-way hash prevents token harvesting from DB. Rejected:
  APNs/FCM require the raw token for delivery; hashing prevents re-delivery after a crash. Encryption
  at rest achieves the same goal without this limitation.
- **Delegate token management to a SaaS push gateway (Firebase, OneSignal)** — reduces operational
  burden. Rejected: SaaS gateway receives all push payloads including SCA challenges; that is an
  unacceptable trust boundary for a banking application.

## Consequences

**Positive**
- Satisfies OWASP Mobile Top 10 M1 (improper credential usage) for push tokens.
- Prevents mass token harvesting from a DB compromise.
- Reduces stale token volume → higher APNs/FCM delivery rates and lower retry overhead.
- Audit trail for every token lifecycle event supports forensic investigation.

**Negative**
- `DELETE /devices` on logout is best-effort on offline devices; the 90-day TTL is the backstop.
- APNs/FCM feedback loop integration requires handling platform-specific receipt formats (two
  separate parsers).
- Payload minimisation increases the number of API round-trips on notification tap (one extra fetch).

**Neutral**
- Existing `notification-service` stores tokens in plain text today (Delivery-Status: Partial).
  A migration is required to encrypt existing rows and add `registeredAt`, `refreshedAt`,
  `status` columns.

## Compliance impact

- GDPR: Art. 32 (security of processing — encryption at rest for device tokens as PII)
- DORA: Art. 9(4)(b) (protection of ICT assets)
- PSD2: Art. 97 (strong customer authentication — SCA push channel integrity)
- ČNB: AML/KYC guidance on customer contact channel integrity
- OWASP Mobile Top 10: M1 (improper credential usage), M3 (insecure communication)

## References

- ADR-0021 (SCA decoupled device approval)
- ADR-0064 (customer app Kotlin Multiplatform)
- ADR-0073 (hardware-backed credential storage)
- ADR-0099 (automated secret rotation)
- ADR-0133 (tamper-evident audit chain)
- `openbank-notification-service/` — current implementation (token storage, delivery)
- Apple APNs documentation — token invalidation feedback
- Firebase FCM — `NotRegistered` error handling
