// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Generate the code-derived service catalog (ADR-0029 D3 / Layer C, "derive from
// code → enforce in CI → surface in UI"). Walks the monorepo and emits
// `catalog.json` — the per-service inventory the admin-ui catalog loads as a
// static, point-in-time snapshot (mirrors the test-results.json / cost-report.json
// bake pattern). This REPLACES hand-curated governance data (CLAUDE.md rule #7:
// catalog/coverage are derived, never hand-edited).
//
// Two version AXES are recorded separately, never conflated, per ADR-0048
// (API contract version is decoupled from the service release version):
//   releaseVersion — version.txt (the released component SemVer, ADR-0029)
//   apiVersion     — openapi.yaml info.version (the contract version, ADR-0005/0048)
// We do NOT flag releaseVersion != apiVersion as drift — that is expected and
// legitimate under ADR-0048. We DO surface genuine gaps: a service with an API
// spec but no version.txt, or a money-path service missing either.
//
// Honest by construction: a missing file yields null, never a fabricated value.
//
// Usage: node scripts/generate-catalog.mjs [--repo <path>] [--out <file>]
// Defaults: repo = parent of admin-ui, out = ./catalog.json

import { readdirSync, statSync, readFileSync, writeFileSync } from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'
import { parse as parseYaml } from 'yaml'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2)
const getArg = (flag, dflt) => {
  const i = args.indexOf(flag)
  return i >= 0 && args[i + 1] ? args[i + 1] : dflt
}

const REPO = path.resolve(getArg('--repo', path.resolve(__dirname, '..', '..')))
const OUT = path.resolve(getArg('--out', path.resolve(__dirname, '..', 'catalog.json')))

function readText(p) {
  try { return readFileSync(p, 'utf-8') } catch { return null }
}

// Money-path set + (optional) data-domain hints from the single governance source.
function loadGovernance() {
  const raw = readText(path.join(REPO, 'openbank-libs', 'governance', 'rules.yaml'))
  if (!raw) return { moneyPath: new Set() }
  try {
    const y = parseYaml(raw)
    const mp = Array.isArray(y?.money_path_services) ? y.money_path_services : []
    return { moneyPath: new Set(mp) }
  } catch {
    return { moneyPath: new Set() }
  }
}

// Read openapi.yaml info.{version,title} without trusting line order — parse it.
function readOpenapi(serviceDir) {
  const p = path.join(serviceDir, 'src', 'main', 'resources', 'openapi.yaml')
  const raw = readText(p)
  if (raw == null) return { hasOpenapi: false, apiVersion: null, apiTitle: null }
  try {
    const y = parseYaml(raw)
    return {
      hasOpenapi: true,
      apiVersion: y?.info?.version != null ? String(y.info.version) : null,
      apiTitle: y?.info?.title != null ? String(y.info.title) : null,
    }
  } catch {
    return { hasOpenapi: true, apiVersion: null, apiTitle: null }
  }
}

// Listener ports, read from the service's own application.yaml (the single place a
// port is declared). `${ENV:8101}` expressions yield their default; anything that is
// not a plain port yields null — never a guess.
function readPorts(serviceDir) {
  const raw = readText(path.join(serviceDir, 'src', 'main', 'resources', 'application.yaml'))
  if (raw == null) return { port: null, mgmtPort: null }
  try {
    const y = parseYaml(raw)
    const toPort = v => {
      const m = /^(?:\$\{[^:}]+:)?(\d{2,5})\}?$/.exec(String(v ?? '').trim())
      return m ? Number(m[1]) : null
    }
    return { port: toPort(y?.quarkus?.http?.port), mgmtPort: toPort(y?.quarkus?.management?.port) }
  } catch {
    return { port: null, mgmtPort: null }
  }
}

const gov = loadGovernance()

// Discover service modules: every top-level openbank-* dir that is a Gradle
// module (has version.txt OR an openapi spec OR a build.gradle.kts). admin-ui and
// libs are platform components, catalogued with their own version source.
const entries = readdirSync(REPO)
  .filter(n => n.startsWith('openbank-'))
  .filter(n => { try { return statSync(path.join(REPO, n)).isDirectory() } catch { return false } })
  .sort()

