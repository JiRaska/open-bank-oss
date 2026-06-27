// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Generate the customer-app dossier artefact (ADR-0074). Mirrors the
// generate-catalog.mjs / generate-governance.mjs bake pattern: derive what code
// already knows, declare only the curatorial, emit a committed JSON the admin-ui
// loads as a static snapshot (ADR-0029 #7: derived, never hand-edited).
//
// Two layers, joined here:
//   DERIVED  — extracted from the customer app's source (openbank-app), never
//              transcribed: useFakeData, edge URL, OAuth scopes, cert-pinning
//              state from AppConfig.kt; app version from version.txt. The README
//              drift (it claimed F0/useFakeData=true while the code says false)
//              is exactly why these must be derived.
//   DECLARED — the curatorial facts no code expresses (capability list, lens
//              tagging, gap narratives, decision-missing judgment) read from
//              openbank-app/app-status.yaml, which lives next to that code.
//
// Honest by construction: a missing source yields null + a recorded gap, never a
// fabricated value.
//
// Cross-repo by design: the customer app is a SEPARATE repo (JiRaska/openbank-app).
// This is a new integration, not the in-repo monorepo walk that generate-catalog
// does — the app source path is an explicit --app-repo arg, never a baked sibling
// path. In CI both repos provide it via an actions/checkout of the app source
// (ADR-0074 invariant 3, transport): the platform repo holds the COMMITTED
// app-status.json (the baked artefact the Dockerfile COPYs); openbank-app CI
// regenerates it from its own source and publishes it back. The committed copy is
// the transport — the build never reaches across a filesystem to a sibling.
//
// Modes:
//   (write, default)  emit app-status.json. If the app source is UNAVAILABLE and a
//                     committed artefact already exists at --out, the committed copy
//                     is PRESERVED (never clobbered with a null-derived stub) — a
//                     run without the app checkout must not erase the transport copy.
//   --check           do not write; regenerate in memory and diff the `derived`
//                     block against the committed artefact (--against, default --out).
//                     This is the ADR-0074 invariant-5 content check that would have
//                     caught the README-vs-code drift. ADVISORY by default: prints a
//                     ::warning and exits 0 on drift. Add --enforce to exit 1 on drift
//                     (the advisory→enforce flip, mirroring ADR-0071 / ADR-0034).
//
// Usage:
//   node scripts/generate-app-status.mjs --app-repo <path> [--out <file>]
//   node scripts/generate-app-status.mjs --app-repo <path> --check [--against <file>] [--enforce]
// Defaults: app-repo = <admin-ui>/../../openbank-app (LOCAL DEV ONLY), out = ./app-status.json

import { readFileSync, writeFileSync, existsSync } from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'
import { parse as parseYaml } from 'yaml'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const args = process.argv.slice(2)
const getArg = (flag, dflt) => {
  const i = args.indexOf(flag)
  return i >= 0 && args[i + 1] ? args[i + 1] : dflt
}
const hasFlag = (flag) => args.includes(flag)

const ADMIN_UI = path.resolve(__dirname, '..')
const PLATFORM_REPO = path.resolve(ADMIN_UI, '..')
const APP_REPO = path.resolve(getArg('--app-repo', path.resolve(PLATFORM_REPO, '..', 'openbank-app')))
const OUT = path.resolve(getArg('--out', path.resolve(ADMIN_UI, 'app-status.json')))
const CHECK = hasFlag('--check')
const ENFORCE = hasFlag('--enforce')
const AGAINST = path.resolve(getArg('--against', OUT))

function readText(p) {
  try {
    return readFileSync(p, 'utf8')
  } catch {
    return null
  }
}

