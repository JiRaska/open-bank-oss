# 64. Customer-facing app: Kotlin Multiplatform + Compose Multiplatform

Date: 2026-06-05
Status: Accepted
Author(s): OpenBank platform

## Context

The backend is far along (~30 `openbank-*` services), but the **only** frontend is
`openbank-admin-ui` — an operator/banker console (Next.js), explicitly the *admin* plane
(ADR-0056: the admin-UI BFF is the sole browser→cluster path for **operators**, gated on
Keycloak operator roles). There is **no customer-facing application** and none of the
plumbing a retail app needs:

- **No customer edge.** ADR-0056's BFF is operator-only; a retail app must not share it
  (different principal, different realm, different blast radius).
- **SCA is unfinished (ADR-0021, Proposed).** Device-based Strong Customer Authentication
  (push/biometric) currently auto-approves — audit finding K2, a full SCA bypass. ADR-0021
  requires a **decoupled device-approval channel**: the enrolled device signs the challenge
  bound to `DynamicLinkingData` (amount, currency, creditor) with a FIDO2/Secure-Enclave key
  (RTS Art. 5 dynamic linking). A first-party mobile app *is* that "enrolled device" — the
  app and ADR-0021 are two halves of the same mechanism.
- **No customer IAM.** Keycloak today serves operator login only; retail needs its own realm
  and a public PKCE client.

The only recorded plan was a stale one-liner in [README.md](../../README.md)
(`Mobile | Flutter 3.x (planned)`) — no ADR, no detail. The product requirement is now
explicit: **cover all platforms (iOS, Android, and later web/desktop) from a single,
banking-grade, maintainable codebase**, consistent with this repo's governance-as-code and
code-as-single-source-of-truth ethos (ADR-0029) and its design-first API contract
(ADR-0005).

A single-platform native app (SwiftUI) would give the best per-platform security and polish
but, by definition, does not satisfy "all platforms" — it implies 2–3 parallel native
codebases (Swift + Jetpack Compose + web), tripling maintenance and drifting from one shared
domain model.

## Decision

**We will build the customer-facing application on Kotlin Multiplatform (KMP) with Compose
Multiplatform for the UI, in a dedicated repository, and reuse the backend's Kotlin domain
contracts as shared code.**

Concretely:

1. **Shared Kotlin core.** Money types, value objects, validation, and DTOs are written once
   in a `:shared` KMP module and reused across all targets. Where it makes sense, this core
   reuses or re-publishes contract types from `openbank-libs` so the app and the backend
   share **one** definition of the domain — no Dart/TS re-translation that rots.

2. **API clients generated from `openapi.yaml`** (ADR-0005, design-first) into the shared
   module (`openapi-generator`, Kotlin client). The app consumes the **API-contract version
   axis** (`openapi.yaml:info.version` → `/api/v{N}`, ADR-0048), never hand-rolled URLs.

3. **Platform security via `expect`/`actual`.** Security-critical operations are declared in
   the shared module and implemented natively per platform — full native power, no
   plugin-roulette:
   - **iOS** — Secure Enclave key generation, biometric-gated Keychain, ASAuthorization /
     passkeys, App Attest, written as a thin Swift `actual`.
   - **Android** — Android Keystore (StrongBox where present), BiometricPrompt, Credential
     Manager / passkeys, Play Integrity.
   This is exactly what ADR-0021 needs: the device signs the SCA challenge + dynamic-linking
   data with a hardware-backed key the server verifies against the enrolled public key.

4. **UI in Compose Multiplatform.** One UI codebase for Android + iOS now; Desktop and
   Web (Wasm) are **deferred targets** the same codebase can light up later (see Negative —
   Compose Web/Wasm is the least mature target).

5. **Dedicated repository, contract-coupled not code-coupled to the monorepo.** The app lives
   in its own repo (`openbank-app`, name TBD): a different toolchain (Gradle/Kotlin/Xcode +
   App Store/TestFlight cadence) than the Quarkus monorepo, kept out of the path-scoped CI
   (ADR-0040). Coupling to the backend is through **published contracts** (the shared Kotlin
   artifact + generated OpenAPI clients), preserving the contract axis and governance.

