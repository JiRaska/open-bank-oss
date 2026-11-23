# Resilience bounds — openbank-delegation-service

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
| `party-service` | 2000 | 2000 |
| `pid-service` | 2000 | 2000 |
| `sca-service` | 2000 | 2000 |

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

## Client certificate reload (outbound mTLS)

Every named `quarkus.tls.<name>` configuration that presents a client certificate to a peer's
8443 listener sets `reload-period: 1h`. cert-manager renews the mounted client certificate
before it expires; without a reload period the process would keep presenting the old one until
the peer rejects it, while every expiry signal measured the renewed Secret and read healthy.
After a renewal the new certificate is in use within an hour, with no restart. The
`tls-certificate-reload` gate keeps every such configuration covered.

## Payment-proposal idempotency: only definitive answers are stored

`PaymentProposalResource` replays a stored response for a repeated `Idempotency-Key`, but it
stores only a definitive answer. A 5xx is released from the idempotency store, never saved:
customer-edge sends a FIXED key per proposal for reject (`mcp-proposal-reject-<id>`), so a stored
transient failure would be replayed to every retry for the record's whole TTL and the customer
could never reject that proposal. A retry after a 5xx therefore executes again.
