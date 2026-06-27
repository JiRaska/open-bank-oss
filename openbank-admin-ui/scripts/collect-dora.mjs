// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Derive DORA delivery metrics (Deployment Frequency + Lead Time for Changes)
// from git history and bake them into `dora.json` at build time. The admin-ui is
// a READ-ONLY consumer (ADR-0029 rule #3; mirrors collect-test-results.mjs /
// collect-aws-costs.mjs): the pod holds NO GITHUB_TOKEN and makes no live API
// call — the metric is derived-from-code in CI and served from the snapshot
// (src/app/api/devops/dora/route.ts). See ADR-0061.
//
// Source of truth = squash-merges to the trunk (each PR -> main -> the deploy
// pipeline is one shipped change, ADR-0053/0060), read off git's first-parent
// history:
//   * Deployment frequency = first-parent commits in the last `--days` window.
//   * Lead time for changes = median(committerDate - authorDate) over the last
//     `--window` first-parent commits — authored -> landed-on-trunk. (A squash
//     keeps the original author date and stamps the merge as the committer date,
//     so this is the time from "code written" to "shipped".)
// Change Failure Rate and MTTR are NOT derived here — they come from reverts/
// incidents and the ICT incident register respectively (ADR-0061 phases 2-3).
//
// Honest by construction: outside a git checkout (or on failure) we write an
// `available:false` snapshot and the page degrades calmly — never a fabricated
// number.
//
// Usage: node scripts/collect-dora.mjs [--out <file>] [--days <n>] [--window <n>]

import { execFileSync } from 'child_process'
import { writeFileSync } from 'fs'

const args = process.argv.slice(2)
const getArg = (flag, dflt) => {
  const i = args.indexOf(flag)
  return i >= 0 && args[i + 1] ? args[i + 1] : dflt
}
const OUT = getArg('--out', 'dora.json')
const DAYS = parseInt(getArg('--days', '30'), 10)
const WINDOW = parseInt(getArg('--window', '50'), 10)
const now = new Date()

const git = (gitArgs) =>
  execFileSync('git', gitArgs, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] })

const median = (xs) => {
  if (!xs.length) return null
  const s = [...xs].sort((a, b) => a - b)
  const m = Math.floor(s.length / 2)
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2
}
// conventional-commit scope: `type(scope): subject` -> scope, else type, else '—'
const scopeOf = (subject) =>
  subject.match(/^[a-z]+\(([^)]+)\)!?:/)?.[1] ?? subject.match(/^([a-z]+)!?:/)?.[1] ?? '—'

let report
try {
  git(['rev-parse', '--is-inside-work-tree'])

  // Deployment frequency: first-parent commits in the window.
  const since = `${DAYS} days ago`
  const freqRaw = git(['log', '--first-parent', `--since=${since}`, '--pretty=format:%H'])
    .split('\n').filter(Boolean)
  const deploymentCount = freqRaw.length

  // Lead time + recent deployments: the last WINDOW first-parent commits.
  // %ct committer (= merge/ship) epoch, %at author (= written) epoch, %s subject.
  const recentRaw = git(['log', '--first-parent', `-n${WINDOW}`, '--pretty=format:%ct\t%at\t%s\t%h'])
    .split('\n').filter(Boolean)
    .map((line) => {
      const [ct, at, ...rest] = line.split('\t')
      const subject = rest.slice(0, -1).join('\t')
      const sha = rest[rest.length - 1]
      return { ct: Number(ct), at: Number(at), subject, sha }
    })

  // Lead time from git alone is only meaningful on a non-squash trunk: a GitHub
  // squash-merge collapses author==committer date, so ct-at is ~0. When the
  // trunk is squash-merged (median ~0), report null with a reason — lead time is
  // a Phase-2 metric derived from deploy-event/commit correlation (ADR-0061),
  // not a fabricated 0.
  const leadHours = recentRaw
    .map((c) => (c.ct - c.at) / 3600)
    .filter((h) => h >= 0)
  const m = median(leadHours)
  const leadTimeHours = m != null && m > 0.05 ? m : null
  const leadTimeReason = leadTimeHours == null
    ? 'trunk is squash-merged (author==merge date); needs deploy-event correlation (ADR-0061 phase 2)'
    : null

  const recentDeployments = recentRaw.slice(0, 10).map((c) => ({
    date: new Date(c.ct * 1000).toISOString(),
    service: scopeOf(c.subject),
    sha: c.sha,
  }))

  report = {
    available: true,
    source: 'git-first-parent',
    windowDays: DAYS,
    collectedAt: now.toISOString(),
    deploymentCount,
    deploymentFrequencyPerDay: deploymentCount / DAYS,
    leadTimeHours,
    leadTimeReason,
    recentDeployments,
  }
  console.warn(`[collect-dora] ${deploymentCount} deployments / ${DAYS}d, median lead ${leadTimeHours?.toFixed(1)}h → ${OUT}`)
} catch (e) {
  const msg = e instanceof Error ? e.message : String(e)
  report = {
    available: false,
    reason: 'not a git checkout or git unavailable',
    source: 'git-first-parent',
    windowDays: DAYS,
    collectedAt: now.toISOString(),
    deploymentCount: 0,
    deploymentFrequencyPerDay: null,
    leadTimeHours: null,
    recentDeployments: [],
  }
  console.warn(`[collect-dora] unavailable (${msg.slice(0, 120)}); wrote available:false → ${OUT}`)
}

writeFileSync(OUT, JSON.stringify(report, null, 2) + '\n')
