// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import React from 'react'
import { describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import SegmentsPage from '@/app/segments/page'
import { SessionProvider } from 'next-auth/react'

describe('audience library', () => {
  it('uses the real segment preview and carries its version into campaign authoring', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('/preview')) return { ok: true, json: async () => ({ state: 'ok', size: 1240, asOf: '2026-08-13T10:00:00Z' }) }
      return { ok: true, json: async () => ({ state: 'ok', items: [{ name: 'actives', version: 1, rules: ['party status is ACTIVE'] }] }) }
    }))

    const session = { user: { roles: ['ROLE_OPERATOR'] }, expires: '2099-01-01' }
    const { container } = render(React.createElement(SessionProvider, { session }, React.createElement(LanguageProvider, null, React.createElement(SegmentsPage))))
    await waitFor(() => expect(screen.getByText('Active customers')).toBeTruthy())

    const start = container.querySelector('[data-use-audience="actives@1"]') as HTMLAnchorElement
    expect(start.href).toContain('/campaigns/new?audience=actives%401')
    fireEvent.click(container.querySelector('[data-audience-count="actives@1"]')!)
    await waitFor(() => expect(container.querySelector('[data-audience-size="actives@1"]')?.textContent).toContain('1,240'))
  })

  it('serializes lifecycle actions and makes progress visible on the active audience', async () => {
    let completeMutation: ((value: { ok: boolean; json: () => Promise<object> }) => void) | undefined
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.endsWith('/submit')) {
        return new Promise(resolve => { completeMutation = resolve })
      }
      return Promise.resolve({ ok: true, json: async () => ({
        state: 'ok',
        items: [
          { name: 'actives', version: 1, rules: ['party status is ACTIVE'], state: 'DRAFT' },
          { name: 'tenured', version: 1, rules: ['tenure >= 30 days'], state: 'PENDING_APPROVAL' },
        ],
      }) })
    }))

    const session = { user: { roles: ['ROLE_OPERATOR'] }, expires: '2099-01-01' }
    render(React.createElement(SessionProvider, { session }, React.createElement(LanguageProvider, null, React.createElement(SegmentsPage))))
    const submit = await screen.findByRole('button', { name: 'Submit for approval' })
    const approve = screen.getByRole('button', { name: 'Review and approve' })
    await act(async () => {
      // Native activations share one React batch. This is the pre-render race that
      // `disabled` cannot stop; only the hook's synchronous ref claim can.
      submit.click()
      submit.click()
    })

    expect(await screen.findByRole('button', { name: 'Submitting…' })).toHaveProperty('disabled', true)
    expect(approve).toHaveProperty('disabled', true)
    expect((globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls.filter(([url]) => String(url).endsWith('/submit'))).toHaveLength(1)

    completeMutation?.({ ok: true, json: async () => ({}) })
    await waitFor(() => expect(screen.getByRole('button', { name: 'Submit for approval' })).toBeTruthy())
  })

  it('keeps the exact audience approval review open after failure and permits a safe retry', async () => {
    let decisions = 0
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.endsWith('/approve')) {
        decisions += 1
        return decisions === 1
          ? { ok: false, json: async () => ({ error: 'temporarily unavailable' }) }
          : { ok: true, json: async () => ({}) }
      }
      return { ok: true, json: async () => ({
        state: 'ok',
        items: decisions > 1 ? [] : [{ name: 'tenured', version: 3, rules: ['tenure >= 30 days'], state: 'PENDING_APPROVAL', createdBy: 'maker.operator' }],
      }) }
    }))

    const session = { user: { roles: ['ROLE_OPERATOR'] }, expires: '2099-01-01' }
    render(React.createElement(SessionProvider, { session }, React.createElement(LanguageProvider, null, React.createElement(SegmentsPage))))
    fireEvent.click(await screen.findByRole('button', { name: 'Review and approve' }))

    const dialog = screen.getByRole('alertdialog')
    expect(dialog).toHaveTextContent('tenured · v3')
    expect(dialog).toHaveTextContent('maker.operator')
    expect(dialog).toHaveTextContent('tenure >= 30 days')
    fireEvent.click(screen.getByRole('button', { name: 'Confirm approval' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('state change did not complete')
    expect(screen.getByRole('alertdialog')).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Confirm approval' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    expect(decisions).toBe(2)
  })

  it('moves initial focus to the safe Back action, never the destructive confirm', async () => {
    const user = userEvent.setup()
    vi.stubGlobal('fetch', vi.fn(async () => ({
      ok: true,
      json: async () => ({
        state: 'ok',
        items: [{ name: 'tenured', version: 3, rules: ['tenure >= 30 days'], state: 'PENDING_APPROVAL', createdBy: 'maker.operator' }],
      }),
    })))

    const session = { user: { roles: ['ROLE_OPERATOR'] }, expires: '2099-01-01' }
    render(React.createElement(SessionProvider, { session }, React.createElement(LanguageProvider, null, React.createElement(SegmentsPage))))
    await user.click(await screen.findByRole('button', { name: 'Review and approve' }))

    expect(screen.getByRole('alertdialog')).toBeTruthy()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Back to review' })).toHaveFocus())
  })

  it('closes on Escape only while idle and restores focus to the exact trigger', async () => {
    const user = userEvent.setup()
    let resolveApprove: ((value: { ok: boolean; json: () => Promise<object> }) => void) | undefined
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.endsWith('/approve')) return new Promise(resolve => { resolveApprove = resolve })
      return Promise.resolve({
        ok: true,
        json: async () => ({
          state: 'ok',
          items: [{ name: 'tenured', version: 3, rules: ['tenure >= 30 days'], state: 'PENDING_APPROVAL', createdBy: 'maker.operator' }],
        }),
      })
    }))

    const session = { user: { roles: ['ROLE_OPERATOR'] }, expires: '2099-01-01' }
    render(React.createElement(SessionProvider, { session }, React.createElement(LanguageProvider, null, React.createElement(SegmentsPage))))
    const trigger = await screen.findByRole('button', { name: 'Review and approve' })
    await user.click(trigger)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Back to review' })).toHaveFocus())

    // Idle: Escape dismisses and returns focus to the exact control that opened the review.
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('alertdialog')).toBeNull()
    await waitFor(() => expect(trigger).toHaveFocus())

    // Re-open, then start an in-flight approval: Escape must not discard it mid-flight.
    await user.click(trigger)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Back to review' })).toHaveFocus())
    await user.click(screen.getByRole('button', { name: 'Confirm approval' }))
    await waitFor(() => expect(resolveApprove).toBeTruthy())
    await user.keyboard('{Escape}')
    expect(screen.getByRole('alertdialog')).toBeTruthy()

    act(() => resolveApprove?.({ ok: true, json: async () => ({}) }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
  })

  it('restores focus to the audience catalogue when a successful approval removes the trigger', async () => {
    // The approved audience stays in the catalogue (its state flips to APPROVED, replacing the
    // "Review and approve" trigger with a "Use in campaign" link) — the catalogue landmark itself
    // does not unmount, which is what makes it a stable focus target.
    const user = userEvent.setup()
    let decisions = 0
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.endsWith('/approve')) {
        decisions += 1
        return { ok: true, json: async () => ({}) }
      }
      return {
        ok: true,
        json: async () => ({
          state: 'ok',
          items: [{
            name: 'tenured', version: 3, rules: ['tenure >= 30 days'], createdBy: 'maker.operator',
            state: decisions > 0 ? 'APPROVED' : 'PENDING_APPROVAL',
          }],
        }),
      }
    }))

    const session = { user: { roles: ['ROLE_OPERATOR'] }, expires: '2099-01-01' }
    render(React.createElement(SessionProvider, { session }, React.createElement(LanguageProvider, null, React.createElement(SegmentsPage))))
    const trigger = await screen.findByRole('button', { name: 'Review and approve' })
    await user.click(trigger)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Back to review' })).toHaveFocus())
    await user.click(screen.getByRole('button', { name: 'Confirm approval' }))

    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    expect(trigger).not.toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('region', { name: 'Audience workspace' })).toHaveFocus())
  })

  it('reports lifecycle mutation errors locally without replacing the loaded catalogue', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.endsWith('/submit')) return { ok: false, json: async () => ({ error: 'four-eyes required' }) }
      return { ok: true, json: async () => ({ state: 'ok', items: [{ name: 'actives', version: 1, rules: ['party status is ACTIVE'], state: 'DRAFT' }] }) }
    }))

    const session = { user: { roles: ['ROLE_OPERATOR'] }, expires: '2099-01-01' }
    render(React.createElement(SessionProvider, { session }, React.createElement(LanguageProvider, null, React.createElement(SegmentsPage))))
    fireEvent.click(await screen.findByRole('button', { name: 'Submit for approval' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('catalogue is still available')
    expect(screen.getByText('Active customers')).toBeTruthy()
    expect(screen.queryByText('Campaign-service is not responding')).toBeNull()
  })
})
