// SPDX-License-Identifier: Apache-2.0
// Metadata-migration acceptance: real repository declarations and their generated
// consumers must implement the reviewed business responsibilities, not just agree.
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { parse } from 'yaml'
import { buildRegistry, type CatalogFleetModule } from '@/lib/services/registry'
import { getGovernanceManifest } from '@/lib/governance/governanceLoader'
import { collectServiceDiscovery } from '@/lib/discovery'
import { allocate } from '@/lib/finops/allocation'
import { canonicalToolDomains } from '@/lib/agent/toolDomains'
import { domainLabel } from '@/lib/governance/domains'

const root = path.resolve(process.cwd(), '..')
const catalog = JSON.parse(readFileSync('catalog.json', 'utf8')) as { services: CatalogFleetModule[] }
const registry = buildRegistry(catalog.services)
const governance = getGovernanceManifest()
const responsibilities = {
  'pension-service': 'pensions', 'pension-fund-service': 'pensions', 'wealth-service': 'wealth',
  'treasury-service': 'treasury', 'lending-service': 'lending', 'ledger-service': 'accounting',
  'transaction-service': 'accounting', 'card-processing-service': 'cards', 'dispute-service': 'cards',
  'tax-reporting-service': 'reporting', 'billing-service': 'products', 'risk-engine': 'risk',
  'context-service': 'compliance', 'mcp-service': 'channels', 'devops-agent': 'platform',
} as const

describe('reviewed business ownership metadata migration', () => {
  it('declares the intended bounded-context ownership in the real service sources', () => {
    for (const [service, domain] of Object.entries(responsibilities)) {
      const declaration = parse(readFileSync(path.join(root, `openbank-${service}`, 'governance.yaml'), 'utf8'))
      expect(declaration.dataDomain, service).toBe(domain)
    }
  })

  it('propagates the reviewed real assignments through generated catalog, governance and registry', () => {
    for (const [service, domain] of Object.entries(responsibilities)) {
      expect(catalog.services.find(row => row.name === `openbank-${service}`)?.dataDomain, service).toBe(domain)
      expect(governance.find(row => row.serviceName === service)?.dataDomain, service).toBe(domain)
      expect(registry.find(row => row.container === `openbank-${service}`)?.group, service).toBe(domain)
    }
  })

  it('classifies deployed services from real ownership even in a shared platform namespace', async () => {
    const names = Object.keys(responsibilities)
    const result = await collectServiceDiscovery(async url => ({ items: url.includes('/deployments') ? names.map(name => ({
      metadata: { name, namespace: 'platform' }, spec: { replicas: 1, template: { spec: { containers: [{ ports: [{ name: 'http', containerPort: 9000 }] }] } } },
      status: { replicas: 1, readyReplicas: 1 },
    })) : [] }) as never, ['platform'])
    expect(result.status).toBe('complete')
    for (const [service, domain] of Object.entries(responsibilities)) {
      expect(result.services.find(row => row.name === service)?.group, service).toBe(domain)
    }
  })

  it('projects migrated real ownership onto MCP tools without changing advertised execution contracts', async () => {
    // No ownership mock: the production helper resolves this checkout's real
    // generated catalog and governance through its normal source/fallback path.
    const cases = [
      ['list_ledger_journals', 'ledger-service', 'accounting', 'query.ledger.readonly'],
      ['list_transactions', 'transaction-service', 'accounting', 'query.ledger.readonly'],
      ['dispute_list', 'dispute-service', 'cards', 'query.disputes.readonly'],
      ['list_products', 'product-catalog', 'products', 'query.catalog.readonly'],
      ['sepa_instant_list', 'sepa-instant-service', 'payments', 'query.payments.readonly'],
      ['sepa_instant_get', 'sepa-instant-service', 'payments', 'query.payments.readonly'],
      ['sepa_instant_list_by_debtor', 'sepa-instant-service', 'payments', 'query.payments.readonly'],
    ] as const
    const tools = cases.map(([name, service, , capability]) => ({
      name, service, domain: 'Legacy grouping', capabilities: [capability],
      inputSchema: { type: 'object', properties: { id: { type: 'string' } } },
    }))
    const before = structuredClone(tools)
    const projected = await canonicalToolDomains(tools)
    expect(projected).toHaveLength(cases.length)
    for (const [index, tool] of tools.entries()) {
      expect(projected[index]).toEqual({ ...tool, domain: domainLabel(cases[index][2], 'en') })
      expect((projected[index] as typeof tool).name).toBe(tool.name)
      expect((projected[index] as typeof tool).service).toBe(tool.service)
      expect((projected[index] as typeof tool).inputSchema).toBe(tool.inputSchema)
      expect((projected[index] as typeof tool).capabilities).toBe(tool.capabilities)
    }
    expect(tools).toEqual(before)
  })

  it('allocates the real ledger and transaction costs to Accounting while retaining other business owners', () => {
    const footprints = [...Object.keys(responsibilities), 'unregistered-service'].map(service => ({ service, cpuMillis: 100, memMiB: 100 }))
    const total = footprints.length * 10
    const result = allocate({ available: true, currency: 'USD', total, services: [{ name: 'Amazon Elastic Compute Cloud - Compute', amount: total }] }, footprints)
    for (const [service, domain] of Object.entries(responsibilities)) expect(result.byService.find(row => row.service === service)?.domain, service).toBe(domain)
    expect(result.byDomain.find(row => row.domain === 'accounting')).toMatchObject({ serviceCount: 2, amount: 20 })
    expect(result.byService.find(row => row.service === 'unregistered-service')?.domain).toBe('unclassified')
    expect(result.unmapped).toEqual(['unregistered-service'])
    expect(result.byDomain.reduce((sum, row) => sum + row.amount, 0)).toBe(total)
  })
})
