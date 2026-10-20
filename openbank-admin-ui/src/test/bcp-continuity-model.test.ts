// SPDX-License-Identifier: Apache-2.0
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  bcpCatalogCoverageIsComplete,
  buildBcpRoster,
  parseBcpPolicy,
  type BcpCatalogService,
} from '@/lib/bcp/continuityModel'

const catalog = JSON.parse(readFileSync(path.resolve(__dirname, '../../catalog.json'), 'utf8')) as {
  services: BcpCatalogService[]
}
const policyMarkdown = readFileSync(path.resolve(__dirname, '../../../docs/bcp/bcp-policy.md'), 'utf8')
const policy = parseBcpPolicy(policyMarkdown)
if (!policy) throw new Error('canonical BCP policy §2 could not be parsed')

const graph = { edges: [], infraEdges: [], infraNodes: [{ id: 'infra:keycloak', label: 'Keycloak', kind: 'infra' }] }

describe('BCP recovery roster', () => {
  it('reconciles every current catalog service exactly once, including new services under the policy T3 default', () => {
    const current = catalog.services.filter(service => service.runnable === true)
    const newService: BcpCatalogService = {
      name: 'openbank-future-service', short: 'future-service', kind: 'service', runnable: true,
    }
    const roster = buildBcpRoster([...current, newService], graph, policy)

    expect(bcpCatalogCoverageIsComplete(roster)).toBe(true)
    expect(roster.catalogServiceCount).toBe(current.length + 1)
    expect(roster.tiers.flatMap(tier => tier.services.map(service => service.name)))
      .toContain('future-service')
    expect(roster.tiers.find(tier => tier.key === 'T3')?.services)
      .toContainEqual(expect.objectContaining({ name: 'future-service' }))
  })

  it('parses RTO/RPO and named service tiers from the canonical policy, preserving missing policy references', () => {
    const roster = buildBcpRoster(catalog.services, graph, policy)

    expect(policy.tiers.map(({ key, rto, rpo }) => [key, rto, rpo])).toEqual([
      ['T0', '4 h', '15 min'],
      ['T1', '4 h', '1 h'],
      ['T2', '8 h', '1 h'],
      ['T3', '24 h', '4 h'],
    ])
    expect(policy.tiers.find(tier => tier.key === 'T0')?.explicitShortNames).toContain('ledger-service')
    expect(roster.policyServicesMissingFromCatalog).toContain('payment-service')
    expect(roster.policyServicesMissingFromCatalog).not.toContain('keycloak')
  })

  it('renders direct service and infrastructure dependencies from the generated service graph', () => {
    const roster = buildBcpRoster(catalog.services, {
      edges: [{ from: 'openbank-ledger-service', to: 'openbank-transaction-service', type: 'rest' }],
      infraEdges: [{ from: 'openbank-ledger-service', to: 'infra:postgres', type: 'db' }],
      infraNodes: [{ id: 'infra:postgres', label: 'PostgreSQL', kind: 'infra' }],
    }, policy)
    const ledger = roster.tiers.flatMap(tier => tier.services).find(service => service.name === 'ledger-service')

    expect(ledger?.note[1]).toContain('Declared dependencies: PostgreSQL, transaction-service.')
  })

  it('does not treat an incomplete or duplicate tier roster as catalog coverage', () => {
    const roster = buildBcpRoster([
      { name: 'openbank-account-service', short: 'account-service', kind: 'service', runnable: true },
    ], graph, policy)
    expect(bcpCatalogCoverageIsComplete({ ...roster, tiers: roster.tiers.map(tier => ({ ...tier, services: [] })) })).toBe(false)
  })

  it('excludes non-runnable catalog entries from the BCP roster', () => {
    const roster = buildBcpRoster([
      { name: 'openbank-account-service', short: 'account-service', kind: 'service', runnable: true },
      { name: 'openbank-ui-helper', short: 'ui-helper', kind: 'component', runnable: false },
    ], graph, policy)
    expect(roster.catalogServiceCount).toBe(1)
    expect(roster.unclassifiedCatalogServices).not.toContain('ui-helper')
  })

  it('fails closed if the policy removes its all-other-services default or changes into an unsupported shape', () => {
    const noDefault = parseBcpPolicy(policyMarkdown.replace('| all other services |', '| account-service |'))
    expect(noDefault).not.toBeNull()
    const roster = buildBcpRoster(catalog.services, graph, noDefault)
    expect(roster.unclassifiedCatalogServices.length).toBeGreaterThan(0)
    expect(bcpCatalogCoverageIsComplete(roster)).toBe(false)
    const misplacedDefault = policyMarkdown
      .replace('| T1 — Identity and auth | party-service, onboarding-service, keycloak, consent-service |', '| T1 — Identity and auth | all other services |')
      .replace('| T3 — Supporting | all other services |', '| T3 — Supporting | account-service |')
    expect(parseBcpPolicy(misplacedDefault)).toBeNull()
    expect(parseBcpPolicy(policyMarkdown.replace('| T3 — Supporting | all other services | 24 h | 4 h |', ''))).toBeNull()
  })
})
