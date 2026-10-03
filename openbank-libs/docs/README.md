# openbank-libs — Documentation

> **Co to je:** rodina sdílených knihoven OpenBank. `openbank-libs-domain` obsahuje doménové primitivy bez frameworku, `openbank-libs-runtime` adaptéry Quarkus a endpoint pro vlastní dokumentaci. `openbank-libs` je kompatibilitní zastřešení obou modulů. Nejde o běžící službu.

Tato složka se kopíruje do image Admin UI jako snímek. Běžící služby publikují dokumentaci ze svého buildu na `/q/openbank/docs`; ručně psané kapitoly patří do `src/main/resources/docs/` jednotlivých služeb. Verze a commit běžící služby jsou v jejím indexu dokumentace. Počet služeb se odvozuje z katalogu buildu, nikoli z tohoto textu.

## Obsah

| Sekce | Pro koho | Co tam najdeš |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | Proč sdílené knihovny existují a jejich schopnosti |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagramy, mapa balíčků, Jandex discovery, dependency strategie |
| [03 — API & contracts](./03-api.md) | Service developers | Per-package konzumpční vzory s code snippets (Money, Iban, BuildInfo, IdempotencyStore, …) |
| [04 — Data](./04-data.md) | Data, analytics | (libs nedrží data — odkaz na per-service docs) |
| [05 — Operations](./05-operations.md) | DevOps, release engineers | Build, test, release, JDK/Kotlin/Quarkus compatibility matrix |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Mapping na DORA, GDPR, PSD2, NIS2 (per komponenta) |

## Mapa modulů

| Modul | Odpovědnost |
|---|---|
| `openbank-libs-domain` | Sdílené doménové hodnoty a porty bez importů Quarkus nebo CDI |
| `openbank-libs-runtime` | Adaptéry Quarkus, webové resource, observabilita a `/q/openbank/docs` |
| `openbank-libs` | Kompatibilitní zastřešení exportující domain a runtime |

U konkrétní služby ukazují běžící `/q/openbank/docs` a `/api/v1/info` skutečně nasazený build. Pro aktuální balíčky a API knihoven jsou autoritativní zdrojové kódy obou modulů výše.

## Související dokumenty

- [ADR 0013 — shared outbox in libs](../../docs/adr/0013-shared-outbox-in-openbank-libs.md)
- [ADR 0014 — libs centralization roadmap](../../docs/adr/0014-openbank-libs-centralization-roadmap.md)
- [ADR 0015 — Panache migration plan](../../docs/adr/0015-panache-with-annotations-migration.md) (Status: reverted, see file)
- [ADR 0016 — Virtual Threads not adopted yet](../../docs/adr/0016-virtual-threads-not-adopted-yet.md)
- [ADR 0017 — Vault for secrets (Op-ex 1)](../../docs/adr/0017-secrets-via-vault.md)
- [ADR 0018 — OPA for fine-grained authz (Op-ex 4)](../../docs/adr/0018-opa-for-fine-grained-authz.md)
