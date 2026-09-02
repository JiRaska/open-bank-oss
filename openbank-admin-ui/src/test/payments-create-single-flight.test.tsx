// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// The money path, driven the way an operator drives it, asserting the two things
// that actually decide whether a duplicate payment is booked:
//
//   1. how many POSTs left the browser  (a COUNT, not a disabled attribute), and
//   2. what Idempotency-Key each one carried  (the KEY, not "it succeeded").
//
// Both were red against main before this suite's fix: the page minted
// `crypto.randomUUID()` INSIDE each submit handler, so two same-tick submits sent
// two distinct keys — two payments — and a retry after a failure sent a third key,
// defeating the server-side idempotency the payment services genuinely enforce
// (Redis IdempotencyStore + a UNIQUE idempotency_key column + a pre-insert lookup).

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import React from 'react'
import { render, screen, cleanup, fireEvent, waitFor, act } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), back: vi.fn(), prefetch: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
  usePathname: () => '/payments',
}))

vi.mock('next-auth/react', () => ({
  useSession: () => ({
    data: { user: { name: 'Operator', roles: ['ROLE_PAYMENT_OFFICER', 'ROLE_OPERATOR', 'ROLE_ADMIN'] } },
    status: 'authenticated',
  }),
  signIn: vi.fn(),
  SessionProvider: ({ children }: { children: React.ReactNode }) => React.createElement(React.Fragment, null, children),
}))

import PaymentsPage from '@/app/payments/page'

interface Posted { url: string; key: string | null; body: string }
let posted: Posted[] = []
/** Resolver for the pending POST, so a submit can be held open across a second activation. */
let releasePost: ((r: Response) => void) | null = null
let postMode: 'hold' | 'fail' | 'ok' = 'ok'

function headerOf(init: RequestInit | undefined, name: string): string | null {
  const h = init?.headers
  if (!h) return null
  if (h instanceof Headers) return h.get(name)
  const rec = h as Record<string, string>
  const hit = Object.keys(rec).find(k => k.toLowerCase() === name.toLowerCase())
  return hit ? rec[hit] : null
}

function installFetch() {
  const f = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (init?.method === 'POST') {
      posted.push({ url, key: headerOf(init, 'Idempotency-Key'), body: String(init.body ?? '') })
      if (postMode === 'fail') return new Response('upstream unavailable', { status: 503 })
      if (postMode === 'hold') return new Promise<Response>(res => { releasePost = res })
      return new Response(JSON.stringify({ id: 'pay-1' }), { status: 201, headers: { 'content-type': 'application/json' } })
    }
    // Every GET the page makes on mount: an empty, well-formed list.
    return new Response(JSON.stringify({ data: [], content: [], pagination: {} }), {
      status: 200, headers: { 'content-type': 'application/json' },
    })
  })
  globalThis.fetch = f as unknown as typeof fetch
  return f
}

function renderPage() {
  return render(React.createElement(LanguageProvider, null, React.createElement(PaymentsPage)))
}

/** Opens the SEPA (non-instant) create form and fills every required field. */
async function openSepaForm(amount = '100.00') {
  fireEvent.click(await screen.findByLabelText('New Payment'))
  fireEvent.click(await screen.findByText('SEPA Credit Transfer'))
  const set = (id: string, value: string) => {
    const el = document.getElementById(id) as HTMLInputElement
    expect(el, `missing #${id}`).toBeTruthy()
    fireEvent.change(el, { target: { value } })
  }
  set('sepa-debtor-iban', 'CZ6508000000192000145399')
  set('sepa-creditor-iban', 'DE89370400440532013000')
  set('sepa-creditor-name', 'Jane Doe')
  set('sepa-amount', amount)
  const form = (document.getElementById('sepa-debtor-iban') as HTMLElement).closest('form')
  expect(form).toBeTruthy()
  return form as HTMLFormElement
}

beforeEach(() => { posted = []; releasePost = null; postMode = 'ok'; installFetch() })
afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('payment creation — single-flight (issues #7093, #7172)', () => {
  it('two submits in the SAME event-loop turn produce exactly ONE POST', async () => {
    postMode = 'hold'
    renderPage()
    const form = await openSepaForm()

    fireEvent.submit(form)
    expect(posted).toHaveLength(0)
    const confirm = await screen.findByRole('button', { name: 'Confirm and submit' })

    // Both confirmations before React can re-render `disabled={creating}` — the
    // exact window a state-only guard cannot cover.
    await act(async () => {
      fireEvent.click(confirm)
      fireEvent.click(confirm)
    })

    expect(posted.filter(p => p.url.includes('/api/sepa-payments'))).toHaveLength(1)

    await act(async () => {
      releasePost?.(new Response(JSON.stringify({ id: 'pay-1' }), { status: 201 }))
      await Promise.resolve()
    })
  })

  it('a retry after a FAILED attempt reuses the SAME Idempotency-Key', async () => {
    postMode = 'fail'
    renderPage()
    const form = await openSepaForm()

    fireEvent.submit(form)
    const confirm = await screen.findByRole('button', { name: 'Confirm and submit' })
    await act(async () => { fireEvent.click(confirm) })
    await waitFor(() => expect(posted).toHaveLength(1))
    expect(screen.getByTestId('payment-create-review-error')).toHaveTextContent('upstream unavailable')
    await act(async () => { fireEvent.click(confirm) })
    await waitFor(() => expect(posted).toHaveLength(2))

    expect(posted[0].key).toBeTruthy()
    expect(posted[0].body).toBe(posted[1].body)
    // The load-bearing assertion: the KEY, not that the retry happened.
    expect(posted[1].key).toBe(posted[0].key)
  })

  it('EDITING the payload before retrying mints a NEW key — a different intent', async () => {
    postMode = 'fail'
    renderPage()
    const form = await openSepaForm('100.00')

    fireEvent.submit(form)
    await act(async () => { fireEvent.click(await screen.findByRole('button', { name: 'Confirm and submit' })) })
    await waitFor(() => expect(posted).toHaveLength(1))

    fireEvent.click(screen.getByRole('button', { name: 'Back to editing' }))
    fireEvent.change(document.getElementById('sepa-amount') as HTMLInputElement, { target: { value: '250.00' } })
    fireEvent.submit(form)
    await act(async () => { fireEvent.click(await screen.findByRole('button', { name: 'Confirm and submit' })) })
    await waitFor(() => expect(posted).toHaveLength(2))

    expect(posted[1].body).not.toBe(posted[0].body)
    expect(posted[1].key).not.toBe(posted[0].key)
  })

  it('the healthy path still works: one submit, one POST, a well-formed key and payload', async () => {
    renderPage()
    const form = await openSepaForm()
    fireEvent.submit(form)

    expect(posted).toHaveLength(0)
    const dialog = await screen.findByRole('alertdialog', { name: 'Review payment order' })
    expect(dialog).toHaveTextContent('100.00 EUR')
    expect(dialog).toHaveTextContent('Jane Doe')
    expect(dialog).toHaveTextContent('DE89370400440532013000')

    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Confirm and submit' })) })
    await waitFor(() => expect(posted).toHaveLength(1))

    expect(posted[0].url).toContain('/api/sepa-payments')
    expect(posted[0].key).toMatch(/^[0-9a-f-]{8,}/)
    const body = JSON.parse(posted[0].body)
    expect(body).toMatchObject({ creditorIban: 'DE89370400440532013000', creditorName: 'Jane Doe', amount: 100, currency: 'EUR' })
  })
})
