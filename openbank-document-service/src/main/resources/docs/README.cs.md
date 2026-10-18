# openbank-document-service — Dokumentace

> **Co to je:** ohraničený kontext Správy dokumentů (Document Management) — vlastní **šablony dokumentů**,
> **generuje** dokumenty ze šablony a datové mapy, **ukládá** je do objektového úložiště s bohatými
> metadaty o životním cyklu a retenci a orchestruje **ceremonie elektronického podpisu**. **Co to NENÍ:**
> NEleží na synchronní platební cestě (vydává události, nikdy neblokuje uvolnění prostředků), NEpřesouvá
> peníze a NENÍ účetní kniha ani zůstatkový modul.

Tato dokumentace je publikována přímo službou na management endpointu `/q/openbank/docs`
(vzor Docs-as-Service — [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)).

## Obsah

| Sekce | Publikum | Co najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sedí |
| [02 — Architektura](./02-architecture.md) | Inženýři, tech leads | Hexagonální vrstvy, porty/adaptéry, tok generování a podpisu |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | eIDAS, GDPR, DORA, retence (10 let) |

## TL;DR

- **Technologie:** Kotlin / Quarkus / JDK 25 / PostgreSQL 16 / Hibernate Reactive (Panache).
- **Port:** 8143 (aplikace), 8088 (management, root-path `/q`).
- **Perzistence:** vlastní databáze `openbank_documents`, Flyway migrace V1..V3, governance schéma
  `documents_schema`.
- **Outbox:** `document_outbox` → Kafka topic `openbank.documents.document.event`.
- **Klasifikace dat:** `restricted`; retence **10 let**.
- **Platební cesta:** **Ne** — vydává události, není synchronní bránou uvolnění prostředků. JE to změna
  hranice důvěry ⇒ vyžaduje threat model (`docs/threat-models/document-service.md`).
- **Zástupné adaptéry (za porty):** renderování šablon (bezlogické `{{token}}`, ADR-0162), generování PDF
  (stub, ADR-0162), objektové úložiště (Postgres BYTEA; S3 + WORM je ADR-0161), PAdES pečetění (no-op;
  EU DSS je ADR-0007/0162).

## Sdílené závislosti sestavení

Soubor `build.gradle.kts` této služby neuvádí závislosti, které sdílí každá Quarkus služba: Quarkus BOM, quarkus-kotlin, RESTEasy Reactive s Jacksonem, SmallRye Health a OpenAPI, OIDC, registr Micrometer Prometheus a config-yaml a testovací knihovny quarkus-junit5, AssertJ a REST Assured. Deklaruje je konvenční plugin `openbank.quarkus-service` v `build-logic`. Výsledná classpath je beze změny. `build.gradle.kts` uvádí jen to, co služba přidává navíc.
