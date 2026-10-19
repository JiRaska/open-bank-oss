// SPDX-License-Identifier: Apache-2.0
import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import CloudArchitecturePage from '@/app/docs/cloud-architecture/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
vi.mock('@/lib/use-platform-view', () => ({ usePlatformView: () => null }))
afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('architecture observation recovery', () => {
  it('does not retain a green probe after the source fails and recovers on refresh', async () => {
    let fail = false
    vi.stubGlobal('fetch', vi.fn(async () => fail
      ? new Response('{}', { status: 503 })
      : Response.json({ kafka: { id: 'kafka', status: 'UP', checkedAt: new Date().toISOString(), latencyMs: 2 } })))
    render(<LanguageProvider><CloudArchitecturePage /></LanguageProvider>)
    expect(await screen.findByRole('button', { name: /Strimzi \/ Kafka UP — Probe reachable/ })).toBeInTheDocument()
    fail = true
    fireEvent.click(screen.getByRole('button', { name: 'Refresh infrastructure status' }))
    await waitFor(() => expect(screen.getByRole('button', { name: /Strimzi \/ Kafka — Declared/ })).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /Strimzi \/ Kafka UP/ })).not.toBeInTheDocument()
    fail = false
    fireEvent.click(screen.getByRole('button', { name: 'Refresh infrastructure status' }))
    expect(await screen.findByRole('button', { name: /Strimzi \/ Kafka UP — Probe reachable/ })).toBeInTheDocument()
  })
})
