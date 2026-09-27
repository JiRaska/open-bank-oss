// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { SessionProvider } from 'next-auth/react'
import DocumentTemplatesPage from '@/app/document-templates/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

function Providers({ children }: { children: React.ReactNode }) {
  return <SessionProvider session={{ user: { roles: ['ROLE_ADMIN'], email: 'admin@openbank.local' } } as never}>
    <LanguageProvider>{children}</LanguageProvider>
  </SessionProvider>
}

function stubTemplates(create?: Promise<Response>) {
  return vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input)
    if (url.includes('/preview')) return new Response(JSON.stringify({ renderedHtml: '<p>Preview</p>' }), { status: 200 })
    if (init?.method === 'POST') return create ?? new Response(JSON.stringify({ id: 'new-template' }), { status: 201 })
    return new Response(JSON.stringify([]), { status: 200 })
  })
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('document template editor dialog', () => {
  // Measured standalone (no other suite load) on 2026-09-27: 5595ms / 4841ms —
  // already within ~600ms of the 5000ms default testTimeout with nothing else
  // running. Each test mounts the full templates page (list + modal) and drives
  // several multi-character `user.type()` calls, each keystroke a full React
  // re-render of that tree plus the page's 250ms preview-debounce effect
  // resetting on real timers — genuinely heavy interaction, not a bug to fix,
  // and any full-suite CPU contention pushes it over. Scope the timeout with
  // headroom rather than raising the file/global default.
  it('names the editor, focuses its first field and protects an unsaved draft before restoring the trigger', async () => {
    vi.stubGlobal('fetch', stubTemplates())
    const user = userEvent.setup()
    render(<Providers><DocumentTemplatesPage /></Providers>)
    const trigger = await screen.findByRole('button', { name: 'New Template' })

    await user.click(trigger)
    const editor = screen.getByRole('dialog', { name: 'New Template' })
    expect(editor).toHaveAccessibleDescription('Edit metadata, HTML content and sample data. The preview refreshes automatically.')
    await waitFor(() => expect(screen.getByLabelText('Code *')).toHaveFocus())

    fireEvent.keyDown(editor, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    await waitFor(() => expect(trigger).toHaveFocus())
    await user.click(trigger)
    const reopenedEditor = screen.getByRole('dialog', { name: 'New Template' })
    await user.type(screen.getByLabelText('Name *'), 'Customer agreement')
    await user.click(within(reopenedEditor).getByRole('button', { name: 'Close' }))

    const discard = screen.getByRole('alertdialog', { name: 'Discard unsaved changes?' })
    expect(discard).toHaveTextContent('cannot be recovered')
    expect(screen.getByRole('button', { name: 'Keep editing' })).toHaveFocus()
    fireEvent.keyDown(discard, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument())
    expect(screen.getByRole('dialog', { name: 'New Template' })).toBeInTheDocument()

    await user.click(within(reopenedEditor).getByRole('button', { name: 'Close' }))
    await user.click(screen.getByRole('button', { name: 'Discard changes' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    await waitFor(() => expect(trigger).toHaveFocus())
  }, 15000)

  it('cannot dismiss while a save is in flight', async () => {
    const save = deferred<Response>()
    vi.stubGlobal('fetch', stubTemplates(save.promise))
    const user = userEvent.setup()
    render(<Providers><DocumentTemplatesPage /></Providers>)

    await user.click(await screen.findByRole('button', { name: 'New Template' }))
    await user.type(screen.getByLabelText('Code *'), 'CUSTOMER_AGREEMENT')
    await user.type(screen.getByLabelText('Name *'), 'Customer agreement')
    await user.type(screen.getByLabelText('Template body (HTML)'), '<p>Hello</p>')
    await user.click(screen.getByRole('button', { name: 'Save Template' }))
    const editor = screen.getByRole('dialog', { name: 'New Template' })
    await waitFor(() => expect(editor).toHaveAttribute('aria-busy', 'true'))

    fireEvent.keyDown(editor, { key: 'Escape' })
    expect(screen.getByRole('dialog', { name: 'New Template' })).toBeInTheDocument()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()

    save.resolve(new Response(JSON.stringify({ id: 'new-template' }), { status: 201 }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  }, 15000)
})
