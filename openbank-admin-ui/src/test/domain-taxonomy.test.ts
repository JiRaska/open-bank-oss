// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync, readdirSync, existsSync, mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import path from 'node:path'
import { tmpdir } from 'node:os'
import { parse } from 'yaml'
import { DOMAIN_IDS, DOMAIN_META, domainLabel, isDomainId } from '@/lib/governance/domains'
import { buildRegistry, k8sNameOf, type CatalogFleetModule } from '@/lib/services/registry'
import { getGovernanceManifest } from '@/lib/governance/governanceLoader'
import { collectServiceDiscovery } from '@/lib/discovery'
import { allocate } from '@/lib/finops/allocation'
import { parseGovernanceFleet, parseDashboardHealth } from '@/lib/dashboard/clientContract'
import { parseGovernanceLineage } from '@/lib/governance/lineage-evidence'

const root = path.resolve(process.cwd(), '..')
const catalog = JSON.parse(readFileSync('catalog.json', 'utf8')) as { services: CatalogFleetModule[] }
const registry = buildRegistry(catalog.services)
const governance = getGovernanceManifest()
const examples = {
  'pension-service': 'pensions', 'pension-fund-service': 'pensions', 'wealth-service': 'wealth',
  'treasury-service': 'treasury', 'lending-service': 'lending', 'ledger-service': 'accounting',
  'transaction-service': 'accounting', 'card-processing-service': 'cards', 'dispute-service': 'cards',
  'tax-reporting-service': 'reporting', 'billing-service': 'products', 'risk-engine': 'risk',
  'context-service': 'compliance', 'mcp-service': 'channels', 'devops-agent': 'platform',
} as const

