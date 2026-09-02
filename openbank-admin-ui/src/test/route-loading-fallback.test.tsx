// SPDX-License-Identifier: Apache-2.0

import { afterEach, describe, expect, it } from 'vitest'
import React from 'react'
import { cleanup, render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import Loading from '@/app/loading'

afterEach(cleanup)

describe('App Router route-transition loading boundary', () => {
  it('announces one accessible, localized progress status', () => {
    render(<LanguageProvider><Loading /></LanguageProvider>)
    expect(screen.getAllByRole('status')).toHaveLength(1)
    expect(screen.getByRole('status')).toHaveTextContent('Loading page…')
  })

  it('switches the announced status to Czech when the language toggles', () => {
    render(<LanguageProvider initialLanguage="cs"><Loading /></LanguageProvider>)
    expect(screen.getByRole('status')).toHaveTextContent('Načítání stránky…')
  })

  it('keeps decorative skeleton placeholders out of the accessibility tree', () => {
    const { container } = render(<LanguageProvider><Loading /></LanguageProvider>)
    const skeletons = container.querySelectorAll('.skeleton')
    expect(skeletons.length).toBeGreaterThan(0)
    skeletons.forEach(node => {
      const hiddenAncestor = node.closest('[aria-hidden="true"]')
      expect(hiddenAncestor).not.toBeNull()
    })
  })

  it('renders a page-shaped skeleton (header + card grid) to keep layout stable', () => {
    const { container } = render(<LanguageProvider><Loading /></LanguageProvider>)
    expect(container.querySelector('.page-header')).not.toBeNull()
    expect(container.querySelector('.grid-3')).not.toBeNull()
    expect(container.querySelectorAll('.card').length).toBeGreaterThanOrEqual(3)
  })
})
