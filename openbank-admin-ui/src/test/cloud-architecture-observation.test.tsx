// SPDX-License-Identifier: Apache-2.0
import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { PlatformView } from '@/lib/platform-view'
import { platformVersions } from '@/lib/platform-versions'
import CloudArchitecturePage from '@/app/docs/cloud-architecture/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
const { view } = vi.hoisted(() => ({ view: { current: null as PlatformView | null } }))
vi.mock('@/lib/use-platform-view', () => ({ usePlatformView: () => view.current }))
afterEach(() => { view.current = null; cleanup(); vi.unstubAllGlobals() })

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


it('renders published inventory declarations and preserves explicit absent inventory', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => Response.json({})))
  const inventory = structuredClone(platformVersions!.inventory)
  inventory.iac.route53 = ['published-new-environment']
  view.current = {
    inventory, items: {}, liveAvailable: false, nodes: null, declaredNodeGroup: null,
    declarationSource: { source: 'published', status: 'ready', sourceSha: 'a'.repeat(40), sourceCollectedAt: '2026-10-22T10:00:00.000Z', imageBuiltAt: null, fetchedAt: '2026-10-22T10:01:00.000Z' },
  } as PlatformView
  const result = render(<LanguageProvider><CloudArchitecturePage /></LanguageProvider>)
  fireEvent.click(screen.getByRole('button', { name: /^Route 53/ }))
  expect(screen.getByText(/published-new-environment/)).toBeInTheDocument()
  expect(screen.getByTestId('content-source-note')).toHaveTextContent('a'.repeat(40))
  view.current = { ...view.current, inventory: null }
  result.rerender(<LanguageProvider><CloudArchitecturePage /></LanguageProvider>)
  expect(screen.queryByRole('button', { name: /^Route 53/ })).not.toBeInTheDocument()
})
