// SPDX-License-Identifier: Apache-2.0
//
// ADR-0370 U7 — version skew: does the UI depend on a response shape the RELEASED service does
// not serve yet?
//
// The U1 guard checks every /api/svc mock against openapi.yaml on this branch. A service is
// deployed from its latest release, not from main, so a mock can be valid against main while
// relying on a field the deployed version lacks. This replays the mocks captured during the
// e2e run (OPENBANK_CONTRACT_CAPTURE) against two specs per service, both read strictly
// (an undeclared field is a violation):
//   main    — openapi.yaml in this checkout
//   release — openapi.yaml at the service's highest `<component>-v<semver>` tag
// A violation against release that main does not have is skew. Violations both share are U1's
// business and its baseline, not this one's.
//
// Usage (from openbank-admin-ui/):
//   node scripts/contract-version-skew.ts <capture.jsonl> [--record]
// --record rewrites e2e/contract-skew-baseline.json with today's findings.

import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { OpenApiContract, parseProxyUrl, resolveSpecFile, type MockedResponse, type Violation } from '../e2e/helpers/openapi-contract.ts'

const UI_DIR = path.resolve(path.dirname(new URL(import.meta.url).pathname), '..')
const REPO_ROOT = path.resolve(UI_DIR, '..')
const BASELINE = path.join(UI_DIR, 'e2e/contract-skew-baseline.json')
const SEMVER_TAG = /^(.+)-v(\d+)\.(\d+)\.(\d+)$/

function git(args: string[]): string {
  return execFileSync('git', args, { cwd: REPO_ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
}

function componentFor(moduleDir: string): string | null {
  const config = JSON.parse(readFileSync(path.join(REPO_ROOT, 'release-please-config.json'), 'utf8'))
  return config.packages?.[moduleDir]?.component ?? null
}

/** Highest `<component>-vX.Y.Z` tag per component, from ONE ls-remote over all tags. */
function latestReleaseTags(components: string[]): Map<string, string> {
  const wanted = new Set(components)
  const best = new Map<string, { tag: string; v: number[] }>()
  for (const line of git(['ls-remote', '--tags', '--refs', 'origin']).split('\n')) {
    const tag = line.split('\t')[1]?.replace('refs/tags/', '')
    const m = tag ? SEMVER_TAG.exec(tag) : null
    if (!tag || !m || !wanted.has(m[1]!)) continue
    const v = [Number(m[2]), Number(m[3]), Number(m[4])]
    const cur = best.get(m[1]!)
    const newer = !cur || v[0]! > cur.v[0]! || (v[0] === cur.v[0] && (v[1]! > cur.v[1]! || (v[1] === cur.v[1] && v[2]! > cur.v[2]!)))
    if (newer) best.set(m[1]!, { tag, v })
  }
  const tags = new Map([...best].map(([c, { tag }]) => [c, tag]))
  // One shallow fetch for every tag a CI checkout (fetch-depth 1, no tags) does not have.
  if (tags.size) git(['fetch', '--no-tags', '--depth=1', 'origin', ...[...tags.values()].map(t => `refs/tags/${t}:refs/tags/${t}`)])
  return tags
}

/** Stage each service's release-tag spec under the layout resolveSpecFile expects. */
function stageReleaseSpecs(services: string[]): { root: string; tags: Record<string, string | null> } {
  const root = mkdtempSync(path.join(os.tmpdir(), 'ob-release-specs-'))
  const modules = new Map<string, { moduleDir: string; component: string | null }>()
  for (const service of services) {
    const file = resolveSpecFile(REPO_ROOT, service)
    if (!file) continue
    const moduleDir = path.relative(REPO_ROOT, file).split(path.sep)[0]!
    modules.set(service, { moduleDir, component: componentFor(moduleDir) })
  }
  const latest = latestReleaseTags([...modules.values()].map(m => m.component).filter((c): c is string => !!c))
  const tags: Record<string, string | null> = {}
  for (const service of services) {
    const mod = modules.get(service)
    const tag = mod?.component ? latest.get(mod.component) ?? null : null
    tags[service] = tag
    if (!mod || !tag) continue
    const rel = `${mod.moduleDir}/src/main/resources/openapi.yaml`
    let text: string
    try { text = git(['show', `${tag}:${rel}`]) } catch { tags[service] = `${tag} (no openapi.yaml)`; continue }
    mkdirSync(path.join(root, path.dirname(rel)), { recursive: true })
    writeFileSync(path.join(root, rel), text)
  }
  return { root, tags }
}

const key = (v: Violation) => `${v.method} ${v.service} ${v.operation}|${v.status}|${v.kind}`

export function skew(mocks: MockedResponse[], mainRoot: string, releaseRoot: string): Map<string, string> {
  const main = new OpenApiContract(mainRoot, { strictObjects: true })
  const release = new OpenApiContract(releaseRoot, { strictObjects: true })
  const found = new Map<string, string>()
  for (const mock of mocks) {
    const target = parseProxyUrl(mock.url)
    // No release spec staged (no tag yet, or the tag predates the spec): nothing to compare.
    if (!target || !resolveSpecFile(releaseRoot, target.service)) continue
    const onMain = new Set((main.check(mock) ?? []).map(v => `${v.kind}|${v.detail}`))
    for (const v of release.check(mock) ?? []) {
      if (!onMain.has(`${v.kind}|${v.detail}`)) found.set(key(v), v.detail)
    }
  }
  return found
}

function main(): void {
  const [captureFile, ...flags] = process.argv.slice(2)
  if (!captureFile) { console.error('usage: contract-version-skew.ts <capture.jsonl> [--record]'); process.exit(2) }
  if (!existsSync(captureFile)) {
    console.log('SUBJECTS=UNRESOLVED')
    console.log(`contract-version-skew: no capture at ${captureFile} — nothing was measured`)
    return
  }
  const mocks = readFileSync(captureFile, 'utf8').split('\n').filter(Boolean).map(l => JSON.parse(l) as MockedResponse)
  const services = [...new Set(mocks.map(m => parseProxyUrl(m.url)?.service).filter((s): s is string => !!s))].sort()
  const { root, tags } = stageReleaseSpecs(services)
  for (const s of services) console.log(`  ${s}: ${tags[s] ?? 'no release tag — skipped'}`)
  const found = skew(mocks, REPO_ROOT, root)
  console.log(`SUBJECTS=${mocks.length}`)
  if (flags.includes('--record')) {
    writeFileSync(BASELINE, `${JSON.stringify({
      _comment: 'ADR-0370 U7: known version skew (mock valid against main, not against the latest release tag). May only shrink; it empties as releases catch up. Regenerate with --record.',
      skew: [...found.keys()].sort(),
    }, null, 2)}\n`)
    console.log(`contract-version-skew: recorded ${found.size} finding(s)`)
    return
  }
  const known = new Set<string>(JSON.parse(readFileSync(BASELINE, 'utf8')).skew as string[])
  const fresh = [...found].filter(([k]) => !known.has(k))
  for (const [k, detail] of fresh) console.log(`::warning title=Version skew::${k} — ${detail}`)
  console.log(`contract-version-skew: ${mocks.length} mocks, ${services.length} services, ${found.size} skew finding(s), ${fresh.length} new`)
  process.exit(fresh.length ? 1 : 0)
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(new URL(import.meta.url).pathname)) main()
