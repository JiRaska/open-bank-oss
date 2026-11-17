# Resilience bounds — openbank-mcp-service

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
| `account-service` | 2000 | 5000 |
| `balance-service` | 2000 | 5000 |
| `consent-service` | 2000 | 5000 |
| `transaction-service` | 2000 | 5000 |

## Reactive datasource pool self-heal

Quarkus leaves `idle-timeout` / `max-lifetime` / `max-size` off by default, so a
transient DB blip that poisons pooled connections wedges the service until a pod
restart — with every health probe green (billing #1682: ~8h unreachable against a
healthy DB). These bounds make the pool discard suspect connections on its own.

- `idle-timeout` evicts a dead idle connection within the cleaner period.
- `max-lifetime` is the hard ceiling on how long any wedge can survive.
- `max-size` bounds a reconnect storm.

Configured values:

- `idle-timeout: 5M`
- `max-lifetime: 30M`
- `max-size: 20`



## JSON-RPC input bounds

Every caller-supplied value on `POST /mcp` is bounded before it is reflected, audited or
forwarded (`McpInputBounds`, #16628):

- U+0000 anywhere in the decoded body (keys or values, any depth) → HTTP 400, JSON-RPC
  `-32602`, before dispatch. Not audited; logged without the payload.
- Tool names, unknown methods and invalid ids are echoed at most 64 characters, then
  `...[truncated]`.
- `list_transactions.limit`: integer in `1..200`, default `50` when absent; anything else
  is `-32602`.
- Audit `argument_keys`: at most 32 keys, each at most 64 characters.
- `accountId` on `get_balance` / `list_transactions` / `get_statement` must be a canonical IBAN (uppercase, no spaces; shape and libs-domain
  mod-97) before consent-service is called; otherwise `-32602`.
- Every NUL refusal increments `openbank.mcp.input_refused{reason="nul"}` (closed tag set).
