# QRlessPay SDK — open-source reference SDK proposal

Status: Proposal (v0) · Protocol: [qrlesspay-v1](qrlesspay-v1.md) · Decision: [ADR-0095](../adr/0095-qrlesspay-ble-proximity-spayd-payments.md)

QRlessPay is an open, bank-agnostic BLE profile. For it to become a standard
(ČBA/EPC ambition, ADR-0095), other banks must be able to adopt it in weeks, not
quarters. This document proposes a **reference SDK, published under Apache-2.0 in
its own public repository**, that any bank can drop into its existing mobile app.

## 1. Goals & non-goals

**Goals**

- One audited implementation of the wire profile (beacon codec, CBOR bundle,
  Ed25519, verification order, proximity gate) shared by every adopter — the
  protocol's security properties live in reviewed code, not in each bank's
  re-implementation.
- Payer and payee roles, iOS + Android, with the same feature-negotiation and
  graceful-downgrade behaviour (UWB → RSSI, SAS optional) on both.
- UI-less core: banks keep their own design system, confirmation screens and SCA.
  The SDK never renders a payable screen and never initiates a payment.
- Conformance testability: golden vectors + a test harness, so "QRlessPay-v1
  conformant" (spec §9) is checkable, not aspirational.

**Non-goals**

- No backend components. Settlement stays on each bank's IBAN/instant rail.
- No UI kit in v0 (optional add-on later; it must stay separable).
- No custody of long-term keys — session keys are in-memory only, per spec §4.
- Web/PWA payee role — Web Bluetooth has no peripheral/advertising API. Out of
  scope (see §5).

## 2. Repository & licensing

- **Separate public repo** (working name `qrlesspay-sdk`), Apache-2.0, DCO.
  The wire spec stays authoritative in `open-bank-oss/docs/specs/qrlesspay-v1.md`;
  the SDK repo vendors a **tagged snapshot** of the spec + threat model and CI
  fails if the vendored copy drifts from the referenced tag.
- Governance mirrors the platform: conventional commits, release-please,
  SECURITY.md with private disclosure (the protocol is money-path; spec/threat
  model change control stays in open-bank-oss with ADR-0030 review).
- Trademark note: "QRlessPay" name usage policy documented so forks can claim
  conformance without implying endorsement.

## 3. Architecture — one core, thin bindings

Layered so the security-critical logic exists **once**:

```
┌─────────────────────────────────────────────────────────┐
│  Bindings: Swift API (SPM) · Kotlin/Android (Maven) ·   │
│  React Native (TS, autolinked module) · Flutter (Dart)  │
├─────────────────────────────────────────────────────────┤
│  Transport adapters (per platform):                     │
│  BLE advertise/scan · GATT server/client · UWB ranging  │
├─────────────────────────────────────────────────────────┤
│  PROTOCOL CORE (Kotlin Multiplatform, no platform deps) │
│  beacon codec · CBOR bundle codec · Ed25519 sign/verify │
│  SPAYD parse/build · verification state machine ·       │
│  SAS derivation (X25519+HKDF) · proximity policy        │
└─────────────────────────────────────────────────────────┘
```

- **Protocol core = Kotlin Multiplatform.** It already exists, proven in
  production shape, inside the `openbank-app` KMP client (ADR-0095 client
  implementation) — the SDK is an **extraction**, not a rewrite. Pure-Kotlin
  crypto deps (kotlincrypto Ed25519/SHA-256) keep the core free of JNI/native
  surprises. Compiled out as: Android AAR + iOS XCFramework.
- **Bindings stay thin.** A binding may only: expose idiomatic types, forward
  events, host the platform BLE/UWB adapter. It must NOT re-implement any
  verification step — the state machine in the core is the single place the spec
  §3 verification order (sid/kh binding → signature → exp/nonce → SPAYD parse →
  proximity → confirmation hand-off) is encoded, so a binding cannot skip a step
  or reorder it.

### Per-target packaging

