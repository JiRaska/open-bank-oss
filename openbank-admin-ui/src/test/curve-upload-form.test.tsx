// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import React from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { CURVE_INDICES } from '@/components/balance-sheet/model'
import {
  INDEX_INFO, applySample, explainServerError, formProblems, isSandboxEnvironment, lastBusinessDay,
  parsePaste, parseRate, summary, toPayload, validateGrid, type FormState,
} from '@/components/balance-sheet/curveForm'
import { CurveUploadForm } from '@/components/balance-sheet/CurveUploadForm'

const base = (over: Partial<FormState> = {}): FormState => ({
  asOf: '2026-09-30', provenance: 'production', sourceId: 'cnb-czeonia', sourceOther: '',
  curves: { CZEONIA: [{ tenor: 'ON', rate: '3,50' }, { tenor: '3M', rate: '3.6' }] }, ...over,
})

describe('curve form vocabulary', () => {
  it('offers exactly the indices risk-engine accepts', () => {
    expect(INDEX_INFO.map(i => i.id).sort()).toEqual([...CURVE_INDICES].sort())
  })
})

describe('parseRate', () => {
  it('accepts a decimal comma, percent sign and spaces', () => {
    expect(parseRate('3,5')).toBe(3.5)
    expect(parseRate(' 3.50 % ')).toBe(3.5)
    expect(parseRate('-0,10')).toBe(-0.1)
  })
  it('refuses text and thousands-style input', () => {
    expect(parseRate('abc')).toBeNull()
    expect(parseRate('3,5,1')).toBeNull()
    expect(parseRate('')).toBeNull()
  })
})

describe('parsePaste', () => {
  it('reads two Excel columns (tab, decimal comma) and skips a header', () => {
    const r = parsePaste('Splatnost\tSazba\nON\t3,50\n1m\t3,55\r\n', 'CZEONIA')
    expect(r.rows.CZEONIA).toEqual([{ tenor: 'ON', rate: '3,50' }, { tenor: '1M', rate: '3,55' }])
    expect(r.skipped).toBe(1)
  })
  it('routes three-column rows to their own index', () => {
    const r = parsePaste('ESTR;ON;1,9\n€STR 1M 1.95\nPRIBOR-3M 3M 3,7', 'CZEONIA')
    expect(r.rows.ESTR).toHaveLength(2)
    expect(r.rows.PRIBOR_3M).toEqual([{ tenor: '3M', rate: '3,7' }])
    expect(r.rows.CZEONIA).toBeUndefined()
  })
})

describe('validateGrid', () => {
  it('ignores empty rows, blocks bad ones', () => {
    const v = validateGrid([
      { tenor: 'ON', rate: '3,5' }, { tenor: '', rate: '' }, { tenor: '2Y', rate: '3' },
      { tenor: 'XX', rate: '3' }, { tenor: '1M', rate: 'abc' }, { tenor: '3M', rate: '80' },
    ])
    expect(v.filled).toBe(1)
    expect(v.errors).toEqual({ 2: 'tenor-range', 3: 'tenor', 4: 'rate', 5: 'rate-range' })
  })
  it('treats 1Y and 12M as the same pillar', () => {
    expect(validateGrid([{ tenor: '12M', rate: '3' }, { tenor: '1Y', rate: '3' }]).errors).toEqual({ 1: 'duplicate' })
  })
  it('flags an inverted curve as a warning, not an error', () => {
    const v = validateGrid([{ tenor: '1Y', rate: '3,0' }, { tenor: 'ON', rate: '3,5' }, { tenor: '3M', rate: '3,4' }])
    expect(v.errors).toEqual({})
    expect(v.nonMonotonic.sort()).toEqual([0, 2])
  })
})

