// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import IdentityCasesPage from '@/app/identity-cases/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

vi.mock('@/components/auth/AuthGuard', () => ({
  AuthGuard: ({ children }: { children: ReactNode }) => children,
  Can: ({ children }: { children: ReactNode }) => children,
}))

const openCase = {
  id: '019fbe12-1111-7222-8333-444444444444',
  trigger: 'NAMESAKE_CANDIDATE',
  status: 'OPEN',
  applicant: { givenName: 'Adam', familyName: 'Novák', birthdate: '1990-01-02', birthplace: 'Praha', nationalities: ['CZ'] },
  candidatePartyIds: ['019fbe12-aaaa-7bbb-8ccc-dddddddddddd'],
  firstApprover: null,
  firstVerdict: null,
  firstLinkPartyId: null,
  firstNotes: null,
  firstAt: null,
  secondApprover: null,
  finalVerdict: null,
  decidedAt: null,
  createdAt: '2026-09-02T09:00:00Z',
}

const secondVoteCase = {
  ...openCase,
  id: '019fbe12-2222-7333-8444-555555555555',
  trigger: 'RN_COLLISION',
  status: 'AWAITING_SECOND_APPROVAL',
  applicant: { givenName: 'Beáta', familyName: 'Svobodová', birthdate: '1984-03-04', birthplace: 'Brno', nationalities: ['CZ'] },
  candidatePartyIds: ['019fbe12-bbbb-7ccc-8ddd-eeeeeeeeeeee'],
  firstApprover: 'first.reviewer',
  firstVerdict: 'DISTINCT_NEW',
  firstAt: '2026-09-02T08:30:00Z',
  createdAt: '2026-09-01T09:00:00Z',
}

function deferred<T>() {
  let resolve!: (value: T) => void
  return { promise: new Promise<T>(settle => { resolve = settle }), resolve }
}

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('identity case queue triage', () => {
  it('announces initial loading, prioritizes the second vote, and filters without changing the queue', async () => {
    const response = deferred<Response>()
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(response.promise))

    await act(async () => {
      render(<LanguageProvider initialLanguage="en"><IdentityCasesPage /></LanguageProvider>)
    })

    expect(screen.getByRole('status')).toHaveTextContent('Loading the case queue')
    expect(screen.queryByText('No open cases')).not.toBeInTheDocument()

    await act(async () => {
      response.resolve(new Response(JSON.stringify([openCase, secondVoteCase]), { status: 200 }))
    })

    expect(await screen.findByText('2 active cases')).toBeVisible()
    expect(screen.getByText(/1 awaits an independent second vote/)).toBeVisible()
    const secondVoteName = screen.getByText('Beáta Svobodová')
    const openName = screen.getByText('Adam Novák')
    expect(secondVoteName.compareDocumentPosition(openName) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.getAllByText('National-ID collision')).toHaveLength(2)
    expect(screen.getAllByText('Possible namesake')).toHaveLength(2)

    fireEvent.change(screen.getByLabelText('Review stage'), { target: { value: 'OPEN' } })
    expect(screen.getByText('Adam Novák')).toBeVisible()
    expect(screen.queryByText('Beáta Svobodová')).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Find a person or case'), { target: { value: 'missing person' } })
    expect(screen.getByRole('status')).toHaveTextContent('No cases match these filters')
    expect(screen.queryByText('No open cases')).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Find a person or case'), { target: { value: '' } })
    fireEvent.change(screen.getByLabelText('Review stage'), { target: { value: 'ALL' } })
    await waitFor(() => expect(screen.getByText('Beáta Svobodová')).toBeVisible())
    expect(fetch).toHaveBeenCalledTimes(1)
  })
})
