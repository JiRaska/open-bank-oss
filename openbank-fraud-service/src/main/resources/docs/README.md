# Fraud Service API

Real-time transaction fraud scoring (ADR-0084).

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.

## Integration test setup

Under the `%test` profile, Kafka output from `fraud-outbox-out` and outbox dispatch are disabled by default. `TransactionSignalConsumerBootIT` uses its Testcontainers resource to enable the output channel while it checks the broker-backed consumer path. These settings apply only to tests; production outbox configuration is unchanged.
