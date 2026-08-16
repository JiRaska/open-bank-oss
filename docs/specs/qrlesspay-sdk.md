# QRlessPay SDK — open-source reference SDK proposal

Status: **Implemented** — this document is the architecture; the code is at
[github.com/JiRaska/qrlesspay-sdk](https://github.com/JiRaska/qrlesspay-sdk) (Apache-2.0, `v0.1.0`).
Protocol: [qrlesspay-v1](qrlesspay-v1.md) · Decision: [ADR-0095](../adr/0095-qrlesspay-ble-proximity-spayd-payments.md)

> **What shipped against this document, and what did not.** Native Swift and Kotlin cores that agree
> byte for byte, BLE transports on both platforms, a React Native binding, UWB and SAS, an example
> iOS app, and the conformance suite this document argues for — 3 positive vectors, 20 negative
> cases, 19 UWB cases, run by both implementations, with CI regenerating and diffing them. Flutter
> is not started. Nothing is published to a package registry, and nothing has run on real hardware.
>
> The suite justified itself before it was finished: building the second implementation found that
> the reference CBOR encoding did not match §3 of the spec — text keys, integer arrays, 326 B
> against 197 B — so anything written from the spec could not read it. A round-trip test could not
> have found it, which is the argument of §3 of this document restated as a fact.

QRlessPay is an open, bank-agnostic BLE profile. For it to become a standard
(ČBA/EPC ambition, ADR-0095), other banks must be able to adopt it in weeks, not
quarters. This document proposes a **reference SDK, published under Apache-2.0 in
its own public repository**, that any bank can drop into its existing mobile app.

## 1. Goals & non-goals

**Goals**

- A bank can adopt QRlessPay **in the language its app is already written in** —
  native Swift, native Kotlin, React Native, Flutter or Kotlin Multiplatform —
  without taking on a foreign runtime to do it.
- The wire profile's security properties (beacon codec, CBOR bundle, Ed25519,
  verification order, proximity gate, single use) are guaranteed by a shared
  **conformance suite** that every implementation must pass, rather than by
  every adopter linking the same binary.
- Payer and payee roles, iOS + Android, with the same feature-negotiation and
  graceful-downgrade behaviour (UWB → RSSI, SAS optional) on both.
- UI-less core: banks keep their own design system, confirmation screens and SCA.
  The SDK never renders a payable screen and never initiates a payment.
- Conformance testability: golden vectors + a test harness, so "QRlessPay-v1
  conformant" (spec §9) is checkable, not aspirational.

**The governing invariant — QRlessPay is an extension of SPAYD, and SPAYD has no backend.**
A SPAYD string is a payment descriptor that any bank's app parses from the bytes
alone; there is no registry, no directory, no trust anchor to consult. QRlessPay
carries that same descriptor over BLE instead of a QR image, and adds only things
that keep the property: a signature, a session binding, an expiry, a proximity
gate. The test any future addition has to pass is therefore **"can the payer
decide this from the bytes in hand and its own device state?"** — if it needs a
lookup, it is no longer a SPAYD extension, and the lookup is what makes two
banks' apps stop interoperating off the profile alone. The SDK must not ship an
API that invites one. Normative statement: wire spec §11.

**Non-goals**

- No backend components. Settlement stays on each bank's IBAN/instant rail.
- No trust anchor, registry, participant list or key-directory lookup, in any
  binding — see the invariant above.
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

## 3. Architecture — a family of native SDKs, one conformance suite

The obvious design is one Kotlin Multiplatform core with thin bindings on top, so
the security-critical logic exists exactly once. That is the right shape for a
*product*, and the wrong one for a *standard*.

A bank with a pure-Swift app will not add a Kotlin runtime and an XCFramework to
its binary to accept payments, and one that has standardised on React Native does
not want a KMP toolchain in its build. A profile whose only real implementation
is KMP is a profile with one implementation — which is the thing ADR-0095's
standardisation ambition is trying not to be. And the moment a second bank
implements from the spec (which the spec explicitly invites: §9 defines
conformance for anyone), the "single core" guarantee is already gone; the only
question is whether the second implementation was checked against anything.

So the shared artifact is **the conformance suite, not the code**:

```
┌──────────────────────────────────────────────────────────────────┐
│  CONFORMANCE SUITE  (the normative artifact — language-neutral)  │
│  golden vectors as JSON: adverts, bundles, signatures, SAS       │
│  derivations, and a large NEGATIVE corpus · cross-implementation │
│  interop matrix · two-device lab script                          │
└──────────────────────────────────────────────────────────────────┘
        ▲              ▲              ▲              ▲
┌───────┴──────┐┌──────┴───────┐┌─────┴────────┐┌────┴────────────┐
│  Swift SDK   ││ Kotlin/       ││  KMP SDK     ││  React Native / │
│  (SPM)       ││ Android (AAR) ││ (AAR + XCF)  ││  Flutter        │
│  CoreBluetooth││ android.blue-││ for KMP apps ││  bind the two   │
│  NearbyInter. ││ tooth, core- ││ e.g. this    ││  NATIVE SDKs,   │
│  swift-crypto ││ uwb, Tink    ││ platform's   ││  not the KMP one│
└──────────────┘└──────────────┘└──────────────┘└─────────────────┘
```

- **Swift and Kotlin SDKs are first-class implementations, not wrappers.** Each
  is idiomatic for its platform, uses its platform's own crypto (swift-crypto /
  Tink or the platform providers) and ships no foreign runtime.
- **The KMP SDK is a peer, not the substrate.** It is the natural choice for a
  KMP app — this platform's own client is one — and it is where the existing,
  production-shaped implementation in `openbank-app` gets extracted to. It is
  simply not privileged over the others.
