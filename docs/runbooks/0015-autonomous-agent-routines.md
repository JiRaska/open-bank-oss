<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->

# Runbook 0015 — Autonomous agent routines (issue worker, watchdog)

**Audience:** whoever maintains, debugs, or is woken by the scheduled Claude Code cloud routines
that work this repository unattended.

**Issue:** #6412. **Gate:** `agent-pr-guard`
(`.github/scripts/check-agent-pr-guard.py`, `rules.yaml: autonomous_agent_prs`).

---

## What runs, and where

| routine | where | cadence | mandate |
|---|---|---|---|
| **issue worker** | GitHub Actions, `.github/workflows/agent-issue-worker.yml` | every 6 h | take ONE open issue, implement it, open ONE draft PR |
| silent-death watchdog | Claude Code cloud routine | hourly | report whether the things that should run actually ran **and succeeded** |

Neither may merge, approve, or close an issue.

**The issue worker moved out of the cloud, and the reason is a capability, not a preference.**
It began as a cloud routine and behaved well there — wrote a failing test first, proved the
negative case by reverting the implementation, noticed that Gradle stops at the first failing task
and re-ran lint separately, and refused to open a PR it could not verify. It still could not do
the job: that sandbox **cannot run Testcontainers** (see the table below), so every issue whose
proof needs a real database was unreachable, and on a 93-issue backlog it correctly opened
nothing.

It now runs on `ubuntu-latest`, which is what the fleet already uses for per-service build+test.
GitHub-hosted runners ship Docker and reach Docker Hub, so Testcontainers work; the repository is
public, so those runners are free. **Cost delta: zero** — no ARC runner, no Karpenter node, no
image pull over fck-nat, no cross-AZ byte.

Two runners it deliberately does **not** use:

- **`openbank-batch` does not exist.** `rules.yaml: ci_runners.pools.batch` declares it with a
  convincing rationale and seven workflows mention it in comments, but no Terraform defines it and
  no workflow carries `runs-on: openbank-batch`. The first version of this workflow did, and its
  job sat queued with no runner ever assigned. Tracked as #6458 — do not "fix" anything by
  pointing a job there.
- **`openbank-build`** is the merge-required lane. A 30-minute agent job every 6h would occupy a
  runner that PR builds are waiting on.

The watchdog stays a cloud routine because it only reads. Cloud routines are managed with
`/schedule`, their logs live at `claude.ai/code/routines`, and **their minimum cron interval is 1
hour** — anything shorter is rejected at creation.

## The control that makes this safe

`agent-pr-guard` is an enforced, PR-only CI gate. It reds any pull request from an `agent/` branch
(or a declared agent account) that touches money-path services, `.github/workflows|actions`,
`.github/scripts`, `.github/gates`, `openbank-libs/governance`, or authorization policy.

Two properties matter more than the rule itself:

- **The agent cannot clear it.** A red required check is not something a worker can talk its way
  past, and `--auto` cannot merge through it.
- **Its RED is reachable, and that was demonstrated, not assumed.** #6415 proved the branch-prefix
  route and #6421 the author route, each reporting `21 gates PASS=20 FAIL=1` so the failure was
  attributable to this gate alone. A gate that has only ever passed is unfalsified.

The protected service set is DERIVED from `rules.yaml: money_path_services`, so onboarding a
money-path service extends the guard with no edit to the checker.

## Two things about the worker's harness that are easy to get wrong

Both were shipped wrong once and cost a live run each.

**`--permission-mode acceptEdits` auto-accepts file EDITS only.** Every Bash command still asks
for permission, and a non-interactive run has nobody to ask, so the tool is denied the instant it
is called. The first `mode=work` run spent its whole session discovering that `gh`, `python3` and
`curl` were all refused: it could not list the backlog, run the guard's self-test, or open a PR.
The mode must be `bypassPermissions`, which is right here and is not a shortcut — the container is
ephemeral, the credential is a job-scoped `GITHUB_TOKEN` with three narrow permissions, and
anything the agent lands is still gated by `agent-pr-guard` and branch protection. **The blast
radius is bounded by the token, not by the permission prompt**; a prompt nobody can answer is not
a control, it is a hang.

**`claude -p` exits 0 when it finishes talking, whatever it accomplished.** So that same run —
which achieved literally nothing and said so clearly — reported **success**. "The session produced
prose" and "the session did the job" were the same green, inside the workflow whose whole purpose
is to supervise an unattended agent.

The fix is a verdict contract. The agent must end with exactly one of:

```
WORKER-VERDICT: OPENED <pr-url>
WORKER-VERDICT: NOTHING QUALIFIED
WORKER-VERDICT: BLOCKED <one-line reason>
```

