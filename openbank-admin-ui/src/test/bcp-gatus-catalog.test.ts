// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { gatusContainer } from '@/lib/services/gatus'
import { buildRegistry, type CatalogFleetModule } from '@/lib/services/registry'

const catalog = JSON.parse(readFileSync(path.resolve(__dirname, '../../catalog.json'), 'utf8')) as {
  services: CatalogFleetModule[]
}
const registry = buildRegistry(catalog.services)

describe('Gatus endpoint resolution', () => {
  it.each([
    ['core-banking_account-service', 'openbank-account-service'],
    ['core-banking_account-service-—-liveness', 'openbank-account-service'],
    ['core-banking_product-catalog', 'openbank-product-catalog'],
    ['core-banking_ledger-service', 'openbank-ledger-service'],
    ['core-banking_transaction-service', 'openbank-transaction-service'],
    ['core-banking_balance-service', 'openbank-balance-service'],
    ['core-banking_pid-service', 'openbank-pid-service'],
    ['core-banking_party-service', 'openbank-party-service'],
    ['core-banking_notification-service', 'openbank-notification-service'],
    ['payments_fx-service', 'openbank-fx-service'],
    ['payments_card-issuance-service', 'openbank-card-issuance-service'],
    ['payments_standing-order-service', 'openbank-standing-order-service'],
    ['payments_swift-service', 'openbank-swift-service'],
    ['payments_clearing-service', 'openbank-clearing-service'],
    ['payments_interest-service', 'openbank-interest-service'],
    ['payments_dispute-service', 'openbank-dispute-service'],
    ['psd2-open-banking_sepa-payment', 'openbank-sepa-payment'],
    ['psd2-open-banking_domestic-payment', 'openbank-domestic-payment'],
    ['psd2-open-banking_consent-service', 'openbank-consent-service'],
    ['psd2-open-banking_sca-service', 'openbank-sca-service'],
    ['psd2-open-banking_psd2-api-(čobs)', 'openbank-psd2-service'],
    ['psd2-open-banking_psd2-sandbox', 'openbank-psd2-service'],
    ['psd2-open-banking_tpp-registry', 'openbank-tpp-registry-service'],
    ['compliance_security-scanner', 'openbank-security-scanner'],
    ['compliance_aml-service', 'openbank-aml-service'],
    ['compliance_audit-service', 'openbank-audit-service'],
    ['compliance_kyc-service', 'openbank-kyc-service'],
    ['compliance_sanctions-service', 'openbank-sanctions-service'],
    ['payments_sepa-instant-service', 'openbank-sepa-instant'],
    ['ai---automation_agent-service-(mcp)', 'openbank-agent-service'],
    ['psd2-open-banking_api-gateway', 'openbank-api-gateway'],
    ['infrastructure_postgresql', 'openbank-postgres'],
    ['infrastructure_keycloak-(iam)', 'openbank-keycloak'],
    ['infrastructure_kafka', 'openbank-kafka'],
    ['infrastructure_valkey-(cache)', 'openbank-valkey'],
    ['infrastructure_schema-registry', 'openbank-schema-registry'],
    ['infrastructure_vault-(secrets)', 'openbank-vault'],
    ['observability_grafana', 'openbank-grafana'],
    ['observability_prometheus', 'openbank-prometheus'],
    ['observability_loki', 'openbank-loki'],
    ['observability_tempo', 'openbank-tempo'],
  ])('resolves %s to %s', (key, expected) => {
    expect(gatusContainer(key, registry)).toBe(expected)
  })

  it('automatically resolves a new catalog workload without adding a Gatus mapping', () => {
    const extended = buildRegistry([
      ...catalog.services,
      { name: 'openbank-new-service', short: 'new-service', kind: 'service', runnable: true, port: 8000 },
    ])
    expect(gatusContainer('core-banking_new-service', extended)).toBe('openbank-new-service')
  })

  it.each([
    'core-banking_unknown-service',
    'infrastructure_unknown',
    'malformed',
  ])('does not invent an upstream for %s', key => {
    expect(gatusContainer(key, registry)).toBeUndefined()
  })
})
