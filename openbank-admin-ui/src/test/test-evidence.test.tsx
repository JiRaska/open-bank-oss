// SPDX-License-Identifier: Apache-2.0
import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { buildEvidenceMeta, fetchLiveJson, STALE_AFTER_MS } from '@/lib/test-evidence'
import { EvidenceFreshness, formatAge } from '@/components/testing/EvidenceFreshness'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

const NOW = Date.parse('2026-10-17T12:00:00Z')
const inv = { collectedAt: '2026-10-17T00:00:00Z', kotlin: { unit: 10, integration: 5 }, adminUi: 3, e2e: 2, totalFiles: 20 }

describe('buildEvidenceMeta', () => {
  it('is fresh within 24h', () => {
    const m = buildEvidenceMeta('live', '2026-10-17T10:00:00Z', inv, NOW)
    expect(m.stale).toBe(false)
    expect(m.ageSeconds).toBe(7200)
  })
  it('is stale after 24h', () => {
    expect(buildEvidenceMeta('bundled', new Date(NOW - STALE_AFTER_MS - 1000).toISOString(), null, NOW).stale).toBe(true)
  })
  it('treats epoch-0 and garbage as unknown, never as a date', () => {
    for (const bad of ['1970-01-01T00:00:00.000Z', 'nope', null]) {
      const m = buildEvidenceMeta('bundled', bad, null, NOW)
      expect(m.asOf).toBeNull(); expect(m.stale).toBe(true); expect(m.ageSeconds).toBeNull()
    }
  })
})

describe('fetchLiveJson', () => {
  afterEach(() => { vi.unstubAllGlobals(); delete process.env.T_URL; delete process.env.T_TOK })
  it('returns null when unconfigured', async () => { expect(await fetchLiveJson('T_URL', 'T_TOK')).toBeNull() })
  it('rejects non-https', async () => {
    process.env.T_URL = 'http://x/y.json'
    expect(await fetchLiveJson('T_URL', 'T_TOK')).toEqual({ error: 'live URL must be https' })
  })
  it('sends the server-side token and returns data', async () => {
    process.env.T_URL = 'https://x/y.json'; process.env.T_TOK = 'sekret'
    const f = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ a: 1 }) })
    vi.stubGlobal('fetch', f)
    expect(await fetchLiveJson('T_URL', 'T_TOK')).toEqual({ data: { a: 1 } })
    expect(f.mock.calls[0][1].headers.Authorization).toBe('Bearer sekret')
  })
  it('degrades to an error value on HTTP failure or network throw', async () => {
    process.env.T_URL = 'https://x/y.json'
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 503 }))
    expect(await fetchLiveJson('T_URL', 'T_TOK')).toEqual({ error: 'live source HTTP 503' })
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('boom')))
    expect(await fetchLiveJson('T_URL', 'T_TOK')).toEqual({ error: 'boom' })
  })
})

describe('EvidenceFreshness', () => {
  const wrap = (ui: React.ReactElement) => render(<LanguageProvider>{ui}</LanguageProvider>)
  it('renders nothing without evidence', () => {
    const { container } = wrap(<EvidenceFreshness />)
    expect(container.textContent).toBe('')
  })
  it('shows source, inventory vs reported and a stale warning', () => {
    wrap(<EvidenceFreshness reportedCases={7} evidence={buildEvidenceMeta('bundled', '2026-01-01T00:00:00Z', inv, NOW, 'live source HTTP 503')} />)
    const el = screen.getByTestId('evidence-freshness')
    expect(el.textContent).toMatch(/20/)
    expect(el.textContent).toMatch(/7/)
    expect(screen.getByRole('alert')).toBeTruthy()
  })
  it('formats ages', () => {
    expect(formatAge(30, false)).toBe('just now')
    expect(formatAge(7200, false)).toBe('2 h ago')
    expect(formatAge(7200, true)).toBe('před 2 h')
  })
})
