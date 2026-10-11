// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ── Admin-UI service-registry drift rule (enforced) ────────────────────────
//
// The SET of services the console knows is DERIVED, not typed: scripts/generate-catalog.mjs walks
// the monorepo (every module applying the `openbank.quarkus-service` convention plugin, with the
// port from its own application.yaml) and `buildRegistry` / `buildProxyAllowlist`
// (src/lib/services/registry.ts) turn that into the registry, the health/config probe lists, the
// service-map nodes and the /api/svc BFF allowlist. This file used to cross-check three hand-kept
// registries against each other; those no longer exist, so the checks that remain are about the
// parts that are still authored (SERVICE_OVERRIDES: labels, groups, naming exceptions) and about
// the derivation staying total:
//
//   - the derived set is exactly the runnable modules on disk (it cannot go stale or short);
//   - every override names a real runnable module and a real gitops workload;
//   - nobody reintroduces a hand-typed fleet list (negative-tested scan below).
//
// None of the original failure modes throw: `sepa-instant-service` (a key matching no Deployment)
// left a payments panel on `not_deployed` for weeks; an invented `container` resolved docs to a
// non-existent Service. Hence a test that re-derives the truth from the repo and gitops.
//
// If this test flags your change: fix the source (the module, or SERVICE_OVERRIDES) - do not add
// to an allowlist unless your service genuinely matches the documented exception.

import { describe, it, expect } from 'vitest'
import { readFileSync, readdirSync, existsSync, statSync } from 'fs'
import path from 'path'

import {
  SERVICE_OVERRIDES,
  buildProxyAllowlist,
  isBffExposed,
  buildRegistry,
  k8sNameOf,
  type CatalogFleetModule,
} from '@/lib/services/registry'

const ADMIN_UI = path.resolve(__dirname, '../..')
const REPO = path.resolve(ADMIN_UI, '..')
const GITOPS = path.join(REPO, 'openbank-infra', 'gitops')
const GITOPS_APPS = path.join(GITOPS, 'apps')

const SERVICES_PAGE = path.join(ADMIN_UI, 'src/app/services/page.tsx')

// ── Exceptions (tight, documented, code-backed — NOT an escape hatch) ───────

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

function catalogModules(): CatalogFleetModule[] {
  const raw = readFileSync(path.join(ADMIN_UI, 'catalog.json'), 'utf-8')
  return (JSON.parse(raw) as { services: CatalogFleetModule[] }).services
}

const REGISTRY = buildRegistry(catalogModules())
const ALLOWLIST = buildProxyAllowlist(REGISTRY)

// ── The rules ──────────────────────────────────────────────────────────────

