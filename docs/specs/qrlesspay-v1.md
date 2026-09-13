# QRlessPay v1 — wire specification

Status: Draft (v1) · Decision: [ADR-0095](../adr/0095-qrlesspay-ble-proximity-spayd-payments.md)

QRlessPay transfers a **signed SPAYD payment descriptor** between two phones over
Bluetooth Low Energy, with no camera and no required backend. It is an open,
bank-agnostic profile: any wallet implementing this profile interoperates, and
settlement uses the existing IBAN / instant-payment rail.

Roles: **Payee** (requests money; advertises + runs a GATT server) and **Payer**
(pays; scans + acts as GATT client). Either side may be any bank's app.

## 1. Identifiers

| Item | Value |
|------|-------|
| Service UUID (advert filter + GATT service) | `4272AAD2-26D1-41F7-96DE-69F41CD4E85D` — 128-bit, randomly generated (UUIDv4) and frozen; no registry applies to the 128-bit space |
| GATT characteristic — `bundle` (read) | `29A03ABE-E002-4B66-A4D5-D8B07F5C195B`, read-only, returns the signed bundle |
| GATT characteristic — `sas` (read/write, optional) | `28299596-338B-4BDA-8F5C-A74C471DCA6E`, ephemeral-DH for the Short Authentication String |
| Advert service-data company/16-bit alias | `0xF0B2` (Android service-data budget) — Bluetooth SIG-administered space, **not yet assigned to us**; see [readiness §6](../compliance/qrlesspay-readiness.md#6-legal-ip-and-standardisation--the-least-developed-area) |

The three 128-bit UUIDs above are frozen normative values for this profile: any
implementation (including the external `qrlesspay-sdk` reference implementation)
MUST use these exact values, not regenerate its own. The 16-bit alias is a
Bluetooth SIG-administered space and has not been assigned to us — using it today
is effectively squatting. Per [readiness §6](../compliance/qrlesspay-readiness.md#6-legal-ip-and-standardisation--the-least-developed-area),
the decision taken here is to defer requesting a real assignment until a pilot
with a second bank is real, since a collision cannot occur while QRlessPay runs
only between our own apps.

## 2. Phase 1 — discovery advert

Connectionless BLE advertisement. Service UUID = scan filter. The beacon payload
rides in service data (Android) or local name (iOS) — both forms MUST be
accepted by scanners. Layout (≤24 bytes):

| Field | Size | Notes |
|-------|------|-------|
| `ver` | 1 B | high nibble = protocol version (`1`); low nibble = flags: `0x1` amount-present, `0x2` **reserved** (see §11), `0x4` SAS-capable |
| `name` | ≤12 B | payee **first name**, ASCII-folded (Czech diacritics → ASCII), UTF-8, truncated on a code-point boundary |
| `sid` | 4 B | ephemeral session-id, CSPRNG, single-use per request screen |
| `kh` | 2 B | first 2 bytes of `SHA-256(payee session public key)` — binds advert to the GATT bundle |
| `amt` | 3 B | optional (flag `0x1`): requested amount in minor units, big-endian, capped at 16 777 215 |

The advert MUST run only while the request screen is open, MUST use the platform
private/rotating MAC, and MUST stop on screen dispose. `sid` and the session
keypair regenerate per screen open.

> **`name` and `amt` are untrusted display hints, not authority.** The advert is
> unauthenticated, so a scanner MUST treat the broadcast first name and amount as
> a discovery label only. The authoritative recipient, account and amount are the
> signed values in the §3 bundle, confirmed by the payer in §6. First names are
> not unique — see the threat model on discovery ambiguity/impersonation; the
> proximity gate (§5) and the §6 confirmation, not the name, are what bind the
> payer to the right payee.

## 3. Phase 2 — signed bundle (GATT read of `bundle`)

The payer connects and reads `bundle`. Payload is **CBOR** (compact, ~140–180 B):

```
{
  1: ver        (uint)            # = 1
  2: sid        (bstr, 4)         # MUST equal the advert sid
  3: spayd      (tstr)            # full SPAYD, e.g. "SPD*1.0*ACC:CZ65…*AM:250.00*CC:CZK*RN:Jiri"
  4: nonce      (bstr, 16)        # CSPRNG, single-use
  5: exp        (uint)            # unix seconds, ≤ now+90s
  6: pk         (bstr, 32)        # payee session Ed25519 public key; SHA-256(pk)[:2] MUST equal advert kh
  7: sig        (bstr, 64)        # Ed25519 over the deterministic CBOR of {1,2,3,4,5,6}
  8: —                            # RESERVED, never emitted; see §11
}
```

Verification by the payer, in order — any failure aborts:

1. `ver == 1`; `sid == advert.sid`; `SHA-256(pk)[:2] == advert.kh`.
2. `sig` verifies against `pk`.
3. `exp` is in the future and within 90 s; `nonce`/`sid` not seen before.
4. `spayd` parses to a valid SPAYD (valid IBAN in `ACC`, no spaces, amount ≤ 2 dp).
5. **Proximity** gate passes (§5).

Every step is decidable from the bytes in hand plus the payer's own clock, radio
and history — see the §11 invariant. A v1 payer performs **no network lookup** to
verify a bundle, and MUST ignore CBOR key 8 and advert flag `0x2` rather than
treating either as a signal.

## 4. Cryptography

- Signature: **Ed25519**. Keys are **per-session** (regenerated each request
  screen), held only in memory — the signature proves "same device, this
  session", not long-term identity. Long-term identity comes from the IBAN +
  Verification of Payee (§6) where a scheme exists; there is no in-protocol
  identity assertion (§11).
- Hashes: SHA-256.
- Optional SAS (high value): X25519 ephemeral DH over `sas`; both sides derive a
  4-digit code = `HKDF(shared, "QP-SAS")[:digits]`; users compare out-of-band.
  Defeats an active MITM on the GATT link without any PKI.

## 5. Proximity binding (anti-relay)

The **baseline is RSSI only**; everything stronger is optional enhancement. A
conformant implementation MUST work end-to-end with RSSI alone, and MUST NOT make
any flow depend on UWB or Channel Sounding being present.

- **Baseline (required, RSSI)**: signal above a threshold (≈ within 1–2 m) for ≥ N
  samples; the UI nudges "hold the phones close". RSSI is spoofable (an attacker
  can raise TX power), so it is necessary-not-sufficient and is **always** paired
  with the §6 confirmation. This is the only proximity mechanism we can assume on
  every device.
- **Enhanced (optional, where hardware present)**: UWB secure ranging (Apple
  Nearby Interaction / Android UWB) or Bluetooth 6.0 Channel Sounding —
  cryptographic distance-bounding that resists relay. Negotiated, best-effort,
  with **graceful downgrade to the RSSI baseline** when either side lacks it.

  > **UWB is not portable.** It is present on iPhone 11 and later (not the SE),
  > but on Android only on a minority of flagship/Pro models (e.g. Pixel 6 Pro+,
  > Samsung S21+/Ultra and later, a few others) — most mid-range and budget
  > Android phones have no UWB radio. We therefore treat UWB strictly as an
  > opt-in upgrade for two capable devices, never a requirement. For high-value
  > transfers where strong proximity matters and UWB is absent, fall back to the
  > SAS code (§4) or to QR Platba — see the threat model's value-threshold item.

## 6. Authorisation & settlement

QRlessPay transfers a *proposal*; it never moves money on its own.

1. The payer app opens a **pre-filled payment proposal** (recipient name, masked
   IBAN, amount, message) from the verified `spayd`. The payer's explicit
   confirmation of this screen is the **mandatory, always-present** authorisation
   gate — every other identity control below is optional and may be absent.
2. **Verification of Payee (name ↔ IBAN), where available.** This is the strongest
   identity control, but it is **optional, not assumed**: the EU Instant Payments
   Regulation mandates VOP only for **euro / SEPA** credit transfers. **Czech CZK
   domestic instant payments are not yet covered by a deployed VOP scheme**, so
   for the CZ domestic rail VOP MUST be treated as absent today. When present, the
   app SHOULD use it; when absent, it falls back to the §6.1 confirmation +
   proximity only. **TODO / watch:** adopt VOP for CZ domestic once ČBA / the
   domestic rail ships a name-check scheme; gate behind a capability flag.
3. The payer authorises with **SCA**; the payment is sent over the normal IBAN /
   instant-payment rail. The payee learns of settlement out-of-band (existing
   session-status poll).

## 7. State machine (payer)

`SCANNING → SELECTED → CONNECTING → BUNDLE_READ → VERIFYING → (PROXIMITY_OK) →
PROPOSAL → AUTHORISED → SETTLED`. Any verification failure → `REJECTED` with a
specific reason; the GATT link is closed immediately after `BUNDLE_READ` (plus
the optional SAS exchange) — connections are short-lived.

## 8. Versioning & interop

- `ver` high nibble gates breaking changes; unknown higher versions are ignored
  by a v1 scanner. New optional fields use new CBOR keys + advert flags, so older
  peers ignore them gracefully.
- Interop is defined by this profile alone; no shared backend, and no lookup of
  any kind at verification time (§11).

## 9. Conformance

An implementation is QRlessPay-v1 conformant iff it: advertises/parses the §2
beacon both ways (service-data and local-name); serves/reads the §3 CBOR bundle
over the §1 GATT profile; performs all §3 verification steps; enforces a §5
proximity gate; and never presents a payable proposal that skips §6 confirmation.

## 10. v1.1 draft — MITM hardening (proposal, not yet normative)

Design-review outcome on the active-MITM axis, recorded here so it is not lost;
none of it ships before the threat-model §8 gates (independent crypto review in
particular). All items are **additive** — negotiated via advert flags / new CBOR
keys per §8, with graceful downgrade to the v1 baseline.

1. **Encrypted bundle (confidential + key-authenticated GATT read).** Today the
   bundle is signed but plaintext: a passive sniffer of the payer-initiated GATT
   read learns the IBAN and amount, and an active MITM is stopped only by the
   `kh` binding + payer confirmation. Proposal: the payer writes an ephemeral
   X25519 public key to `sas` (`28299596-338B-4BDA-8F5C-A74C471DCA6E`) before reading `bundle`; the payee
   returns the CBOR bundle as a **ChaCha20-Poly1305** AEAD ciphertext under
   `key = HKDF-SHA-256(X25519(payer_eph, payee_session), "QP-ENC" ‖ sid)`, with
   the advert `sid` as associated data. This upgrades the existing SAS DH
   exchange into a full encrypted channel: an attacker who cannot produce the
   payee session key (bound to the advert via `kh`) cannot decrypt-and-reswap
   the payload, and the IBAN is no longer readable off the air even during the
   transfer. Signature inside stays Ed25519 — encryption complements, never
   replaces, the §3 verification order. Advert flag `0x8` = encryption-capable;
   either side lacking it falls back to the v1 plaintext bundle.
2. **SAS strengthening.** 6 digits (~20 bits) instead of 4 (~13 bits) for the
   numeric comparison, derived per §4; and a **default-on policy above a value
   threshold** (threshold = threat-model §8 open item 5) rather than
   opt-in-only. Below the threshold SAS stays optional to protect the tap-to-pay
   UX.
3. **Strong distance bounding where hardware allows** — unchanged from §5 (UWB
   secure ranging / BT6 Channel Sounding, optional, graceful downgrade). Listed
   here because with 1+2 in place, relay is the residual active attack and UWB
   is its only cryptographic answer.
4. **Platform link security.** Where both stacks allow it without pairing-UX
   damage, prefer LE Secure Connections for the GATT link; treated as
   defence-in-depth only — the profile's guarantees must never depend on BLE
   link-layer security (it is absent on unpaired connections).

Explicitly rejected in review: raising Ed25519/SHA-256 to larger primitives
("stronger signature") — the curve is not the weak axis; the weak axes are
payload confidentiality, human comparison entropy (SAS), and relay, which items
1–3 address in that order.

## 11. The no-lookup invariant, and what it retired

**QRlessPay is an extension of SPAYD, and SPAYD has no backend.** A SPAYD string
is a payment descriptor any bank's app parses from the bytes alone — no registry,
no directory, nothing to consult. This profile carries that same descriptor over
BLE instead of a QR image, and everything it adds keeps the property: signature,
advert↔bundle binding, expiry, replay rejection and proximity are all decidable
from the bytes in hand plus the payer's own clock, radio and history — state that
never leaves the device and that no other party has to agree with.

> **The test for any future addition: can the payer decide it from the bytes in
> hand and its own device state?** If it needs a lookup, it is no longer a SPAYD
> extension — and the lookup, not the cryptography, is what stops two banks' apps
> interoperating off this profile alone.

**Bank attestation (`att` JWS) failed that test and is not part of v1.** A
signature is only meaningful once you can say whose key produced it, and that
answer necessarily lives outside the bundle. Every candidate trust anchor — a
trust list shipped per app, an issuer derived from the IBAN bank code via a
national registry, a scheme-operated participant registry — is interbank
coordination, which ADR-0095 rejected the incumbent designs for requiring. And
without an anchor the check is not a control at all: an attacker publishes their
own JWKS, self-attests their own IBAN, and the verification passes. The choice
was between a coordination dependency and a check that decides nothing.

**Advert flag `0x2` and CBOR key 8 stay RESERVED, not retired** — deliberately,
so the decision is reversible if a trust anchor ever exists that does not cost
portability (a scheme registry under ČBA/EPC would be one). Reserving promises
nothing while keeping the codepoints free. Until then:

- A payee MUST NOT set flag `0x2` and MUST NOT emit CBOR key 8.
- A payer MUST ignore both, and MUST NOT present a bundle as carrying any
  stronger identity assurance because they are present.

The residual identity gap this leaves is real and stated plainly in the threat
model: with attestation out and VOP absent for CZ domestic, identity rests on
proximity, the mandatory masked-IBAN confirmation, and the payer-side duplicate
and same-name warnings — all of which are device-local and pass the test above.
