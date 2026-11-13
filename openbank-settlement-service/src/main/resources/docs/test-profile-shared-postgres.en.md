# Test profile and shared Postgres — Settlement

Integration tests for this service now obtain their database from the shared Postgres test resource in `openbank-libs-testing` instead of a service-local container resource. This changes test infrastructure only; settlement behaviour and the API contract are unchanged.

The test profile in `application.yaml` additionally routes the outgoing `settlement-events-out` channel to the in-memory connector, so tests no longer need a Kafka broker to publish settlement events. Deployed profiles keep their Kafka connector configuration.
