---
date: 2026-07-25
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [privacy-gdpr, sca, accounts]
summary: "Adds a GDPR_ONLY_SCOPES activation path that skips SCA, one consent aggregate per party under a fixed internal granteeId, and local consentId tracking so ConsentRevoked's missing scopes field is never guessed."
---

# ADR-0205 — Marketing consent forwarder shape and SCA-exempt activation

## Context

ADR-0198 D3 says party-service's `UpdateMarketingConsentCommand` becomes a forwarder to
consent-service, and D1 says the three `MARKETING_COMMS_*` scopes are "not SCA-gated." Trying to
build the forwarder and the party-service projection consumer surfaced three decisions ADR-0198 left
implicit, each discovered by reading the actual code rather than assumed:

**Gap 1 — there is no SCA-exempt activation path in consent-service, so "not SCA-gated" does not hold
today.** `ConsentService.activateConsent` (`ConsentService.kt:91-116`) unconditionally calls
`scaChallengeClient.getChallenge(scaSessionId)` and requires `status == "COMPLETED"` before any
consent — regardless of scope — transitions out of `PENDING_SCA`. `ConsentResource`'s only activation
endpoint, `POST /{id}/activate?scaSessionId=<uuid>`, has the same unconditional requirement. This is
not specific to marketing: `TELEMETRY_RUM` carries the identical "not SCA-gated" comment in
`Consent.kt` and has **zero callers anywhere in the codebase** — it has never been exercised through
this activation flow, so the gap was latent until this ADR's implementation attempt found it. A
marketing-consent toggle forced through a real OTP/push/biometric SCA ceremony would be a materially
worse UX than the "flip a switch" GDPR opt-in ADR-0198 D1 promised, and is not what "not SCA-gated"
was supposed to mean.

**Gap 2 — the customer-facing toggle is one boolean, but D1 created three independent scopes.**
`consent_marketing` (`parties` table) is a single `BOOLEAN`. ADR-0198's Neutral section says the
toggle "writes the EMAIL and IN_APP scopes and leaves PUSH to the notification-preferences screen" —
but never decides whether that is one consent aggregate carrying both scopes, or two separate
consents that could independently succeed or fail. Two aggregates means the boolean can't represent a
partial state (email granted, in-app failed) without inventing a new one.

