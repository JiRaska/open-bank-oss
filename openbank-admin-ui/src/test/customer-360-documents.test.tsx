// SPDX-License-Identifier: Apache-2.0

import { afterEach, describe, expect, it, vi } from 'vitest'
import React from 'react'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { DocumentsPanel } from '@/components/party/DocumentsPanel'

const PARTY = '55555555-5555-5555-5555-555555555555'
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })

afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

describe('Customer 360 documents panel', () => {
  it('queries the document owner by the selected party and exposes the audited content route', async () => {
    const f = vi.fn(async () => json([{
      id: 'doc-1', templateCode: 'SECCI', templateVersion: '2', contentType: 'application/pdf',
      sizeBytes: 2048, status: 'GENERATED', caseRef: 'case-7', productRef: null,
      retainUntil: '2036-08-20', createdAt: '2026-08-20T10:00:00Z',
    }]))
    vi.stubGlobal('fetch', f)
    render(<LanguageProvider><DocumentsPanel partyId={PARTY} /></LanguageProvider>)

    await waitFor(() => expect(screen.getByText('SECCI')).toBeInTheDocument())
    expect(String(f.mock.calls[0][0])).toBe(`/api/svc/document-service/api/v1/documents?partyRef=${PARTY}`)
    expect(screen.getByRole('link', { name: /Download/i })).toHaveAttribute(
      'href', '/api/svc/document-service/api/v1/documents/doc-1/content',
    )
    expect(screen.getByText('case-7')).toBeInTheDocument()
  })

  it('distinguishes a measured empty list from an unavailable service', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => json([])))
    const view = render(<LanguageProvider><DocumentsPanel partyId={PARTY} /></LanguageProvider>)
    await waitFor(() => expect(screen.getByText(/No documents for this customer/i)).toBeInTheDocument())

    view.unmount()
    vi.stubGlobal('fetch', vi.fn(async () => json({ error: 'scaled_to_zero' }, 503)))
    render(<LanguageProvider><DocumentsPanel partyId={PARTY} /></LanguageProvider>)
    await waitFor(() => expect(screen.getByText(/Documents unavailable.*scaled_to_zero/i)).toBeInTheDocument())
    expect(screen.queryByText(/No documents for this customer/i)).toBeNull()
  })
})
