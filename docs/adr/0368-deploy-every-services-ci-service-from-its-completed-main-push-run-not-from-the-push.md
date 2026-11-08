---
date: 2026-11-08
decision-status: proposed
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ci, gitops, api-contract]
summary: "Auto deploy deploys every service Services CI builds from that run's successful main-push completion, when its Pact version exists, instead of racing it from the push; only self-built images stay on the push lane."
followup: "#15294 — DEPLOY_AFTER_SERVICES_CI_ENABLED stays off until the owner enables it; the delivery check below then runs against one week of Auto deploy runs."
---

# ADR-0368 — Deploy every Services CI service from its completed main-push run, not from the push

## Context

ADR-0092 placed `can-i-deploy` between `build-push` and `gitops-pr` in `auto-deploy.yml`.
The Pact version it asks about for a commit is published by that commit's main-push
**Services CI** `contract` job. ADR-0250 moved that build onto free hosted runners to shorten
the queue in front of it. Neither ADR decides **when** a deploy may start relative to that
publication. ADR-0358 and ADR-0362 decide where image pins live and that a pin moves only
forward. None of them covers trigger ordering.

Today Auto deploy runs on `push` to main, in parallel with Services CI for the same commit.
`can-i-deploy` waits up to 3 × 60 s for the commit's Pact version. Measured over 2026-11-01..08:

| Measure | Value | Source |
|---|---|---|
| Auto deploy runs that failed | 24 | `gh run list --workflow "Auto deploy" --status failure --created '>=2026-11-01'` |
| …of which `PENDING_BUILD` (no Pact version for the commit yet) | 13, every one triggered by `push` | `##[error]` lines of each run's `--log-failed` |
| …`REGRESSION` / `UNVERIFIED` (a real contract verdict) | 5 | same |
| Services CI main-push conclusions | 329 success, 35 failure | `gh run list --workflow services-ci.yml --branch main --event push` |
| Services CI main-push duration (last 30 successes) | p50 11 min, p90 32 min | same, `updatedAt − createdAt` |

The 3-minute wait cannot cover an 11–32-minute build. The pipeline itself labels the result
self-clearing, but what clears it is the reconcile tick, which runs every 3 hours
(`cron: "23 */3 * * *"`). So the most common Auto deploy failure is also a deploy delay of up
to 3 hours. Every such run is red, and so trains people to ignore the one signal that should
mean a contract regression.

PR #13872 already built the mechanism the other way round, as a disabled pilot: a
`workflow_run` lane that starts when Services CI completes successfully. It validates the
event's provenance, checks out the producer commit only after an ancestry check against main,
and refuses rather than falls back to a moving `latest/main` Pact version. The pilot was
limited to context-service and has no ADR.

## Decision

We will deploy every service that Services CI builds from the successful completion of that
service's main-push Services CI run, not from the push.

- **Partition.** With `DEPLOY_AFTER_SERVICES_CI_ENABLED=true`, the push lane keeps only the
  services in `SELF_BUILT_SERVICES` (images Services CI never builds). The `workflow_run` lane
  takes every other service the ordinary change detector selected for that commit. The
  partition never adds a service. It is the pilot's `context-deploy-handoff.py select`, given
  the push lane as data instead of a hard-coded single component.
- **Gate.** `can-i-deploy` is unchanged and still asks about the exact commit. Because the lane
  starts after publication, `PENDING_BUILD` stops being a scheduling artefact.
