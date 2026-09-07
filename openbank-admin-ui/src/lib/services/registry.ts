// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// Single source of truth for "which service is at which port / container".
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

export const SERVICE_REGISTRY: ServiceEntry[] = [
  { id: 'account',            label: 'Accounts',         group: 'core',         container: 'openbank-account-service',        port: 8100 },
  { id: 'ledger',             label: 'Ledger',           group: 'core',         container: 'openbank-ledger-service',         port: 8101 },
  { id: 'transaction',        label: 'Transactions',     group: 'core',         container: 'openbank-transaction-service',    port: 8102 },
  { id: 'balance',            label: 'Balance',          group: 'core',         container: 'openbank-balance-service',        port: 8103 },
  { id: 'product-catalog',    label: 'Product Catalog',  group: 'core',         container: 'openbank-product-catalog',        port: 8104 },
  { id: 'pid',                label: 'PID',              group: 'identity',     container: 'openbank-pid-service',            port: 8105 },
  { id: 'consent',            label: 'Consent',          group: 'open-banking', container: 'openbank-consent-service',        port: 8106 },
  { id: 'psd2',               label: 'PSD2',             group: 'open-banking', container: 'openbank-psd2-service',           port: 8107 },
  { id: 'tpp-registry',       label: 'TPP Registry',     group: 'open-banking', container: 'openbank-tpp-registry-service',   port: 8108 },
  { id: 'agent',              label: 'Agent (MCP)',      group: 'platform',     container: 'openbank-agent-service',          port: 8109 },
  { id: 'sca',                label: 'SCA',              group: 'identity',     container: 'openbank-sca-service',            port: 8110 },
  { id: 'party',              label: 'Parties',          group: 'identity',     container: 'openbank-party-service',          port: 8111 },
  { id: 'notification',       label: 'Notifications',    group: 'platform',     container: 'openbank-notification-service',   port: 8112 },
  { id: 'audit',              label: 'Audit',            group: 'compliance',   container: 'openbank-audit-service',          port: 8113 },
  { id: 'kyc',                label: 'KYC',              group: 'compliance',   container: 'openbank-kyc-service',            port: 8114 },
  { id: 'sepa-payment',       label: 'SEPA',             group: 'payments',     container: 'openbank-sepa-payment',           port: 8115 },
  { id: 'domestic-payment',   label: 'Domestic',         group: 'payments',     container: 'openbank-domestic-payment',       port: 8116 },
  { id: 'aml',                label: 'AML',              group: 'compliance',   container: 'openbank-aml-service',            port: 8117 },
  { id: 'card-issuance',      label: 'Cards',            group: 'payments',     container: 'openbank-card-issuance-service', port: 8118 },
  { id: 'fx',                 label: 'FX',               group: 'payments',     container: 'openbank-fx-service',             port: 8119 },
  { id: 'security-scanner',   label: 'Security',         group: 'platform',     container: 'openbank-security-scanner',       port: 8120, k8sName: 'security-scanner-service' },
  { id: 'standing-order',     label: 'Standing Orders',  group: 'payments',     container: 'openbank-standing-order-service', port: 8121 },
  { id: 'swift',              label: 'SWIFT',            group: 'payments',     container: 'openbank-swift-service',          port: 8122 },
  { id: 'sanctions',          label: 'Sanctions',        group: 'compliance',   container: 'openbank-sanctions-service',     port: 8123 },
  { id: 'clearing',           label: 'Clearing',         group: 'payments',     container: 'openbank-clearing-service',       port: 8124 },
  { id: 'interest',           label: 'Interest',         group: 'payments',     container: 'openbank-interest-service',       port: 8125 },
  { id: 'dispute',            label: 'Disputes',         group: 'compliance',   container: 'openbank-dispute-service',        port: 8135 },
  { id: 'sepa-instant',       label: 'SEPA Instant',     group: 'payments',     container: 'openbank-sepa-instant',           port: 8127 },
  { id: 'vop',                label: 'VoP',              group: 'payments',     container: 'openbank-vop-service',           port: 8149 },
  { id: 'customer-edge',     label: 'Customer Edge',    group: 'platform',     container: 'openbank-customer-edge',          port: 8128 },
  { id: 'statement',         label: 'Statements',       group: 'compliance',   container: 'openbank-statement-service',      port: 8136 },
  { id: 'onboarding',        label: 'Onboarding',       group: 'compliance',   container: 'openbank-onboarding-service',     port: 8130 },
  { id: 'document',          label: 'Documents',        group: 'platform',     container: 'openbank-document-service',       port: 8143 },
  { id: 'lending',           label: 'Lending',          group: 'payments',     container: 'openbank-lending-service',        port: 8126 },
  { id: 'sdd',               label: 'SDD',              group: 'payments',     container: 'openbank-sdd-service',            port: 8129 },
  { id: 'copilot',           label: 'Copilot',          group: 'platform',     container: 'openbank-copilot-service',        port: 8131 },
  { id: 'fraud',             label: 'Fraud',            group: 'compliance',   container: 'openbank-fraud-service',          port: 8133 },
  { id: 'analytics-sink',    label: 'Analytics Sink',   group: 'platform',     container: 'openbank-analytics-sink',         port: 8134 },
  { id: 'anacredit',         label: 'AnaCredit',        group: 'compliance',   container: 'openbank-anacredit-service',      port: 8137 },
  { id: 'case-coordinator',  label: 'Case Coordinator', group: 'platform',     container: 'openbank-case-coordinator-agent', port: 8146 },
  { id: 'card-processing',   label: 'Card Processing',  group: 'payments',     container: 'openbank-card-processing-service', port: 8157 },
]

export function findService(id: string): ServiceEntry | undefined {
  return SERVICE_REGISTRY.find(s => s.id === id)
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
