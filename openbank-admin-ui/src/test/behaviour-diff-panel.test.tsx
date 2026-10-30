// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { BehaviourDiffPanel } from '@/components/product-studio/BehaviourDiffPanel'
import { BEHAVIOUR_DIFF_SCHEMA, behaviourDiffVerdict, samplePortfolio } from '@/lib/behaviour-diff'
import type { ProductRevision } from '@/lib/product-catalog-v2'

const t = (_cs: string, en: string) => en

function revision(behaviour: unknown = { irVersion: 1, productCode: 'SAV', currency: 'EUR', hooks: {} }): ProductRevision {
  return {
    id: '30000000-0000-0000-0000-000000000001', offeringId: '20000000-0000-0000-0000-000000000001',
    number: 2, schemaRef: { id: 'org.openbank.banking.deposit', version: 2 }, state: 'DRAFT',
    content: { name: { en: 'Savings' }, attributes: {}, prices: [], eligibility: [], relationships: [], documentCodes: [], behaviour },
    makerId: 'maker', contentHash: 'a'.repeat(64), createdAt: '2026-10-29T00:00:00Z', updatedAt: '2026-10-29T00:00:00Z',
    effectiveFrom: null, effectiveTo: null, revision: 7,
  } as unknown as ProductRevision
}

const changed = {
  schema: BEHAVIOUR_DIFF_SCHEMA, valid: true, identical: false, changedAccounts: 2,
  revisionA: { productCode: 'SAV', revisionHash: '1'.repeat(64), irVersion: 1 },
  revisionB: { productCode: 'SAV', revisionHash: '2'.repeat(64), irVersion: 1 },
  range: { from: '2026-01-01', to: '2026-03-31', zone: 'UTC', days: 90 }, portfolio: { accounts: 2, seed: null },
  materialityThreshold: '1.000000000000',
  aggregate: {
    outcome: { a: '10.000000000000', b: '14.000000000000', delta: '4.000000000000' },
    posted: [{ ruleId: 'scheduled.capitalise', accountingClass: 'CUSTOMER_DEPOSIT', a: { debit: '0', credit: '10', net: '10' }, b: { debit: '0', credit: '14', net: '14' }, deltaNet: '4.000000000000' }],
    instructionCounts: [{ ruleId: 'scheduled.fee', kind: 'Post', a: 3, b: 0, delta: -3 }],
  },
  signChanged: ['sample-low'], thresholdCrossed: ['sample-high'],
  accounts: [
    {
      account: 'sample-high', outcome: { a: '8.000000000000', b: '11.500000000000', delta: '3.500000000000' },
      finalBalance: { a: '20008.000000000000', b: '20011.500000000000', delta: '3.500000000000' },
      rejectedPostings: { a: 0, b: 0 }, signChanged: false, thresholdCrossed: true, posted: [], instructionCounts: [],
    },
    {
      account: 'sample-low', outcome: { a: '-0.500000000000', b: '0.500000000000', delta: '1.000000000000' },
      finalBalance: { a: '499.500000000000', b: '500.500000000000', delta: '1.000000000000' },
      rejectedPostings: { a: 1, b: 2 }, signChanged: true, thresholdCrossed: false, posted: [], instructionCounts: [],
    },
  ],
}

const respond = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

/** The sample portfolio fills in after mount; clicking before that hits a disabled button. */
async function runBacktest() {
  const button = await screen.findByRole('button', { name: /Run backtest/ })
  await waitFor(() => expect(button).toBeEnabled())
  fireEvent.click(button)
}

describe('behaviourDiffVerdict', () => {
  it('never presents an unconserved replay or an unknown schema as a result', () => {
    expect(behaviourDiffVerdict({ ...changed, valid: false, identical: true })).toEqual({ kind: 'invalid' })
    expect(behaviourDiffVerdict({ ...changed, schema: 'openbank.product-behaviour-diff.v2' }).kind).toBe('unknown-schema')
    expect(behaviourDiffVerdict(null).kind).toBe('unknown-schema')
    expect(behaviourDiffVerdict({ ...changed, identical: true }).kind).toBe('identical')
    expect(behaviourDiffVerdict(changed).kind).toBe('changed')
  })

  it('offers a starting portfolio in the golden steps grammar obp reads, with a real customer movement', () => {
    const doc = JSON.parse(samplePortfolio('CZK', '2026-01-01')) as {
      accounts: Array<{ state: { currency: string }; steps: Array<{ at: string; posting?: { kind: string } }> }>
    }
    expect(doc.accounts.every(a => a.state.currency === 'CZK' && Array.isArray(a.steps))).toBe(true)
    expect(doc.accounts.flatMap(a => a.steps).filter(step => step.posting).map(step => step.at)).toEqual(['2026-01-10T10:00:00Z'])
  })
})

