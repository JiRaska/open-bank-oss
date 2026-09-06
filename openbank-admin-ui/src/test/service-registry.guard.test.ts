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
const GITOPS_APPS = path.join(GITOPS, 'apps')

const BFF_ROUTE = path.join(ADMIN_UI, 'src/app/api/svc/[service]/[...path]/route.ts')
const SERVICES_PAGE = path.join(ADMIN_UI, 'src/app/services/page.tsx')
const DOCS_API_PAGE = path.join(ADMIN_UI, 'src/app/docs/api/page.tsx')

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
/**
 * Finds the `name:` value inside the `metadata:` block starting at [metadataLineIdx].
 * Line-by-line, not regex: an indented, whitespace-only line (e.g. "\t\t") let a
 * `[ \t]+` / `.*` pair split it many ways, and CodeQL flagged the resulting exponential
 * backtrack (js/redos) even after pinning the char class to `[ \t]`. Scanning lines
 * directly has no backtracking to begin with.
 */
function nameFromMetadataBlock(lines: string[], metadataLineIdx: number): string | undefined {
  for (let i = metadataLineIdx + 1; i < lines.length; i++) {
    const line = lines[i]
    if (!/^[ \t]/.test(line)) break // dedented past the metadata block
    const m = /^[ \t]+name:[ \t]*([a-z0-9][a-z0-9-]*)[ \t]*$/.exec(line)
    if (m) return m[1]
  }
  return undefined
}

function gitopsWorkloadNames(): Set<string> {
  const names = new Set<string>()
  for (const file of walkYaml(GITOPS)) {
    const src = readFileSync(file, 'utf-8')
    for (const doc of src.split(/^---$/m)) {
      if (!/^kind:\s*(Deployment|Service|Rollout)\s*$/m.test(doc)) continue
      const lines = doc.split('\n')
      const metadataLineIdx = lines.findIndex(l => /^metadata:[ \t]*$/.test(l))
      if (metadataLineIdx === -1) continue
      const name = nameFromMetadataBlock(lines, metadataLineIdx)
      if (name) names.add(name)
    }
  }
  return names
}


/**
 * Namespaces that run an openbank-* Deployment/Rollout, derived from the gitops tree.
 * Derived, not listed: a second hand-kept list is the drift this guard exists to prevent.
 */
function gitopsWorkloadNamespaces(): Set<string> {
  const namespaces = new Set<string>()
  for (const file of walkYaml(GITOPS)) {
    const src = readFileSync(file, 'utf-8')
    for (const doc of src.split(/^---$/m)) {
      if (!/^kind:\s*(Deployment|Rollout)\s*$/m.test(doc)) continue
      const name = doc.match(/^\s{2}name:\s*(\S+)/m)?.[1]
      const ns = doc.match(/^\s{2}namespace:\s*(\S+)/m)?.[1]
      if (name?.startsWith('openbank-') || /-service$/.test(name ?? '')) {
        if (ns) namespaces.add(ns)
      }
    }
  }
  return namespaces
}

interface ApplicationDiscoveryState {
  file: string
  namespace: string
  staged: boolean
  automated: boolean
}

/**
 * Deployment lifecycle comes from the Argo Application, not merely from manifests under its
 * component path. A staged Application may describe a complete workload while deliberately
 * withholding sync; binding discovery RBAC into that absent namespace blocks the Admin UI's own
 * Argo reconciliation before its Deployment can roll out.
 */
