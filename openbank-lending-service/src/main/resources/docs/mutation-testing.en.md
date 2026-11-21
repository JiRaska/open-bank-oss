# Mutation testing — openbank-lending-service

openbank-lending-service is a money-path service, so a green unit suite is not
evidence that its money math is exercised: a test can pass against code whose
branches no assertion ever discriminates. PIT mutation testing closes that gap
by injecting faults (mutants) into `com.openbank.lending.domain.*` and checking
that the test suite kills them — a surviving mutant is a test that does not
actually cover the behaviour it claims to.

## Configuration

PIT runs via the `info.solidsoft.pitest` Gradle plugin in
`build.gradle.kts`. Targets are the domain package (the pure money logic per
ADR-0002) and the domain plus application-use-case test classes:

- `targetClasses = ["com.openbank.lending.domain.*"]`
- `targetTests` selects `**.*Test` under the domain and application use-case
  packages.
- `mutationThreshold = 0` — CI never fails on the kill rate; the baseline
  below is the number to move, not a gate to game.
- Reports are written to `build/reports/pitest/` (XML + HTML).

## Measured baseline (2026-11-18, local, `./gradlew :openbank-lending-service:pitest`)

| Metric | Value |
|---|---|
| Mutations generated | 336 |
| Killed | 228 (68%) |
| Survived | 40 |
| NO_COVERAGE | 68 |

Of the covered mutants the test strength is 85%. The 68 NO_COVERAGE mutants are the useful signal: they mark read-model and view paths (`CreditDecisionView`, the `*Summary` rows) that only integration tests reach, which `targetTests` does not select. The 40 survivors are assertions that execute the code but would also pass if the condition were inverted — strengthening them is the paydown work.

## How to run

```bash
./gradlew :openbank-lending-service:pitest
```

The kill rate for a run is in `build/reports/pitest/mutations.xml` (`status`
per `mutation` element) or summarised in `build/reports/pitest/index.html`.
