# openbank-libs — Documentation

> **What it is:** a shared infrastructure library for all 27 OpenBank Quarkus microservices. **What it is NOT:** a generic Kotlin utility library, nor a Quarkus extension.

This directory is the entry point for per-service documentation, following the **arc42-lite + C4 + Backstage TechDocs file layout** standard. Every service is expected to mirror the same structure at `openbank-<service>/docs/`.

## Contents

| Section | Audience | What you'll find |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | Why libs exists, the value it brings to a fleet of 27 services, key capabilities |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagrams, package map, Jandex discovery, dependency strategy |
| [03 — API & contracts](./03-api.md) | Service developers | Per-package consumption patterns with code snippets (Money, Iban, BuildInfo, IdempotencyStore, …) |
| [04 — Data](./04-data.md) | Data, analytics | (libs holds no data — pointer to per-service docs) |
| [05 — Operations](./05-operations.md) | DevOps, release engineers | Build, test, release, JDK/Kotlin/Quarkus compatibility matrix |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Mapping to DORA, GDPR, PSD2, NIS2 (per component) |

## Package map in one sentence

```
com.openbank.libs/
├── api/               REST-side primitives — error model (ApiError), pagination (CursorPage), common exception mappers
├── audit/             AuditEvent envelope + AuditEventPublisher port (GDPR Art. 30, DORA Art. 17)
├── domain/
│   ├── money/         Money + CurrencyCode value objects (ISO 4217)
│   ├── account/       Iban + Bic value objects (ISO 13616, 9362)
│   ├── case/          Case state machine primitives (KYC, AML, dispute workflows)
│   ├── event/         DomainEvent envelope base
│   └── identifiers/   Typesafe ID value objects (AccountId, TransactionId, …) + JPA converters
├── idempotency/       IdempotencyStore port + Redis-backed implementation
├── persistence/
│   └── outbox/        Generic transactional outbox primitives (entity, dispatcher, ports)
├── security/          PiiMask, Roles, SecurityContext extensions, BearerTokenClientHeadersFactory
├── util/              BuildInfo (runtime tech-stack snapshot via Gradle stamping)
└── web/               JAX-RS filters: CorrelationIdFilter, RateLimitFilter, ApiVersionResponseFilter, ServiceInfoResource, ServiceConfigResource
```

`security/` does **not** contain a `BootstrapVerifier` — this line used to list one, and that was wrong.
ADR-0017 prescribes a startup fail-fast guard against dev-placeholder secrets, but it was never written
(`git grep BootstrapVerifier -- '*.kt'` returns 0), and that ADR's own delivery note says so. Dev
placeholders are kept out of prod by ESO/OpenBao `secretKeyRef` secret injection (ADR-0007), not by
anything in this library (#8426).

## Related documents

- [ADR 0013 — shared outbox in libs](../../docs/adr/0013-shared-outbox-in-openbank-libs.md)
- [ADR 0014 — libs centralization roadmap](../../docs/adr/0014-openbank-libs-centralization-roadmap.md)
- [ADR 0015 — Panache migration plan](../../docs/adr/0015-panache-with-annotations-migration.md) (Status: reverted, see file)
- [ADR 0016 — Virtual Threads not adopted yet](../../docs/adr/0016-virtual-threads-not-adopted-yet.md)
- [ADR 0017 — Vault for secrets (Op-ex 1)](../../docs/adr/0017-secrets-via-vault.md)
- [ADR 0018 — OPA for fine-grained authz (Op-ex 4)](../../docs/adr/0018-opa-for-fine-grained-authz.md)
