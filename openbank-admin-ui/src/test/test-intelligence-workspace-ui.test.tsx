// SPDX-License-Identifier: Apache-2.0
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { TestIntelligenceWorkspace } from '@/components/testing/workspace/TestIntelligenceWorkspace'
import { TestAttentionList } from '@/components/testing/workspace/TestAttentionList'
import { workspaceCase, workspaceComponent, workspaceObservedAt, workspaceReport } from '../../e2e/fixtures/test-intelligence-workspace'
import type { TestIntelligenceReport } from '@/lib/types/test-intelligence'

const locale = vi.hoisted(() => ({ language: 'en' }))
vi.mock('@/lib/i18n/LanguageContext', () => ({ useLanguage: () => ({ language: locale.language, t: (cs: string, en: string) => locale.language === 'cs' ? cs : en }) }))
const response = (value: unknown) => new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } })
beforeEach(() => {
  locale.language = 'en'
  window.history.replaceState(null, '', '/system/tests')
  vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => Promise.resolve(response(url === '/api/catalog/services'
    ? { services: [{ name: workspaceComponent.component, dataDomain: 'core', apiTitle: 'Accounts' }] }
    : { explanation: 'Only the recorded outcome is known.', model: 'fixture-model', fingerprint: workspaceCase.fingerprint, collectedAt: workspaceObservedAt }))))
})
afterEach(() => vi.unstubAllGlobals())
function mount(report: TestIntelligenceReport = workspaceReport()) {
  return render(<TestIntelligenceWorkspace report={report} freshness={null} attention={(onOpen, onOpenRuns) => <TestAttentionList report={report} onOpen={onOpen} onOpenRuns={onOpenRuns} />} learning={<p>Existing architecture</p>} renderRuns={openTest => <button type="button" onClick={() => openTest(workspaceCase)}>Open retained test</button>} />)
}
async function openDetail() {
  fireEvent.click(await screen.findByRole('button', { name: 'Accounts · Logic · Passed' }))
  fireEvent.click(await screen.findByRole('button', { name: 'rejects duplicate requests' }))
  await screen.findByRole('region', { name: 'Understand this test' })
}

