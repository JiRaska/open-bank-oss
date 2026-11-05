# Resilience bounds — openbank-pension-service

Operational reference for the declarative resilience configuration seeded in
`application.yaml` by the 2026-10 fleet sweep (billing #1682 convention). The
values below are held by the enforced CI gates `rest-client-timeouts` and
`datasource-pool-self-heal`; loosening them requires an explicit owner decision
in this file's service, not a silent edit.

## REST client timeouts

An unbounded wait on an upstream call is a defect in itself: one slow upstream
holds caller threads until the pool exhausts and the failure surfaces several
services away from its cause.

| Client | connect-timeout | read-timeout |
|---|---|---|
| `pension-fund-service` | 2000 | 5000 |
| `product-catalog` | 2000 | 5000 |

## Reference annuity partner response lifecycle

`ReferenceRestAnnuityAdapter` uses the JDK HTTP client for the reference annuity
partner protocol. It rejects non-success HTTP statuses without collecting their
response bodies or waiting for their end. The existing status exception remains
the caller-visible result. A transport failure on a successful response remains
a transport failure; status handling is scoped to each request.

Cancellation of the calling coroutine, including its deadline, cancels the
original HTTP exchange and propagates cancellation. This aborts the local exchange;
it does not prove that the partner rolled back a command. Consult the existing
partner status/reconciliation flow before any retry. The adapter adds no retry.
Existing request timeouts, credential sourcing, redirect policy and idempotency
headers are unchanged.

Successful responses retain the existing JSON parsing contract. This change does
not introduce a response-size or offer-count limit: those bounds require a
separate partner protocol decision. The inbound request limit is not an outbound
response budget. Loopback integration tests exercise held declared/chunked error
bodies, cancellation, concurrent response isolation and valid responses larger
than 8 MiB; they do not establish deployed partner behavior.

## Reactive datasource pool self-heal

Quarkus leaves `idle-timeout` / `max-lifetime` / `max-size` off by default, so a
transient DB blip that poisons pooled connections wedges the service until a pod
restart — with every health probe green (billing #1682: ~8h unreachable against a
healthy DB). These bounds make the pool discard suspect connections on its own.

- `idle-timeout` evicts a dead idle connection within the cleaner period.
- `max-lifetime` is the hard ceiling on how long any wedge can survive.
- `max-size` bounds a reconnect storm.

Configured values:

- `idle-timeout: 60S`
- `max-lifetime: 30M`
- `max-size: 20`


