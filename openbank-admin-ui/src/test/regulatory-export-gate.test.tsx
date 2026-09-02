// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

/**
 * A regulatory export must be BLOCKED on incomplete, missing or unbalanced data, and
 * PERMITTED on complete data (issue #5904).
 *
 * Before this gate the CSV/JSON buttons were disabled only while `status === 'loading'`, so an
 * operator could download a file whose every value read "Zdroj dat není dostupný" — same
 * filename shape, same headers, same columns as a real return. A regulatory filing is the
 * artefact people act on, which makes a successful-looking export over unusable data worse
 * than no export at all.
 *
 * "Incomplete" is NOT invented here — it is `isDataGap` / `hasDataGaps`, contract-required
 * fields in openbank-finrep-service's openapi.yaml, documented there as values the platform
 * "could NOT honestly derive" which "MUST NOT be read as an attested zero balance".
 *
 * Deliberately out of scope, per the issue's own boundary: XBRL/DPM rendering and authenticated
 * ČNB submission (#5914). Nothing here adds a submit path.
 */

import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RegulatoryPage from '@/app/regulatory/page'
import { LanguageProvider } from '@/lib/i18n/LanguageContext'
import { blockReasonCopy, evaluateExportReadiness } from '@/lib/regulatory/exportReadiness'

afterEach(() => vi.unstubAllGlobals())

const cell = (over: Record<string, unknown> = {}) => ({
  rowRef: 'r010', colRef: 'c010', value: 100, currency: 'CZK', isDataGap: false, ...over,
})

