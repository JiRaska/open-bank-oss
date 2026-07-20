---
date: 2026-06-02
decision-status: accepted
delivery-status: shipped
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [notifications, privacy-gdpr, observability]
summary: "Oversight signals reach Slack and Teams as an opt-in, allow-listed side-channel invoked inside dispatch, carrying a fixed safe schema only, so notification variables, recipients and other PII can never leak."
---

# 59. Outbound oversight webhooks (Slack/Teams): anonymized, allow-listed, opt-in egress

**Delivery note (updated 2026-07-17):** v1 scope fully delivered; the 2026-06-30 "renderers/delivery pending" note was stale (all of it had shipped by 2026-06-29).
- **Security design** — ✅ Complete: allow-list schema (`OversightSignal`), a local defense-in-depth PII scrubber (`scrubPii`, IBAN/PAN/email — not the shared `openbank-libs/security` `PiiMask`), fail-closed egress, off-by-default + Vault secret pattern.
- **Renderers and delivery** — ✅ Complete: Slack + Teams renderers (`OversightWebhook.renderText`/`renderSlackPayload`), HttpClient adapters (`SlackOversightWebhookPublisher`, `TeamsOversightWebhookPublisher`), dispatch fan-out (`NotificationConsumer.publishOversight`), and the nightly dead-letter janitor (`NotificationOutboxDeadLetterJanitorJob`) — all shipped and tested (unit + `OversightWebhookIT`).
- **Follow-ups (post-v1):** Kafka-durable audit (webhook-sent is currently log-backed via `LoggingAuditEventPublisher`) and N-in-M-minutes event windowing.

## Context

Operators want operational/risk signals from the running bank pushed to their existing chat tools
(Slack first, Microsoft Teams next) — "3 payments failed in the last 5 minutes", "a KYC was rejected",
"an account was frozen" — so they do not have to watch the admin console continuously.

The load-bearing pieces already exist:

- **`openbank-notification-service`** consumes `openbank.notification.requests` and dispatches per channel
  (EMAIL/SMS/PUSH/IN_APP — most are stubs) through a **transactional outbox** (ADR-0003/0013) with
  resilience (retry, circuit-breaker, timeout) and KEDA scale-to-zero (ADR-0057, tier T2).
- **`PiiMask`** (`openbank-libs/security`) is a production, non-reversible masker (IBAN, name, email,
  PAN, phone, national id) already used in the audit pipeline (GDPR Art. 25/32).
- **Default-deny network egress** (`openbank-infra/k8s/base/network-policies.yaml`): no pod reaches the
  internet unless it carries `openbank.io/allow-internet-egress: "true"`.
- **ExternalSecrets + Vault** (ADR-0017): the pattern for injecting a secret (here, the webhook URL)
  without ever committing it.

What is missing — and risky if done naively — is the **egress itself**. A notification today carries
PII in its `variables` map (`accountNumber`/IBAN, `amount`, `name`). Forwarding a notification verbatim
to Slack/Teams would ship customer PII to a third-party SaaS outside the cluster boundary — a GDPR and
DORA-third-party violation. The hard requirement is therefore: **make it functional, but guarantee no
sensitive data leaves.**

Status legend: 🟢 GREEN = built + tested; 🟡 YELLOW = scaffolded; ⬜ PLANNED.

## Decision

We add **outbound oversight webhooks** to `notification-service` as an **anonymized, allow-listed,
opt-in side-channel** — never a verbatim notification forward.

### D1 — Side-channel, not a delivery channel

The webhook is **not** a new `NotificationChannel` a producer can target (that would let any upstream
push arbitrary `variables` — PII — to Slack). It is a **cross-cutting oversight notifier** invoked
inside `dispatch()` for allow-listed templates only, regardless of the notification's primary channel.
Producers cannot address it; they cannot choose what it contains.

### D2 — Allow-list, not block-list (the anonymization guarantee)

The outbound payload is built from a **fixed, safe schema** — `OversightSignal { template, primaryChannel,
status, occurredAt }` — and **nothing else**. The PII-bearing `variables` map, `recipient`, raw
`partyId`, names, IBANs and amounts are **never read** by the renderer. This is a positive allow-list:
a field leaks only if someone adds it to the schema on purpose, which a test forbids (D5). Block-list
masking ("send everything, redact the sensitive bits") is rejected — a new template variable would
silently leak.

Only **oversight/risk** templates egress: `TRANSACTION_FAILED`, `KYC_REJECTED`, `ACCOUNT_FROZEN`,
`CONSENT_REVOKED`. Per-customer success/marketing/secret templates (`WELCOME`, `OTP_CODE`,
`TRANSACTION_COMPLETED`, …) are **not** on the allow-list and never egress.

