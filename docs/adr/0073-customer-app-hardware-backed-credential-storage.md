# Hardware-backed credential storage for the customer app

Date: 2026-06-08
Status: Accepted
Delivery-Status: Planned
Author(s): Jiří Raška

## Context

ADR-0064 chose Kotlin Multiplatform partly *because* it gives full native access
to the Secure Enclave / Android Keystore for credential storage. That rationale
was never turned into a binding contract, and the `SecureCredentialStore`
`expect/actual` triad has drifted into three different security postures — a gap
the customer-app dossier (ADR-0074) surfaces as `decision missing`:

- **iOS** (`SecureCredentialStore.ios.kt`) is real: tokens in the Keychain with
  `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` (encrypted, this-device-only,
  excluded from backup). **But** the PIN is stored as the **raw value** (in the
  Keychain, and — when the Keychain bridge throws — falling back to
  **`NSUserDefaults` in plaintext**), not a derived verifier; and `verifyPin`
  short-circuits on `stored.length != pin.length`, so the comparison **leaks PIN
  length** and is not constant-time.
- **Android** (`SecureCredentialStore.android.kt`) is an **in-memory placeholder**
  — `access`, `refresh` and `pin` are plain `var` fields. Its own header says
  "F0 PLACEHOLDER — in-memory only. DO NOT store real tokens here."
- **SCA device key on Android** (`ScaDeviceKey.android.kt`) is a stub —
  `ensureKeyPair() = true` with a `TODO(ADR-0066-S2b): AndroidKeyStore`.

The timing is sharper than it looks. The app is already at **F1** —
`AppConfig.useFakeData = false` and the customer edge is live — so real account
data flows today; only the login screen is still a placeholder, so a real OAuth2
refresh token is not yet issued. That gap is therefore **imminent, not distant**:
the moment real PKCE login lands (the current F1 TODO), the Android in-memory
placeholder would hold a real refresh token in process memory with no hardware
backing — and a PIN already persists on iOS, today, in a form that is not safe
(see below). This must be closed *before* real-token login ships, not after. With
passwordless auth (ADR-0066) and SCA decoupled approval (ADR-0021) both landing
on this store, the secrets it holds are exactly the ones a banking threat model
cares about. There is no recorded decision on where they live, how the key is
invalidated on biometric change, or what blocks shipping the placeholder.

## Decision

We will define a **binding secure-storage contract** for the customer app and
gate the F1→F2 transition on meeting it. No real customer token or SCA key is
written to disk until the platform it runs on satisfies the contract below.

**1. Hardware-backed by default; the in-memory placeholder is F0-only.** The
Android in-memory `SecureCredentialStore` is explicitly an F0 artefact and MUST
NOT reach a build that talks to the live edge with real tokens. Android tokens
move to **Android Keystore (StrongBox where the device reports it)** for the key
material, wrapping an **EncryptedSharedPreferences** (or Tink/Jetpack Security
equivalent) blob. iOS stays on the Keychain as-is for tokens.

**2. PINs are never stored in the clear, anywhere.** The PIN is reduced to a
**derived verifier** (a salted KDF — Argon2id/PBKDF2 — output), and the verifier
lives in hardware-backed storage (Keychain / Keystore), never in `NSUserDefaults`
or plain `SharedPreferences`. The iOS `NSUserDefaults` PIN fallback is **removed**;
a Keychain failure is surfaced as an error, not silently downgraded to plaintext.
Verification **must become constant-time** — the current iOS path leaks PIN length
via an early `length` short-circuit and must be replaced with a fixed-time compare
over the derived verifier.

**3. Access tiers match the secret's blast radius.** Access/refresh tokens use
*device-unlocked, this-device-only* and intentionally do **not** require
biometrics on every read (so background token refresh works — the existing iOS
choice, made explicit and mirrored on Android). The **SCA signing key** and any
payment-authorising key require **user authentication on use**
(`setUserAuthenticationRequired` / `kSecAccessControlBiometryCurrentSet`) and are
**non-exportable** — generated in and never leaving the secure element.

