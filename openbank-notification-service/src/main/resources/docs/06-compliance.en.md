# Compliance

> **Money-path:** No. `openbank-notification-service` is **not** listed in `rules.yaml: money_path_services`. It carries no funds, balances or ledger entries, so it does not require the 2-approval + threat-model gate that money-path services do. It is nonetheless a **confidential** data processor (it handles recipient PII and customer event content) and an egress point, so the controls below still apply.

## Regulatory framework

| Regulation | Relation to this service | Implementation |
|---|---|---|
| **GDPR** | Processes recipient PII (email/phone), party identifiers, push tokens, message bodies | PiiMask in logs, token write-only over REST, 2-year retention, oversight egress is PII-free by construction |
| **DORA** (Reg. (EU) 2022/2554) | Operational resilience of the comms channel | health probes, fault-tolerant outbox (circuit-breaker/retry/bulkhead/timeout), break-glass control (ADR-0047), audit trail, runbooks |
| **PSD2** (Reg. (EU) 2015/2366) | Delivers transaction / SCA-related notifications (e.g. OTP_CODE) on behalf of payment flows | renders & delivers; OTP/secret templates never egress to the oversight channel |
| **AML / AMLD** | Relays AML-driven outcomes (ACCOUNT_FROZEN, KYC_REJECTED) — does **not** perform screening | oversight allow-list surfaces these as anonymized risk signals; no AML decision logic here |
| **NIS2** | Network & info security of an egress service | mTLS in-cluster (Istio), security response headers (CSP/HSTS/X-Frame-Options), network policies, audit log |
| **eIDAS / SCA delivery** | OTP delivery channel for strong customer authentication | OTP_CODE template, 5-minute validity copy; secret never leaves the cluster except to the customer |

## GDPR mapping

### Role & lawful basis

The service is a **processor** acting for the OpenBank controller; the originating service (account/transaction/kyc/consent) is the controller for the underlying event.

- **Contract** (Art. 6(1)(b)) — transactional/service notifications necessary to perform the customer contract.
- **Legal obligation** (Art. 6(1)(c)) — security/SCA notifications (OTP), regulatory communications.
- Marketing-style templates (e.g. WELCOME) would require **consent** (Art. 6(1)(a)) managed upstream — this service only renders & delivers.

### Data subject rights

| Right | Application |
|---|---|
| Access (Art. 15) | `GET /api/v1/notifications?partyId=…`, `GET /api/v1/devices?partyId=…` return the subject's records (tokens excluded) |
| Rectification (Art. 16) | recipient/contact corrections happen upstream (party-service); notifications are immutable history |
| Erasure (Art. 17) | applicable after the 2-year retention window; honoured via upstream party erasure + purge (purge job **TBD**) |
| Restriction (Art. 18) | break-glass `halt` stops all outbound dispatch |
| Portability (Art. 20) | N/A — notifications are derived records, not provided-by-subject data |
| Object (Art. 21) | marketing preferences managed upstream (consent-service) |

### Records of processing (Art. 30)

Every dispatch-control transition emits an `AuditEvent` (actor, operation, resource, result, reason) to `audit-service` — supporting Art. 30 and DORA Art. 17.

### Data flows out

| Destination | Data | Notes |
|---|---|---|
| **SMTP mailer** | recipient address + rendered body | EMAIL delivery; same controller boundary, real egress to the customer's mailbox |
| **FCM / APNs** (Google / Apple) | provider push token + push text | **third-party processors**, off by default; only when push is enabled. Subject to processor agreements |
| **Slack / Teams** (oversight) | anonymized `OversightSignal` (template name, channel, status, timestamp) only | **no customer data** — positive allow-list (ADR-0059) + IBAN/PAN/email scrubber; off by default |
| **audit-service** (Kafka) | dispatch-control transition metadata | intra-OpenBank, same controller |