### D3 — Defense in depth: PiiMask over the rendered text

Even though the schema carries no PII, the final rendered string is passed through `PiiMask` before
egress as a second layer — so if the schema is ever extended with free text, an IBAN/PAN/email pattern
is masked, not leaked. Two independent controls must both fail to leak.

### D4 — Opt-in, secret-managed, network-gated

- **Off by default**: `openbank.notification.webhook.slack.enabled=false`. Disabled → the publisher is a
  no-op; nothing egresses.
- **Webhook URL via Vault/ExternalSecret** (ADR-0017): never in git, never in config files. Injected as
  `SLACK_WEBHOOK_URL`.
- **Network egress is explicit**: the pod gets `openbank.io/allow-internet-egress: "true"`; without it
  the default-deny policy blocks the call (fail-closed).

### D5 — Audited + tested

- Every send emits a structured audit log `notification.webhook.sent` with `{template, status,
  webhook_url_masked}` — **never** the message content. (Full `AuditEvent` emission via the audit Kafka
  channel mirrors the existing pattern — ⬜ PLANNED follow-up.)
- A unit test asserts the rendered payload contains **no PII** (no recipient, no variable values, no raw
  partyId) for every allow-listed template, and that non-allow-listed templates never render. This test
  is the executable form of the anonymization guarantee.

### D6 — Slack first, Teams via the same port

Delivery is behind an `OversightWebhookPublisher` port. The first adapter is **Slack** (incoming webhook,
`{"text": …}`). **Teams** (MessageCard / Adaptive Card to an incoming webhook URL) is the same port, a
second adapter — ⬜ PLANNED, no new decision needed.

### D7 — Data classification stays `internal`

Because only an **anonymized, aggregated oversight signal** leaves (no PII, no per-customer detail),
`notification-service` stays `internal` (manifest). The ADR is the record that egress is anonymized; a
reclassification would be required only if per-customer content were ever sent (which D2 forbids).

## Alternatives considered

- **Forward the notification verbatim, mask sensitive fields (block-list).** Simplest, but one new
  template variable leaks PII silently. Rejected — allow-list (D2) is fail-safe.
- **A new `WEBHOOK` NotificationChannel.** Lets producers target Slack — and push arbitrary `variables`
  (PII) to it. Rejected — the side-channel (D1) keeps content control in one audited place.
- **Send from the admin-ui / a new service.** Duplicates the outbox, resilience and scale-to-zero that
  `notification-service` already has. Rejected — reuse the existing dispatcher.
- **Open broad internet egress for the namespace.** Rejected — opt-in label per workload (D4), fail-closed.

## Consequences

**Positive**
- Operators get timely risk signals in their existing tools without watching the console.
- The anonymization guarantee is structural (allow-list) + defense-in-depth (PiiMask) + test-enforced.
- Reuses outbox/resilience/scale-to-zero, Vault/ExternalSecrets, PiiMask, network policy — little net-new
  infrastructure.
- Off-by-default + secret-managed URL + fail-closed egress: safe to ship dark, enable per environment.

**Negative**
- A third-party SaaS (Slack/Teams) is now in the path of oversight signals — even anonymized, an outage
  or a misrouted webhook URL is an exposure of *operational metadata* (which templates fire, when). The
  URL is the sensitive asset; it lives in Vault and is treated as a secret.
- Webhook failures sit in the outbox and retry; a persistently bad URL accumulates failed rows (a janitor
  is a follow-up).
- Aggregation/windowing ("N events in M minutes") is a follow-up; v1 emits per-event anonymized signals.

**Neutral**
- The webhook is best-effort and never blocks or fails the primary notification dispatch.

## Compliance impact

- **GDPR Art. 25/32** (data protection by design): allow-list + PiiMask ensure no personal data egresses.
- **DORA Art. 28–30** (ICT third party): Slack/Teams treated as a third party; only anonymized metadata
  is shared; the webhook URL is a managed secret.
- **PCI DSS Req. 3/4** (protect stored/transmitted data): no PAN/cardholder data in the payload (allow-list).
- **CNB / operational resilience**: fail-closed egress, off-by-default, audited sends.

## References

- ADR-0003 / ADR-0013 — transactional outbox (the delivery substrate reused here).
- ADR-0017 — Vault / ExternalSecrets (webhook URL custody).
- ADR-0057 — FinOps tiers / KEDA scale-to-zero (notification-service is T2).
- `openbank-libs/security/PiiMasking.kt` — the masker reused for defense-in-depth.
- `openbank-infra/k8s/base/network-policies.yaml` — default-deny egress + opt-in label.
- Slack incoming webhooks; Microsoft Teams incoming webhooks (MessageCard / Adaptive Cards) — external.