function applicationDiscoveryStates(): ApplicationDiscoveryState[] {
  const states: ApplicationDiscoveryState[] = []
  for (const file of walkYaml(GITOPS_APPS)) {
    const src = readFileSync(file, 'utf8')
    for (const doc of src.split(/^---$/m)) {
      if (!/^kind:\s*Application\s*$/m.test(doc)) continue
      const lines = doc.split('\n')
      const destinationLine = lines.findIndex(line => line === '  destination:')
      let namespace: string | undefined
      if (destinationLine !== -1) {
        for (let i = destinationLine + 1; i < lines.length; i++) {
          if (/^  \S/.test(lines[i])) break
          const match = /^ {4}namespace:\s*(\S+)\s*$/.exec(lines[i])
          if (match) {
            namespace = match[1]
            break
          }
        }
      }
      if (!namespace) continue
      states.push({
        file: path.relative(REPO, file),
        namespace,
        staged: lines.some(line => /^ {4}openbank\.io\/discovery-state:\s*staged\s*$/.test(line)),
        automated: lines.some(line => /^ {4}automated:\s*$/.test(line)),
      })
    }
  }
  return states
}

/**
 * Namespaces deliberately outside the discovery boundary. Kept tight: each entry is a namespace
 * whose workloads the console never proxies to.
 */
const DISCOVERY_EXEMPT_NAMESPACES = new Set<string>([
  'admin-ui',      // the console itself
  'temporal',      // infra, reached through its own status route
  'messaging',     // Kafka/Strimzi
  'observability', // Prometheus/Tempo, reached directly
  'argocd', 'external-secrets', 'vault', 'cnpg-system', 'keda', 'iam',
])

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

/** Catalog module `name`s (the full `openbank-*` directory name, generate-catalog.mjs's key). */
function catalogNames(): Set<string> {
  const raw = readFileSync(path.join(ADMIN_UI, 'catalog.json'), 'utf-8')
  const parsed = JSON.parse(raw) as { services: { name: string }[] }
  return new Set(parsed.services.map(s => s.name))
}

/**
 * `SERVICES` entries from the /docs/api page, as `{ specId, k8sName }`. `specId` doubles as the
 * key into the code-derived catalog (`catalog[svc.specId]`, keyed by module directory name) AND,
 * absent a `k8sName` override, the source `k8sName()` derives the BFF workload name from — so a
 * `specId` that only satisfies one of those two readers is exactly the drift this guards against.
 */