// ── DERIVED layer: extract from AppConfig.kt + version.txt ───────────────────
// Honest extraction: a value we cannot find stays null. We never invent.
function deriveFromCode(appRepo) {
  const gaps = []
  const cfgPath = path.join(appRepo, 'shared/src/commonMain/kotlin/tech/openbank/app/config/AppConfig.kt')
  const cfg = readText(cfgPath)
  const versionTxt = readText(path.join(appRepo, 'version.txt'))

  if (!cfg) {
    gaps.push(`AppConfig.kt not found at ${path.relative(appRepo, cfgPath)} — derived facts unavailable`)
    return {
      derived: {
        version: versionTxt ? versionTxt.trim() : null,
        useFakeData: null,
        edgeBaseUrl: null,
        keycloakIssuer: null,
        oauthScopes: null,
        certPinningConfigured: null,
        sourceAvailable: false,
      },
      gaps,
    }
  }

  const matchStr = (name) => {
    const m = cfg.match(new RegExp(`val\\s+${name}\\s*=\\s*"([^"]*)"`))
    return m ? m[1] : null
  }
  const useFakeDataMatch = cfg.match(/val\s+useFakeData\s*=\s*(true|false)/)
  const useFakeData = useFakeDataMatch ? useFakeDataMatch[1] === 'true' : null

  // OAUTH_SCOPES = listOf("a", "b", ...) → pull the quoted entries
  const scopesBlock = cfg.match(/OAUTH_SCOPES\s*=\s*listOf\(([^)]*)\)/)
  const oauthScopes = scopesBlock
    ? Array.from(scopesBlock[1].matchAll(/"([^"]+)"/g)).map((m) => m[1])
    : null

  // Cert pinning: find the PINNED_SPKI_HASHES line FIRST, then read its value.
  // Honest by construction (ADR-0074): if the field is absent/renamed we return
  // null — never default to `true`, which would claim pinning is configured when
  // we simply couldn't see it (the exact false-security-claim the dossier forbids).
  const pinsLine = cfg.match(/PINNED_SPKI_HASHES[^\n]*/)
  const certPinningConfigured = pinsLine ? !/emptySet\(\)/.test(pinsLine[0]) : null

  if (useFakeData === null) gaps.push('could not parse useFakeData from AppConfig.kt')
  if (certPinningConfigured === null) gaps.push('could not find PINNED_SPKI_HASHES in AppConfig.kt — cert-pinning state unknown')

  return {
    derived: {
      version: versionTxt ? versionTxt.trim() : null,
      useFakeData,
      edgeBaseUrl: matchStr('EDGE_BASE_URL'),
      keycloakIssuer: matchStr('KEYCLOAK_ISSUER'),
      oauthClientId: matchStr('OAUTH_CLIENT_ID'),
      oauthScopes,
      certPinningConfigured,
      // pinning is only ACTIVE off-fakedata with hashes present (AppConfig.CERT_PINNING_ENABLED)
      certPinningActive: useFakeData === false && certPinningConfigured === true,
      sourceAvailable: true,
    },
    gaps,
  }
}

// ── DECLARED layer: curatorial app-status.yaml ───────────────────────────────
function loadDeclared(appRepo) {
  const ymlPath = path.join(appRepo, 'app-status.yaml')
  const raw = readText(ymlPath)
  if (!raw) {
    return { declared: null, gaps: [`app-status.yaml not found at ${path.relative(appRepo, ymlPath)}`] }
  }
  try {
    return { declared: parseYaml(raw), gaps: [] }
  } catch (e) {
    return { declared: null, gaps: [`app-status.yaml failed to parse: ${e.message}`] }
  }
}

// ── Join ─────────────────────────────────────────────────────────────────────
const { derived, gaps: derivedGaps } = deriveFromCode(APP_REPO)
const { declared, gaps: declaredGaps } = loadDeclared(APP_REPO)

const capabilities = declared?.capabilities ?? []
const decisionMissing = capabilities.filter((c) => c.decisionMissing === true)
const byStatus = capabilities.reduce((acc, c) => {
  acc[c.status] = (acc[c.status] || 0) + 1
  return acc
}, {})

const out = {
  schema: 'openbank.appstatus/v1',
  // generatedAt is intentionally omitted to keep the artefact diff-stable in CI
  // (the content check in ADR-0074 diffs the derived block, not a timestamp).
  app: declared?.app ?? { name: 'openbank-app' },
  asOf: declared?.asOf ?? null,
  // Stable repo identifier (not a local filesystem path) — keeps the artefact
  // byte-identical regardless of WHERE the app source is checked out in CI, so the
  // content check diffs source-derived facts, not the runner's directory layout.
  appRepo: declared?.app?.repo ?? 'JiRaska/openbank-app',
  derived,
  capabilities,
  summary: {
    total: capabilities.length,
    byStatus,
    decisionMissing: decisionMissing.map((c) => c.id),
  },
  gaps: [...derivedGaps, ...declaredGaps],
}

