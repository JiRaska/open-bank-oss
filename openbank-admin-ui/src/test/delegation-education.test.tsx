// SPDX-License-Identifier: Apache-2.0

import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { DELEGATION_SCENARIOS, DelegationEducation } from '@/components/delegations/DelegationEducation'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'

vi.mock('next/image', () => ({ default: (props: Record<string, unknown>) => <span data-testid="guide-image" data-alt={props.alt} /> }))

afterEach(cleanup)

describe('delegation education', () => {
  it('teaches the authority chain before any customer or API is available', () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
    renderEducation('en')

    const guide = screen.getByRole('complementary', { name: 'Understand first. Decide second.' })
    expect(guide).toHaveTextContent('An owner is not a catalog role')
    expect(guide).toHaveTextContent('administrators can change only the preset catalog')

    fireEvent.click(screen.getByText('Open the full model and segment examples'))
    const model = screen.getByRole('list', { name: 'How to read delegated access' })
    expect(within(model).getAllByRole('listitem')).toHaveLength(5)
    expect(model).toHaveTextContent('Authority source')
    expect(model).toHaveTextContent('Exact rights')
    expect(model).toHaveTextContent('Evidence and history')

    expect(screen.getByRole('heading', { name: 'How to read an “effective” right' })).toBeVisible()
    expect(screen.getByText(/included in the preset.*not proof of support/i)).toBeVisible()
    expect(fetchSpy).not.toHaveBeenCalled()
    fetchSpy.mockRestore()
  })

  it('marks every business scenario as education and separates current evidence from roadmap controls', () => {
    renderEducation('en')

    fireEvent.click(screen.getByText('Open the full model and segment examples'))

    expect(DELEGATION_SCENARIOS.map(item => item.id)).toEqual(['sole-trader', 'sme', 'corporate'])
    expect(screen.getByText('Education only')).toBeVisible()
    expect(screen.getByText(/select nothing automatically/i)).toBeVisible()

    for (const name of ['Sole trader', 'SME', 'Corporate']) {
      expect(screen.getByText(name, { selector: 'span' })).toBeVisible()
    }

    const corporate = screen.getByText('Corporate', { selector: 'span' }).closest('details')
    expect(corporate).not.toBeNull()
    fireEvent.click(within(corporate!).getByText('Corporate'))
    expect(within(corporate!).getByRole('region', { name: 'Corporate: What the console can explain today' })).toBeVisible()
    expect(within(corporate!).getByRole('region', { name: 'Corporate: Next control layer — not active' })).toHaveTextContent('N-of-M approval')
    expect(within(corporate!).getByText(/Target model:/)).toBeVisible()
  })

  it('ships the same truthful model in Czech', () => {
    renderEducation('cs')

    expect(screen.getByRole('heading', { name: /Kdo může nad čím dělat co/ })).toBeVisible()
    fireEvent.click(screen.getByText('Otevřít celý model a příklady pro jednotlivé segmenty'))
    expect(screen.getByText('Pouze edukace')).toBeVisible()
    expect(screen.getByText(/Tyto režimy dnes ještě nelze nabídnout jako účinnou delegaci/)).toBeInTheDocument()
    expect(screen.queryByText('DELEGATION_MANAGE')).not.toBeInTheDocument()
  })
})

function renderEducation(initialLanguage: 'cs' | 'en') {
  render(
    <LanguageProvider initialLanguage={initialLanguage}>
      <DelegationEducation />
    </LanguageProvider>,
  )
}
