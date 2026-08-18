# openbank-audit-service — Documentation

> **Co to je:** platformový **neměnný auditní záznam (audit trail)** — konzumuje doménové události z celého fleetu (account, transaction, balance, party, kyc, consent) a ukládá je jako append-only, proti úpravám odolnou historii událostí dotazovatelnou per agregát. **Co to NENÍ:** business služba vlastnící nějaký vlastní agregát, ani autorizační/SIEM engine — zaznamenává, co se stalo, nerozhoduje o politice.

Tato dokumentace je vystavena přímo službou na management endpointu `/q/openbank/docs` (Docs-as-Service pattern — viz [ADR 0019](../../../../docs/adr/0019-docs-as-service.md)). Admin UI ji fetchuje při zobrazení Service Docs.

## Obsah

| Sekce | Pro koho | Co tam najdeš |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | Co služba dělá, kdo ji plní, kde sedí v doméně |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagramy, hexagonální vrstvy, consume flow |
| [03 — API](./03-api.md) | Service developers, integrátoři | REST kontrakt, error model, role gating |
| [04 — Data](./04-data.md) | Data, analytics, DBA | Schema, migrace, neměnnost, retence, PII fields |
| [05 — Operations](./05-operations.md) | DevOps, SRE, release engineers | Build, deploy, runbooks, SLO |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | DORA, GDPR, EBA ICT, NIS2 mapping |

## TL;DR

- **Tech stack:** Kotlin / Quarkus 3 (LTS) / JDK / PostgreSQL / Hibernate Reactive (Panache) / SmallRye Reactive Messaging (Kafka)
- **Port:** 8113 (app), 8085 (management, root-path `/q`)
- **Persistence:** PostgreSQL databáze `openbank_audit`, tabulka `audit_entries` (append-only, schema `public`), Flyway migrace V1..V16
- **Ingest:** Kafka consumer `audit-events-in` nad topiky `openbank.accounts.account.created`, `openbank.transactions.transaction.initiated`, `openbank.balance.events`, `openbank.party.events`, `openbank.kyc.events`, `openbank.consent.events`
- **Outbox:** žádný — žádná vlastní doménová událost k publikaci; mrtvá aparatura re-emitu `audit_outbox` byla odstraněna (#5126)
- **Idempotence:** žádný `Idempotency-Key` header — zápisová cesta je event-driven, každý záznam nese unikátní `entry_id` UUID
- **Auth:** Keycloak OIDC; read API omezené na role `ROLE_AUDITOR`, `ROLE_ADMIN`, `ROLE_COMPLIANCE`
- **Money-path:** Ne (není v `rules.yaml: money_path_services`)
