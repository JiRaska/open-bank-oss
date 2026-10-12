// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Bakes infra-lifecycle.json (ADR-0079): for every /infrastructure component it joins
//   - the GitOps-resolved image tag (for versionSource=gitops components), and
//   - the upstream lifecycle feed from endoflife.date (cycles: eol, lts, latest, support)
// into one snapshot the BFF reads. Pure outbound fetch + repo walk, zero credentials.
//
// Deliberately uses ONLY Node built-ins (fetch, fs, path) — no `yaml`, no node_modules.
// A generator that needs node_modules silently failed and emptied governance.json once
// (the dashboard read "0/0 services"); this one cannot hit that class of bug.
//
// Usage: node scripts/generate-infra-lifecycle.mjs [--repo <path>] [--out <file>]

import { readdirSync, statSync, readFileSync, writeFileSync, existsSync } from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'
import { sourceDateForSemanticInputs } from './lib/source-date.mjs'
import { canonicalEolUrl, makeEolEvidence, projectEolCycles, verifyEolEvidence } from './lib/eol-source-evidence.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const arg = (flag, def) => {
  const i = process.argv.indexOf(flag)
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : def
}
const REPO = path.resolve(arg('--repo', path.resolve(__dirname, '..', '..')))
const OUT = path.resolve(arg('--out', path.resolve(__dirname, '..', 'infra-lifecycle.json')))
const EVIDENCE_OUT = arg('--source-evidence-out', null)
const REGISTRY = path.resolve(__dirname, '..', 'src', 'lib', 'infra-lifecycle', 'registry.json')
const GITOPS = path.join(REPO, 'openbank-infra', 'gitops')
// Repo inputs whose newest commit time becomes `generatedAt` (issue #2621). The upstream
// endoflife.date feed is NOT a repo input: when it moves, the `components` payload itself
// changes, so that diff is genuine and worth a conflict. What is no longer worth a conflict
// is the stamp advancing on its own while nothing changed.
const INPUTS = [
  'openbank-infra/gitops',
  'openbank-admin-ui/src/lib/infra-lifecycle/registry.json',
]

// Recursively collect every text line under the GitOps tree once (cheap; the tree is small).
function walkFiles(dir, acc = []) {
  let entries = []
  try { entries = readdirSync(dir) } catch { return acc }
  for (const e of entries) {
    const p = path.join(dir, e)
    let st
    try { st = statSync(p) } catch { continue }
    if (st.isDirectory()) walkFiles(p, acc)
    else if (/\.(ya?ml|json)$/.test(e)) acc.push(p)
  }
  return acc
}

// version = first dotted numeric run in the image tag (after the last ':'), minus a
// leading 'v' and any -alpine/.Final style suffix. e.g. v0.7.2 → 0.7.2, 7.4-alpine → 7.4,
// 2.6.2.Final → 2.6.2, 16.4 → 16.4.
function tagToVersion(tag) {
  const m = tag.match(/(\d+(?:\.\d+)*)/)
  return m ? m[1] : null
}

function resolveGitopsVersion(imageGrep, files) {
  // Only an `image:`/`imageName:` line, and the tag must start with an optional 'v' + a
  // DIGIT — so a host:port reference (redis.accounts.svc:6379) can never be mistaken for
  // a version. Grep is the image name without a trailing ':'.
  const base = imageGrep.replace(/:$/, '')
  const esc = base.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const re = new RegExp(`${esc}[^\\s"':]*:(v?\\d[^\\s"']*)`)
  for (const f of files) {
    let txt
    try { txt = readFileSync(f, 'utf8') } catch { continue }
    if (!txt.includes(base)) continue
    for (const line of txt.split('\n')) {
      if (!/image/i.test(line)) continue
      const m = line.match(re)
      if (m) return tagToVersion(m[1])
    }
  }
  return null
}

const sleep = ms => new Promise(r => setTimeout(r, ms))

