// SPDX-License-Identifier: Apache-2.0

import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import PidPage from '@/app/pid/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { parsePidExternalIdLookup } from '@/lib/pid/pidRecordContract'

vi.mock('next-auth/react', () => ({
  useSession: () => ({ data: { user: { roles: ['ROLE_ADMIN'] } }, status: 'authenticated' }),
  signIn: vi.fn(),
}))

const PARTY_ID = '123e4567-e89b-42d3-a456-426614174000'
const CREATED_AT = '2026-10-19T10:30:00Z'
const BANKID = 'bankid|subject-123'

function response(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
}

function party(value = BANKID) {
  return {
    partyId: PARTY_ID,
    status: 'ACTIVE',
    createdAt: CREATED_AT,
    externalId: { type: 'BANKID_SUB', value, verifiedAt: CREATED_AT },
  }
}

function submitLookup(value = BANKID) {
  const input = screen.getByRole('textbox', { name: 'Exact identifier value' })
  fireEvent.change(input, { target: { value } })
  fireEvent.submit(input.closest('form')!)
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('PID explicit identifier lookup', () => {
  it('does not fetch a party roster until an operator submits an exact lookup', async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    render(<LanguageProvider initialLanguage="en"><PidPage /></LanguageProvider>)

    expect(fetchMock).not.toHaveBeenCalled()
    expect(screen.getByText('Search for a specific identifier')).toBeVisible()
    expect(screen.queryByText('Total Records')).not.toBeInTheDocument()
    expect(fetchMock).not.toHaveBeenCalledWith(expect.stringContaining('/api/v1/pids'), expect.anything())
  })

  it('sends the exact identifier in a POST body and renders only the minimized response', async () => {
    const fetchMock = vi.fn(async () => response(200, party()))
    vi.stubGlobal('fetch', fetchMock)
    render(<LanguageProvider initialLanguage="en"><PidPage /></LanguageProvider>)

    submitLookup()
    expect(await screen.findByText(BANKID)).toBeVisible()
    expect(fetchMock).toHaveBeenCalledWith(
      '/api/svc/pid-service/api/v1/parties/by-external-id/lookup',
      expect.objectContaining({
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ type: 'BANKID_SUB', value: BANKID }),
        signal: expect.any(AbortSignal),
      }),
    )
    expect(String(fetchMock.mock.calls[0][0])).not.toContain(BANKID)
    expect(JSON.stringify(fetchMock.mock.calls[0][1])).not.toContain('sensitive-keyed-digest')
    expect(JSON.stringify(fetchMock.mock.calls[0][1])).toContain(BANKID)
    expect(screen.getAllByText('BANKID_SUB')).toHaveLength(2)
    expect(screen.queryByText('sensitive-keyed-digest')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open person' })).toHaveAttribute('href', `/parties/${PARTY_ID}`)
    expect(screen.getByText('Matching identifiers')).toBeVisible()
  })

  it('shows a record-level 404 as no match, not as an undeployed service', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response(404, { error: 'party not found' })))
    render(<LanguageProvider initialLanguage="en"><PidPage /></LanguageProvider>)

    submitLookup('missing-subject')
    expect(await screen.findByText('No exact identifier match found')).toBeVisible()
    expect(screen.queryByText('PID-service is not deployed in this environment')).not.toBeInTheDocument()
    expect(screen.getByText('Matching identifiers')).toBeVisible()
  })

  it('distinguishes the proxy unknown-service 404 from a missing identifier', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response(404, { error: 'Unknown service: pid-service' })))
    render(<LanguageProvider initialLanguage="en"><PidPage /></LanguageProvider>)

    submitLookup()
    expect(await screen.findByText('PID-service is not deployed in this environment')).toBeVisible()
    expect(screen.queryByText('No exact identifier match found')).not.toBeInTheDocument()
  })

  it('an old failed lookup cannot overwrite a newer successful result', async () => {
    let resolveFirst!: (value: Response) => void
    const first = new Promise<Response>(resolve => { resolveFirst = resolve })
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) =>
      String(init?.body).includes('old-subject') ? first : Promise.resolve(response(200, party('new-subject'))),
    )
    vi.stubGlobal('fetch', fetchMock)
    render(<LanguageProvider initialLanguage="en"><PidPage /></LanguageProvider>)

    submitLookup('old-subject')
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
    submitLookup('new-subject')
    expect(await screen.findByText('new-subject')).toBeVisible()

    await act(async () => { resolveFirst(response(503, { error: 'upstream_unreachable' })) })
    await waitFor(() => expect(screen.queryByText('Failed to load: PID records')).not.toBeInTheDocument())
    expect(screen.getByText('new-subject')).toBeVisible()
  })

  it('rejects malformed or mismatched minimized lookup evidence', () => {
    expect(() => parsePidExternalIdLookup([], 'BANKID_SUB', BANKID)).toThrow('Invalid PID lookup response')
    expect(() => parsePidExternalIdLookup(party('other'), 'BANKID_SUB', BANKID)).toThrow('requested identifier')
  })

  it('accepts any canonical Java UUID value for the stable party reference', () => {
    const result = parsePidExternalIdLookup({ ...party(), partyId: '00000000-0000-0000-0000-000000000000' }, 'BANKID_SUB', BANKID)
    expect(result.personId).toBe('00000000-0000-0000-0000-000000000000')
  })
})
