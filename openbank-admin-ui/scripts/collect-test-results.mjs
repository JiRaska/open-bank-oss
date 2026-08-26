// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Collect per-service test results from JUnit XML into a single
// `test-results.json`, baked into the admin-ui image at build time. The admin-ui
// is a READ-ONLY consumer (see src/app/api/test-results/route.ts) — it never runs
// tests; CI (`./gradlew test integrationTest koverXmlReport`) produces the XML and
// this script summarises it. Honest by construction: a service with no XML reports
// shows tests:0 (accurate "not run here"), never fabricated numbers.
//
// Usage:
//   node scripts/collect-test-results.mjs [--repo <path>] [--out <file>]
// Defaults: repo = parent of admin-ui, out = ./test-results.json
//
// JUnit `<testsuite>` carries: tests, failures, errors, skipped, time (seconds).
// Gradle writes unit tests under build/test-results/test/ and integration tests
// under build/test-results/integrationTest/ (or .../intTest/). We classify by
// that directory segment.

import { readdirSync, statSync, readFileSync, writeFileSync, existsSync } from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'
import { releasedJvmServices } from './lib/service-inventory.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2)
const getArg = (flag, dflt) => {
  const i = args.indexOf(flag)
  return i >= 0 && args[i + 1] ? args[i + 1] : dflt
}

const REPO = path.resolve(getArg('--repo', path.resolve(__dirname, '..', '..')))
const OUT = path.resolve(getArg('--out', path.resolve(__dirname, '..', 'test-results.json')))

const KNOWN = releasedJvmServices(REPO)

function findXml(dir) {
  const out = []
  if (!existsSync(dir)) return out
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry)
    let st
    try { st = statSync(full) } catch { continue }
    if (st.isDirectory()) out.push(...findXml(full))
    else if (entry.endsWith('.xml') && entry.startsWith('TEST-')) out.push(full)
  }
  return out
}

// Pull integer/float attribute off the opening <testsuite ...> tag.
function attr(xml, name) {
  const m = new RegExp(`<testsuite[^>]*\\b${name}="([0-9.]+)"`).exec(xml)
  return m ? Number(m[1]) : 0
}

function classify(file) {
  // .../build/test-results/<phase>/TEST-*.xml
  const seg = file.split(path.sep)
  const idx = seg.lastIndexOf('test-results')
  const phase = idx >= 0 ? (seg[idx + 1] ?? '') : ''
  return /int|integration/i.test(phase) ? 'integration' : 'unit'
}

function collectService(folder) {
  const base = path.join(REPO, folder)
  const acc = {
    tests: 0, passed: 0, failed: 0, skipped: 0, errors: 0, durationMs: 0,
    testFiles: 0, lastRunAt: null,
    unit: { tests: 0, passed: 0, failed: 0 },
    integration: { tests: 0, passed: 0, failed: 0 },
  }
  const xmls = findXml(path.join(base, 'build', 'test-results'))
  for (const file of xmls) {
    let xml
    try { xml = readFileSync(file, 'utf-8') } catch { continue }
    const tests = attr(xml, 'tests')
    const failures = attr(xml, 'failures')
    const errors = attr(xml, 'errors')
    const skipped = attr(xml, 'skipped')
    const timeS = attr(xml, 'time')
    const failed = failures + errors
    const passed = Math.max(0, tests - failed - skipped)
    acc.tests += tests
    acc.failed += failed
    acc.errors += errors
    acc.skipped += skipped
    acc.passed += passed
    acc.durationMs += Math.round(timeS * 1000)
    acc.testFiles += 1
    const kind = classify(file)
    acc[kind].tests += tests
    acc[kind].passed += passed
    acc[kind].failed += failed
    try {
      const mtime = statSync(file).mtime.toISOString()
      if (!acc.lastRunAt || mtime > acc.lastRunAt) acc.lastRunAt = mtime
    } catch { /* ignore */ }
  }
  return { service: folder, ...acc }
}

const services = KNOWN.map(collectService)

const totals = services.reduce(
  (a, s) => ({
    tests: a.tests + s.tests, passed: a.passed + s.passed, failed: a.failed + s.failed,
    skipped: a.skipped + s.skipped, services: a.services + 1,
    servicesWithTests: a.servicesWithTests + (s.tests > 0 ? 1 : 0),
    unit: { tests: a.unit.tests + s.unit.tests, passed: a.unit.passed + s.unit.passed, failed: a.unit.failed + s.unit.failed },
    integration: { tests: a.integration.tests + s.integration.tests, passed: a.integration.passed + s.integration.passed, failed: a.integration.failed + s.integration.failed },
  }),
  { tests: 0, passed: 0, failed: 0, skipped: 0, services: 0, servicesWithTests: 0,
    unit: { tests: 0, passed: 0, failed: 0 }, integration: { tests: 0, passed: 0, failed: 0 } },
)

const body = { services, totals, collectedAt: new Date().toISOString() }
writeFileSync(OUT, JSON.stringify(body, null, 2))
console.log(`[collect-test-results] ${totals.tests} tests across ${totals.servicesWithTests}/${totals.services} services → ${OUT}`)
