// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Single source of truth for "which service is at which port / container" - and it is DERIVED:
// `buildRegistry` maps the code-generated catalog (catalog.json: every runnable module, with the
// port read from its own application.yaml) onto this shape. Nothing here lists the fleet; the only
// authored data is SERVICE_OVERRIDES (label / naming exceptions). Server code loads the
// catalog through fleet.ts; the browser calls buildRegistry over GET /api/catalog/services.
// Consumed by:
//   - /api/services/health (per-service probe)
//   - /api/services/[name]/docs (Docs-as-Service proxy → live /q/openbank/docs)
//   - any future per-service proxy (logs, SBOM, metrics, …)
//
// The `id` is the short name users see in URLs (`/services/<id>/docs`,
// `/services/<id>/health`). The `container` is the Docker hostname inside
// the openbank-net network. The `port` is the HTTP port both for the
// business API and the management endpoints (we have not yet split into a
// dedicated mgmt port — when we do, add a `mgmtPort` field here).
//
// `container` MUST be the real `openbank-*` module directory name — it is not
// just a compose hostname. In-cluster, `k8sNameOf()` resolves the Kubernetes
// workload from it, so an invented suffix silently resolves to a non-existent
// Service and the page renders `not_deployed` forever with no error anywhere.
// Both invariants — container ⇒ real directory, and k8sNameOf ⇒ real gitops
// workload — are enforced by src/test/service-registry.guard.test.ts.

import { isDomainId, type DomainGroup } from '@/lib/governance/domains'

export interface ServiceEntry {
  /** URL/sidebar identifier, e.g. "account" or "tpp-registry". */
  id: string
  /** Display label. */
  label: string
  /** Logical grouping for UI. */
  group: DomainGroup
  /** Docker container hostname in the openbank-net network. */
  container: string
  /** HTTP port for both business and `/q/...` endpoints. */
  port: number
  /** Management port (`/q/health`), when the module declares one. */
  mgmtPort?: number
  /**
   * Kubernetes workload name, ONLY when it differs from the module directory.
   * Defaults to `container` minus the `openbank-` prefix, which holds for every
   * service but one — `openbank-security-scanner` deploys as
   * `security-scanner-service`. That single mismatch is why this field exists:
   * the name used to be derived by string surgery on `container`, so encoding
   * the k8s name forced `container` to a directory that does not exist, and
   * encoding the directory silently broke discovery. Prefer `k8sNameOf()` over
   * re-deriving it at the call site.
   */
  k8sName?: string
  /** Canonical namespace when GitOps deploys the same workload name for distinct tenants. */
  k8sNamespace?: string
}

/** The Kubernetes Deployment/Service name for an entry. */
export function k8sNameOf(svc: ServiceEntry): string {
  return svc.k8sName ?? svc.container.replace(/^openbank-/, '')
}

/**
 * Presentation metadata that cannot be derived from the code: a display label and the
 * two naming exceptions. Keyed by MODULE DIRECTORY name. This is NOT the list of services — the SET
 * of services comes from the code-derived catalog (`catalog.json`, generate-catalog.mjs: every
 * module applying the `openbank.quarkus-service` convention plugin) and so cannot drift; an entry
 * here for a module the catalog does not know is inert, and a module missing here still appears,
 * with a label derived from its name; grouping comes from governance via the catalog. The guard test asserts every key is a
 * real catalog module.
 */
export const SERVICE_OVERRIDES: Record<string, { label?: string; id?: string; k8sName?: string; k8sNamespace?: string; exposeViaBff?: boolean }> = {
  'openbank-account-service'            : { label: 'Accounts' },
  // ADR-0337 runs a second ledger-service for the pension company. The bank's canonical
  // registry identity remains the ledger namespace; discovery validates the live row there.
  'openbank-ledger-service'             : { label: 'Ledger', k8sNamespace: 'ledger' },
  'openbank-transaction-service'        : { label: 'Transactions' },
  'openbank-balance-service'            : { label: 'Balance' },
  'openbank-product-catalog'            : { label: 'Product Catalog' },
  'openbank-pid-service'                : { label: 'PID' },
  'openbank-kyb-service'                : { label: 'KYB' },
  'openbank-consent-service'            : { label: 'Consent' },
  'openbank-psd2-service'               : { label: 'PSD2' },
  'openbank-tpp-registry-service'       : { label: 'TPP Registry' },
  'openbank-agent-service'              : { label: 'Agent (MCP)' },
  'openbank-sca-service'                : { label: 'SCA' },
  'openbank-party-service'              : { label: 'Parties' },
  'openbank-notification-service'       : { label: 'Notifications' },
  'openbank-audit-service'              : { label: 'Audit' },
  'openbank-kyc-service'                : { label: 'KYC' },
  'openbank-sepa-payment'               : { label: 'SEPA' },
  'openbank-domestic-payment'           : { label: 'Domestic' },
  'openbank-aml-service'                : { label: 'AML' },
  'openbank-card-issuance-service'      : { label: 'Cards' },
  'openbank-fx-service'                 : { label: 'FX' },
  'openbank-security-scanner'           : { label: 'Security', k8sName: 'security-scanner-service' },
  'openbank-standing-order-service'     : { label: 'Standing Orders' },
  'openbank-swift-service'              : { label: 'SWIFT' },
  'openbank-sanctions-service'          : { label: 'Sanctions' },
  'openbank-clearing-service'           : { label: 'Clearing' },
  'openbank-interest-service'           : { label: 'Interest' },
  'openbank-dispute-service'            : { label: 'Disputes' },
  'openbank-sepa-instant'               : { label: 'SEPA Instant' },
  'openbank-vop-service'                : { label: 'VoP' },
  'openbank-customer-edge'              : { label: 'Customer Edge' },
  'openbank-statement-service'          : { label: 'Statements' },
  'openbank-onboarding-service'         : { label: 'Onboarding' },
  'openbank-document-service'           : { label: 'Documents' },
  'openbank-lending-service'            : { label: 'Lending' },
  'openbank-risk-engine'                : { label: 'Risk Engine' },
  'openbank-sdd-service'                : { label: 'SDD' },
  'openbank-copilot-service'            : { label: 'Copilot' },
  'openbank-fraud-service'              : { label: 'Fraud' },
  'openbank-analytics-sink'             : { label: 'Analytics Sink' },
  'openbank-anacredit-service'          : { label: 'AnaCredit' },
  'openbank-case-coordinator-agent'     : { label: 'Case Coordinator', id: 'case-coordinator' },
  'openbank-communication-service'      : { label: 'Communication' },
  // Not business services (agents / auditors / sentinels whose names carry no -agent suffix): never BFF-exposed.
  'openbank-authz-policy-auditor':       { exposeViaBff: false },
  'openbank-control-liveness-sentinel':  { exposeViaBff: false },
  'openbank-flaky-test-hunter':          { exposeViaBff: false },
  'openbank-governance-auditor':         { exposeViaBff: false },
  'openbank-release-steward':            { exposeViaBff: false },
  // Owner decision: mcp-service is reached through admin-ui's own /api/agent/mcp route, not the generic proxy.
  'openbank-mcp-service':                { exposeViaBff: false },
}

