# 2. Hexagonal architecture per service

Date: 2026-05-26
Status: Accepted

## Context

Every OpenBank service handles money, customer data, or regulatory state. Coupling business logic to framework code (Quarkus, Spring, JPA) historically leads to:

- Inability to test domain logic without spinning a container.
- Domain rules leaking into REST controllers and JPA entities.
- Difficulty replacing infrastructure (changing DB, message broker) without rewriting business logic.
- Auditors unable to find "where the business rule lives" because it is scattered.

We need a structure that puts the **domain at the centre**, makes infrastructure replaceable, and is recognisable to anyone who has read Vernon, Evans, or Cockburn.

## Decision

Every OpenBank JVM service follows hexagonal architecture (ports and adapters), with this physical layout:

```
src/main/kotlin/<base-package>/
  domain/
    model/        — Entities, aggregates, value objects (pure Kotlin)
    event/        — Domain events
    service/      — Domain services (pure Kotlin)
  application/
    port/in/      — Use case interfaces (commands, queries)
    port/out/     — Repository + event publisher + external client interfaces
    usecase/      — Use case implementations (orchestration)
  infrastructure/
    persistence/  — JPA entities, Panache / Hibernate repositories
    messaging/    — Kafka producers, consumers
    rest/         — REST resources, DTOs, exception mappers
    client/       — Outbound HTTP clients
    config/       — Quarkus configuration
```

Rules:

- `domain/` MUST NOT depend on Quarkus, JPA, Jackson, or any framework annotation.
- `application/` MUST NOT depend on infrastructure; infrastructure depends on application via ports.
- `infrastructure/` is the only layer that may import Quarkus, Hibernate, Kafka, REST framework annotations.
- DTOs at the REST boundary are distinct types from domain entities; explicit mapping required.

CODEOWNERS for `domain/` and `application/` requires architect review; pure refactors in `infrastructure/` may merge with a single reviewer.

## Consequences

**Positive**
- Domain logic is unit-testable in milliseconds without containers.
- Infrastructure swappable: changing persistence is a localised change.
- Auditors find business rules in `domain/` consistently across all 26 services.
- New contributors onboard faster — same layout everywhere.

**Negative**
- More files per change; mappers feel like boilerplate.
- Risk of "anaemic domain" if logic drifts into use cases instead of entities.

**Mitigation**
- Use Kotlin's data classes + extension functions to minimise mapper boilerplate.
- Code review enforces: business rules live in `domain/`, orchestration lives in `application/`.

## References

- Alistair Cockburn, "Hexagonal Architecture" (2005)
- Vaughn Vernon, "Implementing Domain-Driven Design" (2013)
- Existing OpenBank services already use this layout (52 domain dirs, 50 application dirs, 50 infrastructure dirs across 26 services).
