# 66. Passwordless customer authentication — passkey-first onboarding and login

Date: 2026-06-05
Status: Accepted
Author(s): OpenBank platform

## Context

The `openbank-customers` Keycloak realm (ADR-0065) was created with a public PKCE client
(`openbank-app`) and WebAuthn/passkeys *configured* (ES256, platform authenticator,
UV=required) but the **authentication flow still defaults to username + password**. The
WebAuthn configuration only takes effect when the browser flow explicitly invokes a
WebAuthn authenticator step — which the default Keycloak `browser` flow does not do.

Meanwhile, ADR-0021 introduced a hardware-backed SCA signing key (Secure Enclave /
Android Keystore) for payment approval. This creates a situation where:
- Login uses a password (phishing-susceptible, weak inherence factor)
- Payment approval uses a hardware-backed cryptographic assertion (strong, phishing-resistant)

Requiring a password for login while requiring a cryptographic assertion for payments is
architecturally inconsistent and a poor UX. A password is also unnecessary: the device's
hardware security (Secure Enclave + biometrics) provides both the *possession* and
*inherence* factors required by PSD2 RTS in a single gesture.

The goal: **no password is ever set or needed**, from the first onboarding step through
every subsequent login and payment.

## Decision

**We will adopt passkey-first (FIDO2/WebAuthn) authentication for all customer sessions.
No password is created during onboarding or ever used for login or SCA.**

Concretely:

### 1. Two hardware-backed keys, one Secure Enclave

```
Secure Enclave / Android Keystore
├── Login passkey  — registered with Keycloak WebAuthn
│                   Scope: authentication (who you are)
│                   Relying party: kc.open-bank.tech / openbank-customers
│
└── SCA signing key — registered with openbank-sca-service (ADR-0021)
                    Scope: transaction authorization (consent to a specific payment)
                    Relying party: open-bank.tech / sca-service
                    Carries dynamic-linking data (amount + payee, RTS Art. 5)
```

Two separate key pairs even though they live in the same hardware enclave. Separation
is PSD2-correct: the *authentication* factor (who the customer is) is independent of the
*authorization* factor (consent to a specific amount and payee). The same biometric gesture
(Face ID / Touch ID) protects both but the signed payloads are entirely different.

### 2. Keycloak `openbank-customers` realm — passkey-first browser flow

Replace the default `browser` flow with a custom flow that uses WebAuthn as the **primary
and only** factor:

```
openbank-customers browser flow (custom):
  1. WebAuthn Authenticator (required)
     — on success → session granted, no further steps
     — on first visit (no registered passkey) → redirect to registration
  2. Cookie (alternative) — allow SSO cookie continuation within the session
```

*No* `Username Password Form` step. *No* OTP step. A customer who loses their device
follows the recovery path (out-of-scope for this ADR; see Consequences).

The custom flow is defined in `customers-realm-template.json` (gitops) and applied via
`kcadm.sh` or Keycloak import.

### 3. Onboarding sequence (no password ever created)

```
App          KYC/Party       Keycloak         SCA service
 │                                │
 ├─KYC scan──────────────►kyc-service validates identity
 │◄────────────── party_id ───────┤
 │                                │
 ├─register passkey───────►Keycloak WebAuthn registration
 │◄──────────── session ──────────┤
 │                                │
 ├─enroll SCA key──────────────────────────►POST /sca/parties/{id}/devices
 │◄────────────────────────────────────────── 201
 │ (app is fully operational — no password touched)
```

### 4. Login flow — `ASWebAuthenticationSession` → native passkey

**Phase F1 (current):** `ASWebAuthenticationSession` opens Safari-hosted Keycloak login.
Keycloak's browser flow now presents the WebAuthn authenticator UI (system passkey sheet)
instead of a password form. Works on iOS 16+ with platform passkeys. No code change
required in the app — only the Keycloak flow configuration changes.

