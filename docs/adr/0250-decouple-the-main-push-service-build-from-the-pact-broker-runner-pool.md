---
date: 2026-08-09
decision-status: accepted
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ci, finops, capacity, testing]
summary: "Main-push service builds run on the 6-slot in-cluster ARC pool only because the Pact Broker has no public ingress. Split the job so compile+test run on free hosted runners and only broker-touching work stays in-cluster."
followup: "#4414 — Phase 2, the actual _service-ci.yml job split and Gradle provider-test isolation, is specified below but not implemented: it needs a session with a working Gradle/JVM environment to verify a Gradle Test Suite change across 26 services, which this session does not have."
---

# ADR-0250 — Decouple the main-push service build from the Pact Broker runner pool

**Delivery note (2026-08-09, same day as acceptance):** Accepted with a two-phase plan (see
"Implementation phases" below), added on re-reading this ADR with fresh queue data (see that
section) and, critically, after actually reading `_service-ci.yml` and the shared Gradle
convention plugin rather than reasoning from this document alone. **Phase 1** (raise
`arc_max_runners` 6→12, retire `runners-warm`) already shipped in #4319, *before* this ADR was
accepted — the immediate lever from "Alternatives considered" was taken first, and the queue
depth this section originally measured (42, p50 7h) has since moved (see below) but the
underlying saturation has not resolved, which is itself evidence for the harder half of this
ADR rather than against it. **Phase 2** (the actual `build`/`contract` split + Gradle task
isolation) is specified precisely enough to hand off, and NOT implemented, for a reason worth
stating rather than working around: it requires a Gradle Test Suite change verified by
compiling and running tests across up to 26 services, which needs a real Gradle/JVM toolchain
this session does not have. Attempting it blind would risk the fleet's own deploy pipeline —
exactly the "hard to reverse, affects shared systems" class of action that gets a pause, not a
guess.

## Context

Every push to `main` starts a `Services CI` lane that is deliberately never cancelled — the
per-SHA concurrency group exists so a superseded lane cannot strand a service's deploy (#846).
Each lane's per-service build runs on the self-hosted `openbank-build` ARC pool, selected by
one expression in `_service-ci.yml`:

```yaml
runs-on: ${{ (github.event_name == 'push' || github.event_name == 'workflow_dispatch')
             && github.ref == 'refs/heads/main' && 'openbank-build' || 'ubuntu-latest' }}
```

The PR lane already runs on `ubuntu-latest` with `PACT_BROKER_URL` blanked. **The only reason
the main-push lane differs is broker reachability**: the Pact Broker has no public ingress
(ADR-0056), so provider verification and pact publishing can only be done from inside the
cluster (ADR-0092).

That single dependency puts the entire fleet's post-merge build through a pool capped at
`arc_max_runners = 6`. Measured 2026-08-09 against the live cluster and the Actions API:

| measurement | value |
|---|---|
| queued `Services CI` runs | 42 |
| age of that queue | p50 7.0 h, max 23.4 h |
| queued runs repo-wide | 79 |
| per-build queue time | p50 85 min, p95 123 min |
| per-build execution time | 4–11 min |
| runner pods, and how many were executing a job | 6 of 6 |

The pool is genuinely saturated — every runner pod was verified to be executing a real job
(`WORKER` frames in the runner container log), and the ARC listener reports
`"assigned job"=6 decision=6 min=0 max=6`. This is **not** the stale-claim defect of #1152:
that was root-caused to ARC 0.9.3, fixed by the bump to 0.14.2, and 0.14.2 is what is running.

So arrival rate exceeds throughput, the backlog is a day deep, and `can-i-deploy` reports the
symptom rather than the cause: a service whose pact has not been published yet blocks as
`PENDING_BUILD`, which the reconcile tick then retries against the same saturated pool.

Two things follow that make this an architecture question rather than a tuning one. First,
raising the cap treats a structural mismatch as a capacity shortfall. Second — and this is
what makes the current shape expensive rather than merely slow — the work that *needs* the
cluster is a small fraction of the work that currently *runs* there.

## Decision

We will split the main-push per-service job in `_service-ci.yml` into two jobs:

