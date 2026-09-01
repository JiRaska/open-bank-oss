// SPDX-License-Identifier: Apache-2.0

import { useRef, useState } from 'react'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ConfirmTransitionDialog } from '@/components/cards/ConfirmTransitionDialog'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import type { Feedback } from '@/lib/cards/useCardOperations'
import type { Card } from '@/lib/cards/types'

const CARD = {
  id: 'card-1', partyId: 'party-1', accountId: 'account-1', productCode: 'CURRENT_CZK',
  cardType: 'DEBIT', network: 'VISA', maskedPan: '**** 4242', cardholderName: 'A',
  embossedName: 'A', expiryDate: '2030-01', status: 'ACTIVE', dailyLimitMinorUnits: 1,
  monthlyLimitMinorUnits: 2, currency: 'CZK', createdAt: '2026-08-26T00:00:00Z',
} satisfies Card

afterEach(cleanup)

function DialogHarness({
  closeOnConfirm = false, outcome, onConfirm,
}: {
  closeOnConfirm?: boolean
  outcome?: 'error' | 'four-eyes'
  onConfirm?: (reason: string) => void
}) {
  const [open, setOpen] = useState(false)
  const [showTrigger, setShowTrigger] = useState(true)
  const [feedback, setFeedback] = useState<Feedback | null>(null)
  const stableContextRef = useRef<HTMLButtonElement>(null)
  const closeFocusOverrideRef = useRef<HTMLElement | null>(null)

  return (
    <LanguageProvider>
      <button ref={stableContextRef} type="button">Card row</button>
      {showTrigger && (
        <button type="button" onClick={() => {
          closeFocusOverrideRef.current = null
          setOpen(true)
        }}>Open card action</button>
      )}
      {open && (
        <ConfirmTransitionDialog
          card={CARD}
          transition={{ action: 'block', to: 'BLOCKED', irreversible: true, reason: true }}
          busy={false}
          feedback={feedback}
          closeFocusOverrideRef={closeFocusOverrideRef}
          onCancel={() => setOpen(false)}
          onDismissFeedback={() => setFeedback(null)}
          onConfirm={reason => {
            onConfirm?.(reason)
            if (closeOnConfirm) {
              closeFocusOverrideRef.current = stableContextRef.current
              setShowTrigger(false)
              setOpen(false)
            } else if (outcome === 'error') {
              setFeedback({ tone: 'error', text: 'The service refused the card transition.' })
            } else if (outcome === 'four-eyes') {
              setFeedback({ tone: 'info', text: 'The operation is queued for a second operator’s approval (four-eyes).' })
            }
          }}
        />
      )}
    </LanguageProvider>
  )
}

