# openbank-sepa-instant — Dokumentace

> **Co to je:** výkonný engine pro **SEPA okamžitou úhradu (SCT Inst)** — zúčtování do 10 s se synchronní sankční prověrkou a podporou recallu (vrácení). **Co to NENÍ:** podvojná účetní kniha (`openbank-ledger-service`), úložiště transakcí (`openbank-transaction-service`), engine zůstatků (`openbank-balance-service`) ani běžná (neokamžitá) SEPA linka (`openbank-sepa-payment-service`).

Tato dokumentace je publikována přímo službou na management endpointu `/q/openbank/docs` (vzor Docs-as-Service — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji načítá při vykreslování stránky Service Docs.

## Obsah

| Sekce | Publikum | Co tam najdete |
|---|---|---|
| [01 — Přehled](./01-overview.md) | Produkt, audit, management | Co služba dělá, kdo ji volá, kde sídlí v doméně |
| [02 — Architektura](./02-architecture.md) | Inženýři, tech leadi | C4 diagramy, hexagonální vrstvy, sankční brána, přímá publikace událostí |
| [03 — API](./03-api.md) | Vývojáři služeb, integrátoři | REST kontrakt, idempotence, model chyb |
| [04 — Data](./04-data.md) | Data, analytika, DBA | Schéma, migrace, retence, PII pole |
| [05 — Provoz](./05-operations.md) | DevOps, SRE, release inženýři | Build, deploy, runbooky, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Mapování DORA, GDPR, PSD2, AML, sankce |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.x / Hibernate Reactive (Panache) + reaktivní PostgreSQL / SmallRye Reactive Messaging (Kafka) — sestaveno přes konvenční plugin `openbank.quarkus-service`
- **Port:** 8127 (app), 8085 (management — `/q`)
- **Persistence:** dedikovaná databáze `openbank_sepa_instant`, deklarované schéma `sepa_instant_schema`, Flyway migrace V1..V4
- **Události:** přímá, synchronní publikace ze `SctInstPaymentService` při každém přechodu stavu přes `KafkaSctInstEventPublisher` → Kafka topic `openbank.sepa.instant.events` (nejde o transakční outbox — dřívější outbox pipeline byla postavena, ale nikdy napojena na žádné reálné volání, a byla odstraněna, issue #1034)
- **Idempotence:** hlavička `Idempotency-Key` (fallback na pole v těle) → unique constraint na `sct_inst_payments.idempotency_key`
- **Auth:** Keycloak OIDC (klient `openbank-services`); OPA autorizace (ADR-0034) ve výchozím stavu advisory; `@Authorize` na recallu
- **Money-path:** ANO — uvedeno v `rules.yaml: money_path_services`; ADR-0057 tier **T0 (always-on)**; threat model v `docs/threat-models/openbank-sepa-instant.md`
- **Sankční brána:** synchronní sankční prověrka jmen plátce + příjemce při submitu (ADR-0032), fail-closed
