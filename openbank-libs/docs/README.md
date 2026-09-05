# openbank-libs — Documentation

> **Co to je:** sdílená infrastrukturní knihovna pro všech 27 Quarkus mikroslužeb OpenBank. **Co to NENÍ:** generická Kotlin utility knihovna ani Quarkus extension.

Tato složka je entry point per-service dokumentace dle standardu **arc42-lite + C4 + Backstage TechDocs file layout**. Stejnou strukturu má mít každá služba v `openbank-<service>/docs/`.

## Obsah

| Sekce | Pro koho | Co tam najdeš |
|---|---|---|
| [01 — Overview](./01-overview.md) | Product, audit, management | Proč libs existuje, jakou hodnotu přináší fleeru 27 služeb, klíčové schopnosti |
| [02 — Architecture](./02-architecture.md) | Engineering, tech leads | C4 diagramy, mapa balíčků, Jandex discovery, dependency strategie |
| [03 — API & contracts](./03-api.md) | Service developers | Per-package konzumpční vzory s code snippets (Money, Iban, BuildInfo, IdempotencyStore, …) |
| [04 — Data](./04-data.md) | Data, analytics | (libs nedrží data — odkaz na per-service docs) |
| [05 — Operations](./05-operations.md) | DevOps, release engineers | Build, test, release, JDK/Kotlin/Quarkus compatibility matrix |
| [06 — Compliance](./06-compliance.md) | Compliance, audit, GRC | Mapping na DORA, GDPR, PSD2, NIS2 (per komponenta) |

## Mapa balíčků v jedné větě

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

`security/` **neobsahuje** `BootstrapVerifier` — tento řádek ho dříve uváděl a byl to omyl. ADR-0017
předepisuje startup fail-fast guard proti dev-placeholder secrets, ten ale nebyl nikdy napsán
(`git grep BootstrapVerifier -- '*.kt'` vrací 0) a delivery note téže ADR to uvádí. Dev placeholdery
drží mimo prod injektáž secrets přes ESO/OpenBao `secretKeyRef` (ADR-0007), ne cokoli v této knihovně (#8426).

## Související dokumenty

- [ADR 0013 — shared outbox in libs](../../docs/adr/0013-shared-outbox-in-openbank-libs.md)
- [ADR 0014 — libs centralization roadmap](../../docs/adr/0014-openbank-libs-centralization-roadmap.md)
- [ADR 0015 — Panache migration plan](../../docs/adr/0015-panache-with-annotations-migration.md) (Status: reverted, see file)
- [ADR 0016 — Virtual Threads not adopted yet](../../docs/adr/0016-virtual-threads-not-adopted-yet.md)
- [ADR 0017 — Vault for secrets (Op-ex 1)](../../docs/adr/0017-secrets-via-vault.md)
- [ADR 0018 — OPA for fine-grained authz (Op-ex 4)](../../docs/adr/0018-opa-for-fine-grained-authz.md)
