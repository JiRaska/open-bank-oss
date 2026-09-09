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
- **delegation-service KafkaUser → `openbank.delegation.events` → snapshot consumer:** the broker
  authenticates the service-specific mTLS identity and ACL; this is intentionally not a REST write
  because the shared backend OIDC identity cannot distinguish delegation-service from other
  workloads. Results leave on the dedicated `openbank.documents.disclosure-snapshot.event` topic.
- **Kafka → document-service (new, ADR-0248):** `billing-service`'s billing-outbox
  (`openbank.billing.fee.event`, `AnnualFeeSummaryReadyConsumer`) — the annual statement of
  fees is a PAD Art. 5 push duty, so this is the one template family document-service renders on an
  async trigger rather than a synchronous caller request. Same poison-pill posture as the existing
  `AccountCreatedConsumer` ingress (malformed/incomplete events are logged and skipped, never
  crash the consumer or wedge the partition); the rendered bytes never reach the object store (no
  `Document` row, no outbox event) and are handed to a delivery port that is a **logging-only
  phase-1 stub** — no real email/postal channel exists yet, so nothing is actually sent today.
- **Any internal service → `POST /api/v1/documents/templates/preview` (new synchronous callers,
  ADR-0248):** `statement-service`, `sepa-payment-service` and `domestic-payment-service` now call
  this existing endpoint synchronously to render a statement/confirmation document on customer
  request, alongside document-service's own editor-preview use. This endpoint never persists (no
  `Document` row, no object-store write, no outbox event), so it carries no new data-at-rest risk —
  only a rendering-abuse/DoS surface, mitigated by the existing `MAX_PREVIEW_BODY_LENGTH` cap
  (200,000 chars) and the platform rate-limit noted under **Denial of service** below. Each caller
  still authenticates as any other endpoint on this resource (OIDC bearer,
  `ROLE_API`/`ROLE_OPERATOR`/`ROLE_ADMIN`) — this is a new set of *callers*, not a new
  authentication or authorization boundary.

## Threats & mitigations (STRIDE)
| Threat | Mitigation |
| --- | --- |
| **Spoofing the caller** | Every REST endpoint requires a valid OIDC bearer with `ROLE_SERVICE`/`ROLE_OPERATOR`/`ROLE_ADMIN`; unauthenticated calls are 401. Reflection guard test asserts no endpoint is `@PermitAll`/unannotated. The `AnnualFeeSummaryReadyConsumer` Kafka ingress has no per-message caller identity (mTLS at the broker is the only authentication layer, ADR-0056) — mitigated by `eventType` + required-field validation and by the consumer being a pure read of `billing-service`'s own outbox topic, never a write path back into billing. |
| **Tampering — document forgery** | Documents render only from a `PUBLISHED` template (`findPublished`); the rendered bytes are content-addressed by SHA-256 and stored under a derived key; phase-2 adds S3 Object Lock (WORM) so stored bytes are immutable. |
| **Tampering — disclosure source substitution or retry race** | Snapshot issuance requires the command's expected party to equal the immutable source `partyRef`, status `SIGNED`, media type PDF, and source bytes to match the persisted SHA-256. `request_id` is unique; the blob key includes deterministic request identity plus content digest, and the service validates the row returned after `ON CONFLICT`, so two conflicting requests cannot overwrite or substitute the winner. A database trigger rejects UPDATE/DELETE of evidence rows. |
| **Tampering — SSTI / XSS via template + data** | The phase-1 renderer is logic-less `{{token}}` substitution (regex only, no engine), and substituted values are HTML-escaped. Production Handlebars/Qute adapter (ADR-0162) must stay logic-less and sandboxed behind the same port. |
| **Repudiation** | Lifecycle transitions emit domain events to the outbox → audit pipeline; phase-2 PAdES sealing makes the signed artifact independently verifiable (non-repudiation). Ceremony records each signer's decision + timestamp. |
| **Information disclosure** | Content is `restricted`-class; access is role- and OPA-gated; no PII in logs (JSON console, no body logging). Ordinary content remains behind authenticated `/{id}/content`. Disclosure redemption has a separate snapshot-only read: it requires the caller's expected SHA-256, answers 404 for missing/malformed/mismatched values, returns no storage key, and sets `private, no-store`. It cannot resolve mutable source-document bytes. |
| **Disclosure command forgery** | Only delegation-service's KafkaUser may write `openbank.delegation.events`; document-service has Read/Describe and a dedicated consumer group. Malformed commands are ignored/rejected with bounded reason codes and no source detail. No snapshot-creation REST endpoint exists behind the fleet-shared OIDC client. |
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

## Change log

- **2026-09-09** — Added the authenticated snapshot-content read required by delegation redemption.
  The boundary accepts only an immutable snapshot id plus its expected SHA-256, conflates mismatch
  with absence, verifies stored bytes again through the snapshot use case, and never returns a
  source-document or object-store locator. It is deliberately not public; delegation-service owns
  recipient proof, expiry, revocation and atomic view consumption.

- **2026-09-09** — External disclosure snapshot boundary added (ADR-0232 D7): immutable metadata,
  digest verification, idempotent command handling, service-specific Kafka authentication, explicit
  DLQ/topic ACLs and a dedicated result schema. No public download or OTP endpoint exists in this
  slice; watermark/redaction after an existing signature remains prohibited because it would
  invalidate PAdES.

- **2026-09-03** — Doc correction, no behavior change: §2 named the billing ingress topic
  `openbank.billing.billing.event`. No such topic exists — the string occurs nowhere in the
  repository except this document (`git grep -l -F openbank.billing.billing.event` returns only
  this file), so no consumer, producer, contract or `KafkaTopic` CR has ever carried it. **The
  ingress itself is real and correctly wired**: the topic is `openbank.billing.fee.event`, declared
  by the consumer at `openbank-document-service/src/main/resources/application.yaml:251`, by the
  producer at `openbank-billing-service/src/main/resources/application.yaml:131`, and in the
  contract at `openbank-contracts/openbank-billing-service/asyncapi.yaml:66`. The consumer class
  named in the same sentence, `AnnualFeeSummaryReadyConsumer`, does exist and is unchanged. Only
  the topic name in this document was wrong; nothing about the PAD Art. 5 push duty, the
  poison-pill posture or the trust boundary changes.
