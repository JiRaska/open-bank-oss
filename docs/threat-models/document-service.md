# Threat model — Document Service (ADR-0161 / ADR-0162)

**Surface:** `openbank-document-service` — owns document templates, renders documents from a template +
data map, stores the bytes in an object store with lifecycle/retention metadata, and orchestrates
e-signature ceremonies. **Posture:** NOT on the synchronous money path — it emits domain events and is
never a blocking fund-release gate. It IS a **trust-boundary change**: it ingests template bodies and
data that flow into rendered legal documents, holds `restricted`-class content (agreements, statements,
signatures) with a 10-year retention obligation, and orchestrates e-signature — hence this threat model
(ADR-0030).

## Assets
- **Rendered document integrity** — a document must be a faithful, tamper-evident render of a *published*
  template + the supplied data; its SHA-256 content address is the integrity anchor.
- **Template integrity** — only a `PUBLISHED` template may render live documents; a poisoned or
  unauthorized template body is a document-forgery and SSTI vector.
- **Stored content confidentiality & retention** — `restricted` PII (party refs, agreement content) held
  under a 10-year WORM retention obligation (ADR-0161).
- **Signature non-repudiation** — a completed ceremony must be attributable and, in phase-2, PAdES-sealed
  so the signed artifact is independently verifiable.

## Trust boundaries
- Caller (operator / in-cluster service, OIDC bearer, `ROLE_OPERATOR`/`ROLE_ADMIN`/service token) →
  document-service REST.
- document-service → object store (phase-1 Postgres BYTEA; phase-2 S3 + Object Lock/WORM, ADR-0161).
- document-service → render adapters (phase-1 in-process placeholder; phase-2 WeasyPrint/Gotenberg
  sidecar over REST, ADR-0162) — a **new** egress trust boundary when the sidecar lands.
- document-service → seal adapter (phase-1 no-op; phase-2 EU DSS PAdES with a QSeal/HSM key,
  ADR-0007/0162) — introduces an HSM/key-custody trust boundary in phase-2.
- Outbox → Kafka (`openbank.documents.document.event`) → downstream consumers (lending, account).

## Threats & mitigations (STRIDE)
| Threat | Mitigation |
| --- | --- |
| **Spoofing the caller** | Every endpoint requires a valid OIDC bearer with `ROLE_SERVICE`/`ROLE_OPERATOR`/`ROLE_ADMIN`; unauthenticated calls are 401. Reflection guard test asserts no endpoint is `@PermitAll`/unannotated. |
| **Tampering — document forgery** | Documents render only from a `PUBLISHED` template (`findPublished`); the rendered bytes are content-addressed by SHA-256 and stored under a derived key; phase-2 adds S3 Object Lock (WORM) so stored bytes are immutable. |
| **Tampering — SSTI / XSS via template + data** | The phase-1 renderer is logic-less `{{token}}` substitution (regex only, no engine), and substituted values are HTML-escaped. Production Handlebars/Qute adapter (ADR-0162) must stay logic-less and sandboxed behind the same port. |
| **Repudiation** | Lifecycle transitions emit domain events to the outbox → audit pipeline; phase-2 PAdES sealing makes the signed artifact independently verifiable (non-repudiation). Ceremony records each signer's decision + timestamp. |
| **Information disclosure** | Content is `restricted`-class; access is role-gated; no PII in logs (JSON console, no body logging). Content bytes are served only via an authenticated `/{id}/content` endpoint. Phase-2 pre-signed URLs must be short-lived and scoped. |
| **Denial of service** | Rendering is bounded and event-driven, off the money path; platform rate-limit (`openbank.rate-limit`, 200 concurrent). The PDF/seal sidecars (phase-2) need their own timeouts + bulkheads at the port. |
| **Elevation / cluster pivot** | Restricted PSS, non-root, OPA authz (`@Authorize`, advisory→enforce, ADR-0034); egress NetworkPolicy allowlists only Postgres, Kafka, OIDC, OPA (and, in phase-2, the render sidecar + KMS/HSM). |

## Residual risk / follow-ups
- **Object-store WORM (ADR-0161).** Phase-1 stores bytes in Postgres BYTEA — mutable. The S3 + Object
  Lock adapter is the key remaining integrity/retention control; until it lands, immutability rests on
  application discipline.
- **Real rendering + PAdES sealing (ADR-0162).** Phase-1 PDF rendering is a stub and signature sealing is
  a no-op — the signed artifact is not yet cryptographically verifiable. Wiring the WeasyPrint/Gotenberg
  sidecar and EU DSS PAdES (QSeal/HSM key, ADR-0007) each introduces a new trust boundary (external
  egress / key custody) and requires this threat model to be revisited before it ships.
- **QUALIFIED signature level** is declared but phase-2 — the QES/QSCD path is not implemented.
- **10-year retention enforcement** (`retainUntil`) is captured as metadata but not yet enforced by a
  retention/erasure job — a tracked follow-up.
