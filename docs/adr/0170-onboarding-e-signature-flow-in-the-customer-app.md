---
date: 2026-07-15
decision-status: proposed
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [mobile-app, onboarding, documents, sca]
summary: "The app's mock agreement step splits: GDPR consent is captured pre-registration and actually sent to the edge, while the framework agreement is signed after account provisioning via a real PDF viewer and SCA biometric signature."
followup: "#1284 — real signing is fail-closed on a throwaway dev cert; the OpenBao signing key is unseeded (owner action, not automatable) and require-trusted-issuer is off by default"
---

# ADR-0170 — Onboarding e-signature flow in the customer app

**Delivery note (2026-08-18).** The ceremony platform is shipped: `SignatureCeremony`/`Signer`
domain model, two-tier signature levels, `customer-edge` document/signature routes (PR #1037,
#1102, #1139) and the idempotent onboarding agreement (#1137). It is not yet a usable real
signature: `PdfBoxPadesSealAdapter` refuses to seal unless `allow-ephemeral-seals` is explicitly
true (default false, #1298 fail-closed gate) and the Vault-backed signing keystore has never been
populated — real ceremonies fail closed by design, not by accident, pending a deliberate
operator action outside this codebase.

## Context

The customer app (`openbank-app`, KMP/Compose) has an onboarding wizard whose step 3 —
`StepAgreement` — *looks* like contract signing: a document list ("Rámcová smlouva o platebních
službách", "Obchodní podmínky a ceník"), GDPR/marketing consent rows, and a hold-to-sign
fingerprint gesture. It is **entirely a mock**, verified against `main` (2026-07-15):

- The "documents" are hard-coded Czech/English sample strings rendered as plain Compose `Text`
  (`DocViewerOverlay`) — not fetched, not the published template, not a PDF.
- The consent booleans are written to an on-device key-value store and folded into `OnboardRequest`,
  but `HttpOnboardingApi.register` / `registerAuthenticated` **omit them from the JSON body** — so
  consent never reaches the edge. Nothing is signed; no legal record is produced.

Three more facts shape the design:
- **No PDF/WebView rendering exists anywhere in the app** — every "document" today is monospace
  Compose `Text`. There is no native PDF viewer.
- **Language is a manual in-app CS/EN toggle** (`lang` state, defaults `"cs"`); `systemLanguage()`
  exists but is unused. `lang` is available in every screen scope.
- **SCA signing already works** for payments: `ScaDeviceKey.sign()` (biometric-gated hardware
  ECDSA) over a payment-bound payload → `POST sca/challenges/{id}/decision`. A document ceremony
  mirrors this exactly.
- **Ordering**: the mock agreement sits at step 3, *before* the party even exists (registration
  happens at step 4). But the real ceremony (ADR-0162 D7) is created *after* `account.created`,
  i.e. after registration. The real signature therefore cannot live where the mock does.

ADR-0169 defines the edge API + document-bound SCA this consumes. This ADR is the app-side
decision: what the customer sees and does, and where it sits in onboarding.

## Decision

### D1 — Split the mock: pre-contractual consent stays early, cryptographic signature moves after account provisioning

- **Before registration** (former step 3, kept): show the customer the pre-contractual information
  and capture the **GDPR consent** they must give up front. This consent now travels to the edge
  with registration (fixing the drop noted above) — it is a legal fact, not on-device state.
- **After registration + account provisioning** (new step): the customer reads and **cryptographically
  signs the framework agreement**. This is where the real ceremony (ADR-0162 D7, ADR-0169 D3) lives,
  because that is when the document/ceremony exist. The former hold-to-sign fingerprint mock is
  replaced by a real SCA biometric signature (D3 below).

Onboarding becomes: `Welcome → KYC → Email → Consent (pre-contractual) → Passkey (register →
account opens → ceremony) → 📄 Sign framework agreement → HOME`.

### D2 — Native PDF rendering of the exact server artifact

The app gains its first real PDF viewer via `expect`/`actual`: **iOS `PDFKit.PDFView`**, **Android
`PdfRenderer`**, surfaced as a Compose component. It renders the exact bytes streamed from
`GET /customer/v1/documents/{id}/content` (ADR-0169 D1/D4) — never a re-formatted local view, so
what the customer scrolls is byte-identical to what gets sealed. VOP and ceník are shown through the
same viewer.

### D3 — Real SCA biometric signature over the document (reusing the payment rails)

Signing reuses the existing possession+inherence ceremony, re-pointed from a payment to a document:
`ScaApi.initiateChallenge(purpose = DOCUMENT_SIGNING, dynamicLinkingData = { ceremonyId, documentId,
documentSha256 })` → `ScaDeviceKey.sign(payload-including-doc-hash)` fires Face ID / biometric →
`POST /customer/v1/signature-ceremonies/{id}/decisions { decision: SIGNED, evidenceRef: challengeId }`.
The document hash the app signs is computed over the fetched PDF bytes (ADR-0169 D2). No new signing
UI paradigm — the customer experiences the same biometric confirmation they already use for
payments, over the contract instead of a transfer.

### D4 — App-side gating until signed

The account is opened before the framework agreement is signed (ADR-0162 D7 keeps document-service
off the money-path). The app therefore **gates HOME/transacting on the signed ceremony**: after
registration it queries for a pending onboarding ceremony (`ensureOnboardingAgreement`, ADR-0169
D3); an unsigned one forces the signing step, and only a `SIGNED`/`COMPLETED` ceremony lets the
customer reach a fully-active HOME. A returning customer who somehow bypassed it (e.g. app killed
mid-flow) is re-gated on next launch. This is a UX/product gate, not a money-path block — it keeps
the "you must sign the framework contract before using the account" invariant without violating
ADR-0086.

### D5 — Language drives the render; framework agreement is signed, VOP/ceník acknowledged

- The in-app `lang` (CS/EN) is passed to `POST /customer/v1/documents/agreements {lang}` (ADR-0169
  D3) so the customer signs the contract in the language they read. Signed ⇒ language frozen.
- **Signed** (one ceremony, SCA): the **framework agreement** (`RAMCOVA_SMLOUVA`), which incorporates
  the account contract terms. **Acknowledged** (viewable + a confirm tap, incorporated by reference):
  **VOP** and **ceník**. Phase 1 keeps a single signed document to keep the ceremony simple; a
  multi-document ceremony (separately signing the account contract) is a later refinement, not a
  Phase-1 requirement.

### D6 — Phasing & cleanup

- **Phase 1 (this ADR):** AdES signature (ADR-0162 D4 phase 1), CS/EN, single signed framework
  agreement, app gate. Delivers a legally-valid onboarding contract end to end.
- **Phase 2 (later):** QES/HSM (ADR-0007), a visual signature appearance, an in-app "My contracts"
  surface for documents beyond onboarding, and device-locale-driven default language.
- The on-device consent KV store and the hard-coded sample legal text are **removed** — consent
  becomes part of the signed/edge record, and legal text has exactly one source of truth (the
  published template).

## Alternatives considered

- **Keep signing at step 3 (before registration).** Rejected: the document/ceremony don't exist
  until after `account.created`; signing before the party exists would require inventing a parallel
  pre-party document flow, contradicting ADR-0162 D7.
- **Render legal text as styled Compose text (no PDF viewer).** Rejected: signed ≠ shown (ADR-0169
  D4), and it keeps a second copy of legal text on-device that drifts from the published template.
- **Hard-gate account opening on the signature (block money-path).** Rejected: violates ADR-0086
  money-path decoupling. D4's app-side gate achieves the product invariant without it.
- **Auto-pick language from device locale now.** Deferred to Phase 2: the app already has an
  explicit CS/EN toggle that the customer controls; wiring `systemLanguage()` as a *default* is a
  small, separable follow-up.

## Consequences

**Positive**
- Onboarding becomes genuinely complete and legally sound: a customer signs a real, versioned,
  sealed framework agreement in their language, evidenced by a document-bound SCA signature.
- Removes a latent liability — a mock step that presents as "signed" but produces no legal record.
- The new native PDF viewer is reusable for statements and any future document surface.

**Negative**
- First native PDF integration (per-platform `actual`s) and an onboarding-flow change with a new
  gated state to test on device.

**Neutral**
- No new backend service; all backend capability exists (ADR-0162) or is added at the edge (ADR-0169).

## Compliance impact

- **eIDAS / PSD2**: as ADR-0169 (AdES; document-bound SCA). The app is the presentation + SCA
  trigger; the legal artifact is produced server-side.
- **GDPR**: GDPR consent now reaches the edge as a first-class fact rather than dying on-device.
- **Accessibility/consumer law**: the customer reads the actual contract PDF in their language
  before signing — a stronger informed-consent posture than the mock.

## References

- ADR-0169 — Customer document access & SCA-bound signing over customer-edge (the API this consumes)
- ADR-0162 — Document service, templating & e-signature (D7 onboarding integration)
- ADR-0021 — SCA device approval (biometric signature); ADR-0066 — customer app auth
- ADR-0069 — customer onboarding journey; ADR-0086 — money-path decoupling
- ADR-0065 — customer-edge trust boundary