- **Partial verdicts.** As on reconcile (#846), the lane deploys the subset that
  `can-i-deploy` proved (`gate_ran == 'true'` and a non-empty `deployable`) even when another
  service in the same run is blocked. The pilot's stricter rule, which deployed nothing unless
  the whole gate job succeeded, was right for one service and wrong for a fleet: one regression
  would hold back every service in the commit.
- **Failed CI.** A failed Services CI run starts no deployment, as in the pilot. Those services
  are re-offered by the reconcile tick, exactly as a `PENDING_BUILD` is today.
- **Activation.** The switch is a repository variable, off by default. Merging this ADR changes
  nothing until the owner sets it. Setting it to anything other than `true` restores push
  deployment of everything for new runs.

## Alternatives considered

- **Lengthen the `can-i-deploy` wait to the Services CI p90 (~32 min).** It removes most
  `PENDING_BUILD` reds. Rejected: it holds an in-cluster deploy runner idle for half an hour per
  commit, on the pool ADR-0250 shows to be the scarce resource, and it still races the p99.
- **Report `PENDING_BUILD` as neutral, not red, and keep relying on reconcile.** It makes the
  runs green. Rejected: it hides the symptom while keeping the up-to-3-hour deploy delay.
- **Run reconcile more often (for example every 15 min).** It shortens the delay. Rejected: it
  adds a scheduled fleet scan ~12× per 3 hours and still deploys a fixed interval after the
  build instead of right after it.
- **Keep the pilot limited to context-service.** Rejected: the race exists for every service
  with a Pact. Context-service was chosen for build reuse, not because only it races.

## Consequences

**Positive**
- A service deploys when its own build and contract publication finish, not up to 3 hours
  later at the next reconcile tick.
- `can-i-deploy` red means a contract verdict (`REGRESSION`/`UNVERIFIED`) again, not "too early".
- No push-lane deploy build runs for a commit whose build Services CI is still producing.

**Negative**
- Deploy latency becomes Services CI duration (p50 11 min) instead of the push lane's own build.
  For a commit that would have deployed cleanly from the push lane, this is slower.
- A failed Services CI run (35 of 364 commits measured) now deploys none of its services until
  reconcile, including services unrelated to the failure. Today the push lane would deploy those.
- A Services CI run that finishes after a newer commit's run produces an older deploy. The
  enforced `deploy-pin-monotonic` gate (ADR-0362) refuses a backwards pin, so it is closed
  instead of applied.

**Neutral**
- Gate: `.github/scripts/test-context-deploy-handoff.py`, run by `ci.yml`. It executes the
  workflow's real partition block and the real `gitops-pr` condition. It fails when the push
  lane is not passed, and when the strict whole-job rule is restored; both were checked by
  reintroducing the defect.

### Delivery check

After the variable is enabled for 7 days, with `<since>` the enable date:

```
gh variable get DEPLOY_AFTER_SERVICES_CI_ENABLED -R JiRaska/open-bank-oss          # expect: true
gh run list -R JiRaska/open-bank-oss --workflow "Auto deploy" --event push --status failure \
  --created ">=<since>" --json databaseId --jq '.[].databaseId'                   # then, per id:
gh run view <id> -R JiRaska/open-bank-oss --log-failed | grep -c 'PENDING_BUILD'  # expect: 0 for every id
```

The before baseline is 13 `PENDING_BUILD` runs in 7 days (2026-11-01..08). Count `workflow_run`
lane runs as well: a lane that never runs leaves no failure to count, so the second command
alone could pass while nothing deploys. Expect roughly one `workflow_run` lane run per
successful Services CI run that changed a deployable service.

## Compliance impact

- PCI DSS: not applicable — no cardholder data or card processing changes; this is CI scheduling.
- DORA: not applicable — no change to ICT risk controls; the contract gate and change approval are unchanged.
- GDPR: not applicable — no personal data is processed.
- PSD2: not applicable — no payment or access-interface behaviour changes.
- CNB: not applicable — no reporting or prudential surface changes.

## References

- ADR-0092 (Pact Broker and `can-i-deploy`), ADR-0250 (main-push build on hosted runners),
  ADR-0358 and ADR-0362 (deploy pins), PR #13872 (context-service pilot).
- `docs/ci/throughput-optimization.md` — the handoff's provenance rules.
- Issue #846 — a red gate still deploys the proven subset.
