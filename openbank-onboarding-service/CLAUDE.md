# openbank-onboarding-service — agent notes

Onboarding read-model and four-eyes approval cockpit (**ADR-0068**), port **8130**. Hexagonal
per ADR-0002. Not a money-path service — single approval sufficient.

## What it does

Maintains a **denormalized onboarding funnel read-model** over the party/KYC/SCA state machine.
Events from `party-service` drive state transitions; the admin-UI "Onboarding Cockpit" queries
this service to display where each applicant is in the funnel (`funnel_stage`).

Key concepts:
- One `onboarding_records` row per party. Created on `PARTY_CREATED`; updated on KYC/SCA events.
- `funnel_stage` drives the admin-UI board column (PENDING_KYC, KYC_IN_PROGRESS, PENDING_SCA, …).
- `four-eyes` gate: state transitions that require two-operator approval are gated here before
  the downstream command is issued to `party-service`.

## Database — applied migrations + rollback notes

**Never edit an applied migration file** — Flyway checksum will mismatch → crashloop.
Rollback notes for applied migrations are kept here, not in the SQL file.

| Version | File | Creates | Rollback |
|---------|------|---------|---------|
| V1 | `V1__create_onboarding_records.sql` | `onboarding_records` table + indexes | `DROP INDEX idx_onboarding_updated_at; DROP INDEX idx_onboarding_funnel_stage; DROP TABLE onboarding_records;` |
| V2 | `V2__onboarding_records_seq.sql` | `onboarding_records_seq` sequence (Hibernate ORM 6 pooled allocationSize=50) | `DROP SEQUENCE IF EXISTS onboarding_records_seq;` |

V2 existed because V1 used `BIGSERIAL` (implicit sequence `onboarding_records_id_seq`) but
Hibernate ORM 6 / Panache looks for `onboarding_records_seq` by naming convention — inserts
failed with "relation does not exist". V2 adds the correctly-named sequence.

## Layout

- `domain/model/` — `OnboardingRecord`, `FunnelStage`, events
- `application/` — `OnboardingService`, `FourEyesGate`
- `infrastructure/persistence/` — `OnboardingRecordRepository` (reactive Panache)
- `infrastructure/messaging/` — Kafka consumer for party/KYC/SCA events
- `infrastructure/rest/` — `OnboardingResource`

## Build / test

```
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :openbank-onboarding-service:test --offline
```

## Config gotchas

- Flyway uses JDBC datasource; Panache uses the reactive datasource — both must be configured.
- The OIDC config uses env-overridable placeholders; tests use `%test` profile with `oidc.enabled: false`.
