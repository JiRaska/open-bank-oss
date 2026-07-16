// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ── Admin-UI service-registry drift rule (enforced) ────────────────────────
//
// Why this guard exists: the admin-ui carries three hand-maintained service
// registries that must agree with each other AND with the deployed fleet:
//
//   1. SERVICE_REGISTRY  (src/lib/services/registry.ts)
//        id → { container, port }. Drives docs + per-service health.
//   2. SERVICE_MAP       (src/app/api/svc/[service]/[...path]/route.ts)
//        BFF off-cluster fallback. Its KEY is the `/api/svc/<key>` segment,
//        which IN-CLUSTER is looked up verbatim against real k8s workload names.
//   3. STATIC_CANDIDATES (src/app/services/page.tsx)
//        the cards rendered on /services.
//
// They drifted apart silently, because every failure mode here is quiet:
//   • SERVICE_MAP key `sepa-instant-service` did not match the real Deployment
//     (`sepa-instant`), so in-cluster discovery missed → 404 `Unknown service`
//     → the payments SCT-Inst panel rendered `not_deployed` forever. Off-cluster
//     it worked, so it looked fine locally.
//   • `container: 'openbank-sepa-instant-service'` named a directory that does
//     not exist. docs.ts derives the k8s name as `container.replace(/^openbank-/,'')`,
//     so docs resolved to a non-existent Service — again, silently.
//   • 7 STATIC_CANDIDATES had no SERVICE_REGISTRY entry, so their docs link
//     404'd. They were added on the strength of a comment promising an
//     `openbank-<id>-service` → `openbank-<id>` fallback that docs.ts never had.
//
// None of these throw. Nothing goes red. The UI just quietly lies. Hence this
// test: it re-derives the truth from the repo (module directories) and from
// openbank-infra/gitops (real workload names) and fails CI on any drift.
//
// If this test flags your change: fix the registry entry — do not add to an
// allowlist unless your service genuinely matches the documented exception.

import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, existsSync, statSync } from 'fs'
import path from 'path'

import { SERVICE_REGISTRY, k8sNameOf } from '@/lib/services/registry'

const ADMIN_UI = path.resolve(__dirname, '../..')
const REPO = path.resolve(ADMIN_UI, '..')
const GITOPS = path.join(REPO, 'openbank-infra', 'gitops')

const BFF_ROUTE = path.join(ADMIN_UI, 'src/app/api/svc/[service]/[...path]/route.ts')
const SERVICES_PAGE = path.join(ADMIN_UI, 'src/app/services/page.tsx')

// ── Exceptions (tight, documented, code-backed — NOT an escape hatch) ───────

// `libs` is the single docs id with no SERVICE_REGISTRY entry, because it is not
// a running service: docs.ts special-cases `id === 'libs'` and reads the
// image-baked bundle (openbank-libs/docs) instead of fetching /q/openbank/docs.
// See loadDocsIndex/loadDocsDocument in src/lib/services/docs.ts.
const BUNDLE_ONLY_DOCS_IDS = new Set(['libs'])

// Registry entries for real, buildable services that are NOT yet onboarded to
// GitOps. Their registry entry is the off-cluster/local-dev target and the docs
// backlog marker; in-cluster they resolve to null and degrade to "not deployed",
// which is honest. Remove an id from here once its Deployment lands.
const NOT_YET_DEPLOYED = new Set(['openbank-analytics-sink'])

// ── Truth sources, re-derived from the repo ────────────────────────────────

/** Every `openbank-*` module directory that actually exists in the monorepo. */
function moduleDirs(): Set<string> {
  return new Set(
    readdirSync(REPO)
      .filter(e => e.startsWith('openbank-'))
      .filter(e => statSync(path.join(REPO, e)).isDirectory()),
  )
}

function walkYaml(dir: string): string[] {
  const out: string[] = []
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry)
    if (statSync(full).isDirectory()) out.push(...walkYaml(full))
    else if (entry.endsWith('.yaml') || entry.endsWith('.yml')) out.push(full)
  }
  return out
}

/**
 * Real Kubernetes workload names declared in gitops. We scan for
 * Deployment/Service/Rollout kinds and take the following `metadata.name`.
 * Regex-based (the repo's guard-test convention) — these manifests are plain,
 * single-doc-per-kind YAML, so a parser buys nothing here.
 */
