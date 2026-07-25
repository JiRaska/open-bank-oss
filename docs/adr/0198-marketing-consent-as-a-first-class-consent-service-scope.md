---
date: 2026-07-25
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [privacy-gdpr, notifications, compliance]
summary: "Marketing consent becomes per-channel MARKETING_COMMS scopes in consent-service; party-service's column drops to a projection and notification-service's default-on flag drops to a mute, resolving the ADR-0126 split-brain."
---

# ADR-0198 — Marketing consent as a first-class consent-service scope

## Context

Issue #1331 ends with a sentence nobody has acted on: *"Someone must decide"*. This ADR is that
decision. ADR-0176 D6 (purpose / lawful-basis binding + marketing hard-deny) is still `⬜ Pending`
with the note *"Blocked on the #1331 consent-authority decision"*, and #1331 was closed
`COMPLETED` on 2026-07-16 with no comment and no ADR — so the analysis landed and the decision did
not. The evidence that it is still unmade is in the tree today, not inferred:

**Force 1 — an Art. 7 consent lives outside the Art. 7 authority.** ADR-0126:28 declares
`openbank-consent-service` *"the single consent authority for all three regimes"*, regime 2 being
*"GDPR Art. 7 data-processing consents"*. Yet `ConsentScope` (`Consent.kt:12-44`) contains no
marketing value: AISP/PISP/CBPII reads, `PAYMENTS_*`, `FUNDS_CONFIRMATION`, the four `AGENT_*`
scopes, and `TELEMETRY_RUM`. The live marketing consent is instead a pair of party-service columns —
`consent_marketing` (`V12__consent_capture.sql`, #1157) and `consent_marketing_updated_at`
(`V13__marketing_consent_updated_at.sql`, #1161) — mapped on `PartyEntity`, written through
`UpdateMarketingConsentCommand`, revocable by the customer via `PATCH /customer/v1/profile/consent`
in customer-edge, and audited. The control is real. It is simply in the wrong service, so the two
ADRs and the code disagree.

**Force 2 — the consent that exists is not the gate that runs.** No service reads it before
sending. `NotificationConsumer` gates only the PUSH channel, and only on a channel preference:
`maybeSendPush` reads `notification_preferences` and evaluates
`NotificationCategory.MARKETING -> pref?.marketingPush ?: true` — a column declared
`marketing_push BOOLEAN NOT NULL DEFAULT TRUE`, whose migration comment states the intent plainly
("A missing row means *all on*"). A default-on boolean is an opt-out; GDPR Art. 7(1) requires the
controller to *demonstrate* consent, and the absence of a row demonstrates nothing. Worse, the EMAIL
branch of the same `when (req.channel)` dispatch calls `sendEmail` directly with no category, no
preference and no consent check at all. **Today a MARKETING-category email would be delivered with
zero consent evaluation anywhere in the path.** Nothing currently sends one — the ADR-0176 catalogue
has no marketing template, which is the only reason this is a latent defect rather than a live one.
ADR-0200 introduces the first real marketing sender, so it stops being latent.

**Force 3 — a fourth description of the same fact, published and wrong.** `admin-ui`
`src/app/docs/compliance/page.tsx:68` reports "Marketing consent — ok" citing
`marketing_consent boolean V2`. That is the *dead legacy* party-service column from
`V2__compliance_fields.sql:11`, which #1331 verified has zero readers and zero writers. So a
compliance page shown to auditors sources its green tick from a column no code touches. This is the
failure mode the root `CLAUDE.md` already names: a published document keeping its own copy of a fact
that lives elsewhere *is* the drift.

**Force 4 — one boolean cannot carry three channels.** #1331 read Act 480/2004 §2 and found
*"elektronickými prostředky"* defined with `zejména` (non-exhaustive), so §7(2)'s consent
requirement reaches push and SMS, not only email — while §7(3)'s soft opt-in is scoped *"pro
elektronickou poštu"*, email only, and can therefore never cover push. A single `consent_marketing`
boolean cannot be simultaneously right for EMAIL, PUSH and IN_APP.

Why now: ADR-0199 (customer 360) and ADR-0200 (campaign journeys) both need a consent answer they
can query, and ADR-0200 is the first capability that will actually send marketing at volume.
Building either on a default-on flag would industrialise the defect.

## Decision

We will make **consent-service the sole authority for marketing consent**, in four parts.

**D1 — A new `ConsentScope` value per channel, not one for marketing.** Add
`MARKETING_COMMS_EMAIL`, `MARKETING_COMMS_PUSH` and `MARKETING_COMMS_INAPP` to `ConsentScope`,
following the `TELEMETRY_RUM` precedent exactly: a GDPR Art. 7 data-processing consent, **not** a
PSD2 account-access consent, therefore **not** SCA-gated, and falling in the 365-day non-AISP
validity bucket rather than the PSD2 RTS Art. 10 90-day cap. The per-channel split is what makes
force 4 expressible; a customer who accepted email marketing has not thereby accepted push. Default
is **absent**, and absent means denied — the inverse of today's flag.

**D2 — `GranteeType` needs no new value.** The grantee is the bank itself, which is
`INTERNAL_SERVICE`. A `BANK_MARKETING` grantee type would be a second axis describing the same fact
as the scope.

**D3 — party-service's column becomes a projection; the dead column is dropped.**
`consent_marketing` / `consent_marketing_updated_at` stay as a read-optimised projection fed from
the consent-service outbox (`consent.granted` / `consent.revoked`), so the existing customer-edge
`PATCH /customer/v1/profile/consent` contract and the mobile Profile screen keep working unchanged —
but the write path is redirected to consent-service and `UpdateMarketingConsentCommand` becomes a
forwarder. The legacy `marketing_consent` column from V2 is dropped in a **new** migration, never by
editing V2 (Flyway checksums the whole file, comments included). Both party-service reads and
admin-ui's compliance page are repointed at the projection, and the compliance page cites
`rules.yaml` rather than a column name.

**D4 — notification-service checks consent for every marketing channel, and its flag is demoted to
a mute.** `NotificationConsumer` gains a consent check for `NotificationCategory.MARKETING` on
*every* channel, not only push, placed before dispatch so the EMAIL hole closes by construction.
`notification_preferences.marketing_push` keeps its `DEFAULT TRUE` and stops being a consent: it
becomes what its name says, a per-channel mute *within* a granted consent, which is a legitimate and
different control. A marketing send with no ACTIVE consent for the target channel records
`SUPPRESSED` and emits an audit event. `SECURITY` category is untouched — OTP, SCA, KYC and freeze
notices are not marketing and are never gated.

The check is a call to consent-service, not a cached copy. ADR-0195 already establishes live
validation per call as the pattern for the MCP consent binding, for the same reason: a cached
consent is a consent that survives its own revocation.

## Alternatives considered

- **Amend ADR-0126 to carve out simple per-party opt-ins** — the other branch #1331 explicitly
  offers. Cheapest possible change: no migration, no new scope, party-service keeps writing.
  Rejected because the carve-out has no principled edge. `TELEMETRY_RUM` is *also* a simple
  per-party Art. 7 opt-in and it lives in consent-service, so the carve-out would have to explain
  why two identical things sit in different services. It also keeps the per-channel axis
  inexpressible and leaves expiry, revocation events and erasure to be re-implemented per column.
- **One `MARKETING_COMMS` scope with the channel carried in adjacent metadata.** Fewer enum values,
  and the channel axis still expressible. Rejected because every existing consent decision in this
  codebase is made by scope matching; putting the channel elsewhere means every caller must remember
  to check a second field, and the one that forgets fails open. Three enum values are checked by the
  compiler.
- **Keep `notification_preferences.marketing_push` as the consent and flip its default to FALSE.** A
  one-line migration that would make the push path defensible. Rejected: it fixes one channel of
  three, leaves the EMAIL path with no gate at all, still holds an Art. 7 consent outside the
  declared authority, and provides no grant timestamp, no expiry and no revocation event — so Art.
  7(1) demonstrability is still unmet.
- **Put the consent check in customer-edge or in campaign-service instead of notification-service.**
  Rejected: notification-service is the single choke point every channel passes through, so a gate
  there cannot be bypassed by a future second sender. A gate in a caller protects only that caller.

## Consequences

**Positive**
- ADR-0126's "single consent authority" claim becomes true of the code, not only of the ADR.
- The EMAIL no-gate hole closes before ADR-0200 makes it reachable.
- Consent expiry, the ADR-0126 revoke/expire outbox events and the ADR-0118 erasure cascade apply to
  marketing consent for free — they are consent-service behaviours, and marketing stops being the one
  Art. 7 consent that has to re-implement each of them.
- Act 480/2004's email-versus-push asymmetry becomes representable, so the bank can be stricter on
  push deliberately rather than by omission.
- Four descriptions of marketing consent (dead V2 column, live V12/V13 columns, notification flag,
  admin-ui compliance row) collapse to one authority plus one projection.

**Negative**
- Every marketing send takes a consent-service call. On a campaign fan-out that is per recipient;
  ADR-0200 must bulk-resolve at segment-materialisation time and re-check per send, and
  consent-service becomes a runtime dependency of notification-service that it does not have today.
- A migration touching a live customer-facing consent column. The projection must be backfilled from
  the existing `consent_marketing` values **before** the write path flips, or every customer who had
  opted in silently reverts to denied. Rollback note: the projection columns are additive and the
  forwarder is flag-guarded, so rollback is flipping the flag back; a rollback after the V2 drop
  cannot restore that column, which is acceptable because it has no readers.
- Three new values on a fleet-shared enum that is matched exhaustively in several places, so this is
  a compile-error sweep at first build.

**Neutral**
- The customer-facing API shape does not change: `PATCH /customer/v1/profile/consent` keeps its
  `marketingConsent` boolean, which now writes the EMAIL and IN_APP scopes and leaves PUSH to the
  notification-preferences screen. Whether the mobile app should surface three toggles instead of one
  is an `openbank-app` product decision, not this ADR's.

## Compliance impact

- PCI DSS: not applicable — a consent record holds no cardholder data.
- DORA: not applicable — no ICT third-party or resilience dimension.
- GDPR: Art. 7 (demonstrable, revocable consent — a default-on flag cannot demonstrate, and the
  EMAIL path evaluates nothing); Art. 7(3) (withdrawal as easy as giving, preserved through the
  existing customer-edge PATCH); Art. 21(2) (direct-marketing objection, which applies regardless of
  lawful basis and is satisfied by revocation); Art. 30 (the record of processing stops citing a
  column with no readers). Art. 6(1)(a) is the lawful basis for marketing here, per #1331's reading
  of Recital 47 as permissive rather than prescriptive.
- PSD2: not applicable by design — a marketing consent is explicitly *not* an account-access
  consent, so the RTS Art. 10 90-day cap and the SCA requirement do not apply. Stating that is the
  point: the risk is a future reader treating every `ConsentScope` value as PSD2-governed.
- CNB: not applicable.

Act 480/2004 §7 (Czech commercial-communications consent, and its email-only soft opt-in in §7(3))
is the domestic instrument driving the per-channel split. Legal points here are an
engineering-grade reading of primary sources carried over from #1331, not advice; the lawful-basis
classification should be confirmed with counsel before it is encoded in a ROPA.

## References

- [ADR-0126](0126-unified-consent-lifecycle.md) — consent-service as the single authority for all
  three consent regimes; the claim this ADR makes true of the code.
- [ADR-0176](0176-operator-initiated-customer-messaging.md) — D6 pending, blocked on this decision;
  its correction note is the source for the split-brain analysis.
- [ADR-0135](0135-push-notification-token-security.md) — §3 PII-free push payloads, still unbuilt
  (#1182); a marketing push must not carry content either.
- [ADR-0195](0195-mcp-server-caller-authentication-and-psd2-consent-binding.md) — live per-call
  consent validation rather than trusting a cached copy.
- [ADR-0118](0118-gdpr-data-lifecycle-and-retention.md) — erasure and retention that marketing
  consent inherits once it lives in consent-service.
- [ADR-0200](0200-campaign-journeys-as-temporal-workflows-with-consent-gated-delivery.md) — the
  first real marketing sender, which this ADR gates.
- Issue #1331 — the party-service versus consent-service split-brain; closed without the decision
  being recorded anywhere.
- `openbank-consent-service/src/main/kotlin/com/openbank/consent/domain/model/Consent.kt` —
  `ConsentScope`, `GranteeType`, `ConsentStatus`.
- `openbank-notification-service/src/main/kotlin/com/openbank/notification/application/NotificationConsumer.kt`
  — `maybeSendPush`, and the per-channel dispatch that skips EMAIL.
- `openbank-notification-service/src/main/resources/db/migration/V11__notification_preferences.sql`
  — `marketing_push BOOLEAN NOT NULL DEFAULT TRUE`.
