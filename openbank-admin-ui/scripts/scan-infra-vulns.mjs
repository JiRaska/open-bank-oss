// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Grype CVE scan of the GitOps-declared infra images (ADR-0079). Resolves each full
// image ref (name:tag) to an immutable registry digest, scans it, and writes a
// COMPACT per-component summary (counts by severity + top high/critical) to
// infra-vulns.json. Compact on purpose: the future ConfigMap path has a 1 MiB cap.
//
// Only components whose image ref is derivable from GitOps are scanned; the rest stay
// honestly "not scanned" (the BFF shows that). Built-ins only (no node_modules).
//
// Usage: node scripts/scan-infra-vulns.mjs [--repo <path>] [--out <file>]

import { readdirSync, statSync, readFileSync, writeFileSync, existsSync } from 'fs'
import path from 'path'
import { execFileSync } from 'child_process'
import { fileURLToPath } from 'url'
import {
  DIGEST, immutableImageRef, scanInstant, sha256, sha256File,
  verifiedDbStatus, verifyInfraVulnSnapshot,
} from './lib/grype-source-evidence.mjs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const arg = (flag, def) => {
  const i = process.argv.indexOf(flag)
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : def
}
const REPO = path.resolve(arg('--repo', path.resolve(__dirname, '..', '..')))
const OUT = path.resolve(arg('--out', path.resolve(__dirname, '..', 'infra-vulns.json')))
const REGISTRY = path.resolve(__dirname, '..', 'src', 'lib', 'infra-lifecycle', 'registry.json')
const GITOPS = path.join(REPO, 'openbank-infra', 'gitops')
// This script runs only for an explicit --scan-infra build. A real scan time
// belongs in its uncommitted image input; the checked-in file is a placeholder.

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

function output(command, args) {
  return execFileSync(command, args, { encoding: 'utf8', maxBuffer: 256 * 1024 * 1024,
    stdio: ['ignore', 'pipe', 'pipe'] })
}

function registryDigest(ref) {
  const digest = output('crane', ['digest', ref]).trim()
  if (!DIGEST.test(digest)) throw new Error('registry returned no immutable image digest')
  return digest
}

function scan(ref, digest) {
  const immutable = immutableImageRef(ref, digest)
  // Scan the immutable content, then re-resolve the tag. A tag moved during
  // the scan must not be reported as the new image's CVE verdict.
  let raw
  try {
    raw = output('grype', [immutable, '-o', 'json', '-q'])
  } catch (e) {
    return { error: e.message || 'grype failed' }
  }
  if (registryDigest(ref) !== digest) throw new Error('image tag moved during Grype scan')
  let doc
  try { doc = JSON.parse(raw) } catch { return { error: 'unparseable grype output' } }
  if (!doc || typeof doc !== 'object' || Array.isArray(doc) || !Array.isArray(doc.matches) ||
      !doc.matches.every(m => m && typeof m === 'object' && !Array.isArray(m) &&
        m.vulnerability && typeof m.vulnerability === 'object' &&
        typeof m.vulnerability.severity === 'string')) {
    return { error: 'invalid Grype report shape' }
  }
  const counts = { critical: 0, high: 0, medium: 0, low: 0, negligible: 0, unknown: 0 }
  const top = []
  for (const m of doc.matches) {
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
  return { image: ref, imageDigest: digest, scannedImage: immutable,
    scanAt: scanInstant(), reportSha256: sha256(Buffer.from(raw)),
    critical: counts.critical, high: counts.high, medium: counts.medium, low: counts.low, total, top }
}

async function main() {
  const version = JSON.parse(output('grype', ['version', '-o', 'json'])).version
  const dbStatus = JSON.parse(output('grype', ['db', 'status', '-o', 'json']))
  const db = verifiedDbStatus(dbStatus, await sha256File(dbStatus.path))
  const reg = JSON.parse(readFileSync(REGISTRY, 'utf8'))
  const files = existsSync(GITOPS) ? walkFiles(GITOPS) : []
  const images = {}
  const expectedRefs = {}
  const skipped = []
  for (const c of reg.components) {
    if (c.versionSource !== 'gitops' || !c.imageGrep) { skipped.push(c.id); continue }
    const ref = resolveImageRef(c.imageGrep, files)
    if (!ref) { skipped.push(c.id); continue }
    const digest = registryDigest(ref)
    console.log(`[infra-vulns] scanning ${c.id} at immutable registry digest`)
    const r = scan(ref, digest)
    // A resolved image whose scan fails is not an honest fleet snapshot. Do
    // not let one successful image give the incomplete output a global time.
    if (r.error) throw new Error(`Grype scan failed for ${c.id}: ${r.error}`)
    images[c.id] = r
    expectedRefs[c.id] = ref
    console.log(`[infra-vulns] ${c.id}: ${r.critical}C ${r.high}H ${r.medium}M ${r.low}L`)
  }
  if (!Object.keys(images).length) throw new Error('no infra image scan completed')
  if ((await sha256File(dbStatus.path)) !== db.sha256) {
    throw new Error('Grype vulnerability DB changed during scan')
  }
  const out = {
    schema: 'openbank.infra-vulns/v2',
    scanner: { name: 'grype', version, db },
    scannedAt: scanInstant(),
    scanned: Object.keys(images),
    skipped,
    images,
  }
  verifyInfraVulnSnapshot(out, expectedRefs)
  writeFileSync(OUT, JSON.stringify(out, null, 2))
  console.log(`[infra-vulns] ${Object.keys(images).length} scanned, ${skipped.length} skipped → ${OUT}`)
}

main().catch(error => { console.error(`[infra-vulns] ${error.message}`); process.exitCode = 1 })