// ── The decision function, exercised directly ────────────────────────────────
describe('evaluateExportReadiness', () => {
  it('permits a complete, balanced, gap-free set of templates', () => {
    expect(evaluateExportReadiness({
      status: 'ready',
      templates: [{ templateId: 'F01.01', cells: [cell()], isBalanced: true, hasDataGaps: false }],
    })).toEqual({ ok: true })
  })

  it.each([
    ['not_loaded', { status: 'loading' as const }],
    ['not_loaded', { status: 'idle' as const }],
    ['no_data_source', { status: 'unsupported' as const }],
    ['source_unavailable', { status: 'unavailable' as const, kind: 'unreachable' }],
    ['no_closed_periods', { status: 'no-periods' as const }],
  ])('blocks with %s when the data was never obtained', (reason, data) => {
    const verdict = evaluateExportReadiness(data)
    expect(verdict.ok).toBe(false)
    expect(verdict.ok === false && verdict.reason).toBe(reason)
  })

  it('blocks as INCOMPLETE on a cell flagged isDataGap', () => {
    const verdict = evaluateExportReadiness({
      status: 'ready',
      templates: [{ templateId: 'C_01.00', cells: [cell({ isDataGap: true, gapReason: 'no capital GL accounts' })] }],
    })
    expect(verdict).toEqual({ ok: false, reason: 'data_gaps', templateIds: ['C_01.00'] })
  })

  it('shows live values for review but blocks exporting them as a sealed return', () => {
    const verdict = evaluateExportReadiness({
      status: 'ready',
      evidence: 'LIVE_PREVIEW',
      templates: [{ templateId: 'F01.01', cells: [cell()], isBalanced: true, hasDataGaps: false }],
    })
    expect(verdict).toEqual({ ok: false, reason: 'provisional_data', templateIds: ['F01.01'] })
  })

  it('blocks as INCOMPLETE on the derived hasDataGaps flag alone', () => {
    const verdict = evaluateExportReadiness({
      status: 'ready',
      templates: [{ templateId: 'C_01.00', cells: [cell()], hasDataGaps: true }],
    })
    expect(verdict.ok === false && verdict.reason).toBe('data_gaps')
  })

  it('blocks as UNBALANCED when a template says its identity does not hold and carries no verdict', () => {
    // A deployment predating #6163 serves `isBalanced` alone. It must still block, under the
    // plain accounting reason.
    const verdict = evaluateExportReadiness({
      status: 'ready',
      templates: [{ templateId: 'F01.01', cells: [cell()], isBalanced: false }],
    })
    expect(verdict).toEqual({ ok: false, reason: 'unbalanced', templateIds: ['F01.01'] })
  })

  // ── balanceVerdict (#6163 / issue #6011): three non-balanced verdicts, three defects ──────
  it.each([
    ['AGREED_IMBALANCED' as const, 'unbalanced'],
    ['SOURCES_DISAGREE' as const, 'balance_sources_disagree'],
    ['LEDGER_FLAG_ABSENT' as const, 'ledger_verdict_absent'],
  ])('blocks %s under its own reason %s', (balanceVerdict, reason) => {
    const verdict = evaluateExportReadiness({
      status: 'ready',
      templates: [{ templateId: 'F01.01', cells: [cell()], isBalanced: false, balanceVerdict }],
    })
    expect(verdict).toEqual({ ok: false, reason, templateIds: ['F01.01'] })
  })

  it('permits AGREED_BALANCED — the only verdict for which isBalanced is true', () => {
    expect(evaluateExportReadiness({
      status: 'ready',
      templates: [{ templateId: 'F01.01', cells: [cell()], isBalanced: true, balanceVerdict: 'AGREED_BALANCED', hasDataGaps: false }],
    })).toEqual({ ok: true })
  })

  it('names only the templates carrying the reported verdict, not every non-balanced one', () => {
    // Otherwise an operator is sent to look at F02.00 for a defect it does not have.
    const verdict = evaluateExportReadiness({
      status: 'ready',
      templates: [
        { templateId: 'F02.00', cells: [cell()], isBalanced: false, balanceVerdict: 'AGREED_IMBALANCED' },
        { templateId: 'F01.01', cells: [cell()], isBalanced: false, balanceVerdict: 'SOURCES_DISAGREE' },
      ],
    })
    expect(verdict).toEqual({ ok: false, reason: 'balance_sources_disagree', templateIds: ['F01.01'] })
  })

  it('reports an absent ledger verdict ahead of disagreeing sources', () => {
    // Both are claims about the evidence rather than the books; a missing flag is the more
    // fundamental of the two, because with no flag there is nothing left to disagree with.
    const verdict = evaluateExportReadiness({
      status: 'ready',
      templates: [
        { templateId: 'F01.01', cells: [cell()], isBalanced: false, balanceVerdict: 'SOURCES_DISAGREE' },
        { templateId: 'F02.00', cells: [cell()], isBalanced: false, balanceVerdict: 'LEDGER_FLAG_ABSENT' },
      ],
    })
    expect(verdict).toEqual({ ok: false, reason: 'ledger_verdict_absent', templateIds: ['F02.00'] })
  })

  it('blocks on isBalanced === false even when the verdict claims agreement', () => {
    // finrep computes `isBalanced` as `verdict == AGREED_BALANCED`, so this pair cannot come
    // from a correct producer. The gate must still refuse rather than trust the friendlier field.
    const verdict = evaluateExportReadiness({
      status: 'ready',
      templates: [{ templateId: 'F01.01', cells: [cell()], isBalanced: false, balanceVerdict: 'AGREED_BALANCED' }],
    })
    expect(verdict.ok).toBe(false)
    expect(verdict.ok === false && verdict.reason).toBe('unbalanced')
  })

  it('blocks as MISSING when a template renders no cells at all', () => {
    // The contract renders every row always, so zero cells is a malformed payload, not an
    // empty period.
    expect(evaluateExportReadiness({ status: 'ready', templates: [{ templateId: 'F02.00', cells: [] }] }))
      .toEqual({ ok: false, reason: 'missing_cells', templateIds: ['F02.00'] })
  })

  it('reports unbalanced ahead of data gaps when a template is both', () => {
    const verdict = evaluateExportReadiness({
      status: 'ready',
      templates: [{ templateId: 'F01.01', cells: [cell({ isDataGap: true })], isBalanced: false, hasDataGaps: true }],
    })
    expect(verdict.ok === false && verdict.reason).toBe('unbalanced')
  })
})

