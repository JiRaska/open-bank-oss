// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Derive the CI gate estate's health from the GitHub Actions API and bake it into
// `gate-health.json` at build time — same pattern as collect-dora.mjs (ADR-0061):
// a CI-time collector calls the API with a token scoped to THIS build job only
// (`permissions: actions: read` in admin-ui-deploy.yml), writes a snapshot, and
// the admin-ui pod that serves it at runtime holds no token at all. See ADR-0255.
//
// Two tiers of data, at two different costs:
//   * SHARD timing/pass-rate over the last `--runs` completed `ci.yml` runs — the
//     Jobs API alone, one call per run, no artifact download. Cheap, always
//     available for as many runs as GitHub keeps run metadata (much longer than
//     artifact retention).
//   * Per-GATE detail (id-level status, subjects, self-test, budget) — requires
//     downloading and unzipping the `gate-results-<group>` artifact `run-gates.py
//     --json` writes and ci.yml uploads. Bounded to `--gate-detail-runs` (default
//     3): artifacts expire (this repo has not overridden GitHub's 90-day default,
//     but a few runs are enough for "current state + is this flaky right now"
//     without paying for a long backfill on every deploy). Missing/expired
//     artifacts degrade that run to shard-only data, never a crash.
//     Lowered 5 -> 3 on 2026-09-04. Each detail run costs 1 artifact listing plus
//     one download per shard, and the shard count went 8 -> 3 (#6257), so 5 runs
//     is 20 requests where 3 is 12. The installation API quota — 1000/hour, shared
//     by every workflow — was exhausted at 08:01 on 2026-09-03 and took Trivy's
//     SARIF upload and dependency-review down fleet-wide, including on main.
//
// Honest by construction, like every other collector in this directory: outside
// a token, or on any API failure, this writes `available:false` and a reason —
// never a fabricated number.
//
// Usage: node scripts/collect-gate-health.mjs [--out <file>] [--runs <n>]
//                                              [--gate-detail-runs <n>]

import { execFileSync } from 'child_process'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'fs'
import { tmpdir } from 'os'
import path from 'path'

const args = process.argv.slice(2)
const getArg = (flag, dflt) => {
  const i = args.indexOf(flag)
  return i >= 0 && args[i + 1] ? args[i + 1] : dflt
}
const OUT = getArg('--out', 'gate-health.json')
const RUNS = parseInt(getArg('--runs', '20'), 10)
const GATE_DETAIL_RUNS = parseInt(getArg('--gate-detail-runs', '3'), 10)
// A build-time observability collector must degrade, never strand a deployment
// behind one unavailable GitHub API connection. Keep this bounded and allow a
// runner-specific override for diagnosed transient network conditions.
const REQUEST_TIMEOUT_MS = Math.max(1_000, Math.min(30_000,
  parseInt(process.env.OPENBANK_GATE_HEALTH_TIMEOUT_MS || '5000', 10) || 5000))
const now = new Date()

const TOKEN = process.env.GITHUB_TOKEN || ''
const REPO = process.env.GITHUB_REPOSITORY || 'JiRaska/open-bank-oss'
const API = 'https://api.github.com'

async function gh(pathname, opts = {}) {
  const res = await fetch(`${API}${pathname}`, {
    ...opts,
    signal: opts.signal ?? AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    headers: {
      Authorization: `Bearer ${TOKEN}`,
      Accept: 'application/vnd.github+json',
      'X-GitHub-Api-Version': '2022-11-28',
      ...(opts.headers || {}),
    },
  })
  if (!res.ok) {
    throw new Error(`GET ${pathname} -> ${res.status} ${res.statusText}`)
  }
  return res
}

async function ghJson(pathname) {
  return (await gh(pathname)).json()
}

// One HTTP round trip per artifact; `unzip` shelled out to rather than an npm dependency,
// same "no new dependency" convention collect-dora.mjs already keeps (execFileSync + fs
// only). Returns null on ANY failure (expired artifact, no `unzip` on PATH, corrupt zip) —
// the caller must treat that run as shard-only, not crash the whole collector over one gap.
async function downloadArtifactJson(artifact, workdir) {
  try {
    // CodeQL js/http-to-file-access: the GitHub Actions API is a trusted, authenticated source
    // (not a "download from evil.com" backdoor pattern) and the zip is only ever unzipped and
    // read back as JSON, never executed — but the path built from `artifact.id` below is worth
    // hardening on its own terms (CWE-434): reject anything that isn't the safe integer the
    // API contract promises before it reaches path.join, rather than trusting the shape.
    if (!Number.isInteger(artifact.id) || artifact.id < 0) {
      throw new Error(`unexpected artifact id shape: ${JSON.stringify(artifact.id)}`)
    }
    const res = await gh(`/repos/${REPO}/actions/artifacts/${artifact.id}/zip`, {
      redirect: 'follow',
    })
    const buf = Buffer.from(await res.arrayBuffer())
    const zipPath = path.join(workdir, `${artifact.id}.zip`)
    writeFileSync(zipPath, buf)
    execFileSync('unzip', ['-o', '-q', zipPath, '-d', workdir], { stdio: 'ignore' })
    const jsonName = artifact.name.replace(/^gate-results-/, '') + '.json'
    // run-gates.py --json writes whatever filename the caller passed; ci.yml (this change)
    // names it gate-results-<group>.json inside the artifact, matching the artifact's own
    // name — read that, and fall back to the first *.json in the extracted dir so a rename
    // on either side degrades to "not found" rather than a silent empty array.
    const candidate = path.join(workdir, `gate-results-${jsonName.replace('.json', '')}.json`)
    const text = readFileSync(candidate, 'utf8')
    return JSON.parse(text)
  } catch {
    return null
  }
}

