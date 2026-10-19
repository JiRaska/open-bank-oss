// SPDX-License-Identifier: Apache-2.0
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import SdlcPage from '@/app/devops/sdlc/page'
import InfraTopologyPage from '@/app/infrastructure/topology/page'
import ClusterDossierPage from '@/app/docs/cluster/page'

vi.mock('@/components/devops/GateCatalogExplorer', () => ({ GateCatalogExplorer: () => null }))
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
beforeEach(() => { localStorage.clear() })

describe.each(['en', 'cs'] as const)('painted control names in %s', language => {
  function mount(page: React.ReactNode) {
    return render(<LanguageProvider initialLanguage={language}>{page}</LanguageProvider>)
  }

  it('keeps the animation name equal to its painted action through both states', () => {
    mount(<SdlcPage />)
    const pause = language === 'en' ? 'Pause animation' : 'Pozastavit animaci'
    const play = language === 'en' ? 'Play animation' : 'Spustit animaci'
    const control = screen.getByRole('button', { name: pause })
    expect(control).toHaveTextContent(pause)
    expect(control).toHaveAttribute('aria-pressed', 'true')
    fireEvent.click(control)
    expect(control).toHaveAccessibleName(play)
    expect(control).toHaveTextContent(play)
    expect(control).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(control)
    expect(control).toHaveAccessibleName(pause)
    expect(control).toHaveTextContent(pause)
  })

  it('names the refresh control from the painted idle and pending action', async () => {
    let resolveStatus!: (response: Response) => void
    const pending = new Promise<Response>(resolve => { resolveStatus = resolve })
    vi.stubGlobal('fetch', vi.fn((input: unknown) => String(input).includes('/status') ? pending : Promise.resolve(new Response('{"components":[]}'))))
    mount(<InfraTopologyPage />)
    const checking = language === 'en' ? 'Checking...' : 'Zjišťuji…'
    const idle = language === 'en' ? 'Refresh infrastructure status' : 'Obnovit stav infrastruktury'
    const control = await screen.findByRole('button', { name: checking })
    expect(control).toHaveTextContent(checking)
    expect(control).toBeDisabled()
    resolveStatus(new Response('{}'))
    await waitFor(() => expect(control).toHaveAccessibleName(idle))
    expect(control).toHaveTextContent(idle)
    expect(control).toBeEnabled()
  })

  it.each(['live', 'partial', 'planned'] as const)('retains ordinal, layer and %s status in the natural disclosure name', async status => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      counts: {}, groups: [], namespaces: [], securityLayers: [{ id: 'edge', label: 'Edge', status, icon: 'shield', controls: [], adr: [], analogy: '', summary: '' }],
      imageAnatomy: { steps: [] }, planVsReality: [],
    }))))
    mount(<ClusterDossierPage />)
    const label = status === 'live' ? 'Live' : status === 'partial' ? (language === 'en' ? 'Partial' : 'Částečně') : (language === 'en' ? 'Planned' : 'Plánováno')
    const control = await screen.findByRole('button', { name: `1 Edge ${label}` })
    expect(control.textContent!.replace(/\s+/g, ' ').trim()).toBe(`1 Edge ${label}`)
    expect(control).toHaveAttribute('aria-expanded', 'true')
    fireEvent.keyDown(control, { key: 'Enter' })
    expect(control).toHaveAttribute('aria-expanded', 'false')
    expect(control).toHaveAccessibleName(`1 Edge ${label}`)
    fireEvent.keyDown(control, { key: ' ' })
    expect(control).toHaveAttribute('aria-expanded', 'true')
  })
})
