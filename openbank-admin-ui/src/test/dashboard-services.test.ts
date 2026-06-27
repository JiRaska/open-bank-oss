// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest'

const VALID_GROUPS = ['core', 'payments', 'compliance', 'identity', 'open-banking', 'platform'] as const

const SERVICES = [
  { name: 'account-service',          port: 8100, label: 'Accounts',        group: 'core' },
  { name: 'ledger-service',           port: 8101, label: 'Ledger',          group: 'core' },
  { name: 'transaction-service',      port: 8102, label: 'Transactions',    group: 'core' },
  { name: 'balance-service',          port: 8103, label: 'Balance',         group: 'core' },
  { name: 'sepa-payment',             port: 8115, label: 'SEPA',            group: 'payments' },
  { name: 'domestic-payment',         port: 8116, label: 'Domestic',        group: 'payments' },
  { name: 'card-issuance-service',    port: 8118, label: 'Cards',           group: 'payments' },
  { name: 'fx-service',               port: 8119, label: 'FX',              group: 'payments' },
  { name: 'standing-order-service',   port: 8121, label: 'Standing Orders', group: 'payments' },
  { name: 'swift-service',            port: 8122, label: 'SWIFT',           group: 'payments' },
  { name: 'clearing-service',         port: 8124, label: 'Clearing',        group: 'payments' },
  { name: 'interest-service',         port: 8125, label: 'Interest',        group: 'payments' },
  { name: 'sepa-instant-service',     port: 8127, label: 'SEPA Instant',    group: 'payments' },
  { name: 'aml-service',              port: 8117, label: 'AML',             group: 'compliance' },
  { name: 'kyc-service',              port: 8114, label: 'KYC',             group: 'compliance' },
  { name: 'sanctions-service',        port: 8123, label: 'Sanctions',       group: 'compliance' },
  { name: 'dispute-service',          port: 8135, label: 'Disputes',        group: 'compliance' },
  { name: 'audit-service',            port: 8113, label: 'Audit',           group: 'compliance' },
  { name: 'party-service',            port: 8111, label: 'Parties',         group: 'identity' },
  { name: 'sca-service',              port: 8110, label: 'SCA',             group: 'identity' },
  { name: 'psd2-service',             port: 8107, label: 'PSD2',            group: 'open-banking' },
  { name: 'consent-service',          port: 8106, label: 'Consent',         group: 'open-banking' },
  { name: 'tpp-registry-service',     port: 8108, label: 'TPP Registry',    group: 'open-banking' },
  { name: 'product-catalog-service',  port: 8104, label: 'Product Catalog', group: 'platform' },
  { name: 'notification-service',     port: 8112, label: 'Notifications',   group: 'platform' },
  { name: 'agent-service',            port: 8109, label: 'Agent',           group: 'platform' },
  { name: 'security-scanner',         port: 8120, label: 'Security',        group: 'platform' },
  { name: 'pid-service',              port: 8128, label: 'PID',             group: 'platform' },
]

describe('SERVICES registry', () => {
  it('has no duplicate ports', () => {
    const ports = SERVICES.map(s => s.port)
    const unique = new Set(ports)
    expect(unique.size).toBe(ports.length)
  })

  it('has no duplicate service names', () => {
    const names = SERVICES.map(s => s.name)
    const unique = new Set(names)
    expect(unique.size).toBe(names.length)
  })

  it('every service has required fields', () => {
    for (const s of SERVICES) {
      expect(s.name).toBeTruthy()
      expect(s.port).toBeGreaterThan(0)
      expect(s.label).toBeTruthy()
      expect(s.group).toBeTruthy()
    }
  })

  it('all groups are valid', () => {
    for (const s of SERVICES) {
      expect(VALID_GROUPS).toContain(s.group)
    }
  })

  it('includes pid-service', () => {
    expect(SERVICES.find(s => s.name === 'pid-service')).toBeDefined()
  })

  it('kyc-service appears exactly once', () => {
    const kyc = SERVICES.filter(s => s.name === 'kyc-service')
    expect(kyc).toHaveLength(1)
  })

  it('all ports are in expected range 8100-8200', () => {
    for (const s of SERVICES) {
      expect(s.port).toBeGreaterThanOrEqual(8100)
      expect(s.port).toBeLessThanOrEqual(8200)
    }
  })
})
