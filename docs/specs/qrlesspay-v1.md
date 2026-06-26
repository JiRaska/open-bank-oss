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
| Service UUID (advert filter + GATT service) | `QP01` — 128-bit, registered for the standard (placeholder `0000QP01-…`) |
| GATT characteristic — `bundle` (read) | `…QP02`, read-only, returns the signed bundle |
| GATT characteristic — `sas` (read/write, optional) | `…QP03`, ephemeral-DH for the Short Authentication String |
| Advert service-data company/16-bit alias | `0xF0B2` (Android service-data budget) |

## 2. Phase 1 — discovery advert

Connectionless BLE advertisement. Service UUID = scan filter. The beacon payload
rides in service data (Android) or local name (iOS) — both forms MUST be
accepted by scanners. Layout (≤24 bytes):

| Field | Size | Notes |
|-------|------|-------|
| `ver` | 1 B | high nibble = protocol version (`1`); low nibble = flags: `0x1` amount-present, `0x2` bank-attested, `0x4` SAS-capable |
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
  8: att        (bstr, optional)  # bank attestation (JWS), present iff advert flag 0x2
}
```

Verification by the payer, in order — any failure aborts:

1. `ver == 1`; `sid == advert.sid`; `SHA-256(pk)[:2] == advert.kh`.
2. `sig` verifies against `pk`.
3. `exp` is in the future and within 90 s; `nonce`/`sid` not seen before.
4. `spayd` parses to a valid SPAYD (valid IBAN in `ACC`, no spaces, amount ≤ 2 dp).
5. **Proximity** gate passes (§5).
6. If `att` present: JWS verifies against the payee bank's published JWKS and its
   subject IBAN matches `ACC`.

## 4. Cryptography

- Signature: **Ed25519**. Keys are **per-session** (regenerated each request
  screen), held only in memory — the signature proves "same device, this
  session", not long-term identity. Long-term identity comes from the IBAN +
  Verification of Payee (§6), or from the optional bank attestation (`att`).
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
- Interop is defined by this profile alone; no shared backend. The bank-attested
  path (`att` + `.well-known`) is an additive capability, not a separate
  protocol.

## 9. Conformance

An implementation is QRlessPay-v1 conformant iff it: advertises/parses the §2
beacon both ways (service-data and local-name); serves/reads the §3 CBOR bundle
over the §1 GATT profile; performs all §3 verification steps; enforces a §5
proximity gate; and never presents a payable proposal that skips §6 confirmation.