function docsApiSpecEntries(): { id: string; specId: string; k8sName: string | null }[] {
  const src = readFileSync(DOCS_API_PAGE, 'utf-8')
  const block = src.match(/const SERVICES: Service\[\] = \[([\s\S]*?)\n\]/)
  expect(block, 'SERVICES literal not found in the /docs/api page').toBeTruthy()
  const out: { id: string; specId: string; k8sName: string | null }[] = []
  for (const line of block![1].split('\n')) {
    const id = line.match(/id:\s*'([a-z0-9-]+)'/)?.[1]
    const specId = line.match(/specId:\s*'([a-z0-9-]+)'/)?.[1]
    if (!id || !specId) continue // the `catalog` entry has `specId: null` — nothing to check
    const k8sName = line.match(/k8sName:\s*'([a-z0-9-]+)'/)?.[1] ?? null
    out.push({ id, specId, k8sName })
  }
  expect(out.length, 'no specId entries parsed out of the /docs/api SERVICES literal').toBeGreaterThan(0)
  return out
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

  it('every svcUrl() key exists in SERVICE_MAP (no caller pointing at an unknown service)', () => {
    // The OTHER direction of the check above, and the one that was missing: that guard proves no
    // SERVICE_MAP key is dead, but nothing proved every CALLER has a key. A page calling
    // svcUrl('campaign-service', …) with no such key gets "Unknown service" from the proxy and
    // renders as "not responding" — a deployed, healthy service that looks down. That is exactly
    // what shipped with the campaign console (#2749): unit tests stubbed `fetch`, so the proxy was
    // never on the path they exercised.
    const keys = new Set(serviceMapKeys())
    const callers: { file: string; key: string }[] = []
    const walkDir = (dir: string): string[] =>
      readdirSync(dir, { withFileTypes: true }).flatMap(e => {
        const full = path.join(dir, e.name)
        return e.isDirectory() ? walkDir(full) : [full]
      })
    for (const file of walkDir(path.join(ADMIN_UI, 'src/app'))) {
      if (!file.endsWith('.ts') && !file.endsWith('.tsx')) continue
      for (const m of readFileSync(file, 'utf8').matchAll(/svcUrl\(\s*'([^']+)'/g)) {
        callers.push({ file: path.relative(ADMIN_UI, file), key: m[1] })
      }
    }
    expect(callers.length, 'no svcUrl() callers found — the matcher has drifted').toBeGreaterThan(0)
    const unknown = callers.filter(c => !keys.has(c.key))
    expect(
      unknown.map(c => `${c.file} → ${c.key}`),
      'svcUrl() callers naming a key SERVICE_MAP does not define. The proxy answers '
      + '"Unknown service" and the page degrades to "not responding" on a healthy service.',
    ).toEqual([])
  })

  it('every namespace with a gitops workload is discoverable (OPENBANK_NAMESPACES + RoleBinding)', () => {
    // ADR-0051 makes adding a domain namespace a THREE-step change, two of which live in
    // admin-ui.yaml: the OPENBANK_NAMESPACES filter and a per-namespace RoleBinding for
    // admin-ui-discovery. Miss either and discovery cannot see the service, so the console renders
    // "not responding" against a healthy pod — which is exactly what campaign did (#2749).
    //
    // The checklist was already written in a comment. A comment cannot fail; this can.
    const manifest = readFileSync(
      path.join(GITOPS, 'components/admin-ui/admin-ui.yaml'), 'utf8',
    )
    const listed = new Set(
      (manifest.match(/name: OPENBANK_NAMESPACES\s*\n\s*value:\s*(.+)/)?.[1] ?? '')
        .split(',').map(n => n.trim()).filter(Boolean),
    )
    const bound = new Set(
      [...manifest.matchAll(/name: admin-ui-discovery\s*\n\s*namespace:\s*(\S+)/g)].map(m => m[1]),
    )

    // Namespaces that actually run an openbank service, derived from the gitops tree rather than
    // from a second hand-kept list — the whole point is that the set cannot drift.
    const stagedNamespaces = new Set(
      applicationDiscoveryStates().filter(app => app.staged).map(app => app.namespace),
    )
    const serviceNamespaces = new Set<string>()
    for (const ns of gitopsWorkloadNamespaces()) serviceNamespaces.add(ns)

    const missing = [...serviceNamespaces]
      .filter(ns => !DISCOVERY_EXEMPT_NAMESPACES.has(ns))
      .filter(ns => !stagedNamespaces.has(ns))
      .filter(ns => !listed.has(ns) || !bound.has(ns))
      .map(ns => `${ns}${listed.has(ns) ? '' : ' (not in OPENBANK_NAMESPACES)'}${bound.has(ns) ? '' : ' (no RoleBinding)'}`)

    expect(
      missing,
      'namespaces running an openbank service that admin-ui discovery cannot see. The console '
      + 'will render "not responding" for every service in them, against healthy pods.',
    ).toEqual([])
  })

  it('keeps staged Applications outside live discovery until activation', () => {
    const manifest = readFileSync(
      path.join(GITOPS, 'components/admin-ui/admin-ui.yaml'), 'utf8',
    )
    const listed = new Set(
      (manifest.match(/name: OPENBANK_NAMESPACES\s*\n\s*value:\s*(.+)/)?.[1] ?? '')
        .split(',').map(n => n.trim()).filter(Boolean),
    )
    const bound = new Set(
      [...manifest.matchAll(/name: admin-ui-discovery\s*\n\s*namespace:\s*(\S+)/g)].map(m => m[1]),
    )
    const staged = applicationDiscoveryStates().filter(app => app.staged)
    const staleMarkers = staged
      .filter(app => app.automated)
      .map(app => `${app.namespace} (${app.file} is automated)`)
    const activatedTooEarly = staged
      .filter(app => listed.has(app.namespace) || bound.has(app.namespace))
      .map(app => `${app.namespace}${listed.has(app.namespace) ? ' (queried)' : ''}${bound.has(app.namespace) ? ' (RBAC bound)' : ''}`)

    expect(
      staleMarkers,
      'an automated Application cannot remain marked discovery-state=staged; remove the marker '
      + 'and complete its OPENBANK_NAMESPACES + RoleBinding activation together.',
    ).toEqual([])
    expect(
      activatedTooEarly,
      'staged Applications are desired state, not live namespaces. Querying or binding them makes '
      + `the Admin UI Argo sync fail before its own rollout: ${activatedTooEarly.join(', ')}`,
    ).toEqual([])
  })

  it('no server route calls svcUrl() — a relative URL cannot be fetched server-side', () => {
    // svcUrl() returns a same-origin RELATIVE path. That is correct for the browser and fatal in a
    // route handler: Node's fetch answers `Failed to parse URL from /api/svc/…`, which lands in a
    // catch and surfaces as "the service did not answer" — a healthy service reported as down. It
    // cost the campaign console three wrong diagnoses before the throw was read (#2749).
    //
    // Server code addresses the Service DNS directly via serverSvcUrl(); the proxy exists to give
    // the BROWSER a same-origin path, which server code does not need.
    const offenders: string[] = []
    const walkDir = (dir: string): string[] =>
      readdirSync(dir, { withFileTypes: true }).flatMap(e => {
        const full = path.join(dir, e.name)
        return e.isDirectory() ? walkDir(full) : [full]
      })
    for (const file of walkDir(path.join(ADMIN_UI, 'src/app'))) {
      // Route handlers only: a page.tsx marked 'use client' runs in the browser, where svcUrl is right.
      if (!/route\.tsx?$/.test(file)) continue
      const src = readFileSync(file, 'utf8')
      if (/\bsvcUrl\s*\(/.test(src) && !/\bserverSvcUrl\s*\(/.test(src.match(/\bsvcUrl\s*\(/) ? src : '')) {
        if (/[^r]\bsvcUrl\s*\(/.test(src)) offenders.push(path.relative(ADMIN_UI, file))
      }
    }
    expect(
      offenders,
      'route handlers calling svcUrl(). Node fetch rejects the relative URL it returns; use '
      + 'serverSvcUrl(name, namespace, port, path) instead.',
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

  it('every /docs/api SERVICES specId resolves to a real code-derived catalog entry', () => {
    // The page looks up `catalog[svc.specId]` (keyed by full module directory name, e.g.
    // "openbank-security-scanner") to overlay release/API version, money-path and gap facts.
    // specId `openbank-security-scanner-service` names no such directory, so that card silently
    // rendered with no version/API metadata forever — nothing threw, the lookup just missed.
    const names = catalogNames()
    const orphaned = docsApiSpecEntries()
      .filter(s => !names.has(s.specId))
      .map(s => `${s.id} → specId '${s.specId}'`)
    expect(
      orphaned,
      `/docs/api specId names no catalog module, so the card never overlays real version/API `
      + `data: ${orphaned.join(', ')}`,
    ).toEqual([])
  })

  it('every /docs/api SERVICES entry resolves to a real gitops workload', () => {
    // The health check calls svcUrl(k8sName(svc), …); k8sName() derives from specId unless a
    // `k8sName` override is set. Fixing the specId above to match the catalog directory must not
    // silently break this — the two readers want different strings for security-scanner, which is
    // exactly why the override field exists.
    const workloads = gitopsWorkloadNames()
    const undeployed = docsApiSpecEntries()
      .map(s => ({ id: s.id, k8s: s.k8sName ?? s.specId.replace(/^openbank-/, '') }))
      .filter(s => !workloads.has(s.k8s))
      .map(s => `${s.id} → ${s.k8s}`)
    expect(
      undeployed,
      `/docs/api entries whose derived k8s name matches no Deployment/Service/Rollout in `
      + `openbank-infra/gitops — the health probe 404s and the card reads "not deployed" `
      + `forever: ${undeployed.join(', ')}`,
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