describe('service registry drift guard', () => {
  it('the derived registry is exactly the runnable modules on disk', () => {
    // The whole point: nothing here is typed, so the count must equal what the repo contains.
    // A module is runnable when its build applies the `openbank.quarkus-service` convention plugin
    // and a runtime libs dependency (the same predicate generate-catalog.mjs uses). Recomputed here
    // straight from the build scripts, not from catalog.json, so a generator that stops emitting
    // modules is caught too.
    const onDisk = [...moduleDirs()]
      .filter(dir => {
        const gradle = path.join(REPO, dir, 'build.gradle.kts')
        if (!existsSync(gradle)) return false
        const src = readFileSync(gradle, 'utf-8')
        const viaConvention = /^\s*(?:plugins\s*\{\s*)?id\(["']openbank\.quarkus-service["']\)/m.test(src)
          && (src.includes('project(":openbank-libs-runtime")') || src.includes('project(":openbank-libs")'))
        return viaConvention || /^\s*alias\(libs\.plugins\.quarkus\)/m.test(src)
      })
      .sort()
    expect(onDisk.length, 'no runnable modules found on disk - the predicate has drifted').toBeGreaterThan(40)
    expect(REGISTRY.map(s => s.container).sort()).toEqual(onDisk)
    // Every one of them carries a real port from its own application.yaml.
    const noPort = catalogModules().filter(m => m.runnable === true && typeof m.port !== 'number').map(m => m.name)
    expect(noPort, `runnable modules with no readable quarkus.http.port: ${noPort.join(', ')}`).toEqual([])
  })

  it('the derived registry also covers every module that has a version.txt and a quarkus service build', () => {
    // Cross-check against the release axis: a released service (version.txt + runnable build) that
    // is missing from the registry would be invisible to health, config, the map and the BFF.
    const released = [...moduleDirs()]
      .filter(dir => existsSync(path.join(REPO, dir, 'version.txt')))
      .filter(dir => REGISTRY.some(s => s.container === dir))
    expect(released.length).toBeGreaterThan(40)
    const catalogRunnable = catalogModules().filter(m => m.runnable === true).map(m => m.name).sort()
    expect(REGISTRY.map(s => s.container).sort()).toEqual(catalogRunnable)
  })

  it('/services hand-lists only the libs documentation bundle (every service card is derived)', () => {
    const src = readFileSync(SERVICES_PAGE, 'utf-8')
    const block = src.match(/const STATIC_CANDIDATES\s*=\s*\[([\s\S]*?)\n\]\s*as const/)
    expect(block, 'STATIC_CANDIDATES literal not found in the /services page').toBeTruthy()
    const ids = [...block![1].matchAll(/\{\s*id:\s*'([a-z0-9-]+)'/g)].map(m => m[1])
    expect(ids, 'only `libs` (a doc bundle, no runtime module) may be hand-listed').toEqual(['libs'])
  })

  it('every registry container is a real openbank-* module directory', () => {
    const dirs = moduleDirs()
    const bogus = REGISTRY.filter(s => !dirs.has(s.container)).map(s => `${s.id} -> ${s.container}`)
    expect(bogus).toEqual([])
  })

  it('every SERVICE_OVERRIDES key is a runnable catalog module (an override cannot outlive its module)', () => {
    const runnable = new Set(catalogModules().filter(m => m.runnable === true).map(m => m.name))
    const stale = Object.keys(SERVICE_OVERRIDES).filter(k => !runnable.has(k))
    expect(
      stale,
      `SERVICE_OVERRIDES names modules that are not runnable catalog modules (renamed or deleted?): ${stale.join(', ')}`,
    ).toEqual([])
  })

  it('every k8sName override resolves to a real gitops workload', () => {
    // k8sNameOf() is what docs.ts and the BFF actually call to reach a service in-cluster. A
    // module with NO override is allowed to have no workload (not deployed yet - the console says
    // so honestly); an override exists precisely because the workload name differs, so it must hit.
    const workloads = gitopsWorkloadNames()
    const missing = REGISTRY
      .filter(s => s.k8sName)
      .filter(s => !workloads.has(k8sNameOf(s)))
      .map(s => `${s.id} -> ${k8sNameOf(s)}`)
    expect(missing).toEqual([])
  })

  it('k8sName is only set where it actually differs from the directory', () => {
    const redundant = REGISTRY
      .filter(s => s.k8sName && s.k8sName === s.container.replace(/^openbank-/, ''))
      .map(s => s.id)
    expect(redundant, `k8sName equals the default derivation - drop the field: ${redundant.join(', ')}`).toEqual([])
  })

  it('the BFF allowlist is exactly the derived registry (an allowlist, never a pass-through)', () => {
    // /api/svc/<key> is a security boundary: the key selects an upstream host. It must be a closed
    // set built from catalog modules - no key outside it resolves, and the host is the module's
    // own container name, never a caller-supplied value.
    expect(Object.keys(ALLOWLIST).sort()).toEqual(REGISTRY.filter(isBffExposed).map(s => k8sNameOf(s)).sort())
    for (const [key, target] of Object.entries(ALLOWLIST)) {
      expect(key, 'allowlist keys are plain DNS-label workload names').toMatch(/^[a-z0-9]([a-z0-9-]*[a-z0-9])?$/)
      expect(target.container).toMatch(/^openbank-[a-z0-9-]+$/)
      expect(Number.isInteger(target.port) && target.port > 0 && target.port < 65536).toBe(true)
    }
    for (const hostile of ['../etc', 'localhost', 'evil.example.com', 'openbank-account-service/../x', '__proto__', 'constructor', '']) {
      expect(Object.hasOwn(ALLOWLIST, hostile), `${hostile} must not be allowlisted`).toBe(false)
    }
  })

  it('the BFF never exposes agents, sinks, simulators or libs (unless explicitly overridden)', () => {
    const exposed = new Set(Object.values(ALLOWLIST).map(t => t.container))
    const leaked = REGISTRY
      .filter(s => /(-agent|-sink|-simulator)$|^openbank-libs/.test(s.container))
      .filter(s => exposed.has(s.container) && SERVICE_OVERRIDES[s.container]?.exposeViaBff !== true)
    expect(leaked.map(s => s.container)).toEqual([])
    for (const name of ['openbank-devops-agent', 'openbank-finops-agent', 'openbank-analytics-sink', 'openbank-clearing-simulator',
      'openbank-release-steward', 'openbank-governance-auditor', 'openbank-mcp-service']) {
      expect(exposed.has(name), `${name} must not be BFF-exposed`).toBe(false)
    }
    // Every module the old hand-kept BFF map served remains reachable.
    for (const key of ['account-service', 'ledger-service', 'product-catalog', 'security-scanner-service', 'sepa-instant', 'kyb-service', 'risk-engine']) {
      expect(Object.hasOwn(ALLOWLIST, key), key).toBe(true)
    }
    expect(REGISTRY.filter(isBffExposed).length).toBe(Object.keys(ALLOWLIST).length)
  })

  it('duplicate registry ids / BFF keys throw at build instead of silently overwriting', () => {
    const mod = (name: string, port: number): CatalogFleetModule =>
      ({ name, short: name.replace(/^openbank-/, ''), kind: 'service', runnable: true, port })
    // Same short name cannot occur on disk, so force the collision through two modules mapping to one id.
    expect(() => buildRegistry([mod('openbank-dup-service', 1), mod('openbank-dup-service', 2)])).toThrow(/duplicate registry id/)
    const entry = { id: 'a', label: 'A', group: 'core' as const, container: 'openbank-a-service', port: 1 }
    expect(() => buildProxyAllowlist([entry, { ...entry, id: 'b', container: 'openbank-b-service', k8sName: 'a-service' }])).toThrow(/duplicate BFF allowlist key/)
  })

  it('every svcUrl() key exists in the BFF allowlist (no caller pointing at an unknown service)', () => {
    // A page calling svcUrl('campaign-service', ...) with no such key gets "Unknown service" from
    // the proxy and renders as "not responding" - a deployed, healthy service that looks down.
    // That is exactly what shipped with the campaign console (#2749): unit tests stubbed `fetch`,
    // so the proxy was never on the path they exercised.
    const keys = new Set(Object.keys(ALLOWLIST))
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
    expect(callers.length, 'no svcUrl() callers found - the matcher has drifted').toBeGreaterThan(0)
    const unknown = callers.filter(c => !keys.has(c.key))
    expect(
      unknown.map(c => `${c.file} -> ${c.key}`),
      'svcUrl() callers naming a key the allowlist does not derive. The proxy answers '
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

  it('registry ids and k8s names are unique', () => {
    const ids = REGISTRY.map(s => s.id)
    expect(ids.length, 'duplicate id in the derived registry').toBe(new Set(ids).size)
    const keys = REGISTRY.map(s => k8sNameOf(s))
    expect(keys.length, 'duplicate k8s name in the derived registry').toBe(new Set(keys).size)
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

  it('SERVICE_OVERRIDES stays presentation-only', () => {
    // Overrides carry label/group/id/k8sName. If they start carrying ports or other facts, the
    // list has become a second source of truth again - derive the fact in generate-catalog.mjs.
    for (const [name, o] of Object.entries(SERVICE_OVERRIDES)) {
      expect(Object.keys(o).filter(k => !['label', 'group', 'id', 'k8sName', 'exposeViaBff'].includes(k)), name).toEqual([])
    }
  })
})
