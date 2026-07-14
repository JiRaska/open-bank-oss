# Přehled

## Co služba dělá

`openbank-document-service` je ohraničený kontext **Správy dokumentů**. Drží:

- **Agregát DocumentTemplate** — verzované tělo šablony vázané na engine (`code`, `version`, `engine`,
  `bodyHtml`, `locale`, `classification`, volitelný `productRef`) s publikačním životním cyklem
  (`DRAFT → PUBLISHED → RETIRED`). Živý dokument lze vygenerovat pouze z šablony ve stavu `PUBLISHED`.
- **Agregát Document** — vygenerovaný, obsahem adresovaný artefakt: `sha256`, `storageKey`, `contentType`,
  `sizeBytes`, `metadata`, retence (`retainUntil`) a životní cyklus
  (`GENERATED → PENDING_SIGNATURE → SIGNED → ARCHIVED`). Bajty leží v objektovém úložišti.
- **Agregát SignatureCeremony** — orchestruje elektronický podpis nad dokumentem: seřazený seznam
  podepisujících, stav ceremonie (`DRAFT → PENDING → PARTIALLY_SIGNED → COMPLETED / DECLINED / EXPIRED`)
  a `signatureLevel` (`ADVANCED` výchozí; `QUALIFIED` je fáze 2).
- **Outbox** — transakční outbox řádek pro každou podstatnou změnu stavu, odesílaný do Kafky.

## Co služba **NEdělá**

- ❌ Není na platební cestě — nikdy neblokuje ani nebrání uvolnění prostředků; vydává události.
- ❌ Nepřesouvá peníze, nedrží zůstatky, neúčtuje do knihy.
- ❌ (Zatím) negeneruje skutečná PDF ani neaplikuje skutečné kryptografické podpisy — jde o zástupné
  adaptéry za porty (ADR-0161/0162); viz [02 — Architektura](./02-architecture.md).

## Klíčové případy užití

| Případ užití | API | Událost |
|---|---|---|
| Vytvořit šablonu (DRAFT) | `POST /api/v1/documents/templates` | — |
| Publikovat / stáhnout šablonu | `POST /templates/{id}/publish` · `/retire` | `DocumentTemplatePublished` (plánováno) |
| Vygenerovat dokument | `POST /api/v1/documents/render` | `DocumentGenerated` |
| Metadata / obsah dokumentu | `GET /documents/{id}` · `/{id}/content` | — |
| Otevřít podpisovou ceremonii | `POST /api/v1/signature-ceremonies` | — |
| Zaznamenat rozhodnutí podepisujícího | `POST /signature-ceremonies/{id}/decisions` | `SignatureCeremonyCompleted` |

## Volající a konzumenti

- **Operátoři / služby uvnitř clusteru** (OIDC token) — autorské šablony, generování dokumentů, ceremonie.
- **lending-service, account-service** — navazující konzumenti vygenerovaných dokumentů (governance
  lineage `downstream → lending-service, account-service`).
- **audit-service** — konzumuje události pro auditní stopu.

## Závislosti

- **PostgreSQL** (databáze `openbank_documents`) — šablony, dokumenty, bloby, ceremonie, outbox.
- **Kafka** (topic `openbank.documents.document.event`).
- **Keycloak** — OIDC autentizace; **OPA sidecar** — autorizace (`@Authorize`, ADR-0034).
- **openbank-libs** — authz, outbox, `ServiceInfoResource` (`/api/v1/info`), dokumentace.