function gitopsWorkloadNames(): Set<string> {
  const names = new Set<string>()
  for (const file of walkYaml(GITOPS)) {
    const src = readFileSync(file, 'utf-8')
    for (const doc of src.split(/^---$/m)) {
      if (!/^kind:\s*(Deployment|Service|Rollout)\s*$/m.test(doc)) continue
      // `[ \t]` rather than `\s`: `\s` matches \n, so `(?:\s+.*\n)*?` could split a run of
      // " \n" lines many ways and backtrack exponentially (CodeQL js/redos). Indentation is
      // spaces/tabs only, so pinning it makes each iteration consume exactly one line.
      const m = doc.match(/^metadata:[ \t]*\n(?:[ \t]+.*\n)*?[ \t]+name:[ \t]*([a-z0-9][a-z0-9-]*)[ \t]*$/m)
      if (m) names.add(m[1])
    }
  }
  return names
}

/** BFF SERVICE_MAP keys, read from the route source. */
function serviceMapKeys(): string[] {
  const src = readFileSync(BFF_ROUTE, 'utf-8')
  const block = src.match(/const SERVICE_MAP[^=]*=\s*\{([\s\S]*?)\n\}/)
  expect(block, 'SERVICE_MAP literal not found in the BFF route').toBeTruthy()
  return [...block![1].matchAll(/^\s*'([a-z0-9-]+)':/gm)].map(m => m[1])
}

