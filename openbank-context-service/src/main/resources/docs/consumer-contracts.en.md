# Consumer contracts — openbank-context-service

context-service consumes AML case state for its context assembly. This page
records the consumer side; context-service's own provider verification lives
with the API docs.

## context-service → openbank-aml-service

`AmlCaseStatusPactConsumerTest` publishes
`pacts/openbank-context-service-openbank-aml-service.json`. It pins
`GET /api/v1/aml/cases/{id}`:

- the case `id` and `status` fields the context snapshot binds — both DTO
  fields default to null, so a renamed field would bind as a silent null
  rather than fail loudly,
- the unauthenticated refusal (401, ADR-0279 #3).

The provider replays the contract in aml-service's folder/broker provider
verification classes; the refusal leg reuses the fleet NEGATIVE_AUTH_STATE.

## Running

```bash
./gradlew :openbank-context-service:test --tests '*AmlCaseStatusPactConsumerTest'
```

The generated pact lands in the repo-root `pacts/` directory
(`pact.rootDir`, git-pact convention) and is committed; the
`pact-drift-check` workflow replays it against the provider on every change.
