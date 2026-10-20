// SPDX-License-Identifier: Apache-2.0
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import ClusterDossierPage from '@/app/docs/cluster/page'

interface PendingFetch {
  signal: AbortSignal
  resolve: (response: Response) => void
}

function topology(layerId: string) {
  return {
    schema: 'openbank.cluster-topology/v1', source: 'repo declarations', generatedAt: null,
    counts: { namespaces: 0, networkPolicies: 0, externalSecrets: 0, clusterPolicies: 0 },
    groups: [], namespaces: [],
    securityLayers: [layerId, 'selected-layer'].map(id => ({ id, label: id, icon: 'shield', status: 'partial', analogy: '', summary: '', controls: [], adr: [] })),
    imageAnatomy: { steps: [] }, planVsReality: [],
    contentSource: { source: 'published', status: 'ready', sourceSha: 'a'.repeat(40), sourceCollectedAt: '2026-10-20T10:00:00.000Z', imageBuiltAt: null, fetchedAt: '2026-10-20T10:01:00.000Z' },
  }
}

function deferredFetches() {
  const pending: PendingFetch[] = []
  vi.stubGlobal('fetch', vi.fn((_input: RequestInfo | URL, init?: RequestInit) => new Promise<Response>(resolve => {
    pending.push({ signal: init?.signal as AbortSignal, resolve })
  })))
  return pending
}

function mount() {
  return render(<LanguageProvider initialLanguage="en"><ClusterDossierPage /></LanguageProvider>)
}

afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks() })
beforeEach(() => { localStorage.clear() })

describe('cluster topology refresh lifecycle', () => {
  it('ignores an older response after a newer refresh completes', async () => {
    const pending = deferredFetches()
    mount()
    await waitFor(() => expect(pending).toHaveLength(1))

    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
    await act(async () => { document.dispatchEvent(new Event('visibilitychange')) })
    await waitFor(() => expect(pending).toHaveLength(2))
    expect(pending[0].signal.aborted).toBe(true)
    await act(async () => { pending[1].resolve(new Response(JSON.stringify(topology('new-layer')))) })
    expect(await screen.findByText('new-layer')).toBeInTheDocument()
    fireEvent.click(screen.getByText('selected-layer').closest('[role="button"]')!)
    await act(async () => { pending[0].resolve(new Response(JSON.stringify(topology('old-layer')))) })

    expect(screen.getByText('new-layer')).toBeInTheDocument()
    expect(screen.queryByText('old-layer')).not.toBeInTheDocument()
  })

  it('refreshes on the five-minute visible poll and aborts work on unmount', async () => {
    const pending = deferredFetches()
    let poll: (() => void) | undefined
    const originalSetInterval = window.setInterval.bind(window)
    vi.spyOn(window, 'setInterval').mockImplementation(((handler: TimerHandler, timeout?: number, ...args: unknown[]) => {
      if (timeout === 5 * 60_000 && typeof handler === 'function') poll = handler as () => void
      return originalSetInterval(handler, timeout, ...args)
    }) as typeof window.setInterval)

    const view = mount()
    await waitFor(() => expect(pending).toHaveLength(1))
    await act(async () => { pending[0].resolve(new Response(JSON.stringify(topology('first-layer')))) })
    expect(await screen.findByText('first-layer')).toBeInTheDocument()
    fireEvent.click(screen.getByText('selected-layer').closest('[role="button"]')!)

    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' })
    await act(async () => { poll?.() })
    expect(pending).toHaveLength(1)

    Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'visible' })
    await act(async () => { poll?.() })
    await waitFor(() => expect(pending).toHaveLength(2))
    await act(async () => { pending[1].resolve(new Response(JSON.stringify(topology('second-layer')))) })
    expect(await screen.findByText('second-layer')).toBeInTheDocument()
    expect(screen.getByText('selected-layer').closest('.card')?.style.boxShadow).toContain('var(--accent)')

    await act(async () => { poll?.() })
    await waitFor(() => expect(pending).toHaveLength(3))
    view.unmount()
    expect(pending[2].signal.aborted).toBe(true)
    await act(async () => { pending[2].resolve(new Response(JSON.stringify(topology('after-unmount')))) })
  })
})