/** The subset of a `catalog.json` module this registry needs. */
export interface CatalogFleetModule {
  name: string
  short: string
  kind: string
  dataDomain?: string | null
  runnable?: boolean
  apiTitle?: string | null
  port?: number | null
  mgmtPort?: number | null
}

function titleCase(short: string): string {
  return short.replace(/-service$/, '').split('-').map(w => w.charAt(0).toUpperCase() + w.slice(1)).join(' ')
}

/**
 * Derive the service registry from the catalog: one entry per runnable module that declares a port.
 * Pure (no I/O) so server routes, the browser and the guard test all share one definition.
 */
export function buildRegistry(modules: readonly CatalogFleetModule[]): ServiceEntry[] {
  const entries = modules
    .filter(m => m.runnable === true && typeof m.port === 'number')
    .map((m): ServiceEntry => {
      const o = SERVICE_OVERRIDES[m.name] ?? {}
      return {
        id: o.id ?? m.short.replace(/-service$/, ''),
        label: o.label ?? titleCase(m.short),
        group: isDomainId(m.dataDomain) ? m.dataDomain : 'unclassified',
        container: m.name,
        port: m.port as number,
        ...(typeof m.mgmtPort === 'number' ? { mgmtPort: m.mgmtPort } : {}),
        ...(o.k8sName ? { k8sName: o.k8sName } : {}),
        ...(o.k8sNamespace ? { k8sNamespace: o.k8sNamespace } : {}),
      }
    })
    .sort((a, b) => a.container.localeCompare(b.container))
  assertUnique(entries.map(e => e.id), 'registry id')
  return entries
}

function assertUnique(values: readonly string[], what: string): void {
  const seen = new Set<string>()
  for (const v of values) {
    if (seen.has(v)) throw new Error(`duplicate ${what} '${v}' in the derived service registry - fix SERVICE_OVERRIDES or the module name`)
    seen.add(v)
  }
}

export function findInRegistry(registry: readonly ServiceEntry[], id: string): ServiceEntry | undefined {
  return registry.find(s => s.id === id)
}

/**
 * Modules that are NOT business services and must not be reachable through the operator BFF unless
 * explicitly overridden (`exposeViaBff: true`): AI/ops agents, data sinks, simulators, libraries.
 * Names, not metadata, because the catalog carries no exposure class; the guard test pins the
 * resulting set.
 */
const NON_BFF_PATTERN = /(-agent|-sink|-simulator)$|^openbank-libs/

/** The registry entries `/api/svc` may reach. */
export function isBffExposed(s: ServiceEntry): boolean {
  const o = SERVICE_OVERRIDES[s.container]
  if (o?.exposeViaBff !== undefined) return o.exposeViaBff
  return !NON_BFF_PATTERN.test(s.container)
}

/**
 * The BFF allowlist: `/api/svc/<key>` -> upstream target. Derived from the same registry, so the
 * key set is exactly the catalog's runnable BUSINESS modules - never free-form, and never a
 * caller-supplied host (the host is `container`/`SERVICES_HOST`, the port comes from the module's
 * own config). The key is the Kubernetes workload name, which in-cluster is looked up verbatim in
 * discovery. The returned map has no prototype: `constructor`/`__proto__` are plain missing keys.
 * A duplicate key throws instead of silently overwriting (which would re-route one service to
 * another's upstream).
 */
export function buildProxyAllowlist(registry: readonly ServiceEntry[]): Record<string, { container: string; port: number }> {
  const exposed = registry.filter(isBffExposed)
  assertUnique(exposed.map(s => k8sNameOf(s)), 'BFF allowlist key')
  const out: Record<string, { container: string; port: number }> = Object.create(null)
  for (const s of exposed) out[k8sNameOf(s)] = { container: s.container, port: s.port }
  return out
}

/**
 * Resolves the base URL for talking to a service. Inside Docker (admin-ui
 * container) we use the container hostname; from a developer's host machine
 * we use localhost.
 */
export function serviceBaseUrl(svc: ServiceEntry): string {
  const host = process.env.SERVICES_HOST === 'container'
    ? svc.container
    : (process.env.SERVICES_HOST ?? 'localhost')
  return `http://${host}:${svc.port}`
}
