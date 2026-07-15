# ADR-0169 — Customer document access & SCA-bound signing over customer-edge

Date: 2026-07-15
Decision-Status: Proposed
Delivery-Status: Planned
Author(s): jiri.raska

## Context

ADR-0162 shipped `openbank-document-service`: versioned templates, HTML→PDF rendering, WORM
storage, and a signature ceremony that seals a document with the signer's own one-time PKI
signature plus the bank's PAdES-B institutional seal (eIDAS **AdES**). D7 of that ADR even wires
onboarding: on `account.created`, `OnboardingDocumentService` renders the product's bound contract
and opens a single-signer ceremony for the account holder.

But the capability is **not reachable by the customer**. Verified against `main` (2026-07-15):

1. **Every document-service endpoint is `@RolesAllowed("ROLE_SERVICE", "ROLE_OPERATOR",
   "ROLE_ADMIN")`** — `DocumentResource` and `SignatureCeremonyResource` alike. A customer JWT
   (`ROLE_CUSTOMER`, `openbank-customers` realm) cannot fetch a rendered PDF, list its own
   documents, or record a signature decision. `openbank-customer-edge` — the single internet-facing
   surface for the app (ADR-0065) — exposes **no** document or signature routes at all.
2. **The SCA binding is document-agnostic.** `ScaVerificationAdapter` (the `SignerVerificationPort`
   over sca-service) treats `evidenceRef` as an SCA challenge id and accepts a SIGNED decision iff
   that challenge is `COMPLETED`, belongs to the signer's party, and hasn't been consumed. It does
   **not** check that the challenge was raised *for this document* — its own KDoc flags the gap:
   "it does encode an assumption … that a future, purpose-built 'verify approval' endpoint could
   make explicit." So today *any* completed challenge for the party would authorize signing *any*
   document. For a payment, RTS Art. 5 **dynamic linking** binds the SCA approval to the specific
   amount+payee; the equivalent binding for a *document* signature does not yet exist.
3. **D7 renders in one fixed language.** `OnboardingDocumentService` resolves a single
   `documentTemplateCode` from product-catalog and renders it with `data = emptyMap()`. Template
   codes are locale-suffixed (`RAMCOVA_SMLOUVA_CS` / `_EN`, ADR-0162 seed), so a product binds
   exactly one language; there is no path for the customer to receive the contract in the language
   they actually read.

This ADR defines the **customer-facing contract** for reading and signing documents: the edge API,
the document-bound SCA linking that makes the signature legally sound, and language-correct
rendering. The app-side UX that consumes it is ADR-0170. (The mock "Agreement" step in the app
today — local-only consent that never reaches the backend — is replaced by ADR-0170 D1.)

## Decision

### D1 — Customer document & signature routes on customer-edge (ownership-scoped)

The app never talks to document-service directly (ADR-0065 trust boundary). `customer-edge` gains
`ROLE_CUSTOMER` routes that proxy to document-service with the edge's M2M service token, enforcing
ownership on every call — `partyRef`/document/ceremony must resolve to the caller's `party_id`
claim (the same `resolvePartyIdClaim` pattern the edge already uses; IDOR is the primary risk):

| Edge route (`/customer/v1/…`) | Proxies to document-service | Ownership check |
|---|---|---|
| `POST /documents/agreements` (body: `{lang}`) | ensure-onboarding-agreement (D3) | party = token |
| `GET /documents` | `GET /api/v1/documents?partyRef=<token party>` | forced partyRef = token |
| `GET /documents/{id}/content` | `GET /api/v1/documents/{id}/content` | document.partyRef = token |
| `GET /signature-ceremonies/{id}` | `GET /api/v1/signature-ceremonies/{id}` | ceremony's signer = token |
| `POST /signature-ceremonies/{id}/decisions` | `POST …/{id}/decisions` | signer = token |

The edge **never** trusts a client-supplied `partyRef`: it injects the token's party on reads and
rejects a decision whose ceremony signer isn't the caller. PDF content streams back with
document-service's own `Content-Type` (`application/pdf`), like the existing statement-render proxy.

### D2 — Document-bound SCA: dynamic linking for a signature act (RTS Art. 5, applied to documents)

The signer's biometric SCA approval MUST be bound to the exact document, closing the gap in §2. We
extend the SCA contract with a **document-signing purpose** whose dynamic-linking data *is* the
document identity, mirroring how a payment challenge binds amount+payee:

- The app opens an SCA challenge with `purpose = DOCUMENT_SIGNING` and dynamic-linking data
  `{ ceremonyId, documentId, documentSha256 }` — the SHA-256 of the exact rendered PDF the customer
  is viewing (document-service already content-addresses documents by SHA-256, ADR-0161).
- The on-device key (ADR-0021 `ScaDeviceKey`, Secure Enclave / AndroidKeyStore, biometric-gated)
  signs a payload that **includes that document hash**, so the hardware signature covers *which
  document* is being authorized — not a bare "approve".
