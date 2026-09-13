---
date: 2026-06-07
decision-status: accepted
delivery-status: partial
authors: [Jiří Raška]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [mobile-app, observability, security-ops]
summary: "The customer app gets a hidden in-app diagnostics surface whose security boundary is build-gating (compiled out of release binaries), not the tap gesture; panels are read-mostly with tiered guarded actions."
---

# In-app diagnostics / debug surface (customer app)

**Delivery note (updated 2026-08-26):**
- **Core build-gating** — ✅ Shipped: 7-tap activation gesture, build-info/edge `/api/v1/info` panel.
- **UI panels** — ✅ Shipped, correcting the previous note which listed them as pending: the
  feature-flag, session-claims, SCA-challenge and proximity panels are all present in `DebugMenu`.
- **Reachability** — ⚠️ Was broken until 2026-08-26, and the two notes above hid it. The gate was
  `DebugGate.isDebugBuild` (`Platform.isDebugBinary` on iOS), but **TestFlight builds are compiled
  Release**, so the gesture was a no-op in every build anyone ever installed — the surface worked
  only when run from Xcode, i.e. exactly where a debugger is already attached and the panel is
  least needed. Reported as "the debug feature disappeared"; it had never been reachable off a
  developer's own machine. The gate is now the DISTRIBUTION CHANNEL (`BuildChannel`): developer
  builds and TestFlight yes, App Store never. Invariant 1 is unchanged — the boundary is still the
  build, decided offline with no network in the trust path.

  Lesson for future delivery notes: "shipped" was asserted from the code existing, not from the
  feature being reachable in a distributed build. A gate that compiles is not a gate that runs.

## Context

The KMP customer app (ADR-0064) talks to the customer-facing edge (ADR-0065)
and is acquiring real flows: onboarding that creates a real party (ADR-0069),
passwordless auth (ADR-0066), SCA device approval (ADR-0021), and — once the
fleet adopts it — feature flags (ADR-0067). On a device, when something
misbehaves, an engineer or a sandbox tester currently has **no in-app way** to
answer the first questions of any banking-app incident:

- *Which backend build am I actually talking to?* (edge version, git commit, API
  contract version)
- *What is the correlation-id of the request that just failed?* (so support can
  reconstruct the cross-service trace — DORA Art. 11 24h reconstruction)
- *Which feature-flag variant did this user resolve to, and why?* (ADR-0067
  `EvaluationReason`)
- *What is the token/SCA/session state on this device right now?*

Today the only answers come from rebuilding with logging or attaching a
debugger — neither is available to a sandbox tester, and neither works on iOS in
the field. Mobile banking apps universally solve this with a **hidden developer
menu** behind a secret gesture. The risk is equally universal: a debug surface
is an information-disclosure and privilege-escalation vector if it ships in the
production binary or exposes secrets. This ADR fixes the **security
invariants** of that surface so the gesture is a convenience, not the security
boundary.

This is explicitly a **diagnostics read surface for non-production builds**, not
an operator control plane (ADR-0047) and not engineer-owned flag rollout
(ADR-0067) — it *observes* those systems, it does not become a second way to
change them.

## Decision

We will add an **in-app diagnostics surface** to the customer app, governed by
the following non-negotiable invariants.

**1. Build-gating is the security boundary, not the gesture.** The debug surface
is compiled out of release binaries. Short term (sandbox / F1) it is gated by a
single `DebugGate.isDebugBuild` flag derived from the platform build type
(`BuildConfig.DEBUG` on Android, `Platform.isDebugBinary` on Kotlin/Native iOS);
the target state, **before any store/production build exists**, is a hard
flavor / source-set split where the `debug` UI code is not linked into the
release artifact at all. The activation gesture (N taps on the brand logo) only
*opens* an already-permitted surface — it never gates security.

**2. Read-mostly; guarded actions.** Pure information panels (build info, flag
evaluation result, correlation-ids, redacted request log) are free. Actions are
allowed but tiered by what they touch:

- **Local-destructive** (wipe app data, clear session, reset preferences) affect
  **only on-device state** — tokens, PIN, cache, prefs — and drop the app back to
  onboarding. They **must never** delete or mutate server-side party / account /
  audit data; sandbox server state is reset out-of-band (seed/reset script), not
  from a phone. Guard: **tap-to-arm confirmation**, deliberately *not* re-auth — a
  broken app must stay resettable, and biometric/SCA could lock the user out of
  recovery.
- **Backend-effecting** (e.g. "go to onboarding", which on completion creates a
  **real** party, ADR-0069) must be clearly labelled as hitting the edge, and
  confirmed.
- **Customer-context mutations against the server** (force token refresh,
  environment switch, flag override) require a fresh local authentication
  (biometric / SCA, ADR-0021/0066).

Money-path flag flips are **never** performed here — those go through four-eyes
(ADR-0067, `libs/foureyes`).

