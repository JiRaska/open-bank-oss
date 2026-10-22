# Resilience bounds — openbank-risk-engine

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
| `ledger-service` | 2000 | 5000 |

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



## Pricing request bounds

`POST /api/v1/risk/pricing` (the stateless pricing request) is bounded so one request
cannot buy unbounded work:

- at most 8 legs, each spanning at most 50 years, no frequency finer than daily, and at most
  2,000 periods per leg;
- `paymentLagBusinessDays` and `rate.fixingLagBusinessDays` of 0 to 30 business days. The
  calendar walks a lag one business day at a time, so an unbounded lag is unbounded work;
- a request past `openbank.risk.pricing.timeout` answers 503 `COMPUTATION_TIMEOUT`. The
  deadline is also checked once per leg while the legs are prepared, not only while they are
  priced, so a many-leg request cannot overrun it unnoticed.

A breach of any bound is a 400. A deal that is already booked is not subject to the lag and
leg-count bounds: it is stored as unvaluable if it cannot be priced, never rejected or dropped.
