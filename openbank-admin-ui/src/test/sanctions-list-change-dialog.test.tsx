// SPDX-License-Identifier: Apache-2.0

import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  retainEnabledSelectedListTypes,
  SanctionsListChangeDialog,
  type SanctionsList,
} from '@/components/sanctions/SanctionsListChangeDialog'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const LIST = {
  id: 'eu-consolidated',
  listType: 'EU',
  displayName: 'EU Consolidated Financial Sanctions List',
  sourceUrl: 'https://example.test/eu-sanctions',
  enabled: true,
  lastUpdatedAt: '2026-08-31T12:00:00Z',
  lastEntryCount: 1248,
  cronHour: 3,
  cronMinute: 0,
  cronDays: 'MON,TUE,WED,THU,FRI,SAT,SUN',
} satisfies SanctionsList

afterEach(cleanup)

describe('sanctions-list enablement review', () => {
  it('prunes disabled types after reconciliation without restoring user deselections', () => {
    const reconciledLists = [
      { ...LIST, enabled: false },
      { ...LIST, id: 'ofac-sdn', listType: 'OFAC_SDN', displayName: 'OFAC SDN' },
    ]

    expect(retainEnabledSelectedListTypes(['EU', 'OFAC_SDN'], reconciledLists)).toEqual(['OFAC_SDN'])
    expect(retainEnabledSelectedListTypes(['EU'], reconciledLists)).toEqual([])
  })

  it('explains the exact disable impact without claiming a global screening exclusion', () => {
    render(
      <LanguageProvider>
        <SanctionsListChangeDialog
          list={LIST}
          enabled={false}
          busy={false}
          error={false}
          onCancel={vi.fn()}
          onConfirm={vi.fn()}
        />
      </LanguageProvider>,
    )

    const dialog = screen.getByRole('alertdialog', { name: `Pause automatic updates for “${LIST.displayName}”?` })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(dialog).toHaveTextContent('Future scheduled and refresh-all runs will skip this list')
    expect(dialog).toHaveTextContent('a download already in progress may finish')
    expect(dialog).toHaveTextContent('API screenings outside this console are not excluded by this setting')
    expect(dialog).toHaveTextContent('1,248 entries')
    expect(dialog).toHaveTextContent('ENABLED')
    expect(dialog).toHaveTextContent('PAUSED')
    expect(dialog).toHaveTextContent('does not enter the four-eyes approval queue')
  })

  it('contains keyboard focus and supports escape dismissal before submission', () => {
    const cancel = vi.fn()
    const confirm = vi.fn()
    render(
      <LanguageProvider>
        <SanctionsListChangeDialog
          list={LIST}
          enabled={false}
          busy={false}
          error={false}
          onCancel={cancel}
          onConfirm={confirm}
        />
      </LanguageProvider>,
    )

    const dialog = screen.getByRole('alertdialog')
    const keep = screen.getByRole('button', { name: 'Keep automatic updates' })
    const apply = screen.getByRole('button', { name: 'Pause automatic updates' })
    keep.focus()
    fireEvent.keyDown(dialog, { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(apply)
    fireEvent.keyDown(dialog, { key: 'Tab' })
    expect(document.activeElement).toBe(keep)
    fireEvent.keyDown(dialog, { key: 'Escape' })
    expect(cancel).toHaveBeenCalledOnce()
  })

  it('locks duplicate submission and leaves a failed change retryable', () => {
    const cancel = vi.fn()
    const confirm = vi.fn()
    const { rerender } = render(
      <LanguageProvider>
        <SanctionsListChangeDialog
          list={LIST}
          enabled={false}
          busy
          error={false}
          onCancel={cancel}
          onConfirm={confirm}
        />
      </LanguageProvider>,
    )

    const dialog = screen.getByRole('alertdialog')
    expect(dialog).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByRole('button', { name: 'Applying change…' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Keep automatic updates' })).toBeDisabled()
    fireEvent.keyDown(dialog, { key: 'Escape' })
    expect(cancel).not.toHaveBeenCalled()
    expect(confirm).not.toHaveBeenCalled()

    rerender(
      <LanguageProvider>
        <SanctionsListChangeDialog
          list={LIST}
          enabled={false}
          busy={false}
          error
          onCancel={cancel}
          onConfirm={confirm}
        />
      </LanguageProvider>,
    )

    expect(screen.getByRole('alert')).toHaveTextContent('The service did not confirm the change')
    expect(screen.getByRole('alert')).toHaveTextContent('retrying the same target state is safe')
    expect(screen.getByRole('button', { name: 'Close and refresh status' })).toBeEnabled()
    fireEvent.click(screen.getByRole('button', { name: 'Pause automatic updates' }))
    expect(confirm).toHaveBeenCalledOnce()
  })

  it('renders an unknown entry count truthfully', () => {
    render(
      <LanguageProvider>
        <SanctionsListChangeDialog
          list={{ ...LIST, lastEntryCount: undefined }}
          enabled={false}
          busy={false}
          error={false}
          onCancel={vi.fn()}
          onConfirm={vi.fn()}
        />
      </LanguageProvider>,
    )

    expect(screen.getByRole('alertdialog')).toHaveTextContent('Entry count not synced')
    expect(screen.getByRole('alertdialog')).not.toHaveTextContent('0 entries')
  })

  it('shows the authorization-specific failure and offers status reconciliation', () => {
    const reconcile = vi.fn()
    render(
      <LanguageProvider>
        <SanctionsListChangeDialog
          list={LIST}
          enabled={false}
          busy={false}
          error="unauthorized"
          onCancel={reconcile}
          onConfirm={vi.fn()}
        />
      </LanguageProvider>,
    )

    expect(screen.getByRole('alert')).toHaveTextContent('This session is not authorised to make the change')
    fireEvent.click(screen.getByRole('button', { name: 'Close and refresh status' }))
    expect(reconcile).toHaveBeenCalledOnce()
  })

  it('describes re-enablement as resuming refresh and console selection', () => {
    render(
      <LanguageProvider>
        <SanctionsListChangeDialog
          list={{ ...LIST, enabled: false }}
          enabled
          busy={false}
          error={false}
          onCancel={vi.fn()}
          onConfirm={vi.fn()}
        />
      </LanguageProvider>,
    )

    expect(screen.getByRole('alertdialog', { name: `Resume automatic updates for “${LIST.displayName}”?` }))
      .toHaveTextContent('Scheduled and refresh-all downloads resume')
    expect(screen.getByRole('alertdialog')).toHaveTextContent('new manual checks from this console')
  })
})
