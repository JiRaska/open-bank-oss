# Consumer contracts — openbank-analytics-sink

analytics-sink is a consumer in two pact relationships and a provider in one.
This page records the consumer side; the provider side
(`CreditProfile*` verification tests) is documented in the API docs.

## analytics-sink → openbank-account-service

`AccountRegistryPactConsumerTest` publishes
`pacts/openbank-analytics-sink-openbank-account-service.json`. It pins the
initial-load page of `GET /api/v1/accounts/active`:

- the row fields the reconcile projection binds (`id`, `accountNumber`,
  `accountType`, `partyId`, `productId`, `currencyCode`, `openedAt`),
- the default 200-row request and its optional terminal cursor, plus a one-row
  nonterminal page seeded with two existing ACTIVE-account provider states;
  this page requires a non-empty `pagination.nextCursor` and `hasNextPage=true`,
  so a renamed cursor cannot silently truncate the snapshot,
- the unauthenticated refusal (401, ADR-0279 #3).

The provider replays the contract in account-service's
`AccountPactFolderProviderVerificationTest` (positive states) and its
NegativeAuth verification class (refusal state).
The initial-load paging test also verifies the actual 200-row requests and
that the next request echoes the returned opaque cursor.

## Running

```bash
./gradlew :openbank-analytics-sink:consumerPactTest
```

(or `:openbank-analytics-sink:test --tests '*AccountRegistryPactConsumerTest'` inside the full suite).

The generated pact lands in the repo-root `pacts/` directory
(`pact.rootDir`, git-pact convention) and is committed; the
`pact-drift-check` workflow replays it against the provider on every change.
