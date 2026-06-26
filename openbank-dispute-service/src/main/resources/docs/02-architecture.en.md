# Architecture

The service follows the OpenBank hexagonal (ports & adapters) layout (ADR-0002): a framework-free **domain**, an **application** layer of use-case ports, and **infrastructure** adapters for REST, persistence, Kafka and authorization.

## C4 — container view

```
        ┌──────────────────────────────────────────────────────┐
        │                  dispute-service                      │
        │                                                       │
   REST │  ┌───────────────┐   ┌───────────────────────────┐   │
  ─────►│  │ DisputeResource│──►│ DisputeService (use cases)│   │
 (8135) │  │  (adapter in) │   │  Open/Update/Get          │   │
        │  └───────────────┘   └────────────┬──────────────┘   │
        │                                   │ ports out         │
        │     ┌─────────────────────────────┼────────────────┐ │
        │     │ DisputeRepository / Evidence / Timeline       │ │
        │     │ (Hibernate Reactive Panache)                  │ │
        │     └─────────────────────────────┬────────────────┘ │
        │                                   ▼                   │
        │                            PostgreSQL                 │
        │                          (openbank_dispute)           │
        │                                                       │
        │  ┌───────────────────────┐   ┌──────────────────────┐ │
        │  │ DisputeOutboxDispatcher│──►│ KafkaDisputeOutbox    │ │
        │  │ @Scheduled every 5s    │   │ EventPublisher        │ │
        │  └───────────────────────┘   └──────────┬───────────┘ │
        └─────────────────────────────────────────┼─────────────┘
                                                   ▼
                              Kafka topic openbank.disputes.dispute.event
```

## Hexagonal layers

### Domain (`domain/model`)
Pure Kotlin, zero framework imports:
- `Dispute`, `DisputeEvidence`, `DisputeTimelineEvent` data classes.
- Enums `DisputeType`, `DisputeStatus`, `DisputeResolution`.
- Request DTOs `OpenDisputeRequest`, `UpdateDisputeRequest`.

### Application (`application`)
- **Inbound ports** (`port.in`): `OpenDisputeUseCase`, `UpdateDisputeUseCase`, `GetDisputeUseCase`.
- **Outbound ports** (`port.out`): `DisputeRepository`, `DisputeEvidenceRepository`, `DisputeTimelineRepository`, `DisputeOutboxRepository`, `DisputeOutboxEventPublisher`.
- **Use-case implementation**: `DisputeService` implements all three inbound ports. It generates the `DSP-…` reference, computes `resolutionDeadline` from `resolution-sla-days`, writes the aggregate via the repository and appends a `DisputeTimelineEvent` on every mutation. `withdraw`/`escalate` delegate to `update` with the appropriate status.

### Infrastructure (`infrastructure`)
- **`rest/DisputeResource`** — JAX-RS reactive (`Uni`) adapter exposing `/api/v1/disputes`. `@RolesAllowed` at class and method level; `@Authorize(action = "dispute.update")` on `PUT` (OPA advisory).
- **`persistence`** — Panache entities (`DisputeEntity`, `DisputeEvidenceEntity`, `DisputeTimelineEntity`, `DisputeOutboxEntity`), mappers and repository impls over Hibernate Reactive.
- **`kafka/KafkaDisputeOutboxEventPublisher`** — SmallRye Reactive Messaging emitter on channel `dispute-events-out`, keyed by a random UUID.
- **`outbox/DisputeOutboxDispatcher`** — `@Scheduled(every = "5s", delayed = "5s")` poller that reads processable outbox rows in batches of 25, publishes with fault-tolerance and marks each row sent/failed.
- **`authz/AuthzProducer`** — wires the libs OPA/authz client (ADR-0034).

## Outbox → Kafka flow

The transactional-outbox pattern decouples the DB write from the Kafka publish:

1. A mutation persists domain rows (and is intended to enqueue a row into `dispute_outbox`).
2. `DisputeOutboxDispatcher` polls every 5s, `listProcessable(25)`.
3. Each payload is published through `publishWithResilience` — guarded by `@Bulkhead(1)`, `@CircuitBreaker(volume=10, ratio=0.5, delay=5s)`, `@Retry(maxRetries=2)`, `@Timeout(3000)`.
4. On success → `markSent(eventId)`; on failure → `markFailed(eventId, error)` (row carries `attempt_count`, `last_error`).
5. Consumers (`audit-service`, notification) read `openbank.disputes.dispute.event`.

> **Note (current state):** the outbox table, dispatcher and publisher are wired, but `DisputeService` currently only appends timeline events on mutations — explicit enqueue of dispute domain events into `dispute_outbox` is a last-mile gap (TBD). Treat the event stream as the intended contract.

## Key ports

| Port | Direction | Adapter |
|---|---|---|
| `OpenDisputeUseCase` / `UpdateDisputeUseCase` / `GetDisputeUseCase` | in | `DisputeResource` |
| `DisputeRepository` / `…EvidenceRepository` / `…TimelineRepository` | out | Panache repository impls |
| `DisputeOutboxRepository` | out | `DisputeOutboxRepositoryImpl` |
| `DisputeOutboxEventPublisher` | out | `KafkaDisputeOutboxEventPublisher` |

## Cross-cutting

- **Reactive end-to-end**: JAX-RS `Uni` + Hibernate Reactive + Mutiny.
- **Observability**: Micrometer/Prometheus metrics, OpenTelemetry OTLP export (`service.name=openbank-dispute-service`), SmallRye Health.
- **Resilience**: SmallRye Fault Tolerance on the outbox publish path.
- **Security headers**: CSP, HSTS, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff` set globally in `application.yaml`.
