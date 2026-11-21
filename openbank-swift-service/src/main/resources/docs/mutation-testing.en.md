# Mutation testing — openbank-swift-service

openbank-swift-service moves cross-border money (SWIFT MT103 / institution rails), so
a green unit suite is not evidence that its admission and lifecycle logic is
exercised. PIT mutation testing closes that gap by injecting faults (mutants) into
`com.openbank.swift.domain.*` and checking that the test suite kills them — a
surviving mutant is a test that does not actually cover the behaviour it claims to.

## Configuration

PIT runs via the `info.solidsoft.pitest` Gradle plugin in `build.gradle.kts`.
Targets are the domain packages (the pure decision logic per ADR-0002) and every
unit-test package that exercises them. PIT's `*` glob does NOT cross package
boundaries, so each test package is listed explicitly:

- `targetClasses = ["com.openbank.swift.domain.*"]`
- `targetTests`: `domain.model.*`, `domain.institution.*`, `institution.*`,
  `application.usecase.*`.
- `mutationThreshold = 0` — CI never fails on the kill rate; the baseline below is
  the number to move, not a gate to game.
- Reports are written to `build/reports/pitest/` (XML + HTML).

## Measured baseline (2026-11-18, local, `./gradlew :openbank-swift-service:pitest`)

| Metric | Value |
|---|---|
| Mutations generated | 232 |
| Killed | 179 (77%) |
| Survived | 37 |
| NO_COVERAGE | 16 |

The thin-domain caveat from ADR-0063 turned out to be a test-selection artifact,
not a property of the domain: with the institution-rail tests included, the kill
rate lands in the same band as the rest of the money path. Test strength on the
covered set is 83% (179/216). The remaining 16 NO_COVERAGE mutants sit on
`SwiftMessage` value-edge branches that the unit selection reaches but does not
assert tightly enough — the minor-units round trip (`moneyOfMinorUnits`) added
in this change pays down exactly that concentration.

## How to run

```bash
./gradlew :openbank-swift-service:pitest
```

The kill rate for a run is in `build/reports/pitest/mutations.xml` (`status`
per `mutation` element) or summarised in `build/reports/pitest/index.html`.