EMAIL and PUSH deliveries leave the cluster by necessity (they reach the customer); FCM/APNs introduce non-EU sub-processors (Google/Apple) — relevant for the cross-border transfer assessment when push is enabled.

### Retention (Art. 5(1)(e))

| Data | Retention |
|---|---|
| `notifications` | 2 years (governance manifest), then purge — notifications are communication records, **not** subject to 10-year AML retention |
| `device_tokens` | while registered; INVALID tokens drop from fan-out |
| dispatch-control logs | operational-evidence window (append-only, DORA Art. 17) |

## DORA mapping (Reg. (EU) 2022/2554)

| Article | Topic | Implementation |
|---|---|---|
| Art. 9 | Identification | `BuildInfo` (gitCommit, buildTime, version) in `/api/v1/info` |
| Art. 10 | Detection | Prometheus metrics, consumer-lag alerting |
| Art. 11 | Response & recovery | break-glass halt/resume (ADR-0047), runbooks in `05-operations.md` |
| Art. 16/17 | Incident management & logging | audit events on every control transition; append-only dispatch log (point-in-time reconstructible) |
| Art. 28 | Third-party risk | FCM/APNs are external sub-processors — off by default, credentials in Vault; SMTP self-hosted in sandbox |

## ADR-0059 — oversight webhook (privacy by construction)

The Slack/Teams oversight side-channel is the only path by which any signal egresses to a non-customer external system. It is designed so a leak requires **two independent controls to fail**:

1. **Positive allow-list schema** — only `OversightSignal` (template enum name, channel, status, timestamp) is serialized; the PII-bearing `variables`, `recipient`, raw `partyId`, names, IBANs and amounts are unreachable. Only risk templates (`TRANSACTION_FAILED`, `KYC_REJECTED`, `ACCOUNT_FROZEN`, `CONSENT_REVOKED`) egress — success/secret templates (`WELCOME`, `OTP_CODE`, `TRANSACTION_COMPLETED`) are deliberately absent.
2. **Defense-in-depth scrubber** — `scrubPii` masks IBAN-like, PAN-like (13–19 digit) and email-shaped tokens before send, guarding against future schema drift.

Off by default; best-effort (a webhook failure never fails or blocks notification dispatch).

## ADR-0047 — governed break-glass control

Stopping outbound notifications is the fail-safe direction, so **halt** is a single-actor break-glass that takes effect immediately and flags a mandatory deferred review. **Resume** raises risk and is gated by **four-eyes**: the approver must differ from the proposer (enforced in `openbank-libs` governance — `MakerCheckerViolation` → HTTP 422), not by convention. The desired-state log is append-only and versioned; every replica converges on the latest snapshot without a per-pod RPC.

## Security controls

- ✅ Input validation (required-field checks, platform enum parsing) → `400`
- ✅ AuthN: Keycloak OIDC, RS256 JWT
- ✅ AuthZ: `@RolesAllowed` per endpoint; dispatch-control actor from JWT subject (not body)
- ✅ IDOR prevention: device `partyId` injected by the edge from the customer JWT
- ✅ Token confidentiality: push token write-only over REST, masked in logs
- ✅ Secret confidentiality: OTP_CODE bodies delivered but never stored — redacted on write and again on read (GDPR Art. 5(1)(c); a stored OTP would let an operator complete the customer's SCA, ADR-0021)
- ✅ Egress minimisation: push + oversight off by default; oversight PII-free by construction
- ✅ Resilience: outbox circuit-breaker/retry/bulkhead/timeout; break-glass halt
- ✅ Security headers: CSP, HSTS, X-Frame-Options DENY, X-Content-Type-Options nosniff, Referrer-Policy, Permissions-Policy
- ✅ Audit: every dispatch-control transition → audit-service
- ✅ Secrets: dev placeholders only; prod secrets via Vault ExternalSecret
- ⚠️ Automated retention/purge job: **not yet implemented** (TBD) — 2-year retention currently enforced operationally
