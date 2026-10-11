// SPDX-License-Identifier: Apache-2.0
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import Page from '@/app/communication/edit/[personaKey]/page'

vi.mock('next/navigation', () => ({ useParams: () => ({ personaKey: 'customer-copilot' }) }))
vi.mock('@/lib/i18n/LanguageContext', () => ({ useLanguage: () => ({ t: (cs: string) => cs }) }))
vi.mock('@/components/auth/AuthGuard', () => ({
  AuthGuard: ({ children }: { children: React.ReactNode }) => children,
  Can: ({ children }: { children: React.ReactNode }) => children,
}))

afterEach(() => vi.unstubAllGlobals())

it('loads approved copy and saves edited copy with existing vocabulary in the reviewable draft', async () => {
  let saved: Record<string, unknown> | undefined
  vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith('/published')) return Response.json({
      tone: 'warm', formality: 'informal', formOfAddress: 'tykání',
      preferredTerms: { account: 'účet' }, forbiddenTerms: ['password'],
      uiMessages: { 'cs.status.loading': 'Původní text.', 'en.status.loading': 'Original text.' },
    })
    if (url.endsWith('/style-versions') && init?.method === 'POST') {
      saved = JSON.parse(String(init.body))
      return Response.json({ id: 'draft-1', version: 2, status: 'DRAFT' }, { status: 201 })
    }
    return Response.json([])
  }))
  render(<Page />)
  const input = await screen.findByDisplayValue('Původní text.')
  fireEvent.change(input, { target: { value: 'Už hledám.' } })
  fireEvent.click(screen.getByRole('button', { name: 'Uložit koncept' }))
  await waitFor(() => expect(saved).toBeDefined())
  expect(saved?.uiMessages).toEqual({ 'cs.status.loading': 'Už hledám.', 'en.status.loading': 'Original text.' })
  expect(saved?.preferredTerms).toEqual({ account: 'účet' })
  expect(saved?.forbiddenTerms).toEqual(['password'])
  expect(await screen.findByRole('button', { name: 'Odeslat ke schválení' })).toBeEnabled()
  expect(input).toBeDisabled()
})