const services = []
for (const name of entries) {
  const dir = path.join(REPO, name)
  // admin-ui's release version lives in package.json; everything else in version.txt.
  let releaseVersion = readText(path.join(dir, 'version.txt'))?.trim() || null
  if (!releaseVersion && name === 'openbank-admin-ui') {
    try { releaseVersion = JSON.parse(readText(path.join(dir, 'package.json')) ?? '{}').version ?? null } catch { /* keep null */ }
  }
  const { hasOpenapi, apiVersion, apiTitle } = readOpenapi(dir)
  const isService = name.endsWith('-service') || name.endsWith('-payment') || name.endsWith('-instant')
  // The convention plugin identifies a runnable Quarkus module more reliably
  // than its name: risk-engine and product-catalog have no -service suffix.
  const buildScript = readText(path.join(dir, 'build.gradle.kts')) ?? ''
  const runnable = (/^\s*(?:plugins\s*\{\s*)?id\(["']openbank\.quarkus-service["']\)/m.test(buildScript)
    && (buildScript.includes('project(":openbank-libs-runtime")') || buildScript.includes('project(":openbank-libs")')))
    // analytics-sink applies the Quarkus Gradle plugin directly (no convention plugin) but is a
    // deployable service with its own port, so it belongs to the fleet too.
    || /^\s*alias\(libs\.plugins\.quarkus\)/m.test(buildScript)
  const { port, mgmtPort } = readPorts(dir)
  const moneyPath = gov.moneyPath.has(name)

  // Genuine gaps (NOT version drift, which ADR-0048 makes legitimate):
  const gaps = []
  if (isService && !releaseVersion) gaps.push('missing version.txt')
  if (moneyPath && !hasOpenapi) gaps.push('money-path service with no OpenAPI spec')

  services.push({
    name,
    short: name.replace(/^openbank-/, ''),
    kind: name === 'openbank-admin-ui' ? 'ui' : name === 'openbank-libs' ? 'library' : isService ? 'service' : 'component',
    runnable,
    port,
    mgmtPort,
    releaseVersion,
    apiVersion,
    apiTitle,
    hasOpenapi,
    moneyPath,
    gaps,
  })
}

const totals = {
  modules: services.length,
  services: services.filter(s => s.kind === 'service').length,
  withOpenapi: services.filter(s => s.hasOpenapi).length,
  moneyPath: services.filter(s => s.moneyPath).length,
  withGaps: services.filter(s => s.gaps.length > 0).length,
}

const catalog = {
  schema: 'openbank.catalog/v1',
  generator: 'generate-catalog.mjs',
  source: 'code-derived (version.txt, openapi.yaml, rules.yaml) — ADR-0029 D3 / ADR-0048',
  // collectedAt is stamped by the caller/CI (kept null here so the artifact is
  // reproducible byte-for-byte for a given commit; CI sets it post-generation).
  collectedAt: null,
  totals,
  services,
}

// Duplicate listener ports among runnable modules are real in application.yaml; warn (never fail)
// so they are visible instead of silently hidden by a hand-typed, accidentally-unique list.
{
  const byPort = new Map()
  for (const s of services.filter(m => m.runnable && m.port != null)) {
    byPort.set(s.port, [...(byPort.get(s.port) ?? []), s.name])
  }
  const dups = [...byPort.entries()].filter(([, names]) => names.length > 1)
  if (dups.length > 0) {
    console.warn(`[generate-catalog] WARNING: ${dups.length} duplicate listener port(s) among runnable modules:`)
    for (const [port, names] of dups) console.warn(`  - ${port}: ${names.join(', ')}`)
  }
}

writeFileSync(OUT, JSON.stringify(catalog, null, 2) + '\n')
console.log(`[generate-catalog] ${totals.modules} modules (${totals.services} services, ${totals.withOpenapi} with OpenAPI, ${totals.moneyPath} money-path, ${totals.withGaps} with gaps) → ${OUT}`)
if (totals.withGaps > 0) {
  console.log('[generate-catalog] gaps:')
  for (const s of services.filter(s => s.gaps.length > 0)) {
    console.log(`  - ${s.name}: ${s.gaps.join('; ')}`)
  }
}
