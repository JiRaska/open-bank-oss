// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { useState } from 'react'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RemediationReviewDialog } from '@/components/devops/RemediationReviewDialog'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import type { DevOpsFinding } from '@/app/api/devops/insights/route'

const FINDING = {
  id: 'finding-1',
  detector: 'D2_DORA_REGRESSION',
  severity: 'CRITICAL',
  detectedAt: '2026-08-30T00:00:00Z',
  title: 'Change failure rate regressed for ledger-service',
  rawMetricValue: 0.42,
  threshold: 0.15,
  affectedResource: 'openbank-ledger-service',
  doraMetricImpacted: 'CHANGE_FAILURE_RATE',
  rootCause: 'Three of the last five deploys required a rollback within an hour.',
  remediationKind: 'PULL_REQUEST',
  proposalPrUrl: 'https://github.com/JiRaska/open-bank-oss/pull/1234',
  proposedRemediation: 'Add a canary stage before full rollout.',
  status: 'PROPOSED',
  diagnosedAt: '2026-08-30T00:05:00Z',
  proposedAt: '2026-08-30T00:10:00Z',
} satisfies DevOpsFinding

const MINIMAL_FINDING = {
  ...FINDING,
  id: 'finding-2',
  doraMetricImpacted: null,
  rootCause: null,
  proposalPrUrl: null,
  remediationKind: 'NONE',
} satisfies DevOpsFinding

afterEach(cleanup)

function DialogHarness({ finding, outcome }: { finding: DevOpsFinding; outcome?: 'error' }) {
  const [open, setOpen] = useState(false)
  const [failed, setFailed] = useState(false)
  const onConfirm = vi.fn(() => {
    if (outcome === 'error') setFailed(true)
    else setOpen(false)
  })

  return (
    <LanguageProvider>
      {!open && (
        <button type="button" onClick={() => { setFailed(false); setOpen(true) }}>Open review</button>
      )}
      {open && (
        <RemediationReviewDialog
          finding={finding}
          approve
          busy={false}
          failed={failed}
          onCancel={() => setOpen(false)}
          onConfirm={onConfirm}
        />
      )}
    </LanguageProvider>
  )
}

describe('devops remediation review dialog (#7895)', () => {
  it('does not decide on a single click — opening the review calls no decision handler', () => {
    const onConfirm = vi.fn()
    render(
      <LanguageProvider>
        <RemediationReviewDialog finding={FINDING} approve busy={false} failed={false} onCancel={vi.fn()} onConfirm={onConfirm} />
      </LanguageProvider>,
    )

    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    expect(onConfirm).not.toHaveBeenCalled()
  })

  it('shows the finding id, detector, severity, status, root cause, remediation kind, DORA impact and proposal link', () => {
    render(
      <LanguageProvider>
        <RemediationReviewDialog finding={FINDING} approve busy={false} failed={false} onCancel={vi.fn()} onConfirm={vi.fn()} />
      </LanguageProvider>,
    )

    const dialog = screen.getByRole('alertdialog')
    // The heading is `{action}: {finding.title}` — three text nodes, so match on the
    // heading's combined content rather than an exact getByText on the title alone.
    expect(within(dialog).getByRole('heading')).toHaveTextContent('Change failure rate regressed for ledger-service')
    expect(within(dialog).getByText('finding-1')).toBeInTheDocument()
    expect(within(dialog).getByText('D2_DORA_REGRESSION')).toBeInTheDocument()
    expect(within(dialog).getByText('Critical')).toBeInTheDocument()
    expect(within(dialog).getByText('PROPOSED')).toBeInTheDocument()
    expect(within(dialog).getByText(/rollback within an hour/)).toBeInTheDocument()
    expect(within(dialog).getByText('Pull request')).toBeInTheDocument()
    expect(within(dialog).getByText('Change failure')).toBeInTheDocument()
    expect(within(dialog).getByRole('link', { name: /View proposal/ })).toHaveAttribute(
      'href', 'https://github.com/JiRaska/open-bank-oss/pull/1234',
    )
  })

  it('omits DORA impact and proposal link when the finding carries none', () => {
    render(
      <LanguageProvider>
        <RemediationReviewDialog finding={MINIMAL_FINDING} approve busy={false} failed={false} onCancel={vi.fn()} onConfirm={vi.fn()} />
      </LanguageProvider>,
    )

    const dialog = screen.getByRole('alertdialog')
    expect(within(dialog).queryByText('DORA impact')).not.toBeInTheDocument()
    expect(within(dialog).queryByRole('link', { name: /View proposal/ })).not.toBeInTheDocument()
  })

  it('confirms only after the operator clicks the primary action', async () => {
    const user = userEvent.setup()
    const onConfirm = vi.fn()
    render(
      <LanguageProvider>
        <RemediationReviewDialog finding={FINDING} approve busy={false} failed={false} onCancel={vi.fn()} onConfirm={onConfirm} />
      </LanguageProvider>,
    )

    expect(onConfirm).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Confirm approval' }))
    expect(onConfirm).toHaveBeenCalledTimes(1)
  })

  it('keeps the dialog open and announces the error after a failed decision', async () => {
    const user = userEvent.setup()
    render(<DialogHarness finding={FINDING} outcome="error" />)

    await user.click(screen.getByRole('button', { name: 'Open review' }))
    await user.click(screen.getByRole('button', { name: 'Confirm approval' }))

    const dialog = screen.getByRole('alertdialog')
    expect(dialog).toBeInTheDocument()
    expect(within(dialog).getByRole('alert')).toHaveTextContent('The decision could not be saved')
  })

  it('closes on Escape while idle but not while busy', () => {
    const onCancel = vi.fn()
    const { rerender } = render(
      <LanguageProvider>
        <RemediationReviewDialog finding={FINDING} approve busy={false} failed={false} onCancel={onCancel} onConfirm={vi.fn()} />
      </LanguageProvider>,
    )
    fireEvent.keyDown(screen.getByRole('alertdialog'), { key: 'Escape' })
    expect(onCancel).toHaveBeenCalledTimes(1)

    onCancel.mockClear()
    rerender(
      <LanguageProvider>
        <RemediationReviewDialog finding={FINDING} approve busy failed={false} onCancel={onCancel} onConfirm={vi.fn()} />
      </LanguageProvider>,
    )
    fireEvent.keyDown(screen.getByRole('alertdialog'), { key: 'Escape' })
    expect(onCancel).not.toHaveBeenCalled()
  })

  it('disables both actions while a decision is in flight', () => {
    render(
      <LanguageProvider>
        <RemediationReviewDialog finding={FINDING} approve busy failed={false} onCancel={vi.fn()} onConfirm={vi.fn()} />
      </LanguageProvider>,
    )

    expect(screen.getByRole('button', { name: 'Back to review' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Recording decision…' })).toBeDisabled()
  })

  it('moves focus into the dialog on open, since there is no field to carry autoFocus', async () => {
    render(
      <LanguageProvider>
        <RemediationReviewDialog finding={FINDING} approve busy={false} failed={false} onCancel={vi.fn()} onConfirm={vi.fn()} />
      </LanguageProvider>,
    )
    await waitFor(() => expect(screen.getByRole('alertdialog')).toHaveFocus())
  })
})