**3. A hard redaction allowlist — derived values only.** The surface may show
**derived, non-reversible** facts, never raw secrets. Forbidden in every panel,
log line, and share/export: access/refresh/ID tokens, SCA private keys, OTP
secrets, signed assertions, SCA `DynamicLinkingData` (amount/payee), raw
request/response bodies containing PII, and pre-sanitization audit payloads.
Permitted derived equivalents: token *expiry timestamp* (not the token), SCA
challenge *status/method* (not the payload), `party_id` (the pseudonymous
subject already in the JWT, ADR-0069), `X-Correlation-ID` / `X-Request-ID`. The
redaction passes through one allowlist used by both the live request log and any
export, so "share diagnostics" cannot leak what a panel hides.

**4. It mirrors `openbank-libs` contracts, it does not reimplement them.** Build
and stack info comes from calling the edge's `GET /api/v1/info`
(`openbank-libs/web/ServiceInfoResource`, `PermitAll`); correlation/trace ids are
the `X-Correlation-ID` / `X-Request-ID` headers from
`openbank-libs/web/CorrelationIdFilter`; flag evaluation + reason come from the
ADR-0067 `FeatureClient` shape. The app is a JVM/Native client and cannot import
the libs JAR, so it reproduces the *contract* (header names, `/api/v1/info`
response shape) — one source of truth, two implementations.

**5. No production analytics side effects.** A local flag override in the debug
menu must **not** emit a `FlagExposure` event (ADR-0067) — debug observation is
not production measurement.

## Alternatives considered

- **Runtime flag only (`if (BuildConfig.DEBUG)`) with no flavor split** — simple,
  but the debug code (strings, endpoint switches, panel logic) remains in the
  release binary and is reachable by tampering. Accepted *as the F1 interim*
  under invariant 1, rejected as the *end state*.
- **Remote/web diagnostics page instead of in-app** — would need its own
  authenticated surface and still couldn't read on-device state (token/SCA/keychain).
  Doesn't answer the device-local questions; rejected.
- **Reuse the admin-ui operator tooling** — wrong audience and wrong trust
  boundary; the operator console (ADR-0068) is staff-only and server-side. The
  customer app needs a *client-side* read surface. Rejected.
- **Ship nothing, rely on logs/debugger** — not available to sandbox testers, not
  usable on iOS in the field, and gives support no correlation-id to pull a
  trace. Rejected.

## Consequences

**Positive**
- Sandbox testers and engineers can self-serve the first incident questions on a
  real device, and hand support a correlation-id that pulls the full trace.
- The security invariants are written down once and enforced in code (gate +
  redaction allowlist), so the surface can grow without re-litigating safety.
- Reusing `/api/v1/info` and the correlation headers means zero new backend work.

**Negative**
- The interim runtime gate (F1) leaves debug code in the binary until the
  flavor/source-set split lands; this is an accepted, time-boxed risk while no
  production build exists, tracked as a follow-up.
- A redaction allowlist is a maintenance obligation: every new panel must be
  reviewed against it.

**Neutral**
- Adds a small `debug` code path to `shared` (gate) and `composeApp` (UI).
- The surface is expected to grow (network throttling, deep-link launcher,
  design-token inspector); those land as additive panels under the same invariants.

## Compliance impact

- PCI DSS: not applicable (no PAN/card data in the app surface).
- DORA:    Art. 11 (ICT continuity) — exposing `X-Correlation-ID` materially
           improves the 24h incident-reconstruction path.
- GDPR:    Art. 5(1)(c) data minimisation, Art. 32 security — the redaction
           allowlist (invariant 3) is the control; no PII or secrets surfaced.
- PSD2:    SCA (ADR-0021) secrets explicitly excluded from the surface
           (invariant 3); re-auth required for customer-context mutations
           (invariant 2).
- CNB:     no direct impact; diagnostics is a non-production read surface.

## References

- ADR-0021 — SCA decoupled device approval (secrets excluded from surface)
- ADR-0064 — Customer app: Kotlin Multiplatform
- ADR-0065 — Customer-facing edge and Keycloak realm (`/api/v1/info`, headers)
- ADR-0066 — Passwordless customer authentication (build hardening, re-auth)
- ADR-0067 — Feature flags and experimentation (`FeatureClient`, `EvaluationReason`)
- ADR-0069 — Customer onboarding journey (`party_id` claim)
- `openbank-libs/web/ServiceInfoResource`, `web/CorrelationIdFilter`

## Implementation note (Phase 1)

Phase 1 implemented in openbank-app. Build-gated (`DebugGate.isDebugBuild` — Android: `BuildConfig.DEBUG`, iOS: `Platform.isDebugBinary`). Panels: build info (edge `/api/v1/info`), feature flags (F2 TODO), session state (F2 TODO). Activation: 7-tap on brand logo within 3 s via `Modifier.debugTrigger`. See `shared/src/commonMain/kotlin/tech/openbank/app/debug/` and `composeApp/.../debug/`.
