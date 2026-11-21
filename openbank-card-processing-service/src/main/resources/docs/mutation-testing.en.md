# Mutation testing — openbank-card-processing-service

openbank-card-processing-service sits on the card money path (authorizations,
clearing, disputes, tokens), so a green unit suite is not evidence that its
lifecycle policies are exercised. PIT mutation testing closes that gap by injecting
faults (mutants) into the decision-logic packages and checking that the test suite
kills them — a surviving mutant is a test that does not actually cover the
behaviour it claims to.

## What is mutated, and what is not

`targetClasses` is `domain.model.*` + `domain.policy.*`: the aggregates and the
pure lifecycle policies, where a surviving mutant is a refused check that never
fired. The `domain.event.*` payloads are deliberately NOT mutated — they are
serialised data carriers whose wire shape is owned by the Kafka schema-versioning
contract tests; a mutated getter on a data class is noise, not signal (the
ADR-0063 thin-domain criterion, applied per package).

## Configuration

PIT runs via the `info.solidsoft.pitest` Gradle plugin in `build.gradle.kts`.
PIT's `*` glob does NOT cross package boundaries, so each test package is listed
explicitly (`domain.*` and `usecase.*`):

- `mutationThreshold = 0` — CI never fails on the kill rate; the baseline below is
  the number to move, not a gate to game.
- Reports are written to `build/reports/pitest/` (XML + HTML).

## Measured baseline (2026-11-18, local, `./gradlew :openbank-card-processing-service:pitest`)

| Metric | Value |
|---|---|
| Mutations generated | 86 |
| Killed | 55 (64%) |
| Survived | 22 |
| NO_COVERAGE | 9 |

Of the covered mutants, the test strength is ~71% — the bulk of the surviving
mutants are boundary comparisons in the aggregate guards. The paydown work added
here (`DisputeAndTokenTerminalRulesTest`) targets the terminal-state rules of
`CardDisputeCase` / `CardTokenRegistration`; the remaining NO_COVERAGE tail is
the dispute/token registers exercised today only through integration tests, which
`targetTests` does not select. The event payloads stay excluded per the criterion
above.

## How to run

```bash
./gradlew :openbank-card-processing-service:pitest
```

The kill rate for a run is in `build/reports/pitest/mutations.xml` (`status`
per `mutation` element) or summarised in `build/reports/pitest/index.html`.
