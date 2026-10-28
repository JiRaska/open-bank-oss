# Test JVM heap and kover-ic diagnostics (#9919)

## Why this exists

`openbank-agent-service` hit the intermittent kover binary-report truncation tracked
in issue #9919 (consent-service carried it first): roughly one CI run in ten fails
`:openbank-agent-service:koverVerify` with
`Failed to load coverage data ... java.io.EOFException`, because the forked test JVM
dies mid-suite under the default 512m heap and kover's agent never finishes writing
`build/kover/bin-reports/test.ic`. The reported coverage then reads far below the real
value (observed 16–86 % against the floor of 87 on PR #14059, which touched no
agent-service source).

## What changed in `build.gradle.kts`

- `tasks.withType<Test>().configureEach { maxHeapSize = "2g" }` — the same per-module
  mitigation already used by account-service, consent-service, kyc and
  domestic-payment. It is deliberately not a fleet default: only modules whose suites
  demonstrably OOM the 512m fork pay the extra memory.
- A diagnostic block (copied from consent-service) that logs `test.ic` size + mtime
  after `test` and again before `koverCachedVerify`, plus any live JVM still carrying
  the kover agent. The two readings distinguish the two live hypotheses — the JVM
  exiting before the shutdown hook finished writing versus a stray JVM still writing
  the file. Remove the block once a failing run has printed both lines and #9919 has
  its answer.

Neither change alters production behaviour; the coverage floor (87, LINE) is unchanged.
