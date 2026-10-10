// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Single source of truth for "which service is at which port / container" - and it is DERIVED:
// `buildRegistry` maps the code-generated catalog (catalog.json: every runnable module, with the
// port read from its own application.yaml) onto this shape. Nothing here lists the fleet; the only
// authored data is SERVICE_OVERRIDES (label / group / naming exceptions). Server code loads the
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

export interface ServiceEntry {
  /** URL/sidebar identifier, e.g. "account" or "tpp-registry". */
  id: string
  /** Display label. */
  label: string
  /** Logical grouping for UI. */
  group: 'core' | 'identity' | 'open-banking' | 'payments' | 'compliance' | 'platform'
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
}

/** The Kubernetes Deployment/Service name for an entry. */
export function k8sNameOf(svc: ServiceEntry): string {
  return svc.k8sName ?? svc.container.replace(/^openbank-/, '')
}

/**
 * Presentation metadata that cannot be derived from the code: a display label, a UI group, and the
 * two naming exceptions. Keyed by MODULE DIRECTORY name. This is NOT the list of services — the SET
 * of services comes from the code-derived catalog (`catalog.json`, generate-catalog.mjs: every
 * module applying the `openbank.quarkus-service` convention plugin) and so cannot drift; an entry
 * here for a module the catalog does not know is inert, and a module missing here still appears,
 * with a label derived from its name and group `platform`. The guard test asserts every key is a
 * real catalog module.
 */
export const SERVICE_OVERRIDES: Record<string, { label?: string; group?: ServiceEntry['group']; id?: string; k8sName?: string }> = {
  'openbank-account-service'            : { label: 'Accounts', group: 'core' },
  'openbank-ledger-service'             : { label: 'Ledger', group: 'core' },
  'openbank-transaction-service'        : { label: 'Transactions', group: 'core' },
  'openbank-balance-service'            : { label: 'Balance', group: 'core' },
  'openbank-product-catalog'            : { label: 'Product Catalog', group: 'core' },
  'openbank-pid-service'                : { label: 'PID', group: 'identity' },
  'openbank-kyb-service'                : { label: 'KYB', group: 'identity' },
  'openbank-consent-service'            : { label: 'Consent', group: 'open-banking' },
  'openbank-psd2-service'               : { label: 'PSD2', group: 'open-banking' },
  'openbank-tpp-registry-service'       : { label: 'TPP Registry', group: 'open-banking' },
  'openbank-agent-service'              : { label: 'Agent (MCP)', group: 'platform' },
  'openbank-sca-service'                : { label: 'SCA', group: 'identity' },
  'openbank-party-service'              : { label: 'Parties', group: 'identity' },
  'openbank-notification-service'       : { label: 'Notifications', group: 'platform' },
  'openbank-audit-service'              : { label: 'Audit', group: 'compliance' },
  'openbank-kyc-service'                : { label: 'KYC', group: 'compliance' },
  'openbank-sepa-payment'               : { label: 'SEPA', group: 'payments' },
  'openbank-domestic-payment'           : { label: 'Domestic', group: 'payments' },
  'openbank-aml-service'                : { label: 'AML', group: 'compliance' },
  'openbank-card-issuance-service'      : { label: 'Cards', group: 'payments' },
  'openbank-fx-service'                 : { label: 'FX', group: 'payments' },
  'openbank-security-scanner'           : { label: 'Security', group: 'platform', k8sName: 'security-scanner-service' },
  'openbank-standing-order-service'     : { label: 'Standing Orders', group: 'payments' },
  'openbank-swift-service'              : { label: 'SWIFT', group: 'payments' },
  'openbank-sanctions-service'          : { label: 'Sanctions', group: 'compliance' },
  'openbank-clearing-service'           : { label: 'Clearing', group: 'payments' },
  'openbank-interest-service'           : { label: 'Interest', group: 'payments' },
  'openbank-dispute-service'            : { label: 'Disputes', group: 'compliance' },
  'openbank-sepa-instant'               : { label: 'SEPA Instant', group: 'payments' },
  'openbank-vop-service'                : { label: 'VoP', group: 'payments' },
  'openbank-customer-edge'              : { label: 'Customer Edge', group: 'platform' },
  'openbank-statement-service'          : { label: 'Statements', group: 'compliance' },
  'openbank-onboarding-service'         : { label: 'Onboarding', group: 'compliance' },
  'openbank-document-service'           : { label: 'Documents', group: 'platform' },
  'openbank-lending-service'            : { label: 'Lending', group: 'payments' },
  'openbank-risk-engine'                : { label: 'Risk Engine', group: 'core' },
  'openbank-sdd-service'                : { label: 'SDD', group: 'payments' },
  'openbank-copilot-service'            : { label: 'Copilot', group: 'platform' },
  'openbank-fraud-service'              : { label: 'Fraud', group: 'compliance' },
  'openbank-analytics-sink'             : { label: 'Analytics Sink', group: 'platform' },
  'openbank-anacredit-service'          : { label: 'AnaCredit', group: 'compliance' },
  'openbank-case-coordinator-agent'     : { label: 'Case Coordinator', group: 'platform', id: 'case-coordinator' },
  'openbank-communication-service'      : { label: 'Communication', group: 'platform' },
}

/** The subset of a `catalog.json` module this registry needs. */
export interface CatalogFleetModule {
  name: string
  short: string
  kind: string
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
  return modules
    .filter(m => m.runnable === true && typeof m.port === 'number')
    .map((m): ServiceEntry => {
      const o = SERVICE_OVERRIDES[m.name] ?? {}
      return {
        id: o.id ?? m.short.replace(/-service$/, ''),
        label: o.label ?? titleCase(m.short),
        group: o.group ?? 'platform',
        container: m.name,
        port: m.port as number,
        ...(typeof m.mgmtPort === 'number' ? { mgmtPort: m.mgmtPort } : {}),
        ...(o.k8sName ? { k8sName: o.k8sName } : {}),
      }
    })
    .sort((a, b) => a.container.localeCompare(b.container))
}

export function findInRegistry(registry: readonly ServiceEntry[], id: string): ServiceEntry | undefined {
  return registry.find(s => s.id === id)
}

/**
 * The BFF allowlist: `/api/svc/<key>` → upstream target. Derived from the same registry, so the
 * key set is exactly the catalog's runnable modules — never free-form, and never a caller-supplied
 * host (the host is `container`/`SERVICES_HOST`, the port comes from the module's own config).
 * The key is the Kubernetes workload name, which in-cluster is looked up verbatim in discovery.
 */
export function buildProxyAllowlist(registry: readonly ServiceEntry[]): Record<string, { container: string; port: number }> {
  return Object.fromEntries(registry.map(s => [k8sNameOf(s), { container: s.container, port: s.port }]))
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
