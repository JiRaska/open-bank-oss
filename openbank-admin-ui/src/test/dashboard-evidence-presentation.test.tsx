// SPDX-License-Identifier: Apache-2.0

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import DashboardPage from '@/app/dashboard/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: ['admin'] } } }),
}))

const governance = {
  available: true,
  timestamp: '2026-09-10T03:00:00Z',
  items: [{ serviceName: 'account-service', dataDomain: 'core' }],
}
const health = {
  services: [{ name: 'account-service', label: 'Accounts', group: 'core', status: 'UP', latencyMs: 12 }],
}

const wrap = (child: React.ReactNode) => <LanguageProvider initialLanguage="en">{child}</LanguageProvider>

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('dashboard evidence presentation', () => {
  it('does not label services healthy or not deployed when governance evidence is unavailable', async () => {
    const fetchMock = vi.fn((url: string) => Promise.resolve(Response.json(
      url.endsWith('/governance') ? { ...governance, available: false, items: [] } : health,
    )))
    vi.stubGlobal('fetch', fetchMock)

    render(wrap(<DashboardPage />))

    expect(await screen.findByText('Current platform state cannot be verified')).toBeInTheDocument()
    expect(screen.getByText(/not labelled healthy or not deployed/)).toBeInTheDocument()
    expect(screen.queryByText('Healthy services')).not.toBeInTheDocument()
    expect(screen.queryByText('Current health by group')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(4))
  })

  it('renders measured health only after both response contracts validate', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => Promise.resolve(Response.json(
      url.endsWith('/governance') ? governance : health,
    ))))

    render(wrap(<DashboardPage />))

    expect(await screen.findByText('Healthy services')).toBeInTheDocument()
    expect(screen.getAllByText('1/1')).toHaveLength(2)
    expect(screen.queryByText('Current platform state cannot be verified')).not.toBeInTheDocument()
  })
})
