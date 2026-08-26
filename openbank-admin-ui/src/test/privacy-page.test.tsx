// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, it, expect, beforeEach } from 'vitest'
import { render, cleanup, screen, fireEvent } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import PrivacyContent from '@/components/privacy/PrivacyContent'

// #7068: the public privacy notice used to render only Czech, hardcoded, and
// nothing on the page reacted to the language toggle. These tests fail against
// that shape (fixed literal Czech strings, no button) and pass against the
// bilingual PrivacyContent that now backs /privacy.
describe('privacy notice — bilingual public surface (#7068)', () => {
  beforeEach(() => {
    localStorage.clear()
    cleanup()
  })

  it('renders English content by default, with every mandated legal section present', () => {
    render(<LanguageProvider><PrivacyContent /></LanguageProvider>)

    expect(screen.getByRole('heading', { level: 1, name: /privacy notice/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /who is the controller/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /what data we process, and why/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /retention period/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /your rights/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /security contact/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /back to sign-in/i })).toHaveAttribute('href', '/auth/login')
  })

  it('switches every section to Czech when the language toggle is activated — nothing is stuck in English', () => {
    render(<LanguageProvider><PrivacyContent /></LanguageProvider>)

    fireEvent.click(screen.getByRole('button', { name: /switch to czech/i }))

    expect(screen.getByRole('heading', { level: 1, name: /ochrana osobních údajů/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /kdo je správcem/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /jaké údaje zpracováváme a proč/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /doba uchování/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /vaše práva/i })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /bezpečnostní kontakt/i })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /zpět na přihlášení/i })).toHaveAttribute('href', '/auth/login')

    // The stale-language regression this guards against: an English heading
    // left over from the section that was skipped by the toggle handler.
    expect(screen.queryByRole('heading', { name: /who is the controller/i })).not.toBeInTheDocument()
  })

  it('exposes the language switch as an accessible, labeled button (not a bare click target)', () => {
    render(<LanguageProvider><PrivacyContent /></LanguageProvider>)
    const toggle = screen.getByRole('button', { name: /switch to czech/i })
    expect(toggle).toHaveAttribute('type', 'button')
    expect(toggle).toHaveAccessibleName()
  })

  it('preserves the GDPR contact and controller details verbatim', () => {
    render(<LanguageProvider><PrivacyContent /></LanguageProvider>)
    expect(screen.getAllByText('hello@open-bank.tech').length).toBeGreaterThan(0)
    expect(screen.getByText('security@open-bank.tech')).toBeInTheDocument()
  })
})
