// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import React from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import NotificationsPage from '@/app/notifications/page'

vi.mock('@/components/auth/AuthGuard', () => ({
  AuthGuard: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))

const authState = vi.hoisted(() => ({ roles: [] as string[] }))

vi.mock('@/lib/auth/useAuth', () => ({
  useAuth: () => ({ roles: authState.roles }),
}))

type NotificationPage = {
  items: Array<Record<string, unknown>>
  total: number
  page: number
  size: number
}

function item(index: number, overrides: Record<string, unknown> = {}) {
  return {
    id: `notification-${index}`,
    partyId: '10000000-0000-0000-0000-000000000001',
    channel: 'EMAIL',
    template: 'PAYMENT_COMPLETED',
    recipient: `recipient-${index}`,
    subject: `Message ${index}`,
    status: index <= 10 ? 'SENT' : index <= 15 ? 'FAILED' : 'PENDING',
    sentAt: index <= 10 ? '2026-09-01T10:00:00Z' : null,
    readAt: null,
    createdAt: '2026-09-01T09:00:00Z',
    ...overrides,
  }
}

function page(items: Array<Record<string, unknown>>, total: number, pageIndex: number): NotificationPage {
  return { items, total, page: pageIndex, size: 20 }
}

function response(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

function renderPage() {
  return render(
    React.createElement(LanguageProvider, null, React.createElement(NotificationsPage)),
  )
}

afterEach(() => {
  cleanup()
  authState.roles = []
  window.history.replaceState(null, '', '/notifications')
  vi.unstubAllGlobals()
})

describe('Notifications contract pagination', () => {
  it('renders the documented template and authoritative range, then requests the next page', async () => {
    const firstItems = Array.from({ length: 20 }, (_, index) => item(index + 1))
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(page(firstItems, 21, 0)))
      .mockResolvedValueOnce(response(page([item(21, { template: 'ACCOUNT_OPENED' })], 21, 1)))
    vi.stubGlobal('fetch', fetchMock)

    renderPage()

    await screen.findByText('Message 1')
    expect(String(fetchMock.mock.calls[0]?.[0])).toBe(
      '/api/svc/notification-service/api/v1/notifications?page=0&size=20',
    )
    expect(screen.getAllByText('PAYMENT_COMPLETED')).toHaveLength(20)
    expect(screen.getByText('Showing 1–20 of 21 notifications')).toBeInTheDocument()
    expect(screen.getByText('Sent on this page').parentElement).toHaveTextContent('10')
    expect(screen.getByRole('button', { name: 'Previous notifications page' })).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: 'Next notifications page' }))

    await screen.findByText('Message 21')
    expect(String(fetchMock.mock.calls[1]?.[0])).toBe(
      '/api/svc/notification-service/api/v1/notifications?page=1&size=20',
    )
    expect(screen.getByText('ACCOUNT_OPENED')).toBeInTheDocument()
    expect(screen.getByText('Showing 21–21 of 21 notifications')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Next notifications page' })).toBeDisabled()
  })

  it('resets an operator refresh to the first page', async () => {
    const firstItems = Array.from({ length: 20 }, (_, index) => item(index + 1))
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(page(firstItems, 21, 0)))
      .mockResolvedValueOnce(response(page([item(21)], 21, 1)))
      .mockResolvedValueOnce(response(page([item(1, { subject: 'Refreshed first message' })], 1, 0)))
    vi.stubGlobal('fetch', fetchMock)

    renderPage()
    await screen.findByText('Message 1')
    fireEvent.click(screen.getByRole('button', { name: 'Next notifications page' }))
    await screen.findByText('Message 21')

    fireEvent.click(screen.getByRole('button', { name: 'Refresh notifications' }))

    await screen.findByText('Refreshed first message')
    expect(String(fetchMock.mock.calls[2]?.[0])).toBe(
      '/api/svc/notification-service/api/v1/notifications?page=0&size=20',
    )
    expect(screen.getByText('Showing 1–1 of 1 notification')).toBeInTheDocument()
  })

  it('aborts and ignores a superseded next-page response', async () => {
    const firstItems = Array.from({ length: 20 }, (_, index) => item(index + 1))
    const lateSecondPage = deferred<Response>()
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(page(firstItems, 21, 0)))
      .mockReturnValueOnce(lateSecondPage.promise)
      .mockResolvedValueOnce(response(page([item(1, { subject: 'Current first page' })], 1, 0)))
    vi.stubGlobal('fetch', fetchMock)

    renderPage()
    await screen.findByText('Message 1')
    fireEvent.click(screen.getByRole('button', { name: 'Next notifications page' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    const supersededSignal = (fetchMock.mock.calls[1]?.[1] as RequestInit | undefined)?.signal

    fireEvent.click(screen.getByRole('button', { name: 'Previous notifications page' }))

    await screen.findByText('Current first page')
    expect(supersededSignal?.aborted).toBe(true)
    const staleBody = vi.fn().mockResolvedValue(page([item(21, { subject: 'Late stale page' })], 21, 1))
    await act(async () => {
      lateSecondPage.resolve({ ok: true, status: 200, json: staleBody } as Response)
      await lateSecondPage.promise
      await new Promise(resolve => window.setTimeout(resolve, 0))
    })
    expect(staleBody).not.toHaveBeenCalled()
    expect(screen.queryByText('Late stale page')).not.toBeInTheDocument()
    expect(screen.getByText('Current first page')).toBeInTheDocument()
  })

  it('ignores a superseded response whose JSON body resolves after navigation', async () => {
    const firstItems = Array.from({ length: 20 }, (_, index) => item(index + 1))
    const lateBody = deferred<NotificationPage>()
    const readLateBody = vi.fn(() => lateBody.promise)
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(page(firstItems, 21, 0)))
      .mockResolvedValueOnce({ ok: true, status: 200, json: readLateBody } as Response)
      .mockResolvedValueOnce(response(page([item(1, { subject: 'Body-race winner' })], 1, 0)))
    vi.stubGlobal('fetch', fetchMock)

    renderPage()
    await screen.findByText('Message 1')
    fireEvent.click(screen.getByRole('button', { name: 'Next notifications page' }))
    await waitFor(() => expect(readLateBody).toHaveBeenCalledTimes(1))

    fireEvent.click(screen.getByRole('button', { name: 'Previous notifications page' }))
    expect(await screen.findByText('Body-race winner')).toBeInTheDocument()

    await act(async () => {
      lateBody.resolve(page([item(21, { subject: 'Late parsed body' })], 21, 1))
      await lateBody.promise
      await new Promise(resolve => window.setTimeout(resolve, 0))
    })
    expect(screen.queryByText('Late parsed body')).not.toBeInTheDocument()
    expect(screen.getByText('Body-race winner')).toBeInTheDocument()
  })

  it('returns to the last valid page when the total shrinks', async () => {
    const firstItems = Array.from({ length: 20 }, (_, index) => item(index + 1))
    const shrunkenItems = Array.from({ length: 5 }, (_, index) => item(index + 1))
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(page(firstItems, 21, 0)))
      .mockResolvedValueOnce(response(page([], 5, 1)))
      .mockResolvedValueOnce(response(page(shrunkenItems, 5, 0)))
    vi.stubGlobal('fetch', fetchMock)

    renderPage()
    await screen.findByText('Message 1')
    fireEvent.click(screen.getByRole('button', { name: 'Next notifications page' }))

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    expect(String(fetchMock.mock.calls[2]?.[0])).toContain('page=0&size=20')
    expect(await screen.findByText('Showing 1–5 of 5 notifications')).toBeInTheDocument()
  })

  it('walks back from an empty in-range page produced by a concurrent total change', async () => {
    const firstItems = Array.from({ length: 20 }, (_, index) => item(index + 1))
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(page(firstItems, 21, 0)))
      .mockResolvedValueOnce(response(page([], 21, 1)))
      .mockResolvedValueOnce(response(page([item(1, { subject: 'Stable first page' })], 1, 0)))
    vi.stubGlobal('fetch', fetchMock)

    renderPage()
    await screen.findByText('Message 1')
    fireEvent.click(screen.getByRole('button', { name: 'Next notifications page' }))

    expect(await screen.findByText('Stable first page')).toBeInTheDocument()
    expect(String(fetchMock.mock.calls[2]?.[0])).toContain('page=0&size=20')
    expect(screen.getByText('Showing 1–1 of 1 notification')).toBeInTheDocument()
  })

  it('keeps an approval deep link while starting the notification log on page zero', async () => {
    authState.roles = ['ROLE_OPERATOR']
    window.history.replaceState(null, '', '/notifications?approvalId=approval%2Fone#notification-approval-id')
    const fetchMock = vi.fn().mockResolvedValueOnce(response(page([], 0, 0)))
    vi.stubGlobal('fetch', fetchMock)

    renderPage()

    expect(await screen.findByDisplayValue('approval/one')).toHaveAttribute('id', 'notification-approval-id')
    expect(String(fetchMock.mock.calls[0]?.[0])).toContain('page=0&size=20')
  })

  it('renders an expired-session state for a 401 response', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response({ error: 'unauthorized' }, 401)))

    renderPage()

    expect(await screen.findByText('Session expired')).toBeInTheDocument()
    expect(screen.queryByText('Access denied')).not.toBeInTheDocument()
    expect(screen.queryByText(/No data yet/)).not.toBeInTheDocument()
  })

  it('renders access denied without telling an authenticated operator to sign in again', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response({ error: 'forbidden' }, 403)))

    renderPage()

    expect(await screen.findByText('Access denied')).toBeInTheDocument()
    expect(screen.getByText(/You are signed in, but your role cannot read/)).toBeInTheDocument()
    expect(screen.queryByText('Session expired')).not.toBeInTheDocument()
    expect(screen.queryByText(/No data yet/)).not.toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: 'Notifications pagination' })).not.toBeInTheDocument()
  })

  it.each([
    { status: 401, title: 'Session expired' },
    { status: 403, title: 'Access denied' },
  ])('removes protected cached rows when a refresh is refused with $status', async ({ status, title }) => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(page([item(1, { subject: 'Protected subject' })], 1, 0)))
      .mockResolvedValueOnce(response({ error: 'refused' }, status))
    vi.stubGlobal('fetch', fetchMock)

    renderPage()
    expect(await screen.findByText('Protected subject')).toBeInTheDocument()
    expect(screen.getByText('recipient-1')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Refresh notifications' }))

    expect(await screen.findByText(title)).toBeInTheDocument()
    expect(screen.queryByText('Protected subject')).not.toBeInTheDocument()
    expect(screen.queryByText('recipient-1')).not.toBeInTheDocument()
    expect(screen.getByText('Sent on this page').parentElement).toHaveTextContent('—')
  })

  it('keeps the committed page but hides stale navigation after a page request fails', async () => {
    const firstItems = Array.from({ length: 20 }, (_, index) => item(index + 1))
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(page(firstItems, 21, 0)))
      .mockResolvedValueOnce(response({ error: 'failed' }, 500))
      .mockResolvedValueOnce(response(page([item(1, { subject: 'Recovered first page' })], 1, 0)))
    vi.stubGlobal('fetch', fetchMock)

    renderPage()
    await screen.findByText('Message 1')
    fireEvent.click(screen.getByRole('button', { name: 'Next notifications page' }))

    expect(await screen.findByText('Failed to load: Notifications')).toBeInTheDocument()
    expect(screen.getByText('Message 1')).toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: 'Notifications pagination' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Refresh notifications' }))
    expect(await screen.findByText('Recovered first page')).toBeInTheDocument()
    expect(String(fetchMock.mock.calls[2]?.[0])).toContain('page=0&size=20')
  })

  it('reports malformed JSON and rendered fields as response errors, not unreachable service', async () => {
    const malformedSubject = page([item(1, { subject: { unsafe: true } })], 1, 0)
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response('{', { status: 200 }))
      .mockResolvedValueOnce(response(malformedSubject))
    vi.stubGlobal('fetch', fetchMock)

    const view = renderPage()
    expect(await screen.findByText('Failed to load: Notifications')).toBeInTheDocument()
    expect(screen.queryByText('Notification-service is not responding')).not.toBeInTheDocument()

    view.unmount()
    renderPage()
    expect(await screen.findByText('Failed to load: Notifications')).toBeInTheDocument()
    expect(screen.queryByText('[object Object]')).not.toBeInTheDocument()
  })

  it('rejects a nonempty page whose authoritative total cannot contain its items', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(page([item(1)], 0, 0))))

    renderPage()

    expect(await screen.findByText('Failed to load: Notifications')).toBeInTheDocument()
    expect(screen.queryByText('Message 1')).not.toBeInTheDocument()
    expect(screen.queryByText(/Showing/)).not.toBeInTheDocument()
  })

  it('shows a genuine empty state only for a successful empty page', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(page([], 0, 0))))

    renderPage()

    expect(await screen.findByText('No data yet: Notifications')).toBeInTheDocument()
    expect(screen.getByText('Sent on this page').parentElement).toHaveTextContent('0')
    expect(screen.queryByRole('navigation', { name: 'Notifications pagination' })).not.toBeInTheDocument()
  })
})
