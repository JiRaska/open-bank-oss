// SPDX-License-Identifier: Apache-2.0
import React from 'react'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import BcpPage from '@/app/docs/bcp/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { parseBcpPolicy } from '@/lib/bcp/continuityModel'
vi.mock('@/components/governance/CatalogDriftBanner', () => ({ CatalogDriftBanner: () => null }))
vi.mock('@/components/docs/PrintDocumentButton', () => ({ PrintDocumentButton: () => null }))
const compliance = ['balance-service', 'aml-service', 'sanctions-service', 'kyc-service', 'security-scanner', 'notification-service']
const catalog = JSON.parse(readFileSync(path.resolve(__dirname, '../../catalog.json'), 'utf8'))
const graph = JSON.parse(readFileSync(path.resolve(__dirname, '../../service-graph.json'), 'utf8'))
const bcpPolicy = parseBcpPolicy(readFileSync(path.resolve(__dirname, '../../../docs/bcp/bcp-policy.md'), 'utf8'))
if (!bcpPolicy) throw new Error('canonical BCP policy §2 could not be parsed')
const paymentPath = ['ledger-service', 'balance-service', 'transaction-service', 'clearing-service', 'aml-service', 'sanctions-service']
const allPaymentPathUp = () => ({ services: paymentPath.map(name => ({ name, status: 'UP' })) })
const allCatalogServicesUp = () => ({
  services: catalog.services.filter((service: { kind: string }) => service.kind === 'service')
    .map((service: { short: string }) => ({ name: service.short, status: 'UP' })),
})
const allInfraUp = Object.fromEntries(graph.infraNodes
  .filter((node: { kind: string }) => node.kind === 'infra')
  .map((node: { id: string }) => [node.id.replace(/^infra:/, ''), { status: 'UP' }]))
const json = (body: unknown) => new Response(JSON.stringify(body))
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
function mount(services: () => Response | Promise<Response>) {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => url === '/api/services/health'
    ? services()
    : url === '/api/infra/status' ? json(allInfraUp)
      : url === '/api/catalog/services' ? json({ ...catalog, available: true })
      : url === '/api/catalog/graph' ? json({ ...graph, available: true })
        : url === '/api/bcp/policy' ? json({ available: true, policy: bcpPolicy })
          : json({})))
  render(<LanguageProvider><BcpPage /></LanguageProvider>)
}
describe('BCP incomplete evidence', () => {
  it('marks payment and compliance service health unverified when a required member is unknown', async () => {
    mount(() => json({ services: compliance.slice(1).map(name => ({ name, status: 'UP' })) }))
    expect(await screen.findAllByText('UNVERIFIED')).toHaveLength(2)
    expect(screen.queryByText('CLEAR')).not.toBeInTheDocument()
    expect(screen.queryByText('ALLOWED')).not.toBeInTheDocument()
  })
  it('retains known failure precedence over missing evidence', async () => {
    mount(() => json({ services: [{ name: 'aml-service', status: 'DOWN' }] }))
    await waitFor(() => expect(screen.getAllByText('DEGRADED')).toHaveLength(3))
    expect(screen.queryByText('ALLOWED')).not.toBeInTheDocument()
  })
  it('keeps overall recovery and payment-path status unverified while policy references a missing service', async () => {
    let broken = false
    mount(() => broken ? new Response('{') : json(allCatalogServicesUp()))
    expect(await screen.findByText('UNKNOWN')).toBeInTheDocument()
    expect(await screen.findByText('HEALTHY')).toBeInTheDocument()
    expect(screen.getByText('UNVERIFIED')).toBeInTheDocument()
    expect(screen.queryByText('CLEAR')).not.toBeInTheDocument()
    expect(screen.getByText('The BCP policy names a service absent from the current catalog; it is not guessed into a replacement:')).toBeInTheDocument()
    broken = true
    fireEvent.click(screen.getByRole('button', { name: 'Refresh BCP status' }))
    expect(await screen.findAllByText('UNVERIFIED')).toHaveLength(2)
    expect(screen.queryByText('CLEAR')).not.toBeInTheDocument()
    broken = false
    fireEvent.click(screen.getByRole('button', { name: 'Refresh BCP status' }))
    expect(await screen.findByText('HEALTHY')).toBeInTheDocument()
    expect(screen.getByText('UNVERIFIED')).toBeInTheDocument()
  })

  it('ignores a late response from before a newer interval refresh', async () => {
    let resolveSlowRefresh!: (response: Response) => void
    const slowRefreshResponse = new Promise<Response>(resolve => { resolveSlowRefresh = resolve })
    let servicesCalls = 0
    let intervalRefresh: (() => void) | undefined
    const originalSetInterval = globalThis.setInterval
    vi.spyOn(globalThis, 'setInterval').mockImplementation((handler, timeout, ...args) => {
      if (timeout === 30000) {
        intervalRefresh = handler as () => void
        return 1 as ReturnType<typeof setInterval>
      }
      return originalSetInterval(handler, timeout, ...args)
    })

    mount(() => {
      servicesCalls += 1
      if (servicesCalls === 2) return slowRefreshResponse
      return json(allPaymentPathUp())
    })

    // The policy still names payment-service, which is absent from the current
    // catalog. Even a fully UP current payment path therefore remains unverified.
    expect(await screen.findByText('HEALTHY')).toBeInTheDocument()
    expect(screen.getByText('UNVERIFIED')).toBeInTheDocument()
    expect(servicesCalls).toBe(1)
    expect(intervalRefresh).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: 'Refresh BCP status' }))
    expect(servicesCalls).toBe(2)
    await act(async () => { intervalRefresh?.() })
    expect(servicesCalls).toBe(3)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Refresh BCP status' })).toHaveAttribute('aria-busy', 'false'))
    expect(screen.getByText('UNVERIFIED')).toBeInTheDocument()
    expect(screen.queryByText('ALLOWED')).not.toBeInTheDocument()

    await act(async () => {
      resolveSlowRefresh(json({ services: paymentPath.map(name => ({ name, status: 'DOWN' })) }))
      await slowRefreshResponse
    })
    await waitFor(() => expect(screen.queryByText('DEGRADED')).not.toBeInTheDocument())

    expect(screen.getByText('HEALTHY')).toBeInTheDocument()
    expect(screen.getByText('UNVERIFIED')).toBeInTheDocument()
    expect(screen.queryByText('DEGRADED')).not.toBeInTheDocument()
    expect(screen.queryByText('ALLOWED')).not.toBeInTheDocument()
  })
})
