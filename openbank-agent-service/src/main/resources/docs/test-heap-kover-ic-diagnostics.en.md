# Test JVM heap and kover-ic diagnostics (#9919)

## Why this exists

`openbank-agent-service` hit the intermittent kover binary-report truncation tracked
in issue #9919 (consent-service carried it first): roughly one CI run in ten fails
`:openbank-agent-service:koverVerify` with
`Failed to load coverage data ... java.io.EOFException`, with incomplete coverage data in
`build/kover/bin-reports/test.ic`. The reported coverage can then read below the expected
value (observed 16–86 % against the floor of 87 on PR #14059, which touched no
agent-service source).

## What changed in `build.gradle.kts`

- `tasks.withType<Test>().configureEach { maxHeapSize = "2g" }` — the same per-module
  mitigation already used by account-service, consent-service, kyc and
  domestic-payment. It is deliberately not a fleet default: only this affected module receives the larger configured test heap. This is
  a mitigation; the EOF warning alone does not prove an out-of-memory exit.
- A diagnostic block (copied from consent-service) that logs `test.ic` size + mtime
  after `test` and again before `koverCachedVerify`, plus any live JVM still carrying
  the kover agent. The readings provide evidence to investigate two live hypotheses — the JVM
  exiting before the shutdown hook finished writing versus a stray JVM still writing
  the file. Remove the block once a failing run has printed both lines and #9919 has
  its answer.

Neither change alters production behaviour; the coverage floor (87, LINE) is unchanged.
