// SPDX-License-Identifier: Apache-2.0

import { useState } from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ConfirmTransitionDialog } from '@/components/cards/ConfirmTransitionDialog'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import type { CardTransition } from '@/lib/cards/lifecycle'
import type { Card } from '@/lib/cards/types'

const CARD = {
  id: 'card-1', partyId: 'party-1', accountId: 'account-1', productCode: 'CURRENT_CZK',
  cardType: 'DEBIT', network: 'VISA', maskedPan: '**** 4242', cardholderName: 'A',
  embossedName: 'A', expiryDate: '2030-01', status: 'ACTIVE', dailyLimitMinorUnits: 1,
  monthlyLimitMinorUnits: 2, currency: 'CZK', createdAt: '2026-08-26T00:00:00Z',
} satisfies Card

const BLOCK: CardTransition = { action: 'block', to: 'BLOCKED', irreversible: true, reason: true }

/** Mirrors the real caller shape (cards/page.tsx): a trigger that conditionally mounts the dialog. */
function TriggerAndDialog({ onConfirm }: { onConfirm: (reason: string) => void }) {
  const [pending, setPending] = useState(false)
  return (
    <LanguageProvider>
      <button type="button" onClick={() => setPending(true)}>Block card</button>
      {pending && (
        <ConfirmTransitionDialog
          card={CARD}
          transition={BLOCK}
          busy={false}
          onCancel={() => setPending(false)}
          onConfirm={reason => { onConfirm(reason); setPending(false) }}
        />
      )}
    </LanguageProvider>
  )
}

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

  it('does not dismiss on Escape while a transition is in flight', () => {
    const onCancel = vi.fn()
    render(
      <LanguageProvider>
        <ConfirmTransitionDialog
          card={CARD}
          transition={BLOCK}
          busy
          onCancel={onCancel}
          onConfirm={vi.fn()}
        />
      </LanguageProvider>,
    )

    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })

    expect(onCancel).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('returns focus to the invoking trigger after cancelling with Escape', async () => {
    render(<TriggerAndDialog onConfirm={vi.fn()} />)

    const trigger = screen.getByRole('button', { name: 'Block card' })
    trigger.focus()
    fireEvent.click(trigger)

    const dialog = await screen.findByRole('dialog', { name: 'Block card' })
    fireEvent.keyDown(dialog, { key: 'Escape' })

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(trigger))
  })

  it('returns focus to the invoking trigger after a successful confirm', async () => {
    const onConfirm = vi.fn()
    render(<TriggerAndDialog onConfirm={onConfirm} />)

    const trigger = screen.getByRole('button', { name: 'Block card' })
    trigger.focus()
    fireEvent.click(trigger)

    await screen.findByRole('dialog', { name: 'Block card' })
    fireEvent.change(screen.getByLabelText('Reason for the operation'), { target: { value: 'Lost card' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm block' }))

    expect(onConfirm).toHaveBeenCalledWith('Lost card')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(trigger))
  })

  it('connects the visible title and warning as the dialog accessible name and description', () => {
    render(
      <LanguageProvider>
        <ConfirmTransitionDialog
          card={CARD}
          transition={BLOCK}
          busy={false}
          onCancel={vi.fn()}
          onConfirm={vi.fn()}
        />
      </LanguageProvider>,
    )

    const dialog = screen.getByRole('dialog')
    const labelledBy = dialog.getAttribute('aria-labelledby')
    const describedBy = dialog.getAttribute('aria-describedby')
    expect(labelledBy).toBeTruthy()
    expect(describedBy).toBeTruthy()
    expect(document.getElementById(labelledBy!)?.textContent).toBe('Block card')
    expect(document.getElementById(describedBy!)?.textContent).toBe('This operation cannot be undone.')
  })
})
