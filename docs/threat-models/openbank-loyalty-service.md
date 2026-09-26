# Loyalty service threat model

This service keeps the **Lístek ledger** (ADR-0282): leaves a customer earns from defined sources,
redeems for benefits, and loses to expiry, with a per-party annual cap. Leaves are not money and
nothing here posts to the general ledger, but they are a liability the bank provisions for
(`GET /provisioning` reports the outstanding obligation), and benefits are redeemed for real value.
So the model is about **integrity of the balance and of the provisioning figure**, then about the
confidentiality of one customer's activity trail. The service calls no other service.

## What is actually at stake

- **The leaf balance per party** — derived from append-only ledger entries (earn, redeem, expire).
  Minting leaves out of nothing, or redeeming the same leaves twice, turns into benefits the bank
  pays for and into a wrong provisioning number.
- **The provisioning figure** (`outstandingLeaves`, `annualCapPerParty`, `ruleVersion`) — an input to
  the bank's own liability view; a wrong number is a misstatement, not merely a UI defect.
- **The activity trail** — which party earned from which source and redeemed which benefit, and when.
  Behavioural personal data; `governance.yaml` classifies the database `confidential` with 13-month
  retention.

## Spoofing and elevation of privilege

Every endpoint is `@RolesAllowed`; there is no unauthenticated surface and no ingress — the pod is
reachable only through its ClusterIP, and the derived NetworkPolicy admits admin-ui (the Lípa BFF),
same-namespace traffic, and the metrics/posture scrapers on the management port. Reads accept
`ROLE_OPERATOR`, `ROLE_API` and `ROLE_ADMIN`; the two writes (`earn`, `redeem`) accept
`ROLE_OPERATOR` and `ROLE_API` only.

The known weakness is the one the fleet shares: authorization is by role, not by principal, and the
Keycloak service-accounts authenticate as HUMAN and hold `ROLE_OPERATOR`/`ROLE_API` in at least one
realm. Any backend holding those roles can therefore call `earn` for any party. There is no
`@Authorize`/OPA decision in front of the writes today, so there is no per-principal narrowing of the
kind `wealth_rest_ext.rego` gives wealth-service. That is accepted for a non-money ledger while the
only caller is the operator console; it must be revisited before a customer-facing path or an
automated earn source calls in, and the fix is an `@Authorize` action with a principal-keyed rule.

## Tampering — what the domain refuses

- **Double award.** `earn` carries a `correlationEventId`; a replay answers `ALREADY_AWARDED` (200)
  with the original entry instead of a second award, so a redelivered trigger cannot mint twice.
- **Unknown sources.** `earnSourceId` must name a catalogue source; the amount and validity come from
  the catalogue, never from the request, so a caller cannot choose how many leaves to mint.
- **The cap.** Hitting the annual cap is an explicit `CAPPED` outcome, never folded into success or
  error, so a capped award cannot be mistaken for a granted one downstream.
- **Double redeem.** `redeem` requires an `Idempotency-Key` header, declared nullable and checked in
  the body, so the absent-header case answers 400 rather than 500 (#3104).
- **History.** Ledger entries are appended, not updated; the balance is a projection of them.

## Repudiation

Every earn, grant and expiry is written to the ledger and published through the transactional
outbox onto `openbank.loyalty.events` in the same transaction as the state change, so "the bank
removed my leaves" is answerable from the entries and their events. The outbox also carries the
synthetic-traffic taint (V2 migration), so synthetic probes cannot be confused with customer history.

## Information disclosure

The Kafka topic is the widest edge: payloads name the party and the earn source or benefit. It is
contained by transport and ACL — the `loyalty-service` KafkaUser (mTLS, Strimzi-issued, projected by
ESO) holds Write/Describe on `openbank.loyalty.events` only, and the topic has **no consumer yet**
(declared in `rules.yaml: event_consumer_liveness.allowlist`). The first consumer is the moment to
check that its Read ACL is scoped to that one reader. Error bodies do not echo client-supplied
strings back (CodeQL java/xss shape).

## Denial of service

The service is on no payment path; an outage degrades the Lípa console and delays awards, it blocks
no transaction. Scheduled expiry and provisioning run nightly and are idempotent over the ledger, so
a missed run is recovered by the next one. Hardening against volumetric abuse is not worth its cost
here while every caller is authenticated and in-cluster.

## Known gaps, stated rather than implied

- **Role-based, not principal-based, write authorization** (above). The largest gap.
- **No per-party rate limit on `earn`.** The catalogue fixes the amount and the cap bounds the damage
  per party per year, so the ceiling of abuse is known; attributable via the OIDC principal.
- **No VEX overlay yet** (#8830): the dependency dispositions have not been diffed for this service.
