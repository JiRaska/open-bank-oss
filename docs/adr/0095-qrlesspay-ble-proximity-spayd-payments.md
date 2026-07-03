# QRlessPay — BLE proximity SPAYD payments

Date: 2026-06-16
Status: Accepted
Delivery-Status: Partial
Author(s): Jiří Raška
Delivery-Repos: openbank-app

**Delivery note (2026-07-02, ADR-0147):** the client-side BLE
peripheral/central implementation and the QR SPAYD flow it extends both
ship in `openbank-app`, not in this monorepo — confirmed directly, not
inferred. This repo holds the wire-format spec and threat model only. A
review scoped to this monorepo alone will otherwise (and did, once)
misread this ADR as unimplemented.

## Context

The Czech QR-payment standard (QR Platba / SPAYD) works, but it forces a
camera scan: the payer points a phone at the payee's screen or invoice. For a
two-people-standing-next-to-each-other transfer that is clumsy — one person
holds up a code, the other hunts for it in the frame.

We already ship a Bluetooth-LE "nearby pay" in the customer app (referenced in
code as *ADR-0087*, which was **never actually written** — this ADR formalises
and supersedes that informal work). Today it advertises an **opaque session
token**; the payer resolves the token against the OpenBank customer edge to get
the payee's name + amount. That is a **closed loop**: it only works
OpenBank↔OpenBank, requires the backend online, and the discovery list shows
nothing until a round-trip succeeds.

We want a transfer that is:

- **Phone-to-phone and cross-bank** — payee in bank A (iOS), payer in bank B
  (Android), no shared backend required.
- **Readable on discovery** — the payer sees a human label (the payee's *first
  name*) in a "nearby" list, not an opaque blob.
- **Direct** — selecting a payee transfers the full SPAYD descriptor and opens a
  **pre-filled payment proposal**; the payer only confirms.
- **Secure** — authentic, anti-spoofing, anti-relay, anti-replay, privacy-minimal,
  and no money moves without explicit payer confirmation / SCA.
- **Standardisable** — a bank-agnostic, backend-optional open profile we can take
  to ČBA / EPC, with this app as the reference implementation.

A BLE advertisement carries only ~27 usable bytes, so a full SPAYD string
(IBAN + name + amount + variable symbol) does not fit in the advert. The design
must therefore split discovery from payload transfer.

## Decision

We will define **QRlessPay**, an open BLE proximity profile that transfers a
**signed SPAYD descriptor** directly between two phones in two phases, and we
will publish it as an open standard. The reference implementation ships in
`openbank-app` (Apache-2.0).

**Two-phase protocol** (full wire format in
[`docs/specs/qrlesspay-v1.md`](../specs/qrlesspay-v1.md)):

1. **Discovery** — the payee connectionlessly *advertises* under the QRlessPay
   service UUID a tiny beacon: `version/flags · first name (≤12 B, ASCII-folded)
   · 4-byte ephemeral session-id · optional 3-byte amount`. The beacon also
   carries a short prefix of the payee's per-session public-key hash. This is
   what the payer sees as a readable "nearby" tile. Advertising runs **only while
   the request screen is open**, with a rotating session-id and the platform's
   privacy MAC.
2. **Transfer** — on selection, the payer opens a short **GATT** connection and
   reads a characteristic that returns a **signed bundle** (CBOR): the SPAYD
   string, a nonce, a short expiry, the session-id, and an **Ed25519 signature**
   over them. The advert↔GATT key-hash binding proves the bundle came from the
   same device that advertised.

**Security is defence-in-depth** (full analysis in
[`docs/threat-models/qrlesspay.md`](../threat-models/qrlesspay.md)):

- *Integrity & session binding*: Ed25519 signature + advert↔GATT key-hash binding.
- *Replay*: nonce + expiry (≤90 s) + single-use session-id.
- *Relay (anti-MITM proximity)*: RSSI threshold gate; **UWB secure ranging**
  (Apple Nearby Interaction / Android UWB) or BT 6.0 channel sounding as
  progressive enhancement where the hardware supports it.
- *IBAN substitution / identity*: the payer **always** confirms a screen showing
  recipient name + masked IBAN (the only always-present control), optionally
  strengthened by **Verification of Payee** *where available* — the EU mandates
  VOP only for euro/SEPA; **CZ CZK domestic is not yet covered**, so VOP is a
  future/optional layer (TODO/watch), not an assumption.
- *High value / MITM*: optional Short Authentication String (4-digit numeric
  comparison from a DH over the GATT link).

**Backend is optional.** The baseline is fully offline and bank-agnostic;
settlement rides the existing IBAN / instant-payment rail, which is already
interbank. An **optional upgrade** has the payee's bank counter-sign the bundle
(JWS) or resolve the session-id via a `.well-known` endpoint, giving
bank-attested identity. This is negotiated by a flag in the advert.