describe('matrix-first educational workspace', () => {
  it('takes two clicks to an exact test, retains a shareable URL and requests AI only explicitly', async () => {
    mount()
    expect(screen.getByRole('region', { name: 'Component evidence matrix' })).toBeVisible()
    await openDetail()
    expect(window.location.search).toContain('test=case-1')
    expect(screen.getByRole('link', { name: /Open this test’s exact run/ })).toHaveAttribute('href', workspaceCase.lastRun!.url)
    expect(screen.getByText(/Individual steps, input values/)).toBeVisible()
    expect(vi.mocked(fetch).mock.calls.filter(([url]) => url === '/api/test-intelligence/explain')).toHaveLength(0)
    fireEvent.click(screen.getByRole('button', { name: 'Explain this result with AI' }))
    expect(await screen.findByText('Only the recorded outcome is known.')).toBeVisible()
    expect(screen.getByText('AI interpretation · original verdict unchanged')).toBeVisible()
  })
  it('restores a deep link and responds to browser-history navigation without losing the report', async () => {
    window.history.replaceState(null, '', '/system/tests?component=openbank-account-service&kind=unit&test=case-1')
    mount()
    await screen.findByRole('region', { name: 'Understand this test' })
    act(() => { window.history.replaceState(null, '', '/system/tests'); window.dispatchEvent(new PopStateEvent('popstate')) })
    expect(await screen.findByRole('region', { name: 'Component evidence matrix' })).toBeVisible()
  })
  it('never substitutes an aggregate component run for a missing individual run', async () => {
    mount(workspaceReport({ testCases: [{ ...workspaceCase, lastRun: undefined }] }))
    await openDetail()
    expect(screen.queryByRole('link', { name: /exact run/ })).not.toBeInTheDocument()
    expect(screen.getByText(/A component-level run does not substitute/)).toBeVisible()
  })
  it('keeps deterministic evidence visible when AI fails and rejects a response for a different test', async () => {
    vi.mocked(fetch).mockImplementation((url: string | URL | Request) => Promise.resolve(response(String(url) === '/api/catalog/services'
      ? { services: [{ name: workspaceComponent.component, dataDomain: 'core', apiTitle: 'Accounts' }] }
      : { explanation: 'Wrong test result', fingerprint: 'other-test', collectedAt: workspaceObservedAt })))
    mount()
    await openDetail()
    fireEvent.click(screen.getByRole('button', { name: 'Explain this result with AI' }))
    expect(await screen.findByRole('status')).toHaveTextContent('AI explanation is unavailable')
    expect(screen.queryByText('Wrong test result')).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: /exact run/ })).toBeVisible()
  })
  it('cancels an explanation when leaving the selected test', async () => {
    let finish: ((value: Response) => void) | undefined
    vi.mocked(fetch).mockImplementation((url: string | URL | Request) => String(url) === '/api/catalog/services'
      ? Promise.resolve(response({ services: [{ name: workspaceComponent.component, dataDomain: 'core', apiTitle: 'Accounts' }] }))
      : new Promise(resolve => { finish = resolve }))
    mount()
    await openDetail()
    fireEvent.click(screen.getByRole('button', { name: 'Explain this result with AI' }))
    const options = vi.mocked(fetch).mock.calls.find(([url]) => url === '/api/test-intelligence/explain')?.[1]
    fireEvent.click(screen.getByRole('button', { name: 'Back to component tests' }))
    expect(options?.signal?.aborted).toBe(true)
    await act(async () => { finish?.(response({ explanation: 'late explanation', fingerprint: workspaceCase.fingerprint, collectedAt: workspaceObservedAt })) })
    expect(screen.queryByText('late explanation')).not.toBeInTheDocument()
  })
  it('bounds the matrix render and keeps every component reachable through paging', async () => {
    const components = Array.from({ length: 51 }, (_, index) => ({ ...workspaceComponent, component: `openbank-fixture-${index}`, domain: 'platform' }))
    const { container } = mount(workspaceReport({ components }))
    await waitFor(() => expect(container.querySelectorAll('td[data-component]')).toHaveLength(100))
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.getByText('Showing 26–50 of 51')).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(container.querySelectorAll('td[data-component]')).toHaveLength(4)
    expect(screen.getByText('Showing 51–51 of 51')).toBeVisible()
  })
  it('opens a missing-control item at the corresponding component and type', async () => {
    mount()
    fireEvent.click(screen.getByRole('button', { name: 'Needs attention' }))
    fireEvent.click(screen.getByRole('button', { name: workspaceComponent.component }))
    expect(await screen.findByText(/Individual tests are not retained/)).toBeVisible()
    expect(window.location.search).toContain('kind=mutation')
  })
  it('keeps the report useful when the catalog is unavailable and explains old missing test links', async () => {
    vi.mocked(fetch).mockRejectedValue(new Error('offline catalog'))
    window.history.replaceState(null, '', '/system/tests?component=openbank-account-service&kind=unit&test=not-retained')
    mount()
    expect(await screen.findByText(/linked test is not in the retained report slice/)).toBeVisible()
    expect(screen.getByRole('button', { name: 'rejects duplicate requests' })).toBeVisible()
  })
  it('keeps failures visible above an unknown required control and deduplicates planned obligations', () => {
    const report = workspaceReport({
      components: [{ ...workspaceComponent, evidence: [{ ...workspaceComponent.evidence[0], state: 'failed' }] }],
      requiredControls: [
        { id: 'account:unit', component: workspaceComponent.component, kind: 'unit', state: 'unknown', reason: 'Unverified control', source: null, observedAt: null },
        { id: 'synthetic:login', component: null, kind: 'synthetic', state: 'blocked', reason: 'Missing target', source: null, observedAt: null },
      ],
      syntheticJourneys: [{ id: 'login', title: 'Login canary', status: 'planned', state: 'blocked', severity: 'ticket', schedule: null, environment: null, covers: [], falsifies: 'Login fails', blocker: 'Missing target' }],
    })
    render(<TestAttentionList report={report} onOpen={vi.fn()} onOpenRuns={vi.fn()} />)
    expect(screen.getAllByRole('row')).toHaveLength(3)
    expect(screen.getAllByRole('row')[1]).toHaveTextContent('Failed')
    expect(screen.getByRole('button', { name: 'Login canary' })).toBeVisible()
  })
  it('translates navigation and educational states into Czech', async () => {
    locale.language = 'cs'
    mount()
    expect(screen.getByRole('button', { name: 'Komponenty' })).toBeVisible()
    fireEvent.click(screen.getByRole('button', { name: 'Jak testujeme' }))
    expect(screen.getByText('Od otázky k ověřitelnému výsledku')).toBeVisible()
    expect(screen.getByRole('heading', { name: 'Celá cesta' })).toBeVisible()
  })
})
