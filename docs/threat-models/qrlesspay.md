<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — QRlessPay (BLE proximity SPAYD)

- **Date:** 2026-06-16 (completed 2026-07-30)
- **Status:** Complete as a design-level STRIDE/DFD (ADR-0030 D2). **Money-path** capability —
  rollout remains gated by the external reviews in §8 (they are process gates, not doc gaps).
- **ADR:** [ADR-0095](../adr/0095-qrlesspay-ble-proximity-spayd-payments.md) · **Spec:** [qrlesspay-v1](../specs/qrlesspay-v1.md)

## 1. Scope & purpose

Phone-to-phone transfer of a signed SPAYD payment descriptor over BLE, ending in
a payer-confirmed credit transfer on the existing rail. Scope = the over-the-air
exchange (advert + GATT) and the payer-side proposal up to SCA. Out of scope =
the downstream payment rail (covered by domestic-payment / instant threat models).

## 2. Data flow (DFD)

```
[Payee app] --BLE advert: firstName+sid+keyHash(+amt)--> (air) --> [Payer app: nearby list]
[Payer app] --GATT connect+read--> [Payee GATT server] --signed CBOR bundle(SPAYD,nonce,exp,pk,sig)--> [Payer app]
[Payer app] --verify(sig,binding,proximity)--> [Payer prefilled proposal] --VOP+SCA--> [Payer bank rail]
```

- **External entities:** payee device, payer device, payer's bank (VOP + rail), optional payee bank (attestation).
- **Trust boundaries:** the **air** (untrusted, observable, injectable); payer↔payer-bank (authenticated); device keystore.
- **Assets:** payee first name (low-sensitivity PII), IBAN (in the bundle, not on air), requested amount, session keypair, payer authorisation.

## 3. Trust assumptions

- The air is fully hostile: passively observable to ~10 m, actively injectable, relayable.
- Each app holds a per-session keypair in memory only; no shared secret between strangers.
- The payer's bank provides Verification of Payee (EU IPR, Oct 2025) and SCA.
- BLE advertising (payee role) may be unavailable on some Android hardware — fail closed (no advert) not open.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing (impersonate payee) | Attacker advertises same service UUID with own first name/IBAN | Payer always confirms name+masked IBAN; **VOP** name↔IBAN at payer bank; optional bank attestation (`att` JWS) |
| **S**poofing (swap GATT payload) | Nearby device answers the GATT read with a different bundle | advert `kh` = SHA-256(pk)[:2] binds advert to bundle; `sid` must match; `sig` over the bundle |
| **T**ampering (alter amount/IBAN in flight) | Modify CBOR between read and display | Ed25519 `sig` covers SPAYD+nonce+exp+sid+pk; any edit fails verify |
| **R**epudiation | Payer denies authorising | SCA evidence + audit on the payer-bank rail (existing) |
| **I**nfo disclosure | Passive sniff of the advert | Only first name + ephemeral id on air; IBAN only over payer-initiated GATT read; screen-open-only; rotating sid + privacy MAC |
| **I**nfo disclosure (tracking) | Correlate device across sessions | Per-screen sid + session key; platform resolvable-private-address MAC |
| **D**enial of service | Flood adverts / fake tiles | Payer-side rate limit + RSSI gate hides far/weak beacons; user picks one tile, connects to one |
| **E**levation / unauthorised payment | Move money without consent | No payment is initiated by the transfer; payer SCA is mandatory before any debit |
| **Relay / MITM** (distance fraud) | Relay advert+GATT from a remote victim | RSSI proximity gate (baseline); UWB / BT6 channel sounding (enhanced, distance-bounding); SAS numeric comparison for high value |
| **Replay** | Re-present a captured bundle later | `nonce` + `exp` ≤90 s + single-use `sid` |

## 5. Privacy (GDPR)

- Personal data on air: **first name only** + ephemeral id. Low-sensitivity (already on the payee's QR card), broadcast only on explicit screen-open (user-initiated), non-persistent, minimised (Art. 5(1)(c)). IBAN is never on air — only in the payer-initiated GATT read.
- Lawful basis: performance of the payment the user requested.
- **TODO (DPIA):** confirm first-name broadcast acceptability with DPO; consider an opt-out "show as initials only".

## 6. Residual risks & open items

- **Baseline identity authenticity is weak — be honest about it.** The per-session
  Ed25519 key proves *device/session continuity*, not *who owns the IBAN*: anyone
  can sign their own SPAYD with their own key. The two stronger identity controls
  are **both optional and frequently absent today**: bank attestation (needs both
  banks to support it) and **VOP**, which the EU mandates only for euro/SEPA —
  **CZ CZK domestic instant payments are not yet covered** (TODO/watch, spec §6.2).
  With neither, identity rests **entirely on the payer's §6 confirmation** — i.e.
  the same trust model as scanning a QR off a stranger's screen (see §7), which is
  phishing-grade. **Mitigation:** prefer attestation/VOP where available; where
  both are absent, treat the transfer as display-assist only and lean on proximity
  + explicit confirmation, exactly as a QR scan does.
- **First-name discovery is ambiguous and impersonable.** Two nearby "Jiří"s, or
  an attacker who advertises the victim's first name with *their own* IBAN (VOP
  passes — it is genuinely their account), can get the payer to pick the wrong
  tile. The name is **not** an authenticator. **Mitigation:** proximity gate
  (strongest signal / "touch phones") is the real binding, not the name; offer an
  optional short verification code shown on both screens before the proposal; show
  masked IBAN prominently on the confirm screen. **Open:** decide whether a
  verification code is default-on for payer→payee mismatch-prone contexts.