**4. Invalidate on biometric/credential change.** Keys gating SCA use
`biometryCurrentSet` (iOS) / `setInvalidatedByBiometricEnrollment(true)` (Android)
so enrolling a new fingerprint/face invalidates the key and forces
re-enrolment — a new biometric must not inherit authority over an existing
signing key. This is the security boundary for ADR-0021 device binding.

**5. Degrade safely, never silently downgrade.** On a device without StrongBox,
fall back to TEE-backed Keystore (logged); on a device with no hardware keystore
at all, the app refuses to persist high-value secrets rather than writing them
softly. No code path may turn a hardware-storage failure into plaintext
persistence (the current iOS PIN fallback is the anti-pattern this kills).

## Alternatives considered

- **Keep the placeholder, fix it "before launch."** This is the status quo by
  omission — no contract, no gate. It is how the Android in-memory store would
  quietly survive into F1. Rejected: the dossier (ADR-0074) exists precisely to
  stop undecided gaps from shipping.
- **iOS-parity only (Keychain/Keystore, no StrongBox / no use-auth on SCA key).**
  Closes the Android plaintext hole but leaves the SCA signing key without
  hardware non-exportability or biometric invalidation — under-protects the one
  key that authorises money movement. Rejected as insufficient for ADR-0021.
- **A KMP third-party secure-storage library (e.g. multiplatform-settings +
  crypto).** Faster to wire, but abstracts away exactly the platform-specific
  controls (StrongBox attestation, `biometryCurrentSet`, use-auth) that are the
  point. Rejected: the `expect/actual` seam already gives native access; we
  should use it, not hide it.

## Consequences

**Positive**
- Real tokens and SCA keys are hardware-backed and biometric-invalidated before
  they ever exist; the Android plaintext-in-memory and iOS plaintext-PIN holes
  are closed by contract, not by hope.
- Gives ADR-0021 device binding a concrete, attestable key-storage substrate.
- The dossier's `decision missing` flag on credential storage flips to a linked,
  governed decision.

**Negative**
- Real Android Keystore + biometric-invalidation handling is fiddly (key
  invalidation exceptions, StrongBox availability variance) — a genuine F1→F2
  implementation cost.
- Removing the iOS `NSUserDefaults` PIN fallback means Keychain failures become
  visible errors that must be handled in the UX rather than swallowed.

**Neutral**
- Scope is the `SecureCredentialStore` and `ScaDeviceKey` actuals in `shared`;
  no API or backend change.
- The contract is testable: a CI/manual check can assert no token/PIN persists to
  `NSUserDefaults` / plain `SharedPreferences` and that SCA keys report
  hardware backing.

## Compliance impact

- PCI DSS: not applicable (no PAN/card data stored on device).
- DORA:    Art. 9 (protection & prevention) — credentials for a customer channel
           held in hardware-backed storage with defined invalidation.
- GDPR:    Art. 32 (security of processing) — authentication secrets encrypted at
           rest, this-device-only, not in world-readable stores.
- PSD2:    SCA (ADR-0021) — possession factor bound to a non-exportable,
           biometric-invalidated key in the secure element; dynamic-linking
           signing key never leaves hardware.
- CNB:     supports ICT security expectations for a customer-facing channel.

## References

- ADR-0021 — SCA decoupled device approval (the signing key this protects)
- ADR-0064 — Customer app: Kotlin Multiplatform (Secure Enclave/Keystore rationale)
- ADR-0066 — Passwordless customer authentication (tokens/keys this stores)
- ADR-0070 — In-app diagnostics (secrets excluded from any surface)
- ADR-0074 — Customer-app dossier (surfaced this gap as decision-missing)
- `shared/src/iosMain/.../SecureCredentialStore.ios.kt`,
  `shared/src/androidMain/.../SecureCredentialStore.android.kt`,
  `shared/src/androidMain/.../ScaDeviceKey.android.kt`