6. **Customer edge is a separate decision.** A retail BFF/edge + Keycloak customer realm is
   required before the app can reach the cluster; it gets its **own ADR** (mirroring the
   ADR-0056 pattern for the customer plane) and is **not** in scope here.

7. **Sandbox shortcuts are explicit and flagged.** For the sandbox we skip SMS OTP entirely
   and run device attestation in *advisory* mode, behind feature flags so every shortcut is
   visible and reversible. None of these ship to a production posture without the flag flip.

This decision also corrects the stale README stack line (Flutter/Next 14/React 18 → KMP;
admin-ui is actually Next 16/React 19).

## Alternatives considered

- **Flutter 3.x** — most mature cross-platform stack today, pixel-identical UI on every
  target, huge ecosystem. Rejected as primary because Dart is a **second language island**:
  zero code sharing with the Kotlin backend, the domain model must be re-translated and kept
  in sync by hand (against ADR-0029), and hardware security (Secure Enclave signing for
  ADR-0021) still requires hand-written Swift via MethodChannel — so you write the native
  glue anyway, without the shared-core upside. Strong, conservative fallback if KMP's younger
  ecosystem proves blocking.
- **Native per-platform (SwiftUI + Jetpack Compose + web)** — best per-platform security and
  polish. Rejected: 2–3 parallel codebases, no shared domain, triples maintenance — directly
  contradicts the "all platforms from one codebase" requirement.
- **PWA (Next.js, reuse admin-ui stack)** — fastest to test on iPhone, shares web stack.
  Rejected for the customer app: no Secure Enclave key custody, no App Attest; cannot satisfy
  ADR-0021 hardware-backed dynamic linking. Acceptable only as a throwaway demo, not the
  product.

## Consequences

**Positive**
- One Kotlin domain shared backend↔app — single source of truth, on-brand with ADR-0029.
- Full native security per platform via `expect`/`actual`; unblocks ADR-0021 device approval.
- iOS + Android from one codebase now; Desktop + Web reachable later without a rewrite.
- API clients flow from `openapi.yaml`, keeping the contract axis honest (ADR-0005/0048).

**Negative**
- KMP/Compose ecosystem is younger than Flutter; **Compose Web (Wasm) is the least mature
  target** — if web becomes first-class urgently, this is the main risk and a reason to
  revisit Flutter.
- Adds an Xcode/Apple toolchain dependency (Mac, later Apple Developer account for TestFlight).
- Requires the follow-up customer-edge ADR + Keycloak customer realm before end-to-end flows.

**Neutral**
- New repo to govern; contract coupling (not source coupling) to the monorepo.
- Compose UI gives a unified look; matching platform-native feel on iOS is deliberate effort.

## Compliance impact

- **PSD2**: directly enables RTS (EU) 2018/389 Art. 5 dynamic linking via hardware-backed
  device approval (ADR-0021). SMS-OTP skipped **in sandbox only**, flagged.
- **PCI DSS**: card PAN/CVV never stored on device; card features use tokenization — design
  constraint for the card surface, no PAN in shared state.
- **GDPR**: customer data on device protected by hardware-backed, biometric-gated storage;
  minimization in local caches.
- **DORA**: app is a third-party-facing channel; resilience/telemetry inherits backend posture.
- **CNB**: not applicable at the app-stack-choice level.

## References

- ADR-0005 — OpenAPI design-first (client generation source).
- ADR-0021 — SCA decoupled device approval (the app is the enrolled device).
- ADR-0029 — Versioning, release & governance as code (single source of truth).
- ADR-0048 — Decouple API-contract version from service-release version.
- ADR-0056 — Admin-UI BFF as the sole browser→cluster path (pattern for the future customer edge).
- [README.md](../../README.md) — stack table (corrected by this ADR).