- `SignerVerificationPort.verify` is strengthened: a `DOCUMENT_SIGNING` challenge is accepted as
  evidence only if its linked `documentSha256`/`ceremonyId` match the document/ceremony the decision
  is being recorded against. A completed challenge raised for document A can no longer authorize
  document B. Single-use `consume` (already present) stays — replay-proof per RTS Art. 5.

This makes "what the customer saw is what the customer authorized is what got sealed" a verified
property, not an assumption. It is the document analogue of payment dynamic linking, using the same
SCA rails — no new authentication mechanism.

### D3 — Language-correct, idempotent onboarding-agreement provisioning (app-driven)

Because D7 renders one fixed language server-side, the **app drives** the render language. The edge
`POST /documents/agreements {lang}` calls an idempotent document-service operation
`ensureOnboardingAgreement(partyRef, lang)`:

- If the party has **no** pending (unsigned) onboarding ceremony → render the locale-resolved
  template (`RAMCOVA_SMLOUVA` + `lang` → `RAMCOVA_SMLOUVA_CS`/`_EN`) and open a single-signer
  ceremony; return `{ ceremonyId, documents:[…] }`.
- If a pending ceremony **already exists in the requested language** → return it unchanged
  (idempotent — safe against retries and D7 having pre-rendered it).
- If a pending ceremony exists in a **different** language (e.g. D7 pre-rendered the product's
  default while the customer reads the other) → because the document is still **unsigned**, retire
  it and render+open in the requested language. Once **signed**, the `(templateCode,
  templateVersion)` snapshot is immutable (ADR-0162 D2) and language can never change under a
  customer.

D7's `account.created` auto-render is kept as a pre-warm / server-of-record default; the
app-triggered ensure is the authoritative, language-correct path and reconciles with it. This needs
**no** party-schema change and keeps language a pure presentation concern.

### D4 — The signed artifact is the exact rendered PDF, never a client re-render

The customer views, and signs, the **same** content-addressed PDF that gets sealed — fetched via
`GET /documents/{id}/content`. The app MUST NOT reconstruct or re-format the legal text locally for
display-and-sign (a re-formatted client view would make "what was shown" ≠ "what was sealed", the
classic e-signature integrity hole). Native PDF rendering of the server artifact is ADR-0170 D2.

## Alternatives considered

- **Expose document-service directly to the app.** Rejected: it would move the customer trust
  boundary off customer-edge (ADR-0065) and force `ROLE_CUSTOMER` + ownership logic into a
  non-edge service. The edge is exactly where per-customer ownership belongs.
- **Keep SCA document-agnostic (any completed challenge is evidence).** Rejected: it fails the
  legal core — the signature would not be provably bound to the document, so "the customer
  authorized *this contract*" could not be evidenced. D2 is the whole point.
- **Render the contract client-side from a template + data, sign that.** Rejected: signed ≠ shown,
  and it duplicates the legal-text source of truth on-device where it can drift from the published
  template.
- **Bind language to the party record + rely solely on D7.** Rejected for Phase 1: a party-schema
  + event change for what D3 solves as a presentation concern, and it still races (party language
  unknown at `account.created` for a fresh self-registration). Revisit if language must persist for
  later re-issued documents.

## Consequences

**Positive**
- Closes the last gap between "document-service can sign" and "a customer can sign", reusing the
  edge's proven ownership model and the SCA rails — no new auth mechanism, no new customer-facing
  service.
- D2 upgrades the signature from *an* AdES to one whose signer-intent is cryptographically bound to
  the specific document, materially strengthening the evidential value (and the audit-chain record).

**Negative**
- A small, security-sensitive change to sca-service / `ScaVerificationAdapter` (document-bound
  verification). It touches the SCA path, so it carries a threat-model update on document-service
  (already trust-boundary-flagged by ADR-0162) and a security review.

**Neutral**
- Edge gains a new proxy surface; standard coverage/openapi-contract obligations apply. Non-money-
  path (ADR-0162): a document-service outage degrades signing availability, never a payment.

## Compliance impact

- **eIDAS** (Reg. (EU) 910/2014): Phase-1 signatures are AdES (ADR-0162 D4); D2 binds signer intent
  to the document, supporting the "sole control" and "detectable subsequent change" AdES criteria.
- **PSD2 / RTS Art. 5**: reuses SCA (ADR-0021); D2 is dynamic linking applied to a document
  signature act (single-use, bound to what is authorized).
- **GDPR**: no new personal data at the edge; documents carry the party ref only. Retention/erasure
  overrides for signed contracts stay in document-service (ADR-0162 D5 / ADR-0118).
- **CNB**: records retention via WORM (ADR-0161) + audit hash-chain (ADR-0086/0133), unchanged.

## References

- ADR-0162 — Document management, templating & e-signature (the service this exposes)
- ADR-0021 — SCA decoupled device approval (signer binding); RTS Art. 5 dynamic linking
- ADR-0065 — customer-edge as the single customer trust boundary
- ADR-0161 — Object-storage standard (content-addressed documents)
- ADR-0086 / ADR-0133 — non-repudiation & tamper-evident audit hash-chain
- ADR-0069 — customer onboarding journey; ADR-0170 — the app-side signing UX that consumes this
