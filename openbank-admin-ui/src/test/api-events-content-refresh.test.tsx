// SPDX-License-Identifier: Apache-2.0
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import ApiCatalogPage from '@/app/docs/api/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

afterEach(() => { cleanup(); vi.unstubAllGlobals() })
it.each(['network', 'http503', 'unavailable', 'malformed', 'bad-topic', 'bad-source'])('retains original rows and provenance visibly stale after %s refresh failure', async failureMode => {
  let revision = 'first'
  let failure = false
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    if (String(input) === '/api/events') {
      if (failure) {
        if (failureMode === 'network') throw new Error('offline')
        if (failureMode === 'http503') return new Response(null, { status: 503 })
        const source = { source: 'published', status: 'ready', sourceSha: 'unrelated-new', sourceCollectedAt: '2026-10-22T11:00:00.000Z', fetchedAt: '2026-10-22T11:01:00.000Z' }
        return new Response(JSON.stringify(failureMode === 'malformed' ? null
          : failureMode === 'bad-topic' ? { available: true, topics: [{ topic: 'broken' }], contentSource: source }
          : failureMode === 'bad-source' ? { available: true, topics: [], contentSource: { ...source, sourceSha: {} } }
          : { available: false, topics: [], contentSource: source }))
      }
      return new Response(JSON.stringify({ available: true, topics: [{ channel: revision, topic: `${revision}.topic`, description: null, publishers: [], consumers: [], color: '#abcdef' }],
        contentSource: { source: 'published', status: 'ready', sourceSha: revision, sourceCollectedAt: '2026-10-22T10:00:00.000Z', fetchedAt: '2026-10-22T10:01:00.000Z' } }))
    }
    return new Response(JSON.stringify({ available: false, services: [] }))
  }))
  render(<LanguageProvider><ApiCatalogPage /></LanguageProvider>)
  fireEvent.click(screen.getByRole('button', { name: /AsyncAPI/ }))
  expect(await screen.findByText('first.topic')).toBeVisible()
  await waitFor(() => expect(screen.getByRole('button', { name: 'Refresh' })).toBeEnabled())
  revision = 'second'
  fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
  expect(await screen.findByText('second.topic')).toBeVisible()
  expect(screen.queryByText('first.topic')).not.toBeInTheDocument()
  await waitFor(() => expect(screen.getByRole('button', { name: 'Refresh' })).toBeEnabled())
  failure = true
  fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
  await waitFor(() => expect(screen.getByText(/Reviewed source second/)).toHaveTextContent('stale'))
  expect(screen.getByText('second.topic')).toBeVisible()
  expect(screen.getByText(/Reviewed source second/)).toHaveTextContent('2026-10-22T10:00:00.000Z')
  expect(screen.queryByText(/Reviewed source unrelated-new/)).not.toBeInTheDocument()
})