1. **`build`** — compile, test, `quarkusBuild`, and consumer-pact generation, on
   `ubuntu-latest` for every event including main-push. Generated pacts are uploaded as a
   workflow artifact. GitHub-hosted runners are free for public repositories, so this work
   leaves the paid pool entirely.
2. **`contract`** — on `openbank-build`, downloads the artifact and performs only the work
   that requires cluster DNS: publishing consumer pacts to the broker and running provider
   verification.

`can-i-deploy` and the `record-deployment` path are unchanged: they continue to read broker
state, and the pact version for a SHA still lands from an in-cluster job. What changes is that
the 4–11 minute build no longer occupies one of six in-cluster slots.

We will additionally retire the `runners-warm` NodePool, whose justification has already
lapsed (see Consequences), and fund a higher `arc_max_runners` from that saving.

## Alternatives considered

- **Raise `arc_max_runners` alone (6 → 12).** Halves the drain time and is close to
  FinOps-neutral, because total runner-hours are conserved — the same work runs on more nodes
  for less wall-clock, not for more node-time. Rejected as the *primary* answer because it
  scales the pool with the fleet forever and leaves the free hosted capacity unused. Kept as
  an immediate lever, not as the design.
- **Give the Pact Broker a public ingress.** Would let the whole build run on hosted runners
  with no split. Rejected: it reverses ADR-0056 and puts an authenticated, internet-reachable
  surface in front of contract state for a CI-throughput reason.