| Target | Artifact | BLE/UWB access |
|---|---|---|
| Android (Kotlin/Java) | Maven Central AAR | native (android.bluetooth, androidx.core.uwb) |
| iOS (Swift) | SPM package wrapping the XCFramework | native (CoreBluetooth, NearbyInteraction) |
| React Native | npm package (TS types), autolinks the two native artifacts | via native module |
| Flutter | pub.dev plugin (Dart API), platform channels to the same artifacts | via plugin |
| Web | **not supported** (payee impossible; payer's Web Bluetooth GATT is Chrome-only and cannot meet the proximity gate) — documented, not shimmed | — |

React Native and Flutter get first-class bindings because that is what most CZ/EU
bank apps are actually written in; both delegate to the identical native cores,
so interop and security review cover them for free.

## 4. Public API sketch (core semantics, per-language idioms)

```kotlin
// Payee — "Receive nearby" screen scope
val session = QrlessPayee.start(
    spayd = SpaydRequest(iban, amount, currency, name, message),
    config = PayeeConfig(bankAttestation = null /* optional JWS */),
)   // advertises + serves GATT; auto-stops on close()
session.events  // Flow<PayeeEvent>: Advertising, BundleServed, SasRequested(code), Error

// Payer — "Pay nearby" screen scope
val scanner = QrlessPayer.scan(PayerConfig(
    proximity = ProximityPolicy.Default,   // RSSI baseline; UWB auto-negotiated
    sasPolicy = SasPolicy.AboveAmount(CZK, 10_000_00),
))
scanner.tiles   // Flow<List<NearbyTile>>: name, amountHint, capable flags
val outcome = scanner.select(tile)         // connect + read + FULL §3 verification
when (outcome) {
    is Verified -> showConfirmation(outcome.proposal)  // bank's own UI + SCA
    is SasRequired -> showSasComparison(outcome.code)  // then outcome.confirm()
    is Rejected -> explain(outcome.reason)             // typed reason enum
}
```

Contract points the API enforces by construction:

- `Verified.proposal` carries the **signed** SPAYD values only — advert
  `name`/`amt` hints are unreachable from the proposal type (spec §2 warning).
- There is no "skip proximity" or "skip verification" knob. Test builds use an
  injected fake transport, not relaxed checks.
- The SDK ends at the proposal. Confirmation UI, VOP lookup and SCA are the
  bank's, keeping the mandatory §6 gate outside SDK code.
- Typed `Rejected.reason` mirrors the spec failure taxonomy so telemetry is
  comparable across banks.

## 5. Platform truth the SDK must encode (from the shipped client)

Lessons already learned in the `openbank-app` implementation, baked in as
behaviour + docs so every adopter doesn't rediscover them:

- **Advert carriage differs**: service-data (Android) vs local-name (iOS) — both
  emitted, both parsed (spec §2).
- **Android peripheral role is hardware-dependent** — feature-detect; payee role
  degrades to QR display, never fails open.
- **UWB is asymmetric on Android** (controller/controlee roles) and symmetric on
  iOS; **Apple NI ↔ Android FiRa do not interop** — cross-OS pairs silently fall
  back to the RSSI baseline + tap list. The SDK's negotiation encodes this
  matrix; a UWB token codec version/magic byte rejects a foreign token instead of
  misparsing it.
- **UWB hardware coverage is a minority** (iPhone 11+, flagship Androids) — UWB
  is a plugin module, never a dependency of the core artifact, so the base SDK
  adds no UWB binary weight for banks that skip it.

## 6. Conformance & test strategy

- **Golden vectors** in the repo: beacon payloads, CBOR bundles (valid + a
  malformed corpus: truncation, oversize SPAYD, bad `kh`, expired, replayed),
  Ed25519 test keys, SAS derivation vectors. Every binding runs the same vectors.
- **Protocol fuzzing** of the CBOR decoder in CI (threat model §8 gate 2 asks for
  this anyway — the SDK repo is where it runs continuously).
- **Loopback interop harness**: in-process payee↔payer over a fake transport for
  CI; a two-device lab script for physical BLE/UWB runs (UWB is untestable in
  emulators).
- **Conformance checklist** generated from spec §9 — a bank can self-certify
  with the harness output attached.

## 7. Rollout gates (inherited — not new)

Publishing the SDK is publishing the money-path protocol implementation, so the
existing threat-model gates apply **before the first tagged release**:
independent crypto review, CBOR fuzzing, ADR-0030 second approval, DPIA
(first-name broadcast), SAS-default + high-value-threshold decisions (threat
model §8, items 1–5). The repo can go public earlier as clearly-labelled
pre-release; `1.0.0` waits for the gates.

## 8. Open questions

1. Repo host & org: under the OpenBank GitHub org, or a neutral foundation-style
   org to ease adoption by competitor banks?
2. Maven/npm/pub/SPM publishing identities & signing keys — who holds them
   (release engineering decision, not in this doc).
3. Kotlin/JS or WASM build of the core for a future web *payer* experiment —
   deferred until Web Bluetooth reality changes.
4. Whether the optional UI kit (nearby-tiles list + SAS comparison sheet) ships
   as a second artifact in v1.x.