describe('mapping to the POST body', () => {
  it('converts percent to a decimal rate and maps the source to its stored string', () => {
    const body = toPayload(base({ curves: { CZEONIA: [{ tenor: 'on', rate: '3,50' }, { tenor: '1M', rate: '' }], ESTR: [{ tenor: '', rate: '' }] } }))
    expect(body).toEqual({ asOf: '2026-09-30', provenance: 'production', source: 'ČNB – fixing CZEONIA', curves: { CZEONIA: [{ tenor: 'ON', rate: 0.035 }] } })
  })
  it('uses the free text for "other"', () => {
    expect(toPayload(base({ sourceId: 'other', sourceOther: '  Reuters CZKFIX  ' })).source).toBe('Reuters CZKFIX')
  })
  it('summarises curves and points', () => {
    expect(summary(base({ curves: { CZEONIA: [{ tenor: 'ON', rate: '3' }], ESTR: [{ tenor: 'ON', rate: '2' }, { tenor: '1M', rate: '2' }] } }))).toEqual({ curves: 2, points: 3 })
  })
})

describe('formProblems', () => {
  it('requires date, provenance, source and a curve; refuses a future date', () => {
    expect(formProblems(base({ asOf: '', provenance: '', sourceId: '', curves: {} }), '2026-10-01')).toEqual(['asOf', 'provenance', 'source', 'no-curve'])
    expect(formProblems(base({ asOf: '2026-10-02' }), '2026-10-01')).toEqual(['asOf-future'])
    expect(formProblems(base(), '2026-10-01')).toEqual([])
  })
})

describe('sample fill', () => {
  it('forces synthetic provenance and the sandbox source, whatever was chosen', () => {
    const s = applySample(base({ provenance: 'production' }))
    expect(s.provenance).toBe('synthetic')
    expect(toPayload(s).source).toMatch(/syntetická/i)
    expect(Object.keys(s.curves).sort()).toEqual(['CZEONIA', 'ESTR'])
    expect(formProblems(s, '2026-10-01')).toEqual([])
  })
  it('refuses sample data relabelled as production', () => {
    expect(formProblems({ ...applySample(base()), provenance: 'production' }, '2026-10-01')).toContain('sample-production')
  })
  it('is hidden only in an explicit production build', () => {
    expect(isSandboxEnvironment('production')).toBe(false)
    expect(isSandboxEnvironment('sandbox')).toBe(true)
    expect(isSandboxEnvironment(undefined)).toBe(true)
  })
})

describe('dates and server errors', () => {
  it('defaults to the last weekday', () => {
    expect(lastBusinessDay(new Date(2026, 9, 5))).toBe('2026-10-02') // Monday → Friday
    expect(lastBusinessDay(new Date(2026, 9, 1))).toBe('2026-09-30')
  })
  it('explains risk-engine 400s in Czech', () => {
    expect(explainServerError("tenor '2Y' exceeds one year", 'cs')).toMatch(/delší než 1 rok/)
    expect(explainServerError('curve CZEONIA: two quotes end on 2027-09-30', 'cs')).toMatch(/stejný den/)
    expect(explainServerError('something new', 'cs')).toBe('risk-engine sadu odmítl: something new')
  })
})

describe('<CurveUploadForm />', () => {
  afterEach(cleanup)
  const mount = (sandbox: boolean, onSubmit = vi.fn(async () => true)) => {
    render(<LanguageProvider><CurveUploadForm sandbox={sandbox} busy={false} onSubmit={onSubmit} /></LanguageProvider>)
    return onSubmit
  }

  it('hides the sample button outside the sandbox', () => {
    mount(false)
    expect(screen.queryByRole('button', { name: /ukázkovými|sample values/i })).toBeNull()
  })

  it('fills the sample, shows the summary and submits a synthetic set', async () => {
    const onSubmit = mount(true)
    fireEvent.click(screen.getByRole('button', { name: /ukázkovými|sample values/i }))
    expect(screen.getByText(/2 (křivky|curves), 12 (bodů|points)/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: /Nahrát sadu|Upload set/ }))
    await vi.waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect((onSubmit.mock.calls[0] as unknown as [FormState])[0].provenance).toBe('synthetic')
  })

  it('pastes two columns from the clipboard into the active grid', () => {
    mount(false)
    fireEvent.click(screen.getByRole('button', { name: /CZEONIA/ }))
    const grid = screen.getByRole('table')
    fireEvent.paste(grid, { clipboardData: { getData: () => '2W\t3,52\n9M\t3,66' } })
    expect((screen.getAllByRole('textbox').map(e => (e as HTMLInputElement).value))).toEqual(expect.arrayContaining(['2W', '3,52', '9M', '3,66']))
  })
})