This is a **money-path** capability (per ADR-0030 it requires two approvals and
the threat model above).

Implementation milestone: BLE peripheral/central roles will be added to openbank-app after the QR SPAYD flow (ADR-0076) reaches production. The server-side settlement path is shared with QR SPAYD — no backend changes needed.

## Alternatives considered

- **Token + federated bank resolver only** (extend ADR-0087). Strong identity
  (bank attests), but requires every participant's backend online and a
  federation agreement — high adoption barrier, not offline-capable. *Rejected as
  the baseline; kept as the optional attestation upgrade.*
- **Raw SPAYD in the BLE advertisement.** Simplest, but a full SPAYD does not fit
  the ~27-byte advert, and an unauthenticated broadcast invites IBAN-substitution
  spoofing. *Rejected — replaced by the signed two-phase transfer.*
- **NFC tap (P2P).** Great proximity proof, but iOS killed peer-to-peer NFC; not
  cross-platform. *Rejected (may revisit as an optional pairing accelerator).*
- **Cloud relay / phone-number scheme** (Bizum/Swish style). Not proximity-based,
  nationally siloed, backend-bound. *Rejected — doesn't meet the phone-to-phone,
  cross-bank, offline goals.*

## Consequences

**Positive**
- True phone-to-phone, cross-bank, cross-platform transfer with no camera and no
  shared backend; the payer just confirms a pre-filled proposal.
- A publishable open standard with a working reference implementation — a
  credible pitch to ČBA / EPC, aligned with EU Instant Payments + VOP.
- Reuses existing rails: SPAYD payload, the app's scan→prefill path, and
  ADR-0087's session/status plumbing.

**Negative**
- New platform plumbing: a GATT server/client (iOS `CBPeripheralManager`,
  Android `BluetoothGattServer`) — more than today's advertise/scan-only beacon.
- BLE peripheral/advertising support is hardware-dependent on some Android
  devices (payee role may be unavailable there; payer role works broadly).
- Security correctness is load-bearing; the threat model and a security review
  gate the rollout.
- **Baseline identity authenticity is intentionally modest.** The per-session key
  proves only device/session continuity — not IBAN ownership. Both stronger
  controls (bank attestation; VOP) are optional and often absent — VOP is euro/SEPA
  only today, **not CZ CZK domestic** — so with neither, identity rests purely on
  the payer's confirmation, the **same trust model as scanning a QR off a screen**
  (threat model §7), which is phishing-grade. The broadcast first name is a
  discovery label, not an authenticator (two nearby "Jiří"s, or an attacker
  advertising a victim's name with their own IBAN, are possible). Proximity +
  confirmation, and bank attestation where available, are what bind the payer to
  the right payee. See the threat model.
- Strong relay resistance (UWB / BT6 Channel Sounding) is hardware-limited (UWB
  only on iPhone 11+ and a minority of Android), so it is an optional enhancement,
  never assumed; the portable baseline is RSSI + confirmation.

**Neutral**
- Offline baseline vs bank-attested upgrade is a negotiated capability, not a
  hard fork — both speak the same wire format.
- First name is broadcast in clear; it is low-sensitivity (already shown on the
  payee's QR card) but is a deliberate privacy trade-off documented in the threat
  model.

## Compliance impact

- PCI DSS: not applicable (no card data; bank-account credit transfer).
- DORA: positive — offline-capable degraded mode; no new critical third party.
- GDPR: first name + ephemeral id are the only personal data on air, broadcast
  only with the screen open (user-initiated), minimised and non-persistent
  (Art. 5(1)(c) data minimisation; lawful basis = performance of the payment the
  user requested). DPIA tracked in the threat model.
- PSD2: payer-side SCA on the resulting payment (ties to ADR-0021); the transfer
  itself initiates no payment without explicit confirmation. VOP applies only
  where the rail offers it (euro/SEPA today; CZ CZK domestic = future/optional,
  TODO/watch) — it is not relied upon as a baseline control.
- CNB: domestic credit transfer over the existing rail; no new licence surface.

## References

- [`docs/specs/qrlesspay-v1.md`](../specs/qrlesspay-v1.md) — wire-format spec.
- [`docs/threat-models/qrlesspay.md`](../threat-models/qrlesspay.md) — threat model.
- SPAYD / QR Platba (ČBA Short Payment Descriptor).
- EU Instant Payments Regulation (Reg. (EU) 2024/886) — Verification of Payee.
- ADR-0021 (SCA), ADR-0030 (money-path governance), ADR-0064 (KMP customer app),
  ADR-0066 (app security), and the informal nearby-pay work referenced as
  "ADR-0087" in `openbank-app`.
