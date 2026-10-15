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

## Also in this library

`ApiError` now names its HTTP-status constants instead of embedding magic
numbers — the detekt baseline shrank accordingly. Pure readability; no wire
change.
