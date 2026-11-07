// SPDX-License-Identifier: Apache-2.0
import React from 'react'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ServiceConfigPage from '@/app/system/config/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { fetchAllServiceConfigSnapshots } from '@/lib/api'

vi.mock('@/lib/api', async importOriginal => ({
  ...await importOriginal<typeof import('@/lib/api')>(),
  fetchAllServiceConfigSnapshots: vi.fn(),
}))

const sample = {
  name: 'ledger-service',
  port: 8101,
  // Quarkus omits null fields under NON_NULL serialization; the page normalizes these
  // optional policy blocks before rendering them.
  config: { service: 'ledger-service', rateLimit: null },
  health: { status: 'UP' as const, checks: [] },
  latencyMs: 8,
  reachable: true,
}

function mount() {
  return render(<LanguageProvider initialLanguage="en"><ServiceConfigPage /></LanguageProvider>)
}

beforeEach(() => {
  localStorage.setItem('openbank-language', 'en')
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.resetAllMocks()
})

describe('service configuration refresh freshness', () => {
  it('keeps the last good sample and timestamp marked stale until a valid recovery', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-21T10:00:00Z'))
    vi.mocked(fetchAllServiceConfigSnapshots)
      .mockResolvedValueOnce([sample])
      .mockRejectedValueOnce(new Error('503'))
      .mockResolvedValueOnce([{ ...sample, health: { status: 'DOWN', checks: [] } }])
    mount()

    expect(await screen.findByText('ledger-service')).toBeInTheDocument()
    expect(screen.getByText(/Healthy/)).toBeInTheDocument()
    const timestamp = screen.getByText(/Last successful load:/).textContent

    vi.setSystemTime(new Date('2026-10-21T10:01:00Z'))
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(await screen.findByText('Refresh failed. The values shown are from the last successful load and may be stale.')).toBeInTheDocument()
    expect(screen.getByText('ledger-service')).toBeInTheDocument()
    expect(screen.getByText(/Healthy/)).toBeInTheDocument()
    expect(screen.getByText(/Last successful load:/).textContent).toBe(timestamp)

    vi.setSystemTime(new Date('2026-10-21T10:02:00Z'))
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(await screen.findByText(/Unreachable/)).toBeInTheDocument()
    expect(screen.getByText(/Last successful load:/).textContent).not.toBe(timestamp)
    expect(screen.queryByText('Refresh failed. The values shown are from the last successful load and may be stale.')).not.toBeInTheDocument()
  })

  it('does not show empty healthy totals after an initial failure or malformed response', async () => {
    vi.mocked(fetchAllServiceConfigSnapshots)
      .mockRejectedValueOnce(new Error('503'))
      .mockResolvedValueOnce(null as never)
      .mockResolvedValueOnce([])
    mount()

    expect(await screen.findByText('Service configuration could not be loaded; status is unknown.')).toBeInTheDocument()
    expect(screen.queryByText(/Healthy/)).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(await screen.findByText('Service configuration could not be loaded; status is unknown.')).toBeInTheDocument()
    expect(screen.queryByText(/Healthy/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Last successful load:/)).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(await screen.findByText('The last successful load returned no service configuration observations.')).toBeInTheDocument()
    expect(screen.queryByText(/Healthy/)).not.toBeInTheDocument()
    expect(screen.queryByText('Service configuration could not be loaded; status is unknown.')).not.toBeInTheDocument()
  })

  it('keeps the prior observation stale when an array contains malformed rows', async () => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-10-21T10:00:00Z'))
    vi.mocked(fetchAllServiceConfigSnapshots)
      .mockResolvedValueOnce([sample])
      .mockResolvedValueOnce([null] as never)
      .mockResolvedValueOnce([{}] as never)
    mount()

    expect(await screen.findByText('ledger-service')).toBeInTheDocument()
    const timestamp = screen.getByText(/Last successful load:/).textContent

    vi.setSystemTime(new Date('2026-10-21T10:01:00Z'))
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(await screen.findByText('Refresh failed. The values shown are from the last successful load and may be stale.')).toBeInTheDocument()
    expect(screen.getByText('ledger-service')).toBeInTheDocument()
    expect(screen.getByText(/Last successful load:/).textContent).toBe(timestamp)

    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(await screen.findByText('Refresh failed. The values shown are from the last successful load and may be stale.')).toBeInTheDocument()
    expect(screen.getByText('ledger-service')).toBeInTheDocument()
    expect(screen.getByText(/Last successful load:/).textContent).toBe(timestamp)
  })

  it('ignores an older pending response after a newer poll fails', async () => {
    vi.useFakeTimers()
    let resolveInitial!: (value: Array<typeof sample>) => void
    const initial = new Promise<Array<typeof sample>>(resolve => { resolveInitial = resolve })
    vi.mocked(fetchAllServiceConfigSnapshots)
      .mockReturnValueOnce(initial)
      .mockRejectedValueOnce(new Error('503'))
    mount()

    await act(async () => { await vi.advanceTimersByTimeAsync(15_000) })
    expect(screen.getByText('Service configuration could not be loaded; status is unknown.')).toBeInTheDocument()

    await act(async () => { resolveInitial([sample]); await initial })
    expect(screen.getByText('Service configuration could not be loaded; status is unknown.')).toBeInTheDocument()
    expect(screen.queryByText('Healthy')).not.toBeInTheDocument()
    expect(screen.queryByText('ledger-service')).not.toBeInTheDocument()
  })

  it('keeps same-named services from different namespaces independently addressable', async () => {
    vi.mocked(fetchAllServiceConfigSnapshots).mockResolvedValue([
      { ...sample, namespace: 'accounts' },
      { ...sample, namespace: 'payments' },
    ])
    mount()

    expect(await screen.findAllByText('ledger-service')).toHaveLength(2)
    const accounts = screen.getByLabelText('Namespace accounts').closest('button')
    const payments = screen.getByLabelText('Namespace payments').closest('button')
    expect(accounts).not.toBeNull()
    expect(payments).not.toBeNull()
    expect(accounts).toHaveAttribute('aria-expanded', 'false')
    expect(payments).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(accounts!)
    expect(accounts).toHaveAttribute('aria-expanded', 'true')
    expect(payments).toHaveAttribute('aria-expanded', 'false')
    const accountsPanelId = accounts!.getAttribute('aria-controls')
    expect(accountsPanelId).toBeTruthy()
    expect(document.getElementById(accountsPanelId!)).toHaveAttribute('role', 'region')
    expect(payments).not.toHaveAttribute('aria-controls')
    fireEvent.click(payments!)
    expect(accounts).toHaveAttribute('aria-expanded', 'false')
    expect(payments).toHaveAttribute('aria-expanded', 'true')
    const paymentsPanelId = payments!.getAttribute('aria-controls')
    expect(paymentsPanelId).toBeTruthy()
    expect(paymentsPanelId).not.toBe(accountsPanelId)
    expect(document.getElementById(paymentsPanelId!)).toHaveAttribute('role', 'region')
    expect(accounts).not.toHaveAttribute('aria-controls')
  })

  it('keeps panel ids distinct when namespace and service names contain separators', async () => {
    vi.mocked(fetchAllServiceConfigSnapshots).mockResolvedValue([
      { ...sample, name: 'c', namespace: 'a-b' },
      { ...sample, name: 'b-c', namespace: 'a' },
    ])
    mount()

    const namespaceAb = (await screen.findByLabelText('Namespace a-b')).closest('button')
    const namespaceA = screen.getByLabelText('Namespace a').closest('button')
    expect(namespaceAb).not.toBeNull()
    expect(namespaceA).not.toBeNull()

    fireEvent.click(namespaceAb!)
    const firstPanelId = namespaceAb!.getAttribute('aria-controls')
    expect(firstPanelId).toBe('service-config-a-b%2Fc')
    expect(document.getElementById(firstPanelId!)).toHaveAttribute('role', 'region')

    fireEvent.click(namespaceA!)
    const secondPanelId = namespaceA!.getAttribute('aria-controls')
    expect(secondPanelId).toBe('service-config-a%2Fb-c')
    expect(secondPanelId).not.toBe(firstPanelId)
    expect(document.getElementById(secondPanelId!)).toHaveAttribute('role', 'region')
    expect(namespaceAb).toHaveAttribute('aria-expanded', 'false')
    expect(namespaceA).toHaveAttribute('aria-expanded', 'true')

    fireEvent.click(namespaceAb!)
    expect(document.getElementById(namespaceAb!.getAttribute('aria-controls')!)).toHaveAttribute('role', 'region')
    expect(namespaceAb).toHaveAttribute('aria-expanded', 'true')
    expect(namespaceA).toHaveAttribute('aria-expanded', 'false')
  })
})
