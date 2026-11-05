// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ProductIrEditor } from '@/components/product-studio/ProductIrEditor'
import { irSaveGate, parseIrText, revisionRequestWithBehaviour } from '@/lib/product-ir-editor'
import type { ProductRevision } from '@/lib/product-catalog-v2'

const t = (_cs: string, en: string) => en
const IR = { irVersion: 1, productCode: 'SAV', currency: 'EUR', hooks: {} }

function revision(state: ProductRevision['state'] = 'DRAFT'): ProductRevision {
  return {
    id: '30000000-0000-0000-0000-000000000001',
    offeringId: '20000000-0000-0000-0000-000000000001',
    number: 2, schemaRef: { id: 'org.openbank.banking.deposit', version: 2 }, state,
    content: { name: { en: 'Savings' }, attributes: { a: 1 }, prices: [], eligibility: [], relationships: [], documentCodes: [], behaviour: IR },
    makerId: 'maker', contentHash: 'a'.repeat(64),
    createdAt: '2026-10-29T00:00:00Z', updatedAt: '2026-10-29T00:00:00Z',
    effectiveFrom: null, effectiveTo: null, revision: 7,
  } as unknown as ProductRevision
}

const valid = { valid: true, productCode: 'SAV', irVersion: 1, revisionHash: 'b'.repeat(64), canonicalIr: '{}', problems: [] }

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('product IR editor gate', () => {
  it('refuses to save without a lint, with a stale lint, an invalid lint, or a non-DRAFT revision', () => {
    const text = JSON.stringify(IR)
    expect(irSaveGate(revision(), text, null)).toEqual({ allowed: false, reason: 'NOT_LINTED' })
    expect(irSaveGate(revision(), `${text} `, { text, result: valid })).toEqual({ allowed: false, reason: 'STALE_LINT' })
    expect(irSaveGate(revision(), text, { text, result: { ...valid, valid: false, revisionHash: null } }))
      .toEqual({ allowed: false, reason: 'INVALID' })
    expect(irSaveGate(revision('PUBLISHED'), text, { text, result: valid })).toEqual({ allowed: false, reason: 'NOT_DRAFT' })
    expect(irSaveGate(revision(), text, { text, result: valid })).toMatchObject({ allowed: true, revisionHash: valid.revisionHash })
  })

  it('checks JSON syntax only and leaves IR rules to the server', () => {
    expect(parseIrText('{').ok).toBe(false)
    expect(parseIrText('[]').ok).toBe(false)
    // An IR the server would refuse is still syntactically fine here: there is no second validator.
    expect(parseIrText('{"bogus":true}')).toEqual({ ok: true, ir: { bogus: true } })
  })

  it('writes only behaviour into the draft and refuses a non-DRAFT revision', () => {
    const next = { ...IR, productCode: 'SAV2' }
    const body = revisionRequestWithBehaviour(revision(), next) as unknown as Record<string, unknown>
    expect(body.behaviour).toEqual(next)
    expect(body.attributes).toEqual({ a: 1 })
    expect(body).not.toHaveProperty('state')
    expect(() => revisionRequestWithBehaviour(revision('PUBLISHED'), next)).toThrow(/DRAFT/)
  })

  it('holds no IR rules of its own: the module never imports the parser vocabulary', () => {
    const src = readFileSync(path.join(__dirname, '../lib/product-ir-editor.ts'), 'utf8')
    expect(src).not.toMatch(/irVersion|hooks|maxRules|IrLimits/)
  })
})

describe('ProductIrEditor', () => {
  it('lints on the server, saves the DRAFT with If-Match, and never calls publish, approve or sign', async () => {
    const calls: Array<{ url: string; method: string; body: unknown; headers: Headers }> = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit) => {
      calls.push({ url, method: String(init.method), body: init.body ? JSON.parse(String(init.body)) : undefined, headers: new Headers(init.headers) })
      const payload = url.includes('/product-ir/lint') ? valid : revision()
      return new Response(JSON.stringify(payload), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }))
    const onSaved = vi.fn()
    render(<ProductIrEditor revision={revision()} t={t} onSaved={onSaved} />)

    const save = screen.getByRole('button', { name: /Save IR to draft/ })
    expect(save).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: /Lint IR/ }))
    await screen.findByTestId('ir-lint-valid')
    expect(save).toBeEnabled()

    fireEvent.change(screen.getByLabelText('IR document'), { target: { value: JSON.stringify({ ...IR, currency: 'CZK' }) } })
    expect(screen.getByTestId('ir-lint-stale')).toBeInTheDocument()
    expect(save).toBeDisabled()

    fireEvent.click(screen.getByRole('button', { name: /Lint IR/ }))
    await screen.findByTestId('ir-lint-valid')
    fireEvent.click(save)
    await waitFor(() => expect(onSaved).toHaveBeenCalled())

    const lint = calls.filter(c => c.url.includes('/product-ir/lint'))
    expect(lint).toHaveLength(2)
    expect(lint[1].body).toEqual({ ir: { ...IR, currency: 'CZK' } })
    const write = calls.find(c => c.method === 'PUT')!
    expect(write.url).toContain(`/offerings/${revision().offeringId}/revisions/${revision().id}`)
    expect(write.headers.get('If-Match')).toBe('"7"')
    expect((write.body as Record<string, unknown>).behaviour).toEqual({ ...IR, currency: 'CZK' })
    expect(calls.some(c => /publish|approv|sign/.test(c.url))).toBe(false)
  })

  it('shows the server problems for an invalid IR and keeps Save disabled', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({
      valid: false, problems: [{ path: '/bogus', message: 'unknown key' }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } })))
    render(<ProductIrEditor revision={revision()} t={t} onSaved={vi.fn()} />)
    fireEvent.click(screen.getByRole('button', { name: /Lint IR/ }))
    expect(await screen.findByTestId('ir-lint-problems')).toHaveTextContent('/bogus unknown key')
    expect(screen.getByRole('button', { name: /Save IR to draft/ })).toBeDisabled()
  })

  it('is read-only for a published revision', () => {
    render(<ProductIrEditor revision={revision('PUBLISHED')} t={t} onSaved={vi.fn()} />)
    expect(screen.getByLabelText('IR document')).toBeDisabled()
    expect(screen.getByRole('button', { name: /Lint IR/ })).toBeDisabled()
  })
})

// The committed reference IR is what Studio edits: keep the fixture honest about the real shape.
describe('reference IR', () => {
  it('every committed openbank-products IR is a JSON object the editor accepts', () => {
    const dir = path.join(__dirname, '../../../openbank-products/ir')
    for (const name of ['current-account', 'savings-fixed', 'term-deposit']) {
      expect(parseIrText(readFileSync(path.join(dir, `${name}.json`), 'utf8')).ok).toBe(true)
    }
  })
})
