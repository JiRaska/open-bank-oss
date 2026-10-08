// SPDX-License-Identifier: Apache-2.0
import { useState } from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { UiMessageEditor } from '@/components/communication/UiMessageEditor'

vi.mock('@/lib/i18n/LanguageContext', () => ({ useLanguage: () => ({ t: (cs: string) => cs }) }))

function Editor() {
  const [value, setValue] = useState<Record<string, string>>({ 'cs.status.loading': 'Hledám.' })
  return <><UiMessageEditor value={value} onChange={setValue} disabled={false} /><output data-testid="value">{JSON.stringify(value)}</output></>
}

describe('mobile message editor', () => {
  it('edits locales independently and clearing an override restores bundled fallback', () => {
    render(<Editor />)
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'en' } })
    fireEvent.change(screen.getAllByRole('textbox')[0], { target: { value: 'Looking for you.' } })
    expect(JSON.parse(screen.getByTestId('value').textContent!)).toEqual({
      'cs.status.loading': 'Hledám.', 'en.status.loading': 'Looking for you.',
    })
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'cs' } })
    fireEvent.change(screen.getAllByRole('textbox')[0], { target: { value: '' } })
    expect(JSON.parse(screen.getByTestId('value').textContent!)).toEqual({ 'en.status.loading': 'Looking for you.' })
  })

  it('renders literal text in preview and locks the form after drafting', () => {
    render(<UiMessageEditor value={{ 'cs.status.loading': '<img src=x>' }} onChange={vi.fn()} disabled />)
    expect(screen.getByLabelText('Náhled hlášky')).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(screen.getAllByRole('textbox')[0]).toBeDisabled()
  })

  it('edits the profile-change introduction while showing the fixed payment instruction', () => {
    const onChange = vi.fn()
    render(<UiMessageEditor value={{ 'cs.send.profileChanged': 'Změnili jste profil.' }} onChange={onChange} disabled={false} />)
    const intro = screen.getByLabelText('Změna profilu při platbě') as HTMLTextAreaElement
    expect(intro.value).toBe('Změnili jste profil.')
    expect(intro.maxLength).toBe(120)
    expect(screen.getByLabelText('Náhled hlášky')).toHaveTextContent(
      'Změnili jste profil. Zkontrolujte platbu a potvrďte ji znovu.',
    )
    fireEvent.change(intro, { target: { value: 'Jste v jiném profilu.' } })
    expect(onChange).toHaveBeenCalledWith({ 'cs.send.profileChanged': 'Jste v jiném profilu.' })
  })
})
