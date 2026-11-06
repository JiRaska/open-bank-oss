// SPDX-License-Identifier: Apache-2.0

// #14780: a manual list refresh is queued (202), not done. While `refreshRequestedAt` is set the
// list row must say so, because the count and date beside it belong to the PREVIOUS download.

import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { SanctionsRefreshState } from '@/components/sanctions/SanctionsRefreshState'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

afterEach(cleanup)

function renderState(refreshRequestedAt?: string | null) {
  return render(
    <LanguageProvider>
      <SanctionsRefreshState refreshRequestedAt={refreshRequestedAt} dateLocale="en-GB" />
    </LanguageProvider>,
  )
}

describe('sanctions refresh queued state', () => {
  it('announces a queued refresh and that the figures are from the previous download', () => {
    renderState('2026-11-04T08:00:00Z')
    const status = screen.getByRole('status')
    expect(status).toHaveTextContent('Download queued since')
    expect(status).toHaveTextContent('runs on the next scheduler pass')
    expect(status).toHaveTextContent('the figures below are from the previous download')
  })

  it('renders nothing once the scheduler has imported the list (flag cleared)', () => {
    renderState(null)
    expect(screen.queryByTestId('sanctions-refresh-queued')).toBeNull()
    renderState(undefined)
    expect(screen.queryByTestId('sanctions-refresh-queued')).toBeNull()
  })

  it('still says queued when the timestamp cannot be parsed, rather than hiding the state', () => {
    renderState('not-a-date')
    expect(screen.getByRole('status')).toHaveTextContent('Download queued —')
  })
})
