# RulesYamlSections — reading governance rules as data

`com.openbank.libs.governance.RulesYamlSections` (in `openbank-libs-runtime`)
is the shared parser every governance consumer uses to read
`openbank-libs/governance/rules.yaml`.

## Why it exists

Two governance controllers (governance-auditor, control-liveness-sentinel)
previously carried hand-rolled copies of rules fragments — compiled mirrors
that could drift from the file CI enforces. `RulesYamlSections` replaces the
regex/string-matching approach with typed section lookups (e.g.
`event_consumer_liveness.topic`, `lineage_code_audit.edge`), so a malformed or
moved key fails loudly at parse time instead of being silently skipped.

## Contract

- Sections are addressed by dotted path; missing optional sections return
  empty collections (consumers fail open and log at WARN).
- The parser never round-trips the file: comments and review-critical
  formatting survive untouched.
- `RulesYamlSectionsTest` pins parsing behaviour against the real
  `rules.yaml` in this repository, so a governance edit that breaks consumers
  fails in `openbank-libs-runtime:build` before it reaches either controller.

## Running the checked-in rules test

Run `./gradlew :openbank-libs-runtime:test --tests '*RulesYamlSectionsTest'` from
this repository. The Gradle test task derives the canonical rules file from
`rootProject.file("openbank-libs/governance/rules.yaml")`, declares it as the
`governanceRulesUnderTest` input, and supplies its project-relative path through
`openbank.test.governance-rules`. This keeps the property independent of the
checkout location while resolving it from the test task's project directory.

The real-file test fails when the property is missing or the file is not a
regular file; it does not silently skip the checked-in rules. An IDE invocation
must supply that property with a path resolved from its working directory, or
use the Gradle test task. Changing the declared rules-file input invalidates
the test task's up-to-date result. The test checks non-empty money-path, event-consumer and lineage sections
and the presence of the review-approval pair; this is parser evidence,
not a live controller or policy-rollout test.

## Also in this library

`ApiError` now names its HTTP-status constants instead of embedding magic
numbers — the detekt baseline shrank accordingly. Pure readability; no wire
change.