describe('BehaviourDiffPanel', () => {
  it('runs the catalog backtest for this revision and renders its document, approving nothing', async () => {
    const calls: Array<{ url: string; method: string; body: unknown }> = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      calls.push({ url, method: String(init.method), body: init.body ? JSON.parse(String(init.body)) : undefined })
      return respond(200, { diff: changed, canonicalJson: '{}' })
    }))
    render(<BehaviourDiffPanel revision={revision()} t={t} />)
    await runBacktest()
    const view = await screen.findByTestId('behaviour-diff-changed')
    expect(view).toHaveTextContent('2 / 2')
    expect(view).toHaveTextContent('4.000000000000')
    expect(within(screen.getByTestId('behaviour-diff-posted')).getByText('scheduled.capitalise')).toBeInTheDocument()
    // Per account: id, before/after and delta, exactly as the server rendered them (no client rounding).
    const rows = within(screen.getByTestId('behaviour-diff-accounts')).getAllByRole('row').slice(1)
    expect(rows.map(row => row.textContent)).toEqual([
      expect.stringContaining('sample-high'), expect.stringContaining('sample-low'),
    ])
    expect(rows[1]).toHaveTextContent('-0.500000000000')
    expect(rows[1]).toHaveTextContent('0.500000000000')
    expect(rows[1]).toHaveTextContent('1 / 2')
    // Which accounts, not just how many.
    expect(screen.getByTestId('behaviour-diff-sign-changed')).toHaveTextContent('sample-low')
    expect(screen.getByTestId('behaviour-diff-threshold')).toHaveTextContent('sample-high')
    expect(within(screen.getByTestId('behaviour-diff-counts')).getByText('scheduled.fee').closest('tr')).toHaveTextContent('30-3')
    expect(calls).toHaveLength(1)
    expect(calls[0].method).toBe('POST')
    expect(calls[0].url).toContain('/offerings/20000000-0000-0000-0000-000000000001/revisions/30000000-0000-0000-0000-000000000001/behaviour-diff')
    expect((calls[0].body as { portfolio: { accounts: unknown[] } }).portfolio.accounts).toHaveLength(3)
    expect(calls.some(c => /approv|publish|sign/.test(c.url))).toBe(false)
  })

  it('shows an unconserved replay as a defect, not as "no change"', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => respond(200, { diff: { ...changed, valid: false, identical: true }, canonicalJson: '{}' })))
    render(<BehaviourDiffPanel revision={revision()} t={t} />)
    await runBacktest()
    expect(await screen.findByTestId('behaviour-diff-error')).toHaveTextContent('do not approve')
    expect(screen.queryByTestId('behaviour-diff-identical')).toBeNull()
  })

  it('explains a missing published baseline calmly, without a raw status', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => respond(404, { error: 'CATALOG_NOT_FOUND' })))
    render(<BehaviourDiffPanel revision={revision()} t={t} />)
    await runBacktest()
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('no published revision'))
    expect(screen.getByRole('status')).not.toHaveTextContent('404')
  })

  it('drops a result that lands after the operator switched to another revision', async () => {
    let resolve: (value: Response) => void = () => {}
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(r => { resolve = r })))
    const first = revision()
    const { rerender } = render(<BehaviourDiffPanel revision={first} t={t} />)
    await runBacktest()
    const second = { ...first, id: '30000000-0000-0000-0000-000000000002', revision: 8 } as ProductRevision
    rerender(<BehaviourDiffPanel revision={second} t={t} />)
    // The switch's reset runs first; the stale response lands afterwards, as a slow backtest would.
    await new Promise(r => setTimeout(r, 30))
    resolve(respond(200, { diff: changed, canonicalJson: '{}' }))
    // Give the stale promise every chance to land.
    await new Promise(r => setTimeout(r, 50))
    expect(screen.queryByTestId('behaviour-diff-changed')).toBeNull()
    expect(screen.queryByRole('status')).toBeNull()
    // ...and the new revision's backtest is not blocked by the old one's spinner.
    expect(screen.getByRole('button', { name: /Run backtest/ })).toBeEnabled()
  })

  it('shows, per account, which rule moved it and by how much', async () => {
    const perAccount = {
      ...changed,
      accounts: [{
        ...changed.accounts[0],
        posted: [{ ruleId: 'scheduled.capitalise', accountingClass: 'CUSTOMER_DEPOSIT', a: { debit: '0', credit: '8', net: '8.000000000000' }, b: { debit: '0', credit: '11.5', net: '11.500000000000' }, deltaNet: '3.500000000000' }],
        instructionCounts: [{ ruleId: 'scheduled.fee', kind: 'Post', a: 1, b: 0, delta: -1 }],
      }],
    }
    vi.stubGlobal('fetch', vi.fn(async () => respond(200, { diff: perAccount, canonicalJson: '{}' })))
    render(<BehaviourDiffPanel revision={revision()} t={t} />)
    await runBacktest()
    const detail = await screen.findByTestId('behaviour-diff-account-sample-high')
    expect(detail).toHaveTextContent('scheduled.capitalise')
    expect(detail).toHaveTextContent('3.500000000000')
    expect(detail).toHaveTextContent('scheduled.fee')
    expect(detail).toHaveTextContent('-1')
  })

  it('is absent for a revision without product IR', () => {
    render(<BehaviourDiffPanel revision={revision(null)} t={t} />)
    expect(screen.queryByTestId('behaviour-diff-panel')).toBeNull()
  })
})
