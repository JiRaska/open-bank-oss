// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { OperatorMessageApprovals } from '@/components/notifications/NotificationsPage'

const { decide } = vi.hoisted(() => ({ decide: vi.fn() }))

vi.mock('@/lib/api', () => ({ opsMessageApi: { decide } }))

function renderApprovals() {
  return render(<LanguageProvider initialLanguage="en"><OperatorMessageApprovals /></LanguageProvider>)
}

describe('notification approval dialog', () => {
  beforeEach(() => {
    decide.mockReset()
    window.history.replaceState({}, '', '/notifications')
  })

  it('reviews the exact approval before one confirmed request', async () => {
    let finish!: (value: { status: string }) => void
    decide.mockReturnValue(new Promise(resolve => { finish = resolve }))
    renderApprovals()

    fireEvent.change(screen.getByRole('textbox', { name: 'Notification approval ID' }), { target: { value: '  approval-42  ' } })
    fireEvent.click(screen.getByRole('button', { name: 'Approve' }))

    const dialog = screen.getByRole('alertdialog', { name: 'Approve sending this message?' })
    expect(dialog).toHaveTextContent('approval-42')
    expect(dialog).toHaveTextContent('refuses self-approval')
    expect(decide).not.toHaveBeenCalled()

    const confirm = screen.getByRole('button', { name: 'Confirm approval' })
    fireEvent.click(confirm)
    fireEvent.click(confirm)
    expect(decide).toHaveBeenCalledOnce()
    expect(decide).toHaveBeenCalledWith('approval-42', true)
    expect(screen.getByRole('alertdialog')).toHaveAttribute('aria-busy', 'true')

    finish({ status: 'APPROVED' })
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())
    expect(screen.getByRole('status')).toHaveTextContent('Decision recorded: APPROVED')
    expect(screen.getByRole('textbox', { name: 'Notification approval ID' })).toHaveValue('')
  })

  it('keeps a failed rejection bound to the same id and permits a safe retry', async () => {
    decide.mockRejectedValueOnce(new Error('conflict')).mockResolvedValueOnce({ status: 'REJECTED' })
    renderApprovals()

    fireEvent.change(screen.getByRole('textbox', { name: 'Notification approval ID' }), { target: { value: 'approval-9' } })
    fireEvent.click(screen.getByRole('button', { name: 'Reject' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm rejection' }))

    const failure = await screen.findByRole('alert')
    expect(failure).toHaveTextContent('The decision failed')
    expect(screen.getByRole('alertdialog')).toHaveTextContent('approval-9')
    fireEvent.click(screen.getByRole('button', { name: 'Confirm rejection' }))

    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())
    expect(decide).toHaveBeenNthCalledWith(2, 'approval-9', false)
    expect(screen.getByRole('status')).toHaveTextContent('Decision recorded: REJECTED')
  })
})
