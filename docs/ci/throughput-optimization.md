# CI throughput improvements

This rollout reduces repeated work on the existing runner pools. It introduces no
paid runner tier, external cache, database, or hosted service. Tests, coverage floors,
required check names, Pact checks, image scanning, signatures, and PR review remain gates.

## Queue latency first

`Admin UI` and `Validate manifests` evaluate the same anchored UI verdict directly
from its producers. The manifest verdict no longer waits for the UI verdict to obtain
another runner. Missing change-detection output fails closed. The PIT service matrix
uses at most two concurrent service jobs, limiting advisory batch pressure on required CI.

Measure queue time separately from execution:

```sh
python3 .github/scripts/ci-latency-report.py --repo JiRaska/open-bank-oss \
  --max-runs 20 --format markdown --output /tmp/ci-latency.md
```

The report uses exact run attempts and paginated job inventories. It separates event
types, records missing timestamps as unknown, and never substitutes workflow update time
for completion. Setup time is a named-step heuristic; the remainder includes cleanup.
Attempt-start-to-verdict time is not a reconstruction of the workflow's critical path.

## Context-service test pilot

`ContextVerifiedIdentityIT` declares the same database resource identity as its sibling
tests. Local fresh-task runs discovered the same 118 test cases and outcomes, including
one existing broker-dependent skip, while PostgreSQL starts fell from 24 to 14. Local
elapsed time is not a prediction of hosted CI savings: cache warmth and load differ.
The bank-isolation test also uses the existing fixture's future read bound consistently;
comparing a database-recorded timestamp with the host clock made its last assertion flaky.

Optional class-order hints come from a validated main-run Test Intelligence envelope.
Previously failed or retry-flaky classes come first, then shorter classes. Quarkus retains
its primary test-resource/profile grouping; only its secondary class order changes.
Discovery, test methods, retries, and coverage requirements are unchanged. The hint is
a tracked Gradle input for context-service's ordinary `test` task only. Missing, stale,
oversized, or invalid evidence uses the default full-suite order. Fetching is bounded by
a one-minute step timeout. Historical provider builds do not enable this pilot.
A fresh run with reversed class priorities preserved all 118 testcase identities and
outcomes and the 14 PostgreSQL starts; this checks the integration beyond the orderer's
unit tests.

## Verified build handoff

Main-push context-service builds use the same native Linux ARM64 platform and fast-jar
augmentation recipe as deployment. They produce the application and CycloneDX SBOM in
one mandatory Gradle graph. Optional artifact packaging/upload retains the bundle for
one day. Deployment accepts only a successful same-repository main-push Services CI
attempt for the exact source SHA, platform and recipe. It verifies every file digest
and installs the complete validated bundle atomically. Any miss uses the ordinary build.
Image scanning, signing, attestation and deployment authorization still run.

Without completion ordering, a first push can reach deployment before CI finishes.
Artifact reuse on that path is opportunistic; a recovery run can reuse a completed
producer. Do not count a cache lookup as a saved build.

The completed-CI scheduling pilot is disabled by default. Only the repository variable
`CONTEXT_BUILD_HANDOFF_ENABLED=true` enables it. While enabled, the push lane excludes
context-service; a successful same-repository Services CI main-push completion selects
only context-service, and only if the ordinary change detector selected it. Manual and
scheduled recovery retain their existing selection. Failed CI does not trigger a deployment.
Before checking out producer code, the trusted workflow revision validates the event and
requires the source commit to be an ancestor of main. Checkout, image tags, Pact selection,
GitOps metadata and SLSA materials use that source explicitly. Workflow provenance retains
GitHub's workflow revision; `GITHUB_SHA` is never rewritten. Missing Pact evidence refuses
the handoff instead of selecting a moving main tag. A provenance preflight rejects a source
without the split-source helper before credentials or image publication.

Activation is separate from merging this change. First verify a successful main context
artifact with the matching ARM64 recipe, current source support and exact/equivalent Pact
evidence; then compare the enabled pilot against the measurements below. Setting the
variable to `false` restores ordinary push selection for new runs. Already running jobs
retain their evaluated selection and must be considered before changing the flag; the
variable is not a cancellation mechanism.

## Evidence lookup

The existing runtime evidence publisher remains authoritative for fresh execution data.
Its ancillary snapshot seed is now directly discoverable by artifact name, with producer,
attempt, source and timestamp validation. The normal lookup reads one inventory, one run
and one archive instead of walking deploy history. Immutable snapshots keep their existing
retention. Missing or fully expired aliases use the validated legacy discovery path;
invalid unexpired provenance is an error. Reruns replace only their own alias. Observation
timestamps are never refreshed by copying a snapshot.

Ancillary mutation/performance/history collection still runs with the Admin UI image build.
Removing those collectors requires a separate producer migration; runtime publication alone
does not make their observations independent of a deploy.

## Evaluate before widening

Compare at least 20 comparable runs per event and affected service before expanding the
pilots. Record p50/p95 queue and execution time, first-failure time, total runner work,
artifact hit/miss reason, test identities/outcomes/skips and container starts. A smaller
job duration with a longer end-to-end deploy is not a throughput win. Reject an optimization
that hides missing evidence, increases flaky failures, or changes the discovered suite.

The existing merge train already admits available candidates without waiting to fill a
batch, preserves oldest-first fairness, and reduces batches after failures or inconclusive
budgets. Its policy is not duplicated here. Tune it only against measured queue data.

Rollback is a normal PR reverting the corresponding optimization. Artifact misses and
unavailable ordering hints already preserve the original full build/test path. Keep
the named required verdicts and their negative-path tests during any rollback.
