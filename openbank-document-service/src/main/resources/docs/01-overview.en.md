# Overview

## What the service does

`openbank-document-service` is the **Document Management** bounded context. It holds:

- **DocumentTemplate aggregate** — a versioned, engine-specific template body (`code`, `version`,
  `engine`, `bodyHtml`, `locale`, `classification`, optional `productRef`) with a publication lifecycle
  (`DRAFT → PUBLISHED → RETIRED`). Only a `PUBLISHED` template may render live documents.
- **Document aggregate** — a rendered, content-addressed artifact: `sha256`, `storageKey`, `contentType`,
  `sizeBytes`, `metadata`, retention (`retainUntil`), and a lifecycle
  (`GENERATED → PENDING_SIGNATURE → SIGNED → ARCHIVED`). The bytes live in the object store.
- **SignatureCeremony aggregate** — orchestrates e-signature over a document: an ordered list of signers,
  a ceremony status (`DRAFT → PENDING → PARTIALLY_SIGNED → COMPLETED / DECLINED / EXPIRED`) and a
  `signatureLevel` (`ADVANCED` default; `QUALIFIED` is phase-2).
- **Outbox** — a transactional outbox row per material state change, dispatched to Kafka.

## What the service **does NOT** do

- ❌ Not on the money path — it never blocks or gates a fund release; it emits events.
- ❌ Does not move money, keep balances, or post to the ledger.
- ❌ Does not (yet) render real PDFs or apply real cryptographic signatures — those are placeholder
  adapters behind ports (ADR-0161/0162); see [02 — Architecture](./02-architecture.md).

## Position in the domain

```
   ┌────────────┐  POST /render / /templates  ┌────────────────────────┐
   │  operator  │ ─────────────────────────►  │   document-service     │
   │ / service  │                             └───────────┬────────────┘
   └────────────┘                                         │ outbox → Kafka
                                                          ▼
                        ┌──────────────────────────────────────────────┐
                        │ Kafka: openbank.documents.document.event       │
                        └───────────────┬────────────────────────────────┘
                                        ▼
                   ┌──────────────────┐   ┌───────────────┐   ┌───────────────┐
                   │ lending-service  │   │ account-svc   │   │ audit-service │
                   │ (loan agreements)│   │ (statements)  │   │  (audit trail)│
                   └──────────────────┘   └───────────────┘   └───────────────┘
```

## Key use cases

| Use case | API | Event |
|---|---|---|
| Create a template (DRAFT) | `POST /api/v1/documents/templates` | — |
| Publish / retire a template | `POST /templates/{id}/publish` · `/retire` | `DocumentTemplatePublished` (planned) |
| Render a document | `POST /api/v1/documents/render` | `DocumentGenerated` |
| Get document metadata / content | `GET /documents/{id}` · `/{id}/content` | — |
| Open a signature ceremony | `POST /api/v1/signature-ceremonies` | — |
| Record a signer decision | `POST /signature-ceremonies/{id}/decisions` | `SignatureCeremonyCompleted` |

## Callers & consumers

- **Operators / in-cluster services** (OIDC token) — author templates, render documents, run ceremonies.
- **lending-service, account-service** — downstream consumers of rendered documents (governance lineage
  `downstream → lending-service, account-service`).
- **audit-service** — consumes events for the audit trail.

## Dependencies

- **PostgreSQL** (database `openbank_documents`) — templates, documents, blobs, ceremonies, outbox.
- **Kafka** (topic `openbank.documents.document.event`).
- **Keycloak** — OIDC authentication; **OPA sidecar** — authorization (`@Authorize`, ADR-0034).
- **openbank-libs** — authz, outbox plumbing, `ServiceInfoResource` (`/api/v1/info`), docs.
