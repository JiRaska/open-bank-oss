// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ScaApprovalWorkbench } from '@/components/sca/ScaApprovalWorkbench'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { approvalWorkbenchHref } from '@/lib/approvals/triage'

const id = 'bde18d9e-3e49-4f4e-98cc-a56646ccbc61'
const party = 'd52f0505-bb8a-4a9f-b8e0-9c9c5f765876'
const device = 'ab72c875-9e17-4491-a5ef-0bdb34946a3b'
const approval = { id, action: 'device.revoke', resourceId: `${party}@${device}`, status: 'PENDING', makerId: 'maker', createdAt: '2026-09-14T00:00:00Z', decidedBy: null }

function mount() {
  return render(<LanguageProvider initialLanguage="en"><ScaApprovalWorkbench id={id} /></LanguageProvider>)
}
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('SCA approval workbench', () => {
  it('links the queue to the exact review and confirms one decision after target review', async () => {
    let finish!: (value: Response) => void
    const fetcher = vi.fn().mockResolvedValueOnce(Response.json(approval))
      .mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    vi.stubGlobal('fetch', fetcher)
    expect(approvalWorkbenchHref({ id, domain: 'sca', action: approval.action, resourceId: approval.resourceId, maker: 'maker', proposedAt: approval.createdAt }))
      .toBe(`/approvals/sca/${id}`)
    mount()
    const approve = await screen.findByRole('button', { name: 'Approve' })
    expect(approve).toBeDisabled()
    expect(screen.getByText(device)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.click(approve)
    expect(fetcher).toHaveBeenCalledOnce()
    expect(screen.getByRole('alertdialog')).toHaveTextContent(`${party}@${device}`)
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Back to review' }))
    const confirm = screen.getByRole('button', { name: 'Confirm decision' })
    fireEvent.click(confirm)
    fireEvent.click(confirm)
    expect(fetcher).toHaveBeenCalledTimes(2)
    expect(fetcher.mock.calls[1][0]).toBe(`/api/sca/approvals/${id}`)
    expect(JSON.parse(fetcher.mock.calls[1][1].body)).toEqual({ approve: true })
    finish(Response.json({ ...approval, status: 'APPROVED', decidedBy: 'checker' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())
    expect(screen.getByRole('status')).toHaveTextContent('Approval recorded')
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
  })

  it('requires the verified fingerprint before approving enrollment', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ ...approval, action: 'device.enroll', resourceId: `${party}@${'a'.repeat(64)}` })))
    mount()
    const approve = await screen.findByRole('button', { name: 'Approve' })
    fireEvent.click(screen.getByRole('checkbox'))
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'b'.repeat(64) } })
    expect(approve).toBeDisabled()
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'a'.repeat(64) } })
    expect(approve).toBeEnabled()
  })

  it('reloads an uncertain decision instead of allowing a blind retry', async () => {
    const fetcher = vi.fn().mockResolvedValueOnce(Response.json(approval))
      .mockRejectedValueOnce(new Error('response lost'))
      .mockResolvedValueOnce(Response.json({ ...approval, status: 'REJECTED', decidedBy: 'checker' }))
    vi.stubGlobal('fetch', fetcher)
    mount()
    fireEvent.click(await screen.findByRole('button', { name: 'Reject' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm decision' }))
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('response is missing'))
    expect(screen.queryByRole('button', { name: 'Reject' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Reload state' }))
    await screen.findByText('REJECTED')
    expect(fetcher).toHaveBeenCalledTimes(3)
    expect(fetcher.mock.calls[2][1].method).toBeUndefined()
  })

  it('does not allow decisions on a malformed resource or a different returned id', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ ...approval, resourceId: party })))
    const view = mount()
    await screen.findByText('This request type cannot be safely reviewed in this interface.')
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
    view.unmount()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(Response.json({ ...approval, id: device })))
    mount()
    await waitFor(() => expect(screen.queryByText('Loading approval…')).not.toBeInTheDocument())
    expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument()
  })
})
