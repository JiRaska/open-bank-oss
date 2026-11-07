// SPDX-License-Identifier: Apache-2.0
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AgentDock } from '@/components/agent/AgentDock'
import { openAssistant } from '@/lib/navigation/assistant'
vi.mock('next/navigation', () => ({ usePathname: () => '/accounts' }))
vi.mock('@/lib/i18n/LanguageContext', () => ({ useLanguage: () => ({ t: (_cs: string, en: string) => en }) }))
beforeEach(() => { Object.defineProperty(HTMLElement.prototype, 'scrollTo', { configurable: true, value: vi.fn() }) })
afterEach(() => { vi.unstubAllGlobals(); Reflect.deleteProperty(HTMLElement.prototype, 'scrollTo') })

describe('assistant search handoff', () => {
  it('does not submit automatically and ignores an old reply after context changes', async () => {
    let resolveOld!: (value: Response) => void
    const oldReply = new Promise<Response>(resolve => { resolveOld = resolve })
    const fetchMock = vi.fn().mockImplementation((_url: string, init?: RequestInit) => init?.method === 'POST'
      ? oldReply : Promise.resolve(new Response(JSON.stringify({ models: [], default: '' }))))
    vi.stubGlobal('fetch', fetchMock)
    render(<AgentDock />)
    act(() => openAssistant({ prompt: 'First question', context: { label: 'First account', route: '/accounts/first' } }))
    const panel = screen.getByRole('region', { name: 'OpenBank assistant panel' })
    expect(within(panel).getByRole('textbox')).toHaveValue('First question')
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(0)
    fireEvent.click(within(panel).getByRole('button', { name: 'Send message' }))
    const request = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')!
    expect(JSON.parse(request[1]!.body as string).context).toBe('admin page /accounts/first')
    act(() => openAssistant({ prompt: 'Second question', context: { label: 'Second account', route: '/accounts/second' } }))
    await act(async () => { resolveOld(new Response(JSON.stringify({ reply: 'Stale reply', toolCalls: [] }))); await oldReply })
    await waitFor(() => expect(within(panel).getByRole('textbox')).toHaveFocus())
    expect(panel).toHaveTextContent('Second account')
    expect(panel).not.toHaveTextContent('Stale reply')
    expect(within(panel).getByRole('textbox')).toHaveValue('Second question')
  })
})