async function fetchEol(product) {
  // endoflife.date occasionally resets the connection ("fetch failed"); retry a few
  // times with backoff so a transient blip can't silently leave a component with no
  // lifecycle data (which would read as a hollow card).
  let lastErr = 'fetch failed'
  let lastEvidence = makeEolEvidence(product, null, new Date().toISOString(), null)
  for (let attempt = 0; attempt < 4; attempt++) {
    if (attempt > 0) await sleep(400 * attempt)
    try {
      // `product` traces back to the statically-committed component registry
      // JSON (openbank.eolProduct), not attacker input — a fixed catalog string
      // like "node" or "postgresql" sent to a public, read-only lookup API.
      // codeql[js/file-access-to-http]
      const url = canonicalEolUrl(product)
      const res = await fetch(url, {
        headers: { Accept: 'application/json' },
        signal: AbortSignal.timeout(15000),
        redirect: 'error',
      })
      if (res.url !== url) throw new Error('EOL response URL differs from canonical source')
      const fetchedAt = new Date().toISOString()
      if (!res.ok) {
        lastEvidence = makeEolEvidence(product, res.status, fetchedAt, null)
        lastErr = `HTTP ${res.status}`
        continue
      }
      // Hash the exact wire bytes before parsing. The raw public response is
      // retained in the image's signed source-evidence sidecar for replay.
      const body = res.body?.getReader()
      if (!body) throw new Error('EOL response has no body')
      const chunks = []
      let size = 0
      for (;;) {
        const { done, value } = await body.read()
        if (done) break
        size += value.byteLength
        if (size > 2 * 1024 * 1024) {
          await body.cancel()
          throw new Error('EOL response exceeds 2 MiB')
        }
        chunks.push(value)
      }
      const raw = Buffer.concat(chunks)
      lastEvidence = makeEolEvidence(product, res.status, fetchedAt, raw)
      const cycles = projectEolCycles(raw)
      // Keep only the fields the UI needs, newest cycles first (endoflife returns them so).
      return {
        lifecycle: { cycles },
        evidence: lastEvidence,
      }
    } catch (e) {
      lastErr = e.message || 'fetch failed'
      lastEvidence = makeEolEvidence(product, null, new Date().toISOString(), null)
    }
  }
  return { lifecycle: { error: lastErr }, evidence: lastEvidence }
}

async function main() {
  const reg = JSON.parse(readFileSync(REGISTRY, 'utf8'))
  const files = existsSync(GITOPS) ? walkFiles(GITOPS) : []
  if (!files.length) console.warn(`[infra-lifecycle] WARN: no GitOps files under ${GITOPS}`)

  const components = []
  const sourceEvidence = { schema: 'openbank.eol-source-evidence/v1', products: {} }
  let eolHits = 0
  for (const c of reg.components) {
    const gitopsVersion = c.versionSource === 'gitops' && c.imageGrep
      ? resolveGitopsVersion(c.imageGrep, files)
      : null
    let lifecycle = null
    if (c.eolProduct) {
      const result = await fetchEol(c.eolProduct)
      lifecycle = result.lifecycle
      sourceEvidence.products[c.eolProduct] = result.evidence
      if (lifecycle && Array.isArray(lifecycle.cycles) && lifecycle.cycles.length) eolHits++
    }
    components.push({
      id: c.id,
      eolProduct: c.eolProduct,
      versionSource: c.versionSource,
      gitopsVersion,
      releaseNotes: c.releaseNotes ?? null,
      lifecycle, // { cycles } | { error } | null (no eol product = honest GAP)
    })
  }

  const out = {
    schema: 'openbank.infra-lifecycle/v1',
    source: 'endoflife.date + GitOps image tags — ADR-0079',
    generatedAt: sourceDateForSemanticInputs(
      REPO, INPUTS, reg.components.filter(c => c.versionSource === 'gitops' && c.imageGrep).map(c => c.imageGrep),
    ),
    components,
  }
  if (EVIDENCE_OUT) {
    verifyEolEvidence(out, sourceEvidence)
    writeFileSync(path.resolve(EVIDENCE_OUT), JSON.stringify(sourceEvidence, null, 2))
  }
  writeFileSync(OUT, JSON.stringify(out, null, 2))
  console.log(`[infra-lifecycle] ${components.length} components (${eolHits} with lifecycle feed) → ${OUT}`)
  // Fail loud if NOTHING resolved — a snapshot with no lifecycle feeds at all means the
  // upstream fetch is broken, and we must not bake a hollow file (the governance lesson).
  if (eolHits === 0) {
    console.error('[infra-lifecycle] ERROR: zero lifecycle feeds resolved (endoflife.date unreachable?).')
    process.exit(1)
  }
}

main()