- RSSI alone does not stop a determined relay; **UWB/BT6 coverage is partial**
  (UWB only on iPhone 11+ and a minority of flagship Android — see spec §5) → it
  can never be assumed. High-value transfers SHOULD require SAS or fall back to
  QR. **Open:** define the value threshold.
- First-name broadcast is a deliberate privacy/UX trade-off → DPIA sign-off
  pending; offer "initials only" opt-out.
- Android peripheral-role hardware gaps → payee role degrades to QR; document per-device.
- **Open:** security review + second approval (ADR-0030) before any non-pilot rollout; key-handling review of the in-memory session keypair.

## 7. Comparison to optical QR scan (be honest)

QR Platba scanned with the camera is the incumbent. Both flows ultimately end the
same way — the payer reads a name + (masked) IBAN and confirms — so for the
**"did I pay the right person" risk they are roughly equivalent**, and both are
phishing-grade where no VOP/attestation backs them. The differences are per-axis,
and on one axis the BLE baseline is *worse*, which we must not hide.

| Axis | Optical QR scan | QRlessPay (BLE) baseline | Honest verdict |
|---|---|---|---|
| Identity (does payee own the IBAN?) | Unsigned; payer confirms name+IBAN | Signed payload, but key ≠ identity; payer confirms name+IBAN | **Tie** — both rest on confirmation (+ VOP/attestation when present) |
| **Physical targeting** (is this the person in front of me?) | **Strong** — you aim at a code you can *see*; hard to relay | **Weaker** — you tap a *name* in a list; can't see the radio, RSSI is spoofable | **QR wins** unless UWB/SAS/verification-code added |
| Payload tampering in transit | n/a (you photograph the final code) | Prevented (Ed25519 + advert↔bundle binding) | Tie / slight BLE edge |
| Classic attack | **Sticker-swap** (attacker pastes own QR over a legit one — real-world fraud) | **Tile impersonation** (attacker advertises same first name + own IBAN) | Analogous; both beaten only by attention + VOP |
| Privacy of payee account | IBAN+name printed/shown on the visible code to anyone | **Only first name on air**; IBAN never broadcast, only in payer-initiated read | **BLE wins** |
| UX | Aim camera, find code in frame | Tap a named tile, prefilled proposal | **BLE wins** (the point of the feature) |

**Conclusion.** QRlessPay's *innovation* is UX (no camera, pick-from-list,
prefilled) and a privacy gain (no IBAN on air). On raw security it is **not
automatically safer than a QR scan** — its weak axis is *physical targeting*
(a name-list + spoofable RSSI is a looser "this is the person in front of me"
binding than aiming a camera at a visible code). To be *clearly safer* than QR it
must add the optional layers — bank attestation, VOP (when it exists for CZ),
strong proximity (UWB) or a verification code — none of which can be assumed
today. The baseline should therefore be positioned as **"as safe as a QR scan,
more private, nicer to use"**, not as "more secure", until those layers land.

## 8. Rollout gates (what must be true before code ships)

The app-side implementation state (2026-07): protocol core, controller and the
nearby-tiles UI exist; the **Android GATT receive/write paths are deliberate
stubs** (`ProximityBeacon.android.kt` — `startListeningForSpayd` TODO, `pushSpayd`
returns false). Those stubs must NOT be filled until all of the following hold:

1. **Independent cryptographic review** of the Ed25519 bundle format, the
   advert↔bundle `kh` binding, and the in-memory session keypair handling (§6).
2. **Protocol fuzzing** of the CBOR bundle decoder (malformed length prefixes,
   truncation, oversize SPAYD, replayed/expired bundles, invalid `kh`/`sid`).
3. **ADR-0030 security review + second approval** for this money-path capability.
4. **DPIA sign-off** on the first-name broadcast (§5), incl. the "initials only"
   opt-out decision.
5. Decisions on the two open parameters: the **SAS/verification-code default**
   and the **high-value threshold** that forces SAS/QR fallback (§6).
6. Per-device **peripheral-role compatibility** documented; payee role degrades
   to QR where GATT server is unreliable (§6).

Until 1-4 are done, QRlessPay stays **dormant** in the app (BLE permission is
optional, the feature flag is off, and the only live surfaces are the read-only
nearby-tiles list and the QR fallback).
