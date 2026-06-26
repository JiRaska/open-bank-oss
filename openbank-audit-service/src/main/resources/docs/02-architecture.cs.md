# Architecture

Služba dodržuje hexagonální (ports & adapters) uspořádání předepsané [ADR-0002](../../../../docs/adr/0002-hexagonal-architecture.md): doména má nula framework importů; vše, co se dotýká Kafky, databáze nebo HTTP, žije v adaptérech.

## C4 — container view

```
        ┌───────────────────────── openbank-audit-service ──────────────────────────┐
        │                                                                            │
 Kafka  │  ┌─────────────────┐      ┌──────────────────┐      ┌────────────────────┐ │
 topics ─┼─►│ AuditConsumer   │────► │ AuditRepository  │────► │ PostgreSQL         │ │
 (in)   │  │ (@Incoming)     │ save │ (Panache)        │ JDBC │ openbank_audit     │ │
        │  └─────────────────┘      └──────────────────┘      │  • audit_entries   │ │
        │                                                       │  • audit_outbox    │ │
        │  ┌─────────────────┐      ┌──────────────────┐      └────────────────────┘ │
 Kafka  │  │ AuditOutbox     │◄──── │ AuditOutbox      │                              │
 (out)  │◄─┤ Dispatcher      │ drain│ Repository       │                              │
        │  │ (@Scheduled 5s) │      └──────────────────┘                              │
        │  └─────────────────┘                                                        │
        │                                                                            │
        │  ┌─────────────────┐                                                        │
 HTTP  ─┼─►│ AuditResource   │────► AuditRepository.findByAggregateId(...)            │
 (read) │  │ (JAX-RS, OIDC)  │                                                        │
        │  └─────────────────┘                                                        │
        └────────────────────────────────────────────────────────────────────────────┘
```

## Hexagonální vrstvy

### Domain (`com.openbank.audit.domain.model`)
- **`AuditEntry`** — jádrový agregát: neměnná Kotlin `data class` popisující jednu zaznamenanou událost (id, eventType, aggregateType, aggregateId, actor, payload, sourceService, correlationId, occurredAt, recordedAt). Žádné framework importy.

### Application (`com.openbank.audit.application`)
- **`AuditConsumer`** — vstupní use-case. `@Incoming("audit-events-in")` přijme raw JSON string, parsuje ho Jacksonem, mapuje na `AuditEntry` (odvozuje `aggregateType`/`aggregateId` z tvaru payloadu) a volá `AuditRepository.save`. Chyby jsou zalogovány a spolknuty, takže jedna poison message nikdy nezastaví consumer.
- **`application.port.out`** — výstupní porty: `AuditOutboxRepository` (čtení zpracovatelných řádků, mark sent/failed) a `AuditOutboxEventPublisher` (publish payloadu). Enum `AuditOutboxStatus`: `PENDING` / `SENT` / `FAILED`.

### Adaptéry / infrastructure (`com.openbank.audit.infrastructure`)
- **`persistence.AuditRepository`** + `AuditEntryEntity` — Hibernate Reactive Panache repository. `save` běží uvnitř `Panache.withTransaction`; `findByAggregateId` čte uvnitř `Panache.withSession`, řazeno podle `occurredAt DESC`, stránkováno na limit dodaný volajícím (omezeno 1..500).
- **`persistence.entity.AuditOutboxEntity`** + `repository.AuditOutboxRepositoryImpl` — mapování řádku transakčního outboxu pro `audit_outbox`.
- **`outbox.AuditOutboxDispatcher`** — `@Scheduled(every = "5s")` drainuje až 25 zpracovatelných řádků, každý publikuje přes resilience-obalené volání (`@Bulkhead`, `@CircuitBreaker`, `@Retry`, `@Timeout`) a označuje `SENT` nebo `FAILED`. Scheduler chytá a spolkne top-level chyby, takže smyčku nikdy neshodí.
- **`kafka.KafkaAuditOutboxEventPublisher`** — implementuje `AuditOutboxEventPublisher` emitováním `Record<String,String>` na kanál `audit-events-out`.
- **`rest.AuditResource`** — JAX-RS resource vystavující jediný read endpoint, gated přes `@RolesAllowed`.

## Ingest flow (consume)

1. Producent (account, transaction, balance, party, kyc, consent service) emituje doménovou událost do svého Kafka topiku.
2. `AuditConsumer` ji přijme na sdíleném kanálu `audit-events-in` (consumer group `audit-service`, `auto.offset.reset=earliest`).
3. Payload je naparsován; identita agregátu odvozena; vytvoří se nová `AuditEntry` s čerstvým `entry_id` UUID a `recordedAt = now`.
4. `AuditRepository.save` ji uloží transakčně. DB trigger orazítkuje `retention_until = occurred_at + 10 let`; immutability rules blokují jakoukoli pozdější změnu.

## Outbox → Kafka flow (re-emit)

Služba nese standardní transakční-outbox mašinérii (tabulka `audit_outbox`, `AuditOutboxDispatcher`, `KafkaAuditOutboxEventPublisher`), aby zaznamenané události bylo možné re-publikovat downstream (např. do compliance/SIEM streamu) s at-least-once doručením a resiliencí odolnou vůči back-pressure.

> **Provozní poznámka:** vstupní kanál `audit-events-in` je nakonfigurován v `application.yaml`; výstupní kanál `audit-events-out` je v kódu referencován publisherem, ale v `application.yaml` **zatím není deklarován**. Re-emit cestu považuj za zapojenou-ale-spící, dokud nebude nakonfigurován odchozí konektor (viz [05 — Operations](./05-operations.md)). Jde o záměrnou inventurní poznámku, ne vymyšlený topic.

## Klíčové porty

| Port | Směr | Implementace |
|---|---|---|
| `audit-events-in` (Kafka kanál) | inbound | `AuditConsumer.consume` |
| `AuditRepository.save` / `findByAggregateId` | persistence | Panache + PostgreSQL |
| `AuditOutboxRepository` | outbound (DB) | `AuditOutboxRepositoryImpl` |
| `AuditOutboxEventPublisher` | outbound (Kafka) | `KafkaAuditOutboxEventPublisher` |
| `GET /api/v1/audit/entries/{aggregateId}` | inbound (HTTP) | `AuditResource` |

## Cross-cutting

- **Reaktivně všude** — Hibernate Reactive + Mutiny, přemostěno na Kotlin coroutines přes `awaitSuspending`.
- **Observability** — Micrometer/Prometheus metriky a OpenTelemetry tracing (OTLP exporter), SmallRye Health probes.
- **Security headers** — striktní sada konfigurovaná globálně (CSP `default-src 'self'`, HSTS, `X-Frame-Options: DENY`, nosniff, referrer/permissions policy).
