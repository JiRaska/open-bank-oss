// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { gatusContainer } from '@/lib/bcp/gatusMapping'
import { buildRegistry } from '@/lib/services/registry'

const fleet = buildRegistry([
  { name: 'openbank-account-service', short: 'account-service', kind: 'service', runnable: true, port: 8100 },
  { name: 'openbank-psd2-service', short: 'psd2-service', kind: 'service', runnable: true, port: 8107 },
  { name: 'openbank-tpp-registry-service', short: 'tpp-registry-service', kind: 'service', runnable: true, port: 8108 },
  { name: 'openbank-sepa-instant', short: 'sepa-instant', kind: 'service', runnable: true, port: 8127 },
])

describe('BCP health names come from the derived fleet', () => {
  it('resolves regular and historical Gatus aliases to real catalog modules', () => {
    expect(gatusContainer('core-banking_account-service', fleet)).toBe('openbank-account-service')
    expect(gatusContainer('core-banking_account-service-—-liveness', fleet)).toBe('openbank-account-service')
    expect(gatusContainer('psd2-open-banking_psd2-api-(čobs)', fleet)).toBe('openbank-psd2-service')
    expect(gatusContainer('psd2-open-banking_tpp-registry', fleet)).toBe('openbank-tpp-registry-service')
    expect(gatusContainer('payments_sepa-instant-service', fleet)).toBe('openbank-sepa-instant')
  })

  it('does not invent a service when the catalog omits it', () => {
    expect(gatusContainer('core-banking_ledger-service', fleet)).toBeUndefined()
    expect(gatusContainer('constructor', fleet)).toBeUndefined()
    expect(gatusContainer('core-banking_constructor', fleet)).toBeUndefined()
  })

  it('retains separately declared infrastructure probes', () => {
    expect(gatusContainer('observability_prometheus', fleet)).toBe('openbank-prometheus')
    expect(gatusContainer('psd2-open-banking_api-gateway', fleet)).toBe('openbank-api-gateway')
  })
})
