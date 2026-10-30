// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { BehaviourDiffPanel } from '@/components/product-studio/BehaviourDiffPanel'
import { BEHAVIOUR_DIFF_SCHEMA, behaviourDiffVerdict } from '@/lib/behaviour-diff'
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
    instructionCounts: [],
  },
  signChanged: [], thresholdCrossed: ['sample-high'],
}

const respond = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('behaviourDiffVerdict', () => {
  it('never presents an unconserved replay or an unknown schema as a result', () => {
    expect(behaviourDiffVerdict({ ...changed, valid: false, identical: true })).toEqual({ kind: 'invalid' })
    expect(behaviourDiffVerdict({ ...changed, schema: 'openbank.product-behaviour-diff.v2' }).kind).toBe('unknown-schema')
    expect(behaviourDiffVerdict(null).kind).toBe('unknown-schema')
    expect(behaviourDiffVerdict({ ...changed, identical: true }).kind).toBe('identical')
    expect(behaviourDiffVerdict(changed).kind).toBe('changed')
  })

  it('holds no replay logic: amounts are the server strings, never recomputed', () => {
    const src = readFileSync(path.join(__dirname, '../lib/behaviour-diff.ts'), 'utf8')
    expect(src).not.toMatch(/parseFloat|Number\(|toFixed|\bMath\./)
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
    fireEvent.click(await screen.findByRole('button', { name: /Run backtest/ }))
    const view = await screen.findByTestId('behaviour-diff-changed')
    expect(view).toHaveTextContent('2 / 2')
    expect(view).toHaveTextContent('4.000000000000')
    expect(view).toHaveTextContent('scheduled.capitalise')
    expect(calls).toHaveLength(1)
    expect(calls[0].method).toBe('POST')
    expect(calls[0].url).toContain('/offerings/20000000-0000-0000-0000-000000000001/revisions/30000000-0000-0000-0000-000000000001/behaviour-diff')
    expect((calls[0].body as { portfolio: { accounts: unknown[] } }).portfolio.accounts).toHaveLength(2)
    expect(calls.some(c => /approv|publish|sign/.test(c.url))).toBe(false)
  })

  it('shows an unconserved replay as a defect, not as "no change"', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => respond(200, { diff: { ...changed, valid: false, identical: true }, canonicalJson: '{}' })))
    render(<BehaviourDiffPanel revision={revision()} t={t} />)
    fireEvent.click(await screen.findByRole('button', { name: /Run backtest/ }))
    expect(await screen.findByTestId('behaviour-diff-error')).toHaveTextContent('do not approve')
    expect(screen.queryByTestId('behaviour-diff-identical')).toBeNull()
  })

  it('explains a missing published baseline calmly, without a raw status', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => respond(404, { error: 'CATALOG_NOT_FOUND' })))
    render(<BehaviourDiffPanel revision={revision()} t={t} />)
    fireEvent.click(await screen.findByRole('button', { name: /Run backtest/ }))
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('no published revision'))
    expect(screen.getByRole('status')).not.toHaveTextContent('404')
  })

  it('is absent for a revision without product IR', () => {
    render(<BehaviourDiffPanel revision={revision(null)} t={t} />)
    expect(screen.queryByTestId('behaviour-diff-panel')).toBeNull()
  })
})
