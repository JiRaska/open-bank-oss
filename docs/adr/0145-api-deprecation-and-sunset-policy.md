# ADR-0145 — API deprecation and sunset policy

Date: 2026-07-02
Decision-Status: Accepted   <!-- Proposed | Accepted | Superseded by ADR-NNNN | Deprecated | Rejected -->
Delivery-Status: Planned    <!-- Planned | Partial | Shipped | N/A — decision-only -->
Author(s): jiri.raska

## Context

ADR-0048 D6 already ships the *mechanism*: `ApiVersionResponseFilter`
reads `openbank.api.deprecated-paths` and `openbank.api.sunset-date` and
emits `Deprecation` / `Sunset` / `Link: rel=successor-version` headers;
`rules.yaml: api_deprecation.min_sunset_window_days: 180` is a real,
already-declared constant. What is missing is not plumbing — it is the
*policy* that decides when to populate `deprecated_paths`, who decides a
change is breaking enough to require a new major, and how TPP/partner
consumers (PSD2 XS2A per ADR-0090) are notified outside of an HTTP header
they may never read (e.g. a developer-portal changelog, a webhook per
ADR-0059). None of that is decided; `deprecated_paths` is an empty list
today, and it is undocumented whether it stays empty because nothing has
ever been deprecated, or because nobody has decided the process for
deprecating something.

This matters more for OpenBank than for an average API: a breaking change
to `/api/v1/*` propagates to real TPPs under a PSD2 authorization, and
ADR-0090's Berlin Group NextGenPSD2 profile is itself a moving external
standard OpenBank must track.

## Decision

We will adopt the following as policy, on top of the existing D6 mechanism:

1. **What requires a major bump.** Removing a field, tightening a
   validation, changing a status-code contract, or removing an endpoint are
   breaking; adding an optional field or a new endpoint is not. This
   classification is derived the same way ADR-0048 already derives it —
   from the `oasdiff` diff of `openapi.yaml`, not from commit type — and a
   breaking diff without a corresponding major bump fails CI (the
   `api-contract` gate; see ADR-0144 for its graduation from advisory to
   block).
2. **Minimum sunset window stays 180 days** (already declared) for any
   externally-reachable path (PSD2 XS2A surfaces, developer-portal-listed
   endpoints); internal-only service-to-service paths may declare a shorter
   window per-path.
3. **Notification is two-channel, not header-only**: the existing
   `Deprecation`/`Sunset` headers (machine channel) plus an entry in the
   developer-portal changelog and an outbound webhook via the ADR-0059
   oversight-webhook mechanism (human/operational channel), fired the day
   `deprecated_paths` gains a new entry.
4. **Sunset requires evidence of zero live traffic**, not just elapsed time:
   the gate additionally checks Tier-B HTTP metrics (ADR-0077) show zero
   requests to the path over the preceding 30 days before the path may be
   removed from the router, even after the 180-day window elapses.

## Alternatives considered

- **Never break anything (permanent path aliasing).** Rejected — PSD2's own
  Berlin Group profile evolves under external control; OpenBank cannot
  promise an infinite-lifetime contract for a standard it does not own.
- **Per-endpoint versioning instead of per-major-URL versioning.** Rejected
  — contradicts ADR-0048's existing decision that `openapi.yaml:info.version`
  major tracks the URL major fleet-wide; per-endpoint versioning would
  fragment that invariant service by service.
- **Rely on the HTTP headers alone, no separate notification channel.**
  Rejected — a TPP's automated client reads headers; a TPP's human
  integrator, who has to schedule the migration, does not poll HTTP headers.
  ADR-0059's webhook channel already exists for exactly this kind of
  operational signal.

## Consequences

**Positive**
- Closes the last undecided piece of the API-contract version axis
  (ADR-0048); `deprecated_paths` staying empty becomes a verifiable fact
  ("nothing has been deprecated yet") rather than an ambiguous one.
- Gives PSD2 TPP integrators a concrete, written commitment (180 days,
  two-channel notice) instead of an implicit one.

**Negative**
- The zero-traffic-before-sunset check (item 4) can indefinitely delay a
  path's removal if even one stale client keeps polling it — intentional
  (never break a live consumer), but it means "deprecated" is not the same
  as "will definitely be gone in 180 days."
- Requires the `api-contract` gate to move from advisory to block (tracked
  under ADR-0144) before item 1 has real teeth; until then this ADR is
  policy without enforcement.

**Neutral**
- Does not change anything about internal (non-`openapi.yaml`) contracts;
  Kafka/AsyncAPI event versioning is out of scope (see ADR-0006).

## Compliance impact

- PCI DSS: not applicable directly.
- DORA: Art. 9 (ICT change-management controls covering externally-facing
  interfaces).
- GDPR: not applicable.
- PSD2: RTS on SCA & CSC, Art. 30(4) — TPPs must receive advance notice of
  interface changes; this ADR is the concrete implementation of that
  obligation for OpenBank's XS2A surface (ADR-0090).
- CNB: aligns with ČNB supervisory expectation of a documented, stable TPP
  interface lifecycle.

## References

- ADR-0048 (decouple API contract version from release version) — D6 ships
  the mechanism this ADR turns into policy.
- ADR-0090 (PSD2 XS2A Berlin Group base + ČOBS profile) — the primary
  external consumer this policy protects.
- ADR-0059 (outbound oversight webhooks) — reused as the notification
  channel.
- ADR-0077 (observability three-pillar strategy) — Tier-B HTTP metrics used
  as the zero-traffic sunset evidence.
- ADR-0144 (gate graduation) — governs when `api-contract` moves from
  advisory to block.
- `openbank-libs/governance/rules.yaml: api_deprecation`.