- **React Native and Flutter bind the native SDKs**, not the KMP one. Both
  ecosystems already ship native modules, so this is the shortest path and it
  avoids stacking two runtimes to reach one radio.

### What this costs, and what pays for it

Four implementations means four chances to get the verification order wrong, and
four crypto reviews instead of one. That cost is real and it is the reason the
conformance suite is listed as a goal above rather than as tooling:

- The vectors are **executable in every language** and weighted towards the
  **negative** cases — a wrong `kh` binding, a bad signature, an expired or
  replayed bundle, a truncated CBOR, an oversize SPAYD. Implementations do not
  drift on the happy path; they drift on which failures they notice.
- The **interop matrix** runs every implementation against every other as payer
  and payee, so "it works with itself" cannot be mistaken for conformance.
- Each implementation carries its own review, and §7's gates apply per
  implementation rather than once for the family.

### Per-target packaging

| Target | Artifact | Implementation | BLE/UWB access |
|---|---|---|---|
| iOS (Swift) | SPM package | native Swift | CoreBluetooth, NearbyInteraction |
| Android (Kotlin/Java) | Maven Central AAR | native Kotlin | android.bluetooth, androidx.core.uwb |
| Kotlin Multiplatform | AAR + XCFramework | shared Kotlin | via expect/actual to both of the above APIs |
| React Native | npm package (TS types) | binds the Swift + Kotlin SDKs | via native module |
| Flutter | pub.dev plugin (Dart API) | binds the Swift + Kotlin SDKs | via platform channels |
| Web | **not supported** — documented, not shimmed | — | payee role impossible (no Web Bluetooth peripheral API); payer's GATT is Chrome-only and cannot meet the proximity gate |

Priority order for building them, on adoption reach rather than effort: **Swift
and Kotlin first** (they are what the RN and Flutter bindings stand on, so
nothing else can ship before them), then **React Native**, then **KMP**
(extraction, and this platform's own client already has the code), then
**Flutter**.

## 4. Public API sketch — semantics, not signatures

Written in Kotlin below because it has to be written in something. Each SDK is
idiomatic for its own platform (Swift uses `async`/`AsyncSequence`, TypeScript a
promise plus an event emitter), and what is normative is the **semantics** — the
states, the outcome type, the ordering — not these names.

```kotlin
// Payee — "Receive nearby" screen scope
val session = QrlessPayee.start(
    spayd = SpaydRequest(iban, amount, currency, name, message),
    config = PayeeConfig(),
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

Contract points every implementation must enforce by construction — these are the
ones a conformance vector cannot check, so they are review items:

- `Verified.proposal` carries the **signed** SPAYD values only — advert
  `name`/`amt` hints are unreachable from the proposal type (spec §2 warning).
- There is no "skip proximity" or "skip verification" knob. Test builds use an
  injected fake transport, not relaxed checks.
- Single-use tracking is a **required argument**, not an optional one. This
  platform's client shipped the check as "the caller's responsibility" and no
  caller ever took it; an API that lets an adopter omit it will be omitted.
- The SDK ends at the proposal. Confirmation UI, VOP lookup and SCA are the
  bank's, keeping the mandatory §6 gate outside SDK code.
- Typed `Rejected.reason` mirrors the spec failure taxonomy so telemetry is
  comparable across banks.
- The payer-side duplicate and same-name warnings are **surfaced, not decided**:
  the SDK reports "this device opened an identical proposal N seconds ago" and
  "these tiles share a display name", and the bank's UI chooses the wording. A
  refused or repeated tap must never be silent.

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

This is the load-bearing section, not an appendix: with several independent
implementations it is the only thing that makes them one profile rather than
several dialects.

- **Golden vectors** as language-neutral JSON in the repo: beacon payloads, CBOR
  bundles (valid + a malformed corpus: truncation, oversize SPAYD, bad `kh`,
  expired, replayed), Ed25519 test keys, SAS derivation vectors. Every
  implementation runs the same file, and the negative cases outnumber the
  positive ones on purpose — implementations agree on the happy path and diverge
  on which failures they notice.
- **Cross-implementation interop matrix**: every implementation against every
  other, in both roles. Self-interop proves nothing; a shared misreading of the
  spec is invisible until a second implementation disagrees.
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

**Per implementation, not once for the family.** The crypto review and the
fuzzing are properties of code, so a second implementation does not inherit the
first one's review — each reaches `1.0.0` on its own evidence, and the version
numbers are therefore independent. Passing the conformance suite is necessary
and not sufficient: it establishes that an implementation agrees with the
others, not that its key handling is sound.

## 8. Open questions

1. Repo host & org: under the OpenBank GitHub org, or a neutral foundation-style
   org to ease adoption by competitor banks?
2. Maven/npm/pub/SPM publishing identities & signing keys — who holds them
   (release engineering decision, not in this doc).
3. A WASM build for a future web *payer* experiment — deferred until Web
   Bluetooth reality changes.
4. Whether the optional UI kit (nearby-tiles list + SAS comparison sheet) ships
   as a second artifact in v1.x.
5. One repository holding every implementation, or one per language? A monorepo
   makes the conformance suite trivially shared and every change visible across
   implementations; separate repos make each one's release cadence and review
   independent, which matters more once implementations reach `1.0.0` on
   different dates.
6. Whether a bank's own from-scratch implementation can claim conformance by
   publishing suite output, or whether that needs a verification step someone
   operates — the difference between an open profile and a certified one, and
   the point at which the ČBA/EPC ambition needs an actual body behind it.
