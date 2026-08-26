// SPDX-License-Identifier: Apache-2.0

import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ConfirmTransitionDialog } from '@/components/cards/ConfirmTransitionDialog'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import type { Card } from '@/lib/cards/types'

const CARD = {
  id: 'card-1', partyId: 'party-1', accountId: 'account-1', productCode: 'CURRENT_CZK',
  cardType: 'DEBIT', network: 'VISA', maskedPan: '**** 4242', cardholderName: 'A',
  embossedName: 'A', expiryDate: '2030-01', status: 'ACTIVE', dailyLimitMinorUnits: 1,
  monthlyLimitMinorUnits: 2, currency: 'CZK', createdAt: '2026-08-26T00:00:00Z',
} satisfies Card

afterEach(cleanup)

describe('card transition dialog', () => {
  it('contains focus and exposes the submitting state', () => {
    const onConfirm = vi.fn()
    render(
      <LanguageProvider>
        <ConfirmTransitionDialog
          card={CARD}
          transition={{ action: 'block', to: 'BLOCKED', irreversible: true, reason: true }}
          busy={false}
          onCancel={vi.fn()}
          onConfirm={onConfirm}
        />
      </LanguageProvider>,
    )

    const dialog = screen.getByRole('dialog', { name: 'Block card' })
    const reason = screen.getByLabelText('Reason for the operation')
    const back = screen.getByRole('button', { name: 'Back' })

    reason.focus()
    fireEvent.keyDown(dialog, { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(back)
    fireEvent.keyDown(dialog, { key: 'Tab' })
    expect(document.activeElement).toBe(reason)

    fireEvent.change(reason, { target: { value: 'Customer request' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm block' }))
    expect(onConfirm).toHaveBeenCalledWith('Customer request')
  })

  it('marks the modal and confirmation action busy', () => {
    render(
      <LanguageProvider>
        <ConfirmTransitionDialog
          card={CARD}
          transition={{ action: 'cancel', to: 'CANCELLED', irreversible: true, reason: true }}
          busy
          onCancel={vi.fn()}
          onConfirm={vi.fn()}
        />
      </LanguageProvider>,
    )

    expect(screen.getByRole('dialog', { name: 'Cancel card' }).getAttribute('aria-busy')).toBe('true')
    expect(screen.getByRole('button', { name: 'Submitting…' }).getAttribute('aria-busy')).toBe('true')
  })
})
