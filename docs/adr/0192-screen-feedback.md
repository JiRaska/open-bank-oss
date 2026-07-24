---
date: 2026-07-24
decision-status: accepted
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: [openbank-app]
tags: [mobile-app, customer-edge, analytics, privacy-gdpr]
summary: "Global floating feedback rail in the app captures a user-confirmed screenshot + comment per screen; edge publishes to Kafka and the onboarding-analytics pipeline persists it. No new service; screenshots in S3 with 90-day lifecycle."
---

# ADR-0192 — In-app screen feedback with screenshot capture

## Context

The app has no lightweight channel for screen-scoped user feedback. The complaints
flow (ADR-backed, regulatory) is deliberately formal and wrong for "this screen
confuses me" signals. Product iteration currently relies on funnel telemetry
(onboarding analytics: app → edge → Kafka → analytics-sink → ClickHouse) which says
*where* users drop, never *why*.

A screenshot is the highest-signal context for screen feedback, but in a banking app
it necessarily contains personal data (balances, names, transactions), so capture and
retention are privacy-constrained. Some surfaces must never be captured at all
(PIN entry, lock screen, signing ceremony, SCA approval).

## Decision

We will add an in-app feedback capability with the following shape:

**App (openbank-app)**
- A single global overlay component (`FeedbackRail`) mounted at the app root, so it
  is present on every screen with zero per-screen wiring. It renders as a half-hidden
  handle on the right edge; vertically draggable, tap opens a feedback sheet.
- Screenshot capture via Compose `GraphicsLayer.toImageBitmap()` on the root content
  layer — pure common code, no platform screenshot APIs, and it captures only the
  app's own composition (no system UI, no other apps).
- The user always sees the captured screenshot in the sheet and explicitly confirms
  before anything leaves the device. No silent capture, no periodic capture.
- Capture is blocked on trusted/secret surfaces (lock, PIN set/entry, signing
  ceremony, SCA approval): the rail hides there entirely.
- Payload: screenshot (PNG, downscaled, ≤ 2 MB), free-text comment, category
  (bug / idea / confusing), screen id (nav route), app version + build, platform,
  OS version, locale, theme, and session id for RUM/trace correlation. Party
  identity comes from the bearer token on the edge — never client-asserted.

**Edge + pipeline (monorepo)**
- `POST /customer/v1/feedback` on customer-edge (authenticated, same token as all
  customer calls). Edge validates size and content type, stores the screenshot to
  S3 (SSE, 90-day lifecycle rule), and publishes a metadata event carrying the S3
  object key — never the image bytes — to Kafka topic `openbank.feedback.events`.
- analytics-sink consumes the topic and persists metadata to ClickHouse next to the
  onboarding funnel tables; admin read surface follows the existing
  `/onboarding/analytics` pattern.
- No new deployable service.

## Alternatives considered

- **GlitchTip user-feedback + attachments** — zero new infra (GlitchTip is live with
  a known DSN). Rejected: product feedback in an error tracker is the wrong tool —
  feedback would be keyed to synthetic "events", triage/reporting UX is poor, and
  retention/erasure of PII screenshots is not controllable to our GDPR terms.
- **Dedicated feedback-service** — clean ownership, but adds a deploy unit for a
  thin CRUD on an already long serial-deploy backlog, plus OPA/OIDC/KafkaUser
  provisioning for one table. Rejected as not worth a service boundary; can be
  extracted later if feedback grows workflows (assignment, replies).
- **Extend complaints flow** — reuses an existing formal channel. Rejected: mixes a
  regulatory process with product telemetry, and its data model (case lifecycle)
  does not fit fire-and-forget feedback.

## Consequences

**Positive**
- One overlay at the root covers every screen, present and future.
- Reuses the proven funnel pipeline (edge → Kafka → analytics-sink → ClickHouse);
  admin views are ClickHouse gold views like onboarding analytics.
- Screenshot never enters Kafka or ClickHouse — object storage only, keyed
  reference, lifecycle-expired.

**Negative**
- Depends on analytics-sink actually being deployed (it is currently scaffolded but
  not deployed — prerequisite for the read path; the write path to Kafka works
  regardless and events are retained).
- Screenshots are PII: we take on a 90-day retention commitment, erasure-by-party
  support, and a capture-blocklist that must be kept in sync as new sensitive
  screens are added.

**Neutral**
- Feedback volume is unpredictable; rate limiting at the edge (per party, per hour)
  is part of the endpoint contract from day one.

## Compliance impact

- PCI DSS: not applicable — no PAN is ever rendered by the app (PCI keeps
  reveal-PAN out of the edge), so screenshots cannot contain card data.
- DORA: not applicable — no new ICT third party, no critical function change.
- GDPR: screenshots and comments are personal data. Lawful basis: consent, given
  per-send by explicit user confirmation of the previewed screenshot. Storage
  limitation: 90-day S3 lifecycle. Right to erasure: metadata rows and S3 objects
  are locatable by party id. Data minimisation: capture is user-initiated only,
  blocked on sensitive surfaces, and the image is downscaled.
- PSD2: not applicable — no payment initiation or account-information scope change.
- CNB: not applicable — no regulatory reporting impact.

## References

- Onboarding funnel analytics pipeline (app FunnelTracker → edge → Kafka →
  analytics-sink → ClickHouse) — the pipeline this ADR extends.
- ADR-0191 — AI theming; establishes the "trusted surfaces" list this ADR reuses
  as its capture blocklist.