/** STATIC_CANDIDATES ids, read from the /services page source. */
function staticCandidateIds(): string[] {
  const src = readFileSync(SERVICES_PAGE, 'utf-8')
  const block = src.match(/const STATIC_CANDIDATES\s*=\s*\[([\s\S]*?)\n\]\s*as const/)
  expect(block, 'STATIC_CANDIDATES literal not found in the /services page').toBeTruthy()
  return [...block![1].matchAll(/\{\s*id:\s*'([a-z0-9-]+)'/g)].map(m => m[1])
}

/** Catalog `short` names (generated by `pretest` → scripts/generate-catalog.mjs). */
function catalogShorts(): Set<string> {
  const raw = readFileSync(path.join(ADMIN_UI, 'catalog.json'), 'utf-8')
  const parsed = JSON.parse(raw) as { services: { short: string }[] }
  return new Set(parsed.services.map(s => s.short))
}

// ── The rules ──────────────────────────────────────────────────────────────

describe('service registry drift guard', () => {
  it('every /services card resolves to a SERVICE_REGISTRY entry (no dead cards)', () => {
    const known = new Set(SERVICE_REGISTRY.map(s => s.id))
    const dead = staticCandidateIds()
      .filter(id => !BUNDLE_ONLY_DOCS_IDS.has(id))
      .filter(id => !known.has(id))
    expect(
      dead,
      `STATIC_CANDIDATES ids with no SERVICE_REGISTRY entry — each renders a card whose `
      + `docs link 404s with "Unknown service". Add a registry entry (id/container/port) `
      + `or drop the card: ${dead.join(', ')}`,
    ).toEqual([])
  })

  it('every SERVICE_REGISTRY container is a real openbank-* module directory', () => {
    const dirs = moduleDirs()
    const bogus = SERVICE_REGISTRY
      .filter(s => !dirs.has(s.container))
      .map(s => `${s.id} → ${s.container}`)
    expect(
      bogus,
      `SERVICE_REGISTRY containers naming a directory that does not exist. docs.ts derives `
      + `the k8s Service name from this field, so a wrong value silently resolves to nothing `
      + `and the page renders "not deployed" forever: ${bogus.join(', ')}`,
    ).toEqual([])
  })

  it('every SERVICE_REGISTRY entry resolves to a real gitops workload', () => {
    // k8sNameOf() is what docs.ts actually calls to reach a service in-cluster,
    // so this asserts the real resolution path, not a re-derivation of it.
    const workloads = gitopsWorkloadNames()
    const undeployed = SERVICE_REGISTRY
      .filter(s => !NOT_YET_DEPLOYED.has(s.container))
      .filter(s => !workloads.has(k8sNameOf(s)))
      .map(s => `${s.id} → ${k8sNameOf(s)}`)
    expect(
      undeployed,
      `SERVICE_REGISTRY entries whose k8s name matches no Deployment/Service/Rollout in `
      + `openbank-infra/gitops. Either the container/k8sName is wrong, or the service is not `
      + `deployed yet and belongs in NOT_YET_DEPLOYED: ${undeployed.join(', ')}`,
    ).toEqual([])
  })

  it('k8sName is only set where it actually differs from the directory', () => {
    // The override exists for one genuine mismatch. If it starts getting set
    // redundantly it stops signalling anything, so keep it load-bearing.
    const redundant = SERVICE_REGISTRY
      .filter(s => s.k8sName && s.k8sName === s.container.replace(/^openbank-/, ''))
      .map(s => s.id)
    expect(
      redundant,
      `k8sName equals the default derivation — drop the field: ${redundant.join(', ')}`,
    ).toEqual([])
  })

  it('every BFF SERVICE_MAP key matches a real gitops workload', () => {
    // This is the class of bug that broke sepa-instant: in-cluster the key IS the
    // workload name, so a key that matches nothing 404s no matter what the
    // off-cluster container/port say.
    const workloads = gitopsWorkloadNames()
    const bogus = serviceMapKeys().filter(k => !workloads.has(k))
    expect(
      bogus,
      `SERVICE_MAP keys matching no Deployment/Service/Rollout in openbank-infra/gitops. `
      + `In-cluster the key is looked up verbatim against discovery, so these 404 with `
      + `"Unknown service" and every caller of /api/svc/<key> degrades to "not deployed": `
      + `${bogus.join(', ')}`,
    ).toEqual([])
  })

  it('SERVICE_MAP containers agree with SERVICE_REGISTRY on the module directory', () => {
    const dirs = moduleDirs()
    const src = readFileSync(BFF_ROUTE, 'utf-8')
    const block = src.match(/const SERVICE_MAP[^=]*=\s*\{([\s\S]*?)\n\}/)!
    const bogus = [...block[1].matchAll(/^\s*'([a-z0-9-]+)':\s*\{\s*container:\s*'([a-z0-9-]+)'/gm)]
      .filter(m => !dirs.has(m[2]))
      .map(m => `${m[1]} → ${m[2]}`)
    expect(
      bogus,
      `SERVICE_MAP containers naming a directory that does not exist: ${bogus.join(', ')}`,
    ).toEqual([])
  })

  it('registry ports are unique', () => {
    const byPort = new Map<number, string[]>()
    for (const s of SERVICE_REGISTRY) {
      byPort.set(s.port, [...(byPort.get(s.port) ?? []), s.id])
    }
    const clashes = [...byPort.entries()]
      .filter(([, ids]) => ids.length > 1)
      .map(([port, ids]) => `${port}: ${ids.join(' + ')}`)
    expect(clashes, `two services share a port: ${clashes.join(', ')}`).toEqual([])
  })

  it('registry ids are unique', () => {
    const ids = SERVICE_REGISTRY.map(s => s.id)
    expect(ids.length, 'duplicate id in SERVICE_REGISTRY').toBe(new Set(ids).size)
  })

  it('the /services fleet-count exclusion list only names real catalog modules', () => {
    // The libs card derives "…for all N microservices" from the catalog, minus a
    // small NON_FLEET_MODULES set. If a module there is renamed, the exclusion
    // silently stops matching and the rendered count skews — so pin it here.
    const src = readFileSync(SERVICES_PAGE, 'utf-8')
    const block = src.match(/const NON_FLEET_MODULES\s*=\s*new Set\(\[([\s\S]*?)\]\)/)
    expect(block, 'NON_FLEET_MODULES not found in the /services page').toBeTruthy()
    const listed = [...block![1].matchAll(/'([a-z0-9-]+)'/g)].map(m => m[1])
    expect(listed.length, 'NON_FLEET_MODULES is empty — expected the libs/infra modules').toBeGreaterThan(0)
    const shorts = catalogShorts()
    const unknown = listed.filter(s => !shorts.has(s))
    expect(
      unknown,
      `NON_FLEET_MODULES names modules absent from catalog.json — the derived fleet count `
      + `on /services is now wrong: ${unknown.join(', ')}`,
    ).toEqual([])
  })

  it('the exception allowlists stay tight', () => {
    // A guard is only worth as much as its exceptions. If these grow, the rules
    // above have stopped meaning anything — re-read the rationale, don't bump.
    expect(BUNDLE_ONLY_DOCS_IDS.size, 'only `libs` is bundle-only in docs.ts').toBe(1)
    expect(
      NOT_YET_DEPLOYED.size,
      'NOT_YET_DEPLOYED should shrink as services onboard to GitOps, never grow casually',
    ).toBeLessThanOrEqual(1)
    for (const container of NOT_YET_DEPLOYED) {
      expect(existsSync(path.join(REPO, container)), `${container} must still be a real module`).toBe(true)
    }
  })
})
