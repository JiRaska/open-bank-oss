# Test profile and shared Postgres — Loyalty

Integration tests for this service now obtain their database from the shared Postgres test resource in `openbank-libs-testing` instead of a service-local container resource. This changes test infrastructure only; runtime behaviour and the API contract are unchanged.

The `%test` profile in `application.yaml` additionally sets `openbank.outbox.dispatch-enabled: false`. Outbox rows are written and asserted by the tests, but the background dispatcher does not run during tests, so assertions on row state are not raced by dispatch. Production keeps the dispatcher setting from the non-test profile.
