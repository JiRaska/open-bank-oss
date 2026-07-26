// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Grype CVE scan of the running infra images (ADR-0079). Resolves each component's full
// image ref (name:tag) from the GitOps tree, runs `grype <ref> -o json`, and writes a
// COMPACT per-component summary (counts by severity + top high/critical) to
// infra-vulns.json. Compact on purpose: the future ConfigMap path has a 1 MiB cap.
//
// Only components whose image ref is derivable from GitOps are scanned; the rest stay
// honestly "not scanned" (the BFF shows that). Built-ins only (no node_modules).
//
// Usage: node scripts/scan-infra-vulns.mjs [--repo <path>] [--out <file>]

import { readdirSync, statSync, readFileSync, writeFileSync, existsSync } from 'fs'
import path from 'path'
import { execSync } from 'child_process'
import { fileURLToPath } from 'url'
import { sourceDate } from './lib/source-date.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const arg = (flag, def) => {
  const i = process.argv.indexOf(flag)
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : def
}
const REPO = path.resolve(arg('--repo', path.resolve(__dirname, '..', '..')))
const OUT = path.resolve(arg('--out', path.resolve(__dirname, '..', 'infra-vulns.json')))
const REGISTRY = path.resolve(__dirname, '..', 'src', 'lib', 'infra-lifecycle', 'registry.json')
const GITOPS = path.join(REPO, 'openbank-infra', 'gitops')
// Repo inputs whose newest commit time becomes `scannedAt` (issue #2621). A grype DB
// refresh that changes the findings changes `images` too — a genuine diff. A rescan that
// finds nothing new must not rewrite the file, or every deploy PR conflicts by construction.
const INPUTS = [
  'openbank-infra/gitops',
  'openbank-admin-ui/src/lib/infra-lifecycle/registry.json',
]

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

// Full image ref (registry/name:tag) for an imageGrep, from an `image:` line.
function resolveImageRef(imageGrep, files) {
  const base = imageGrep.replace(/:$/, '')
  const esc = base.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const re = new RegExp(`([a-z0-9][a-z0-9./_-]*${esc}[^\\s"':]*:v?\\d[^\\s"']*)`)
  for (const f of files) {
    let txt
    try { txt = readFileSync(f, 'utf8') } catch { continue }
    if (!txt.includes(base)) continue
    for (const line of txt.split('\n')) {
      if (!/image/i.test(line)) continue
      const m = line.match(re)
      if (m) return m[1]
    }
  }
  return null
}

function scan(ref) {
  // grype <ref> -o json ; tolerate non-zero exit (grype exits non-zero on findings if a
  // fail-on threshold is set — we don't set one, but be defensive).
  let raw
  try {
    raw = execSync(`grype ${ref} -o json -q`, { encoding: 'utf8', maxBuffer: 256 * 1024 * 1024, stdio: ['ignore', 'pipe', 'ignore'] })
  } catch (e) {
    if (e.stdout) raw = e.stdout.toString()
    else return { error: e.message || 'grype failed' }
  }
  let doc
  try { doc = JSON.parse(raw) } catch { return { error: 'unparseable grype output' } }
  const counts = { critical: 0, high: 0, medium: 0, low: 0, negligible: 0, unknown: 0 }
  const top = []
  for (const m of doc.matches ?? []) {
    const sev = String(m?.vulnerability?.severity ?? 'Unknown').toLowerCase()
    if (counts[sev] === undefined) counts.unknown++
    else counts[sev]++
    if ((sev === 'critical' || sev === 'high') && top.length < 12) {
      top.push({
        id: m?.vulnerability?.id ?? '?',
        severity: sev,
        pkg: m?.artifact?.name ?? null,
        fixedIn: (m?.vulnerability?.fix?.versions ?? [])[0] ?? null,
      })
    }
  }
  const total = counts.critical + counts.high + counts.medium + counts.low + counts.negligible + counts.unknown
  return { image: ref, critical: counts.critical, high: counts.high, medium: counts.medium, low: counts.low, total, top }
}

function main() {
  const reg = JSON.parse(readFileSync(REGISTRY, 'utf8'))
  const files = existsSync(GITOPS) ? walkFiles(GITOPS) : []
  const images = {}
  const skipped = []
  for (const c of reg.components) {
    if (c.versionSource !== 'gitops' || !c.imageGrep) { skipped.push(c.id); continue }
    const ref = resolveImageRef(c.imageGrep, files)
    if (!ref) { skipped.push(c.id); continue }
    console.log(`[infra-vulns] scanning ${c.id}: ${ref}`)
    const r = scan(ref)
    if (r.error) { console.warn(`[infra-vulns] ${c.id}: ${r.error}`); skipped.push(c.id); continue }
    images[c.id] = r
    console.log(`[infra-vulns] ${c.id}: ${r.critical}C ${r.high}H ${r.medium}M ${r.low}L`)
  }
  const out = {
    schema: 'openbank.infra-vulns/v1',
    source: 'grype (ADR-0079)',
    scannedAt: sourceDate(REPO, INPUTS),
    scanned: Object.keys(images),
    skipped,
    images,
  }
  writeFileSync(OUT, JSON.stringify(out, null, 2))
  console.log(`[infra-vulns] ${Object.keys(images).length} scanned, ${skipped.length} skipped → ${OUT}`)
}

main()
