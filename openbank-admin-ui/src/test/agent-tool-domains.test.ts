// SPDX-License-Identifier: Apache-2.0
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { domainLabel } from '@/lib/governance/domains'

const { resolveOwnership, imageRegistry } = vi.hoisted(() => ({
  resolveOwnership: vi.fn(),
  imageRegistry: [
    { id: 'product-catalog', label: 'Product Catalog', group: 'core', container: 'openbank-product-catalog', port: 8104 },
    { id: 'ledger', label: 'Ledger', group: 'core', container: 'openbank-ledger-service', port: 8101 },
    { id: 'agent', label: 'Agent', group: 'platform', container: 'openbank-agent-service', port: 8109 },
  ],
}))
vi.mock('@/lib/services/domainOwnership', () => ({ currentDomainOwnership: resolveOwnership }))
vi.mock('@/lib/services/fleet', () => ({ getRegistry: () => imageRegistry }))

import { canonicalToolDomains } from '@/lib/agent/toolDomains'

beforeEach(() => {
  resolveOwnership.mockReset().mockResolvedValue({
    byModule: new Map([
      ['openbank-product-catalog', 'products'],
      ['openbank-ledger-service', 'accounting'],
      ['openbank-agent-service', 'channels'],
      ['openbank-sepa-instant', 'payments'],
    ]),
    byWorkload: new Map(),
  })
})

describe('canonical MCP tool display ownership', () => {
  it('replaces stale backend labels using current published ownership without changing execution contracts', async () => {
    const schema = { type: 'object', properties: { accountId: { type: 'string' } }, required: ['accountId'] }
    const capabilities = ['ledger.post', 'ledger.read']
    const tools = [
      { name: 'product.list', service: 'product-catalog', domain: 'Core Banking', inputSchema: schema, capabilities, custom: { retain: true } },
      { name: 'ledger.post', service: 'ledger-service', domain: 'Core Banking', inputSchema: schema, capabilities },
      { name: 'agent.proposal', service: 'openbank-agent-service', domain: 'Platform', inputSchema: schema, capabilities },
    ]
    const before = structuredClone(tools)
    const projected = await canonicalToolDomains(tools)
    expect(resolveOwnership).toHaveBeenCalledExactlyOnceWith(imageRegistry)
    expect(projected).toHaveLength(tools.length)
    for (const [index, group] of (['products', 'accounting', 'channels'] as const).entries()) {
      expect(projected[index]).toEqual({ ...tools[index], domain: domainLabel(group, 'en') })
      expect(projected[index]).toMatchObject({ name: tools[index].name, service: tools[index].service })
      expect((projected[index] as typeof tools[number]).inputSchema).toBe(schema)
      expect((projected[index] as typeof tools[number]).capabilities).toBe(capabilities)
    }
    expect(tools).toEqual(before)
    expect(imageRegistry.map(entry => entry.group)).toEqual(['core', 'core', 'platform'])
  })

  it('resolves the reviewed SEPA Instant service alias without changing the advertised tool identities', async () => {
    const tools = ['sepa_instant_list', 'sepa_instant_get', 'sepa_instant_list_by_debtor'].map(name => ({
      name, service: 'sepa-instant-service', domain: 'Old payment grouping',
      inputSchema: { type: 'object', properties: { debtorId: { type: 'string' } } },
      capabilities: ['payment.read'],
    }))
    const original = structuredClone(tools)
    const projected = await canonicalToolDomains(tools)
    expect(projected).toHaveLength(3)
    for (const [index, tool] of tools.entries()) {
      expect(projected[index]).toEqual({ ...tool, domain: domainLabel('payments', 'en') })
      expect((projected[index] as typeof tool).service).toBe('sepa-instant-service')
      expect((projected[index] as typeof tool).inputSchema).toBe(tool.inputSchema)
      expect((projected[index] as typeof tool).capabilities).toBe(tool.capabilities)
    }
    expect(tools).toEqual(original)
  })

  it('does not guess unknown identities or trust incoming display domains', async () => {
    const tools = [
      { name: 'unknown', service: 'missing-service', domain: 'Accounting' },
      { name: 'not-suffix-normalized', service: 'ledger', domain: 'Accounting' },
      { name: 'not-trimmed', service: ' ledger-service', domain: 'Accounting' },
      { name: 'not-an-alias', service: 'sepa-instant-service-extra', domain: 'Payments' },
      { name: 'missing-service', domain: 'Platform' },
      { name: 'wrong-type', service: 123, domain: 'Payments' },
    ]
    expect(await canonicalToolDomains(tools)).toEqual(tools.map(tool => ({ ...tool, domain: domainLabel('unclassified', 'en') })))
  })

  it('preserves malformed non-object entries and arbitrary RPC-shaped values without interpreting them', async () => {
    const list = ['malformed', null, 17, false, ['untouched-array']]
    const rpc = { jsonrpc: '2.0', method: 'tools/call', params: { name: 'ledger.post', arguments: { service: 'ledger-service', amount: '99' } } }
    const result = await canonicalToolDomains([...list, rpc])
    expect(result).toHaveLength(list.length + 1)
    for (const [index, entry] of list.entries()) expect(result[index]).toBe(entry)
    expect(result.at(-1)).toEqual({ ...rpc, domain: domainLabel('unclassified', 'en') })
    expect((result.at(-1) as typeof rpc).params).toBe(rpc.params)
    expect(rpc).not.toHaveProperty('domain')
  })
})
