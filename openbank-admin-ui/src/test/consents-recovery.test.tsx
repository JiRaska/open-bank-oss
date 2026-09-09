// SPDX-License-Identifier: Apache-2.0

import React from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import ConsentsPage from '@/app/consents/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const consent = {
  id: 'consent-1',
  partyId: 'party-visible-only-while-authorized',
  granteeId: 'party-service:marketing-comms',
  granteeType: 'INTERNAL_SERVICE',
  granteeName: 'Marketing communications',
  scopes: ['MARKETING_COMMS_EMAIL'],
  accountIbans: null,
  status: 'ACTIVE',
  validFrom: '2026-06-01T00:00:00Z',
  validTo: '2026-12-01T00:00:00Z',
  createdAt: '2026-05-31T10:00:00Z',
}

function response(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

function mount() {
  render(<LanguageProvider><ConsentsPage /></LanguageProvider>)
  fireEvent.change(screen.getByRole('combobox', { name: 'Consent lookup lens' }), { target: { value: 'grantee' } })
}

async function lookup() {
  fireEvent.click(screen.getByRole('button', { name: 'Look up' }))
  await waitFor(() => expect(screen.queryByText('Loading…')).not.toBeInTheDocument())
}

beforeEach(() => vi.unstubAllGlobals())
afterEach(() => cleanup())

describe('consent lookup recovery', () => {
  it('retains and labels the last verified result after a transient refresh failure', async () => {
    const fetchSpy = vi.fn()
      .mockResolvedValueOnce(response(200, [consent]))
      .mockRejectedValueOnce(new Error('network down'))
    vi.stubGlobal('fetch', fetchSpy)
    mount()

    await lookup()
    expect(screen.getByText(consent.partyId)).toBeVisible()
    await lookup()

    expect(screen.getByText(consent.partyId)).toBeVisible()
    expect(screen.getByRole('alert')).toHaveTextContent('last verified result')
  })

  it('rejects a malformed service response as invalid data, not a network outage', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(200, [{ ...consent, validTo: 'invalid' }])))
    mount()

    await lookup()

    expect(screen.getByText('Failed to load: Consents')).toBeVisible()
    expect(screen.queryByText('Consent-service is not responding')).not.toBeInTheDocument()
    expect(screen.queryByText(consent.partyId)).not.toBeInTheDocument()
  })

  it.each([401, 403])('removes protected consent data immediately after HTTP %s', async status => {
    const fetchSpy = vi.fn()
      .mockResolvedValueOnce(response(200, [consent]))
      .mockResolvedValueOnce(response(status, { error: status === 401 ? 'unauthorized' : 'forbidden' }))
    vi.stubGlobal('fetch', fetchSpy)
    mount()

    await lookup()
    expect(screen.getByText(consent.partyId)).toBeVisible()
    await lookup()

    expect(screen.queryByText(consent.partyId)).not.toBeInTheDocument()
    expect(screen.queryByText(/last verified result/i)).not.toBeInTheDocument()
    expect(screen.getByText('Session expired')).toBeVisible()
  })
})
