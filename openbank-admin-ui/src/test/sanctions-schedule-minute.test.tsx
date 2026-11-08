// SPDX-License-Identifier: Apache-2.0

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SanctionsPage from '@/app/sanctions/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: ['ROLE_ADMIN'] } }, status: 'authenticated' }),
  signIn: vi.fn(),
}))

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

async function openSchedule(minute: number) {
  const list = {
    id: 'synthetic-schedule', listType: 'EU', displayName: 'Synthetic scheduled list',
    sourceUrl: 'https://example.test/list.csv', enabled: true,
    cronHour: 4, cronMinute: minute, cronDays: 'MON', lastUpdatedAt: null,
    lastEntryCount: null,
  }
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const body = String(input) === '/api/sanctions/lists' ? [list] : []
    if (init?.method === 'PUT') return new Response('{}', { status: 200 })
    return new Response(JSON.stringify(body), { status: 200 })
  })
  vi.stubGlobal('fetch', fetchMock)
  render(<LanguageProvider><SanctionsPage /></LanguageProvider>)
  fireEvent.click(screen.getByRole('button', { name: 'List Management' }))
  await screen.findByText(list.displayName)
  fireEvent.click(screen.getByRole('button', { name: 'Expand list details' }))
  return fetchMock
}

describe('sanctions schedule minute', () => {
  it.each([0, 17, 59])('displays persisted minute %i without changing the schedule', async minute => {
    const fetchMock = await openSchedule(minute)
    expect(screen.getByRole('combobox', { name: 'Run minute' })).toHaveValue(String(minute))
    expect(fetchMock.mock.calls.every(([, init]) => !init?.method || init.method === 'GET')).toBe(true)
  })

  it('submits the exact selected minute while retaining the other schedule fields', async () => {
    const fetchMock = await openSchedule(17)
    fireEvent.change(screen.getByRole('combobox', { name: 'Run minute' }), { target: { value: '23' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save schedule' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/sanctions/lists/synthetic-schedule', {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ cronHour: 4, cronMinute: 23, cronDays: 'MON' }),
    }))
  })
})
