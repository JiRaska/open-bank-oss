// SPDX-License-Identifier: Apache-2.0
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { LifecycleStrip, type CompLifecycle } from '@/components/infra/LifecycleStrip'

const data: CompLifecycle = {
  id: 'postgres',
  running: { version: '17.4', source: 'runtime' },
  lifecycle: {
    available: true,
    product: 'postgresql',
    cycle: '17',
    isLts: false,
    eol: '2029-11-08',
    eolPassed: false,
    eolDaysLeft: 900,
    support: true,
    latestInCycle: '17.6',
    newestVersion: '18.0',
    newestCycle: '18',
  },
  upgrade: { patchAvailable: true, majorAvailable: false, target: '17.6', releaseNotesUrl: null },
  cve: { scanned: true, critical: 0, high: 0, medium: 0, low: 0, total: 0, top: [] },
  urgency: 'patch-available',
}

const t = (_cs: string, en: string) => en

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('lifecycle upgrade proposal feedback', () => {
  it('announces progress and success while preventing a duplicate request', async () => {
    let resolveRequest: ((value: Response) => void) | undefined
    const fetchMock = vi.fn(() => new Promise<Response>(resolve => { resolveRequest = resolve }))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    render(<LifecycleStrip data={data} name="PostgreSQL" t={t} />)

    const button = screen.getByRole('button', { name: 'Plan upgrade' })
    expect(button).toHaveAttribute('type', 'button')
    await user.click(button)
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByRole('status')).toHaveTextContent('Drafting upgrade proposal.')
    await user.click(button)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    resolveRequest?.(new Response(JSON.stringify({ result: { content: [] } }), { status: 200 }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Queued' })).toBeDisabled())
    expect(screen.getByRole('status')).toHaveTextContent('queued for independent review')
  })

  it('turns a failed request into a clear retry action', async () => {
    const fetchMock = vi.fn()
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(new Response(JSON.stringify({ result: { content: [] } }), { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)
    const user = userEvent.setup()
    render(<LifecycleStrip data={data} name="PostgreSQL" t={t} />)

    await user.click(screen.getByRole('button', { name: 'Plan upgrade' }))
    const retry = await screen.findByRole('button', { name: 'Try again' })
    expect(retry).toBeEnabled()
    expect(screen.getByRole('status')).toHaveTextContent('could not be queued')

    await user.click(retry)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Queued' })).toBeDisabled())
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('does not render an unsafe release-notes protocol', () => {
    render(<LifecycleStrip
      data={{ ...data, upgrade: { ...data.upgrade, patchAvailable: false, target: null, releaseNotesUrl: 'javascript:alert(1)' } }}
      name="PostgreSQL"
      t={t}
    />)

    expect(screen.queryByRole('link', { name: /Release notes/ })).not.toBeInTheDocument()
  })
})
