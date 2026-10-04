# openbank-libs — Documentation

> **What it is:** the shared OpenBank library family. `openbank-libs-domain` holds framework-free domain primitives; `openbank-libs-runtime` holds Quarkus adapters and the self-published documentation endpoint. `openbank-libs` is a compatibility umbrella that re-exports both. It is not a running service.

This directory is bundled into the Admin UI image as a snapshot. Runnable services publish documentation from their own build at `/q/openbank/docs`; authored chapters live in each service's `src/main/resources/docs/`. See the running service's version and commit in its documentation index. The service count is derived from the build catalog, not maintained here.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | Why shared libraries exist and their capabilities |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, package map, Jandex discovery, dependency strategy |
| [03 — API & contracts](./03-api.md) | Service developers | Per-package consumption patterns with code snippets (Money, Iban, BuildInfo, IdempotencyStore, …) |
| [04 — Data](./04-data.md) | Data, analytics | (libs holds no data — pointer to per-service docs) |
| [05 — Operations](./05-operations.md) | DevOps, release engineers | Build, test, release, JDK/Kotlin/Quarkus compatibility matrix |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Mapping to DORA, GDPR, PSD2, NIS2 (per component) |

## Module map

| Module | Responsibility |
|---|---|
| `openbank-libs-domain` | Shared domain values and ports without Quarkus or CDI imports |
| `openbank-libs-runtime` | Quarkus adapters, web resources, observability and `/q/openbank/docs` |
| `openbank-libs` | Compatibility umbrella re-exporting domain and runtime |

For a concrete service, the running `/q/openbank/docs` and `/api/v1/info` report the build that is actually deployed. The source code in the two modules above is authoritative for their current packages and APIs.

## Related documents

- [ADR 0013 — shared outbox in libs](../../docs/adr/0013-shared-outbox-in-openbank-libs.md)
- [ADR 0014 — libs centralization roadmap](../../docs/adr/0014-openbank-libs-centralization-roadmap.md)
- [ADR 0015 — Panache migration plan](../../docs/adr/0015-panache-with-annotations-migration.md) (Status: reverted, see file)
- [ADR 0016 — Virtual Threads not adopted yet](../../docs/adr/0016-virtual-threads-not-adopted-yet.md)
- [ADR 0017 — Vault for secrets (Op-ex 1)](../../docs/adr/0017-secrets-via-vault.md)
- [ADR 0018 — OPA for fine-grained authz (Op-ex 4)](../../docs/adr/0018-opa-for-fine-grained-authz.md)
