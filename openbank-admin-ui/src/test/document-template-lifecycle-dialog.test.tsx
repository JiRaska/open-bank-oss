// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SessionProvider } from 'next-auth/react'
import DocumentTemplatesPage from '@/app/document-templates/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const TEMPLATE = {
  id: 'template-42', code: 'LOAN_AGREEMENT', version: '1.0.0', name: 'Loan agreement',
  engine: 'HANDLEBARS', bodyHtml: '<p>Hello</p>', locale: 'en', status: 'DRAFT',
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(res => { resolve = res })
  return { promise, resolve }
}

function Providers({ children }: { children: React.ReactNode }) {
  return (
    <SessionProvider session={{ user: { roles: ['ROLE_ADMIN'], email: 'admin@openbank.local' } } as never}>
      <LanguageProvider>{children}</LanguageProvider>
    </SessionProvider>
  )
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('document template lifecycle dialog', () => {
  it('is named, traps dismissal in a modal, and restores focus to its trigger', async () => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).includes('/preview')) {
        return new Response(JSON.stringify({ renderedHtml: '' }), { status: 200, headers: { 'content-type': 'application/json' } })
      }
      return new Response(JSON.stringify([TEMPLATE]), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      })
    }))
    render(<Providers><DocumentTemplatesPage /></Providers>)

    const trigger = await screen.findByRole('button', { name: 'Publish template' })
    trigger.focus()
    fireEvent.click(trigger)

    const dialog = screen.getByRole('dialog', { name: 'Publish this template?' })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(screen.getByText('A published version is immutable — a further edit creates a new version.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus()

    fireEvent.keyDown(dialog, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    await waitFor(() => expect(trigger).toHaveFocus())
  })

  it('cannot dismiss an in-flight transition and focuses a stable row action after success', async () => {
    const publish = deferred<Response>()
    let calls = 0
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).includes('/preview')) {
        return new Response(JSON.stringify({ renderedHtml: '' }), { status: 200, headers: { 'content-type': 'application/json' } })
      }
      calls += 1
      if (init?.method === 'POST') return publish.promise
      const status = calls >= 3 ? 'PUBLISHED' : 'DRAFT'
      return new Response(JSON.stringify([{ ...TEMPLATE, status }]), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      })
    }))
    render(<Providers><DocumentTemplatesPage /></Providers>)

    fireEvent.change(await screen.findByLabelText('Status:'), { target: { value: 'DRAFT' } })
    fireEvent.click(await screen.findByRole('button', { name: 'Publish template' }))
    const dialog = screen.getByRole('dialog', { name: 'Publish this template?' })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(dialog).toHaveAttribute('aria-busy', 'true'))

    fireEvent.keyDown(dialog, { key: 'Escape' })
    expect(screen.getByRole('dialog')).toBeInTheDocument()

    publish.resolve(new Response('', { status: 200 }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(screen.queryByText('PUBLISHED', { exact: true })).not.toBeInTheDocument()
    await waitFor(() => expect(screen.getByLabelText('Status:')).toHaveFocus())
  })

  it('announces a rejected transition inside the dialog and permits an explicit retry', async () => {
    let publishAttempts = 0
    let published = false
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).includes('/preview')) {
        return new Response(JSON.stringify({ renderedHtml: '' }), { status: 200, headers: { 'content-type': 'application/json' } })
      }
      if (init?.method === 'POST') {
        publishAttempts += 1
        if (publishAttempts === 1) {
          return new Response(JSON.stringify({ error: 'Document policy rejected this transition' }), {
            status: 503,
            headers: { 'content-type': 'application/json' },
          })
        }
        published = true
        return new Response('', { status: 200 })
      }
      return new Response(JSON.stringify([{ ...TEMPLATE, status: published ? 'PUBLISHED' : 'DRAFT' }]), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      })
    }))
    render(<Providers><DocumentTemplatesPage /></Providers>)

    fireEvent.click(await screen.findByRole('button', { name: 'Publish template' }))
    const dialog = screen.getByRole('dialog', { name: 'Publish this template?' })
    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))

    const alert = await screen.findByRole('alert')
    expect(dialog).toContainElement(alert)
    expect(alert).toHaveTextContent('Document policy rejected this transition')
    expect(screen.getByRole('dialog')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(publishAttempts).toBe(2)
    expect(screen.getByText('PUBLISHED', { exact: true })).toBeInTheDocument()
  })
})