let report
try {
  if (!TOKEN) {
    throw new Error('GITHUB_TOKEN not set')
  }

  const runsResp = await ghJson(
    `/repos/${REPO}/actions/workflows/ci.yml/runs?status=completed&per_page=${RUNS}`,
  )
  const runs = runsResp.workflow_runs || []

  // --- Tier: shard timing/pass-rate, Jobs API only, no artifact download -------------------
  const shardHistory = [] // [{runId, sha, event, createdAt, shards: [{name, conclusion, seconds}]}]
  for (const run of runs) {
    const jobsResp = await ghJson(`/repos/${REPO}/actions/runs/${run.id}/jobs?per_page=100`)
    const shards = (jobsResp.jobs || [])
      .filter((j) => j.name.startsWith('gates ('))
      .map((j) => ({
        name: j.name,
        conclusion: j.conclusion,
        seconds: j.completed_at && j.started_at
          ? (new Date(j.completed_at) - new Date(j.started_at)) / 1000
          : null,
      }))
    shardHistory.push({
      runId: run.id,
      sha: run.head_sha,
      event: run.event,
      createdAt: run.created_at,
      shards,
    })
  }

  // --- Tier: per-gate detail from the last GATE_DETAIL_RUNS runs' artifacts ----------------
  const gateRuns = [] // [{runId, sha, createdAt, gates: [...json_records()...]}]
  const workdir = mkdtempSync(path.join(tmpdir(), 'gate-health-'))
  try {
    for (const run of runs.slice(0, GATE_DETAIL_RUNS)) {
      const artifactsResp = await ghJson(
        `/repos/${REPO}/actions/runs/${run.id}/artifacts?per_page=100`,
      )
      const gateArtifacts = (artifactsResp.artifacts || [])
        .filter((a) => a.name.startsWith('gate-results-'))
      const perGate = []
      for (const artifact of gateArtifacts) {
        const records = await downloadArtifactJson(artifact, workdir)
        if (records) perGate.push(...records)
      }
      if (perGate.length) {
        gateRuns.push({ runId: run.id, sha: run.head_sha, createdAt: run.created_at, gates: perGate })
      }
    }
  } finally {
    rmSync(workdir, { recursive: true, force: true })
  }

  // --- Derive: per-gate current state, last-red, flaky (Tier 3, ADR-0255) -----------------
  const byGate = new Map()
  for (const gr of gateRuns) {
    for (const g of gr.gates) {
      if (!byGate.has(g.id)) byGate.set(g.id, [])
      byGate.get(g.id).push({ ...g, runId: gr.runId, sha: gr.sha, createdAt: gr.createdAt })
    }
  }
  const gates = [...byGate.entries()].map(([id, history]) => {
    // Most recent first — gateRuns is already newest-run-first (runs.slice(0, N) off a
    // completed?per_page= listing, which the API returns newest-first).
    const latest = history[0]
    const lastRed = history.find((h) => h.status === 'failed' || h.status === 'unfalsified')
    const distinctShas = new Set(history.map((h) => h.sha))
    const statuses = new Set(history.map((h) => h.status))
    const flaky = distinctShas.size > 1 && statuses.has('ok') &&
      (statuses.has('failed') || statuses.has('unfalsified'))
    return {
      id,
      group: latest.group,
      mode: latest.mode,
      status: latest.status,
      lastRed: lastRed ? { runId: lastRed.runId, sha: lastRed.sha, createdAt: lastRed.createdAt } : null,
      flaky,
      selftestDeclared: latest.selftest_declared,
      minSubjects: latest.min_subjects,
      budgetSeconds: latest.budget_seconds,
      // ?? 0, mirroring gate-health-puller-script-configmap.yaml's to_row(): an artifact
      // from a run-gates.py before this field existed has no such key, and this must
      // degrade to "no selftest cost recorded", not throw across the whole collect.
      selftestSeconds: latest.selftest_seconds ?? 0,
      runsObserved: history.length,
    }
  })

  const estate = gates.length
    ? {
        total: gates.length,
        enforced: gates.filter((g) => g.mode === 'enforced').length,
        advisory: gates.filter((g) => g.mode === 'advisory').length,
        withSelftest: gates.filter((g) => g.selftestDeclared).length,
        withFloor: gates.filter((g) => g.minSubjects != null).length,
        withBudget: gates.filter((g) => g.budgetSeconds != null).length,
        flaky: gates.filter((g) => g.flaky).length,
      }
    : null

  report = {
    available: true,
    source: 'github-actions-api',
    collectedAt: now.toISOString(),
    runsInspected: shardHistory.length,
    gateDetailRunsInspected: gateRuns.length,
    shardHistory,
    gates,
    estate,
  }
  console.warn(
    `[collect-gate-health] ${shardHistory.length} run(s) inspected, ${gateRuns.length} with ` +
    `gate-level detail, ${estate?.total ?? 0} distinct gate(s) seen -> ${OUT}`,
  )
} catch (e) {
  const msg = e instanceof Error ? e.message : String(e)
  report = {
    available: false,
    reason: msg.slice(0, 200),
    source: 'github-actions-api',
    collectedAt: now.toISOString(),
    runsInspected: 0,
    gateDetailRunsInspected: 0,
    shardHistory: [],
    gates: [],
    estate: null,
  }
  console.warn(`[collect-gate-health] unavailable (${msg.slice(0, 120)}); wrote available:false -> ${OUT}`)
}

writeFileSync(OUT, JSON.stringify(report, null, 2) + '\n')
