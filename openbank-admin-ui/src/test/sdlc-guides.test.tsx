// SPDX-License-Identifier: Apache-2.0
import React from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import CiGuidePage from '@/app/devops/sdlc/ci/page'
import CdGuidePage from '@/app/devops/sdlc/cd/page'
import { currentNavigation } from '@/lib/navigation/catalog'
import evidence from '../../sdlc-evidence.json'

const state = vi.hoisted(() => ({ language: 'en' as 'en' | 'cs' }))
vi.mock('@/lib/i18n/LanguageContext', () => ({ useLanguage: () => ({ language: state.language, t: (cs: string, en: string) => state.language === 'cs' ? cs : en }) }))

beforeEach(() => { state.language = 'en' })
afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks() })

describe('SDLC guides', () => {
  it('boards lions, changes their emotions and distinguishes merge from deployment', () => {
    render(<CiGuidePage />)
    const scene = screen.getByRole('img', { name: /Lion change train/ })
    expect(scene).toHaveAccessibleDescription(/awaits verification before boarding/)
    expect(scene.querySelector('[data-lane="batch"]')?.querySelectorAll('image[href="/brand/sdlc/train-passengers.png"]')).toHaveLength(0)
    fireEvent.click(screen.getByRole('button', { name: /Next station/ }))
    expect(scene).toHaveAccessibleDescription(/Ticket inspection represents PR checks/)
    expect(scene).toHaveTextContent('CI / REVIEW')
    fireEvent.click(screen.getByRole('button', { name: /Next station/ }))
    expect(scene).toHaveAccessibleDescription(/have boarded/)
    fireEvent.click(screen.getByRole('button', { name: /Next station/ }))
    expect(screen.getByRole('region', { name: /What is happening now/ })).toHaveTextContent(/two green changes can still interfere/i)
    fireEvent.click(screen.getByRole('button', { name: /Next station/ }))
    expect(scene).toHaveAccessibleDescription(/celebrate a successful merge/)
    expect(screen.getByRole('region', { name: /What is happening now/ })).toHaveTextContent(/Deployment comes next/)
    expect(screen.getByRole('button', { name: /Next station/ })).toBeDisabled()
    expect(screen.getAllByRole('link', { name: /how we deploy/i })[0]).toHaveAttribute('href', '/devops/sdlc/cd')
  })
  it('keeps each track’s progress when switching details and advances CI lanes independently', () => {
    vi.useFakeTimers()
    render(<CiGuidePage />)
    const scene = screen.getByRole('img', { name: /Lion change train/ })
    const progress = (lane: string) => scene.querySelector(`[data-lane="${lane}"]`)?.getAttribute('data-step')
    expect(scene).toHaveAccessibleDescription(/three concurrent tracks/)
    expect([progress('batch'), progress('fast'), progress('strict')]).toEqual(['0', '1', '2'])
    act(() => vi.advanceTimersByTime(5000))
    expect([progress('batch'), progress('fast'), progress('strict')]).toEqual(['0', '2', '2'])
    act(() => vi.advanceTimersByTime(3000))
    expect([progress('batch'), progress('fast'), progress('strict')]).toEqual(['1', '2', '2'])
    fireEvent.click(screen.getByRole('button', { name: /Fast lane.*independent change/i }))
    expect(scene).toHaveAccessibleDescription(/have boarded/)
    fireEvent.click(screen.getByRole('button', { name: /Batch train.*shared test ride/i }))
    expect(scene).toHaveAccessibleDescription(/Ticket inspection/)
    expect(screen.getByText(/Writes to main still follow governed ordering/)).toBeInTheDocument()
  })
  it('explains the carrier-free fast lane and the strict path separately', () => {
    render(<CiGuidePage />)
    fireEvent.click(screen.getByRole('button', { name: /Fast lane.*independent change/i }))
    const scene = screen.getByRole('img', { name: /Lion change train/ })
    expect(scene).toHaveAccessibleDescription(/One proposed change/)
    fireEvent.click(screen.getByRole('button', { name: /Final check/ }))
    expect(screen.getByRole('region', { name: /What is happening now/ })).toHaveTextContent(/No shared carrier is needed/)
    fireEvent.click(screen.getByRole('button', { name: /Strict lane.*dispatcher changes/i }))
    fireEvent.click(within(screen.getByRole('list', { name: /Stations on the change journey/ })).getByRole('button', { name: /Route selection/ }))
    expect(screen.getByRole('region', { name: /What is happening now/ })).toHaveTextContent(/without generators/)
    expect(screen.getByRole('region', { name: /What is happening now/ })).toHaveTextContent(/judge checks ride alone/)
  })
  it('explains real failures separately from inconclusive CI and can return to the journey', () => {
    render(<CiGuidePage />)
    fireEvent.click(screen.getByRole('button', { name: /What if a check fails/ }))
    const detail = screen.getByRole('region', { name: /What is happening now/ })
    expect(detail).toHaveTextContent(/halves the batch/)
    expect(detail).toHaveTextContent(/Cancellation.*is not evidence/)
    fireEvent.click(screen.getByRole('button', { name: /Back to the journey/ }))
    expect(detail).toHaveTextContent(/A lion brings/)
  })
  it('respects reduced motion and never automatically advances its story', () => {
    vi.useFakeTimers()
    vi.spyOn(window, 'matchMedia').mockReturnValue({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() } as unknown as MediaQueryList)
    render(<CiGuidePage />)
    expect(screen.getByRole('button', { name: /Play journey/ })).toHaveAttribute('aria-pressed', 'false')
    vi.advanceTimersByTime(40000)
    expect(screen.getByRole('region', { name: /What is happening now/ })).toHaveTextContent(/A lion brings/)
  })
  it.each(['en', 'cs'] as const)('offers distinct CI and CD destinations in %s', language => {
    state.language = language
    render(<CiGuidePage />)
    const nav = screen.getByRole('navigation', { name: /SDLC/ })
    expect(within(nav).getByRole('link', { name: /^CI/ })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: /^CD/ })).toHaveAttribute('href', '/devops/sdlc/cd')
    expect(currentNavigation('/devops/sdlc/cd')?.href).toBe('/devops/sdlc/cd')
  })
  it('labels CD as build evidence, displays the actual limits and separates application and data recovery', () => {
    render(<CdGuidePage />)
    expect(screen.getByText(/not the live cluster/)).toBeInTheDocument()
    if (!evidence.ledger.exactWeightsPossible) expect(screen.getByText('Declared percentages are not a guaranteed share of real traffic.')).toBeInTheDocument()
    if (evidence.ledger.metrics.some(metric => metric.successCondition?.includes('isNaN'))) expect(screen.getByText('Missing measurements do not necessarily stop deployment today.')).toBeInTheDocument()
    expect(screen.getByText(/does not undo a database migration/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /ledger manifest at this build/ })).toHaveAttribute('href', expect.stringContaining(evidence.revision))
    fireEvent.click(screen.getByText('Exact configured conditions and limits'))
    const table = screen.getByRole('table')
    for (const metric of evidence.ledger.metrics) expect(table).toHaveTextContent(metric.failureCondition ?? '—')
  })
})
