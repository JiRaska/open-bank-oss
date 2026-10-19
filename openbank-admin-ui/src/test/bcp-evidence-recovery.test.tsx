// SPDX-License-Identifier: Apache-2.0
import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import BcpPage from '@/app/docs/bcp/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
vi.mock('@/components/governance/CatalogDriftBanner', () => ({ CatalogDriftBanner: () => null }))
vi.mock('@/components/docs/PrintDocumentButton', () => ({ PrintDocumentButton: () => null }))
const compliance = ['balance-service', 'aml-service', 'sanctions-service', 'kyc-service', 'security-scanner', 'notification-service']
const json = (body: unknown) => new Response(JSON.stringify(body))
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
function mount(services: () => Response | Promise<Response>) {
  vi.stubGlobal('fetch', vi.fn(async (path: string) => path === '/api/services/health'
    ? services() : path === '/api/infra/status' ? json({}) : json({ available: true, services: [] })))
  render(<LanguageProvider><BcpPage /></LanguageProvider>)
}
describe('BCP incomplete evidence', () => {
  it('does not clear a compliance tier when one member is unknown', async () => {
    mount(() => json({ services: compliance.slice(1).map(name => ({ name, status: 'UP' })) }))
    expect(await screen.findByText('UNVERIFIED')).toBeInTheDocument()
    expect(screen.queryByText('CLEAR')).not.toBeInTheDocument()
    expect(screen.queryByText('ALLOWED')).not.toBeInTheDocument()
  })
  it('retains known failure precedence over missing evidence', async () => {
    mount(() => json({ services: [{ name: 'aml-service', status: 'DOWN' }] }))
    await waitFor(() => expect(screen.getAllByText('BLOCKED')).toHaveLength(2))
    expect(screen.queryByText('ALLOWED')).not.toBeInTheDocument()
  })
  it('invalid JSON after a successful observation removes the previous clear state and recovers', async () => {
    let broken = false
    mount(() => broken ? new Response('{') : json({ services: compliance.map(name => ({ name, status: 'UP' })) }))
    expect(await screen.findByText('CLEAR')).toBeInTheDocument()
    expect(screen.getByText('ALLOWED')).toBeInTheDocument()
    broken = true
    fireEvent.click(screen.getByRole('button', { name: 'Refresh BCP status' }))
    expect(await screen.findByText('UNVERIFIED')).toBeInTheDocument()
    expect(screen.queryByText('CLEAR')).not.toBeInTheDocument()
    broken = false
    fireEvent.click(screen.getByRole('button', { name: 'Refresh BCP status' }))
    expect(await screen.findByText('CLEAR')).toBeInTheDocument()
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
      return json({ services: compliance.map(name => ({ name, status: 'UP' })) })
    })

    expect(await screen.findByText('CLEAR')).toBeInTheDocument()
    expect(servicesCalls).toBe(1)
    expect(intervalRefresh).toBeDefined()
    fireEvent.click(screen.getByRole('button', { name: 'Refresh BCP status' }))
    expect(servicesCalls).toBe(2)
    intervalRefresh?.()
    expect(servicesCalls).toBe(3)
    expect(await screen.findByText('CLEAR')).toBeInTheDocument()
    expect(screen.getByText('ALLOWED')).toBeInTheDocument()

    resolveSlowRefresh(json({ services: compliance.map(name => ({ name, status: 'DOWN' })) }))
    await slowRefreshResponse
    await waitFor(() => expect(screen.getByText('CLEAR')).toBeInTheDocument())

    expect(screen.getByText('CLEAR')).toBeInTheDocument()
    expect(screen.getByText('ALLOWED')).toBeInTheDocument()
  })
})
