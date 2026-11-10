// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it, afterEach } from 'vitest'
import { render, screen, fireEvent, within } from '@testing-library/react'
import { mkdtempSync, writeFileSync, readFileSync } from 'fs'
import os from 'os'
import path from 'path'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { RegulatoryObligations } from '@/components/regulatory/RegulatoryObligations'
import { getRegulatoryObligations } from '@/lib/regulatory/obligations'
import type { ObligationRegister } from '@/lib/regulatory/obligation-types'

const register: ObligationRegister = {
  available: true,
  areas: [{ id: 'deposit-insurance', cs: 'Pojištění vkladů', en: 'Deposit insurance' }, { id: 'prudential', cs: 'Obezřetnost', en: 'Prudential' }],
  authorities: [{ id: 'gsft', cs: 'Garanční systém finančního trhu', en: 'Financial Market Guarantee System', url: null }],
  obligations: [
    {
      id: 'eba-lcr', area: 'prudential', name: { cs: 'LCR', en: 'LCR' }, authority: 'cnb', legalBasis: 'CRR čl. 415',
      sources: { law: 'https://eur-lex.europa.eu/eli/reg_del/2015/61/oj', spec: null }, cadence: 'Měsíčně', cadenceConfidence: 'known-eu',
      kind: 'periodic-aggregate', transport: 'SDAT', personalData: 'no', applicability: 'always',
      coverage: { status: 'filed-return', reports: ['cnb-lcr'], services: ['openbank-finrep-service'], tracking: null }, note: null,
    },
    {
      id: 'gsft-depositor-data', area: 'deposit-insurance', name: { cs: 'Údaje pro výplatu náhrad', en: 'Depositor data' }, authority: 'gsft',
      legalBasis: 'vyhláška č. 71/2011 Sb.', sources: { law: 'https://www.zakonyprolidi.cz/cs/2011-71', spec: 'javascript:alert(1)' },
      cadence: 'Při pojistné události', cadenceConfidence: 'verified', kind: 'event', transport: 'CSV', personalData: 'yes', applicability: 'always',
      coverage: { status: 'missing', reports: [], services: [], tracking: null }, note: null,
    },
  ],
}

function renderCs(reg: ObligationRegister) {
  return render(<LanguageProvider initialLanguage="cs"><RegulatoryObligations register={reg} /></LanguageProvider>)
}

describe('RegulatoryObligations', () => {
  it('lists a missing duty first, with its legal source and authority', () => {
    renderCs(register)
    const items = screen.getAllByRole('listitem')
    expect(within(items[0]).getByRole('heading', { name: 'Údaje pro výplatu náhrad' })).toBeInTheDocument()
    expect(screen.getByTestId('status-gsft-depositor-data')).toHaveTextContent('Chybí')
    expect(within(items[0]).getByRole('link', { name: 'Znění předpisu' })).toHaveAttribute('href', 'https://www.zakonyprolidi.cz/cs/2011-71')
    expect(within(items[0]).getByText('Garanční systém finančního trhu')).toBeInTheDocument()
  })

  it('never renders a non-https source as a link', () => {
    renderCs(register)
    expect(screen.queryByRole('link', { name: 'Specifikace úřadu' })).toBeNull()
  })

  it('links a covered duty to its catalogue report', () => {
    renderCs(register)
    expect(screen.getByRole('link', { name: 'cnb-lcr' })).toHaveAttribute('href', '/regulatory#cnb-lcr')
  })

  it('filters by coverage status', () => {
    renderCs(register)
    expect(screen.getByTestId('count-missing')).toHaveTextContent('1')
    fireEvent.click(screen.getByTestId('count-missing').closest('button')!)
    expect(screen.getAllByRole('listitem')).toHaveLength(1)
  })

  it('says the register is not baked instead of rendering an empty list', () => {
    renderCs({ available: false, areas: [], authorities: [], obligations: [] })
    expect(screen.getByRole('alert')).toHaveTextContent('nebyl zabudován')
    expect(screen.queryAllByRole('listitem')).toHaveLength(0)
  })
})

describe('getRegulatoryObligations', () => {
  const prev = process.env.OPENBANK_REGULATORY_OBLIGATIONS
  afterEach(() => { process.env.OPENBANK_REGULATORY_OBLIGATIONS = prev })

  it('reports unavailable when the baked file is missing', () => {
    process.env.OPENBANK_REGULATORY_OBLIGATIONS = '/nonexistent/regulatory-obligations.json'
    expect(getRegulatoryObligations().available).toBe(false)
  })

  it('reads a baked file', () => {
    const dir = mkdtempSync(path.join(os.tmpdir(), 'oblig-'))
    const file = path.join(dir, 'regulatory-obligations.json')
    writeFileSync(file, JSON.stringify({ areas: register.areas, authorities: register.authorities, obligations: register.obligations }))
    process.env.OPENBANK_REGULATORY_OBLIGATIONS = file
    expect(getRegulatoryObligations().obligations).toHaveLength(2)
  })

  it('the baked register from the repo covers GSFT and CEÚ', () => {
    const baked = path.resolve(process.cwd(), 'regulatory-obligations.json')
    const ids = (JSON.parse(readFileSync(baked, 'utf-8')).obligations as { id: string }[]).map(o => o.id)
    expect(ids).toEqual(expect.arrayContaining(['gsft-depositor-data', 'cnb-ceu-account-register']))
  })
})
