---
date: 2026-09-26
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [api-contract, testing, libs, architecture]
summary: "Internal REST clients are generated from each provider's openapi.yaml into one per-provider client artifact, versioned by the API-contract axis (ADR-0048) and bound to Pact consumer tests with literal paths; migration starts non-money-path."
---

# ADR-0319 — Generated, contract-bound inter-service REST clients

## Context

ADR-0005 made external APIs design-first and says clients are generated from the spec, but its
own alternatives section permits code-first for internal east-west APIs "where the consumer is in
the same repo". ADR-0048 gave each `openapi.yaml` its own contract version; ADR-0063/0092 adopted
Pact. None of them decides how an internal consumer obtains its client, and in practice every
consumer hand-writes one.

Measured on `origin/main` 2026-09-26 (filenames under `openbank-*/src/main/**`):

| Hand-written client | Copies |
|---|---|
| `AccountServiceClient.kt` | 10 (+ `AccountClient`, `AccountRegistryClient`) |
| `TransactionServiceClient.kt` | 8 (+ `TransactionServiceRestClient`) |
| `SanctionsServiceClient.kt` | 6 |
| `PartyServiceClient.kt` | 5 (+ 5 differently named Party clients) |
| `ProductCatalogClient.kt` | 4 |
| Ledger (`LedgerRestClient` 4, `LedgerClient` 3, trial-balance and clearing variants) | 9 |

59 services ship `src/main/resources/openapi.yaml`; exactly one module
(`openbank-product-catalog`) applies `org.openapi.generator`, and only for its own server
stubs. 67 pacts are committed under `pacts/`. The documented failure mode this produces is real:
finrep-service called a ledger path that never existed while its unit tests passed (#2269,
root CLAUDE.md, Pact section), and `openapi-enum-domain-drift` found spec enums diverging from
the code in 16 services (#5962) — a hand-copied client inherits whichever side it was copied
from.

## Decision

We will:

1. **Generate** a Kotlin MicroProfile REST client (`@RegisterRestClient` interface plus DTOs)
   from each provider's `src/main/resources/openapi.yaml` into **one client artifact per
   provider**, e.g. `openbank-clients/<provider>-client`, built in the monorepo. Consumers depend
   on that artifact; they do not copy interfaces.
2. **Version** the artifact by the provider's API-contract version (`info.version`, ADR-0048),
   not its release version. A consumer pins a contract major, which already equals the URL
   `/api/v{N}`.
3. **Bind to Pact.** Every consumer of a generated client keeps (or adds) a Pact consumer test
   in which the **expected path is a literal** and only the outgoing request is produced by the
   generated client — the asymmetry rule in root CLAUDE.md. Generated code makes the request
   side exact; the literal keeps the test able to fail. Provider `@PactFolder` replay
   (`pact-provider-replay-coverage`) stays the backstop.
4. **Migrate provider by provider, non-money-path first**: product-catalog (already runs the
   generator), party-service, sanctions-service; then money-path providers account-service,
   transaction-service and ledger-service last, each in its own PR with money-path review.
   Hand-written copies are deleted in the PR that switches the consumer.

## Alternatives considered

- **A hand-written shared client per provider in `openbank-libs`.** Removes the copies without a
  generator. Rejected: it is still a second description of the API beside `openapi.yaml`, so it
  drifts exactly as the copies do, only once instead of ten times.
- **Keep per-consumer clients, rely on Pact alone.** No migration. Rejected: Pact only covers the
  interactions someone wrote, and 30+ copies means 30+ places to update per contract change.
- **Generate into each consumer's build (no shared artifact).** No new modules. Rejected: every
  consumer re-runs the generator with its own options, and nothing ties the consumer to a
  contract version.

## Consequences

**Positive**
- One source for each API; a spec change breaks consumer compilation instead of production.
- Contract-version pinning makes a provider major bump visible in the consumer's build file.

**Negative**
- New modules and a generator in the build graph; generated DTO naming may not match existing
  domain types, so adapters stay in each consumer.
- Specs that are wrong today (#5962) must be corrected before their client is generated.

**Neutral**
- Enforcement: a new ratchet gate `handwritten-rest-client-ratchet` (no new
  `@RegisterRestClient` interface under a consumer's `src/main` for a provider that has a
  generated client), alongside the existing `pact-provider-replay-coverage` and
  `openapi-route-conformance`.
- **Runtime readiness is a measurement to do, not a decision.** Measured 2026-09-26:
  `git grep -l '@RunOnVirtualThread' -- '*.kt'` returns no source file (ADR-0016 keeps
  coroutines); the only native-image build config is `openbank-product-catalog/Dockerfile.native`
  (ADR-0083 pilot). Before generating, check that the chosen generator's client mode (reactive
  Mutiny/suspend vs blocking) matches the fleet's coroutine style and builds under the
  product-catalog native pilot. This ADR does not change ADR-0016 or ADR-0083.

### Delivery check

- `git ls-files 'openbank-*/src/main/**/AccountServiceClient.kt'` (and the other names in the
  Context table) prints nothing for every migrated provider.
- `gates.yaml` id `handwritten-rest-client-ratchet` exists.
- Each generated client has at least one consumer pact under `pacts/` replayed by the provider's
  `@PactFolder` test (`pact-provider-replay-coverage` green).

## Compliance impact

- PCI DSS: not applicable — no cardholder-data flow changes.
- DORA:    not applicable — no ICT-risk control changes beyond ordinary change management.
- GDPR:    not applicable — no change to what personal data is exchanged.
- PSD2:    not applicable — internal east-west APIs only.
- CNB:     not applicable — no regulatory reporting change.

## References

- ADR-0005, ADR-0016, ADR-0048, ADR-0063, ADR-0083, ADR-0092.
- Root CLAUDE.md, "Contract tests (Pact)".
