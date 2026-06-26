# openbank-sanctions-service — Dokumentace

> **Co to je:** služba pro real-time prověřování sankcí v platformě OpenBank. Prověřuje strany (fyzické osoby, organizace, plavidla, letadla) oproti listinám OFAC SDN, EU Consolidated, UN Consolidated, HM Treasury, FATF High-Risk a CNB Domestic před každou platební nebo účetní operací. **Co to NENÍ:** AML monitoring transakcí (`openbank-aml-service`), KYC verifikace identity (`openbank-kyc-service`), ani engine pro detekci podvodů.

Tato dokumentace je vystavena přímo službou na management endpointu `/q/openbank/docs` (Docs-as-Service pattern — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji fetchuje při zobrazení Service Docs.

## Obsah

| Sekce | Pro koho | Co tam najdeš |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | Co služba dělá, kdo ji volá, kde sedí v doméně |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagramy, hexagonální vrstvy, outbox flow |
| [03 — API](./03-api.md) | Service developers, integrátoři | REST kontrakt, idempotence, error model |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrace, retence, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, AML/CFT, PSD2 mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3.33.2 LTS / JDK 25 / PostgreSQL 16 / Hibernate (JPA/Panache)
- **Port:** 8123
- **Persistence:** vlastní DB schema `openbank_sanctions`, Flyway migrace V1..V4
- **Outbox:** `sanctions_outbox` → Kafka topic `openbank.sanctions.screening.event`
- **Idempotence:** pole `idempotencyKey` v těle požadavku → Redis (deduplikace)
- **Auth:** Keycloak OIDC, role `ROLE_OPERATOR` pro mutace
- **Listiny:** OFAC_SDN, EU_CONSOLIDATED, UN_CONSOLIDATED, HM_TREASURY, FATF_HIGH_RISK, CNB_DOMESTIC