describe('canonical business domain ownership across consumers', () => {
  it('generated browser contract matches the shared taxonomy and has bilingual definitions', () => {
    const taxonomy = JSON.parse(readFileSync(path.join(root, 'openbank-libs/governance/domain-taxonomy.json'), 'utf8'))
    expect(DOMAIN_IDS).toEqual(taxonomy.domains.map((d: { id: string }) => d.id))
    for (const { id, ...metadata } of taxonomy.domains) {
      expect(DOMAIN_META[id as keyof typeof DOMAIN_META]).toEqual(metadata)
      expect(domainLabel(id, 'cs')).toBe(metadata.labelCs)
      expect(domainLabel(id, 'en')).toBe(metadata.labelEn)
    }
    expect(isDomainId('unclassified')).toBe(false)
    expect(domainLabel('not-a-domain', 'en')).toBe('Unclassified')
  })

  it('every authored assignment reaches governance and the catalog; every runnable module is classified', () => {
    for (const moduleDir of readdirSync(root).filter(name => name.startsWith('openbank-'))) {
      const file = path.join(root, moduleDir, 'governance.yaml')
      if (!existsSync(file)) continue
      const source = parse(readFileSync(file, 'utf8'))
      expect(isDomainId(source.dataDomain), moduleDir).toBe(true)
      expect(governance.find(row => row.serviceName === moduleDir.slice(9))?.dataDomain, moduleDir).toBe(source.dataDomain)
      expect(catalog.services.find(row => row.name === moduleDir)?.dataDomain, moduleDir).toBe(source.dataDomain)
    }
    for (const entry of registry) {
      const source = parse(readFileSync(path.join(root, entry.container, 'governance.yaml'), 'utf8'))
      expect(entry.group, entry.container).toBe(source.dataDomain)
    }
    expect(registry.length).toBeGreaterThan(50)
  })

  it('classifies implemented responsibilities and preserves operational identities', () => {
    for (const [service, domain] of Object.entries(examples)) {
      const entry = registry.find(row => row.container === `openbank-${service}`)!
      expect(entry.group, service).toBe(domain)
      expect(k8sNameOf(entry), service).toBe(service)
    }
    expect(registry.find(row => row.container === 'openbank-ledger-service')?.k8sNamespace).toBe('ledger')
    expect(buildRegistry([{ name: 'openbank-new-service', short: 'new-service', kind: 'service', runnable: true, port: 9000 }])[0].group).toBe('unclassified')
  })

  it('uses moduleDir ownership for discovery even in shared namespaces and keeps unknown workloads visible', async () => {
    const names = [...Object.keys(examples), 'unregistered-service']
    const result = await collectServiceDiscovery(async url => ({ items: url.includes('/deployments') ? names.map(name => ({
      metadata: { name, namespace: 'platform' }, spec: { replicas: 1, template: { spec: { containers: [{ ports: [{ name: 'http', containerPort: 9000 }] }] } } },
      status: { replicas: 1, readyReplicas: 1 },
    })) : [] }) as never, ['platform'])
    expect(result.status).toBe('complete')
    for (const [name, group] of Object.entries(examples)) expect(result.services.find(s => s.name === name)?.group, name).toBe(group)
    expect(result.services.find(s => s.name === 'unregistered-service')?.group).toBe('unclassified')
  })

  it('parses all declared domains across dashboard and lineage contracts', () => {
    const items = DOMAIN_IDS.map((dataDomain, i) => ({ serviceName: `service-${i}`, dataDomain, dataLineageRole: 'internal' }))
    expect(parseGovernanceFleet({ available: true, timestamp: new Date().toISOString(), items })).toHaveLength(DOMAIN_IDS.length)
    expect(parseGovernanceLineage({ available: true, services: items })).not.toBeNull()
    expect(parseDashboardHealth({ services: [...DOMAIN_IDS, 'unclassified'].map((group, i) => ({ name: `service-${i}`, label: group, group, status: 'UP', latencyMs: 1 })) })).toHaveLength(DOMAIN_IDS.length + 1)
  })

  it('allocates the same costs to business owners and keeps unknown cost outside platform', () => {
    const footprints = [...Object.keys(examples), 'unregistered-service'].map(service => ({ service, cpuMillis: 100, memMiB: 100 }))
    const result = allocate({ available: true, currency: 'USD', total: 160, services: [{ name: 'Amazon Elastic Compute Cloud - Compute', amount: 160 }] }, footprints)
    for (const [service, domain] of Object.entries(examples)) expect(result.byService.find(s => s.service === service)?.domain, service).toBe(domain)
    expect(result.byService.find(s => s.service === 'unregistered-service')?.domain).toBe('unclassified')
    expect(result.unmapped).toEqual(['unregistered-service'])
    expect(result.byDomain.reduce((total, row) => total + row.amount, 0)).toBe(160)
  })

  it.each(['missing', 'invalid', 'valid'] as const)('checks %s ownership when generating a runnable catalog', state => {
    const fixture = mkdtempSync(path.join(tmpdir(), 'domain-catalog-'))
    try {
      const moduleDir = path.join(fixture, 'openbank-example-service')
      mkdirSync(path.join(moduleDir, 'src/main'), { recursive: true })
      writeFileSync(path.join(moduleDir, 'build.gradle.kts'), 'plugins { id("openbank.quarkus-service") }\ndependencies { implementation(project(":openbank-libs-runtime")) }')
      const run = () => execFileSync(process.execPath, ['scripts/generate-catalog.mjs', '--repo', fixture, '--out', path.join(fixture, 'catalog.json')], { encoding: 'utf8', stdio: 'pipe' })
      if (state === 'missing') {
        expect(run).toThrow(/missing domain ownership/)
      } else {
        writeFileSync(path.join(moduleDir, 'governance.yaml'), `dataDomain: ${state === 'valid' ? 'pensions' : 'made-up-domain'}\n`)
        if (state === 'invalid') expect(run).toThrow(/invalid or missing dataDomain/)
        else {
          run()
          expect(JSON.parse(readFileSync(path.join(fixture, 'catalog.json'), 'utf8')).services[0].dataDomain).toBe('pensions')
        }
      }
    } finally { rmSync(fixture, { recursive: true, force: true }) }
  })
})