describe('card transition dialog', () => {
  it('moves initial focus into the required reason and traps Tab in both directions', async () => {
    const user = userEvent.setup()
    render(<DialogHarness />)

    await user.click(screen.getByRole('button', { name: 'Open card action' }))

    const reason = screen.getByLabelText('Reason for the operation')
    const back = screen.getByRole('button', { name: 'Back' })
    await waitFor(() => expect(reason).toHaveFocus())

    await user.tab({ shift: true })
    expect(back).toHaveFocus()
    await user.tab()
    expect(reason).toHaveFocus()
  })

  it('associates the visible irreversible-action title and warning with the dialog', () => {
    const onConfirm = vi.fn()
    render(
      <LanguageProvider>
        <ConfirmTransitionDialog
          card={CARD}
          transition={{ action: 'block', to: 'BLOCKED', irreversible: true, reason: true }}
          busy={false}
          feedback={null}
          onCancel={vi.fn()}
          onConfirm={onConfirm}
          onDismissFeedback={vi.fn()}
        />
      </LanguageProvider>,
    )

    const dialog = screen.getByRole('dialog', { name: 'Block card' })
    const titleId = dialog.getAttribute('aria-labelledby')
    const descriptionId = dialog.getAttribute('aria-describedby')
    expect(titleId).toBeTruthy()
    expect(descriptionId).toBeTruthy()
    expect(document.getElementById(titleId!)).toHaveTextContent('Block card')
    expect(document.getElementById(descriptionId!)).toHaveTextContent('This operation cannot be undone.')

    const reason = screen.getByLabelText('Reason for the operation')
    fireEvent.change(reason, { target: { value: 'Customer request' } })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm block' }))
    expect(onConfirm).toHaveBeenCalledWith('Customer request')
  })

  it('closes on Escape only while idle and restores focus to the trigger', async () => {
    const user = userEvent.setup()
    render(<DialogHarness />)

    const trigger = screen.getByRole('button', { name: 'Open card action' })
    await user.click(trigger)
    await waitFor(() => expect(screen.getByLabelText('Reason for the operation')).toHaveFocus())
    await user.keyboard('{Escape}')

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    await waitFor(() => expect(trigger).toHaveFocus())
  })

  it('keeps the dialog open on Escape while a transition is busy', async () => {
    const user = userEvent.setup()
    const onCancel = vi.fn()
    render(
      <LanguageProvider>
        <ConfirmTransitionDialog
          card={CARD}
          transition={{ action: 'cancel', to: 'CANCELLED', irreversible: true, reason: true }}
          busy
          feedback={null}
          onCancel={onCancel}
          onConfirm={vi.fn()}
          onDismissFeedback={vi.fn()}
        />
      </LanguageProvider>,
    )

    await waitFor(() => expect(screen.getByLabelText('Reason for the operation')).toHaveFocus())
    await user.keyboard('{Escape}')

    expect(onCancel).not.toHaveBeenCalled()
    expect(screen.getByRole('dialog', { name: 'Cancel card' })).toBeInTheDocument()
  })

  it('announces a failed transition from a live region owned by the open dialog', async () => {
    const user = userEvent.setup()
    const onConfirm = vi.fn()
    render(<DialogHarness outcome="error" onConfirm={onConfirm} />)

    await user.click(screen.getByRole('button', { name: 'Open card action' }))
    await user.type(screen.getByLabelText('Reason for the operation'), 'Customer request')
    await user.click(screen.getByRole('button', { name: 'Confirm block' }))

    const dialog = screen.getByRole('dialog', { name: 'Block card' })
    const liveRegion = within(dialog).getByRole('status')
    expect(liveRegion).toHaveTextContent('The service refused the card transition.')
    expect(onConfirm).toHaveBeenCalledWith('Customer request')
  })

  it('announces a four-eyes pause from a live region owned by the open dialog', async () => {
    const user = userEvent.setup()
    render(<DialogHarness outcome="four-eyes" />)

    await user.click(screen.getByRole('button', { name: 'Open card action' }))
    await user.type(screen.getByLabelText('Reason for the operation'), 'High-risk request')
    await user.click(screen.getByRole('button', { name: 'Confirm block' }))

    const dialog = screen.getByRole('dialog', { name: 'Block card' })
    expect(within(dialog).getByRole('status')).toHaveTextContent(
      'The operation is queued for a second operator’s approval (four-eyes).',
    )
  })

  it('restores focus to caller-owned context when success removes the trigger', async () => {
    const user = userEvent.setup()
    render(<DialogHarness closeOnConfirm />)

    const stableContext = screen.getByRole('button', { name: 'Card row' })
    const trigger = screen.getByRole('button', { name: 'Open card action' })
    await user.click(trigger)
    await user.type(screen.getByLabelText('Reason for the operation'), 'Customer request')
    await user.click(screen.getByRole('button', { name: 'Confirm block' }))

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(trigger).not.toBeInTheDocument()
    await waitFor(() => expect(stableContext).toHaveFocus())
  })

  it('marks the modal and confirmation action busy', () => {
    render(
      <LanguageProvider>
        <ConfirmTransitionDialog
          card={CARD}
          transition={{ action: 'cancel', to: 'CANCELLED', irreversible: true, reason: true }}
          busy
          feedback={null}
          onCancel={vi.fn()}
          onConfirm={vi.fn()}
          onDismissFeedback={vi.fn()}
        />
      </LanguageProvider>,
    )

    expect(screen.getByRole('dialog', { name: 'Cancel card' }).getAttribute('aria-busy')).toBe('true')
    expect(screen.getByRole('button', { name: 'Submitting…' }).getAttribute('aria-busy')).toBe('true')
  })
})
