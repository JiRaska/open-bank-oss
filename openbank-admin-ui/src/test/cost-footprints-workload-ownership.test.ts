// SPDX-License-Identifier: Apache-2.0
import { execFileSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { describe, expect, it } from 'vitest'
import { parse as parseYaml } from 'yaml'
import { allocate } from '@/lib/finops/allocation'
import { domainForService } from '@/lib/finops/costGroups'

type Footprint = { service: string; cpuMillis: number; memMiB: number }

function workload(kind: string, name: string, namespace: string | undefined, cpu: string, memory: string) {
  return {
    apiVersion: kind === 'Rollout' ? 'argoproj.io/v1alpha1' : 'apps/v1', kind,
    metadata: { name, ...(namespace ? { namespace } : {}) },
    spec: { template: { spec: { containers: [{ name: 'service', resources: { requests: { cpu, memory } } }] } } },
  }
}

function collect(manifests: Array<[string, unknown]>) {
  const repo = mkdtempSync(path.join(tmpdir(), 'openbank-cost-workloads-'))
  try {
    const components = path.join(repo, 'openbank-infra/gitops/components')
    mkdirSync(components, { recursive: true })
    for (const [file, body] of manifests) {
      const target = path.join(components, file)
      mkdirSync(path.dirname(target), { recursive: true })
      // JSON is a YAML subset; these are actual source manifests read by the real collector.
      writeFileSync(target, JSON.stringify(body))
    }
    const output = path.join(repo, 'footprints.json')
    execFileSync(process.execPath, [path.resolve('scripts/collect-cost-footprints.mjs'), '--repo', repo, '--out', output])
    return JSON.parse(readFileSync(output, 'utf8')) as {
      schema: string; available: boolean; collectedAt: null; footprints: Footprint[]
    }
  } finally {
    rmSync(repo, { recursive: true, force: true })
  }
}

describe('cost footprints preserve physical workload coverage', () => {
  it('includes Rollouts and deduplicates controller overlays using the existing larger-request heuristic', () => {
    const result = collect([
      ['base/pension-fund.yaml', workload('Deployment', 'pension-fund-service', 'pension-fund', '100m', '128Mi')],
      ['rollout/pension-fund.yaml', workload('Rollout', 'pension-fund-service', 'pension-fund', '200m', '256Mi')],
      // Higher CPU alone must not replace the existing scalar-maximum request vector.
      ['overlays/pension-fund.yaml', workload('Deployment', 'pension-fund-service', 'pension-fund', '300m', '64Mi')],
      ['rollout/payment.yaml', workload('Rollout', 'sepa-payment', 'payments', '50m', '64Mi')],
      ['ignored/stateful.yaml', workload('StatefulSet', 'pension-fund-service', 'pension-fund', '4', '8Gi')],
    ])
    expect(result.schema).toBe('openbank.cost-footprints/v1')
    expect(result.available).toBe(true)
    expect(result.collectedAt).toBeNull()
    expect(result.footprints).toEqual([
      { service: 'pension-fund-service', cpuMillis: 200, memMiB: 256 },
      { service: 'sepa-payment', cpuMillis: 50, memMiB: 64 },
    ])
  })

  it('sums distinct tenant namespaces while keeping absent namespaces unknown and unknown services visible', () => {
    const result = collect([
      ['ledger/bank.yaml', workload('Deployment', 'ledger-service', 'ledger', '200m', '256Mi')],
      ['tenant/company.yaml', workload('Deployment', 'ledger-service', 'ledger-pension-co', '50m', '128Mi')],
      // Directory names must never supply an omitted namespace or collapse it into the named tenant.
      ['ledger-pension-co/unknown-base.yaml', workload('Deployment', 'ledger-service', undefined, '250m', '512Mi')],
      ['another/unknown-overlay.yaml', workload('Rollout', 'ledger-service', undefined, '100m', '256Mi')],
      ['new/component.yaml', workload('Rollout', 'unmapped-component', 'new-application', '40m', '64Mi')],
      ['new/no-requests.yaml', { kind: 'Rollout', metadata: { name: 'no-requests', namespace: 'new-application' }, spec: {} }],
    ])
    expect(result.footprints).toEqual([
      { service: 'ledger-service', cpuMillis: 500, memMiB: 896 },
      { service: 'unmapped-component', cpuMillis: 40, memMiB: 64 },
    ])
    // The public v1 join stays service-based; namespace does not become a business-domain field.
    expect(result.footprints.every(row => Object.keys(row).sort().join(',') === 'cpuMillis,memMiB,service')).toBe(true)
  })

  it('carries ledger tenant Rollouts and pension funds through real domain showback without losing cost', () => {
    const result = collect([
      ['bank/ledger.yaml', workload('Rollout', 'ledger-service', 'ledger', '100m', '128Mi')],
      ['pension-company/ledger.yaml', workload('Rollout', 'ledger-service', 'ledger-pension-co', '200m', '256Mi')],
      ['fund/pension.yaml', workload('Rollout', 'pension-fund-service', 'pension-fund', '100m', '128Mi')],
    ])
    for (const service of ['ledger-service', 'pension-fund-service']) {
      const source = parseYaml(readFileSync(path.resolve('..', `openbank-${service}`, 'governance.yaml'), 'utf8'))
      expect(domainForService(service)).toBe(source.dataDomain)
    }
    const allocation = allocate({
      available: true, currency: 'USD', total: 500,
      services: [
        { name: 'Amazon Elastic Compute Cloud - Compute', amount: 400 },
        { name: 'Amazon Simple Storage Service', amount: 100 },
      ],
    }, result.footprints)

    expect(allocation.available).toBe(true)
    expect(allocation.byService.find(row => row.service === 'ledger-service')).toMatchObject({
      domain: 'accounting', cpuMillis: 300, memMiB: 384,
    })
    expect(allocation.byDomain.find(row => row.domain === 'accounting')?.amount).toBeGreaterThan(0)
    expect(allocation.byDomain.find(row => row.domain === 'pensions')?.amount).toBeGreaterThan(0)
    expect(allocation.unmapped).toEqual([])
    expect(allocation.byService.reduce((sum, row) => sum + row.amount, 0) + allocation.platformOverhead)
      .toBeCloseTo(allocation.total, 2)
    expect(allocation.total).toBe(500)
    expect(allocation.platformOverhead).toBe(100)
  })

  it('keeps an absent source honest instead of fabricating zero footprints', () => {
    const result = collect([])
    expect(result.available).toBe(false)
    expect(result.footprints).toEqual([])
  })
})
