// SPDX-License-Identifier: Apache-2.0
import React from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import TechInventoryPage from '@/app/system/inventory/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { fetchAllServiceSnapshotsEvidence } from '@/lib/api'

vi.mock('@/lib/api', async importOriginal => ({
  ...await importOriginal<typeof import('@/lib/api')>(),
  fetchAllServiceSnapshotsEvidence: vi.fn(),
}))
vi.mock('@/components/sbom/SbomViewer', () => ({ SbomViewer: () => <div>SBOM</div> }))

const snapshot = {
  name: 'ledger-service', port: 8101, reachable: true, latencyMs: 12,
  health: { status: 'UP' as const, checks: [] }, rateLimitMax: null, rateLimitRemaining: null, apiVersion: null,
  info: {
    service: 'ledger-service', version: '1.0.0', apiVersion: null, buildTime: null, gitCommit: 'abcdef1',
    timestamp: null, status: 'UP', stack: { quarkus: { version: '3.27.0' } },
  },
}

function mount() {
  return render(<LanguageProvider><TechInventoryPage /></LanguageProvider>)
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
})

describe('technology inventory evidence UI', () => {
  it('renders collector failure without fabricated zero-count summaries', async () => {
    vi.mocked(fetchAllServiceSnapshotsEvidence).mockResolvedValue({ ok: false, failure: 'unreachable' })
    mount()

    expect(await screen.findByText(/Service inventory collector is not responding|Agregátor inventáře služeb neodpovídá/)).toBeInTheDocument()
    expect(screen.queryByText('0 / 0')).not.toBeInTheDocument()
  })

  it('preserves and labels the last verified snapshot when refresh fails', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ vulns: [] }), { status: 200 })))
    vi.mocked(fetchAllServiceSnapshotsEvidence)
      .mockResolvedValueOnce({ ok: true, snapshots: [snapshot] })
      .mockResolvedValueOnce({ ok: false, failure: 'unreachable' })
    mount()

    expect(await screen.findByText('3.27.0')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /Refresh service inventory|Obnovit inventář služeb/ }))

    expect(await screen.findByText(/Showing the last verified inventory|Zobrazuji poslední ověřený inventář/)).toBeInTheDocument()
    expect(screen.getByText('3.27.0')).toBeInTheDocument()
    await waitFor(() => expect(fetchAllServiceSnapshotsEvidence).toHaveBeenCalledTimes(2))
  })

  it('recovers from an initial failure through the explicit retry', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ vulns: [] }), { status: 200 })))
    vi.mocked(fetchAllServiceSnapshotsEvidence)
      .mockResolvedValueOnce({ ok: false, failure: 'unreachable' })
      .mockResolvedValueOnce({ ok: true, snapshots: [snapshot] })
    mount()

    fireEvent.click(await screen.findByRole('button', { name: /Retry|Zkusit znovu/ }))
    expect(await screen.findByText('3.27.0')).toBeInTheDocument()
    expect(screen.queryByText(/is not responding|neodpovídá/)).not.toBeInTheDocument()
  })

  it('shows unavailable CVE evidence instead of a reassuring empty claim', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('{}', { status: 503 })))
    vi.mocked(fetchAllServiceSnapshotsEvidence).mockResolvedValue({ ok: true, snapshots: [snapshot] })
    mount()

    expect(await screen.findByText(/CVE status unavailable|CVE stav nedostupný/)).toBeInTheDocument()
    expect(screen.queryByText(/No known CVE|Žádné známé CVE/)).not.toBeInTheDocument()
  })
})