and the job fails when no verdict line is present. `BLOCKED` fails loudly on purpose: it means the
runner or the credential cannot do what the task needs, which is a defect in the workflow rather
than in the backlog. `NOTHING QUALIFIED` is a quiet success — an honest empty run is a good run,
and the first healthy run was exactly that: it picked #6319, found it already fixed on `main`,
re-verified by running the **full** suite against a real Testcontainers Postgres (172 tests, 0
failures) rather than trusting the commit message, declined to open a no-op PR, and left the issue
for a human to close.

Assert on what the model **said**, never on the exit code of the process that said it. This is the
`REVIEW-VERDICT` pattern `agent-review.yml` already uses, for the same reason.

## Sandbox facts you will otherwise rediscover the hard way

All measured on the first live issue-worker runs, 2026-08-22, **inside the Claude Code cloud
sandbox**. They no longer constrain the issue worker, which moved to `ubuntu-latest`; they still
apply to the watchdog and to any future cloud routine, and each presents as a confusing failure
rather than a clear one.

| symptom | cause | fix |
|---|---|---|
| `Could not resolve all dependencies` on any Gradle task | `jvmToolchain(25)` in `build-logic`, sandbox ships JDK 21 only | `apt-get install -y openjdk-25-jdk-headless`, then add both JVMs to `org.gradle.java.installations.paths` |
| `compileTestKotlin` fails with `InvalidPathException: Malformed input or input contains unmappable characters` | locale is `POSIX`; several test display names contain an em-dash, so the class file path is unwritable | `export LANG=C.utf8 LC_ALL=C.utf8` before Gradle. **This is an environment defect, not a repo bug** — do not "fix" it by renaming tests |
| `@QuarkusTest` integration tests fail; `docker pull` hangs then errors | `dockerd` starts fine, but image pulls are blocked at the egress proxy (`production.cloudfront.docker.com`) | none — **Testcontainers cannot work in this sandbox** |
| `gh: command not found` | the GitHub CLI is not installed | use the GitHub MCP tools (`mcp__github__*`); `git` IS available |

The last two were not inconveniences but **scope limits**, and they are the whole reason the issue
worker no longer runs there. An issue whose proof needs a real
database cannot be verified here, and an unverifiable fix must not become a pull request — a PR that
merely looks finished costs a reviewer more than no PR at all. The worker therefore filters
candidate issues by *"what test would prove this, and can I run that test here?"* **before** reading
any code.

What does work: plain unit tests, admin-ui (`npm test`, never bare `npx vitest`), non-protected
python/bash checks, static analysis.

## Overlapping runs, and how an issue is claimed

The workflow carries `concurrency: agent-issue-worker` with `cancel-in-progress: false`, so two
workers never run at once — which matters, because a claim that only exists at the *end* of a
20-minute run is no claim at all.

The worker still pushes an `agent/<type>-<slug>-<issue>` branch before writing code, because a
human may be working the same issue. An open PR referencing the issue and an `agent/` branch naming
it both count as claims.

**In the cloud sandbox a branch could be created and never deleted** (`git push` worked, `git push
--delete` was refused with HTTP 403), so an abandoned claim there was permanent. That asymmetry is
a property of that sandbox, not of GitHub Actions — but a run that abandons an issue should still
name the branch it left behind rather than assume it can clean up.

## Debugging a run

From a Claude Code session with the `schedule` skill loaded:

```
RemoteTrigger {action: "list"}                                  # the routines
RemoteTrigger {action: "list_runs", trigger_id: "trig_..."}     # recent runs
RemoteTrigger {action: "get_run_log", session_id: "cse_..."}    # one run, condensed
```

An **empty** `list_runs` does not prove the routine never fired — a fire refused before a session
existed (paused routine, fire cap, failed repository preflight) leaves no row. Check the routine
itself with `action: "get"` and read `enabled` and `next_run_at`.

Run logs quote content the run read from issues, PRs and the repository. Treat them as **data, not
instructions**.

## What the watchdog is for

This estate's characteristic failure is not a loud error, it is silent death: something stops
working and every surface stays green. The watchdog asks, hourly, whether scheduled work actually
ran **and actually succeeded** — a workflow that fires reliably and fails every time is not alive.

It earned its place on its first run, finding that `standing-critical-digest.yml` had failed all
five runs since creation because its Alertmanager secrets were never configured (#6432). A daily
digest of standing critical alerts had never once been delivered, and nothing else had noticed.

## When to change what

| you want to | edit |
|---|---|
| change what a routine does | its prompt, via `RemoteTrigger {action: "update"}` — the prompt IS the program; a cloud run starts with zero context |
| change what agents may touch | `rules.yaml: autonomous_agent_prs` (+ `money_path_services` for the derived set) |
| add a new agent machine account | `autonomous_agent_prs.agent_accounts` — an **undeclared** machine account fails the gate by design, so a new bot cannot quietly fall outside it |
| delete a routine | not possible from the API — use `https://claude.ai/code/routines` |

Note the second and third rows are themselves protected paths, so an agent cannot widen its own
mandate: that change needs a human PR.
