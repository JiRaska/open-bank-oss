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

Two routines run in Anthropic's cloud — **not** on anybody's laptop, and not in GitHub Actions.
Each fires on a cron, clones this repository into a fresh sandbox, does one bounded job, and exits.

| routine | cadence | mandate |
|---|---|---|
| issue worker | every 2 h | take ONE open issue, implement it, open ONE draft PR |
| silent-death watchdog | hourly | report whether the things that should run actually ran |

Neither may merge, approve, or close an issue. Both are managed from a Claude Code session
(`/schedule`), and their run logs live at `claude.ai/code/routines`.

**The minimum cloud cron interval is 1 hour**; anything shorter is rejected at creation.

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

## Sandbox facts you will otherwise rediscover the hard way

All measured on the first live issue-worker run, 2026-08-22. The sandbox is **not** ready for this
repository out of the box, and each of these presents as a confusing failure rather than a clear one.

| symptom | cause | fix |
|---|---|---|
| `Could not resolve all dependencies` on any Gradle task | `jvmToolchain(25)` in `build-logic`, sandbox ships JDK 21 only | `apt-get install -y openjdk-25-jdk-headless`, then add both JVMs to `org.gradle.java.installations.paths` |
| `compileTestKotlin` fails with `InvalidPathException: Malformed input or input contains unmappable characters` | locale is `POSIX`; several test display names contain an em-dash, so the class file path is unwritable | `export LANG=C.utf8 LC_ALL=C.utf8` before Gradle. **This is an environment defect, not a repo bug** — do not "fix" it by renaming tests |
| `@QuarkusTest` integration tests fail; `docker pull` hangs then errors | `dockerd` starts fine, but image pulls are blocked at the egress proxy (`production.cloudfront.docker.com`) | none — **Testcontainers cannot work in this sandbox** |
| `gh: command not found` | the GitHub CLI is not installed | use the GitHub MCP tools (`mcp__github__*`); `git` IS available |

The last two are not inconveniences, they are **scope limits**. An issue whose proof needs a real
database cannot be verified here, and an unverifiable fix must not become a pull request — a PR that
merely looks finished costs a reviewer more than no PR at all. The worker therefore filters
candidate issues by *"what test would prove this, and can I run that test here?"* **before** reading
any code.

What does work: plain unit tests, admin-ui (`npm test`, never bare `npx vitest`), non-protected
python/bash checks, static analysis.

## Overlapping runs, and how an issue is claimed

The first run took **20+ minutes** — most of it environment setup and Gradle. With a 2-hour cadence
that is not yet an overlap, but the margin is thinner than it looks, and a claim that only exists at
the *end* of a run is no claim at all.

So the worker pushes an empty `agent/<type>-<slug>-<issue>` branch **immediately after picking**,
before writing any code. An open PR referencing the issue and an `agent/` branch naming it both
count as claims; a run that abandons its issue deletes its claim branch before exiting.

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
