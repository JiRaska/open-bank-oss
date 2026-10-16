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

## Kontraktní testy vstupů pro generování dokumentů

Tři Pact consumer testy fixují data čtená pro právní dokumenty: název/adresu party (200 a 404 pro neznámou party), seznam CURRENT účtů dané party (200 a 401 bez identity) a identitu/název produktu, poplatky i přítomnost pole podmínek (200 a 404 pro neznámý produkt). Deserializují mock odpovědi pomocí produkčních DTO klientů a porovnávají doslovné cesty poskytovatele s anotacemi klientů. Formátování adresy test napodobuje; neprovádí celé generování dokumentu ani produkční volání REST klienta.

Testovací závislost `pact.consumer` používá stávající verzi katalogu. Samostatný provider state party dodává syntetickou úplnou adresu a nemění fixture VoP bez adresy; účty a produkty používají existující sdílené provider states. Musí se provést všech šest consumer testů a samostatný folder-provider replay ověřit všech šest interakcí. Samotná mock odpověď 401 nedokazuje vynucování autentizace. Tyto offline kontrakty neověřují Pact Broker, živé JWT, nasazenou interoperabilitu, výstup PDF/podpisu ani produkční zákaznická data. Další odchozí klienti zůstávají mimo tyto kontrakty; přeskočené testy nejsou úspěšně ověřené kontrakty.


Čtvrtý consumer kontrakt pokrývá `POST /api/v1/sca/challenges/{id}/consume`: shodný hash dokumentu a ceremonie vrací 200, jiný hash 409, neznámá výzva 404 a chybějící identita 401. Skutečný `ScaVerificationAdapter` volá anonymního klienta připojeného k Pact mock serveru a ověřuje úspěšnou či zamítnutou verifikaci. Folder i broker provider test sdílejí stejný syntetický stav COMPLETED, dosud nespotřebované výzvy DOCUMENT_SIGNING; samostatná neautentizovaná provider třída přehrává interakci 401. Fixture před každou interakcí obnovuje stav výzvy, takže samostatně nedokazuje, že odmítnuté spotřebování ponechalo uložený stav nespotřebovaný. Testy neověřují podpis zařízení, produkční OIDC filtr klienta, platné podpisy JWT, vynucované OPA ani zamítnutí přístupu k cizí party. Všechny čtyři SCA interakce vyžadují doložený počet skutečně provedených testů; chybějící Pact či přeskočený broker test nejsou důkazem kompatibility.
