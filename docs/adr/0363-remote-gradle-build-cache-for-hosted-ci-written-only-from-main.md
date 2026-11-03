---
date: 2026-11-03
decision-status: proposed
delivery-status: planned
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ci, supply-chain, capacity]
summary: "Hosted service builds read a shared remote Gradle build cache outside AWS; only main-push builds may write it, through a credential in a main-only environment, so unchanged modules come from cache instead of a full rebuild."
---

# ADR-0363 — Remote Gradle build cache for hosted CI, written only from main

## Context

ADR-0043 decided that one build cache is shared pool-wide, through an opt-in remote
`gradle/build-cache-node` wired by `GRADLE_REMOTE_CACHE_URL`. That node runs in-cluster and
only the ARC runners can reach it. ADR-0250 then moved compile and test to GitHub-hosted
runners, which cannot reach the cluster. So the service build has had no remote cache at all
since then. ADR-0043 is not wrong. Its premise, that builds run where the node is, stopped
holding.

What the hosted build gets today is the GitHub Actions cache, written by one elected job
(`check-gradle-cache-writers.py`). That job lints the fleet. It does not compile or test
services. Measured on 2026-11-03 against PR #14732, which changed only `.github/` scripts and
no Kotlin, the service build jobs still re-executed almost everything:

| Run of `openbank-aml-service:build` | Wall time | Tasks from cache |
|---|---|---|
| Hosted CI, PR #14732 | 4m51s Gradle | 6 of 66 |
| Local, cold | 2m22s | 14 of 62 |
| Local, populated cache, different checkout path | 2m02s | 27 of 62 (`test` re-ran) |
| Local, populated cache, same checkout path | **11s** | `compileKotlin`, `compileTestKotlin`, `test`, ktlint all FROM-CACHE |

CI always checks out to the same path, so the last row is the CI case. The different-path row
re-runs `test` because the test task takes absolute paths as inputs (`OPENBANK_TEST_EVIDENCE_DIR`,
`pact.rootDir` in `openbank.quarkus-service.gradle.kts`). That does not matter on hosted runners.

Why now: a PR that touches `.github/` or a library fans out to the whole fleet (~86 build jobs,
12 at a time, `generate-service-ci-jobs.py: LANES`). That is ~45 minutes per run. A strict-lane
PR re-runs it after every update. The merge queue spent the evening of 2026-11-03 waiting on
exactly that.

## Decision

We will run a remote Gradle build cache that GitHub-hosted runners can reach, and wire the
service build to it with an asymmetric trust split:

1. **Read for every build.** `_service-ci.yml` `build` (and its generated PR projection) sets
   `GRADLE_REMOTE_CACHE_URL` and a read-only credential, so PR builds resolve unchanged
   modules from cache.
2. **Write only from main.** `GRADLE_REMOTE_CACHE_PUSH=true` and the write credential exist only
   in a job that runs on `push` to `main`, bound to a GitHub Environment restricted to the `main`
   branch. A pull request, including a same-repo one, can never obtain the write credential,
   because environment secrets are released only to jobs running on the allowed branch.
3. **Outside AWS.** The node runs on a small dedicated host outside the AWS account, so cache
   traffic from GitHub-hosted runners costs no NAT, ALB or AWS egress. Its URL and credentials
   live only in repository secrets, never in tracked files.
4. **Fail open.** An unreachable node makes Gradle log a warning and build normally. The cache
   can make a build faster. It can never be the reason a build fails.

## Alternatives considered

- **Expose the in-cluster node (ADR-0043) through an ingress.** Reuses existing infra. Rejected:
  hosted runners would pull cache entries out of AWS, paying egress per GB plus a load balancer.
  It would also put a write-capable endpoint on the internet next to production workloads. The
  finops and blast-radius costs both go the wrong way.
- **Make the GitHub Actions cache the build cache** (more writers, larger entries). Rejected: the
  repo's Actions cache already sits over its 10 GB ceiling (#3299, 11.7 GB measured). Adding
  per-service build output would only speed up LRU eviction, which is the problem
  `check-gradle-cache-writers.py` exists to contain.
- **Narrow the fan-out instead** (build a canary subset for CI-only changes). It cuts runs, but it
  weakens what a green PR proves. It is a complement to revisit once the cache makes full
  fan-outs cheap, not a replacement.
- **Split into many repositories.** Rejected: a library change becomes one version bump PR per
  consuming repository, each with its own queue. The cross-service gates (rules.yaml, pacts, OPA
  bundles) lose the single tree they check. The cost moves around instead of shrinking.

## Consequences

**Positive**
- A service that a PR does not change builds in about the job's fixed overhead (~1–2 min)
  instead of ~5–7 min. A full fan-out drops from ~45 min towards ~10–15 min (estimate; see the
  delivery check for how it is measured).
- Less contention for the 20-slot hosted cap, which ADR-era runner work (Mac mini, burst hosts)
  could only add capacity to.

**Negative**
- The cache becomes an integrity-relevant component, as ADR-0043 already noted. A poisoned
  entry would be served to every later build. Mitigation is the write split: only code already
  merged to `main` can write, so poisoning the cache requires the same access as poisoning `main`.
- New host to run, patch and pay for (a few EUR per month). Losing it costs speed only.
- Test results become cacheable across builds. A test whose behaviour depends on something
  Gradle does not see as an input (wall-clock time, an external service) can be served a stale
  pass. That is standard Gradle semantics and already true for local builds. Such tests should
  declare the input or opt out with `outputs.cacheIf { false }`, as the pact tasks already do.

**Neutral**
- `check-gradle-cache-writers.py` governs the GitHub Actions cache only. It is unaffected.

**Enforcement.** A new enforced gate, `remote-build-cache-write-scope`, will fail any job that is
reachable from `pull_request` or `pull_request_target` and sets `GRADLE_REMOTE_CACHE_PUSH`
to anything but `false`, or references the write credential. Until it lands, `delivery-status`
stays `planned`.

### Delivery check

- `grep -c GRADLE_REMOTE_CACHE_URL .github/workflows/_service-ci-pr.yml` prints a non-zero count.
- `grep -n 'GRADLE_REMOTE_CACHE_PUSH' .github/workflows/_service-ci-pr.yml` prints nothing, or only
  `false`.
- `.github/gates/gates.yaml` has id `remote-build-cache-write-scope` with `mode: enforced`.
- On a PR that changes only `.github/`, the service build logs show `test` and `compileKotlin`
  as `FROM-CACHE` for at least 80 % of the built modules (`grep -c 'Task :.*:test FROM-CACHE'`
  across the run's build jobs).

## Compliance impact

- PCI DSS: not applicable — CI build acceleration; no cardholder data reaches build cache entries.
- DORA: not applicable — no change to ICT incident, resilience or third-party obligations of the
  bank's production services; the host is a developer-tooling dependency that fails open.
- GDPR: not applicable — cache entries are compiled code and test results, no personal data.
- PSD2: not applicable — no change to payment or access-to-account surfaces.
- CNB: not applicable — no change to reported or supervised processes.

## References

- ADR-0043 — CI performance model (the shared remote build cache this extends to hosted runners)
- ADR-0250 — Decouple the main-push service build from the Pact Broker runner pool (moved builds to hosted)
- ADR-0030 — governance; the build cache as an integrity-relevant component
- #3299 — GitHub Actions cache over its 10 GB ceiling