// ── Operator copy: each verdict must say what is actually wrong ──────────────
describe('blockReasonCopy for the balance verdicts', () => {
  it.each(['cs', 'en'] as const)('does not call an evidence defect an accounting one (%s)', lang => {
    const disagree = blockReasonCopy('balance_sources_disagree', ['F01.01'], lang)
    const absent = blockReasonCopy('ledger_verdict_absent', ['F01.01'], lang)
    const imbalanced = blockReasonCopy('unbalanced', ['F01.01'], lang)

    // The old copy said "the return does not balance / výkaz nevychází" for all three. That
    // sentence is only true of AGREED_IMBALANCED.
    const doesNotBalance = lang === 'cs' ? 'nevychází' : 'does not balance'
    expect(imbalanced.title).toContain(doesNotBalance)
    expect(disagree.title).not.toContain(doesNotBalance)
    expect(absent.title).not.toContain(doesNotBalance)

    // Each names the verdict it is about, so the message can be traced to the field.
    expect(disagree.detail).toContain('SOURCES_DISAGREE')
    expect(absent.detail).toContain('LEDGER_FLAG_ABSENT')
    expect(imbalanced.detail).toContain('AGREED_IMBALANCED')

    // All three are still a block.
    for (const copy of [disagree, absent, imbalanced]) {
      expect(copy.title).toContain(lang === 'cs' ? 'zablokován' : 'blocked')
      expect(copy.detail).toContain('F01.01')
    }
  })
})

// ── The surface: the buttons and the handler must agree with the verdict ─────
/**
 * Open the preview for CNB FINREP specifically — one of only two catalogue reports with a wired
 * data source. Clicking whichever preview button happens to be first would land on a
 * catalogue-only report and block with `no_data_source` every time, which would make the
 * data_gaps / unbalanced cases untestable while still looking green.
 */
const FINREP_CARD = 'CNB — Finanční výkazy (FINREP)'

async function openPreview(fetchImpl: (url: string) => Promise<Response>) {
  vi.stubGlobal('fetch', vi.fn((url: string) => url.includes('/api/v1/finrep/periods')
    ? Promise.resolve(new Response(JSON.stringify({ latest: '2026-06-30', periods: ['2026-06-30'] }), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      }))
    : fetchImpl(url)))
  render(<LanguageProvider><RegulatoryPage /></LanguageProvider>)
  const card = (await screen.findAllByText(FINREP_CARD))[0].closest('.card')
  if (!card) throw new Error(`no .card ancestor for "${FINREP_CARD}" — the report card markup changed`)
  fireEvent.click(within(card as HTMLElement).getByRole('button', { name: /Preview export|Náhled exportu/i }))
}

const template = (over: Record<string, unknown>) =>
  new Response(JSON.stringify({ templateId: 'F01.01', period: '2026-06-30', cells: [cell()], isBalanced: true, hasDataGaps: false, ...over }),
    { status: 200, headers: { 'content-type': 'application/json' } })

describe('Regulatory export gate — the surface', () => {
  it('PERMITS export when every template is complete and balanced', async () => {
    await openPreview(async () => template({}))

    await waitFor(() => {
      expect(screen.queryByTestId('export-blocked')).not.toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: /JSON/i })).toBeEnabled()
    expect(screen.getByRole('button', { name: /CSV/i })).toBeEnabled()
  })

  it('BLOCKS export, with a named reason, when a cell is a flagged data gap', async () => {
    await openPreview(async () => template({ cells: [cell({ isDataGap: true, gapReason: 'no capital GL accounts' })], hasDataGaps: true }))

    const banner = await screen.findByTestId('export-blocked')
    expect(banner).toHaveAttribute('data-block-reason', 'data_gaps')
    expect(screen.getByRole('button', { name: /JSON/i })).toBeDisabled()
    expect(screen.getByRole('button', { name: /CSV/i })).toBeDisabled()
  })

  it('BLOCKS export when the data source is unreachable', async () => {
    await openPreview(async () => new Response(JSON.stringify({ error: 'upstream_unreachable' }), { status: 502, headers: { 'content-type': 'application/json' } }))

    const banner = await screen.findByTestId('export-blocked')
    expect(banner).toHaveAttribute('data-block-reason', 'source_unavailable')
    expect(screen.getByRole('button', { name: /JSON/i })).toBeDisabled()
  })

  it('produces NO file when a blocked export is invoked anyway', async () => {
    // The disabled attribute is an affordance, not a control — the handler must refuse too.
    const createObjectURL = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:stub')
    vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    await openPreview(async () => template({ cells: [cell({ isDataGap: true })], hasDataGaps: true }))
    await screen.findByTestId('export-blocked')

    const jsonButton = screen.getByRole('button', { name: /JSON/i })
    fireEvent.click(jsonButton)
    // Fire the handler directly too, past the disabled attribute.
    jsonButton.removeAttribute('disabled')
    fireEvent.click(jsonButton)

    expect(createObjectURL).not.toHaveBeenCalled()
  })
})
