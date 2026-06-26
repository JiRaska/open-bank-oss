## Summary

<!-- 1-3 sentences. What and why. -->

## Type of change

- [ ] feat (new functionality)
- [ ] fix (bug fix)
- [ ] refactor (no behavior change)
- [ ] perf (performance)
- [ ] docs
- [ ] chore (build / tooling)
- [ ] security (private until disclosed)
- [ ] breaking change

## Linked issues

<!-- Closes #NNN, Refs OPEN-NNN -->

## Changes

<!-- Bulleted list of what changed. Be specific. -->

-
-

## Definition of Done

- [ ] Hexagonal layering preserved (domain has zero framework imports).
- [ ] Unit tests added/updated.
- [ ] Integration tests added/updated (if service boundary involved).
- [ ] Contract tests updated (if public API changed).
- [ ] `./gradlew detekt ktlintCheck koverVerify build` passes.
- [ ] No new lint warnings.
- [ ] OpenAPI spec regenerated (if REST API changed).
- [ ] Flyway migration added + rollback note (if DB changed).
- [ ] Avro schema versioned backward-compatibly (if event changed).
- [ ] Docs updated (README, ADR if architectural).

## Security checklist

- [ ] No secrets, tokens, passwords, or PII in code, config, tests, logs.
- [ ] No new `@SuppressWarnings`, `as Any`, `@Suppress("...")` without
      justification (state it in this PR).
- [ ] No new third-party dependency, OR: dependency review attached
      (license, CVE, source).
- [ ] Auth / crypto / payment code changed? → tag `security-review-required`.
- [ ] PII path changed? → tag `gdpr-review-required`.
- [ ] Cardholder data path changed? → tag `pci-review-required`.

## Compliance impact

<!-- Mark all that apply -->
- [ ] PCI DSS
- [ ] DORA
- [ ] GDPR
- [ ] PSD2 / SCA / RTS
- [ ] AML / 5AMLD
- [ ] CNB reporting
- [ ] None

If any are marked, describe the impact and any required compensating
controls below.

<!-- describe -->

## Rollout / Rollback

- Deployment strategy: <rolling | blue-green | canary | feature-flag>
- Rollback plan: <how to revert if something goes wrong>
- Data migration reversible: yes / no / N/A

## Screenshots / demo (if UI change)

<!-- attach -->

## Reviewer notes

<!-- Anything reviewers should pay attention to. -->
