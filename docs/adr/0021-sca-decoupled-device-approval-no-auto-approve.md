# 21. SCA push/biometric: decoupled device approval, never auto-approve

Date: 2026-05-29
Status: Accepted
Delivery-Status: Shipped

## Context

`ScaService.verify` (openbank-sca-service) currently auto-approves the two device-based
Strong Customer Authentication methods:

```kotlin
val verified = when (challenge.method) {
    ScaMethod.SMS_OTP, ScaMethod.TOTP -> otpStore.verify(command.challengeId, otp)
    ScaMethod.PUSH_NOTIFICATION, ScaMethod.BIOMETRIC -> true   // <-- always succeeds
}
```

This is critical audit finding **K2**: any caller can complete an SCA challenge by choosing
`PUSH_NOTIFICATION` or `BIOMETRIC` and calling `verify` — no actual user approval is ever
required. It is a full SCA bypass and a direct breach of PSD2 RTS (Commission Delegated
Regulation (EU) 2018/389): the inherence/possession factor is never checked, and the
**dynamic linking** requirement (RTS Art. 5 — the authentication must be bound to a specific
amount and payee) is not enforced.

The root cause is structural, not a one-line typo. OTP methods have an in-band secret the
verifier can check (`otpStore`). Push and biometric are **decoupled** authentication: the
approval happens out-of-band on the user's enrolled device, so there is nothing for the
`verify` caller (the PSP/merchant flow) to present. The code papered over the missing
out-of-band channel by returning `true`.

## Decision

**1. Fail closed immediately.** Until the approval channel below exists, push/biometric
`verify` must **not** auto-approve. It returns "not yet approved" (challenge stays `PENDING`)
or rejects, rather than completing. A method that provides zero authentication must not
report success — an unusable factor is strictly safer than a bypassable one. This is a
deliberate, product-visible behaviour change (those methods stop "working" until step 2
ships) and is the correct default for a system that must not present a known SCA bypass.

**2. Add a decoupled device-approval channel.** Model the out-of-band approval explicitly:

- A new **device-approval endpoint**, authenticated as the *enrolled device / party*
  (a different principal from the `verify` caller), records an approval/denial decision
  against the challenge: `POST /api/v1/sca/challenges/{id}/decision`.
- The decision carries a **cryptographic assertion** bound to the challenge and its
  `DynamicLinkingData` (amount, currency, creditor): a WebAuthn/FIDO2 assertion for
  `BIOMETRIC`, a signed payload from the enrolled push credential for `PUSH_NOTIFICATION`.
  The server verifies the signature against the party's enrolled public key and checks that
  the signed amount+payee equal the challenge's — satisfying RTS dynamic linking.
- `verify` for push/biometric then consults the recorded, signature-verified decision
  (a new `ScaDecisionStore`, mirroring the existing `OtpStore` port) instead of returning a
  literal. No valid decision → challenge stays `PENDING` until it expires.

The challenge state machine already supports this: `ScaChallenge` has `PENDING → COMPLETED`
(approval present) and `PENDING → FAILED` (denied / max attempts / expired). Only the
verification *input* is missing, not the states.

## Consequences

**Positive**
- Closes K2: push/biometric can no longer be satisfied without a real, signature-verified
  device approval.
- Brings the flow into line with PSD2 RTS: possession/inherence factor actually checked,
  and dynamic linking enforced by binding the assertion to amount + payee.
- The new channel reuses existing patterns (out-of-band store port like `OtpStore`; the
  challenge state machine is unchanged).

**Negative**
- Step 1 is a functional regression for any caller relying on push/biometric "working" today
  (they only ever worked by bypass, so this is exposing, not introducing, the gap).
- Step 2 requires device-enrolment + key management (enrol a per-party public key) — real
  scope, not a quick fix. It depends on a device/credential registry that does not yet exist.

**Mitigation / sequencing**
- Ship step 1 (fail-closed) on its own as the security fix; it is small and unblocks the
  "no known bypass" bar. Gate it so SMS_OTP / TOTP — which already verify a real secret —
  keep working as the available SCA methods in the interim.
- Track step 2 (device enrolment + WebAuthn/signed-push + `ScaDecisionStore`) as its own
  milestone; it is the substantive PSD2-compliance work.

## References

- PSD2 RTS — Commission Delegated Regulation (EU) 2018/389, Art. 4–5 (SCA elements +
  dynamic linking)
- WebAuthn / FIDO2 (W3C) — proposed assertion mechanism for the biometric factor
- openbank-sca-service `ScaService.verify` — the bypass this ADR closes (K2)
