// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// FinOps cost-allocation taxonomy (ADR-0062).
//
// Read-only reflection of the PLAN: this mirrors `openbank-libs/governance/rules.yaml:
// finops_cost_groups` (business flow -> services). The admin-ui DISPLAYS this; it does not
// generate it (root CLAUDE.md rule #3 / #7). When a CI-generated catalog carries the
// allocation taxonomy this module becomes a fallback — same trajectory as serverlessTiers.ts.
//
// CANONICAL service key = the manifest serviceName (NO `openbank-` prefix), which also equals
// the gitops Deployment metadata.name. `canonicalServiceId()` strips a leading `openbank-` and
// a trailing `-service`-less alias is NOT attempted — ids must match manifest.ts exactly. The
// data-domain (cost-center) lens is read straight from the governance manifest, so there is no
// second hand-maintained service->domain table to rot.

import type { DataDomain } from '@/lib/governance/manifest'
import { getGovernanceManifest } from '@/lib/governance/governanceLoader'

export interface CostGroup {
  /** Stable id, e.g. `sepa-payment-flow`. */
  id: string
  labelEn: string
  labelCs: string
  /** Canonical service ids (manifest serviceName). A service may appear in many groups. */
  services: string[]
  /** Regulatory anchor shown in the tooltip, when the flow has one. */
  regulatoryRef?: string
}

// Mirror of rules.yaml:finops_cost_groups. Keep IN SYNC with that file; finops-taxonomy.test.ts
// fails the build if any service id here is unknown to the governance manifest.
export const COST_GROUPS: readonly CostGroup[] = [
  { id: 'account-management', labelEn: 'Account Management', labelCs: 'Správa účtů',
    services: ['account-service', 'party-service', 'kyc-service', 'sca-service'] },
  { id: 'double-entry-ledger', labelEn: 'Double-Entry Ledger', labelCs: 'Podvojné účetnictví',
    services: ['ledger-service', 'transaction-service'], regulatoryRef: 'CNB §4 — books of account' },
  { id: 'balance-projection', labelEn: 'Balance Projection', labelCs: 'Projekce zůstatků',
    services: ['balance-service', 'transaction-service'] },
  { id: 'compliance-screening', labelEn: 'Compliance Gate (AML/Sanctions)', labelCs: 'Compliance brána (AML/sankce)',
    services: ['aml-service', 'sanctions-service', 'kyc-service'], regulatoryRef: '5AMLD Art. 18 — screening before payout' },
  { id: 'sepa-payment-flow', labelEn: 'SEPA Credit Transfer', labelCs: 'SEPA úhrada',
    services: ['sepa-payment', 'transaction-service', 'ledger-service', 'account-service', 'balance-service', 'aml-service', 'sanctions-service', 'clearing-service'], regulatoryRef: 'PSD2 / pacs.008' },
  { id: 'sepa-instant-flow', labelEn: 'SEPA Instant', labelCs: 'SEPA okamžitá platba',
    services: ['sepa-instant', 'transaction-service', 'ledger-service', 'aml-service', 'sanctions-service', 'clearing-service'], regulatoryRef: 'PSD2 / 10s SLA' },
  { id: 'domestic-payment-flow', labelEn: 'Domestic Payment', labelCs: 'Tuzemská platba',
    services: ['domestic-payment', 'transaction-service', 'ledger-service', 'aml-service', 'sanctions-service'], regulatoryRef: 'CNB CERTIS' },
  { id: 'swift-payment-flow', labelEn: 'SWIFT Cross-Border', labelCs: 'SWIFT zahraniční platba',
    services: ['swift-service', 'transaction-service', 'ledger-service', 'aml-service', 'sanctions-service'], regulatoryRef: 'pacs.008 / cross-border' },
  { id: 'fx-conversion', labelEn: 'FX Conversion', labelCs: 'Měnová konverze',
    services: ['fx-service', 'transaction-service', 'ledger-service'] },
  { id: 'card-issuance', labelEn: 'Card Issuance', labelCs: 'Vydávání karet',
    services: ['card-issuance-service', 'account-service', 'dispute-service'], regulatoryRef: 'PCI DSS' },
  { id: 'open-banking-psd2', labelEn: 'Open Banking (PSD2)', labelCs: 'Open Banking (PSD2)',
    services: ['psd2-service', 'consent-service', 'sca-service', 'tpp-registry-service', 'pid-service'], regulatoryRef: 'PSD2 AISP/PISP' },
  { id: 'interest-accrual', labelEn: 'Interest Accrual', labelCs: 'Úročení',
    services: ['interest-service', 'account-service'] },
  { id: 'standing-orders', labelEn: 'Standing Orders', labelCs: 'Trvalé příkazy',
    services: ['standing-order-service', 'transaction-service'] },
  { id: 'audit-trail', labelEn: 'Audit Trail', labelCs: 'Auditní stopa',
    services: ['audit-service'], regulatoryRef: 'DORA Art. 17 — immutable' },
]

// service -> domain lookup, built once from the governance manifest (the domain source of truth).
const DOMAIN_BY_SERVICE: ReadonlyMap<string, DataDomain> = new Map(
  getGovernanceManifest().map(e => [e.serviceName, e.dataDomain]),
)

/** Strip a leading `openbank-` so callers can pass either convention; returns the manifest key. */
export function canonicalServiceId(id: string): string {
  return id.replace(/^openbank-/, '')
}

/** Data domain (cost-center) for a service id, or undefined if the manifest doesn't know it. */
export function domainForService(id: string): DataDomain | undefined {
  return DOMAIN_BY_SERVICE.get(canonicalServiceId(id))
}

/** All service ids the governance manifest knows — the universe a footprint must map into. */
export function knownServiceIds(): Set<string> {
  return new Set(DOMAIN_BY_SERVICE.keys())
}