const serialized = JSON.stringify(out, null, 2) + '\n'

function summarize() {
  console.log(
    `  derived: ${derived.sourceAvailable ? `version=${derived.version} useFakeData=${derived.useFakeData} edge=${derived.edgeBaseUrl}` : 'SOURCE UNAVAILABLE'}`,
  )
  console.log(`  capabilities: ${capabilities.length} (${Object.entries(byStatus).map(([k, v]) => `${k}=${v}`).join(', ')})`)
  if (decisionMissing.length) console.log(`  decision-missing: ${decisionMissing.map((c) => c.id).join(', ')}`)
  if (out.gaps.length) console.log(`  ⚠ gaps: ${out.gaps.length}\n    - ${out.gaps.join('\n    - ')}`)
}

// Read the committed artefact's `derived` block (the layer the content check
// governs), tolerating an absent/garbled file by returning null.
function committedDerived(file) {
  try {
    return JSON.parse(readFileSync(file, 'utf8')).derived ?? null
  } catch {
    return null
  }
}

// Field-by-field diff of two `derived` objects — the README-vs-code drift this
// check exists to catch surfaces here (e.g. useFakeData: false → true).
function diffDerived(committed, fresh) {
  const keys = Array.from(new Set([...Object.keys(committed ?? {}), ...Object.keys(fresh ?? {})])).sort()
  const lines = []
  for (const k of keys) {
    const a = JSON.stringify(committed?.[k])
    const b = JSON.stringify(fresh?.[k])
    if (a !== b) lines.push(`    ${k}: committed=${a} → regenerated=${b}`)
  }
  return lines
}

// ── --check: regenerate and diff the derived block (ADR-0074 invariant 5) ─────
if (CHECK) {
  console.log(`app-status check (advisory${ENFORCE ? '→ENFORCE' : ''}) against ${path.relative(process.cwd(), AGAINST)}`)
  summarize()

  if (!derived.sourceAvailable) {
    // No app checkout ⇒ nothing to compare. Degrade to a skip, never a failure:
    // a content check that can't see the source has no verdict to give.
    console.log('::warning title=app-status::app source unavailable — skipping derived-block content check (no openbank-app checkout)')
    process.exit(0)
  }

  const committed = committedDerived(AGAINST)
  if (committed === null) {
    console.log(`::warning title=app-status::no committed artefact at ${path.relative(process.cwd(), AGAINST)} to diff against — first run?`)
    process.exit(ENFORCE ? 1 : 0)
  }

  const drift = diffDerived(committed, derived)
  if (drift.length === 0) {
    console.log('  ✓ derived block matches the committed app-status.json')
    process.exit(0)
  }

  // Drift found — this is the signal the ADR wants. GitHub renders ::warning/::error
  // annotations inline on the PR; the body is also printed for the PR-comment step.
  const level = ENFORCE ? 'error' : 'warning'
  console.log(`::${level} title=app-status derived drift::the committed app-status.json no longer matches openbank-app source (${drift.length} field(s)). Regenerate & publish.`)
  console.log('  derived-block drift:')
  for (const l of drift) console.log(l)
  process.exit(ENFORCE ? 1 : 0)
}

// ── write mode ────────────────────────────────────────────────────────────────
// Guard the transport copy: a run WITHOUT the app source must not overwrite a
// committed artefact with a null-derived stub (that would silently erase the
// transported facts). Only refuse when there is something to lose.
if (!derived.sourceAvailable && existsSync(OUT)) {
  console.log(`app-status: app source unavailable — PRESERVING committed ${path.relative(process.cwd(), OUT)} (not clobbering with a null-derived stub)`)
  summarize()
  process.exit(0)
}

writeFileSync(OUT, serialized)

console.log(`app-status → ${path.relative(process.cwd(), OUT)}`)
summarize()
