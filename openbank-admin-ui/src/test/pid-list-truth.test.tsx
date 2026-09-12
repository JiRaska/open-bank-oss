// SPDX-License-Identifier: Apache-2.0

import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import PidPage from '@/app/pid/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { parsePidRecords } from '@/lib/pid/pidRecordContract'

vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: ['ROLE_ADMIN'] } }, status: 'authenticated' }),
  signIn: vi.fn(),
}))

function response(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('PID list truthfulness', () => {
  it.each([
    [404, 'PID-service is not deployed in this environment'],
    [405, 'Failed to load: PID records'],
  ])('renders an unserved list route as unavailable, never as empty (%s)', async (status, title) => {
    vi.stubGlobal('fetch', vi.fn(async () => response(status, { error: 'not served' })))
    render(<LanguageProvider initialLanguage="en"><PidPage /></LanguageProvider>)

    expect(await screen.findByText(title)).toBeVisible()
    expect(screen.queryByText('No PID records found')).not.toBeInTheDocument()
    expect(screen.queryByText('Total Records')).not.toBeInTheDocument()
  })

  it('keeps a genuine successful empty list distinct from route absence', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response(200, [])))
    render(<LanguageProvider initialLanguage="en"><PidPage /></LanguageProvider>)

    expect(await screen.findByText('No PID records found')).toBeVisible()
    expect(screen.getByText('Total Records')).toBeVisible()
    expect(screen.getAllByText('0').length).toBeGreaterThan(0)
  })

  it.each([{ items: [] }, { content: [] }, {}, null])('rejects an unpublished response envelope', raw => {
    expect(() => parsePidRecords(raw)).toThrow('Invalid PID list response')
  })

  it('rejects malformed record evidence', () => {
    expect(() => parsePidRecords([{
      id: 'pid-1', personId: 'party-1', identifierType: 'BANK_ID', identifierValue: 'x',
      issuingCountry: 'CZ', status: 'ACTIVE', verified: true, createdAt: 'not-a-date',
    }])).toThrow('created timestamp')
  })
})