**Gap 3 — `ConsentRevoked` carries no `scopes` field.** (`ConsentEvents.kt:28-38`): only `aggregateId`,
`partyId`, `granteeId`, `reason`. A party-service consumer projecting revocation into
`consent_marketing` cannot tell from the event alone whether the revoked consent was ever a marketing
one — guessing wrong in either direction is a real GDPR defect (a marketing revoke that doesn't clear
the flag, or an unrelated future consent's revoke that incorrectly clears it).

None of these are ADR-0198 mistakes in the sense of being wrong — D1 is shipped and correct as far as
it goes (#2408). They are the next layer down, found only by trying to build D3, which is exactly why
this is a separate ADR rather than a silent implementation detail: each is a real design decision an
implementer would otherwise make unilaterally and inconsistently.

## Decision

**D1 — A new `GDPR_ONLY_SCOPES` allow-list in consent-service; consents made entirely of those scopes
activate immediately, without an SCA challenge.** Mirrors the existing `AISP_SCOPES` allow-list
pattern exactly (`Consent.kt` companion object). Initial membership: `TELEMETRY_RUM`,
`MARKETING_COMMS_EMAIL`, `MARKETING_COMMS_PUSH`, `MARKETING_COMMS_INAPP` — this also retroactively
fixes `TELEMETRY_RUM`'s pre-existing, never-exercised gap. `CreateConsentUseCase.createConsent` gains
the rule: if `command.scopes.isNotEmpty() && command.scopes.all { it in GDPR_ONLY_SCOPES }`, the
consent is created directly in `ConsentStatus.ACTIVE` (skipping `PENDING_SCA`) and emits
`ConsentGranted` immediately — no separate activation call, no `scaSessionId` needed. **A mixed
request (a GDPR-only scope alongside an AISP/PISP/CBPII/AGENT_INITIATE scope) is rejected outright**,
not silently downgraded to the SCA-required path — mixing weakens the SCA guarantee for the
SCA-required scope by riding it in on a GDPR-only request, and a caller that needs both must make two
separate consent requests. `AGENT_QUERY`/`AGENT_NOTIFY`/`AGENT_ANALYZE` are deliberately **not**
added to `GDPR_ONLY_SCOPES` in this ADR — their SCA requirement is unresolved by ADR-0198 and
reclassifying them is out of scope here; they keep going through the existing SCA-gated path
unchanged.

**D2 — One consent aggregate per party for the marketing toggle, covering both
`MARKETING_COMMS_EMAIL` and `MARKETING_COMMS_INAPP` together.** Matches the single
`consent_marketing` boolean exactly — there is no state the toggle needs to represent that a combined
grant/revoke can't express, and D1's "all-or-nothing" activation rule means this is a single API call
with a single failure mode, not two calls that can partially succeed. `MARKETING_COMMS_PUSH` is
explicitly excluded from this aggregate — it has its own screen and its own consent lifecycle per
ADR-0198's Neutral section, granted/revoked independently, never bundled with the toggle.

**D3 — A fixed, well-known `granteeId` for this internal grant flow: `"party-service:marketing-comms"`,
`granteeType = INTERNAL_SERVICE`.** No such convention exists anywhere in the codebase today — this
ADR establishes the first one. The fixed id is what lets the party-service consumer (D4) recognise
"this Granted/Revoked event is the marketing toggle" without needing to inspect scopes at all,
and lets an operator list every marketing consent fleet-wide via the existing
`GET /api/v1/consents/grantee/{granteeId}` endpoint with zero new query surface.

**D4 — Party-service's projection consumer resolves `ConsentRevoked`'s missing scopes by keying off
its own prior `ConsentGranted` record, never by inspecting the revoke event.** A new party-service
table, `party_marketing_consent(party_id UUID PRIMARY KEY, consent_id UUID NOT NULL, granted_at
TIMESTAMPTZ NOT NULL)`, populated by a new consumer subscribing to consent-service's outbox topic,
filtered to `granteeId == "party-service:marketing-comms"`:
- On `ConsentGranted`: upsert `(partyId, aggregateId, occurredAt)`; set `parties.consent_marketing =
  true`, `consent_marketing_updated_at = occurredAt`.
- On `ConsentRevoked`: look up the local table by `aggregateId`. A match confirms this was a marketing
  consent (the filter on `granteeId` at the topic-consumption level already guarantees this — the
  local table's job is keying `partyId`, not re-verifying "was this ours"); delete the row, set
  `consent_marketing = false`, `consent_marketing_updated_at = occurredAt`.
- On `ConsentExpired`: identical handling to `ConsentRevoked` — an expired marketing consent is no
  longer active, and the projection must not keep reporting `true` past `validTo`.

This needs no synchronous cross-service call and no scope inspection of the revoke/expiry event —
correctness comes entirely from the `granteeId` filter at subscription time plus the consumer's own
prior state, which is exactly the shape ADR-0118's existing `PARTY_ERASED` consumers already use.

**D5 — The forwarder itself is out of scope for this ADR and ships as its own PR after D1–D4.**
`UpdateMarketingConsentCommand` calling consent-service's `POST /api/v1/consents` with the D2/D3
shape, on the SCA-exempt D1 path, is a small, mechanical change once D1–D4 exist — deliberately not
designed further here, to keep this ADR to the decisions that were genuinely blocking rather than
padding it with implementation detail that doesn't need a decision.

## Alternatives considered

- **Force the marketing toggle through the existing SCA flow, no new activation path.** Zero
  consent-service change. Rejected: SCA is a heavyweight ceremony (OTP/push/biometric) designed for
  payment authorization; requiring it for a GDPR marketing opt-in is a materially worse UX than "flip
  a switch," and it isn't what ADR-0198 D1 promised when it said "not SCA-gated." It would also leave
  `TELEMETRY_RUM`'s identical gap unfixed.
- **Two separate consent aggregates (one per channel) instead of D2's combined one.** More precisely
  mirrors the underlying per-channel scopes and would let a future UI expose independent EMAIL/IN_APP
  toggles without a data model change. Rejected for now: `consent_marketing` is one boolean today, a
  two-aggregate design has no state to represent partial grant/revoke without inventing a new
  database column ADR-0198 didn't ask for, and the ADR-0198 Neutral section already deferred a
  per-channel UI to `openbank-app` as a future product decision — building the two-aggregate plumbing
  ahead of that decision is speculative.
- **Have the party-service consumer call consent-service synchronously on `ConsentRevoked` to fetch
  the original consent's scopes.** Would work without D3/D4's local table. Rejected: makes the
  consumer's correctness depend on a live network call inside an event handler, which is fragile
  (the exact anti-pattern ADR-0195 already rejects for cached-vs-live consent checks, applied in
  reverse) — a transient consent-service outage would either stall the consumer or force a guess.
- **Skip the `granteeId` convention; have the consumer inspect `ConsentGranted.scopes` directly and
  track marketing state by scope membership instead of by a fixed grantee.** Works equally well for
  D4's Granted-side logic. Rejected because it does nothing for the Revoked-side problem (gap 3) —
  `ConsentRevoked` has no scopes field regardless of how Granted is filtered — so the local-table
  mechanism is needed either way, and the granteeId filter is what makes topic consumption cheap
  (skip everything not ours before touching the table) rather than load-bearing for correctness by
  itself.

## Consequences

**Positive**
- Fixes a real, previously undiscovered defect in `TELEMETRY_RUM`, not only enables `MARKETING_COMMS_*`.
- The forwarder (D5) becomes a small, well-specified follow-up instead of a PR that also has to
  invent these four decisions under review pressure.
- `party_marketing_consent` gives an operator a durable, queryable record of exactly which party has
  an active marketing consent aggregate, independent of the `parties.consent_marketing` projection —
  useful for reconciliation if the projection and consent-service ever drift.

**Negative**
- `GDPR_ONLY_SCOPES` is a second allow-list alongside `AISP_SCOPES` on the same enum, and the two
  must stay disjoint by construction (D1's own logic) — a future scope added to both would let a
  caller either skip SCA it should have, or force SCA on a request meant to be exempt, and nothing
  currently guards against a scope appearing in both sets besides code review.
- A new party-service table plus a new Kafka consumer is real new infrastructure — a NetworkPolicy
  edge to consent-service's outbox topic, OPA sidecar policy update, and a migration, none of which
  existed for party-service before this.
- The `granteeId` string `"party-service:marketing-comms"` is now a de facto contract between two
  services with no schema enforcing it — a typo in either the consumer's filter or the eventual
  forwarder's grant call silently breaks the whole mechanism with no compiler or CI signal. Worth a
  shared constant if both call sites end up in Kotlin, though they are different services so it
  cannot be a shared compiled dependency without adding one.

**Neutral**
- D1's mixed-scope rejection means a caller wanting both a marketing scope and an SCA-required scope
  in one flow must sequence two requests; no current caller needs this, so it costs nothing today.

## Compliance impact

- PCI DSS: not applicable — no cardholder data in a consent record or its activation path.
- DORA: not applicable — no new ICT third party; consent-service and party-service are both internal.
- GDPR: Art. 7 (a consent that requires an SCA ceremony to grant is a heavier burden on the data
  subject than Art. 7 requires for a data-processing consent, which D1 corrects); Art. 17 erasure is
  unaffected — `party_marketing_consent` holds only a `consentId` and timestamp, no data beyond what
  ADR-0118's existing party erasure cascade already reaches through the `parties` row itself.
- PSD2: not applicable — none of `GDPR_ONLY_SCOPES`' members are AISP/PISP/CBPII scopes; D1's
  mixed-request rejection is precisely what keeps an SCA-required PSD2 scope from ever riding in on
  an exempt request.
- CNB: not applicable.

## References

- [ADR-0198](0198-marketing-consent-as-a-first-class-consent-service-scope.md) — D1 (the three scopes,
  "not SCA-gated"), D3 (the forwarder), D4 (the notification-service gate this doesn't touch).
- [ADR-0195](0195-mcp-server-caller-authentication-and-psd2-consent-binding.md) — the live-vs-cached
  consent-check reasoning the rejected synchronous-lookup alternative would have inverted.
- [ADR-0118](0118-gdpr-data-lifecycle-and-retention.md) — the `PARTY_ERASED`-consumer shape D4's
  projection consumer follows.
- `openbank-consent-service/src/main/kotlin/com/openbank/consent/application/usecase/ConsentService.kt`
  — `activateConsent`'s unconditional SCA requirement (gap 1).
- `openbank-consent-service/src/main/kotlin/com/openbank/consent/domain/event/ConsentEvents.kt` —
  `ConsentRevoked`'s missing `scopes` field (gap 3).
- `openbank-party-service/src/main/resources/db/migration/V12__consent_capture.sql` — the single
  `consent_marketing` boolean D2's combined-aggregate decision matches.
