// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SanctionsApprovalDecisionDialog, type ApprovalDecisionIntent } from '@/components/sanctions/SanctionsApprovalDecisionDialog'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const intent = {
  approve: true,
  approval: {
    id: 'approval-42',
    action: 'CLEAR_SANCTIONS_HIT',
    makerId: 'maker-7',
  },
} as ApprovalDecisionIntent

afterEach(cleanup)

describe('sanctions approval decision dialog', () => {
  it('names the exact decision and puts focus on the safe action', () => {
    render(<LanguageProvider><SanctionsApprovalDecisionDialog intent={intent} busy={false} message="" onCancel={vi.fn()} onConfirm={vi.fn()} /></LanguageProvider>)

    const dialog = screen.getByRole('alertdialog', { name: 'Approve request' })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(dialog).toHaveTextContent('CLEAR_SANCTIONS_HIT')
    expect(dialog).toHaveTextContent('maker-7')
    expect(dialog).toHaveTextContent('approval-42')
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Back to review' }))
  })

  it('dismisses with Escape while idle and remains locked while recording', () => {
    const cancel = vi.fn()
    const confirm = vi.fn(async () => undefined)
    const { rerender } = render(<LanguageProvider><SanctionsApprovalDecisionDialog intent={intent} busy={false} message="" onCancel={cancel} onConfirm={confirm} /></LanguageProvider>)

    fireEvent.keyDown(screen.getByRole('alertdialog'), { key: 'Escape' })
    expect(cancel).toHaveBeenCalledOnce()

    rerender(<LanguageProvider><SanctionsApprovalDecisionDialog intent={intent} busy message="" onCancel={cancel} onConfirm={confirm} /></LanguageProvider>)
    fireEvent.keyDown(screen.getByRole('alertdialog'), { key: 'Escape' })
    expect(cancel).toHaveBeenCalledOnce()
    expect(screen.getByRole('button', { name: 'Recording decision…' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Back to review' })).toBeDisabled()
  })

  it('keeps a failed decision visible and retryable', () => {
    const confirm = vi.fn(async () => undefined)
    render(<LanguageProvider><SanctionsApprovalDecisionDialog intent={intent} busy={false} message="Decision failed" onCancel={vi.fn()} onConfirm={confirm} /></LanguageProvider>)

    expect(screen.getByRole('alert')).toHaveTextContent('Decision failed')
    fireEvent.click(screen.getByRole('button', { name: 'Confirm approval' }))
    expect(confirm).toHaveBeenCalledOnce()
  })
})
