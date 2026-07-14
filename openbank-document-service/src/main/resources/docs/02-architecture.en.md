# Architecture

## Hexagonal layers (ADR-0002)

```
domain/            pure Kotlin, ZERO framework imports
  model/           DocumentTemplate, Document, SignatureCeremony (+ Signer), enums
  event/           DocumentTemplatePublished, DocumentGenerated, DocumentSigned, SignatureCeremonyCompleted
application/
  port/in/         DocumentTemplateUseCase, DocumentRenderUseCase, DocumentQueryUseCase, SignatureCeremonyUseCase
  port/out/        TemplateRepositoryPort, DocumentRepositoryPort, CeremonyRepositoryPort,
                   ObjectStorePort, TemplateRenderPort, PdfRenderPort, SignatureSealPort, DocumentOutboxRepository
  usecase/         DocumentTemplateService, DocumentRenderService, DocumentQueryService, SignatureCeremonyService
infrastructure/
  rest/            DocumentResource, SignatureCeremonyResource (+ dto)
  persistence/     entities, Panache reactive repositories, mappers
  render/          HandlebarsTemplateRenderer, HttpPdfRenderAdapter, PdfBoxPadesSealAdapter
  client/          ScaChallengeClient, ScaVerificationAdapter (sca-service integration, ADR-0021/0162 D4)
  kafka/ outbox/   KafkaDocumentOutboxEventPublisher, DocumentOutboxDispatcher, DocumentOutboxBacklogGauge
  authz/           AuthzProducer (OPA PDP)
```

The domain layer imports only the JDK (`java.time`, `java.util`, `java.security.MessageDigest`) — no
Quarkus, Jakarta or Panache. Framework wiring lives entirely in `infrastructure/`.

## Real adapters behind stable ports

Every port has a real, non-placeholder production adapter — nothing here is a no-op:

| Port | Adapter | Notes |
|---|---|---|
| `TemplateRenderPort` | `HandlebarsTemplateRenderer` — Handlebars.java, no custom helpers, default HTML escaping | logic-less by contract (ADR-0162 D2): no arbitrary code execution |
| `PdfRenderPort` | `HttpPdfRenderAdapter` — HTTP call to `openbank-document-renderer` (WeasyPrint, default) or Gotenberg (opt-in, `openbank.render.profile`) | bounded connect/request timeouts (ADR-0162 D3) |
| `ObjectStorePort` | Shared `openbank-libs-runtime` adapter (ADR-0161): `PostgresBlobStore` (dev/default) or `S3ObjectStore` (production, Object Lock WORM) | selected by `openbank.objectstore.backend` |
| `SignatureSealPort` | `PdfBoxPadesSealAdapter` — real PAdES-B seal (Apache PDFBox + BouncyCastle CMS), ephemeral self-signed cert in dev (loud `WARN`), a configured PKCS12 keystore in production | phase-2 = EU DSS PAdES-LTA with a QSeal/HSM key (ADR-0007/0162 D4) |

## Render flow

1. `DocumentRenderService.render` looks up the **published** template (`findPublished`).
2. `TemplateRenderPort.renderHtml` merges the data map into the body (HTML-escaped).
3. `PdfRenderPort.htmlToPdf` produces the bytes; `ObjectStorePort.put` stores them under `documents/<id>`.
4. The `Document` (content-addressed by `Document.sha256`) is persisted **together with** a
   `DocumentGenerated` outbox row in one transaction (transactional outbox, ADR-0050).
5. `DocumentOutboxDispatcher` relays outbox rows to Kafka on a timer, with a circuit-breaker / retry /
   bulkhead / timeout resilience stack. `DocumentOutboxBacklogGauge` publishes the backlog metric
   (`openbank.outbox.backlog`, tag `service="document"`).

## Signature flow

`SignatureCeremonyService.openCeremony` builds an ordered signer list and opens the ceremony
(`DRAFT → PENDING`). A `SIGNED` decision must first pass SCA verification
(`ScaVerificationAdapter` → `openbank-sca-service`: challenge must be `COMPLETED` and owned by the
signer, then spent via `consume` so the same evidence can't be replayed) and must come from the
next signer in order (out-of-order decisions are rejected). `recordDecision` applies the decision;
when all signers have signed, the ceremony reaches `COMPLETED` **only if** sealing the stored bytes
via `PdfBoxPadesSealAdapter` succeeds first — a sealing failure fails the whole call, so an unsealed
document can never be persisted as `COMPLETED`. Only then is the `SignatureCeremonyCompleted` outbox
event emitted. `SignatureCeremonyEntity` carries a `@Version` column (optimistic locking) so two
concurrent decisions on the same ceremony conflict loudly (422) instead of lost-updating each other.

## Persistence

Dedicated database `openbank_documents`, `generation: none`, Flyway V1..V3. Only the outbox entity
extends `PanacheOutboxEntity` (Hibernate sequence `document_outbox_seq`, created in V3 — guarded by
`HibernateSequenceGuardTest`); all other entities use application-assigned UUID/String ids.