- **Cancel superseded main-push lanes to shorten the queue.** Rejected outright: the per-SHA
  group exists precisely so that a cancelled lane cannot leave a service undeployed (#846).
  Cancelling is not a throughput fix, it is a silent loss of deploys.
- **Do nothing and let the reconcile tick absorb it.** Rejected: the tick re-drives blocked
  services against the same pool, so it converges only while arrival is below throughput —
  which is the condition that currently does not hold.

## Consequences

**Positive**

- The build leaves the paid pool. Measured 17.4 runner-hours/day of build work moves from
  spot Graviton nodes (m7gd.xlarge at $0.0566/h spot) to free hosted runners.
- Retiring `runners-warm` (#4317) moves 2 nodes of the same build work from **on-demand**
  m6gd.xlarge ($0.192/h, ~$280/month for two) to the `runners` spot pool
  (m7gd.xlarge $0.0566/h, ~$83/month) — **~$197/month** for identical throughput. Its stated
  rationale (keeping a `docker:dind` containerd cache warm to avoid NAT egress) was retired on
  2026-06-13, when the ECR pull-through cache made the image route in-VPC; `variables.tf`
  records that and sets `arc_min_runners = 0`, but the NodePool was left at `limits.cpu: 8`
  with `expireAfter: Never`. Its own cost comment also quotes an instance the pool can never
  select — "2× c6g.large on-demand = ~$3.72/day", where c6g.large has 2 vCPU and the pool
  requires ≥4.
- Those two together fund `arc_max_runners` 6 → 12 and still leave the platform cheaper than
  today, because raising concurrency does not raise total runner-hours.
- `PENDING_BUILD` stops being the fleet's default deploy state, without weakening
  `can-i-deploy` by a single check — the gate keeps asking exactly the question it asks now.

**Negative**

- GitHub's Free plan caps hosted jobs at **20 concurrent, account-wide**. Moving fleet builds
  onto that pool can starve the required PR checks that share it. The split therefore needs a
  concurrency governor, and `max-parallel` must be chosen against the 20 ceiling rather than
  against the ARC pool's aio limits as it is today.
- Provider verification runs inside the Gradle `test` task, not as a step of its own, so the
  `contract` job cannot be reduced to a `curl` the way consumer publishing can. It needs
  either the build artifact plus a test-only invocation, or a second compile in-cluster. The
  step timings below bound how much this can cost but do not resolve it, because provider
  verification is not separately observable in them — isolating it is the first task of the
  implementation.
- A two-job split adds an artifact hand-off between jobs, and an artifact that fails to upload
  becomes a new way for a pact never to be published. That failure must be loud, or it
  reproduces `PENDING_BUILD` with a cause that is harder to see.
- Retiring `runners-warm` removes on-demand capacity from the build path, so a spot
  interruption can now kill a main-push build mid-job. The `runners` pool already tolerates
  this for PR builds; main-push inherits that exposure.

## How much of the job actually needs the cluster

Step timings from 25 successful main-push builds on `openbank-build`, 2026-08-09:

| step | share of job |
|---|---|
| `Build + test` | 61–83% |
| `Generate Kover XML report` | 10.3–33.6%, mean ~22% |
| `Publish consumer pacts to broker` | **0–1 second** |

The step that requires cluster DNS is **free**: publishing a consumer pact is a `curl` to the
broker's `/contracts/publish`, and it costs 0–1 s in every sample. Everything expensive —
compile, test, and a coverage XML that is ~22% of every build — needs nothing from the
cluster at all. Coverage cannot simply be dropped from the main lane (it is the ratchet
baseline), but it does not need to be computed *in-cluster*, and today it is.

That is the quantitative case for this ADR: the in-cluster pool is scarce (6 slots, 42 queued
runs, p50 queue 85 min) and roughly 90% of what it computes has no in-cluster dependency. The
residual is provider verification, which is embedded in `test` and therefore not visible as
its own line above.

## Implementation phases

**Phase 1 — shipped (#4319, merged 2026-08-09T13:20:58Z), before this ADR was formally
accepted.** `arc_max_runners` 6→12, `runners-warm` retired. Re-measured same day, ~7h after
merge: queue 57 (was 42 at proposal time), in-progress 5 (was 6 of 6 — i.e. now *under* even
the old cap), oldest queued entry ~18h (was max 23.4h). The queue did not clear and at one
point *grew* to 58 during the re-measurement window. This is not evidence Phase 1 failed on
its own terms — throughput did increase (in-progress dropping below the old ceiling while the
queue still has entries means the pool is draining, just not fast enough against arrival) — it
is evidence that a capacity lever alone cannot out-run an arrival rate that already exceeds
6-12 slots' worth of throughput on a day with this much commit/PR/auto-deploy volume. Which is
exactly this ADR's original thesis in "Two things follow" above: doubling a scarce resource
delays saturation, it does not remove the dependency that makes it scarce.

**Phase 2 — specified here, not implemented.** Investigated on 2026-08-09 by reading
`_service-ci.yml` and the shared Gradle convention plugin
(`build-logic/src/main/kotlin/openbank.quarkus-service.gradle.kts`) rather than reasoning from
this ADR's text alone. Three findings change the shape of the work from "move some steps to
another job" to a real Gradle change:

1. **The Pact-property forwarding block is genuinely centralizable, and already almost
   duplicated correctly.** `tasks.withType<Test> { listOf("pactbroker.url", …9 keys…).forEach {
   key -> System.getProperty(key)?.let { systemProperty(key, it) } } }` is copy-pasted into 32
   individual `<service>/build.gradle.kts` files rather than living in the one convention
   plugin every service already applies. A first pass at hashing all 32 occurrences to confirm
   they are byte-identical before centralizing them returned **5 distinct hashes, not 1** — the
   extraction script's own crude `awk` boundary-matching was unreliable (closing-brace
   detection triggered early on at least one file), which is the "probe that lies by reporting
   almost-clean" pattern this repo's CLAUDE.md already documents at length, caught here before
   it produced a bad mechanical edit rather than after. **Do not centralize this without a
   tool that diffs each file's block individually and shows every difference** — do not trust
   a hash-of-32 rollup, prove each one.
2. **The actual isolation target is unambiguous.** Every provider-verification test class
   across the fleet ends in `ProviderVerificationTest` (confirmed: `grep -rl '@Provider('
   --include='*.kt'` → 26 services, every match's filename matches
   `*ProviderVerificationTest.kt`, split between `*PactFolderProviderVerificationTest`
   — no broker, always safe to run anywhere — and `*Pact(Broker)?ProviderVerificationTest`
   — the ones that need `pactbroker.url`). A Gradle `--tests "*ProviderVerificationTest"`
   filter targets exactly the right classes by name with no ambiguity.
3. **A `--tests` filter alone does NOT achieve the goal, and this is the reason Phase 2 is a
   Gradle change and not a workflow change.** `--tests` narrows which JUnit Platform tests
   *execute* within one invocation of the `test` task; it does not change what Gradle
   considers that task's *inputs* for its own up-to-date/cache-key computation. `-Dpactbroker.url`
   is already one of those tracked inputs (the #1009 hazard this file documents elsewhere), so
   the `contract` job's `test` invocation (broker URL set) is *always* a different cache key
   from the `build` job's (broker URL blank) — meaning `contract` cannot reuse `build`'s
   compiled output via the task-level cache no matter how the test selection is filtered, and
   would still pay compile + full dependency resolution before running even one filtered test.
   The in-cluster remote build cache (`GRADLE_REMOTE_CACHE_URL`) cannot rescue this either: it
   is reachable only from inside the cluster, so the hosted `build` job can never populate it
   for `contract` to read.

   **The actual fix is a separate Gradle test source set / JVM Test Suite**
   (`providerPactTest`, wired once into the shared convention plugin) whose own task has a
   narrower input set — the compiled main + provider-test classes only, not entangled with the
   general `test` task's other system properties — so it can be invoked independently
   (`./gradlew :service:providerPactTest -Dpactbroker.url=…`) without forcing a redundant full
   recompilation/retest of everything `build` already did. This is real Gradle build-logic
   surgery across a shared convention plugin (low fan-out: one file) feeding 26 services' test
   trees (real fan-out: the verification surface), and needs to be compiled and run for real,
   which needs a session with a Gradle/JVM toolchain — not available here.

**What Phase 2 buys, once built**, per the existing "How much of the job actually needs the
cluster" measurement: `contract` job shrinks from the full 4–11 minute build to roughly the
`providerPactTest` slice plus a 0–1s publish — the 61–83% (`Build + test`) and ~22%
(`Generate Kover XML report`) shares move to free hosted runners entirely, for every one of the
~26 services that has a provider contract. The GitHub Free-plan 20-concurrent-hosted-job
ceiling (see Consequences → Negative) becomes the binding constraint at that point, so the
concurrency governor named there is a Phase 2 prerequisite, not an afterthought.

## Compliance impact

No regulated data changes hands and no control is removed. `can-i-deploy` keeps asking the same
question against the same broker state, so the contract gate that guards money-path deploys is
unchanged in scope and in strictness — this ADR moves where the build executes, not what is
verified before a deploy. Two points a reviewer should confirm rather than assume: the
`contract` job must remain the only publisher of pact versions, so provenance of a contract
version stays in-cluster; and secrets (`PACT_BROKER_PASSWORD`) must stay scoped to that job
rather than being handed to the hosted `build` job, which is untrusted-by-default for this
purpose. Audit and evidence paths (`record-deployment`, fleet attestation) read broker and
registry state and are not touched.

## Notes

`runners-warm` does not self-heal, and the reason is worth stating because the obvious
explanation is wrong. Its nodes carry `expireAfter: Never` and are declared `WhenEmpty` /
`consolidateAfter: 5m`, so they should disappear when idle. They do not — but **not** because
disruption is disabled. The `10%` budget visible on the live object is Karpenter's default,
not something this repo authors, and Karpenter scales a percentage budget with round-up, so
10% of two nodes is one node, not zero; Karpenter 1.12.1 is observably disrupting nodes on
this cluster one at a time. The nodes persist because they are **busy**: runner pods carry
`karpenter.sh/do-not-disrupt: true`, and with the build backlog described above the pool is
almost never idle for five consecutive minutes. Retiring the pool is therefore a rate
question (on-demand vs spot for work that really is being done), not the reclamation of idle
capacity. Filed as #4317 so it can land without waiting for this ADR.

The first version of this ADR and of #4317 asserted the floor-to-zero mechanism, reasoning
from the manifest rather than from the running system. It is recorded here rather than
silently deleted, because the failure mode — inferring a mechanism from the shape of a config
line and never asking the cluster — is the one this repo has already paid for elsewhere.
