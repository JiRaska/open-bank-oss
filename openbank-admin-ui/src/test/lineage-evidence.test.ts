// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { parseGovernanceLineage } from '@/lib/governance/lineage-evidence'

const service = {
  serviceName: 'account-service', dataDomain: 'core', dataLineageRole: 'producer',
  lineage: {
    downstream: [{ serviceName: 'ledger-service', relationType: 'api' }],
    interfaces: { apis: ['/api/v1/accounts'], topics: ['openbank.account.created'] },
  },
}

describe('governance lineage evidence', () => {
  it('accepts verified empty and explicit unavailable envelopes as distinct states', () => {
    expect(parseGovernanceLineage({ available: true, services: [] })).toEqual({ available: true, services: [] })
    expect(parseGovernanceLineage({ available: false, services: [] })).toEqual({ available: false, services: [] })
    expect(parseGovernanceLineage({ available: false, services: [service] })).toBeNull()
  })

  it('accepts a valid lineage service and rejects duplicate identities', () => {
    expect(parseGovernanceLineage({ available: true, services: [service] })?.services).toHaveLength(1)
    expect(parseGovernanceLineage({ available: true, services: [service, service] })).toBeNull()
  })

  it('rejects invalid domains, relationships, and interface collections', () => {
    expect(parseGovernanceLineage({ available: true, services: [{ ...service, dataDomain: 'mystery' }] })).toBeNull()
    expect(parseGovernanceLineage({ available: true, services: [{ ...service, lineage: { downstream: [{ serviceName: 'ledger-service', relationType: 'magic' }] } }] })).toBeNull()
    expect(parseGovernanceLineage({ available: true, services: [{ ...service, lineage: { interfaces: { apis: [42] } } }] })).toBeNull()
  })

  it('rejects duplicate and unbounded evidence while returning only renderable fields', () => {
    expect(parseGovernanceLineage({ available: true, services: [{ ...service, lineage: { interfaces: { apis: ['/v1', '/v1'] } } }] })).toBeNull()
    expect(parseGovernanceLineage({ available: true, services: [{ ...service, lineage: { downstream: [
      { serviceName: 'ledger-service', relationType: 'api' },
      { serviceName: 'ledger-service', relationType: 'api' },
    ] } }] })).toBeNull()
    expect(parseGovernanceLineage({ available: true, services: [{ ...service, serviceName: 'x'.repeat(501) }] })).toBeNull()
    expect(parseGovernanceLineage({ available: true, services: [{ ...service, ignored: { unsafe: true } }] })?.services[0]).not.toHaveProperty('ignored')
  })
})
