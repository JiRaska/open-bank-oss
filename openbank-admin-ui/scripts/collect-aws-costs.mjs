// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Snapshot real AWS spend from Cost Explorer into a single `cost-report.json`,
// baked into the admin-ui image at build time. The admin-ui is a READ-ONLY
// consumer (ADR-0029 rule #3, mirrored from collect-test-results.mjs): it never
// holds billing IAM at runtime. CI / the deploy build runs this with an OIDC
// role (or the operator's SSO creds) that has `ce:GetCostAndUsage`; the route
// (src/app/api/finops/costs/route.ts) serves the baked snapshot read-only.
//
// Honest by construction: if the AWS CLI is absent or denied, we write an
// `available:false` report — the page degrades to a calm "cost data unavailable"
// state, never a fabricated number. This realises ADR-0054 phase 2 (periodic
// cost audit) without granting the pod live billing access.
//
// Usage:
//   node scripts/collect-aws-costs.mjs [--out <file>] [--days <n>] [--profile <p>]
// Defaults: out = ./cost-report.json, days = 30, profile = $AWS_PROFILE

import { execFileSync } from 'child_process'
import { writeFileSync } from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2)
const getArg = (flag, dflt) => {
  const i = args.indexOf(flag)
  return i >= 0 && args[i + 1] ? args[i + 1] : dflt
}

const OUT = path.resolve(getArg('--out', path.resolve(__dirname, '..', 'cost-report.json')))
const DAYS = parseInt(getArg('--days', '30'), 10) || 30
const PROFILE = getArg('--profile', process.env.AWS_PROFILE || '')
const REGION = process.env.AWS_REGION || 'us-east-1' // Cost Explorer is a global service, hosted in us-east-1

// A timestamp must be passed in / computed here (not in the route) so the baked
// snapshot carries its own provenance. We compute the trailing window from the
// process clock at build time.
function ymd(d) {
  return d.toISOString().slice(0, 10)
}
const now = new Date()
const end = ymd(now)
const start = ymd(new Date(now.getTime() - DAYS * 86_400_000))

function emptyReport(reason) {
  return {
    available: false,
    reason,
    currency: 'USD',
    periodStart: start,
    periodEnd: end,
    total: 0,
    services: [],
    collectedAt: now.toISOString(),
    source: 'aws-cost-explorer',
  }
}

function runCe() {
  const argv = [
    'ce', 'get-cost-and-usage',
    '--time-period', `Start=${start},End=${end}`,
    '--granularity', 'MONTHLY',
    '--metrics', 'UnblendedCost',
    '--group-by', 'Type=DIMENSION,Key=SERVICE',
    '--region', REGION,
    '--output', 'json',
  ]
  if (PROFILE) argv.push('--profile', PROFILE)
  return execFileSync('aws', argv, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 60_000 })
}

let report
try {
  const raw = runCe()
  const json = JSON.parse(raw)
  const acc = new Map()
  for (const r of json.ResultsByTime ?? []) {
    for (const g of r.Groups ?? []) {
      const name = g.Keys?.[0] ?? 'Unknown'
      const amount = parseFloat(g.Metrics?.UnblendedCost?.Amount ?? '0')
      if (!isNaN(amount)) acc.set(name, (acc.get(name) ?? 0) + amount)
    }
  }
  const services = [...acc.entries()]
    .map(([name, amount]) => ({ name, amount: Math.round(amount * 100) / 100 }))
    .filter(s => s.amount > 0)
    .sort((a, b) => b.amount - a.amount)
  const total = Math.round(services.reduce((s, x) => s + x.amount, 0) * 100) / 100

  report = {
    available: services.length > 0,
    currency: 'USD',
    periodStart: start,
    periodEnd: end,
    total,
    services,
    collectedAt: now.toISOString(),
    source: 'aws-cost-explorer',
  }
  console.log(`[collect-aws-costs] ${services.length} services, $${total} over ${start}..${end} → ${OUT}`)
} catch (e) {
  const msg = (e?.stderr || e?.message || String(e)).toString().split('\n')[0]
  report = emptyReport(msg.slice(0, 200))
  console.warn(`[collect-aws-costs] Cost Explorer unavailable (${msg.slice(0, 120)}); wrote available:false → ${OUT}`)
}

writeFileSync(OUT, JSON.stringify(report, null, 2) + '\n')
