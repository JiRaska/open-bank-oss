# Architektura

Služba dodržuje hexagonální (ports & adapters) uspořádání OpenBank (ADR-0002): bezframeworkovou **doménu**, **aplikační** vrstvu use-case portů a **infrastrukturní** adaptéry pro REST, perzistenci, Kafku a autorizaci.

## C4 — pohled na kontejner

```
        ┌──────────────────────────────────────────────────────┐
        │                  dispute-service                      │
        │                                                       │
   REST │  ┌───────────────┐   ┌───────────────────────────┐   │
  ─────►│  │ DisputeResource│──►│ DisputeService (use cases)│   │
 (8135) │  │  (adapter in) │   │  Open/Update/Get          │   │
        │  └───────────────┘   └────────────┬──────────────┘   │
        │                                   │ porty out         │
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
        │  │ @Scheduled každých 5s  │   │ EventPublisher        │ │
        │  └───────────────────────┘   └──────────┬───────────┘ │
        └─────────────────────────────────────────┼─────────────┘
                                                   ▼
                              Kafka topic openbank.disputes.dispute.event
```

## Hexagonální vrstvy

### Doména (`domain/model`)
Čistý Kotlin, nulové framework importy:
- Data třídy `Dispute`, `DisputeEvidence`, `DisputeTimelineEvent`.
- Enumy `DisputeType`, `DisputeStatus`, `DisputeResolution`.
- Request DTO `OpenDisputeRequest`, `UpdateDisputeRequest`.

### Aplikace (`application`)
- **Vstupní porty** (`port.in`): `OpenDisputeUseCase`, `UpdateDisputeUseCase`, `GetDisputeUseCase`.
- **Výstupní porty** (`port.out`): `DisputeRepository`, `DisputeEvidenceRepository`, `DisputeTimelineRepository`, `DisputeOutboxRepository`, `DisputeOutboxEventPublisher`.
- **Implementace use-case**: `DisputeService` implementuje všechny tři vstupní porty. Generuje referenci `DSP-…`, počítá `resolutionDeadline` z `resolution-sla-days`, zapisuje agregát přes repository a při každé mutaci připojuje `DisputeTimelineEvent`. `withdraw`/`escalate` delegují na `update` s odpovídajícím stavem.

### Infrastruktura (`infrastructure`)
- **`rest/DisputeResource`** — JAX-RS reaktivní (`Uni`) adaptér vystavující `/api/v1/disputes`. `@RolesAllowed` na úrovni třídy i metody; `@Authorize(action = "dispute.update")` na `PUT` (OPA poradní).
- **`persistence`** — Panache entity (`DisputeEntity`, `DisputeEvidenceEntity`, `DisputeTimelineEntity`, `DisputeOutboxEntity`), mappery a implementace repository nad Hibernate Reactive.
- **`kafka/KafkaDisputeOutboxEventPublisher`** — SmallRye Reactive Messaging emitter na kanálu `dispute-events-out`, klíčováno náhodným UUID.
- **`outbox/DisputeOutboxDispatcher`** — `@Scheduled(every = "5s", delayed = "5s")` poller, který čte zpracovatelné outbox řádky v dávkách po 25, publikuje s odolností a označí každý řádek sent/failed.
- **`authz/AuthzProducer`** — zapojuje libs OPA/authz klienta (ADR-0034).

## Tok Outbox → Kafka

Vzor transakční outbox odděluje DB zápis od publikace do Kafky:

1. Mutace perzistuje doménové řádky (a má za úkol zařadit řádek do `dispute_outbox`).
2. `DisputeOutboxDispatcher` pollu­je každých 5s, `listProcessable(25)`.
3. Každý payload je publikován přes `publishWithResilience` — chráněno `@Bulkhead(1)`, `@CircuitBreaker(volume=10, ratio=0.5, delay=5s)`, `@Retry(maxRetries=2)`, `@Timeout(3000)`.
4. Při úspěchu → `markSent(eventId)`; při selhání → `markFailed(eventId, error)` (řádek nese `attempt_count`, `last_error`).
5. Konzumenti (`audit-service`, notification) čtou `openbank.disputes.dispute.event`.

> **Poznámka (současný stav):** outbox tabulka, dispatcher a publisher jsou zapojeny, ale `DisputeService` aktuálně při mutacích připojuje pouze timeline události — explicitní zařazení doménových událostí reklamace do `dispute_outbox` je last-mile mezera (TBD). Proud událostí berte jako zamýšlený kontrakt.

## Klíčové porty

| Port | Směr | Adaptér |
|---|---|---|
| `OpenDisputeUseCase` / `UpdateDisputeUseCase` / `GetDisputeUseCase` | in | `DisputeResource` |
| `DisputeRepository` / `…EvidenceRepository` / `…TimelineRepository` | out | Panache repository impl |
| `DisputeOutboxRepository` | out | `DisputeOutboxRepositoryImpl` |
| `DisputeOutboxEventPublisher` | out | `KafkaDisputeOutboxEventPublisher` |

## Průřezové aspekty

- **Reaktivně end-to-end**: JAX-RS `Uni` + Hibernate Reactive + Mutiny.
- **Observabilita**: metriky Micrometer/Prometheus, OpenTelemetry OTLP export (`service.name=openbank-dispute-service`), SmallRye Health.
- **Odolnost**: SmallRye Fault Tolerance na cestě publikace outboxu.
- **Bezpečnostní hlavičky**: CSP, HSTS, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff` nastaveny globálně v `application.yaml`.