**Phase F2 (target):** `ASAuthorizationController` with `ASAuthorizationPlatformPublicKeyCredentialProvider`
— native passkey API, no browser redirect, supports Conditional UI (passkey autofill
above the keyboard). The app calls the Relying Party (Keycloak OIDC + WebAuthn assertion
endpoint) directly and exchanges the assertion for a token. Better UX, same security.

### 5. Gitops implementation

The Keycloak authentication flow change is delivered as a gitops artifact:
- `customers-realm-template.json` updated with the custom passkey-only browser flow.
- Applied via `kcadm.sh` in the sandbox (no pod restart needed — Keycloak supports live
  realm configuration updates).
- Vault Keycloak realm key is updated; the ExternalSecret re-syncs on next reconcile.

## Alternatives considered

- **Password + WebAuthn as 2FA** — rejected: adds phishing risk of the password factor
  and unnecessary UX friction. PSD2 RTS Art. 4(1) allows inherence + possession (=
  biometric passkey) without a knowledge factor.
- **SMS OTP as fallback** — explicitly deferred for sandbox (CLAUDE.md, ADR-0065);
  remains deferred here. An enrolled passkey IS two-factor by itself on modern hardware.
- **Native `ASAuthorizationController` immediately** — rejected for F1: requires the app
  to implement the WebAuthn assertion exchange + Keycloak token endpoint wiring. That is
  F2 scope; `ASWebAuthenticationSession` delegates all of this to Keycloak for F1.
- **Shared login + SCA key** — rejected: single-key compromise would break both
  authentication and payment authorization. Separate keys limit blast radius.

## Consequences

**Positive**
- No password = no phishing surface, no credential-stuffing risk.
- Single biometric gesture satisfies PSD2 RTS inherence + possession in one step.
- UX: "tap Face ID → logged in" on every return visit.
- Consistent with ADR-0021 (SCA key) — both factors are hardware-backed.

**Negative**
- Device loss / recovery path not yet designed — a customer who loses their phone has no
  password fallback. Recovery requires out-of-band identity re-verification (KYC replay
  or bank branch flow). This is **not in scope here** and must be designed before
  production (candidate for ADR-0067).
- Keycloak Conditional UI / native passkey (`ASAuthorizationController`) is F2 work; the
  F1 flow via `ASWebAuthenticationSession` includes a brief browser redirect that slightly
  roughens the UX on first login (acceptable for sandbox / early adopters).
- Android Credential Manager passkey support (F2) adds Android-specific code; the
  `OAuthLauncher.android.kt` actual is currently a stub.

**Neutral**
- Two separate enrollments (login passkey + SCA key) add ~15 seconds to onboarding but
  are conceptually clear to users ("set up login" → "set up payment approval").

## Compliance impact

- **PSD2 RTS Art. 4**: passkey = possession (device Secure Enclave) + inherence (Face ID /
  biometric) — satisfies two-factor authentication requirement without a knowledge factor.
- **PSD2 RTS Art. 5**: SCA key (ADR-0021) separately satisfies dynamic linking for
  payment initiation. The login passkey does NOT satisfy Art. 5 (it is not bound to
  amount + payee). Correctly separated.
- **GDPR**: biometric verification is processed on-device by the OS; the server never
  sees the biometric template — only the assertion signature over a challenge.
- **DORA**: passkey credentials are hardware-bound; loss of the Keycloak WebAuthn
  credential store is mitigated by the recovery path (to be designed in ADR-0067).
- **CNB**: not applicable at the authentication-mechanism level.

## References

- ADR-0021 — SCA decoupled device approval (SCA signing key, separate from login key).
- ADR-0064 — KMP app stack (iOS: Secure Enclave, `ASWebAuthenticationSession`).
- ADR-0065 — Customer-facing edge + `openbank-customers` Keycloak realm.
- W3C WebAuthn Level 3 — https://www.w3.org/TR/webauthn-3/
- PSD2 RTS (EU) 2018/389 — Art. 4–5 (SCA elements + dynamic linking).
- Apple Human Interface Guidelines — Passkeys.
