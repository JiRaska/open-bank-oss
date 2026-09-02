// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { DataUnavailable } from '@/components/feedback/DataUnavailable'

describe('DataUnavailable accessibility announcements', () => {
  it('politely announces an operational failure without moving focus', () => {
    render(<DataUnavailable kind="unreachable" service="Ledger-service" lang="en" />)

    const status = screen.getByRole('status')
    expect(status).toHaveAttribute('aria-live', 'polite')
    expect(status).toHaveTextContent('Ledger-service is not responding')
  })

  it('uses an assertive alert only for an expired session', () => {
    render(<DataUnavailable kind="unauthorized" lang="en" />)

    const alert = screen.getByRole('alert')
    expect(alert).toHaveAttribute('aria-live', 'assertive')
    expect(alert).toHaveTextContent('Session expired')
  })

  it('keeps an ordinary empty result silent', () => {
    render(<DataUnavailable kind="no_data" feature="Fees" lang="en" />)

    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByText('No data yet: Fees')).toBeVisible()
  })
})
